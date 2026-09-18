#!/usr/bin/env python3
"""Offline contract tests for marketplace preflight."""
import json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import preflight

ok, bad = [], []
def check(name, cond, detail=''):
    (ok if cond else bad).append(name)
    if not cond: print('FAIL %s — %s' % (name, detail))

regard = '''<script type="application/ld+json">{"@type":"ItemList","itemListElement":[
{"@type":"Product","name":"Видеокарта NVIDIA GeForce RTX 3060 12GB","offers":{"price":48990,"priceCurrency":"RUB"}},
{"@type":"Product","name":"Видеокарта GeForce RTX 3060 Ti 8GB","offers":{"price":41990,"priceCurrency":"RUB"}},
{"@type":"Product","name":"Картридж NV Print TK-3060","offers":{"price":1200,"priceCurrency":"RUB"}}
]}</script>'''

r = preflight.parse_regard(regard)
check('Regard извлекает три структурированных позиции', len(r) == 3, str(r))
check('Regard сохраняет цену', r[0]['price'] == 48990, str(r))
check('GPU-фильтр отбрасывает картридж',
      len([x for x in r if preflight.is_gpu(x['name'])]) == 2, str(r))
check('3060 не смешивается с 3060 Ti',
      preflight.match_model('Видеокарта RTX 3060 Ti 8GB', '3060') is False)
check('3060 Ti находится',
      preflight.match_model('Видеокарта RTX 3060 Ti 8GB', '3060 ti') is True)

blocked = '<html><head><title>Вы не робот</title></head><body>captcha</body></html>'
health = preflight.assess('ozon', 200, blocked, [])
check('200 с challenge не считается рабочим', health['status'] == 'blocked', str(health))
health = preflight.assess('empty', 200, '<html><title>ok</title></html>', [])
check('200 без товаров не считается рабочим', health['status'] == 'no_data', str(health))
health = preflight.assess('regard', 200, regard, r)
check('структурированный источник считается рабочим', health['status'] == 'ready', str(health))
check('проверяемость хранит причину', bool(health['checks']) and health['source'] == 'regard', str(health))

jsonld = '''<script type="application/ld+json">{"@type":"Product","name":"RTX 4060","offers":{"price":"35990"}}</script>'''
generic = preflight.parse_jsonld(jsonld, source='shop-x')
check('общий JSON-LD парсер извлекает Product', len(generic) == 1 and generic[0]['price'] == 35990,
      str(generic))
check('общий парсер не требует именно Regard', generic[0]['shop'] == 'shop-x', str(generic))
health = preflight.assess('shop-x', 200, jsonld, generic)
check('источник с Product/price готов', health['status'] == 'ready', str(health))
health = preflight.assess('csr-shop', 200, '<html><title>Каталог</title></html>', [])
check('CSR без цен = no_data, не ready', health['status'] == 'no_data', str(health))
health = preflight.assess('browser-only', 0, '', [])
check('непроверенный источник не выдаётся за готовый', health['status'] == 'blocked', str(health))

# Выбор до поиска: новый рынок не должен подменяться нулём.
choice = preflight.choose({'avito': {'status': 'ready', 'items': 20},
                            'regard': {'status': 'ready', 'items': 4},
                            'ozon': {'status': 'blocked', 'items': 0}})
check('выбор used идёт в Avito', choice['used'] == ['avito'], str(choice))
check('выбор new идёт в рабочую розницу', choice['new'] == ['regard'], str(choice))
check('blocked источник не выбран', 'ozon' not in choice['new'], str(choice))

avito_state = {'search': {'mainCount': 3, 'allItems': {
    '1': {'value': {'title': 'Видеокарта RTX 3060', 'price': '20 000 ₽',
                    'uri': '/x_1', 'analyticParams': {'place': 'serp-items'}}},
    '2': {'value': {'title': 'Видеокарта RTX 3060 Ti', 'price': '22 000 ₽',
                    'uri': '/y_2', 'analyticParams': {'place': 'serp-items'}}},
    '3': {'value': {'title': 'Реклама RTX 3070', 'price': '1 ₽',
                    'uri': '/z_3', 'analyticParams': {'place': 'extra-items'}}}
}}}
av_html = '<script>window.__initialData__ = ' + json.dumps(json.dumps(avito_state, ensure_ascii=False)) + '</script>'
av_rows = preflight.parse_avito(av_html)
check('Avito извлекает свою выдачу', len(av_rows) == 2, str(av_rows))
check('Avito mainCount сохранён', preflight.avito_count(av_html) == 3)
check('Avito extras не проходят без флага', all(x.get('extra') is False for x in av_rows), str(av_rows))
health = preflight.assess('avito', 200, av_html, av_rows)
check('Avito capability требует цены и выдачу', health['status'] == 'ready', str(health))

mfe_state = {'loaderData': {'data': {'mainCount': 2, 'catalog': {'items': [
    {'type': 'item', 'value': {'id': '10', 'title': 'RTX 4060',
                               'price': '24 000 ₽', 'uri_mweb': '/x_10'}},
    {'type': 'item', 'value': {'id': '11', 'title': 'RTX 3060',
                               'price': '20 000 ₽', 'uri_mweb': '/y_11'}}
]}, 'rubricators': {'allItems': {}}}}}
mfe_html = '<script type="mime/invalid" data-mfe-state="true">' + json.dumps(mfe_state, ensure_ascii=False) + '</script>'
mfe_rows = preflight.parse_avito(mfe_html)
check('Avito разбирает актуальный data-mfe-state', len(mfe_rows) == 2, str(mfe_rows))
check('data-mfe-state сохраняет цены', sorted(x['price'] for x in mfe_rows) == [20000, 24000], str(mfe_rows))
check('data-mfe-state mainCount доступен', preflight.avito_count(mfe_html) == 2)

model_reports = {
    '3060': preflight.assess('regard', 200, regard, [r[0]]),
    '3070': preflight.assess('regard', 200, regard, []),
}
agg = preflight.aggregate('regard', model_reports)
check('источник агрегируется один раз', agg['source'] == 'regard' and agg['items'] == 1,
      str(agg))
check('частичный источник не скрывает no_data модели', agg['models']['3070']['status'] == 'no_data',
      str(agg))
check('одна рабочая модель делает источник ready', agg['status'] == 'ready', str(agg))

import tempfile, datetime as dt
cache = tempfile.mktemp(prefix='pf-', suffix='.json')
now = dt.datetime.now(dt.timezone.utc).isoformat(timespec='seconds')
json.dump({'source':'avito','status':'ready','items':30,'observed_at':now,'checks':[]}, open(cache,'w'))
cached = preflight.load_cached(cache, max_age=600)
check('свежий browser preflight читается', cached and cached['status'] == 'ready', str(cached))
old = (dt.datetime.now(dt.timezone.utc)-dt.timedelta(hours=2)).isoformat(timespec='seconds')
json.dump({'source':'avito','status':'ready','items':30,'observed_at':old,'checks':[]}, open(cache,'w'))
cached = preflight.load_cached(cache, max_age=600)
check('старый browser preflight не выдаётся за свежий', cached and cached['status'] == 'stale', str(cached))
os.remove(cache)

print('[real SERP fixture]')
FX = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'fixtures')
fx_html = os.path.join(FX, 'serp-mfe.html')
fx_meta = os.path.join(FX, 'meta.json')
if os.path.exists(fx_html) and os.path.exists(fx_meta):
    meta = json.load(open(fx_meta, encoding='utf-8')).get('serp-mfe', {})
    html = open(fx_html, encoding='utf-8').read()
    rows = preflight.parse_avito(html)
    check('реальный SERP разбирается', len(rows) == meta.get('expect_rows'),
          'получено %d, ожидалось %s' % (len(rows), meta.get('expect_rows')))
    check('реальный mainCount совпадает',
          preflight.avito_count(html) == meta.get('expect_count'),
          'получено %s' % preflight.avito_count(html))
    check('цены реального SERP положительные',
          all(isinstance(x['price'], int) and x['price'] > 0 for x in rows), str(rows[:1]))
    check('url реального SERP пригоден',
          all(x['url'].startswith('/') and '?' not in x['url'] for x in rows), str(rows[:1]))
    check('реальный SERP считается ready',
          preflight.assess('avito', 200, html, rows)['status'] == 'ready')
else:
    print('  нет fixtures/serp-mfe.html — собери: python3 mkfixture-serp.py')

print('[preflight storage contract]')
health = {'avito': {'status': 'ready', 'items': 50},
          'regard': {'status': 'ready', 'items': 4}}
choice = preflight.choose(health)
check('при свежем Avito выбираются used и new',
      choice['used'] == ['avito'] and choice['new'] == ['regard'], str(choice))
choice = preflight.choose({'avito': {'status': 'stale', 'items': 50},
                           'regard': {'status': 'ready', 'items': 4}})
check('stale Avito не выбирается для поиска', choice['used'] == [], str(choice))

print('%d прошло, %d упало' % (len(ok), len(bad)))
sys.exit(1 if bad else 0)
