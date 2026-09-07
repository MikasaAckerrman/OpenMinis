#!/usr/bin/env python3
"""residual.py — РАЗДЕЛЕНИЕ остаточной ошибки на два источника.

Смешивать их в один показатель нельзя:
    GLYPH-FORM      форма глифов недоступного шрифта — внешнее ограничение
    RECONSTRUCTION  геометрия, цвета, поверхности — это механизм обязан уметь

МЕТОД. Маска глифов строится ИЗ ОРИГИНАЛА (рендер в её построение не входит,
поэтому объявить собственный промах «текстом» невозможно). Внутри каждого
измеренного бокса: поверхность = доминирующий цвет бокса, ink = всё, что от
неё отличается, из ink оставляются только ТОНКИЕ структуры (штрих 1..3px) —
так штрихи букв отделяются от сплошных пятен вроде иконок и спрайтов.
Расширение ±1px только внутри бокса: край antialias принадлежит форме буквы.

ИСПРАВЛЕНО. Прежняя версия несла список TEXT_ZONES из 24 боксов с
координатами и цветами диалога «Настройки» CS 1.6. На любом другом скриншоте
эти зоны указывали в пустоту: маска текста выходила пустой, весь остаток
записывался в RECONSTRUCTION, и отчёт печатал «GLYPH-FORM 0.00 / 0.0%» —
уверенное число, означающее лишь то, что зоны не совпали с кадром.
Теперь боксы берутся из boxes.json (их измеряет `minis-uicopy boxes`).
"""
import json
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import metrics as M  # noqa: E402
from uicopy_ctx import ctx  # noqa: E402

HERE = ctx().dir
ORIG = os.path.join(HERE, "ORIGINAL.png")
REND = sys.argv[1] if len(sys.argv) > 1 else None
if not REND:
    print("usage: residual <render.png>", file=sys.stderr)
    sys.exit(2)
if not os.path.isabs(REND):
    REND = os.path.join(HERE, REND)

a_im = Image.open(ORIG).convert("RGB")
b_im = Image.open(REND).convert("RGB")
if a_im.size != b_im.size:
    print("!! РАЗНЫЙ РАЗМЕР %s vs %s" % (a_im.size, b_im.size))
    sys.exit(2)
a, b = np.asarray(a_im), np.asarray(b_im)
H, W = a.shape[:2]

box_path = os.path.join(HERE, "boxes.json")
if not os.path.exists(box_path):
    print("!! нет boxes.json — сначала `minis-uicopy boxes`.")
    print("   Без измеренных боксов маску глифов построить нечем, а печатать")
    print("   GLYPH-FORM = 0 было бы ложью: это не «шрифт совпал», а «зоны")
    print("   не заданы».")
    sys.exit(2)

data = json.load(open(box_path))
elements = [{"name": e["name"], "box": tuple(e["box"])}
            for e in data.get("elements", [])]
# Боксы во весь кадр (внешняя рамка, контейнер диалога) исключаются: их
# «поверхность» — фон всего кадра, и тогда в ink попадёт вообще всё
# содержимое, а не штрихи букв.
frame = 0.5 * W * H
elements = [e for e in elements
            if (e["box"][2] - e["box"][0]) * (e["box"][3] - e["box"][1]) < frame]

# Маска строится по ОБОИМ растрам: зоны заданы боксами оригинала, но глиф
# чужого шрифта физически занимает другие пиксели — там, где в оригинале
# фон, в рендере штрих. Замер на эталоне: без второго растра 31.0% различий
# уезжало в RECONSTRUCTION, хотя это форма букв; с ним — 10.8%.
glyph = M.text_mask(a, elements, rend_rgb=b)
l1 = np.abs(a.astype(np.int32) - b.astype(np.int32)).sum(axis=2)

g_n = int(glyph.sum())
r_n = W * H - g_n
g_sum = int(l1[glyph].sum()) if g_n else 0
r_sum = int(l1[~glyph].sum()) if r_n else 0
g_mae = g_sum / (3.0 * g_n) if g_n else 0.0
r_mae = r_sum / (3.0 * r_n) if r_n else 0.0
tot_mae = (g_sum + r_sum) / (3.0 * W * H)
share_g = 100.0 * g_sum / (g_sum + r_sum) if (g_sum + r_sum) else 0.0

# SSIM отдельно по не-текстовой части: структурная ошибка геометрии, не
# размытая формой букв. MAE и SSIM ошибаются в противоположные стороны,
# поэтому один без другого позволяет объявить «готово» на неверном рендере.
la, lb = M.to_luma(a), M.to_luma(b)
ssim_all = M.ssim(la, lb)

print("РЕНДЕР: %s" % os.path.basename(REND))
print("боксов для маски: %d (из %d в boxes.json, рамки во весь кадр отброшены)"
      % (len(elements), len(data.get("elements", []))))
print()
print("%-34s %10s %11s %7s %15s"
      % ("источник", "пикселей", "доля кадра", "MAE", "вклад в ошибку"))
print("%-34s %10d %10.2f%% %7.2f %14.1f%%"
      % ("GLYPH-FORM (шрифт недоступен)", g_n, 100.0 * g_n / (W * H), g_mae, share_g))
print("%-34s %10d %10.2f%% %7.2f %14.1f%%"
      % ("RECONSTRUCTION (геометрия/цвет)", r_n, 100.0 * r_n / (W * H), r_mae,
         100.0 - share_g))
print("%-34s %10d %10.2f%% %7.2f %14.1f%%" % ("ИТОГО", W * H, 100.0, tot_mae, 100.0))
print()
print("SSIM всего кадра: %.4f" % ssim_all)

if g_n == 0:
    print()
    print("ВНИМАНИЕ: маска глифов пуста. Это НЕ «шрифт совпал» — это значит,")
    print("что внутри измеренных боксов не нашлось тонких структур. Проверь")
    print("boxes.json: вероятно, детекция дала одни крупные пятна.")

# Остаточная RECONSTRUCTION-ошибка в единицах заметности, не в L1: L1 > 12
# ничего не говорит о том, видно ли это глазом.
lab_a, lab_b = M.srgb_to_lab(a), M.srgb_to_lab(b)
de = M.de76(lab_a, lab_b)
bad = int((~glyph & (de > M.JND_DE76)).sum())
print()
print("НЕ-глифовых пикселей выше порога заметности (dE76 > %.1f): %d (%.3f%% от них)"
      % (M.JND_DE76, bad, 100.0 * bad / r_n if r_n else 0.0))
print("→ это и есть остаточная RECONSTRUCTION-ошибка, которую можно устранять")

out = {
    "render": os.path.basename(REND),
    "boxes_used": len(elements),
    "glyph": {"px": g_n, "mae": round(g_mae, 3), "share_pct": round(share_g, 2)},
    "reconstruction": {"px": r_n, "mae": round(r_mae, 3),
                       "share_pct": round(100.0 - share_g, 2),
                       "px_over_jnd": bad},
    "ssim": round(ssim_all, 4),
}
with open(os.path.join(HERE, "residual.json"), "w") as f:
    json.dump(out, f, ensure_ascii=False, indent=1)
