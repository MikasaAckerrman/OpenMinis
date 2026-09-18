import sys, os, json, tempfile
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from render import render_scan as render_cards

passed = 0
failed = 0
def check(name, cond, detail=''):
    global passed, failed
    if cond: passed += 1
    else:
        failed += 1
        print(f'FAIL: {name} | {detail}')

# Минимальный скан
scan = {
    'mission': 'test', 'url': 'https://avito.ru/x', 'found_raw': 2, 'kept': 2,
    'stats': {'n': 2, 'min': 100, 'max': 200, 'median': 150},
    'items': [
        {'price': 100, 'title': 'RTX 3070', 'rating': 4.9, 'reviews': 50,
         'risk_score': -5, 'flags': ['high_rating'], 'url': 'https://avito.ru/a',
         'raw': 'RTX 3070 100 руб'},
        {'price': 200, 'title': 'RTX 3070 Ti', 'rating': 2.0, 'reviews': 1,
         'risk_score': 25, 'flags': ['low_rating'], 'url': 'https://avito.ru/b',
         'raw': 'RTX 3070 Ti 200 руб'},
    ]
}

md = render_cards(scan, top=2)

# 1. Есть заголовок
check('есть заголовок', '## ' in md or '# ' in md, md[:100])
# 2. Есть цена и ссылка
check('есть ссылка', 'https://avito.ru/a' in md, md)
# 3. Есть риск
check('есть риск', 'риск' in md.lower() or 'score' in md.lower(), md)
# 4. Оба лота
check('оба лота', 'RTX 3070 Ti' in md, md)

print(f'\n{passed} прошло, {failed} упало')
sys.exit(1 if failed else 0)
