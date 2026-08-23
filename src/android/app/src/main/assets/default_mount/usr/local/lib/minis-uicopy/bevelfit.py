#!/usr/bin/env python3
"""bevelfit.py — ПОЭЛЕМЕНТНЫЕ профили bevel из оригинала.

ДИАГНОЗ (измерено на btn 'Дополнительно'):
  левый край  O: #5a6552 #7a8472 #76806e #56624e
              R: #56624e #76806e #7a8472 #5a6552   ← ЗЕРКАЛЬНО
  правый край O: #46513e #333b2c #30382a #444e3c
              R: #414b3a #2e3528 #353d2e #485440   ← и фаза, и цвета
Причина: в v4 я снял профиль с ОДНОЙ кнопки (OK) и применил его ко ВСЕМ.
У разных кнопок профиль отличается и по фазе, и по оттенкам.

РЕШЕНИЕ: снять 4 профиля с КАЖДОГО элемента отдельно, на линии без текста,
и выдать per-element CSS. Никакого переноса «по аналогии».
"""
import sys, os
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from uicopy_ctx import ctx
C = ctx()
a, px = C.orig, C.px
BG = (76, 88, 68)


def hx(c):
    return "#%02x%02x%02x" % c


def grad(colors, direction):
    parts = []
    for i, c in enumerate(colors):
        parts.append(f"{hx(c)} {i}px,{hx(c)} {i+1}px")
    return f"linear-gradient(to {direction}," + ",".join(parts) + ")"


# (класс, box, sample_y для гориз. профилей, sample_x для верт. профилей, ширина bevel)
ELEMENTS = [
    # sample_y выбран в СЕРЕДИНЕ элемента: на последней строке профиль уже
    # смешан с нижней кромкой (измерено на b_more: y=479 даёт #55604d вместо
    # #5a6552 — это уже угол, а не боковина). Ошибка v9 была именно в этом.
    ("b_load",   (220, 154, 454, 189), 170, 440, 4),
    ("b_color",  (220, 341, 454, 376), 357, 440, 4),
    ("b_more",   (85, 447, 312, 482), 464, 300, 4),
    ("b_ok",     (638, 607, 775, 642), 624, 760, 4),
    ("b_cancel", (788, 607, 925, 642), 624, 910, 4),
    ("b_apply",  (938, 607, 1075, 642), 624, 1060, 4),
    ("e_cts",    (220, 211, 454, 246), 228, 440, 4),
    ("e_lam",    (220, 285, 454, 320), 302, 440, 4),
    ("e_name",   (537, 155, 914, 189), 172, 900, 4),
    ("e_pass",   (537, 285, 914, 320), 302, 860, 4),
]

if __name__ == "__main__":
    print("/* === ПОЭЛЕМЕНТНЫЕ bevel-профили (bevelfit.py, всё измерено) === */")
    for name, (x0, y0, x1, y1), sy, sx, n in ELEMENTS:
        # левый: x0..x0+n на строке sy (без текста)
        left = [px[x, sy] for x in range(x0, x0 + n)]
        # правый: x1-n..x1
        right = [px[x, sy] for x in range(x1 - n, x1)]
        # верх: y0..y0+n-1 в столбце sx
        top = [px[sx, y] for y in range(y0, y0 + n - 1)]
        # низ: y1-n+1..y1
        bot = [px[sx, y] for y in range(y1 - n + 1, y1)]
        print(f"\n/* {name} [{x0},{y0},{x1},{y1}] sample y={sy} x={sx} */")
        print(f".{name}>i.bl{{background:{grad(left,'right')}}}")
        print(f".{name}>i.br{{background:{grad(right,'right')}}}")
        print(f".{name}>i.bt{{background:{grad(top,'bottom')}}}")
        print(f".{name}>i.bb{{background:{grad(bot,'bottom')}}}")
        print(f"/*  L:{[hx(c) for c in left]}")
        print(f"    R:{[hx(c) for c in right]}")
        print(f"    T:{[hx(c) for c in top]}")
        print(f"    B:{[hx(c) for c in bot]} */")
