#!/usr/bin/env python3
"""probe.py — точечные измерения визуальных свойств (bevel, заливки, текст)."""
import sys, os
from PIL import Image
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from uicopy_ctx import ctx
C = ctx()
im, W, H, px = C.orig, C.W, C.H, C.px


def hx(c):
    return "#%02x%02x%02x" % c


def edges(box, label):
    """Профили краёв прямоугольника: доказывают наличие/тип bevel."""
    x0, y0, x1, y1 = box
    cy = (y0 + y1) // 2
    cx = (x0 + x1) // 2
    print(f"\n--- {label} [{x0},{y0},{x1},{y1}] {x1-x0}x{y1-y0} ---")
    print("  L->R (y=cy):", [hx(px[x, cy]) for x in range(x0 - 1, min(x0 + 5, x1))])
    print("  R->L (y=cy):", [hx(px[x, cy]) for x in range(max(x1 - 5, x0), x1 + 1)])
    print("  T->B (x=cx):", [hx(px[cx, y]) for y in range(y0 - 1, min(y0 + 5, y1))])
    print("  B->T (x=cx):", [hx(px[cx, y]) for y in range(max(y1 - 5, y0), y1 + 1)])
    cnt = Counter()
    for y in range(y0 + 3, y1 - 3):
        for x in range(x0 + 3, x1 - 3):
            cnt[px[x, y]] += 1
    if cnt:
        print("  внутр. доминант:", [(hx(c), n) for c, n in cnt.most_common(3)])


def text_ink(box, label, bgs):
    """Цвет текста = самый «яркий» отличный от фона."""
    x0, y0, x1, y1 = box
    cnt = Counter()
    for y in range(y0, y1):
        for x in range(x0, x1):
            c = px[x, y]
            if all(abs(c[i] - b[i]) < 10 for b in bgs for i in range(3)):
                continue
            cnt[c] += 1
    tops = cnt.most_common(6)
    lum = lambda c: 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]
    ink = max((c for c, n in tops), key=lum) if tops else None
    print(f"  {label:26s} ink={hx(ink) if ink else '—':9s} "
          f"top={[hx(c) for c, n in tops[:3]]}")


def glyph_height(box, label, bg, tol=40):
    """Высота ink-строки (cap+descender) — для кегля."""
    x0, y0, x1, y1 = box
    rows = []
    for y in range(y0, y1):
        n = sum(1 for x in range(x0, x1)
                if abs(px[x, y][0] - bg[0]) + abs(px[x, y][1] - bg[1])
                + abs(px[x, y][2] - bg[2]) > tol)
        rows.append((y, n))
    ink = [y for y, n in rows if n > 0]
    if ink:
        print(f"  {label:26s} ink-rows {ink[0]}..{ink[-1]} height={ink[-1]-ink[0]+1}")


if __name__ == "__main__":
    BG = (76, 88, 68)
    print("=== ФОН / РАМКИ ===")
    print("  dialog bg:", hx(BG))
    print("  outer bg :", hx(px[2, 2]))
    print("  внешняя рамка сверху x=500:", [hx(px[500, y]) for y in range(0, 6)])
    print("  внешняя рамка слева y=300:", [hx(px[x, 300]) for x in range(0, 6)])
    print("  правый край y=300:", [hx(px[x, 300]) for x in range(W - 6, W)])
    print("  низ x=500:", [hx(px[500, y]) for y in range(H - 6, H)])

    # титлбар: где заканчивается
    print("\n=== ТИТУЛЬНАЯ ПОЛОСА ===")
    for y in (10, 20, 38, 42, 44, 46, 48):
        print(f"  y={y}: x=300 {hx(px[300, y])}  x=700 {hx(px[700, y])}")

    # tab-полоса
    print("\n=== TAB СТРОКА ===")
    for y in (46, 50, 52, 56, 66, 78, 80, 82, 84, 86):
        print(f"  y={y}: x=30 {hx(px[30, y])}  x=250 {hx(px[250, y])}  "
              f"x=1050 {hx(px[1050, y])}")

    edges([14, 48, 190, 84], "TAB active (Мультиплеер)")
    edges([196, 48, 340, 84], "TAB inactive (Клавиатура)")
    edges([220, 154, 454, 189], "BUTTON Загрузить...")
    edges([537, 155, 914, 189], "ENTRY Имя игрока")
    edges([220, 211, 454, 246], "COMBOBOX cts_team")
    edges([537, 285, 914, 320], "ENTRY Пароль (disabled?)")
    edges([220, 341, 454, 376], "BUTTON Изменить цвет")
    edges([85, 447, 312, 482], "BUTTON Дополнительно...")
    edges([638, 607, 775, 642], "BUTTON OK")
    edges([938, 607, 1075, 642], "BUTTON Применить (disabled)")

    print("\n=== ЦВЕТА ТЕКСТА ===")
    text_ink([53, 19, 168, 38], "заголовок 'Настройки'", [BG])
    text_ink([21, 57, 167, 77], "tab active", [BG])
    text_ink([199, 59, 327, 77], "tab inactive", [BG])
    text_ink([85, 132, 137, 146], "label 'Аватар'", [BG])
    text_ink([231, 164, 340, 182], "btn 'Загрузить...'", [BG])
    text_ink([546, 160, 900, 185], "entry text '[B]KoHTpE'", [(62, 70, 55)])
    text_ink([231, 219, 340, 240], "combo 'cts_team'", [(62, 70, 55)])
    text_ink([85, 396, 505, 413], "disabled 'Логотип изменится'", [BG])
    text_ink([649, 617, 679, 631], "btn OK", [BG])
    text_ink([950, 617, 1071, 636], "btn Применить disabled", [BG])

    print("\n=== ВЫСОТА ГЛИФОВ (кегль) ===")
    glyph_height([53, 14, 168, 40], "заголовок", BG)
    glyph_height([21, 55, 167, 80], "tab active", BG)
    glyph_height([199, 55, 327, 80], "tab inactive", BG)
    glyph_height([85, 128, 137, 150], "label Аватар", BG)
    glyph_height([231, 160, 340, 186], "btn Загрузить", BG)
    glyph_height([546, 158, 760, 188], "entry text", (62, 70, 55))
    glyph_height([85, 393, 505, 415], "disabled text", BG)
    glyph_height([649, 613, 679, 635], "btn OK", BG)
