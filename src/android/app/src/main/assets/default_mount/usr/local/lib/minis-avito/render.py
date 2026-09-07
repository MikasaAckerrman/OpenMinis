#!/usr/bin/env python3
"""render.py — рендер результатов скана в красивые карточки для чата.

Использование:
    python3 render.py <scan.json> [--top N] [--out file]

Карточки содержат:
  - Заголовок лота
  - Ключевые числа: цена, рейтинг, отзывы, риск
  - Флаги риска как предупреждения
  - Ссылка на оригинал
  - Сгенерированные вопросы продавцу (если контекст передан)
"""
import argparse
import json
import sys
from pathlib import Path


def fmt_int(n):
    if n is None: return '—'
    return f'{n:,}'.replace(',', ' ')


def fmt_rating(r, rev):
    if r is None: return '—'
    s = f'{r}'
    if rev: s += f' ({rev})'
    return s


def risk_icon(score):
    if score <= -5: return '🟢'
    if score <= 5: return '🟡'
    if score <= 20: return '🟠'
    return '🔴'


def render_item(it, n=0):
    """Рендер одной карточки."""
    score = it.get('risk_score', 0)
    icon = risk_icon(score)
    flags = it.get('flags', [])
    flags_str = ''
    if flags:
        flag_icons = {
            'mining': '⛏️ майнинг',
            'no_rating': '👤 нет отзывов',
            'low_rating': '⭐ низкий рейтинг',
            'high_rating': '⭐ высокий рейтинг',
            'too_cheap': '💸 подозрительно дёшево',
            'cheap': '💵 дешевле рынка',
            'above_market': '📈 выше рынка',
            'near': '🚚 близко',
            'far': '🛬 далеко',
        }
        flags_str = ' '.join(flag_icons.get(f, f'❓ {f}') for f in flags)

    raw = it.get('raw', '')[:160]

    # Риск-бейдж: цветной + текст
    risk_txt = 'низкий' if score < 0 else ('средний' if score < 15 else 'высокий')
    out = f"""
### {icon} {fmt_int(it.get('price'))} ₽ · {fmt_rating(it.get('rating'), it.get('reviews'))}

{raw}

{flags_str}

🛡️ риск: {risk_txt}

🔗 [Открыть объявление]({it.get('url', '')})
"""
    return out.strip()


def render_scan(scan, top=5):
    """Рендер полного скана: статистика + топ."""
    items = scan.get('items', [])
    stats = scan.get('stats', {})
    s = f"""
## Скан: {scan.get('mission', '?')}

📊 **{stats.get('n', 0)} лотов после фильтрации** · диапазон {fmt_int(stats.get('min'))}–{fmt_int(stats.get('max'))} ₽ · медиана **{fmt_int(stats.get('median'))} ₽**

Найдено в выдаче: {scan.get('found_raw', '?')} · отброшено: {scan.get('dropped', '?')}

---

"""
    for i, it in enumerate(items[:top]):
        s += render_item(it, i) + '\n---\n'
    return s.strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('scan', help='JSON от scan.py')
    ap.add_argument('--top', type=int, default=5)
    ap.add_argument('--out', help='файл для записи (по умолчанию stdout)')
    args = ap.parse_args()

    scan = json.load(open(args.scan, encoding='utf-8'))
    text = render_scan(scan, args.top)
    if args.out:
        Path(args.out).write_text(text, encoding='utf-8')
    else:
        print(text)


if __name__ == '__main__':
    main()
