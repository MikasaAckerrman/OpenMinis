import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from humanize import humanize, load_rules

rules = load_rules()
passed = 0
failed = 0

def check(name, cond, detail=''):
    global passed, failed
    if cond:
        passed += 1
    else:
        failed += 1
        print(f'FAIL: {name} | {detail}')

# 1. Канцеляризмы убираются
t = humanize('Использовалась ли карта для майнинга?', rules)
check('канцеляризм "использовалась ли" -> "была"', 'была' in t.lower(), t)

# 2. Длинное тире -> дефис
t = humanize('Карта — новая, коробка есть', rules)
check('длинное тире -> дефис', ' - ' in t or '-' in t and '—' not in t, t)

# 3. Сокращения не ломают смысл
t = humanize('Какая температура была под нагрузкой?', rules)
check('сокращение "температура"', 'темп' in t.lower() or 'температур' in t.lower(), t)

# 4. Майнинг не сокращается (важная тема)
t = humanize('Был ли майнинг?', rules)
check('майнинг не сокращается', 'майнинг' in t.lower() and 'майни' not in t.replace('майнинг',''), t)

# 5. Не ломает обычный текст
t = humanize('Здравствуйте, карта в отличном состоянии, продаю потому что апгрейд', rules)
check('обычный текст не сломан', 'здравствуйте' in t.lower(), t)

# 6. Пустой ввод безопасен
try:
    t = humanize('', rules)
    check('пустой ввод безопасен', True)
except Exception as e:
    check('пустой ввод безопасен', False, str(e))

print(f'\n{passed} прошло, {failed} упало')
sys.exit(1 if failed else 0)
