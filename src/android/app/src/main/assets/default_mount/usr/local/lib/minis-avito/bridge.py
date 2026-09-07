#!/usr/bin/env python3
"""Мост browser_use -> scan.py. Схема против лимита execute_js (~3КБ ответ):

1. execute_js (chunk-режим): items.slice(START, START+10) ->
   btoa(unescape(encodeURIComponent(JSON.stringify(chunk))))
2. echo "<b64>" | bridge.py <mission> --append   # накапливает /tmp/bridge_lots.json
   echo "<b64>" | bridge.py <mission>            # последний чанк -> скоринг
"""
import base64, json, sys, subprocess
from pathlib import Path

HERE = Path(__file__).parent
BUF = Path('/tmp/bridge_lots.json')

def decode(raw):
    raw = raw.strip().strip('"').replace('\n', '').replace(' ', '')
    return json.loads(base64.b64decode(raw).decode('utf-8'))

def main():
    argv = sys.argv[1:]
    mission = argv[0] if argv and not argv[0].startswith('-') else 'rtx3070-russia'
    append = '--append' in argv
    raw = sys.stdin.read()
    chunk = decode(raw)
    if append:
        prev = json.loads(BUF.read_text()) if BUF.exists() else []
        prev.extend(chunk)
        BUF.write_text(json.dumps(prev, ensure_ascii=False))
        print(f'буфер: {len(prev)} лотов')
        return
    # финальный чанк: добавить и обработать всё
    if BUF.exists():
        prev = json.loads(BUF.read_text())
        prev.extend(chunk)
        items = prev
        BUF.unlink()
    else:
        items = chunk
    tmp = Path('/tmp/bridge_final.json')
    tmp.write_text(json.dumps(items, ensure_ascii=False), encoding='utf-8')
    r = subprocess.run(['python3', str(HERE / 'scan.py'), mission, '--process'],
                       stdin=open(tmp), capture_output=True, text=True)
    sys.stdout.write(r.stdout)
    if r.returncode: sys.exit(r.returncode)

if __name__ == '__main__':
    main()
