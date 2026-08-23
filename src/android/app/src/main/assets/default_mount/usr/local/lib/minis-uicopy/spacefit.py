#!/usr/bin/env python3
"""spacefit.py — ПОСТРОЧНЫЙ подбор letter-spacing по РЕНДЕРУ.

ЗАЧЕМ. v6 показал: один глобальный трекинг 1.5px не оптимален — у длинной
строки Δw=+14px, у коротких −2px. Ширина зависит от числа зазоров (n-1), а
глобальный трекинг раздувает длинные строки. Значит spacing надо подбирать
на строку.

КАК. Одна страница: каждая строка × набор spacing. Один вызов chromium (тот
же растеризатор, что у финального рендера), затем ink-габариты каждой ячейки
и выбор минимума |dw| (тай-брейк — меньший |dh|, затем меньший spacing).
Никакого PIL-приближения: PIL растеризует иначе, это уже доказано.
"""
import subprocess, os, json
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from uicopy_ctx import ctx
HERE = ctx().dir
TOOLS = os.path.dirname(os.path.abspath(__file__))
BG = (76, 88, 68)
CELL_H = 40
COLS = 3
CELL_W = 620

# (ключ, текст, целевая ink-ширина, целевая ink-высота, цвет)
STRINGS = [
    ("title",      "Настройки", 114, 20, "#f9f9f8"),
    ("tab_act",    "Мультиплеер", 145, 18, "#c3b450"),
    ("tab_klav",   "Клавиатура", 127, 18, "#edeeec"),
    ("tab_mouse",  "Мышь", 67, 14, "#edeeec"),
    ("tab_sound",  "Звук", 50, 18, "#edeeec"),
    ("tab_video",  "Видео", 65, 16, "#edeeec"),
    ("tab_hud",    "HUD", 44, 14, "#edeeec"),
    ("tab_acc",    "Аккаунт", 86, 18, "#edeeec"),
    ("tab_sys",    "Система", 112, 22, "#edeeec"),
    ("lbl_avatar", "Аватар", 79, 18, "#b7bfb1"),
    ("lbl_logo",   "Логотип", 90, 14, "#b7bfb1"),
    ("lbl_name",   "Имя игрока", 124, 18, "#b7bfb1"),
    ("lbl_pass",   "Пароль для VIP/Admin доступа", 341, 19, "#b7bfb1"),
    ("btn_load",   "Загрузить...", 130, 18, "#cdd3c8"),
    ("btn_color",  "Изменить цвет", 163, 17, "#cdd3c8"),
    ("btn_more",   "Дополнительно...", 193, 16, "#cdd3c8"),
    ("btn_ok",     "OK", 29, 14, "#cdd3c8"),
    ("btn_cancel", "Отмена", 82, 14, "#cdd3c8"),
    ("btn_apply",  "Применить", 121, 19, "#727d6c"),
    ("dis1",       "Логотип изменится после соединения", 420, 17, "#97a28d"),
    ("dis2",       "с сервером.", 129, 15, "#97a28d"),
    ("entry_name", "[B] К о Н Т р Е", 157, 20, "#bec4b9"),
    ("combo_cts",  "cts_team", 97, 17, "#bec4b9"),
    ("combo_lam",  "lambda", 76, 15, "#bec4b9"),
]
SPACINGS = [0, 0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0]


def build(path):
    cells = []
    divs = []
    for key, text, tw, th, col in STRINGS:
        for sp in SPACINGS:
            i = len(cells)
            cells.append((key, text, tw, th, sp))
            cx = (i % COLS) * CELL_W
            cy = (i // COLS) * CELL_H
            divs.append(
                f'<div style="position:absolute;left:{cx+10}px;top:{cy+10}px;'
                f'letter-spacing:{sp}px;color:{col};line-height:19px;'
                f'white-space:nowrap">{text}</div>')
    h = ((len(cells) + COLS - 1) // COLS) * CELL_H + 30
    html = ("<!DOCTYPE html><html><head><meta charset='utf-8'><style>"
            "*{margin:0;padding:0}html,body{background:#4c5844}"
            "body{font-family:'DejaVu Sans';font-weight:normal;font-size:19px;"
            "-webkit-font-smoothing:antialiased;"
            "text-rendering:geometricPrecision}</style></head><body>"
            + "".join(divs) + "</body></html>")
    with open(path, "w") as f:
        f.write(html)
    return cells, COLS * CELL_W, h


def main():
    page = os.path.join(HERE, "_sf.html")
    shot = os.path.join(HERE, "_sf.png")
    cells, W, H = build(page)
    subprocess.run(["sh", os.path.join(TOOLS, "render.sh"),
                    page, shot, str(W), str(H + 120)], capture_output=True)
    im = Image.open(shot).convert("RGB")
    px = im.load()
    IW, IH = im.size
    assert px[3, 3] == BG, f"фон рендера {px[3,3]} — рендер не удался"

    def ink(cx, cy):
        xs, ys, strong, weak = [], [], 0, 0
        for y in range(cy, min(cy + CELL_H, IH)):
            for x in range(cx, min(cx + CELL_W, IW)):
                c = px[x, y]
                d = abs(c[0]-BG[0]) + abs(c[1]-BG[1]) + abs(c[2]-BG[2])
                if d > 30:
                    xs.append(x); ys.append(y)
                    if d > 150: strong += 1
                    else: weak += 1
        if not xs:
            return None
        return (max(xs)+1-min(xs), max(ys)+1-min(ys),
                strong/(strong+weak) if strong+weak else 0)

    by_key = {}
    for i, (key, text, tw, th, sp) in enumerate(cells):
        cx = (i % COLS) * CELL_W
        cy = (i // COLS) * CELL_H
        r = ink(cx, cy)
        if not r:
            continue
        w, h, st = r
        cost = abs(w - tw) * 10 + abs(h - th) * 3 + sp * 0.1
        by_key.setdefault(key, []).append((cost, sp, w, h, st, tw, th))

    print(f"{'строка':12s} {'цель':>8s} {'лучший spacing':>15s} {'ink':>9s} "
          f"{'Δw':>4s} {'Δh':>4s}")
    best = {}
    for key, text, tw, th, col in STRINGS:
        opts = sorted(by_key.get(key, []))
        if not opts:
            print(f"{key:12s} НЕТ ДАННЫХ")
            continue
        cost, sp, w, h, st, tw2, th2 = opts[0]
        best[key] = {"spacing": sp, "ink_w": w, "ink_h": h,
                     "target_w": tw, "target_h": th,
                     "dw": w - tw, "dh": h - th, "strong": round(st, 3)}
        print(f"{key:12s} {f'{tw}x{th}':>8s} {sp:15.2f} {f'{w}x{h}':>9s} "
              f"{w-tw:+4d} {h-th:+4d}")

    with open(os.path.join(HERE, "spacing.json"), "w") as f:
        json.dump(best, f, ensure_ascii=False, indent=1)
    tot = sum(abs(v["dw"]) for v in best.values())
    print(f"\nсуммарная |Δw| при построчном spacing: {tot}px "
          f"(при глобальном 1.5px было 34px на 9 строках)")
    print(f"→ {os.path.join(HERE,'spacing.json')}")


if __name__ == "__main__":
    main()
