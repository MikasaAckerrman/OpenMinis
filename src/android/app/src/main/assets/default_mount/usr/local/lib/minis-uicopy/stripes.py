#!/usr/bin/env python3
"""stripes.py — извлечь ИЗМЕРЕННЫЕ профили границ и сгенерировать CSS-полоски.

ЗАЧЕМ. Границы в оригинале — не однопиксельные CSS-border, а 5-7-пиксельные
переходы (измерено: разделитель таба на x=185..191 = #384031 #2c3225 #3f4937
#56624e #76806e #7a8472 #5a6552). CSS border такого не выражает, а
приближение одной линией давало MAE 26 на табах.

РЕШЕНИЕ. Для каждой границы берём РЕАЛЬНЫЙ профиль пикселей из оригинала и
печатаем его как linear-gradient с жёсткими стопами (по 1px). Это не
«придумывание стиля», а перенос измерения в вёрстку.
"""
import sys, os
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from uicopy_ctx import ctx
C = ctx()
a, px, W, H = C.orig, C.px, C.W, C.H
BG = (76, 88, 68)


def hx(c):
    return "#%02x%02x%02x" % c


def hprofile(x0, x1, y):
    """Горизонтальный профиль (вертикальная полоса шириной x1-x0)."""
    return [px[x, y] for x in range(x0, x1)]


def vprofile(y0, y1, x):
    """Вертикальный профиль (горизонтальная полоса высотой y1-y0)."""
    return [px[x, y] for y in range(y0, y1)]


def grad(colors, to="right"):
    """linear-gradient с жёсткими 1px-стопами — точная копия профиля."""
    n = len(colors)
    parts = []
    for i, c in enumerate(colors):
        parts.append(f"{hx(c)} {i}px, {hx(c)} {i+1}px")
    return f"linear-gradient(to {to}, " + ", ".join(parts) + ")"


def css_vstripe(name, x0, x1, y_top, y_bot, sample_y):
    """Вертикальная полоса-разделитель."""
    cols = hprofile(x0, x1, sample_y)
    return (f".{name}{{position:absolute;left:{x0}px;top:{y_top}px;"
            f"width:{x1-x0}px;height:{y_bot-y_top}px;"
            f"background:{grad(cols,'right')}}}")


def css_hstripe(name, y0, y1, x_left, x_right, sample_x):
    """Горизонтальная полоса."""
    rows = vprofile(y0, y1, sample_x)
    return (f".{name}{{position:absolute;left:{x_left}px;top:{y0}px;"
            f"width:{x_right-x_left}px;height:{y1-y0}px;"
            f"background:{grad(rows,'bottom')}}}")


if __name__ == "__main__":
    print("/* === ИЗМЕРЕННЫЕ ПРОФИЛИ ГРАНИЦ (сгенерировано stripes.py) === */")

    # 1) разделители табов: 9 позиций, профиль 7px, y=50..80
    print("\n/* вертикальные разделители полосы вкладок */")
    seps = [(7, 14, "sep0"), (185, 192, "sep1"), (343, 351, "sep2"),
            (466, 473, "sep3"), (588, 595, "sep4"), (710, 717, "sep5"),
            (832, 839, "sep6"), (954, 961, "sep7")]
    for x0, x1, nm in seps:
        print(css_vstripe(nm, x0, x1, 47, 81, 65))

    # 2) верхние кромки табов
    print("\n/* верхние кромки табов: inactive y=47..50, active y=45..47 */")
    print(css_hstripe("tabtop_i", 47, 50, 0, 1, 250).replace(
        "left:0px", "left:var(--l)").replace("width:1px", "width:var(--w)"))
    print(css_hstripe("tabtop_a", 45, 47, 0, 1, 100).replace(
        "left:0px", "left:var(--l)").replace("width:1px", "width:var(--w)"))

    # 3) нижняя кромка (верх рамки content) под неактивными: y=81..84
    print("\n/* верхняя кромка рамки содержимого под неактивными табами */")
    print(css_hstripe("cframe_top", 81, 84, 10, 1087, 600))

    # 4) внешние кромки кадра
    print("\n/* внешние кромки кадра */")
    print(css_hstripe("frame_top", 0, 4, 0, 1097, 500))
    print(css_vstripe("frame_left", 0, 4, 0, 654, 330))
    print(css_hstripe("frame_bot", 651, 654, 0, 1097, 500))
    print(css_vstripe("frame_right", 1094, 1097, 0, 654, 330))

    # 5) разделитель футера
    print("\n/* разделитель футера */")
    print(css_hstripe("footsep", 599, 604, 10, 1087, 548))

    # 6) bevel кнопки: профили слева/справа/сверху/снизу
    print("\n/* bevel кнопки (btn OK 638,607..775,642) */")
    print("/*  L->R:", [hx(c) for c in hprofile(636, 644, 624)], "*/")
    print("/*  R->L:", [hx(c) for c in hprofile(770, 778, 624)], "*/")
    print("/*  T->B:", [hx(c) for c in vprofile(605, 613, 700)], "*/")
    print("/*  B->T:", [hx(c) for c in vprofile(636, 645, 700)], "*/")

    print("\n/* bevel поля (entry 537,155..914,189) */")
    print("/*  L->R:", [hx(c) for c in hprofile(535, 543, 172)], "*/")
    print("/*  R->L:", [hx(c) for c in hprofile(908, 917, 172)], "*/")
    print("/*  T->B:", [hx(c) for c in vprofile(153, 161, 700)], "*/")
    print("/*  B->T:", [hx(c) for c in vprofile(184, 192, 700)], "*/")
