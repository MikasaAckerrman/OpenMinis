#!/usr/bin/env python3
"""textfit.py — подбор шрифта по РАСТРУ, а не по одной ширине.

ЗАЧЕМ. v4 показал: ширина совпала (DejaVu Bold@19, |dw|=33px), но глиф РАЗНЫЙ.
Построчное сравнение 'Аватар' доказало: в оригинале штрих 1px + antialias, в
рендере 2-3px — оригинал НЕ bold. Ширину bold добрал за счёт толщины, а не
трекинга. Значит критерий «ширина ink» недостаточен: нужен растр.

КАК. Генерируем ОДНУ страницу со всеми кандидатами (family × size × weight ×
letter-spacing), рендерим её ОДНИМ вызовом chromium (тот же растеризатор, что
у финального рендера — PIL здесь врёт), затем для каждого кандидата измеряем:
  ink_w, ink_h          — габариты
  ink_ratio             — доля «сильных» пикселей (толщина штриха)
  MAE к кропу оригинала — прямое сравнение растров после выравнивания по ink

Победитель — минимум MAE. Это измерение, а не мнение.
"""
import sys, os, json, subprocess
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from uicopy_ctx import ctx
HERE = ctx().dir
TOOLS = os.path.dirname(os.path.abspath(__file__))
ORIG = os.path.join(HERE, "ORIGINAL.png")
BG = (76, 88, 68)
INK = "#b7bfb1"          # измеренный цвет метки 'Аватар'
SAMPLE = "Аватар"
# измеренный ink-bbox 'Аватар' в оригинале
OX0, OY0, OX1, OY1 = 85, 132, 164, 150

FAMILIES = [
    ("DejaVu Sans", "normal"),
    ("DejaVu Sans", "bold"),
    ("DejaVu Sans Condensed", "normal"),
    ("DejaVu Sans Condensed", "bold"),
    ("Open Sans", "normal"),
    ("Open Sans", "600"),
    ("Open Sans SemiBold", "normal"),
    ("Roboto", "normal"),
    ("Roboto Condensed", "normal"),
    ("Roboto Medium", "normal"),
]
SIZES = [15, 16, 17, 18, 19, 20, 21]
SPACINGS = [0, 0.5, 1, 1.5, 2]

CELL_W, CELL_H = 300, 40


def build_page(path):
    cands = []
    rows = []
    for fam, w in FAMILIES:
        for size in SIZES:
            for sp in SPACINGS:
                i = len(cands)
                cands.append({"i": i, "family": fam, "weight": w,
                              "size": size, "spacing": sp})
                x = (i % 4) * CELL_W
                y = (i // 4) * CELL_H
                rows.append(
                    f'<div style="position:absolute;left:{x+10}px;top:{y+10}px;'
                    f'font-family:\'{fam}\';font-weight:{w};font-size:{size}px;'
                    f'letter-spacing:{sp}px;color:{INK};line-height:19px;'
                    f'white-space:nowrap">{SAMPLE}</div>')
    h = ((len(cands) + 3) // 4) * CELL_H + 20
    html = ("<!DOCTYPE html><html><head><meta charset='utf-8'><style>"
            "*{margin:0;padding:0}html,body{background:#4c5844}"
            "body{-webkit-font-smoothing:antialiased;"
            "text-rendering:geometricPrecision}</style></head><body>"
            + "".join(rows) + "</body></html>")
    with open(path, "w") as f:
        f.write(html)
    return cands, 4 * CELL_W, h


def ink_bbox(px, x0, y0, x1, y1, tol=30):
    xs, ys = [], []
    for y in range(y0, y1):
        for x in range(x0, x1):
            c = px[x, y]
            if abs(c[0] - BG[0]) + abs(c[1] - BG[1]) + abs(c[2] - BG[2]) > tol:
                xs.append(x); ys.append(y)
    if not xs:
        return None
    return min(xs), min(ys), max(xs) + 1, max(ys) + 1


def main():
    page = os.path.join(HERE, "_tf.html")
    shot = os.path.join(HERE, "_tf.png")
    cands, W, H = build_page(page)
    subprocess.run(["sh", os.path.join(TOOLS, "render.sh"),
                    page, shot, str(W), str(H + 120)],
                   capture_output=True)
    im = Image.open(shot).convert("RGB")
    px = im.load()

    orig = Image.open(ORIG).convert("RGB").crop((OX0, OY0, OX1, OY1))
    ow, oh = orig.size
    po = orig.load()

    results = []
    for c in cands:
        i = c["i"]
        cx = (i % 4) * CELL_W
        cy = (i // 4) * CELL_H
        bb = ink_bbox(px, cx, cy, min(cx + CELL_W, im.size[0]),
                      min(cy + CELL_H, im.size[1]))
        if not bb:
            continue
        w, h = bb[2] - bb[0], bb[3] - bb[1]
        # MAE между растром кандидата и оригинала, выровненными по левому-верхнему ink
        s = 0
        n = 0
        for yy in range(oh):
            for xx in range(ow):
                sx, sy = bb[0] + xx, bb[1] + yy
                if sx >= im.size[0] or sy >= im.size[1]:
                    ca = BG
                else:
                    ca = px[sx, sy]
                cb = po[xx, yy]
                s += abs(ca[0] - cb[0]) + abs(ca[1] - cb[1]) + abs(ca[2] - cb[2])
                n += 1
        mae = s / (3.0 * n)
        results.append((mae, w, h, c))

    results.sort(key=lambda r: r[0])
    print(f"эталон 'Аватар' ink = {ow}x{oh} (из оригинала [{OX0},{OY0},{OX1},{OY1}])")
    print(f"\n{'MAE':>7s} {'ink':>8s}  кандидат")
    for mae, w, h, c in results[:15]:
        print(f"{mae:7.2f} {f'{w}x{h}':>8s}  {c['family']} / w={c['weight']} / "
              f"{c['size']}px / spacing={c['spacing']}px")
    with open(os.path.join(HERE, "_tf_results.json"), "w") as f:
        json.dump([{**c, "mae": round(mae, 3), "ink_w": w, "ink_h": h}
                   for mae, w, h, c in results[:40]], f,
                  ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
