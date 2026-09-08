#!/usr/bin/env python3
"""Тесты chatread: сторона по геометрии, время, шум, дамп экрана.

Сеть и устройство не нужны. Скриншот генерируется здесь же, поэтому тест
проверяет реальный путь tesseract, а не подменённый OCR.
"""
import json, os, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import chatread

ok, bad = [], []
def check(name, cond, detail=''):
    (ok if cond else bad).append(name)
    if not cond:
        print('FAIL %s — %s' % (name, detail))


print('[1] геометрия определяет автора')
W = 1000
left = {'text': 'привет', 'x': 40, 'y': 10, 'right': 300, 'conf': 99}
right = {'text': 'да', 'x': 700, 'y': 10, 'right': 960, 'conf': 99}
wide = {'text': 'системное', 'x': 20, 'y': 10, 'right': 980, 'conf': 99}
check('левый пузырь = входящее', chatread.side_of(left, W) == 'incoming')
check('правый пузырь = исходящее', chatread.side_of(right, W) == 'outgoing')
check('широкий блок = unknown, а не догадка',
      chatread.side_of(wide, W) == 'unknown', chatread.side_of(wide, W))

print('[2] шум интерфейса и время')
lines = [
    {'text': 'Онлайн', 'x': 400, 'y': 5, 'right': 600, 'conf': 99},
    {'text': 'Сегодня', 'x': 450, 'y': 30, 'right': 550, 'conf': 99},
    {'text': 'ПК в наличии', 'x': 40, 'y': 60, 'right': 400, 'conf': 99},
    {'text': '10:12', 'x': 350, 'y': 95, 'right': 400, 'conf': 99},
    {'text': 'Написать сообщение', 'x': 40, 'y': 900, 'right': 500, 'conf': 99},
]
msgs = chatread.group_messages(lines, W)
check('служебные строки отброшены', len(msgs) == 1, str(msgs))
check('время привязано к сообщению', msgs and msgs[0]['time'] == '10:12', str(msgs))
check('текст сообщения без времени',
      msgs and msgs[0]['text'] == 'ПК в наличии', str(msgs))

print('[3] суммы извлекаются')
m = chatread.group_messages(
    [{'text': 'отдам за 24 000 ₽ без торга', 'x': 40, 'y': 10, 'right': 500, 'conf': 99}], W)
check('цена из текста', m and m[0]['prices'] == [24000], str(m))

print('[4] дамп AccessibilityService')
dump = {'data': {'children': [
    {'text': 'Здравствуйте', 'bounds': {'left': 40, 'top': 100, 'right': 420},
     'children': []},
    {'text': 'да, актуально', 'bounds': {'left': 640, 'top': 200, 'right': 980},
     'children': []},
]}}
tmp = tempfile.mkdtemp(prefix='chatread-')
dp = os.path.join(tmp, 'dump.json')
json.dump(dump, open(dp, 'w'), ensure_ascii=False)
res = chatread.read(dp, from_dump=True, screen_w=1000)
check('дамп разобран', res['ok'] and len(res['messages']) == 2, str(res))
check('дамп даёт conf 100 (текст точный)',
      res['ok'] and all(m['conf'] == 100 for m in res['messages']), str(res))
check('источник помечен как dump', res.get('source') == 'dump', str(res.get('source')))

print('[5] нечитаемый вход не выдаётся за успех')
empty = os.path.join(tmp, 'empty.json')
open(empty, 'w').write('{}')
res = chatread.read(empty, from_dump=True, screen_w=1000)
check('пустой дамп = ok False', res['ok'] is False, str(res))
res = chatread.read(os.path.join(tmp, 'нет.png'))
check('отсутствующий файл не роняет', res['ok'] is False, str(res))

print('[6] сквозной OCR на сгенерированном скриншоте')
png = os.path.join(tmp, 'chat.png')
try:
    from PIL import Image, ImageDraw, ImageFont
    def font(sz):
        for p in ('/usr/share/fonts/noto/NotoSans-Regular.ttf',
                  '/usr/share/fonts/TTF/DejaVuSans.ttf',
                  '/usr/share/fonts/dejavu/DejaVuSans.ttf'):
            try: return ImageFont.truetype(p, sz)
            except Exception: pass
        return ImageFont.load_default()
    W2, H2 = 1080, 700
    img = Image.new('RGB', (W2, H2), (240, 242, 245))
    d = ImageDraw.Draw(img); F = font(34)
    rows = [('in', 'ПК ещё в наличии'), ('out', 'видеокарту отдельно продадите')]
    y = 60
    for side, text in rows:
        tw = d.textlength(text, font=F); bw = int(tw) + 50
        x = 40 if side == 'in' else W2 - 40 - bw
        d.rounded_rectangle([x, y, x + bw, y + 90], 18,
                            fill=(255, 255, 255) if side == 'in' else (200, 230, 255))
        d.text((x + 25, y + 18), text, font=F, fill=(20, 20, 20))
        y += 130
    img.save(png)
    res = chatread.read(png)
    check('OCR прочитал оба сообщения',
          res['ok'] and len(res['messages']) == 2, str(res.get('messages')))
    sides = [m['side'] for m in res.get('messages', [])]
    check('OCR определил обе стороны', sides == ['incoming', 'outgoing'], str(sides))
except ImportError:
    print('  Pillow нет — сквозной OCR пропущен')

import shutil
shutil.rmtree(tmp, ignore_errors=True)
print('%d прошло, %d упало' % (len(ok), len(bad)))
sys.exit(1 if bad else 0)
