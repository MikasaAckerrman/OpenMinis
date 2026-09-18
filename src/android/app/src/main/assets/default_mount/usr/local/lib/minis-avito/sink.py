#!/usr/bin/env python3
"""Приёмник данных из браузера: браузер POST-ит JSON, скрипт складывает в файл.
Так выдача не проходит через контекст диалога — экономит токены.
Слушает ТОЛЬКО 127.0.0.1 (наружу не открыт, но аутентификации нет —
не запускать на порту, доступном из сети).
"""
import json, os, sys
from http.server import BaseHTTPRequestHandler, HTTPServer

OUT = sys.argv[1] if len(sys.argv) > 1 else '/tmp/avito_raw.json'
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8791

class H(BaseHTTPRequestHandler):
    def _cors(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type')

    def do_OPTIONS(self):
        self.send_response(204); self._cors(); self.end_headers()

    def do_GET(self):
        """Отдаёт .js из каталога скрипта с CORS — чтобы страница Avito могла
        подтянуть краулер через fetch+eval и не гнать его текст через диалог."""
        name = os.path.basename(self.path.split('?')[0]) or 'crawl.js'
        path = os.path.join(os.path.dirname(os.path.abspath(__file__)), name)
        if not name.endswith('.js') or not os.path.exists(path):
            self.send_response(404); self._cors(); self.end_headers(); return
        body = open(path, 'rb').read()
        self.send_response(200); self._cors()
        self.send_header('Content-Type', 'application/javascript; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers(); self.wfile.write(body)

    def do_POST(self):
        n = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(n)
        try:
            payload = json.loads(body)
        except Exception as e:
            self.send_response(400); self._cors(); self.end_headers()
            self.wfile.write(str(e).encode()); return
        # /progress — состояние фонового обхода, пишется отдельным файлом
        if self.path.rstrip('/').endswith('progress'):
            json.dump(payload.get('state', payload),
                      open(OUT + '.state', 'w'), ensure_ascii=False)
            self.send_response(200); self._cors(); self.end_headers()
            self.wfile.write(b'{"ok":true}'); return
        # /details — карточки объявлений, накапливаются отдельным файлом
        if self.path.rstrip('/').endswith('details'):
            det = payload.get('details', [])
            path = OUT + '.details'
            acc = []
            if os.path.exists(path):
                try: acc = json.load(open(path))
                except Exception: acc = []
            acc.extend(det)
            json.dump(acc, open(path, 'w'), ensure_ascii=False)
            self.send_response(200); self._cors(); self.end_headers()
            self.wfile.write(json.dumps({'saved': len(det), 'total': len(acc)}).encode()); return
        acc = []
        if os.path.exists(OUT):
            try: acc = json.load(open(OUT))
            except Exception: acc = []
        if isinstance(payload, list):
            items = payload
        elif isinstance(payload, dict) and 'items' in payload:
            items = payload['items']
        else:
            # Словарь без items (b64-чанк, произвольные данные) — НЕ выбрасываем
            # молча (дефект: 200-ответ при потерянных данных). Сохраняем целиком.
            items = [payload]
        acc.extend(items)
        json.dump(acc, open(OUT, 'w'), ensure_ascii=False)
        self.send_response(200); self._cors()
        self.send_header('Content-Type', 'application/json'); self.end_headers()
        self.wfile.write(json.dumps({'saved': len(items), 'total': len(acc)}).encode())

    def log_message(self, *a): pass

if __name__ == '__main__':
    print(f'sink -> {OUT} on 127.0.0.1:{PORT}', flush=True)
    HTTPServer(('127.0.0.1', PORT), H).serve_forever()
