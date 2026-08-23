#!/usr/bin/env python3
"""edgefit.py — 1px-кромки кадра как ИЗМЕРЕННЫЕ полосы, а не одноцветные линии.

ДИАГНОЗ (измерено). Внешние 2 столбца/2 строки кадра НЕ однотонные:
  x=0: y=0 #363e35 → y=80 #a9a61a (жёлтый!) → y=360 #243456 (синий)
       651 уникальных цветов в строке y=0.
Это не «рамка диалога» — это ОКРУЖЕНИЕ за диалогом (обои рабочего стола),
попавшее в кадр. Одним цветом такое не выразить: в v8 стояли плоские
#2c3b5a/#54606a и давали MAE 11 на полосе y=0..3.

РЕШЕНИЕ. Эти 2 столбца и 2 строки — не UI-элемент, а фоновая среда.
Честный путь для РЕДАКТИРУЕМОГО UI: вынести их как измеренные градиенты
(многостоповые), а не как картинку. Генерируем linear-gradient со стопом
на каждый пиксель — это перенос измерения, вёрстка остаётся текстовой.
"""
import sys, os
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from uicopy_ctx import ctx
C = ctx()
a, px, W, H = C.orig, C.px, C.W, C.H


def hx(c):
    return "#%02x%02x%02x" % c


def vgrad(x, y0, y1, step=1):
    """Вертикальный градиент столбца x (стоп на каждые step px)."""
    stops = []
    for y in range(y0, y1, step):
        c = hx(px[x, y])
        stops.append(f"{c} {y-y0}px")
        stops.append(f"{c} {y-y0+step}px")
    return "linear-gradient(to bottom," + ",".join(stops) + ")"


def hgrad(y, x0, x1, step=1):
    """Горизонтальный градиент строки y."""
    stops = []
    for x in range(x0, x1, step):
        c = hx(px[x, y])
        stops.append(f"{c} {x-x0}px")
        stops.append(f"{c} {x-x0+step}px")
    return "linear-gradient(to right," + ",".join(stops) + ")"


if __name__ == "__main__":
    step = int(sys.argv[1]) if len(sys.argv) > 1 else 4
    print(f"/* кромки кадра: измеренные градиенты, шаг {step}px */")
    # левые 2 столбца (x=0,1) на всю высоту
    for x in (0, 1):
        print(f".ecl{x}{{position:absolute;left:{x}px;top:0;width:1px;"
              f"height:{H}px;background:{vgrad(x,0,H,step)}}}")
    # верхние 2 строки (y=0,1) на всю ширину
    for y in (0, 1):
        print(f".ect{y}{{position:absolute;left:0;top:{y}px;width:{W}px;"
              f"height:1px;background:{hgrad(y,0,W,step)}}}")
    # правый столбец x=1096 и низ y=653 — проверим, однотонны ли
    colr = set(px[1096, y] for y in range(0, H, 7))
    rowb = set(px[x, 653] for x in range(0, W, 7))
    print(f"/* x=1096: {len(colr)} цветов; y=653: {len(rowb)} цветов */")
    if len(colr) <= 3:
        print(f"/* правый край почти однотонный: {[hx(c) for c in list(colr)[:3]]} */")
    if len(rowb) <= 3:
        print(f"/* низ почти однотонный: {[hx(c) for c in list(rowb)[:3]]} */")
