#!/usr/bin/env python3
"""watch.py — снимки выдачи во времени: кто снизил цену, что исчезло, что новое.

Одиночный сбор показывает срез. Ценно другое:
  - цена упала на 3000 -> продавец торопится, торг реален;
  - лот исчез за час после появления -> так уходят нормальные карты, значит
    оставшиеся по этой цене чем-то плохи;
  - лот перевыставлен с новым id и той же ценой -> висит давно, спрос нулевой;
  - цена выросла -> продавец пробует рынок, спешить некуда.

Каждый вызов save делает снимок текущего items.json. diff сравнивает два
последних. Снимки лежат рядом с данными, весят копейки (только id/цена/титул).

  python3 watch.py save              снимок сейчас
  python3 watch.py diff              что изменилось с прошлого снимка
  python3 watch.py list              какие снимки есть
  python3 watch.py history <id>      цена конкретного лота по снимкам
"""
import json, os, re, sys
import datetime as dt

DATA = os.environ.get('AVITO_RAW', '/var/minis/shared/_data/avito/items.json')
SNAPDIR = os.path.join(os.path.dirname(DATA), 'snapshots')


def load_items():
    items = {}
    for suffix in ('', '.details'):
        p = DATA + suffix
        if not os.path.exists(p):
            continue
        try:
            data = json.load(open(p, encoding='utf-8'))
        except Exception:
            continue
        for x in (data if isinstance(data, list) else [data]):
            if x.get('id'):
                items.setdefault(str(x['id']), {}).update(x)
    return items


def snap_path(ts=None):
    ts = ts or dt.datetime.now()
    return os.path.join(SNAPDIR, ts.strftime('%Y%m%d-%H%M%S') + '.json')


def snaps():
    if not os.path.isdir(SNAPDIR):
        return []
    # Два формата имён: watch (20260907-184907) и run.py (2026-09-07_190719).
    # Сортировка по mtime — имена несравнимы между форматами.
    fs = [os.path.join(SNAPDIR, f) for f in os.listdir(SNAPDIR)
          if re.match(r'(\d{8}-\d{6}|\d{4}-\d{2}-\d{2}_\d{6})\.json$', f)]
    return sorted(fs, key=os.path.getmtime)


def cmd_save(args):
    os.makedirs(SNAPDIR, exist_ok=True)
    items = load_items()
    if not items:
        print('нет данных для снимка')
        return 1
    # В снимок кладём только то, что меняется — иначе файлы раздуются описаниями.
    slim = {i: {'p': x.get('price'), 't': (x.get('title') or '')[:80],
                's': x.get('seller_hash'), 'c': x.get('city')}
            for i, x in items.items()}
    p = snap_path()
    json.dump({'at': dt.datetime.now().isoformat(timespec='seconds'), 'items': slim},
              open(p, 'w', encoding='utf-8'), ensure_ascii=False)
    print('снимок: %s (%d лотов)' % (p, len(slim)))
    return 0


def cmd_list(args):
    s = snaps()
    if not s:
        print('снимков нет — сделай: watch.py save')
        return 0
    for p in s:
        d = json.load(open(p, encoding='utf-8'))
        print('%s  %d лотов' % (d.get('at') or os.path.basename(p), len(d['items'])))
    return 0


def fmt(n):
    return '{:,}'.format(int(n)).replace(',', ' ') if n else '—'


def cmd_diff(args):
    s = snaps()
    if len(s) < 2:
        print('нужно минимум два снимка (сейчас %d). Сделай watch.py save позже.' % len(s))
        return 1
    a = json.load(open(s[-2], encoding='utf-8'))
    b = json.load(open(s[-1], encoding='utf-8'))
    # Сравниваем только одну миссию: смешение 3070/3060 даёт 219 «ушли»
    ma, mb = a.get('mission'), b.get('mission')
    if ma and mb and ma != mb:
        prev = [x for x in reversed(s[:-1])
                if json.load(open(x, encoding='utf-8')).get('mission') == mb]
        if not prev:
            print('для миссии %r нет предыдущего снимка (последний: %r)' % (mb, ma))
            return 1
        a = json.load(open(prev[0], encoding='utf-8'))
        print('(предыдущий снимок миссии %s: %s)' % (mb, prev[0].split('/')[-1]))
    old, new = a['items'], b['items']
    print('сравнение: %s -> %s' % (a.get('at'), b.get('at')))

    down, up, gone, fresh = [], [], [], []
    for i, x in new.items():
        if i not in old:
            fresh.append((i, x))
            continue
        po, pn = old[i].get('p'), x.get('p')
        if po and pn and pn != po:
            (down if pn < po else up).append((i, x, po, pn))
    for i, x in old.items():
        if i not in new:
            gone.append((i, x))

    if down:
        print()
        print('=== ЦЕНУ СНИЗИЛИ (%d) — торг реален ===' % len(down))
        for i, x, po, pn in sorted(down, key=lambda r: (r[3] - r[2]) / r[2]):
            print('  %s -> %s ₽  (%+.0f%%)  %s'
                  % (fmt(po), fmt(pn), (pn - po) / po * 100, x['t'][:52]))
    if up:
        print()
        print('=== цену подняли (%d) ===' % len(up))
        for i, x, po, pn in up[:8]:
            print('  %s -> %s ₽  %s' % (fmt(po), fmt(pn), x['t'][:52]))
    if gone:
        print()
        print('=== ушли из выдачи (%d) — продано или снято ===' % len(gone))
        for i, x in gone[:12]:
            print('  %8s ₽  %s' % (fmt(x.get('p')), x['t'][:56]))
    if fresh:
        print()
        print('=== новые (%d) ===' % len(fresh))
        for i, x in fresh[:12]:
            print('  %8s ₽  %s' % (fmt(x.get('p')), x['t'][:56]))
    if not (down or up or gone or fresh):
        print('изменений нет')
    return 0


def cmd_history(args):
    if not args:
        print('укажи id лота: watch.py history 8250907170')
        return 1
    wid = str(args[0])
    rows = []
    for p in snaps():
        d = json.load(open(p, encoding='utf-8'))
        x = d['items'].get(wid)
        if x:
            rows.append((d.get('at') or os.path.basename(p), x.get('p'), x['t']))
    if not rows:
        print('лот %s в снимках не встречается' % wid)
        return 1
    print('%s  %s' % (wid, rows[0][2][:60]))
    prev = None
    for at, price, _ in rows:
        mark = ''
        if prev and price and price != prev:
            mark = '  %+.0f%%' % ((price - prev) / prev * 100)
        print('  %s  %8s ₽%s' % (at, fmt(price), mark))
        prev = price or prev
    return 0


CMDS = {'save': cmd_save, 'diff': cmd_diff, 'list': cmd_list, 'history': cmd_history}

if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else 'list'
    if cmd not in CMDS:
        print(__doc__)
        sys.exit(1)
    sys.exit(CMDS[cmd](sys.argv[2:]))
