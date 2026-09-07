#!/usr/bin/env python3
"""Чтение чата Авито с экрана через AccessibilityService.

Источник: android-a11y-cli ui dump (точный текст, без OCR).
Активность Авито должна быть на переднем плане.

Использование:
  android-a11y-cli ui dump | python3 chatui.py            # из stdin
  chatui.py --dump /tmp/dump.json                          # из файла
"""
import json, re, sys

# Пузыри сообщений в чате Авито: текст + метка времени рядом.
TIME = re.compile(r'^(0?\d|1\d|2[0-3]):[0-5]\d$')
# Служебные узлы чата
NOISE_EXACT = re.compile(r'^(назад|отправить|написать сообщение|чат|онлайн|'
                         r'была в сети|сегодня|вчера|прочитано|доставлено|'
                         r'скопировать|авито\s*доставк[аи]|открыть профиль|'
                         r'пожаловаться|в сети|была? в? сети)$', re.I)
NOISE_PREFIX = re.compile(r'^(чат с |переписк)', re.I)

def nodes_texts(nodes):
    """Извлекаю (text, cy) — только видимые текстовые узлы."""
    out = []
    for n in nodes:
        if not isinstance(n, dict):
            continue
        t = (n.get('text') or n.get('contentDesc') or '').strip()
        if not t:
            continue
        c = n.get('center') or {}
        out.append((t, c.get('y', 0), c.get('x', 0), n))
    return out

AVITO_PKGS = ('ru.avito', 'com.avito')

def parse(dump, only_avito=True):
    nodes = (dump.get('data') or {}).get('nodes') or []
    if only_avito:
        av = [n for n in nodes if isinstance(n, dict)
              and str(n.get('packageName', '')).startswith(AVITO_PKGS)]
        if not av:
            pkgs = sorted(set(str(n.get('packageName')) for n in nodes if isinstance(n, dict)))
            raise SystemExit('Авито не на экране (активные пакеты: %s). '
                             'Открой чат в приложении Авито и повтори.' % ', '.join(pkgs[:3]))
        nodes = av
    rows = [r for r in nodes_texts(nodes) if not (NOISE_EXACT.match(r[0]) or NOISE_PREFIX.match(r[0]))]
    rows.sort(key=lambda r: (r[1], r[2]))
    msgs = []
    for t, y, x, n in rows:
        if TIME.match(t):
            if msgs and not msgs[-1].get('time'):
                msgs[-1]['time'] = t
            continue
        msgs.append({'text': t, 'y': y, 'x': x, 'time': None})
    w = 1260  # ширина экрана iQOO Neo 10
    for m in msgs:
        # Пузырь: левые = продавец, правые = ты. Граница 0.5 ширины.
        frac = (m['x'] + 60) / w
        m['who'] = 'продавец' if frac < 0.45 else ('ты' if frac > 0.55 else '?')
    return msgs

def detect_context(msgs):
    t = ' '.join(m['text'].lower() for m in msgs)
    if 'написать сообщение' in t or 'в сети' in t or 'печатает' in t:
        return 'chat'
    if 'в корзину' in t or 'купить с доставкой' in t or 'авито гарантия' in t:
        return 'item'
    return 'unknown'

def fmt(msgs):
    if not msgs:
        return 'сообщений не найдено — открыт ли чат Авито?'
    ctx = detect_context(msgs)
    if ctx == 'item':
        return 'На экране КАРТОЧКА объявления, не чат. Открой переписку (кнопка «Написать» → существующий чат).'
    lines = []
    for m in msgs:
        tm = (' ' + m['time']) if m['time'] else ''
        lines.append('[%-8s]%s %s' % (m['who'], tm, m['text']))
    return '\n'.join(lines)

def main():
    a = sys.argv[1:]
    if a and a[0] == '--dump':
        dump = json.load(open(a[1], encoding='utf-8'))
    else:
        dump = json.load(sys.stdin)
    print(fmt(parse(dump)))

if __name__ == '__main__':
    main()
