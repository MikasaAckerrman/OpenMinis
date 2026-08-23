#!/usr/bin/env python3
"""boxes.py — детекция элементов: связные области, отличные от фона диалога.
Только измерение из пикселей. 0 токенов."""
import sys, json
from PIL import Image

SRC = sys.argv[1] if len(sys.argv) > 1 else "ORIGINAL.png"
BG = (76, 88, 68)
TOL = 14
MIN_AREA = 40

im = Image.open(SRC).convert("RGB")
W, H = im.size
px = im.load()


def d(c, r=BG):
    return abs(c[0] - r[0]) + abs(c[1] - r[1]) + abs(c[2] - r[2])


# маска «не фон»
mask = bytearray(W * H)
for y in range(H):
    for x in range(W):
        if d(px[x, y]) > TOL:
            mask[y * W + x] = 1

# связные компоненты (4-связность, итеративный flood fill)
seen = bytearray(W * H)
comps = []
for sy in range(H):
    for sx in range(W):
        i0 = sy * W + sx
        if not mask[i0] or seen[i0]:
            continue
        stack = [(sx, sy)]
        seen[i0] = 1
        x0 = x1 = sx
        y0 = y1 = sy
        n = 0
        while stack:
            x, y = stack.pop()
            n += 1
            if x < x0: x0 = x
            if x > x1: x1 = x
            if y < y0: y0 = y
            if y > y1: y1 = y
            for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
                if 0 <= nx < W and 0 <= ny < H:
                    j = ny * W + nx
                    if mask[j] and not seen[j]:
                        seen[j] = 1
                        stack.append((nx, ny))
        if n >= MIN_AREA:
            comps.append({"box": [x0, y0, x1 + 1, y1 + 1], "pixels": n})

# сортировка: сверху вниз, слева направо
comps.sort(key=lambda c: (c["box"][1], c["box"][0]))
print(f"# {SRC} {W}x{H}  компонент: {len(comps)}")
for c in comps:
    x0, y0, x1, y1 = c["box"]
    w, h = x1 - x0, y1 - y0
    fill = c["pixels"] / float(w * h)
    # доминирующий цвет внутри
    from collections import Counter
    cnt = Counter()
    for y in range(y0, y1):
        for x in range(x0, x1):
            cnt[px[x, y]] += 1
    dom = cnt.most_common(2)
    print(f"  [{x0:4d},{y0:4d},{x1:4d},{y1:4d}] {w:4d}x{h:3d} fill={fill:.2f} "
          f"dom={dom[0][0]}"
          + (f" 2nd={dom[1][0]}" if len(dom) > 1 else ""))
