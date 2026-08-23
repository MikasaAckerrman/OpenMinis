#!/usr/bin/env python3
"""diff.py — измеренное сравнение ORIGINAL vs RENDER. Числа, не мнения.

Отчёт:
  1. глобальный MAE + MAE по НЕ-фоновым пикселям (фон 83% => глобальный врёт)
  2. worst-1% вклад
  3. поэлементный MAE по измеренным боксам (какой элемент виноват)
  4. карта различий (diff.png) + список худших зон 32x32
"""
import sys
from PIL import Image, ImageChops

A = sys.argv[1] if len(sys.argv) > 1 else "ORIGINAL.png"
B = sys.argv[2] if len(sys.argv) > 2 else "render_v1.png"
OUT = sys.argv[3] if len(sys.argv) > 3 else "diff.png"

a = Image.open(A).convert("RGB")
b = Image.open(B).convert("RGB")
if a.size != b.size:
    print(f"!! РАЗНЫЙ РАЗМЕР {a.size} vs {b.size}")
    sys.exit(2)
W, H = a.size
pa, pb = a.load(), b.load()
BG = (76, 88, 68)


def dpix(x, y):
    ca, cb = pa[x, y], pb[x, y]
    return abs(ca[0] - cb[0]) + abs(ca[1] - cb[1]) + abs(ca[2] - cb[2])


# --- глобально ---
tot = 0
diffs = []
nonbg_tot = 0
nonbg_n = 0
for y in range(H):
    for x in range(W):
        d = dpix(x, y)
        tot += d
        diffs.append(d)
        ca = pa[x, y]
        if abs(ca[0] - BG[0]) + abs(ca[1] - BG[1]) + abs(ca[2] - BG[2]) > 14:
            nonbg_tot += d
            nonbg_n += 1

n = W * H
mae = tot / (3.0 * n)
nonbg_mae = nonbg_tot / (3.0 * nonbg_n) if nonbg_n else 0
diffs.sort(reverse=True)
worst1 = sum(diffs[:max(1, n // 100)])
print(f"РАЗМЕР        {W}x{H}")
print(f"MAE глобально {mae:.2f}")
print(f"MAE по контенту (не-фон, {nonbg_n} px = {100.0*nonbg_n/n:.1f}%) {nonbg_mae:.2f}")
print(f"worst-1% даёт {100.0*worst1/tot:.1f}% всей ошибки" if tot else "идентично")
exact = sum(1 for d in diffs if d == 0)
print(f"пикселей точно совпало: {exact} ({100.0*exact/n:.1f}%)")
print(f"пикселей |d|<=12:       {sum(1 for d in diffs if d <= 12)} "
      f"({100.0*sum(1 for d in diffs if d <= 12)/n:.1f}%)")

# --- по элементам (измеренные боксы) ---
ELEMENTS = {
    "внешний фон (полоса сверху)": (0, 0, 1097, 3),
    "титульная полоса": (0, 3, 1097, 44),
    "  иконка заголовка": (12, 10, 48, 38),
    "  текст 'Настройки'": (53, 14, 200, 40),
    "  крестик закрытия": (1047, 8, 1088, 41),
    "полоса вкладок": (8, 44, 1090, 84),
    "  tab active": (10, 44, 190, 84),
    "  tab Клавиатура": (190, 44, 349, 84),
    "  tab Система": (959, 44, 1086, 84),
    "label Аватар": (85, 128, 140, 150),
    "avatar asset": (85, 155, 207, 246),
    "btn Загрузить...": (220, 154, 454, 189),
    "combo cts_team": (220, 211, 454, 246),
    "label Логотип": (85, 256, 140, 278),
    "logo asset": (85, 285, 207, 376),
    "combo lambda": (220, 285, 454, 320),
    "btn Изменить цвет": (220, 341, 454, 376),
    "disabled текст 2 строки": (85, 393, 510, 440),
    "btn Дополнительно...": (85, 447, 312, 482),
    "label Имя игрока": (537, 128, 670, 150),
    "entry Имя игрока": (537, 155, 914, 189),
    "label Пароль": (537, 256, 890, 280),
    "entry Пароль + глаз": (537, 285, 914, 320),
    "разделитель футера": (8, 596, 1090, 606),
    "btn OK": (638, 607, 775, 642),
    "btn Отмена": (788, 607, 925, 642),
    "btn Применить": (938, 607, 1075, 642),
    "пустая правая зона": (930, 100, 1080, 590),
}
print("\n=== ПОЭЛЕМЕНТНО (MAE, отсортировано по убыванию) ===")
rows = []
for name, (x0, y0, x1, y1) in ELEMENTS.items():
    s = 0
    cnt = 0
    mx = 0
    for y in range(max(0, y0), min(H, y1)):
        for x in range(max(0, x0), min(W, x1)):
            d = dpix(x, y)
            s += d
            mx = max(mx, d)
            cnt += 1
    rows.append((s / (3.0 * cnt) if cnt else 0, mx, name, (x0, y0, x1, y1)))
rows.sort(reverse=True)
for m, mx, name, box in rows:
    flag = "  <<<" if m > 12 else ""
    print(f"  {m:7.2f} max={mx:4d}  {name:30s} {box}{flag}")

# --- худшие зоны 32x32 ---
print("\n=== ХУДШИЕ ЗОНЫ 32x32 ===")
tiles = []
for ty in range(0, H, 32):
    for tx in range(0, W, 32):
        s = 0
        cnt = 0
        for y in range(ty, min(ty + 32, H)):
            for x in range(tx, min(tx + 32, W)):
                s += dpix(x, y)
                cnt += 1
        tiles.append((s / (3.0 * cnt), tx, ty))
tiles.sort(reverse=True)
for m, tx, ty in tiles[:12]:
    print(f"  MAE {m:7.2f}  зона ({tx},{ty})-({tx+32},{ty+32})")

# --- карта ---
dmap = ImageChops.difference(a, b).convert("L").point(lambda v: min(255, v * 4))
dmap.save(OUT)
print(f"\nкарта различий → {OUT}")
