#!/usr/bin/env python3
"""Smoke-тест photo.py без сети и без вызова реальной модели.

Ревью нашло здесь дефект, который не ловился ничем: `tmp` использовался без
`mkdtemp`, поэтому ЛЮБОЙ успешный download падал с NameError уже после того,
как IP-бюджет был потрачен. Значит нужен тест, который доходит до запроса к
модели, а не только проверяет фильтры.

  python3 selftest-photo.py
"""
import json, os, subprocess, sys, tempfile

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

TMP = tempfile.mkdtemp(prefix='phototest-')
DATA = os.path.join(TMP, 'items.json')
JPEG = b'\xff\xd8\xff' + b'A' * 4000 + b'\xff\xd9'

os.environ['AVITO_RAW'] = DATA
json.dump([], open(DATA, 'w'))
json.dump([{'id': '777', 'title': 'RTX 3060', 'price': 20000,
            'url': 'https://www.avito.ru/x_777',
            'images': ['https://img.avito.st/image/1/a.jpg',
                       'https://evil.example/b.jpg']}],
          open(DATA + '.details', 'w'), ensure_ascii=False)

import photo
photo.RAW = DATA

ok, bad = [], []
def check(name, cond, detail=''):
    (ok if cond else bad).append(name)
    if not cond:
        print('FAIL %s — %s' % (name, detail))

# Модель и канал подменяются: тест обязан быть офлайн и не тратить бюджет.
calls = {'urls': [], 'model_input': None}

class FakeResp:
    def __init__(self, status, body):
        self.status, self.body = status, body
        self.blocked = self.throttled = False
    @property
    def ok(self):
        return self.status == 200 and bool(self.body)

class FakeChannel:
    def __init__(self, name, profile=None):
        calls['channel'] = name
    def get(self, url, timeout=25, retries=1, binary=False):
        calls['urls'].append(url)
        return FakeResp(200, JPEG if binary else '')

photo.Channel = FakeChannel
photo.pick_model = lambda: 'fake-vision'

real_run = subprocess.run
def fake_run(cmd, *a, **kw):
    if cmd and cmd[0] == 'minis-model-use':
        calls['model_input'] = cmd[cmd.index('--input') + 1]
        calls['input_json'] = json.load(open(calls['model_input'], encoding='utf-8'))
        class R:
            returncode = 0
            stdout = json.dumps({'text': 'фото описано'})
            stderr = ''
        return R()
    return real_run(cmd, *a, **kw)
photo.subprocess.run = fake_run

rc = photo.main.__wrapped__() if hasattr(photo.main, '__wrapped__') else None
sys.argv = ['photo.py', '777', '--n', '2']
rc = photo.main()

check('photo.main завершается кодом 0', rc == 0, 'rc=%s' % rc)
check('использован общий avito-images channel',
      calls.get('channel') == 'avito-images', str(calls.get('channel')))
check('скачан только разрешённый CDN',
      calls['urls'] == ['https://img.avito.st/image/1/a.jpg'], str(calls['urls']))
check('запрос к модели сформирован', bool(calls.get('input_json')))
if calls.get('input_json'):
    content = calls['input_json']['messages'][0]['content']
    check('в запросе есть текст и одно изображение',
          sum(1 for c in content if c['type'] == 'image_url') == 1 and
          content[0]['type'] == 'text', str([c['type'] for c in content]))
check('временный каталог убран',
      calls.get('model_input') and not os.path.exists(os.path.dirname(calls['model_input'])),
      str(calls.get('model_input')))

# Пустой список фото: не должно быть попыток модели и падений.
json.dump([{'id': '778', 'title': 'RTX 3070', 'price': 30000,
            'url': 'https://www.avito.ru/y_778', 'images': []}],
          open(DATA + '.details', 'w'), ensure_ascii=False)
sys.argv = ['photo.py', '778']
check('лот без фото не находится как пригодный', photo.main() == 1)

import shutil
shutil.rmtree(TMP, ignore_errors=True)
print('%d прошло, %d упало' % (len(ok), len(bad)))
sys.exit(1 if bad else 0)
