#!/usr/bin/env python3
"""Preflight источников до поиска объявлений.

Задача не «проверить HTTP 200», а доказать, что источник отдаёт пригодные
товары и цены. Сетевой режим сохраняет отчёт; тесты используют чистые функции.
"""
import argparse, datetime as dt, json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
# Движок web-deep: встроенная копия (mount) или скилловая (песочница) —
# что найдено первым. См. photo.py.
ENGINE = next(p for p in (
    os.path.join(HERE, 'engine'),
    os.path.join(os.path.dirname(HERE), 'web-deep'),
) if os.path.isfile(os.path.join(p, 'channel.py')))
for p in (HERE, ENGINE):
    if p not in sys.path:
        sys.path.insert(0, p)
import core
from channel import Channel

GPU_CONTEXT = re.compile(r'видеокарт|geforce|\brtx\b|\bgtx\b|radeon|\brx\s*\d{3,4}\b', re.I)
NOT_GPU = re.compile(
    r'картридж|тонер|сервисн\w*\s*комплект|фотобарабан|драм|печк|'
    r'ремкомплект|термоблок|чернил|бумаг|кабел|переходник|адаптер|'
    r'блок\s*питан|кулер|вентилятор|коробк|подставк|наушник|мышь|клавиатур', re.I)

# Минимум для «готов»: хотя бы один товар с названием и ценой. Ноль данных
# при HTTP 200 — no_data, challenge/антибот — blocked.
MIN_ITEMS = 1


def is_gpu(name):
    return bool(name and GPU_CONTEXT.search(name) and not NOT_GPU.search(name))


def norm_model(s):
    t = re.sub(r'[^0-9a-zа-я ]', ' ', str(s).lower())
    t = re.sub(r'\brtx\b|\bgeforce\b|\bnvidia\b', ' ', t)
    t = re.sub(r'(\d{4})\s*(ti)\b', r'\1 ti', t)
    t = re.sub(r'(\d{4})(ti)\b', r'\1 ti', t)
    return re.sub(r'\s+', ' ', t).strip()


def match_model(name, wanted):
    if not is_gpu(name):
        return False
    t, w = norm_model(name), norm_model(wanted)
    if not re.search(r'\b' + re.escape(w) + r'\b', t):
        return False
    return bool(w.endswith('ti') or not re.search(r'\b' + re.escape(w) + r'\s*ti\b', t))


def parse_avito(html):
    """Извлечь только `search.allItems` из Avito __initialData__.

    Все поля считаются наблюдаемыми только если есть title, price и URL. Блоки
    с analyticParams.place=extra-items не входят в собственную выдачу: это
    «похожее из других городов», которое раньше раздувало count.
    """
    states = core.get_state(html)
    iterables = []
    legacy = states.get('__initialData__')
    if isinstance(legacy, dict):
        search = legacy.get('search')
        if isinstance(search, dict) and isinstance(search.get('allItems'), dict):
            iterables.append(search['allItems'].items())

    # Актуальный SSR Avito: data-mfe-state -> loaderData.data.catalog.items.
    # Разбираем ОБЕ оболочки, если они есть: пустая residual __initialData__
    # не должна скрывать заполненный MFE.
    mfe = states.get('__mfe_state__')
    if isinstance(mfe, dict):
        loader = mfe.get('loaderData')
        data = loader.get('data') if isinstance(loader, dict) else None
        catalog = data.get('catalog') if isinstance(data, dict) else None
        catalog_items = catalog.get('items') if isinstance(catalog, dict) else None
        if isinstance(catalog_items, list):
            iterables.append(
                ((str(((w.get('value') or w).get('id') if isinstance(w.get('value') or w, dict) else i)),
                  w if isinstance(w.get('value'), dict) else {'value': w})
                 for i, w in enumerate(catalog_items)
                 if isinstance(w, dict) and (w.get('type') == 'item' or 'title' in w)))

    out = []
    for iterable in iterables:
        for key, wrap in iterable:
            if not isinstance(wrap, dict):
                continue
            v = wrap.get('value') or wrap
            if not isinstance(v, dict):
                continue
            place = ((v.get('analyticParams') or {}).get('place') or '')
            if place == 'extra-items':
                continue
            title = v.get('title') or ''
            raw = v.get('price')
            if isinstance(raw, dict):
                raw = raw.get('current') or raw.get('priceWithoutDiscount') or raw.get('value')
            # SSR-каталог хранит цену как priceDetailed.value, а не price.
            if raw is None and isinstance(v.get('priceDetailed'), dict):
                raw = v['priceDetailed'].get('value')
            digits = re.sub(r'[^0-9]', '', str(raw or '').split('—')[0])
            uri = str(v.get('uri_mweb') or v.get('urlPath') or v.get('uri') or '').split('?')[0]
            if not title or not digits or not uri or uri.startswith('ru.avito:'):
                continue
            out.append({'id': str(key), 'name': title, 'price': int(digits),
                        'url': uri, 'extra': False})
    return out


def avito_count(html):
    states = core.get_state(html)
    legacy = states.get('__initialData__')
    if isinstance(legacy, dict):
        search = legacy.get('search')
        if isinstance(search, dict) and search.get('mainCount') is not None:
            return search.get('mainCount')
    mfe = states.get('__mfe_state__')
    loader = mfe.get('loaderData') if isinstance(mfe, dict) else None
    data = loader.get('data') if isinstance(loader, dict) else None
    return (data.get('mainCount') or data.get('count')) if isinstance(data, dict) else None


def _jsonld_objects(obj):
    if isinstance(obj, list):
        for x in obj:
            yield from _jsonld_objects(x)
    elif isinstance(obj, dict):
        yield obj
        for x in (obj.get('@graph') or []):
            yield from _jsonld_objects(x)
        for x in (obj.get('itemListElement') or []):
            yield from _jsonld_objects(x.get('item') if isinstance(x, dict) and x.get('item') else x)


def parse_jsonld(html, source='unknown'):
    """Общий Product/Offer parser. Не считает номер модели ценой товара."""
    out = []
    blocks = re.findall(
        r'<script[^>]+type=["\']application/ld\+json["\'][^>]*>([\s\S]*?)</script>',
        html or '', re.I)
    for raw in blocks:
        try:
            obj = json.loads(raw)
        except Exception:
            continue
        for item in _jsonld_objects(obj):
            typ = item.get('@type')
            types = typ if isinstance(typ, list) else [typ]
            if not any(t in ('Product', 'Offer', 'IndividualProduct') for t in types):
                continue
            offers = item.get('offers') or item.get('priceSpecification') or {}
            if isinstance(offers, list):
                offers = offers[0] if offers else {}
            price = item.get('price') or offers.get('price')
            if isinstance(price, dict):
                price = price.get('value')
            try:
                price = int(float(re.sub(r'[^0-9.]', '', str(price))))
            except (TypeError, ValueError):
                continue
            name = item.get('name') or ''
            if name and price > 0:
                out.append({'name': name, 'price': price,
                            'url': item.get('url'), 'shop': source})
    # Один schema item иногда попадает и в @graph, и в itemList.
    seen, uniq = set(), []
    for x in out:
        k = (x['shop'], x['name'], x['price'], x.get('url'))
        if k not in seen:
            seen.add(k); uniq.append(x)
    return uniq


def parse_regard(html):
    """Совместимый алиас: Regard — лишь один из JSON-LD источников."""
    return parse_jsonld(html, source='regard')


def assess(source, http_status, body, items, elapsed_ms=None):
    """Вердикт capability с наблюдаемыми проверками."""
    checks = []
    blocked = core.is_blocked(http_status, body or '')
    checks.append({'name': 'http_200', 'ok': http_status == 200,
                   'value': http_status})
    checks.append({'name': 'not_challenge', 'ok': not blocked,
                   'value': 'blocked' if blocked else 'clear'})
    valid = [x for x in (items or []) if x.get('name') and x.get('price')]
    checks.append({'name': 'priced_items', 'ok': len(valid) >= MIN_ITEMS,
                   'value': len(valid)})
    if http_status != 200 or blocked:
        status = 'blocked'
    elif len(valid) < MIN_ITEMS:
        status = 'no_data'
    else:
        status = 'ready'
    return {'source': source, 'status': status, 'items': len(valid),
            'checks': checks, 'observed_at': dt.datetime.now().isoformat(timespec='seconds'),
            'elapsed_ms': elapsed_ms}


def aggregate(source, model_reports):
    """Свести capability по моделям, не скрывая частичную доступность."""
    models = dict(model_reports)
    ready = sum(1 for h in models.values() if h.get('status') == 'ready')
    blocked = sum(1 for h in models.values() if h.get('status') == 'blocked')
    status = 'ready' if ready else ('blocked' if blocked else 'no_data')
    return {'source': source, 'status': status,
            'items': sum(int(h.get('items', 0) or 0) for h in models.values()),
            'models': models,
            'observed_at': dt.datetime.now().isoformat(timespec='seconds')}


def load_cached(path, max_age=600):
    """Прочитать preflight с TTL; устаревший результат явно маркируется."""
    try:
        obj = json.load(open(path, encoding='utf-8'))
        at = dt.datetime.fromisoformat(obj['observed_at'])
        if at.tzinfo is None:
            # Старые отчёты были naive; трактуем их как UTC, не как локальное
            # время телефона, иначе Saratov/Moscow делали их stale навсегда.
            at = at.replace(tzinfo=dt.timezone.utc)
        age = (dt.datetime.now(dt.timezone.utc) - at.astimezone(dt.timezone.utc)).total_seconds()
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError):
        return None
    if age > max_age:
        obj = dict(obj)
        obj['status'] = 'stale'
        obj['age_seconds'] = int(age)
    else:
        obj['age_seconds'] = max(0, int(age))
    return obj


def choose(health):
    """Выбрать источники ДО поиска; ничего не считать рабочим по 200 alone."""
    ready = [n for n, h in health.items() if h.get('status') == 'ready'
             and h.get('items', 0) >= MIN_ITEMS]
    # Avito — used listings and seller graph; retail sources — new price baseline.
    av = health.get('avito') or {}
    used = ['avito'] if av.get('status') == 'ready' and av.get('items', 0) >= MIN_ITEMS else []
    new = [n for n in ready if n != 'avito']
    return {'used': used, 'new': new,
            'blocked': [n for n, h in health.items() if h.get('status') == 'blocked'],
            'unavailable': [n for n, h in health.items()
                            if h.get('status') in ('no_data', 'stale', 'unknown')],
            'unknown': [n for n, h in health.items()
                        if h.get('status') in ('stale', 'unknown')]}


def live_regard(models):
    """Одна мягкая проба Regard через общий shell-channel."""
    ch = Channel('shops')
    reports = {}
    for model in models:
        url = 'https://www.regard.ru/catalog?search=' + model.replace(' ', '+')
        t0 = __import__('time').time()
        r = ch.get(url, retries=1)
        rows = parse_regard(r.body) if r.ok else []
        rows = [x for x in rows if match_model(x['name'], model)]
        reports['regard:' + model] = assess('regard', r.status, r.body, rows,
                                            int((__import__('time').time()-t0)*1000))
    return reports


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('models', nargs='*', default=['3060', '3060 ti', '3070', '4060'])
    ap.add_argument('--live', action='store_true', help='сделать осторожную сетевую пробу Regard')
    ap.add_argument('--max-age', type=int, default=600)
    ap.add_argument('--json', action='store_true')
    a = ap.parse_args()
    models = a.models or ['3060', '3060 ti', '3070', '4060']

    reports = {}
    # Браузер сохраняет ОДИН свежий capability-report в sink; shell его не
    # пересоздаёт и не делает вид, что Avito проверен без вкладки.
    avito_cache = os.path.join(os.environ.get('MINIS_DATA_ROOT', '/var/minis/shared/_data'),
                               'avito', 'items.json.preflight')
    cached = load_cached(avito_cache, a.max_age)
    if cached:
        reports['avito'] = cached
    else:
        reports['avito'] = {'source': 'avito', 'status': 'unknown', 'items': 0,
                            'checks': [], 'note': 'запусти preflight-browser.js в вкладке Avito'}

    if a.live:
        # Regard может проверяться shell-каналом; это медленно намеренно —
        # Channel соблюдает cooldown и не выбивает чужой IP.
        per_model = live_regard(models)
        reports['regard'] = aggregate('regard', per_model)
    else:
        reports['regard'] = {'source': 'regard', 'status': 'unknown', 'items': 0,
                             'checks': [], 'note': 'запусти --live'}

    result = {'health': reports, 'choice': choose(reports),
              'models': models, 'max_age': a.max_age}
    if a.json:
        print(json.dumps(result, ensure_ascii=False))
    else:
        print('=== PREFLIGHT ДО ПОИСКА ===')
        for n, h in reports.items():
            print('%-18s %-10s items=%s' % (n, h.get('status'), h.get('items')))
        print('выбор:', json.dumps(result['choice'], ensure_ascii=False))
    return 0


if __name__ == '__main__':
    sys.exit(main())
