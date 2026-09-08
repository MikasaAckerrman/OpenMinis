#!/usr/bin/env python3
"""Массовое чтение чатов Авито через приложение + AccessibilityService.

Пайплайн: список чатов (dump+scroll) -> для каждого: tap -> chatui --full
-> back -> следующий. Результат: JSON со всеми переписками.

Требует: AccessibilityService включён (Settings -> Accessibility -> Minis),
приложение Авито залогинено, экран открыт на списке чатов (вкладка «Чаты»).

Использование:
  python3 chats.py --list           # только список чатов (быстро, безопасно)
  python3 chats.py --read N         # прочитать первые N чатов целиком
  python3 chats.py --read all       # все чаты (долго: ~10-15 сек на чат)
  python3 chats.py --read N --only "Дмитрий,Алина"   # только эти продавцы
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

OUT_DIR = '/var/minis/shared/_data/avito/chats'
A11Y = 'android-a11y-cli'

# --- слой взаимодействия с устройством -------------------------------------

def a11y(*args, timeout=30):
    """Вызов android-a11y-cli с таймаутом. Возвращает (rc, stdout)."""
    cmd = [A11Y] + list(args)
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout,
                           stdin=subprocess.DEVNULL)
        return r.returncode, r.stdout
    except subprocess.TimeoutExpired:
        return 124, ''


def dump_screen():
    rc, out = a11y('ui', 'dump', '--compact', timeout=15)
    if rc != 0:
        raise RuntimeError('a11y dump не удался: rc=%s' % rc)
    # --compact может не работать с dump; пробуем обычный вывод
    try:
        return json.loads(out)
    except json.JSONDecodeError:
        # вывод может быть JSON-конверт; пробуем вытащить data
        m = re.search(r'\{.*\}', out, re.S)
        if m:
            return json.loads(m.group(0))
        raise RuntimeError('dump вернул не-JSON: %s...' % out[:120])


def nodes_text(root):
    """Все текстовые узлы дерева дампа (плоско).

    Конверт a11y-cli: {ok, data: {...}} — разворачиваем как chatread.
    """
    if isinstance(root, dict) and 'data' in root:
        root = root['data']
    out = []
    def walk(n):
        if isinstance(n, dict):
            t = n.get('text') or n.get('contentDescription') or ''
            if t.strip():
                out.append({'text': t.strip(),
                            'bounds': n.get('bounds') or n.get('rect') or ''})
            for c in n.get('children', []) or []:
                walk(c)
        elif isinstance(n, list):
            for c in n:
                walk(c)
    walk(root)
    return out


def avito_on_screen(dump_json):
    """Проверка: на экране приложение Авито (ru.avito / com.avito.android)."""
    raw = json.dumps(dump_json, ensure_ascii=False)
    return ('avito' in raw.lower())


def scroll_list():
    """Прокрутка списка чатов вниз. Возвращает True если было движение."""
    rc, out = a11y('scroll', 'xy', '540', '1600', '--compact', timeout=10)
    return rc == 0


# --- список чатов ------------------------------------------------------------

CHAT_ROW_NOISE = re.compile(
    r'^(Чаты|Сообщения|Поиск|Избранное|Профиль|Объявления|Уведомления|'
    r'Написать сообщение|Назад|Ещё|ещё|\d+)$')


def parse_chat_list(dump_json):
    """Из дампа экрана списка чатов вытянуть строки: имя + превью + время.

    Строка чата в приложении: имя продавца, сниппет последнего сообщения,
    метка времени. Ложные строки (навигация, кнопки) отфильтровываются.
    """
    if not avito_on_screen(dump_json):
        raise RuntimeError('Авито не на экране. Открой вкладку «Чаты» в приложении.')
    texts = nodes_text(dump_json)
    rows = []
    for t in texts:
        s = t['text']
        if CHAT_ROW_NOISE.match(s) or len(s) < 3:
            continue
        # строка времени в конце строки чата: «12:30», «Вс», «5 сент»
        has_time = bool(re.search(r'(\d{1,2}:\d{2}|^\d{1,2} [а-яё]+$|^(Пн|Вт|Ср|Чт|Пт|Сб|Вс)$)', s))
        rows.append({'text': s, 'has_time': has_time, 'bounds': t['bounds']})
    return rows


# --- чтение одного чата -------------------------------------------------------

def read_chat_full():
    """Полное чтение открытого чата через chatread (пайплайн из файла).

    Возвращает список сообщений [{side, text, time, prices}] или None.
    """
    import chatread
    import tempfile
    rc, out = a11y('ui', 'dump', '--compact', timeout=20)
    if rc != 0:
        return None
    # a11y-cli печатает JSON-конверт {ok, data} или сырой дамп
    try:
        data = json.loads(out)
    except json.JSONDecodeError:
        return None
    if isinstance(data, dict) and 'data' in data:
        data = data['data']
    with tempfile.NamedTemporaryFile('w', suffix='.json', delete=False,
                                     encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False)
        tmp = f.name
    try:
        res = chatread.read(tmp, from_dump=True)
    finally:
        os.unlink(tmp)
    if res.get('ok'):
        return res['messages']
    return None


# --- главный цикл --------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--list', action='store_true', help='только список чатов')
    ap.add_argument('--read', default='0',
                    help='сколько чатов прочитать: N или all')
    ap.add_argument('--only', default='',
                    help='имена продавцов через запятую (читать только их)')
    args = ap.parse_args()

    os.makedirs(OUT_DIR, exist_ok=True)

    # Снимок списка чатов
    print('Снимаю список чатов...')
    dump = dump_screen()
    rows = parse_chat_list(dump)
    print('Строк на экране: %d' % len(rows))

    # Прокрутка для большего покрытия (только в --list и --read)
    seen_texts = set(r['text'] for r in rows)
    if args.list or args.read != '0':
        for _ in range(5):  # до 5 экранов списка
            if not scroll_list():
                break
            time.sleep(1.2)
            try:
                dump2 = dump_screen()
                rows2 = parse_chat_list(dump2)
            except RuntimeError:
                break
            new = [r for r in rows2 if r['text'] not in seen_texts]
            if not new:
                break
            rows.extend(new)
            seen_texts.update(r['text'] for r in new)

    # Фильтр «только эти продавцы»
    if args.only:
        names = [n.strip().lower() for n in args.only.split(',') if n.strip()]
        rows = [r for r in rows if any(n in r['text'].lower() for n in names)]

    ts = time.strftime('%Y-%m-%d_%H%M%S')
    list_path = os.path.join(OUT_DIR, 'chatlist-%s.json' % ts)
    with open(list_path, 'w', encoding='utf-8') as f:
        json.dump({'ts': ts, 'rows': rows}, f, ensure_ascii=False, indent=1)
    print('Список сохранён: %s (%d строк)' % (list_path, len(rows)))

    if args.list:
        for r in rows[:20]:
            mark = '⏰' if r['has_time'] else '  '
            print(' %s %s' % (mark, r['text'][:80]))
        return

    # Полное чтение
    n = 999 if args.read == 'all' else int(args.read)
    print('Читаю %s чатов полностью...' % n)
    results = {}
    for i, r in enumerate(rows[:n]):
        # tap по строке чата
        b = r.get('bounds', '')
        rc = 1
        if b:
            # bounds может быть [x1,y1,x2,y2] или строкой
            try:
                if isinstance(b, str):
                    nums = [int(x) for x in re.findall(r'\d+', b)]
                else:
                    nums = list(b)
                if len(nums) >= 4:
                    cx, cy = (nums[0] + nums[2]) // 2, (nums[1] + nums[3]) // 2
                    rc, _ = a11y('tap', 'xy', str(cx), str(cy), '--compact', timeout=10)
            except (ValueError, TypeError):
                pass
        if rc != 0:
            # fallback: tap по тексту первого слова имени
            first = r['text'].split()[0]
            rc, _ = a11y('tap', 'text', first, '--compact', timeout=10)
        time.sleep(1.5)
        msgs = read_chat_full()
        if msgs:
            results[r['text'][:40]] = msgs
            print('[%d/%d] %s: %d сообщений' % (i + 1, min(n, len(rows)),
                                                 r['text'][:30], len(msgs)))
        else:
            print('[%d/%d] %s: не прочитан' % (i + 1, min(n, len(rows)),
                                               r['text'][:30]))
        # назад к списку
        a11y('input', 'key', 'BACK', '--compact', timeout=10)
        time.sleep(1.0)

    out_path = os.path.join(OUT_DIR, 'chats-%s.json' % ts)
    json.dump({'ts': ts, 'chats': results}, open(out_path, 'w', ensure_ascii=False),
              ensure_ascii=False, indent=1)
    print('Готово: %s (%d чатов с историей)' % (out_path, len(results)))


if __name__ == '__main__':
    main()
