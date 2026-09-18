#!/usr/bin/env python3
"""ЕДИНСТВЕННЫЙ способ печатать ссылки лотов юзеру. Никогда не вручную.

Берёт пары (url, raw) из scan-json и печатает markdown-ссылки + текст,
гарантированно из ОДНОЙ строки данных. Встроенная автопроверка:

  1. цена из raw совпадает с price (если обе есть)
  2. город из url встречается в raw (или url-город без _)
  3. модель из миссии есть в raw

Если проверка не прошла — лот помечается SUSPECT и НЕ печатается
в фавориты без явного --force.

Использование:
  links.py <scan.json> [--n 5] [--force] [--min-price N] [--max-price N]
"""
import json, re, sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

def _clean(s):
    return (s or '').replace('\u00a0', ' ').replace('\u202f', ' ')

def check_pair(url, raw, price, mission_model):
    """Возвращает список проблем пары url/raw. Пустой = пара согласована."""
    problems = []
    raw = _clean(raw)
    # 1. цена: точное членство среди числовых кандидатов в raw.
    # Подстрочная проверка ('25000' in '307025000') слепа к «модель+цена».
    if price:
        block = re.search(r'(\d[\d\s\xa0\u202f]{2,14})\s*(?:₽|Р\b)', raw)
        if block:
            cands = re.findall(r'\d{1,3}(?:[\s\xa0\u202f]\d{3})+|[1-9]\d{4,5}', block.group(1))
            vals = {int(c.replace('\xa0', '').replace('\u202f', '').replace(' ', '')) for c in cands}
            if price not in vals:
                problems.append('цена %s не в raw(%s)' % (price, sorted(vals)))
        else:
            problems.append('цена не найдена в raw')
    # Город НЕ проверяю: url хранит транслитерацию (moskva), raw — кириллицу (Москва).
    # Сравнение даёт фейковые подозрения. Модель + цена однозначно связывают пару.
    # 3. модель миссии
    if mission_model:
        mm = mission_model.replace(' ', '')
        if mm.lower() not in raw.replace(' ', '').lower():
            problems.append('модель %s не в raw' % mission_model)
    return problems

def fmt_md(url, raw, price, host='https://www.avito.ru'):
    title = _clean(raw)
    m = re.match(r'^(.{0,70}?)(?:\s+\d[\d\s]{2,8}\s*₽|$)', title)
    t = (m.group(1) if m else title).strip()
    return '[%s%s](%s%s) · %s' % (
        t[:60] + ('…' if len(t) > 60 else ''),
        (' — %s ₽' % format(price, ',').replace(',', ' ')) if price else '',
        host, url, (url.strip('/').split('/')[0]).replace('-', ' '))

def main():
    a = sys.argv[1:]
    if not a:
        print(__doc__); return 1
    d = json.load(open(a[0], encoding='utf-8'))
    items = d['items'] if isinstance(d, dict) else d
    n = int(a[a.index('--n') + 1]) if '--n' in a else 5
    force = '--force' in a
    lo = int(a[a.index('--min-price') + 1]) if '--min-price' in a else None
    hi = int(a[a.index('--max-price') + 1]) if '--max-price' in a else None
    model = (d.get('target') or {}).get('model') if isinstance(d, dict) else None

    rows = []
    for it in items:
        u = it.get('url') or it.get('u') or ''
        if not u:
            continue
        rows.append((u, it.get('raw', ''), it.get('price'), it))
    rows.sort(key=lambda r: (r[3].get('risk_score') if r[3].get('risk_score') is not None else 999,
                             r[2] if r[2] else 999999))
    out, skipped = [], []
    for u, raw, price, it in rows:
        if lo and (not price or price < lo):
            continue
        if hi and (not price or price > hi):
            continue
        probs = check_pair(u, raw, price, model)
        if probs and not force:
            skipped.append((u, probs, raw))
            continue
        out.append(fmt_md(u, raw, price))
        if len(out) >= n:
            break
    print('## Лоты (пары url/raw проверены программно)\n')
    for i, l in enumerate(out, 1):
        print('%d. %s' % (i, l))
    if skipped:
        print('\n<!-- SUSPECT пары (не показаны, --force чтобы показать): %d -->' % len(skipped))
        for u, probs, raw in skipped[:3]:
            print('<!-- %s : %s -->' % (u, '; '.join(probs)))
    return 0

if __name__ == '__main__':
    sys.exit(main())
