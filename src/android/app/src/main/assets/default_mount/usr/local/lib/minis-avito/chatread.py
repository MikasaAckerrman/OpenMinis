#!/usr/bin/env python3
"""chatread — переписка Авито из скриншота: OCR -> структура диалога.

Зачем не через сеть: сессия анонимная, `/web/1/messenger/*` отдаёт 404, а логин
требует SMS. Значит единственный честный источник — то, что видно на экране
устройства. Читаем ТОЛЬКО то, что пользователь сам показал (скриншот или дамп
экрана), и ничего не отправляем.

Два входа:
  1. изображение (скриншот чата) -> tesseract -> строки с координатами;
  2. дамп AccessibilityService (`android-a11y-cli ui dump`) -> узлы с границами.

Второй путь точнее (текст без ошибок распознавания), но требует включённого
сервиса. OCR работает всегда, поэтому он основной fallback.

  python3 chatread.py screen.png
  python3 chatread.py screen.png --json
  python3 chatread.py dump.json --from-dump

Сторона сообщения определяется геометрией: в мессенджерах входящие прижаты к
левому краю, исходящие — к правому. Это наблюдаемый признак, а не догадка о
содержании.
"""
import argparse, json, os, re, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))

# Служебные строки интерфейса, которые не являются сообщениями. Список
# намеренно короткий: лучше оставить лишнюю строку, чем выбросить сообщение.
UI_NOISE = re.compile(
    r'^(написать сообщение|сообщение\.\.\.|введите сообщение|онлайн|была?в? сети|'
    r'печатает|доставлено|прочитано|отправить|назад|ещё|еще)$', re.I)

# Отдельная строка-время («10:12») — это метка сообщения, а не шум. Раньше она
# попадала в UI_NOISE и терялась, поэтому у сообщений не было времени.
BARE_TIME = re.compile(r'^([01]?\d|2[0-3]):([0-5]\d)$')
# Разделители дней остаются служебными: к конкретному сообщению не относятся.
DAY_SEP = re.compile(r'^(сегодня|вчера|\d{1,2}\s+[а-яё]+)$', re.I)

TIME_RE = re.compile(r'\b([01]?\d|2[0-3]):([0-5]\d)\b')
MONEY_RE = re.compile(r'(\d[\d\s]{2,})\s*(?:₽|руб)', re.I)


def ocr_lines(path, lang='rus+eng', psm='6'):
    """Строки с координатами через tesseract TSV. Без координат нет стороны."""
    cmd = ['tesseract', path, 'stdout', '-l', lang, '--psm', psm, 'tsv']
    try:
        out = subprocess.run(cmd, capture_output=True, timeout=180).stdout
    except (subprocess.TimeoutExpired, OSError) as e:
        return [], 'tesseract недоступен: %s' % e
    text = out.decode('utf-8', 'replace').splitlines()
    if len(text) < 2:
        return [], 'tesseract не вернул TSV'
    rows = []
    for line in text[1:]:
        p = line.split('\t')
        if len(p) < 12:
            continue
        try:
            left, top, width, height, conf = (int(p[6]), int(p[7]), int(p[8]),
                                              int(p[9]), float(p[10]))
        except ValueError:
            continue
        word = p[11].strip()
        if not word or conf < 40:            # мусор распознавания отбрасываем
            continue
        rows.append({'x': left, 'y': top, 'w': width, 'h': height,
                     'conf': conf, 'text': word,
                     'line': (int(p[1]), int(p[2]), int(p[3]), int(p[4]))})
    # Склейка слов в строки по block/par/line из TSV — надёжнее, чем по y.
    grouped = {}
    for r in rows:
        grouped.setdefault(r['line'], []).append(r)
    lines = []
    for key in sorted(grouped, key=lambda k: min(w['y'] for w in grouped[k])):
        words = sorted(grouped[key], key=lambda w: w['x'])
        lines.append({
            'text': ' '.join(w['text'] for w in words),
            'x': min(w['x'] for w in words),
            'y': min(w['y'] for w in words),
            'right': max(w['x'] + w['w'] for w in words),
            'conf': round(sum(w['conf'] for w in words) / len(words), 1),
        })
    return lines, None


def dump_lines(path):
    """Строки из дампа AccessibilityService: текст точный, границы есть."""
    try:
        data = json.load(open(path, encoding='utf-8'))
    except (OSError, ValueError) as e:
        return [], 'дамп не читается: %s' % e
    lines = []

    def walk(node):
        if isinstance(node, list):
            for x in node:
                walk(x)
            return
        if not isinstance(node, dict):
            return
        txt = (node.get('text') or node.get('contentDescription') or '').strip()
        b = node.get('bounds') or node.get('boundsInScreen') or {}
        if txt:
            lines.append({
                'text': txt,
                'x': int(b.get('left', b.get('l', 0)) or 0),
                'y': int(b.get('top', b.get('t', 0)) or 0),
                'right': int(b.get('right', b.get('r', 0)) or 0),
                'conf': 100.0,
            })
        for k in ('children', 'nodes', 'child'):
            if node.get(k):
                walk(node[k])

    walk(data.get('data') if isinstance(data, dict) and 'data' in data else data)
    lines.sort(key=lambda r: r['y'])
    return lines, None


def side_of(line, screen_w):
    """Входящее/исходящее по геометрии пузыря.

    Признак наблюдаемый: центр входящего сообщения левее середины, исходящего —
    правее. Если пузырь широкий и занимает почти всю ширину, сторона неизвестна:
    выдумывать её нельзя, иначе автор сообщения будет назначен неверно.
    """
    center = (line['x'] + line['right']) / 2 if line['right'] > line['x'] else line['x']
    width = max(1, line['right'] - line['x'])
    if width > screen_w * 0.78:
        return 'unknown'
    if center < screen_w * 0.45:
        return 'incoming'
    if center > screen_w * 0.55:
        return 'outgoing'
    return 'unknown'


def group_messages(lines, screen_w, gap=42):
    """Склеить строки в сообщения: одна сторона + вертикальная близость."""
    msgs = []
    for ln in lines:
        text = ln['text'].strip()
        if not text or UI_NOISE.match(text) or DAY_SEP.match(text):
            continue
        # Одинокая метка времени принадлежит ближайшему сообщению выше.
        if BARE_TIME.match(text):
            if msgs and not msgs[-1].get('time_mark'):
                msgs[-1]['time_mark'] = text
            continue
        side = side_of(ln, screen_w)
        if (msgs and msgs[-1]['side'] == side
                and ln['y'] - msgs[-1]['y_end'] <= gap):
            msgs[-1]['text'] += ' ' + text
            msgs[-1]['y_end'] = ln['y']
            msgs[-1]['conf'] = min(msgs[-1]['conf'], ln['conf'])
        else:
            msgs.append({'side': side, 'text': text, 'y': ln['y'],
                         'y_end': ln['y'], 'conf': ln['conf']})
    for m in msgs:
        # Время может стоять внутри пузыря или отдельной строкой рядом.
        t = TIME_RE.search(m['text'])
        m['time'] = m.pop('time_mark', None) or (t.group(0) if t else None)
        m['prices'] = [int(re.sub(r'\D', '', x)) for x in MONEY_RE.findall(m['text'])]
        m.pop('y_end', None)
    return msgs


def read(path, from_dump=False, screen_w=None):
    if from_dump:
        lines, err = dump_lines(path)
    else:
        lines, err = ocr_lines(path)
    if err:
        return {'ok': False, 'error': err, 'messages': []}
    if not lines:
        return {'ok': False, 'error': 'текст не распознан', 'messages': []}
    width = screen_w or max(l['right'] for l in lines) or 1080
    msgs = group_messages(lines, width)
    low = [m for m in msgs if m['conf'] < 70]
    return {
        'ok': True, 'source': 'dump' if from_dump else 'ocr',
        'screen_width': width, 'lines': len(lines), 'messages': msgs,
        'low_confidence': len(low),
        'warning': ('часть строк распознана неуверенно — проверь текст глазами'
                    if low else None),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('path', help='скриншот чата или дамп экрана')
    ap.add_argument('--from-dump', action='store_true')
    ap.add_argument('--width', type=int, default=None, help='ширина экрана в px')
    ap.add_argument('--json', action='store_true')
    a = ap.parse_args()
    if not os.path.exists(a.path):
        print('нет файла: %s' % a.path)
        return 1
    res = read(a.path, a.from_dump, a.width)
    if a.json:
        print(json.dumps(res, ensure_ascii=False))
        return 0 if res['ok'] else 1
    if not res['ok']:
        print('не прочитал: %s' % res['error'])
        return 1
    print('источник: %s | строк: %d | ширина: %d' %
          (res['source'], res['lines'], res['screen_width']))
    if res['warning']:
        print('ВНИМАНИЕ: %s' % res['warning'])
    print()
    mark = {'incoming': 'продавец', 'outgoing': 'ты', 'unknown': '?'}
    for m in res['messages']:
        print('[%-8s]%s %s' % (mark[m['side']],
                               (' %s' % m['time']) if m['time'] else '',
                               m['text']))
        if m['prices']:
            print('           суммы: %s' % ', '.join(str(p) for p in m['prices']))
    return 0


if __name__ == '__main__':
    sys.exit(main())
