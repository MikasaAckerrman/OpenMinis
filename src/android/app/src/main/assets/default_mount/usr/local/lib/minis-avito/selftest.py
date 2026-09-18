#!/usr/bin/env python3
"""selftest.py — проверки скила avito. Сеть НЕ трогает, работает на фикстурах.

Логика скоринга — самое хрупкое место: одна ошибка в regex, и инструмент
советует нерабочую карту. Здесь закреплены реальные формулировки с живой
выдачи, на которых код уже ломался.

  python3 selftest.py [-v]
"""
import json, os, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import gpu, cross

V = '-v' in sys.argv
ok, bad = [], []


def check(name, cond, detail=''):
    (ok if cond else bad).append(name)
    if V or not cond:
        print('  %s %s%s' % ('ok  ' if cond else 'FAIL', name,
                             (' — ' + detail) if detail else ''))


print('[1] гомоглифы: латиница внутри русских слов')
# Замерено на живых карточках: до 32% символов описания подменено.
cases = [
    ('Прoдaм видеокаpту в не pабoчeм cocтoянии', 'рабоч'),
    ('Kулepa кpутятся, но каpтинки нeт', 'картинки нет'),
    ('oтдаю кaк eсть на запчacти', 'запчаст'),
    ('вoccтaнoвлeние без возвpaтов', 'восстановлени'),
]
for src, need in cases:
    got = gpu.deconfuse(src.lower())
    check('deconfuse: %s' % src[:28], need in got, got[:60])
check('бренды не ломаются',
      gpu.deconfuse('Palit GamingPro Gigabyte MSI ASUS') == 'Palit GamingPro Gigabyte MSI ASUS')
check('чистая кириллица не меняется',
      gpu.deconfuse('продам видеокарту') == 'продам видеокарту')
check('homoglyph_ratio ловит подмену',
      gpu.homoglyph_ratio('Прoдaм видеокаpту Pаlit') > 0.1,
      '%.2f' % gpu.homoglyph_ratio('Прoдaм видеокаpту Pаlit'))
check('homoglyph_ratio чистый текст = 0',
      gpu.homoglyph_ratio('продам видеокарту палит') == 0.0)

print('[2] мёртвые карты')
dead_texts = [
    'Продам видеокарту с ошибкой 43. Торг',
    'ошибка 43 в диспетчере устройств',
    'Продам с дефектом: не совсем корректно работает',
    'При запуске появляются зеленые точки',
    'видеокарта на запчасти',
    'нерабочая, донор',
    'Требуется ремонт',
    'кристалл не работает',
    'отдаю как есть на запчасти или восстановление',
    'в не pабoчeм cocтoянии',                       # с подменой букв
]
for t in dead_texts:
    check('мёртвая: %s' % t[:32], bool(gpu.DEAD.search(gpu.deconfuse(t.lower()))))
alive_texts = [
    'состояние идеальное, любые проверки',
    'технически полностью исправная без дефектов',
    'работает отлично, температуры в норме',
    'на гарантии до 2027 года, есть чек',
]
for t in alive_texts:
    check('живая: %s' % t[:32], not gpu.DEAD.search(gpu.deconfuse(t.lower())))

print('[3] модель и память')
for title, want in [('Rtx 3060 ti zotac', '3060 ti'),
                    ('Видеокарта RTX 3060ti Palit Dual OC 8gb', '3060 ti'),
                    ('Palit GeForce RTX 3060 Dual 12GB', '3060'),
                    ('MSI RTX 4060 ventus 3X OC', '4060'),
                    ('Rtx 3070ti mobile', '3070 ti'),
                    ('Видеокарта rtx 2060 super', '2060 super')]:
    got = gpu.detect_model({'title': title})
    check('модель «%s»' % title[:30], got == want, 'получено %s' % got)

for title, want in [('Palit RTX 3060 Dual 12GB', 12), ('rtx 4060 8gb', 8),
                    ('Видеокарта RTX 3060 12 гб', 12)]:
    got = gpu.detect_vram({'title': title})
    check('память «%s»' % title[:26], got == want, 'получено %s' % got)

print('[4] не-видеокарты и ноутбучные')
for title in ['Коробка от видеокарты palit RTX 4060', 'Кулер охлаждения для 3070m',
              'Блок питания Corsair CV650 650W', 'Вентилятор для Palit RTX 4060',
              'Rx 5700 xt обмен', 'Куплю rtx 3060']:
    check('не GPU: %s' % title[:30], not gpu.is_gpu({'title': title}))
for title in ['Видеокарта rtx 3070m laptop', 'Rtx 3070ti mobile', 'Rtx 3070 m',
              'Видеокарта rtx 2070 laptop 8g']:
    check('ноутбучная: %s' % title[:28], gpu.is_mobile({'title': title}))
for title in ['Rtx 3070 msi Ventus 2x', 'Palit RTX 3060 Dual 12GB']:
    check('НЕ ноутбучная: %s' % title[:26], not gpu.is_mobile({'title': title}))

print('[5] межлотовые сигналы (cross.py)')
items = {
    'a': {'id': 'a', 'title': 'RTX 3070', 'price': 20000, 'seller_hash': 'H1',
          'images': ['https://img.avito.st/1/x.jpg'], 'descr': 'обычное описание карты' * 4,
          'props': {'Модель': 'GeForce RTX 3070'}, 'address': 'Москва',
          'views': {'total': 100}, 'posted_ts': None},
    'b': {'id': 'b', 'title': 'RTX 3070', 'price': 21000, 'seller_hash': 'H2',
          'images': ['https://img.avito.st/1/x.jpg'],       # ТО ЖЕ фото, другой продавец
          'descr': 'обычное описание карты' * 4, 'props': {}, 'address': 'Тверь'},
    'c': {'id': 'c', 'title': 'RTX 3060 ti', 'price': 19000, 'seller_hash': 'H1',
          'props': {'Модель': 'GeForce RTX 3060 Ti'}, 'descr': 'x' * 80, 'address': 'Москва'},
    'd': {'id': 'd', 'title': 'RTX 3060 ti', 'price': 19500, 'seller_hash': 'H1',
          'props': {}, 'descr': 'y' * 80, 'address': 'Москва'},
    'e': {'id': 'e', 'title': 'RTX 3060 ti', 'price': 19900, 'seller_hash': 'H1',
          'props': {}, 'descr': 'z' * 80, 'address': 'Москва'},
    'f': {'id': 'f', 'title': 'RTX 4060', 'price': 18000, 'seller_hash': 'H3',
          'props': {'Модель': 'GeForce RTX 4060 Ti'},       # расхождение с заголовком
          'descr': 'q' * 80, 'address': 'Казань'},
}
sig = cross.analyze(items, {})
flat = lambda i: ' | '.join(t for t, _ in sig[i]['flags'])
check('украденное фото у другого продавца', 'ДРУГОГО продавца' in flat('a'), flat('a'))
check('поток одной модели у продавца', 'поток одной модели' in flat('c'), flat('c'))
check('расхождение заголовок/характеристики', 'расхождение' in flat('f'), flat('f'))
check('одинаковое описание помечено', 'описание совпадает' in flat('a'), flat('a'))
check('чистый лот без флагов', not sig['e']['flags'] or 'поток' in flat('e'), flat('e'))

print('[5b] семантика роли отзыва')
profile_items = {
    'seller-item': {'id': 'seller-item', 'title': 'RTX 3070', 'price': 20000,
                    'seller_hash': 'SELLER-1', 'descr': 'x' * 100,
                    'address': 'Москва'}
}
profile = {'SELLER-1': {'ads_total': 41, 'since': 'октября 2016',
    'reviews': [
        {'role': 'Покупатель', 'score': 1,
         'text': 'Продал видеокарту после майнинга с перешитым биосом'},
        {'role': 'Покупатель', 'score': 5, 'text': 'Всё работает'},
    ]}}
ps = cross.analyze(profile_items, profile)['seller-item']
pflat = ' | '.join(t for t, _ in ps['flags'])
check('Покупатель — автор отзыва о продавце',
      'опыта продаж нет' not in pflat and 'подтверждают сделки: 2' in ' | '.join(ps['notes']),
      pflat + ' || ' + ' | '.join(ps['notes']))
check('негатив покупателя привязан к продавцу', 'негативных отзывов: 1' in pflat,
      pflat)
check('поток из профиля виден', '41 активных лотов' in pflat, pflat)

print('[6] views как объект (Avito отдаёт {today,total})')
it = {'g': {'id': 'g', 'title': 'RTX 3070', 'price': 20000,
            'views': {'today': 4, 'total': 1936},
            'posted_ts': int(__import__('time').time()) - 6 * 86400,
            'descr': 'w' * 80, 'address': 'Тюмень'}}
s = cross.analyze(it, {})
check('скорость просмотров считается',
      any('просмотров в день' in t for t, _ in s['g']['flags']),
      ' | '.join(t for t, _ in s['g']['flags']))
check('число просмотров в фактах',
      any('1936' in n for n in s['g']['notes']), str(s['g']['notes']))

print('[7] сквозной прогон на реальных данных')
if os.path.exists(gpu.DATA):
    r = subprocess.run([sys.executable, os.path.join(HERE, 'gpu.py'),
                        '--budget', '25000', '--top', '5'],
                       capture_output=True, text=True, timeout=300)
    check('gpu.py код 0', r.returncode == 0, r.stderr[:100])
    check('есть медианы по моделям', 'медиана по модели' in r.stdout)
    check('есть топ', 'ЛУЧШИЕ' in r.stdout)
    check('нерабочие исключены', 'исключено как нерабочие' in r.stdout)
    out = r.stdout.lower()
    for word in ('нерабоч', 'на запчасти', 'донор', 'ошибкой 43', 'mobile', 'laptop'):
        check('в топе нет «%s»' % word, word not in out.split('=== лучшие')[-1])
    r2 = subprocess.run([sys.executable, os.path.join(HERE, 'gpu.py'), '--json'],
                        capture_output=True, text=True, timeout=300)
    check('--json валиден', r2.returncode == 0 and isinstance(json.loads(r2.stdout), list))
    rows = json.loads(r2.stdout)
    check('--json уважает --top', len(rows) <= 12, '%d строк' % len(rows))
else:
    print('  нет %s — сквозной прогон пропущен' % gpu.DATA)

print()
print('%d прошло, %d упало' % (len(ok), len(bad)))
if bad:
    print('упало: %s' % '; '.join(bad[:10]))
sys.exit(1 if bad else 0)
