#!/usr/bin/env python3
"""selftest-scan.py — тесты на фикстуре реалистичных лотов.

Покрывает:
  - парсинг полей (цена, рейтинг, отзывы, доставка)
  - desktop-only фильтр (отсев Ti/M/laptop/майнинг)
  - delivery-required фильтр
  - скоринг (title patterns, rating thresholds, price vs median)
  - ранжирование и итоговая статистика
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

# Подгружаем сканер
from scan import (
    load_mission, run_on_items, parse_fields, apply_filter_hard_exclude,
    apply_filter_delivery, score_items, summarize, rank, build_url, FILTERS, load_filter,
)


FIXTURE = [
    # десктопные 3070 с доставкой и хорошим рейтингом
    {"raw": "Gigabyte RTX 3070 Eagle 8Gb 18 000 ₽ Артем Королев 4,3 (3) Доставка от 1 дня", "url": "u1", "price": None, "rating": None, "reviews": None, "metro": ""},
    {"raw": "Palit rtx 3070 GameRock 8gb 19 000 ₽ Дамир 4,5 (16) Доставка от 1 дня", "url": "u2", "price": None, "rating": None, "reviews": None, "metro": ""},
    {"raw": "Видеокарта Zotac RTX 3070 19 500 ₽ Иван 5,0 (2) Доставка от 1 дня", "url": "u3", "price": None, "rating": None, "reviews": None, "metro": ""},
    {"raw": "Rtx 3070 18 900 ₽ Тимур Фаизов 2,3 (3) Доставка от 1 дня", "url": "u4", "price": None, "rating": None, "reviews": None, "metro": ""},
    {"raw": "Msi geforce rtx 3070 gaming x trio 22 500 ₽ Продавец 5,0 (45) Москва", "url": "u5", "price": None, "rating": None, "reviews": None, "metro": ""},
    # мусор — должны быть отброшены
    {"raw": "Peladn RTX 3070M 8gb Laptop 18 000 ₽ Мультифрукт 5,0 (106) Доставка от 1 дня", "url": "u6", "price": None, "rating": None, "reviews": None, "metro": ""},
    {"raw": "RTX 3070 ti 8gb gamerock (возврат) 22 500 ₽ Иван 5,0 (8) Доставка от 1 дня", "url": "u7", "price": None, "rating": None, "reviews": None, "metro": ""},
    {"raw": "Видеокарта rtx 3070 19 500 ₽ Майнинг-ферма 4,5 (5) Доставка от 1 дня", "url": "u8", "price": None, "rating": None, "reviews": None, "metro": ""},
    {"raw": "Видеокарта rtx 3070 17 000 ₽ На запчасти не работает без видеовыход 4,0 (2)", "url": "u9", "price": None, "rating": None, "reviews": None, "metro": ""},
]


ok, fail = [], []


def check(name, cond, detail=""):
    (ok if cond else fail).append((name, detail))


# 1. parse_fields достаёт данные
def t_parse():
    parsed = [parse_fields(x) for x in FIXTURE]
    giga = next(p for p in parsed if 'Gigabyte' in p['raw'])
    check('цена Gigabyte = 18000', giga['price'] == 18000, giga.get('price'))
    check('рейтинг = 4.3', giga['rating'] == 4.3, giga.get('rating'))
    check('отзывы = 3', giga['reviews'] == 3, giga.get('reviews'))
    tri = next(p for p in parsed if 'gaming x trio' in p['raw'])
    check('Msi trio нет metro', not tri['has_metro'])
    msk = next(p for p in parsed if 'Дамир' in p['raw'])
    check('Дамир 4.5', msk['rating'] == 4.5, msk.get('rating'))
    check('Дамир 16 отзывов', msk['reviews'] == 16)


# 2. desktop-only отбрасывает Ti/M/laptop/майнинг/запчасти
def t_desktop():
    parsed = [parse_fields(x) for x in FIXTURE]
    fdef = load_filter('desktop-only')
    kept, dropped = apply_filter_hard_exclude(parsed, fdef)
    dropped_urls = [d['url'] for d in dropped]
    check('drop Peladn 3070M', 'u6' in dropped_urls, dropped_urls)
    check('drop RTX 3070 ti', 'u7' in dropped_urls, dropped_urls)
    check('drop Майнинг-ферма', 'u8' in dropped_urls, dropped_urls)
    check('drop на запчасти', 'u9' in dropped_urls, dropped_urls)
    check('kept = 5', len(kept) == 5, len(kept))
    check('kept содержит gigabyte', any('u1' == k['url'] for k in kept))


# 3. delivery-required отбрасывает без доставки
def t_delivery():
    parsed = [parse_fields(x) for x in FIXTURE]
    fdef = load_filter('delivery-required')
    kept, dropped = apply_filter_delivery(parsed, fdef)
    drop9 = [d for d in dropped if d['url'] == 'u9']
    check('drop u9 (нет доставки)', len(drop9) == 1, str(dropped))


# 4. score
def t_score():
    parsed = [parse_fields(x) for x in FIXTURE]
    fdef1 = load_filter('desktop-only')
    after_desktop, _ = apply_filter_hard_exclude(parsed, fdef1)
    fdef2 = load_filter('delivery-required')
    after_del, _ = apply_filter_delivery(after_desktop, fdef2)
    mission = load_mission('rtx3070-russia')
    items, median = score_items(after_del, mission)
    # Должен быть порядок: лучшие score сверху, плохие снизу
    scores = [it.get('risk_score', 0) for it in items]
    check('медиана вычислена', median > 0, median)
    check('все items получили score', 'risk_score' in items[0])
    # Тимур с плохим рейтингом должен быть ниже
    timur = next(it for it in items if 'Тимур' in it['raw'])
    check('Тимур имеет low_rating', 'low_rating' in timur.get('flags', []), timur.get('flags'))
    giga = next(it for it in items if 'Gigabyte' in it['raw'])
    # 4.3 < 4.5 (порог lt_4_5) => low_rating. До фикса _parse_threshold
    # порог читался как 4.0 и 4.3 ошибочно получал high_rating.
    check('Gigabyte 4.3 имеет low_rating (порог 4.5 работает)', 'low_rating' in giga.get('flags', []), giga.get('flags'))
    # Что-то должно быть среди items; неважно что именно.
    check('items не пустой', len(items) > 0, len(items))


# 5. URL builder
def t_url():
    url = build_url(load_mission('rtx3070-russia'))
    check('URL имеет rossiya', 'rossiya' in url, url)
    check('URL имеет q', 'q=rtx+3070' in url, url)
    check('URL имеет pmin', 'pmin=18000' in url, url)


# 6. Итог ранжирования
def t_ranking():
    parsed = [parse_fields(x) for x in FIXTURE]
    fdef1 = load_filter('desktop-only')
    after_desktop, _ = apply_filter_hard_exclude(parsed, fdef1)
    fdef2 = load_filter('delivery-required')
    after_del, _ = apply_filter_delivery(after_desktop, fdef2)
    mission = load_mission('rtx3070-russia')
    items, median = score_items(after_del, mission)
    ranked = rank(items, 'total_score')
    # Тимур должен быть в самом низу
    last = ranked[-1]
    check('Тимур в самом низу', 'Тимур' in last['raw'], last['raw'][:50])


for t in [t_parse, t_desktop, t_delivery, t_score, t_url, t_ranking]:
    t()
    print(f"[{t.__name__[2:]}]")


print(f"\n{len(ok)} прошло, {len(fail)} упало")
for n, d in fail:
    print(f"  FAIL {n}: {d}")
sys.exit(1 if fail else 0)
