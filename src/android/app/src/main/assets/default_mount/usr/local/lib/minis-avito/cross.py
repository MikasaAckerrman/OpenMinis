#!/usr/bin/env python3
"""cross.py — межлотовые и поведенческие сигналы. Считаются БЕЗ сети.

Один лот сам о себе почти ничего не говорит. Смысл появляется при сравнении:
  - один продавец держит 7 карт одной модели -> это не «продаю свою», это поток;
  - одно и то же фото в двух лотах -> копипаста, часто перевыставление или скам;
  - одинаковый текст описания у разных продавцов -> шаблон массового постинга;
  - лот висит 3 недели при 400 просмотрах -> не берут, есть повод торговаться;
  - в заголовке 3060, в характеристиках 3060 Ti -> либо ошибка, либо подмена;
  - нет ни адреса, ни координат -> личную проверку продавец не предлагает.

Вход — файлы sink'а (items.json, .details, .sellers). Выход — dict сигналов
по id лота, который подмешивает gpu.py, и текстовый отчёт при прямом запуске.

  python3 cross.py            отчёт по всем сигналам
  python3 cross.py --json
"""
import argparse, json, os, re, sys
import datetime as dt
from collections import Counter, defaultdict

DATA = os.environ.get('AVITO_RAW', '/var/minis/shared/_data/avito/items.json')


def load_all():
    """items + details + sellers, склеенные по id. details перекрывают serp."""
    items, sellers = {}, {}
    if os.path.exists(DATA):
        for x in json.load(open(DATA, encoding='utf-8')):
            if x.get('id'):
                items[str(x['id'])] = dict(x)
    dp = DATA + '.details'
    if os.path.exists(dp):
        for x in json.load(open(dp, encoding='utf-8')):
            if x.get('id'):
                items.setdefault(str(x['id']), {}).update(x)
    sp = DATA + '.sellers'
    if os.path.exists(sp):
        for x in json.load(open(sp, encoding='utf-8')):
            if x.get('hash'):
                sellers[x['hash']] = x
    return items, sellers


def photo_key(u):
    """Имя файла фото без параметров — Avito отдаёт один файл в разных размерах."""
    return re.sub(r'\?.*$', '', str(u)).split('/')[-1]


def descr_key(s):
    """Отпечаток описания: только буквы и цифры, первые 160 символов."""
    t = re.sub(r'[^0-9a-zа-яё]', '', (s or '').lower())
    return t[:160] if len(t) >= 60 else None


MODEL_RE = re.compile(r'\b(\d{4})\s*(ti)?\b', re.I)


def model_of(text):
    m = MODEL_RE.search(text or '')
    if not m:
        return None
    return (m.group(1) + (' ti' if m.group(2) else '')).lower()


def days_live(x):
    ts = x.get('posted_ts')
    if not ts:
        return None
    try:
        return max(0, (dt.datetime.now() - dt.datetime.fromtimestamp(ts)).days)
    except Exception:
        return None


def analyze(items, sellers):
    """{id: {'flags': [(текст, вес)], 'notes': [...]}} — сигналы по каждому лоту."""
    out = {i: {'flags': [], 'notes': []} for i in items}

    # --- продавец: сколько лотов и насколько однотипных -------------------
    by_seller = defaultdict(list)
    for i, x in items.items():
        h = x.get('seller_hash')
        if h:
            by_seller[h].append(i)
    for h, ids in by_seller.items():
        if len(ids) < 2:
            continue
        models = Counter(filter(None, (model_of(items[i].get('title')) for i in ids)))
        same = models.most_common(1)[0] if models else (None, 0)
        for i in ids:
            out[i]['notes'].append('у продавца в выборке лотов: %d' % len(ids))
            if same[1] >= 3:
                out[i]['flags'].append(
                    ('поток одной модели у продавца (%s × %d) — перекуп/сервис, не «своя карта»'
                     % (same[0], same[1]), 12))

    # Данные профиля, если собраны seller.js.
    for i, x in items.items():
        s = sellers.get(x.get('seller_hash'))
        if not s:
            continue
        total = s.get('ads_total')
        if total is not None:
            out[i]['notes'].append('всего активных объявлений: %s' % total)
            if isinstance(total, int) and total >= 20:
                out[i]['flags'].append(('у продавца %d активных лотов — поток' % total, 10))
        revs = s.get('reviews') or []
        # titleCaption — роль АВТОРА отзыва в сделке, а не роль владельца
        # профиля. «Покупатель» = покупатель написал отзыв продавцу; это
        # evidence продажи. Прежний `as_seller` инвертировал смысл и на живом
        # профиле 4.6/24 ошибочно выдавал «опыта продаж нет».
        buyer_authored = [r for r in revs
                          if 'покупател' in (r.get('role') or '').lower()]
        seller_authored = [r for r in revs
                           if 'продав' in (r.get('role') or '').lower()]
        unknown_role = [r for r in revs if not r.get('role')]
        if revs:
            out[i]['notes'].append(
                'отзывов в профиле: %d, от покупателей: %d, от продавцов: %d'
                % (len(revs), len(buyer_authored), len(seller_authored)))
            if buyer_authored:
                out[i]['notes'].append(
                    'отзывы от покупателей подтверждают сделки: %d'
                    % len(buyer_authored))
            # Отсутствие buyer-authored отзывов не доказывает отсутствие продаж:
            # роль может быть не раскрыта, отзывов может быть меньше 25 (лимит
            # страницы), а часть сделок могла быть без отзыва. Это unknown, не
            # risk-факт; не создаём ложное обвинение.
            if revs and not buyer_authored and not unknown_role:
                out[i]['notes'].append(
                    'отзывов от покупателей не найдено — опыт продаж не подтверждён')
            neg = [r for r in revs if isinstance(r.get('score'), (int, float))
                   and r['score'] <= 3]
            if neg:
                out[i]['flags'].append(('негативных отзывов: %d' % len(neg), 8 * len(neg)))
                for r in neg[:2]:
                    out[i]['notes'].append('негатив: %s' % (r.get('text') or '')[:120])
        since = s.get('since') or x.get('seller_since')
        if since:
            out[i]['notes'].append(str(since))
            m = re.search(r'(\d{4})', str(since))
            if m and int(m.group(1)) >= dt.datetime.now().year:
                out[i]['flags'].append(('аккаунт создан в этом году', 15))

    # Стаж и число лотов есть и без профиля — прямо в карточке.
    for i, x in items.items():
        if sellers.get(x.get('seller_hash')):
            continue                       # уже разобрано выше, не дублируем
        since = x.get('seller_since')
        if since:
            out[i]['notes'].append(str(since))
            m = re.search(r'(\d{4})', str(since))
            if m and int(m.group(1)) >= dt.datetime.now().year:
                out[i]['flags'].append(
                    ('аккаунт создан в этом году (%s)' % since, 15))
        ads = x.get('seller_ads')
        if ads:
            out[i]['notes'].append('у продавца: %s' % ads)
            n = re.search(r'(\d+)', str(ads))
            if n and int(n.group(1)) >= 20:
                out[i]['flags'].append(('у продавца %s — поток' % ads, 10))
        # Подтверждения аккаунта: снижают риск, но не отменяют проверку товара.
        if x.get('seller_verified'):
            out[i]['notes'].append('аккаунт подтверждён Авито')
        if x.get('seller_confirmed'):
            out[i]['notes'].append(str(x['seller_confirmed']))
        if x.get('seller_reply'):
            out[i]['notes'].append('отвечает: %s' % x['seller_reply'])

    # --- одинаковые фото между лотами -------------------------------------
    by_photo = defaultdict(set)
    for i, x in items.items():
        for u in (x.get('images') or []):
            by_photo[photo_key(u)].add(i)
    for key, ids in by_photo.items():
        if len(ids) < 2:
            continue
        for i in ids:
            others = sorted(ids - {i})
            same_seller = len({items[j].get('seller_hash') for j in ids}) == 1
            if same_seller:
                out[i]['flags'].append(
                    ('то же фото в другом лоте того же продавца (%s) — перевыставление'
                     % ', '.join(others[:2]), 8))
            else:
                out[i]['flags'].append(
                    ('то же фото у ДРУГОГО продавца (%s) — украденное фото'
                     % ', '.join(others[:2]), 45))

    # --- одинаковые описания ----------------------------------------------
    by_descr = defaultdict(set)
    for i, x in items.items():
        k = descr_key(x.get('descr'))
        if k:
            by_descr[k].add(i)
    for k, ids in by_descr.items():
        if len(ids) < 2:
            continue
        sellers_n = len({items[j].get('seller_hash') for j in ids})
        for i in ids:
            out[i]['flags'].append(
                ('описание совпадает с %d лотами%s — шаблонный постинг'
                 % (len(ids) - 1, ' разных продавцов' if sellers_n > 1 else ''),
                 20 if sellers_n > 1 else 6))

    # --- висит долго / много просмотров -----------------------------------
    # views приходит объектом {'today': N, 'total': M}, не числом. Раньше
    # проверка `if v` была правдой всегда, а сравнение v >= 300 падало бы на dict.
    for i, x in items.items():
        d = days_live(x)
        v = x.get('views')
        total = v.get('total') if isinstance(v, dict) else v
        today = v.get('today') if isinstance(v, dict) else None
        if d is not None:
            out[i]['notes'].append('в продаже %d дн.' % d)
            if d >= 21:
                out[i]['flags'].append(('висит %d дней — не берут, есть место торгу' % d, -5))
        if total:
            out[i]['notes'].append('просмотров: %s%s'
                                   % (total, ' (сегодня %s)' % today if today else ''))
            if d and d >= 14 and total >= 300:
                out[i]['flags'].append(
                    ('%s просмотров за %d дн. и не продано — смотрят, но не берут'
                     % (total, d), 8))
            # Интерес = просмотры в день. Порог по абсолютному числу давал флаг
            # почти всем (417 за 2 дня — норма для дешёвой карты), поэтому
            # смотрим скорость и только когда цена НИЖЕ медианы: высокий интерес
            # к дешёвому лоту, который до сих пор не купили, значит проблему
            # видят все, кто звонил.
            per_day = total / max(d, 1) if d is not None else None
            if per_day and per_day >= 250 and d is not None and d >= 2:
                out[i]['flags'].append(
                    ('%.0f просмотров в день (%s за %d дн.) — лот смотрят массово, но не берут'
                     % (per_day, total, d), 10))

    # --- заголовок против характеристик -----------------------------------
    for i, x in items.items():
        props = x.get('props') or {}
        pm = None
        for k, val in props.items():
            if 'модель' in k.lower():
                pm = model_of(str(val))
                break
        tm = model_of(x.get('title'))
        if pm and tm and pm != tm:
            out[i]['flags'].append(
                ('в заголовке %s, в характеристиках %s — расхождение' % (tm, pm), 25))
        # Память: 3060 на 8 ГБ дешевле 12-гиговой, продавцы это скрывают.
        for k, val in props.items():
            if 'памят' in k.lower():
                out[i]['notes'].append('%s: %s' % (k, val))

    # --- возможность личной проверки --------------------------------------
    # ТОЛЬКО для раскрытых карточек: в выдаче адреса нет ни у кого, и без этой
    # проверки флаг загорался у 197 из 219 лотов, то есть не значил ничего.
    for i, x in items.items():
        opened = bool(x.get('descr') or x.get('props'))
        if opened and not x.get('address') and not x.get('coords'):
            out[i]['flags'].append(('нет адреса и координат — личная проверка не предложена', 10))
        if x.get('seller_online'):
            out[i]['notes'].append('продавец онлайн')

    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--json', action='store_true')
    ap.add_argument('--only-flagged', action='store_true')
    a = ap.parse_args()

    items, sellers = load_all()
    if not items:
        print('нет данных: сначала собери выдачу')
        return 1
    sig = analyze(items, sellers)

    if a.json:
        print(json.dumps(sig, ensure_ascii=False))
        return 0

    print('лотов: %d | карточек с деталями: %d | профилей продавцов: %d'
          % (len(items), sum(1 for x in items.values() if x.get('descr')), len(sellers)))
    tot = Counter()
    for i, s in sig.items():
        for text, w in s['flags']:
            tot[re.sub(r'\(.*?\)', '', text).strip()[:60]] += 1
    print()
    print('=== сводка сигналов ===')
    for text, n in tot.most_common(20):
        print('  %3d  %s' % (n, text))

    flagged = [(i, s) for i, s in sig.items() if s['flags']]
    if a.only_flagged and flagged:
        print()
        print('=== лоты с сигналами ===')
        for i, s in sorted(flagged, key=lambda kv: -sum(w for _, w in kv[1]['flags']))[:15]:
            x = items[i]
            print('%s  %s ₽  %s' % (i, x.get('price'), (x.get('title') or '')[:52]))
            for text, w in s['flags']:
                print('     %+3d  %s' % (w, text))
    return 0


if __name__ == '__main__':
    sys.exit(main())
