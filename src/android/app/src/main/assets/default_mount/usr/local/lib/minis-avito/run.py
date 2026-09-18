
import base64, binascii, glob, json, os, re, sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import scan
from render import render_scan
def main():
    argv = sys.argv[1:]
    mission = next((a for a in argv if not a.startswith(chr(45))), 'rtx3070-russia')
    args = set(a for a in argv if a.startswith(chr(45)))
    try:
        m = scan.load_mission(mission)
    except Exception as e:
        raise SystemExit('миссия не читается %s: %s' % (mission, e))
    raw = sys.stdin.read().strip()
    lots = []
    if raw:
        try:
            data = json.loads(base64.b64decode(raw).decode('utf-8'))
        except (binascii.Error, json.JSONDecodeError, ValueError) as e:
            raise SystemExit('битый вход (b64/json): %s' % e)
        if isinstance(data, dict):
            data = data.get('items') or [data]
        lots = data
    else:
        for f in sorted(glob.glob('/var/minis/offloads/tools/browser_use_*.txt'), key=os.path.getmtime)[-1:]:
            mm = re.search(r'[A-Za-z0-9+/=]{200,}', open(f, encoding='utf-8').read())
            if mm:
                lots = json.loads(base64.b64decode(mm.group(0)).decode('utf-8'))
        if not lots:
            raise SystemExit('нет входа: ни stdin, ни свежего offload')
    res = scan.run_on_items(lots, m)
    import datetime
    ts = datetime.datetime.now().strftime('%Y-%m-%d_%H%M%S')
    sid = os.environ.get('MINIS_SESSION', 's0')
    out = scan.save_result(res, mission, sid)
    if isinstance(out, tuple):
        out_path = out[0]
    else:
        out_path = out
    print('скан: %s (kept %s из %s, медиана %s)' % (
        out_path, res.get('kept'), res.get('found_raw'),
        (res.get('stats') or {}).get('median')))
    snapf = '/var/minis/shared/_data/avito/snapshots'
    os.makedirs(snapf, exist_ok=True)
    slim = {}
    for it in res.get('items', []):
        u = it.get('url') or it.get('raw', '')[:40]
        slim[u] = {'p': it.get('price'), 't': (it.get('raw') or '')[:80]}
    p = os.path.join(snapf, ts + '.json')
    json.dump({'at': ts, 'mission': mission, 'items': slim},
              open(p, 'w', encoding='utf-8'), ensure_ascii=False)
    print('снимок: %s (%d лотов)' % (p, len(slim)))
    top = 5
    for a in args:
        mm = re.match(r'--n(\d+)', a)
        if mm: top = int(mm.group(1))
    print()
    print(render_scan(res, top))
if __name__ == '__main__':
    main()
