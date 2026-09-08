#!/usr/bin/env python3
"""core — общий слой для инструментов доступа к сайтам.

Здесь живёт то, что иначе копируется по скилам и расходится:
  - паттерны обёрток состояния (грузятся из patterns.json — один источник)
  - разбор состояния из HTML
  - поиск списков данных в JSON
  - чистый текст / основной текст статьи
  - HTTP через curl с перебором User-Agent и ретраями
  - куда писать данные (paths) — НЕ /tmp

Импортируется как модуль: from core import get_state, http_get, workdir
"""
import json, os, re, sys, time

SAFE_SLUG = re.compile(r'^[A-Za-z0-9_-]+$')


def safe_slug(value, label='имя'):
    """Проверить компонент пути, которым управляет HTTP-клиент."""
    if not isinstance(value, str) or not SAFE_SLUG.fullmatch(value):
        raise ValueError('небезопасный %s: %r' % (label, value))
    return value


HERE = os.path.dirname(os.path.abspath(__file__))
PATTERNS_FILE = os.path.join(HERE, 'patterns.json')

# ---------------------------------------------------------------- пути

# /tmp в этой песочнице НЕ tmpfs (одно устройство с /), файлы там живут,
# но каталог общий на все задачи: 8.7 ГБ мусора от прошлых проектов и
# 95% занятого диска. Данные инструментов держим в своём каталоге в shared,
# чтобы их не путали с мусором и не теряли при чистке.
DATA_ROOT = os.environ.get('MINIS_DATA_ROOT', '/var/minis/shared/_data')


def workdir(tool, sub=None):
    """Каталог для данных инструмента. Создаёт при первом обращении."""
    safe_slug(tool, 'tool')
    if sub is not None:
        safe_slug(sub, 'подкаталог')
    p = os.path.join(DATA_ROOT, tool, sub) if sub else os.path.join(DATA_ROOT, tool)
    os.makedirs(p, exist_ok=True)
    return p


def datafile(tool, name):
    return os.path.join(workdir(tool), name)


# ---------------------------------------------------------------- паттерны

_PAT_CACHE = None


def patterns():
    """Список обёрток состояния из patterns.json. Кэшируется на процесс."""
    global _PAT_CACHE
    if _PAT_CACHE is None:
        with open(PATTERNS_FILE, encoding='utf-8') as f:
            _PAT_CACHE = json.load(f)
    return _PAT_CACHE


def _flags(s):
    f = 0
    if 's' in (s or ''):
        f |= re.S
    if 'i' in (s or ''):
        f |= re.I
    return f


def get_state(html):
    """Все обёртки состояния, которые удалось разобрать. {имя: объект}.

    json-ld бывает несколькими блоками — тогда значение становится списком.
    """
    out = {}
    for p in patterns()['patterns']:
        for m in re.finditer(p['re'], html, _flags(p.get('flags'))):
            raw = m.group(1)
            try:
                obj = json.loads(json.loads(raw)) if p['kind'] == 'jsonstr' else json.loads(raw)
            except Exception:
                continue
            name = p['name']
            if name in out:
                if not isinstance(out[name], list):
                    out[name] = [out[name]]
                out[name].append(obj)
            else:
                out[name] = obj
    return out


def state_report(html):
    """Что нашлось и что НЕ разобралось — для probe. Молчать о сбое парсинга нельзя:
    «обёртки нет» и «обёртка есть, но битая» требуют разных действий."""
    rep = []
    for p in patterns()['patterns']:
        m = re.search(p['re'], html, _flags(p.get('flags')))
        if not m:
            continue
        raw = m.group(1)
        try:
            obj = json.loads(json.loads(raw)) if p['kind'] == 'jsonstr' else json.loads(raw)
            keys = (list(obj.keys())[:12] if isinstance(obj, dict)
                    else ['[список %d]' % len(obj)] if isinstance(obj, list) else [])
            rep.append({'name': p['name'], 'bytes': len(raw), 'ok': True, 'keys': keys})
        except Exception as e:
            rep.append({'name': p['name'], 'bytes': len(raw), 'ok': False,
                        'err': str(e)[:70], 'keys': []})
    return rep


BLOCK_RE = re.compile('|'.join(re.escape(x) for x in patterns()['block_markers']), re.I)


def is_blocked(status, body):
    """Признак антибот-заглушки.

    НЕ искать маркеры по всему телу: у MediaWiki слово Captcha лежит в конфиге
    каждой страницы (wgConfirmEditCaptchaNeededForGenericEdit), из-за чего все
    статьи Википедии считались заблокированными. Смотрим <title> и короткое тело.
    """
    if status != 200:
        return True
    title = (re.search(r'<title[^>]*>([\s\S]{0,200}?)</title>', body or '', re.I) or [None, ''])[1]
    if title and BLOCK_RE.search(title):
        return True
    return len(body or '') < 8000 and bool(BLOCK_RE.search(body or ''))


# ---------------------------------------------------------------- поиск данных

def dig(obj, path):
    """Путь вида a.b.0.c по вложенному JSON."""
    cur = obj
    for part in str(path).split('.'):
        if not part:
            continue
        if isinstance(cur, list):
            try:
                cur = cur[int(part)]
            except (ValueError, IndexError):
                return None
        elif isinstance(cur, dict):
            cur = cur.get(part)
        else:
            return None
        if cur is None:
            return None
    return cur


def find_arrays(obj, min_len=5, max_depth=7, skip_keys=None):
    """Списки однотипных объектов в JSON — обычно это и есть данные.

    Ловит и «dict-как-список» (ключ = id, значение = объект): так отдаёт Avito
    (search.allItems) и многие API. Возвращает [(путь, длина, ключи)].

    skip_keys: ветки-шум, которые всегда всплывают наверх по длине и вытесняют
    настоящие данные. Замерено на Avito: abCentral (90 A/B-тестов), helmet.meta
    (13 meta-тегов), toggles — они первыми в выдаче, а нужный search.allItems
    оказывался ниже. Список — не догадка, а то, что реально мешало.
    """
    NOISE = skip_keys if skip_keys is not None else (
        'abCentral', 'helmet', 'toggles', 'analytics', 'analyticParams',
        'adsPreloadScripts', 'firebaseParams', 'uxFeedbackConfig', 'seoTags',
        'breadcrumbs', 'footerGlobal', 'browserInfo', 'staticContext',
        'subdomainConfig', 'abTests', 'experiments', 'gtm', 'metrika',
        'categoryTree', 'header', 'footer', 'menu', 'navigation', 'seoNavigation')
    # Ключи, по которым список похож на ДАННЫЕ, а не на служебную структуру.
    # Нужно для ранжирования: на Avito навигация (categoryTree) давала n=12,
    # а настоящие товары в json-ld — n=50, но всплывали ниже из-за порядка обёрток.
    PAYLOAD = ('price', 'url', 'title', 'name', 'id', 'image', 'href', 'text',
               'description', 'offers', 'author', 'date', 'value')
    res = []

    def noisy(path):
        return any(p in NOISE for p in path.replace(' {dict}', '').split('.'))

    def score(n, keys):
        hits = sum(1 for k in keys if any(p in k.lower() for p in PAYLOAD))
        return n * (1 + hits)

    def walk(o, path, d):
        if d > max_depth or o is None:
            return
        if isinstance(o, list):
            if len(o) >= min_len and isinstance(o[0], dict) and not noisy(path):
                keys = list(o[0].keys())[:10]
                res.append((path, len(o), keys, score(len(o), keys)))
            for i, x in enumerate(o[:3]):
                walk(x, '%s.%d' % (path, i), d + 1)
        elif isinstance(o, dict):
            vals = list(o.values())
            if (len(o) >= min_len and vals and all(isinstance(v, dict) for v in vals[:5])
                    and not noisy(path)):
                keys = list(vals[0].keys())[:10]
                res.append((path + ' {dict}', len(o), keys, score(len(o), keys)))
            for k, v in o.items():
                walk(v, '%s.%s' % (path, k) if path else k, d + 1)

    walk(obj, '', 0)
    res.sort(key=lambda x: -x[3])
    return [(p, n, k) for p, n, k, _ in res]


# ---------------------------------------------------------------- текст

JUNK = r'(?is)<(script|style|noscript|template|svg|iframe|form|nav|footer|header|aside)[^>]*>.*?</\1>'
ENT = {'&nbsp;': ' ', '&amp;': '&', '&lt;': '<', '&gt;': '>', '&quot;': '"',
       '&#39;': "'", '&mdash;': '—', '&ndash;': '–', '&laquo;': '«', '&raquo;': '»'}


def get_text(html, structure=True):
    h = re.sub(JUNK, ' ', html or '')
    if structure:
        h = re.sub(r'(?i)<h([1-6])[^>]*>', lambda m: '\n\n' + '#' * int(m.group(1)) + ' ', h)
        h = re.sub(r'(?i)</h[1-6]>', '\n', h)
        h = re.sub(r'(?i)<li[^>]*>', '\n- ', h)
        h = re.sub(r'(?i)</(p|div|tr|br)>|<br\s*/?>', '\n', h)
    h = re.sub(r'(?s)<[^>]+>', ' ', h)
    for k, v in ENT.items():
        h = h.replace(k, v)
    h = re.sub(r'&#(\d+);', lambda m: chr(int(m.group(1))), h)
    h = re.sub(r'[ \t]+', ' ', h)
    h = re.sub(r'\n\s*-\s*(?=\n)', '', h)          # пустые пункты от навигации
    h = re.sub(r'\n\s*\n\s*\n+', '\n\n', h)
    return h.strip()


def get_readable(html):
    """Основной текст без навигации: блок с максимальной плотностью НЕ-ссылочного текста."""
    for tag in ('main', 'article'):
        m = re.search(r'(?is)<%s[^>]*>(.*?)</%s>' % (tag, tag), html or '')
        if m and len(get_text(m.group(1))) > 800:
            return get_text(m.group(1))
    best, best_score = '', 0
    for blk in re.findall(r'(?is)<(?:div|section)[^>]*>(.*?)</(?:div|section)>', html or ''):
        t = get_text(blk)
        if len(t) < 400:
            continue
        link_text = sum(len(x) for x in re.findall(r'(?is)<a[^>]*>(.*?)</a>', blk))
        score = len(t) * max(1 - link_text / max(len(t), 1), 0.05)
        if score > best_score:
            best, best_score = t, score
    return best or get_text(html)


# ---------------------------------------------------------------- HTTP

# Порядок не случаен: тупой UA часто проходит там, где браузерный режут —
# WAF ловит расхождение между заявленным UA и TLS-отпечатком, а не сам curl.
# Замерено: Avito пускает curl/8.14.1 и Googlebot, режет Chrome/120.
# Wikipedia наоборот: 403 на пустой UA и Googlebot, 200 на curl и Chrome.
# Foxford — только Googlebot. Универсального UA нет, поэтому перебор.
UAS = [
    ('curl',    None),
    ('none',    ''),
    ('bot',     'Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)'),
    ('mobile',  'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) '
                'Chrome/120.0.0.0 Mobile Safari/537.36'),
    ('desktop', 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) '
                'Chrome/120.0.0.0 Safari/537.36'),
]
UA_BY_NAME = dict(UAS)


def http_get(url, ua=None, out=None, timeout=25):
    """Один GET через Channel, сохраняя старый dict-контракт.

    Старый прямой curl обходил общий limiter. Теперь probe/extract тоже
    проходят через stateful channel; `out` получает проверенное тело после
    ответа. UA остаётся частью профиля, но не отключает lease.
    """
    from channel import Channel
    if ua in UA_BY_NAME and ua is not None:
        ua = UA_BY_NAME[ua]
    profile = {'ua': ua or 'curl/8.14.1', 'min_ok_size': 0,
               'block_codes': (403, 429, 439, 498, 503),
               'min_gap': 2.0, 'burst': 8, 'burst_pause': 15.0,
               'hourly_cap': 300, 'penalty_base': 30.0, 'penalty_max': 300.0,
               'state_name': 'core-http'}
    r = Channel('core-http', profile).get(url, timeout=timeout, retries=1)
    body = r.body if isinstance(r.body, str) else (r.body or b'').decode('utf-8', 'replace')
    if out and r.status == 200:
        with open(out, 'w', encoding='utf-8', errors='replace') as f:
            f.write(body)
    return {'code': r.status, 'size': len(r.body or ''), 'final': url,
            'err': '' if r.status else 'transport'}


def http_many(url, ua, tries=3, out=None, pause=1.2):
    """Несколько попыток: одна проба врёт.

    Замерено на Avito: тот же UA в один час даёт 200 десять раз подряд, через
    полчаса — 429 десять раз подряд. Репутационный WAF решает по IP и частоте,
    поэтому канал измеряется долей успеха. <100% = ретраи в сборе обязательны.
    """
    codes, saved = [], False
    for i in range(tries):
        r = http_get(url, ua, out=out if not saved else None)
        codes.append(r['code'])
        if r['code'] == 200 and out and not saved:
            saved = True
        # WAF/auth response не повторяем в этом helper: Channel уже записал
        # cooldown, а новый shell-вызов только увеличил бы штраф.
        if r['code'] in (401, 403, 404, 410, 429, 439, 498, 503):
            break
        if i + 1 < tries:
            time.sleep(pause)
    ok = codes.count(200)
    return {'ok': ok, 'tries': tries, 'codes': codes, 'saved': saved,
            'code': 200 if ok else (codes[0] if codes else 0)}


def fetch_best(url, tries=2, out=None):
    """Забрать страницу лучшим доступным UA. Возвращает (html, имя_ua) или (None, None)."""
    tmp = out or os.path.join('/tmp', 'core_fetch_%d.html' % os.getpid())
    for name, ua in UAS:
        r = http_many(url, ua, tries, out=tmp)
        if r['saved']:
            html = open(tmp, encoding='utf-8', errors='replace').read()
            if not is_blocked(200, html):
                return html, name
    return None, None
