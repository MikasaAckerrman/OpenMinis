#!/usr/bin/env python3
"""channel.py — единый канал к сайтам с антибот-защитой: троттлинг, backoff, UA.

Зачем: замер показал, что «нестабильность» Авито — это не случайность канала, а
накопительный лимит по IP на стороне QRATOR (`server: QRATOR` в заголовках).
18 запросов за две минуты выключили доступ полностью: сначала 429 на карточках,
потом 429 и на выдаче, и три пробы с паузой 60 с подряд не помогли. Ретраи такой
штраф не лечат, а усугубляют — лечится только соблюдением темпа.

Состояние живёт в файле под flock, потому что:
  - каждый shell_execute это отдельный процесс, счётчик в памяти обнулялся бы;
  - без блокировки два процесса читают «ждать 0» и стреляют одновременно
    (проверено: flock в этой песочнице работает и сериализует, 1.00 с ожидания).

Слот РЕЗЕРВИРУЕТСЯ под блокировкой: в состояние сразу пишется время будущего
запроса, поэтому второй процесс видит занятое окно и встаёт за ним в очередь.

  from channel import Channel
  ch = Channel('avito')
  r = ch.get(url)                    # ждёт свою очередь, ретраит с backoff

  wait = ch.reserve()                # для внешнего клиента (браузер): сколько спать
  ch.report(status, size)            # и чем закончилось

  python3 channel.py stats avito     что накопилось
  python3 channel.py reset avito     сбросить счётчики
"""
import copy, fcntl, json, os, random, re, secrets, subprocess, sys, time

STATE_DIR = os.environ.get('WD_STATE_DIR', '/var/minis/shared/_data/_channel')

# Профили каналов. Значения не выдуманы — из замера probe_ua.sh (3 пробы на UA):
#   выдача Авито:   curl/8.14.1 3/3, curl/7.29.0 2/3, curl/7.68.0 1/3,
#                   Wget/Googlebot/Chrome 0/3
#   карточка Авито: ВСЕ UA 0/3 через curl -> карточки берём только браузером
PROFILES = {
    'avito': {
        'ua': 'curl/8.14.1',
        'min_gap': 6.0,        # секунд между запросами
        'burst': 5,            # столько подряд с min_gap, потом длинная пауза
        'burst_pause': 45.0,
        'hourly_cap': 220,     # запросов в час, с запасом от наблюдённого штрафа
        'penalty_base': 90.0,  # после 429 ждать столько, дальше вдвое
        'penalty_max': 900.0,
        'block_codes': (429, 439, 403, 503),
        'min_ok_size': 100_000,   # заглушка WAF ~8-28 КБ, живая страница > 100 КБ
        'lease_ttl': 90.0,        # запросы curl ограничены 25 с + запас
    },
    # Браузерный канал к Авито: те же лимиты по IP, но размер тела другой —
    # карточка через fetch отдаёт ~600 КБ, а вот 8-28 КБ это заглушка.
    'avito-browser': {
        # Один ресурс/IP с `avito` (curl), не отдельная квота клиента.
        'state_name': 'avito',
        'ua': '(браузерный)', 'min_gap': 6.0, 'burst': 5, 'burst_pause': 45.0,
        'hourly_cap': 220, 'penalty_base': 90.0, 'penalty_max': 900.0,
        'block_codes': (429, 439, 403, 503), 'min_ok_size': 50_000,
        'lease_ttl': 120.0,
    },
    'avito-images': {
        # CDN-фото тоже считаем в бюджете Avito: прямой curl из photo.py не
        # должен обходить общий IP limiter. Для бинарного ответа min_ok_size
        # не применяется, проверяется только HTTP/WAF status.
        'state_name': 'avito', 'ua': 'curl/8.14.1', 'min_gap': 6.0,
        'burst': 5, 'burst_pause': 45.0, 'hourly_cap': 220,
        'penalty_base': 90.0, 'penalty_max': 900.0,
        'block_codes': (429, 439, 403, 503), 'min_ok_size': 0,
        'lease_ttl': 90.0,
    },
    'shops': {
        'ua': ('Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 '
               '(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'),
        'min_gap': 3.0, 'burst': 6, 'burst_pause': 12.0, 'hourly_cap': 300,
        'penalty_base': 30.0, 'penalty_max': 240.0,
        'block_codes': (429, 403, 503, 498, 401), 'min_ok_size': 20_000,
    },
    'default': {
        'ua': 'curl/8.14.1', 'min_gap': 2.0, 'burst': 10, 'burst_pause': 10.0,
        'hourly_cap': 600, 'penalty_base': 30.0, 'penalty_max': 300.0,
        'block_codes': (429, 403, 503), 'min_ok_size': 0,
    },
}

FRESH = {'next_free': 0.0, 'last': 0.0, 'burst': 0, 'hour_start': 0.0,
         'hour_count': 0, 'penalty_until': 0.0, 'penalty_step': 0,
         'ok': 0, 'blocked': 0, 'transport': 0, 'http_errors': 0,
         'other_http': 0, 'lease_seq': 0, 'leases': {}}


class Resp:
    def __init__(self, status, body, took, attempts, waited,
                 blocked=False, throttled=False):
        self.status = status
        self.body = body
        self.took = took
        self.attempts = attempts
        self.waited = waited        # сколько секунд ушло на ожидание очереди
        self.blocked = blocked      # WAF/stub, а не обычный HTTP error
        self.throttled = throttled  # локальный limiter не дал слот

    @property
    def ok(self):
        return self.status == 200 and bool(self.body) and not self.blocked and not self.throttled

    def __repr__(self):
        return '<Resp %s %d байт, попыток %d, ждал %.1f с>' % (
            self.status, len(self.body or ''), self.attempts, self.waited)


class Channel:
    def __init__(self, name='default', profile=None):
        # name входит в имя файла состояния. Разрешаем только безопасный slug:
        # раньше Channel('../escape') принимался и path traversal выходил из
        # STATE_DIR. Пробелы и слэши здесь не нужны — для нового сайта можно
        # использовать `site-kind` или `site_kind`.
        if not isinstance(name, str) or not re.fullmatch(r'[A-Za-z0-9_-]+', name):
            raise ValueError('небезопасное имя канала: %r' % (name,))
        self.name = name
        self.cfg = dict(PROFILES.get(name, PROFILES['default']))
        if profile:
            self.cfg.update(profile)
        os.makedirs(STATE_DIR, exist_ok=True)
        # state_name — имя общего бюджета ресурса, а не клиента. Например,
        # `avito` (curl) и `avito-browser` (fetch) должны делить один IP-лимит.
        # По умолчанию сохраняем старые имена для независимых сайтов.
        state_name = self.cfg.pop('state_name', name)
        if not isinstance(state_name, str) or not re.fullmatch(r'[A-Za-z0-9_-]+', state_name):
            raise ValueError('небезопасное имя состояния: %r' % (state_name,))
        self.state_name = state_name
        self.path = os.path.join(STATE_DIR, '%s.json' % state_name)
        self.lock_path = self.path + '.lock'

    # ---------------------------------------------------------------- состояние
    class _Locked:
        """Контекст: файл состояния под flock. Читает при входе, пишет при выходе."""

        def __init__(self, ch):
            self.ch = ch
            self.fh = None
            self.st = None

        def __enter__(self):
            self.fh = open(self.ch.lock_path, 'a+')
            fcntl.flock(self.fh.fileno(), fcntl.LOCK_EX)
            self.st = self.ch._read()
            return self.st

        def __exit__(self, *exc):
            try:
                self.ch._write(self.st)
            finally:
                fcntl.flock(self.fh.fileno(), fcntl.LOCK_UN)
                self.fh.close()
            return False

    def _read(self):
        # В FRESH есть вложенный leases; поверхностная копия загрязняла
        # состояние соседних тестовых Channel и реальных каналов.
        st = copy.deepcopy(FRESH)
        if os.path.exists(self.path):
            try:
                st.update(json.load(open(self.path)))
            except Exception:
                pass                     # битый файл не должен ронять сбор
        if not st['hour_start']:
            st['hour_start'] = time.time()
        return st

    def _write(self, st):
        tmp = self.path + '.tmp'
        json.dump(st, open(tmp, 'w'))
        os.replace(tmp, self.path)

    @staticmethod
    def _decode(raw):
        """Тело в str. Кодировку берём из meta charset, иначе utf-8 с заменой.

        Нужно потому, что часть магазинов (nix.ru) до сих пор отдаёт
        windows-1251, и слепой utf-8 ломает названия товаров.
        """
        if not raw:
            return ''
        enc = 'utf-8'
        m = re.search(br'charset=["\']?\s*([\w-]+)', raw[:4096].lower())
        if m:
            cand = m.group(1).decode('ascii', 'ignore').lower()
            if cand in ('windows-1251', 'cp1251', 'utf-8', 'koi8-r', 'iso-8859-1'):
                enc = cand
        try:
            return raw.decode(enc, 'replace')
        except LookupError:
            return raw.decode('utf-8', 'replace')

    # ---------------------------------------------------------------- очередь
    def _wait_for(self, st, now):
        """Ожидание следующего старта без изменения state.

        `next_free` — хвост уже зарезервированной очереди и хранится как
        абсолютное время. `last` — фактическое окончание предыдущего запроса.
        Оба ограничения нужны: запланированный слот не должен пересекаться с
        медленным реальным запросом.
        """
        waits = []
        if st['penalty_until'] > now:
            waits.append(st['penalty_until'] - now)
        waits.append(max(0.0, st.get('next_free', 0.0) - now))
        waits.append(max(0.0, st.get('last', 0.0) + self.cfg['min_gap'] - now))

        if st['burst'] >= self.cfg['burst'] and st['last']:
            since = now - st['last']
            if since < self.cfg['burst_pause']:
                waits.append(self.cfg['burst_pause'] - since)

        if now - st['hour_start'] <= 3600 and st['hour_count'] >= self.cfg['hourly_cap']:
            waits.append(3600 - (now - st['hour_start']))
        return max(waits) if waits else 0.0

    def reserve(self):
        """Занять слот. Возвращает, сколько секунд ждать ПЕРЕД запросом.

        Резервирование под блокировкой: next_free сдвигается в будущее, поэтому
        следующий процесс встаёт в очередь ЗА этим слотом, а не рядом с ним.
        """
        with self._Locked(self) as st:
            now = time.time()
            if now - st['hour_start'] > 3600:
                st['hour_start'], st['hour_count'] = now, 0
            # Осиротевший резерв: процесс мог умереть между reserve и report.
            # Без сброса канал простаивал бы до конца брони (замерено 3000 с).
            horizon = self.cfg['min_gap'] * 4 + max(self.cfg['burst_pause'], 60)
            if st.get('next_free', 0.0) - now > horizon:
                st['next_free'] = now
            wait = self._wait_for(st, now)
            # next_free — абсолютный хвост уже зарезервированной очереди.
            # Интервал входит в хвост здесь, а не добавляется второй раз в
            # _wait_for: первый слот свободен сейчас, второй — через gap.
            slot = now + wait
            st['next_free'] = slot + self.cfg['min_gap']
            st['burst'] = 1 if st['burst'] >= self.cfg['burst'] else st['burst'] + 1
            st['hour_count'] += 1
            return wait

    def _cleanup_leases(self, st, now, preserve=None):
        """Удалить просроченные lease, кроме active token в report()."""
        leases = st.setdefault('leases', {})
        removed = False
        for token, lease in list(leases.items()):
            if token == preserve and lease.get('active'):
                continue
            if lease.get('expires', 0) <= now:
                del leases[token]
                removed = True
        if removed:
            # Пересчитать хвост по оставшимся lease. Иначе просроченная lease
            # оставляла next_free в +3000 с и делала свежий запрос бессмысленно
            # ожидающим, хотя активных владельцев уже не было.
            tail = max((float(x.get('slot', now)) + self.cfg['min_gap']
                        for x in leases.values()), default=0.0)
            st['next_free'] = max(st.get('last', 0.0) + self.cfg['min_gap'], tail)

    def _lease_wait_for(self, st, lease, now):
        """Ожидание конкретной lease, не смешанное с legacy reserve()."""
        waits = []
        if st['penalty_until'] > now:
            waits.append(st['penalty_until'] - now)
        if now - st['hour_start'] <= 3600 and st['hour_count'] >= self.cfg['hourly_cap']:
            waits.append(3600 - (now - st['hour_start']))
        earlier = [x for x in st.setdefault('leases', {}).values()
                   if x.get('seq', 0) < lease.get('seq', 0)]
        if earlier:
            # Только незавершённые lease блокируют FIFO. Завершённая удаляется
            # report(), поэтому её слот не должен оставлять phantom wait.
            waits.append(max(0.0, max(x.get('slot', now) for x in earlier) - now))
            # Даже pending lease нельзя перепрыгнуть: её клиент может ещё
            # захватить слот. Если умер — cleanup удалит её по TTL.
            waits.append(self.cfg.get('active_poll', 1.0))
        # Если предшествующий запрос был медленнее min_gap, его slot уже мог
        # пройти, но новый запрос всё равно должен ждать от фактического конца.
        waits.append(max(0.0, st.get('last', 0.0) + self.cfg['min_gap'] - now))
        return max(waits) if waits else 0.0

    def reserve_lease(self):
        """Зарегистрировать запрос и вернуть одноразовый token.

        Lease не считается отправленным до acquire(): это важно для браузера,
        где между выдачей слота и фактическим fetch может быть задержка. Вторая
        lease не может стартовать поверх первой; TTL освобождает канал после
        смерти клиента.
        """
        with self._Locked(self) as st:
            now = time.time()
            self._cleanup_leases(st, now)
            if now - st['hour_start'] > 3600:
                st['hour_start'], st['hour_count'] = now, 0
            wait = self._wait_for(st, now)
            token = secrets.token_urlsafe(18)
            # Жёсткая FIFO-очередь: slot считается от уже зарезервированного
            # конца очереди, а не от now. Иначе вторая lease получала тот же
            # момент, что первая, и могла стартовать поверх активного запроса.
            # next_free — хвост, уже включающий min_gap. Не прибавляем gap
            # второй раз: текущий wait уже учитывает его как абсолютный момент.
            slot = max(now + wait, st.get('next_free', now),
                       st.get('last', 0.0) + self.cfg['min_gap'])
            seq = int(st.get('lease_seq', 0)) + 1
            st['lease_seq'] = seq
            wait_until_slot = max(0.0, slot - now)
            # TTL не может истечь до назначенного старта. Иначе клиент получил
            # token, поспал до slot и обнаружил, что lease уже удалена cleanup.
            ttl = max(self.cfg.get('lease_ttl', 120.0), wait_until_slot + 30.0)
            expires = now + ttl
            st.setdefault('leases', {})[token] = {
                'slot': slot, 'created': now, 'seq': seq,
                'expires': expires, 'active': False,
            }
            st['next_free'] = slot + self.cfg['min_gap']
            return {'token': token, 'wait': wait_until_slot,
                    'expires': expires}

    def acquire(self, token):
        """Запросить старт lease. Повторный вызов безопасен."""
        with self._Locked(self) as st:
            now = time.time()
            self._cleanup_leases(st, now)
            lease = st.setdefault('leases', {}).get(str(token))
            if not lease:
                return {'acquired': False, 'error': 'lease отсутствует или истёк'}
            if lease.get('active'):
                return {'acquired': True, 'wait': 0.0, 'token': str(token)}
            wait = max(0.0, lease.get('slot', now) - now)
            # Проверить актуальную penalty/hour queue: пока lease ждала,
            # другой запрос мог получить блокировку. Не стартуем раньше.
            wait = max(wait, self._lease_wait_for(st, lease, now))
            earlier = [x for x in st.setdefault('leases', {}).values()
                       if x.get('seq', 0) < lease.get('seq', 0)]
            if earlier:
                # FIFO: нельзя перескочить через незавершённую раннюю lease.
                # Она освобождается report() или TTL. Если список пуст после
                # report(), phantom wait не добавляется.
                wait = max(wait, 1.0)
            # `wait` — только наблюдение. Нельзя двигать slot каждым poll:
            # иначе опрос клиента сам отодвигает его навсегда (livelock).
            if wait > 0:
                return {'acquired': False, 'wait': wait, 'token': str(token)}
            lease['active'] = True
            # TTL ожидания не покрывает сетевой запрос после долгой очереди.
            # На acquire начинается новый lease-window для фактического HTTP,
            # иначе late report теряет 429 и не освобождает бюджет.
            lease['expires'] = max(lease.get('expires', 0.0),
                                   now + self.cfg.get('lease_ttl', 120.0))
            st['hour_count'] += 1
            st['burst'] = 1 if st['burst'] >= self.cfg['burst'] else st['burst'] + 1
            return {'acquired': True, 'wait': 0.0, 'token': str(token)}

    def cancel(self, token):
        """Освободить pending lease без счётчика запроса.

        Активную lease не отменяем: без report нельзя доказать, что HTTP уже
        закончился. TTL остаётся аварийной страховкой для умершего клиента.
        """
        with self._Locked(self) as st:
            self._cleanup_leases(st, time.time())
            lease = st.setdefault('leases', {}).get(str(token))
            if not lease or lease.get('active'):
                return False
            del st['leases'][str(token)]
            tail = max((float(x.get('slot', time.time())) + self.cfg['min_gap']
                        for x in st['leases'].values()), default=0.0)
            st['next_free'] = max(st.get('last', 0.0) + self.cfg['min_gap'], tail)
            return True

    def report_result(self, status, size, blocked=None, token=None):
        """Атомарно принять исход lease и вернуть accepted/alive.

        `alive=False` означает обычный 404, WAF, timeout или soft-stub — это
        НЕ то же самое, что «sink отклонил token». Браузеру нужны оба факта,
        иначе 404 превращался в ошибку limiter, а потерянный token выглядел как
        успешный запрос.
        """
        if blocked is None:
            blocked = (status in self.cfg['block_codes']
                       or status == 0
                       or (status == 200 and size < self.cfg['min_ok_size']))
        with self._Locked(self) as st:
            now = time.time()
            # Если active lease уже истекла во время долгого HTTP, сохраняем её
            # именно для этого token и всё равно учитываем поздний 429/report.
            self._cleanup_leases(st, now, preserve=str(token) if token is not None else None)
            if token is not None:
                lease = st.setdefault('leases', {}).get(str(token))
                if lease is None or not lease.get('active'):
                    return {'accepted': False, 'alive': False}
                del st['leases'][str(token)]
            st['last'] = now
            st['next_free'] = max(st.get('next_free', 0.0), now)
            if status == 0:
                st['transport'] = st.get('transport', 0) + 1
            elif status in (401, 404, 410):
                st['http_errors'] = st.get('http_errors', 0) + 1
            elif blocked:
                st['blocked'] += 1
                step = min(st.get('penalty_step', 0) + 1, 8)
                st['penalty_step'] = step
                pen = min(self.cfg['penalty_base'] * (2 ** (step - 1)),
                          self.cfg['penalty_max'])
                st['penalty_until'] = now + pen
            elif status != 200:
                st['other_http'] = st.get('other_http', 0) + 1
            else:
                st['ok'] += 1
                st['penalty_step'] = max(0, st.get('penalty_step', 0) - 1)
                st['penalty_until'] = 0.0
        return {'accepted': True, 'alive': status == 200 and not blocked,
                'blocked': bool(blocked)}

    def report(self, status, size, blocked=None, token=None):
        """Совместимый bool API поверх report_result()."""
        return self.report_result(status, size, blocked, token).get('alive', False)

    # ---------------------------------------------------------------- запрос
    def get(self, url, extra_headers=None, timeout=25, retries=3, follow=True, binary=False):
        """GET через curl с соблюдением темпа. Возвращает Resp.

        follow=True обязателен по умолчанию: замер показал 301 у regard.ru —
        без -L канал возвращал пустое тело и выглядел как «сайт не отдаёт
        данные», хотя данные были после редиректа.
        """
        waited_total = 0.0
        attempts = 0
        last_status, last_body = 0, ''

        for _ in range(retries):
            lease = self.reserve_lease()
            token = lease['token']
            deadline = time.time() + max(self.cfg.get('lease_ttl', 120.0), timeout + 30.0)
            while True:
                acq = self.acquire(token)
                if acq.get('acquired'):
                    break
                wait = float(acq.get('wait', 1.0))
                if time.time() + wait > deadline:
                    # Освобождаем pending lease сразу. TTL — только аварийная
                    # страховка; оставлять её до истечения нельзя, иначе
                    # короткий timeout одного клиента блокирует очередь.
                    self.cancel(token)
                    return Resp(0, '', 0.0, attempts, waited_total, throttled=True)
                time.sleep(min(wait, 2.0))
                waited_total += min(wait, 2.0)

            cmd = ['curl', '-s', '-o', '-', '-w', '\n__STATUS__%{http_code}',
                   '-m', str(timeout), '-A', self.cfg['ua'], '--compressed']
            if follow:
                cmd += ['-L', '--max-redirs', '5']
            for k, v in (extra_headers or {}).items():
                cmd += ['-H', '%s: %s' % (k, v)]
            cmd.append(url)

            t0 = time.time()
            try:
                # Байты, не text=True: nix.ru отдаёт windows-1251, и Python
                # падал с UnicodeDecodeError на 0xca прямо в subprocess.
                p = subprocess.run(cmd, capture_output=True, timeout=timeout + 10)
                raw = p.stdout or b''
            except (subprocess.TimeoutExpired, OSError):
                # Timeout и отсутствие бинарника — transport failure, не WAF.
                raw = b''
            took = time.time() - t0
            attempts += 1

            status = 0
            body_raw = raw
            marker = raw.rfind(b'\n__STATUS__')
            if marker >= 0:
                body_raw = raw[:marker]
                try:
                    status = int(raw[marker + 11:].strip())
                except ValueError:
                    status = 0
            body = body_raw if binary else self._decode(body_raw)

            blocked = (status in self.cfg['block_codes'] or status == 0 or
                       (not binary and status == 200 and
                        len(body_raw) < self.cfg['min_ok_size']))
            alive = self.report(status, len(body_raw), token=token, blocked=blocked)
            if alive:
                return Resp(status, body, took, attempts, waited_total)
            last_status, last_body = status, body
            # Любой HTTP-ответ terminal для этого URL: 404/410 — снятый лот,
            # 401 — авторизация, 429/439/403 — WAF. Повторять их бессмысленно
            # и опасно для QRATOR. Единственный retry-класс — status=0, когда
            # curl не получил HTTP-ответ вообще.
            if status != 0 or (status == 200 and len(body_raw) < self.cfg['min_ok_size']):
                break

        was_blocked = (last_status in self.cfg['block_codes'] or
                       (last_status == 200 and bool(last_body) and
                        not binary and len(last_body) < self.cfg['min_ok_size']))
        return Resp(last_status, last_body, 0.0, attempts, waited_total,
                    blocked=was_blocked)

    def get_bytes(self, url, extra_headers=None, timeout=30, retries=1, follow=True):
        """Получить бинарное тело через тот же lease/IP budget."""
        return self.get(url, extra_headers=extra_headers, timeout=timeout,
                        retries=retries, follow=follow, binary=True)

    # ---------------------------------------------------------------- отчёт
    def stats(self):
        st = self._read()
        now = time.time()
        return {
            'канал': self.name,
            'ua': self.cfg['ua'],
            'успешных': st.get('ok', 0),
            'заблокировано': st.get('blocked', 0),
            'в этом часу': st.get('hour_count', 0),
            'лимит в час': self.cfg['hourly_cap'],
            'штраф ещё': max(0, round(st.get('penalty_until', 0) - now)),
            'шаг штрафа': st.get('penalty_step', 0),
            'ждать сейчас': round(self._wait_for(st, now), 1),
        }

    def reset(self):
        for p in (self.path, self.lock_path):
            if os.path.exists(p):
                os.remove(p)


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else 'stats'
    name = sys.argv[2] if len(sys.argv) > 2 else 'avito'
    ch = Channel(name)
    if cmd == 'stats':
        for k, v in ch.stats().items():
            print('%-16s %s' % (k, v))
    elif cmd == 'reset':
        ch.reset()
        print('состояние канала %s сброшено' % name)
    elif cmd == 'get':
        print(ch.get(sys.argv[3]))
    else:
        print(__doc__)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
