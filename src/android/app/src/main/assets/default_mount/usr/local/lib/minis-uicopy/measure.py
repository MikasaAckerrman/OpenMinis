#!/usr/bin/env python3
"""measure.py — измерение ORIGINAL.png. Только факты из пикселей, 0 токенов."""
import sys, os, json
from PIL import Image
import collections

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from uicopy_ctx import ctx
C = ctx()
im, W, H, px = C.orig, C.W, C.H, C.px


def colors_hist(box=None, top=14):
    c = collections.Counter()
    x0, y0, x1, y1 = box or (0, 0, W, H)
    for y in range(y0, y1):
        for x in range(x0, x1):
            c[px[x, y]] += 1
    tot = (x1 - x0) * (y1 - y0)
    return [(rgb, n, round(100.0 * n / tot, 2)) for rgb, n in c.most_common(top)]


def row_profile(y, x0=0, x1=None):
    x1 = x1 or W
    return [px[x, y] for x in range(x0, x1)]


def find_runs(y, x0=0, x1=None, ref=None, tol=6):
    """Горизонтальные участки, отличающиеся от ref."""
    x1 = x1 or W
    ref = ref or px[x0, y]
    runs, start = [], None
    for x in range(x0, x1):
        r, g, b = px[x, y]
        d = abs(r - ref[0]) + abs(g - ref[1]) + abs(b - ref[2])
        if d > tol:
            if start is None:
                start = x
        else:
            if start is not None:
                runs.append((start, x - 1, x - start))
                start = None
    if start is not None:
        runs.append((start, x1 - 1, x1 - start))
    return runs


def col_runs(x, y0=0, y1=None, ref=None, tol=6):
    y1 = y1 or H
    ref = ref or px[x, y0]
    runs, start = [], None
    for y in range(y0, y1):
        r, g, b = px[x, y]
        d = abs(r - ref[0]) + abs(g - ref[1]) + abs(b - ref[2])
        if d > tol:
            if start is None:
                start = y
        else:
            if start is not None:
                runs.append((start, y - 1, y - start))
                start = None
    if start is not None:
        runs.append((start, y1 - 1, y1 - start))
    return runs


if __name__ == "__main__":
    what = sys.argv[1] if len(sys.argv) > 1 else "all"

    if what in ("all", "size"):
        print(f"SIZE {W}x{H}")

    if what in ("all", "hist"):
        print("\n=== ГЛОБАЛЬНАЯ ПАЛИТРА (top-14) ===")
        for rgb, n, pc in colors_hist():
            print(f"  {rgb} #{rgb[0]:02x}{rgb[1]:02x}{rgb[2]:02x}  {n:8d}  {pc:5.2f}%")

    if what in ("all", "frame"):
        print("\n=== ГРАНИЦЫ ДИАЛОГА ===")
        # внешний фон = цвет в (2,2); ищем где начинается диалог
        outer = px[2, 2]
        print(f"  внешний фон: {outer}")
        # сканируем середину по вертикали и горизонтали
        mid_y = H // 2
        runs = find_runs(mid_y, 0, W, outer, tol=10)
        print(f"  y={mid_y}: runs≠внешний: {runs[:6]}")
        mid_x = W // 2
        cruns = col_runs(mid_x, 0, H, outer, tol=10)
        print(f"  x={mid_x}: runs≠внешний: {cruns[:6]}")

    if what in ("all", "rows"):
        print("\n=== СТРОЧНЫЙ ПРОФИЛЬ (уникальные цвета по строкам, шаг 1) ===")
        prev = None
        for y in range(0, H):
            row = row_profile(y)
            u = len(set(row))
            dom = collections.Counter(row).most_common(1)[0]
            sig = (u > 20)
            if sig != prev:
                print(f"  y={y:4d} uniq={u:4d} dom={dom[0]} {'<-- контент' if sig else '<-- ровно'}")
                prev = sig
