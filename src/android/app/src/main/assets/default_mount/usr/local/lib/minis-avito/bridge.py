#!/usr/bin/env python3
"""Мост browser_use -> scan.py. Схема против лимита execute_js (~3КБ ответ):

1. execute_js: items.slice(A,B) -> btoa(unescape(encodeURIComponent(JSON.stringify(chunk))))
2. echo "<b64>" | bridge.py <mission> --append   # буфер /tmp/bridge_<mission>.json
   echo "<b64>" | bridge.py <mission>            # финал -> скоринг scan.py

Параллельная безопасность: буфер и tmp-файл на МИССИЮ (две сессии с
разными миссиями не конфликтуют). Финальный файл с PID — без гонки.
Буфер чистится только ПОСЛЕ успешного скоринга (крэш scan.py не
теряет накопленные лоты).
"""
import base64, binascii, json, os, re, subprocess, sys
from pathlib import Path

HERE = Path(__file__).parent
TMP = Path('/tmp')

def decode(raw):
    raw = ''.join(raw.split()).strip('"')
    if not raw:
        raise SystemExit('пустой stdin: base64 не передан')
    try:
        return json.loads(base64.b64decode(raw).decode('utf-8'))
    except (binascii.Error, ValueError) as e:
        raise SystemExit(f'битый base64/json: {e}')

def main():
    argv = sys.argv[1:]
    mission = next((a for a in argv if not a.startswith('-')), 'rtx3070-russia')
    safe = re.sub(r'[^a-zA-Z0-9._-]', '_', mission)
    buf = TMP / f'bridge_{safe}.json'
    append = '--append' in argv
    from_offload = '--from-offload' in argv
    # Параллельные вкладки: mtime-порядок ненадёжен (файл A может записаться
    # позже начала B) — указывай offload ЯВНО через AVITO_OFFLOAD.
    explicit = os.environ.get('AVITO_OFFLOAD')
    if from_offload or explicit:
        if explicit:
            src = explicit
            if not os.path.exists(src):
                raise SystemExit(f'offload не найден: {src}')
        else:
            import glob
            offs = sorted(glob.glob('/var/minis/offloads/tools/browser_use_*.txt'),
                          key=os.path.getmtime)
            if not offs:
                raise SystemExit('нет offload-файлов execute_js')
            src = offs[-1]
        t = open(src, encoding='utf-8').read()
        m = re.search(r'[A-Za-z0-9+/=]{500,}', t)
        if not m:
            raise SystemExit(f'в offload нет base64-блока: {src}')
        raw = m.group(0)
    else:
        raw = sys.stdin.read()
    chunk = decode(raw)

    if append:
        prev = json.loads(buf.read_text(encoding='utf-8')) if buf.exists() else []
        prev.extend(chunk)
        tmp_write = buf.with_suffix(f'.{os.getpid()}.tmp')
        tmp_write.write_text(json.dumps(prev, ensure_ascii=False), encoding='utf-8')
        tmp_write.replace(buf)
        print(f'буфер[{safe}]: {len(prev)} лотов')
        return

    items = chunk
    if buf.exists():
        # Дедуп при слиянии: пагинация сдвигает выдачу, один лот может
        # прийти в двух чанках. Дубли завышают вес лота в медиане.
        from scan import dedup_by_url
        items, _ = dedup_by_url(json.loads(buf.read_text(encoding='utf-8')) + chunk)
    final = TMP / f'bridge_{safe}_{os.getpid()}.json'
    final.write_text(json.dumps(items, ensure_ascii=False), encoding='utf-8')
    try:
        r = subprocess.run(['python3', str(HERE / 'scan.py'), mission, '--process'],
                           stdin=open(final), capture_output=True, text=True)
        sys.stdout.write(r.stdout)
        if r.returncode:
            sys.stderr.write(r.stderr[-500:])
            sys.exit(r.returncode)
        if buf.exists():
            buf.unlink()          # чистим ТОЛЬКО после успеха
    finally:
        final.unlink(missing_ok=True)

if __name__ == '__main__':
    main()
