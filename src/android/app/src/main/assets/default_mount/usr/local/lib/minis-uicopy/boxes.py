#!/usr/bin/env python3
"""boxes.py — детекция элементов: связные области, отличные от фона.

Только измерение из пикселей, 0 токенов.

ИСПРАВЛЕНО (было причиной молча неверных чисел): цвет фона больше не
захардкожен как оливковый (76,88,68) из CS 1.6 — он измеряется как
доминирующий цвет кадра. На любом другом скриншоте прежняя константа давала
маску «не фон» на весь кадр, и детекция вырождалась в один компонент
размером с экран.

Пишет boxes.json рядом с оригиналом — его читает diff.py, чтобы поэлементная
атрибуция работала на ЛЮБОМ скриншоте, а не только на том, под который
однажды был вписан список боксов.
"""
import json
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import metrics as M  # noqa: E402

SRC = sys.argv[1] if len(sys.argv) > 1 else "ORIGINAL.png"
TOL = 14          # L1 по sRGB: порог «отличается от фона»
MIN_AREA = 40     # мелочь ниже этого — шум сжатия, не элемент

im = Image.open(SRC).convert("RGB")
W, H = im.size
arr = np.asarray(im)

BG, bg_frac = M.dominant_color(arr)
print("# %s %dx%d" % (SRC, W, H))
print("# фон (измерен, не захардкожен): rgb%s, доля кадра %.1f%%"
      % (BG, 100.0 * bg_frac))
if bg_frac < 0.25:
    # Не отказ, а честная пометка: детекция «всё, что не фон» осмысленна
    # только когда фон действительно доминирует. На фотографии или градиенте
    # доминирующий цвет занимает считанные проценты, и результат ниже надо
    # читать как «зоны, отличные от самого частого цвета», а не «элементы».
    print("# ВНИМАНИЕ: фон занимает меньше 25% кадра — компоненты ниже")
    print("#           могут не соответствовать элементам UI.")

bgv = np.array(BG, dtype=np.int32)
mask = (np.abs(arr.astype(np.int32) - bgv).sum(axis=2) > TOL)

# связные компоненты, 4-связность, итеративный flood fill
seen = np.zeros((H, W), dtype=bool)
comps = []
for sy in range(H):
    for sx in range(W):
        if not mask[sy, sx] or seen[sy, sx]:
            continue
        stack = [(sx, sy)]
        seen[sy, sx] = True
        x0 = x1 = sx
        y0 = y1 = sy
        n = 0
        while stack:
            x, y = stack.pop()
            n += 1
            if x < x0:
                x0 = x
            if x > x1:
                x1 = x
            if y < y0:
                y0 = y
            if y > y1:
                y1 = y
            for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
                if 0 <= nx < W and 0 <= ny < H and mask[ny, nx] and not seen[ny, nx]:
                    seen[ny, nx] = True
                    stack.append((nx, ny))
        if n >= MIN_AREA:
            comps.append({"box": [x0, y0, x1 + 1, y1 + 1], "pixels": n})

comps.sort(key=lambda c: (c["box"][1], c["box"][0]))
print("# компонент: %d" % len(comps))

out = []
for i, c in enumerate(comps, 1):
    x0, y0, x1, y1 = c["box"]
    w, h = x1 - x0, y1 - y0
    fill = c["pixels"] / float(w * h)
    crop = arr[y0:y1, x0:x1]
    dom, dom_frac = M.dominant_color(crop)
    name = "c%02d_%dx%d@%d,%d" % (i, w, h, x0, y0)
    out.append({"name": name, "box": [x0, y0, x1, y1], "pixels": c["pixels"],
                "fill": round(fill, 3), "dominant": list(dom),
                "dominant_frac": round(dom_frac, 3)})
    print("  [%4d,%4d,%4d,%4d] %4dx%-3d fill=%.2f dom=%s (%.0f%%)  %s"
          % (x0, y0, x1, y1, w, h, fill, dom, 100.0 * dom_frac, name))

dst = os.path.join(os.path.dirname(os.path.abspath(SRC)), "boxes.json")
with open(dst, "w") as f:
    json.dump({"source": os.path.basename(SRC), "size": [W, H],
               "background": list(BG), "background_frac": round(bg_frac, 4),
               "elements": out}, f, ensure_ascii=False, indent=1)
print("# записано: %s (%d элементов) — diff подхватит автоматически"
      % (dst, len(out)))
