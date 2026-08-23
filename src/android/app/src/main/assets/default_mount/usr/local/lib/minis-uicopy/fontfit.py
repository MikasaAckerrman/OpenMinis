#!/usr/bin/env python3
"""fontfit.py — подбор шрифта и кегля по ИЗМЕРЕННОЙ ширине/высоте ink.

Никакого «на глаз»: для каждого кандидата (family,size) рисуем строку в PIL и
сравниваем (ink_w, ink_h) с измеренными в оригинале. Победитель — минимум
|dw|+|dh|. Локально, 0 токенов.
"""
import sys
from PIL import Image, ImageDraw, ImageFont

FONTS = {
    "DejaVuSans": "/usr/share/fonts/dejavu/DejaVuSans.ttf",
    "DejaVuSansCondensed": "/usr/share/fonts/dejavu/DejaVuSansCondensed.ttf",
    "DejaVuSans-Bold": "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf",
    "OpenSans": "/usr/share/fonts/opensans/OpenSans-Regular.ttf",
    "OpenSans-Cond": "/usr/share/fonts/opensans/OpenSans-CondensedRegular.ttf",
    "OpenSans-SemiBold": "/usr/share/fonts/opensans/OpenSans-SemiBold.ttf",
}
import os
FONTS = {k: v for k, v in FONTS.items() if os.path.exists(v)}
for extra in ("/var/minis/skills/vision-prep/device_fonts/Roboto-Regular.ttf",
              "/usr/share/fonts/roboto/Roboto-Regular.ttf",
              "/usr/share/fonts/roboto/RobotoCondensed-Regular.ttf",
              "/usr/share/fonts/opensans/OpenSans-Light.ttf"):
    if os.path.exists(extra):
        FONTS[os.path.basename(extra)[:-4]] = extra


def ink_of(text, path, size):
    f = ImageFont.truetype(path, size)
    img = Image.new("L", (1400, 120), 0)
    dr = ImageDraw.Draw(img)
    dr.text((20, 20), text, font=f, fill=255)
    bb = img.getbbox()
    if not bb:
        return None
    return bb[2] - bb[0], bb[3] - bb[1]


# (текст, измеренная ширина ink, измеренная высота ink)
TARGETS = [
    ("Настройки", 114, 20),
    ("Мультиплеер", 164, 28),   # активный таб (с подчерком/акцентом)
    ("Клавиатура", 127, 18),
    ("Аккаунт", 86, 18),
    ("Система", 112, 28),
    ("Аватар", 65, 14),
    ("Логотип", 65, 14),
    ("Имя игрока", 124, 18),
    ("Пароль для VIP/Admin доступа", 341, 19),
    ("Загрузить...", 130, 18),
    ("Изменить цвет", 163, 17),
    ("Дополнительно...", 193, 16),
    ("OK", 29, 14),
    ("Отмена", 82, 14),
    ("Применить", 121, 19),
    ("Логотип изменится после соединения", 420, 17),
    ("с сервером.", 129, 15),
    ("cts_team", 61, 15),
    ("lambda", 55, 11),
]

if __name__ == "__main__":
    print(f"{'текст':34s} {'цель':>9s}  лучшие 3 (шрифт@кегль → ink)")
    agg = {}
    for text, tw, th in TARGETS:
        results = []
        for fname, path in FONTS.items():
            for size in range(9, 26):
                r = ink_of(text, path, size)
                if not r:
                    continue
                w, h = r
                cost = abs(w - tw) + 2 * abs(h - th)
                results.append((cost, fname, size, w, h))
        results.sort()
        best = results[:3]
        print(f"{text[:33]:34s} {tw:4d}x{th:2d}  " +
              "  ".join(f"{f}@{s}→{w}x{h}(c{c})" for c, f, s, w, h in best))
        for c, f, s, w, h in results[:5]:
            agg[(f, s)] = agg.get((f, s), 0) + c

    print("\n=== агрегат: какой (шрифт,кегль) чаще всего в топ-5 ===")
    for (f, s), c in sorted(agg.items(), key=lambda kv: kv[1])[:12]:
        print(f"  {f}@{s}  сумма стоимости {c}")
