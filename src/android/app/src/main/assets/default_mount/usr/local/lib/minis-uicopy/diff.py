#!/usr/bin/env python3
"""diff.py — измеренное сравнение ORIGINAL vs RENDER. Числа, не мнения.

ОТЧЁТ
  1. MAE глобально + MAE по не-фону (фон измеряется, не задаётся константой)
  2. SSIM — структурное сходство: ловит то, что MAE почти не видит
  3. доля пикселей выше порога заметности (dE76 > JND)
  4. ПОЭЛЕМЕНТНО по боксам из boxes.json: кто виноват
  5. НЕОТНЕСЁННАЯ ошибка: различия, под которыми нет ни одного элемента
     => в оригинале есть то, чего в рендере нет
  6. ВЫДУМАННЫЕ элементы: нарисовано там, где в оригинале фон
  7. карта различий + худшие зоны сетки

ЧТО БЫЛО СЛОМАНО ДО ЭТОЙ ВЕРСИИ (найдено чтением, не догадкой):
  * список ELEMENTS был захардкожен 29 боксами диалога «Настройки» CS 1.6
    ("btn OK": (638,607,775,642)). На любом другом скриншоте эти боксы
    указывали в пустоту, а поэлементный отчёт печатал уверенные числа ни о чём.
  * BG = (76,88,68) — оливковый фон CS 1.6. На другом скриншоте метрика
    «MAE по не-фону» молча вырождалась в копию глобальной MAE под другим
    именем. Это хуже отсутствия метрики: у неверного числа есть правильный
    заголовок.
Оба значения теперь измеряются. boxes.json пишет boxes.py.
"""
import json
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import metrics as M  # noqa: E402

A = sys.argv[1] if len(sys.argv) > 1 else "ORIGINAL.png"
B = sys.argv[2] if len(sys.argv) > 2 else "render_v1.png"
OUT = sys.argv[3] if len(sys.argv) > 3 else "diff.png"

a_im = Image.open(A).convert("RGB")
b_im = Image.open(B).convert("RGB")
if a_im.size != b_im.size:
    print("!! РАЗНЫЙ РАЗМЕР %s vs %s — сравнение невозможно" % (a_im.size, b_im.size))
    sys.exit(2)

a = np.asarray(a_im)
b = np.asarray(b_im)
H, W = a.shape[:2]

# ─── фон: измеряется ───
BG, bg_frac = M.dominant_color(a)
bgv = np.array(BG, dtype=np.int32)
nonbg = np.abs(a.astype(np.int32) - bgv).sum(axis=2) > 14

lab_a, lab_b = M.srgb_to_lab(a), M.srgb_to_lab(b)
de = M.de76(lab_a, lab_b)
mask = de > M.JND_DE76

l1 = np.abs(a.astype(np.int32) - b.astype(np.int32)).sum(axis=2)
mae = float(l1.mean()) / 3.0
mae_nonbg = float(l1[nonbg].mean()) / 3.0 if nonbg.any() else 0.0
ssim = M.ssim(M.to_luma(a), M.to_luma(b))

print("РАЗМЕР            %dx%d" % (W, H))
print("фон (измерен)     rgb%s, %.1f%% кадра" % (BG, 100.0 * bg_frac))
if bg_frac < 0.25:
    print("                  ВНИМАНИЕ: фон не доминирует — строка «по не-фону»")
    print("                  почти совпадает с глобальной, читать как одну.")
print("MAE глобально     %.2f   (0..255)" % mae)
print("MAE по не-фону    %.2f   (%d px = %.1f%% кадра)"
      % (mae_nonbg, int(nonbg.sum()), 100.0 * nonbg.mean()))
print("SSIM              %.4f  (1.0 = структурно идентично)" % ssim)
print("отличается > JND  %d px = %.2f%%  (dE76 > %.1f)"
      % (int(mask.sum()), 100.0 * mask.mean(), M.JND_DE76))
print("точно совпало     %d px = %.1f%%"
      % (int((l1 == 0).sum()), 100.0 * float((l1 == 0).mean())))
if mask.any():
    print("макс dE76         %.1f в точке %s"
          % (float(de.max()), tuple(int(v) for v in np.unravel_index(de.argmax(), de.shape)[::-1])))

# ─── элементы: из boxes.json, не из хардкода ───
box_path = os.path.join(os.path.dirname(os.path.abspath(A)), "boxes.json")
elements = []
if os.path.exists(box_path):
    try:
        data = json.load(open(box_path))
        elements = [{"name": e["name"], "box": tuple(e["box"])}
                    for e in data.get("elements", [])]
    except Exception as exc:            # noqa: BLE001
        print("\n!! boxes.json не читается (%s) — поэлементного отчёта не будет" % exc)

if not elements:
    print("\n=== ПОЭЛЕМЕНТНО: нет boxes.json ===")
    print("    Запусти `minis-uicopy boxes` — он измерит элементы и запишет")
    print("    boxes.json. Без него ниже только сетка регионов.")
else:
    rows, unattributed = M.blame(mask, elements)
    print("\n=== ПОЭЛЕМЕНТНО (по убыванию числа отличающихся пикселей) ===")
    print("    %-28s %7s %7s %7s  %s" % ("элемент", "px", "своих%", "доля%", "dE цвета"))
    for r in rows[:20]:
        cd = M.element_color_delta(a, b, r["box"])
        cdtxt = ""
        if cd is not None:
            dE, ca, cb = cd
            cdtxt = "%.1f %s->%s" % (dE, ca, cb) if dE > 1.0 else "-"
        flag = "  <<<" if r["own_pct"] > 20 else ""
        print("    %-28s %7d %6.1f%% %6.1f%%  %s%s"
              % (r["name"][:28], r["hits"], r["own_pct"], r["share_pct"], cdtxt, flag))
    if len(rows) > 20:
        print("    ... ещё %d элементов с различиями" % (len(rows) - 20))

    # НЕОТНЕСЁННАЯ ошибка — то, чего прежний поэлементный MAE показать не мог
    print("\n=== НЕОТНЕСЁННАЯ ОШИБКА: %.1f%% различий ===" % (100.0 * unattributed))
    if unattributed > 0.15:
        print("    Больше 15%% различий лежит там, где НЕТ ни одного элемента.")
        print("    Читается однозначно: в оригинале есть содержимое, которого")
        print("    в рендере нет вообще. Искать пропущенный элемент, а не")
        print("    подгонять цвета существующих.")
    else:
        print("    В норме: почти все различия отнесены к конкретным элементам.")

    hall = M.hallucinated(mask, elements, a, BG)
    if hall:
        print("\n=== ВЫДУМАННЫЕ ЭЛЕМЕНТЫ (в оригинале там фон) ===")
        for h in hall[:10]:
            print("    %-28s %s  фон в оригинале %.0f%%, различий %d px"
                  % (h["name"][:28], h["box"], 100.0 * h["bg_frac"], h["hits"]))

# ─── сетка регионов ───
grid = M.region_grid(mask, 8, 8)
print("\n=== СЕТКА РЕГИОНОВ (%% различающихся пикселей в ячейке) ===")
for r in range(8):
    print("    " + "".join("%6.1f" % grid[r, c] for c in range(8)))
hot = sorted(((grid[r, c], r, c) for r in range(8) for c in range(8)), reverse=True)[:3]
if hot[0][0] > 0:
    print("    худшие: " + " · ".join("r%dc%d=%.1f%%" % (r + 1, c + 1, v)
                                      for v, r, c in hot if v > 0))

# ─── карта различий: ДВА ЦВЕТА ───
# Один цвет на всё был прямой причиной неверного вывода: 69% красного на
# эталоне — форма букв недоступного шрифта, но по картинке это читалось как
# «элементы не доделаны». Жёлтый = внешнее ограничение, красный = то, что
# механизм обязан устранить. Маска глифов по ОБОИМ растрам (см. text_mask).
glyph_els = [e for e in elements
             if (e["box"][2] - e["box"][0]) * (e["box"][3] - e["box"][1]) < 0.5 * W * H]
glyph = (M.text_mask(a, glyph_els, rend_rgb=b) if glyph_els
         else np.zeros_like(mask))
m_font = mask & glyph
m_geo = mask & ~glyph
heat = (a.astype(np.float64) * 0.30).astype(np.uint8)
heat[m_font] = (255, 210, 40)
heat[m_geo] = (255, 40, 40)

# Легенда впечатывается в PNG: смысл цвета, живущий только в тексте вывода,
# теряется в тот момент, когда картинку смотрят отдельно от лога.
try:
    from PIL import ImageDraw
    im = Image.fromarray(heat)
    d = ImageDraw.Draw(im)
    bar = 22
    d.rectangle([0, 0, im.width, bar], fill=(18, 18, 18))
    d.rectangle([6, 6, 18, 16], fill=(255, 210, 40))
    d.text((23, 7), "forma bukv (shrift nedostupen): %d px" % int(m_font.sum()),
           fill=(230, 230, 230))
    x2 = im.width // 2
    d.rectangle([x2, 6, x2 + 12, 16], fill=(255, 40, 40))
    d.text((x2 + 17, 7), "geometriya/cvet (chinitsya): %d px" % int(m_geo.sum()),
           fill=(230, 230, 230))
    im.save(OUT)
except Exception:
    Image.fromarray(heat).save(OUT)
print("\nкарта различий: %s" % OUT)
print("    жёлтое  = форма букв, шрифт недоступен: %d px (%.1f%% различий)"
      % (int(m_font.sum()), 100.0 * m_font.sum() / max(1, mask.sum())))
print("    красное = геометрия и цвет, ЭТО чинится: %d px (%.1f%%)"
      % (int(m_geo.sum()), 100.0 * m_geo.sum() / max(1, mask.sum())))
