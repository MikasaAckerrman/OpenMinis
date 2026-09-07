#!/usr/bin/env python3
"""Осмотр фото объявления моделью зрения.

  python3 photo.py <item_id|url> [--n 3] [--ask "свой вопрос"]

Берёт ссылки на фото из `items.json.details`, скачивает разрешённые CDN-URL
через общий `avito-images` channel (lease/IP budget), затем отдаёт модели с
`image_input` из `minis-model-use list`.
"""
import json, os, re, sys, base64, subprocess, argparse, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ENGINE = os.path.join(os.path.dirname(HERE), 'web-deep')
if ENGINE not in sys.path:
    sys.path.insert(0, ENGINE)
from channel import Channel

RAW = os.environ.get('AVITO_RAW',
                     '/var/minis/shared/_data/avito/items.json')
ASK = ('Это фото товара с Авито. Опиши по фото: что видно, состояние, следы '
       'вскрытия/пыли/повреждений, читаются ли надписи и модель. Отдельно скажи, '
       'чего на фото НЕ видно и какие фото стоит запросить у продавца. 5-7 строк.')


def pick_model():
    out = subprocess.run(['minis-model-use', 'list'], capture_output=True, text=True).stdout
    try:
        models = json.loads(out).get('models', [])
    except Exception:
        return None
    for m in models:
        if 'image_input' in (m.get('modalities') or []):
            return m.get('model_id') or m.get('display_name')
    return None


def find_item(key):
    for path in (RAW + '.details', RAW):
        if not os.path.exists(path):
            continue
        for x in json.load(open(path)):
            if str(x.get('id')) == str(key) or key in (x.get('url') or ''):
                if x.get('images'):
                    return x
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('key', help='id объявления или его URL')
    ap.add_argument('--n', type=int, default=3, help='сколько фото показать модели')
    ap.add_argument('--ask', default=ASK)
    a = ap.parse_args()

    m = re.search(r'_(\d{6,})$', a.key.split('?')[0])
    key = m.group(1) if m else a.key

    it = find_item(key)
    if not it:
        print('Не нашёл объявление с фото в %s(.details). Сначала собери его '
              'crawl.js с details>0 или one.js.' % RAW)
        return 1
    model = pick_model()
    if not model:
        print('Нет модели с image_input. Проверь: minis-model-use list')
        return 1

    content = [{'type': 'text', 'text': '%s\nЗаголовок: %s\nЦена: %s' %
                (a.ask, it.get('title'), it.get('price'))}]
    # Фото — отдельная операция того же ресурса: она расходует общий
    # avito state/IP budget и не может идти голым curl в обход gate.
    ch = Channel('avito-images')
    ok = 0
    errors = []
    for url in it['images'][:max(0, a.n)]:
        host = re.sub(r'^https?://', '', str(url)).split('/')[0].lower().split(':')[0]
        if host not in ('img.avito.st', 'img.avito.ru'):
            errors.append('неожиданный CDN: ' + host)
            continue
        r = ch.get(url, timeout=30, retries=1, binary=True)
        if not r.ok or not isinstance(r.body, bytes) or len(r.body) < 2000:
            errors.append('%s: HTTP %s / %d байт' % (host, r.status, len(r.body or b'')))
            continue
        b = base64.b64encode(r.body).decode()
        content.append({'type': 'image_url',
                        'image_url': {'url': 'data:image/jpeg;base64,' + b}})
        ok += 1
    if errors:
        print('фото пропущены: %s' % '; '.join(errors[:3]), file=sys.stderr)
    if not ok:
        print('Ни одно фото не скачалось'); return 1

    # Запрос к model-use временный; канал фото уже скачал bytes в память.
    # Раньше tmp использовался без создания — любой успешный download падал
    # NameError до вызова модели.
    tmp = tempfile.mkdtemp(prefix='avito-photo-')
    req = os.path.join(tmp, 'req.json')
    json.dump({'messages': [{'role': 'user', 'content': content}], 'max_tokens': 700},
              open(req, 'w'))
    print('модель: %s | фото: %d | %s\n' % (model, ok, it.get('url') or ''))
    r = subprocess.run(['minis-model-use', 'run', '--model', model, '--input', req],
                       capture_output=True, text=True)
    try:
        print(json.loads(r.stdout).get('text') or r.stdout)
    except Exception:
        print(r.stdout or r.stderr)
    for f in os.listdir(tmp):
        os.remove(os.path.join(tmp, f))
    os.rmdir(tmp)
    return 0


if __name__ == '__main__':
    sys.exit(main())
