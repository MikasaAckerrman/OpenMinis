#!/usr/bin/env python3
"""Full check of ALL lots: card -> description -> flags for each.

Usage:
  checkall.py <scan.json> --urls            # b64 list of urls (for XHR batch)
  checkall.py <scan.json> --merge <b64file> # merge cards back, rescore
"""
import base64, json, sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import scan, descflags

def main():
    a = sys.argv[1:]
    if not a:
        print(__doc__)
        return 1
    path = a[0]
    d = json.load(open(path, encoding='utf-8'))
    items = d['items']
    if '--urls' in a:
        urls = [i['url'] for i in items]
        print(base64.b64encode(json.dumps(urls).encode()).decode())
        return 0
    if '--merge' in a:
        raw = open(a[a.index('--merge') + 1]).read().strip()
        cards = json.loads(base64.b64decode(raw + '=' * (-len(raw) % 4)).decode())
        by = {c['url']: c for c in cards}
        covered = 0
        for it in items:
            c = by.get(it['url'])
            if not c:
                it['flags'] = it.get('flags') or []
                it['cover'] = 'miss'
                continue
            desc = c.get('desc') or ''
            fl = descflags.extract_flags(desc)
            it['desc'] = desc[:400]
            it['flags'] = sorted(set((it.get('flags') or []) + fl))
            it['cover'] = 'full'
            covered += 1
        d['coverage'] = '%d/%d' % (covered, len(items))
        m = scan.load_mission(d['mission'])
        items, median = scan.score_items(items, m)
        items.sort(key=lambda x: (x.get('risk_score', 999), x.get('price') or 999999))
        json.dump(d, open(path, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
        print('coverage: %s' % d['coverage'])
        for i, it in enumerate(items, 1):
            print('%2d. %-7s risk %-3s %s %s' % (
                i, it.get('price') or '-', it.get('risk_score'),
                (it.get('raw') or '')[:44], ','.join(it['flags'][:5])))
        return 0
    print(__doc__)
    return 1

if __name__ == '__main__':
    sys.exit(main())
