#!/usr/bin/env python3
"""metrics.py — чистые измерения для ui-copy. Без файлового I/O, без chromium.

ЗАЧЕМ ОТДЕЛЬНЫЙ МОДУЛЬ. Всё, что выдаёт ВЕРДИКТ, должно быть проверяемо
синтетическими входами (selftest.py). Пока метрика живёт внутри скрипта,
который сам открывает файлы и печатает отчёт, её нельзя прогнать на паре
«сдвиг на 1px» и убедиться, что она отличает это от «сменился оттенок».

Заимствованные идеи (обе лицензии MIT, авторство указано):
  * mean-SSIM по блокам — overseek944/pixelpilot ((c) 2026 Manav Patel),
    src/diff/ssim.ts. Константы по Wang et al. 2004.
  * атрибуция ошибки по боксам DOM + доля НЕотнесённой ошибки —
    Marvin0212/ui-agent-loop ((c) 2026 Marvin Seiferling), evaluate.py.
  * учёт несопоставленных блоков в ДВЕ стороны (пропущено / выдумано) —
    идея метрики Block-Match из NoviScl/Design2Code.
Реализация здесь своя (numpy). Формулировки адаптированы под наш случай:
у оригинала DOM физически отсутствует, оригинал — растр.
"""
import numpy as np

# Порог «пиксель отличается заметно», в единицах dE76. 2.3 — общепринятая
# граница едва заметного различия (JND) в CIE Lab.
#
# ЧТО ИМЕННО ДАЁТ ЗАМЕНА L1 НА dE76 (измерено, selftest фиксирует числа):
# при одинаковом L1=12 на канал dE76 равен 4.5 для сдвига яркости серого и
# 20.8 для сдвига того же размера в одном синем канале. То есть L1 уравнивает
# промах по яркости и промах по цветности, а видно их совершенно по-разному.
# Ранжирование «какой элемент промахнулся сильнее» по L1 из-за этого ставит
# заметный цветовой промах ниже незаметного яркостного.
# Чего замена НЕ даёт: чувствительности к светлоте фона — при одинаковом L1
# dE76 для тёмного и светлого серого почти совпадает (4.51 против 4.20).
# Раньше в этом комментарии было написано обратное; проверка показала, что
# утверждение ложно, и оно убрано, а не подогнано.
JND_DE76 = 2.3


# ------------------------------- цвет -------------------------------

def srgb_to_lab(rgb):
    """uint8 (...,3) sRGB -> float (...,3) CIE Lab, белая точка D65.

    Проверяемо: чистый белый обязан дать L=100, a=b=0 (см. selftest).
    """
    c = np.asarray(rgb).astype(np.float64) / 255.0
    lin = np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
    r, g, b = lin[..., 0], lin[..., 1], lin[..., 2]
    x = r * 0.4124564 + g * 0.3575761 + b * 0.1804375
    y = r * 0.2126729 + g * 0.7151522 + b * 0.0721750
    z = r * 0.0193339 + g * 0.1191920 + b * 0.9503041
    x = x / 0.95047
    z = z / 1.08883
    eps, kappa = 216.0 / 24389.0, 24389.0 / 27.0

    def f(t):
        return np.where(t > eps, np.cbrt(t), (kappa * t + 16.0) / 116.0)

    fx, fy, fz = f(x), f(y), f(z)
    return np.stack([116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz)],
                    axis=-1)


def de76(lab_a, lab_b):
    """dE*ab (CIE76) — евклидово расстояние в Lab.

    Именно 76, а не CIEDE2000, и это осознанно: dE00 нельзя проверить без
    эталонных векторов Sharma et al., которых в песочнице нет, а
    непроверенная метрика хуже более простой проверяемой. Для задачи
    «ранжировать, какой элемент промахнулся сильнее» разница между 76 и 00
    много меньше разницы между 76 и L1-по-sRGB.
    """
    return np.sqrt(((np.asarray(lab_a) - np.asarray(lab_b)) ** 2).sum(axis=-1))


def dominant_color(rgb, sample_step=1):
    """(r,g,b) самого частого цвета и его доля 0..1.

    Заменяет захардкоженный BG. Доля — обязательная часть ответа: метрика
    «MAE по не-фону» осмысленна только если фон реально занимает большую
    часть кадра. На фотографии доминирующий цвет займёт 2%, и молчаливое
    использование его как «фона» превратило бы не-фоновую метрику в копию
    глобальной под другим именем.
    """
    a = np.asarray(rgb)[::sample_step, ::sample_step].reshape(-1, 3).astype(np.int64)
    packed = (a[:, 0] << 16) | (a[:, 1] << 8) | a[:, 2]
    vals, counts = np.unique(packed, return_counts=True)
    k = int(vals[int(counts.argmax())])
    return ((k >> 16) & 255, (k >> 8) & 255, k & 255), float(counts.max()) / len(packed)


# ------------------------------ ошибка ------------------------------

def l1_mae(a, b):
    """Средняя |Δ| по каналам, шкала 0..255. Историческая метрика нашего
    diff — сохранена, чтобы числа прошлых прогонов остались сравнимы."""
    return float(np.abs(np.asarray(a).astype(np.int32)
                        - np.asarray(b).astype(np.int32)).mean())


def mismatch_mask(lab_a, lab_b, thresh=JND_DE76):
    """bool-маска «этот пиксель отличается заметно»."""
    return de76(lab_a, lab_b) > thresh


def to_luma(rgb):
    c = np.asarray(rgb).astype(np.float64)
    return 0.299 * c[..., 0] + 0.587 * c[..., 1] + 0.114 * c[..., 2]


def ssim(gray_a, gray_b, window=8):
    """mean-SSIM по неперекрывающимся блокам. 1.0 = идентично.

    Нужен рядом с MAE, потому что они ошибаются в противоположные стороны:
    MAE почти не замечает текст, съехавший на 1px (мало пикселей), но резко
    растёт от слабого сдвига оттенка по всей плашке. SSIM наоборот. Один
    показатель без второго позволяет объявить «готово» на структурно
    неверном рендере.
    """
    ga, gb = np.asarray(gray_a, dtype=np.float64), np.asarray(gray_b, dtype=np.float64)
    h = (ga.shape[0] // window) * window
    w = (ga.shape[1] // window) * window
    if h == 0 or w == 0:
        return 1.0 if np.array_equal(ga, gb) else 0.0
    a = ga[:h, :w].reshape(h // window, window, w // window, window)
    b = gb[:h, :w].reshape(h // window, window, w // window, window)
    a = a.transpose(0, 2, 1, 3).reshape(-1, window * window)
    b = b.transpose(0, 2, 1, 3).reshape(-1, window * window)
    mu_a, mu_b = a.mean(1), b.mean(1)
    va, vb = a.var(1), b.var(1)
    cov = ((a - mu_a[:, None]) * (b - mu_b[:, None])).mean(1)
    c1, c2 = (0.01 * 255) ** 2, (0.03 * 255) ** 2
    s = ((2 * mu_a * mu_b + c1) * (2 * cov + c2)) / \
        ((mu_a ** 2 + mu_b ** 2 + c1) * (va + vb + c2))
    return float(s.mean())


def region_grid(mask, rows=8, cols=8):
    """Доля различий в каждой ячейке сетки, %. Грубая локализация, не
    зависящая ни от какого списка элементов — работает и когда боксов нет."""
    m = np.asarray(mask)
    h, w = m.shape
    out = np.zeros((rows, cols))
    for r in range(rows):
        for c in range(cols):
            y0, y1 = r * h // rows, (r + 1) * h // rows
            x0, x1 = c * w // cols, (c + 1) * w // cols
            cell = m[y0:y1, x0:x1]
            out[r, c] = 100.0 * cell.mean() if cell.size else 0.0
    return out


# --------------------- атрибуция по элементам ---------------------

def blame(mask, elements, huge_frac=0.5):
    """Кто виноват. elements: [{'name': str, 'box': (x0,y0,x1,y1)}].

    Возвращает (строки_по_убыванию, доля_неотнесённого).

    Доля неотнесённого — то, чего у нас не было: различия, под которыми НЕТ
    ни одного нарисованного элемента, означают «в оригинале есть то, чего я
    не построил». Прежний поэлементный MAE такое показать не мог физически:
    он считал только внутри известных боксов, а у пропущенного элемента
    бокса нет, и его ошибка растворялась в общем MAE.

    Элементы, покрывающие больше huge_frac кадра, из атрибуции исключаются:
    контейнер «содержит» любую ошибку, не сообщая, что чинить.
    """
    m = np.asarray(mask)
    h, w = m.shape
    total = int(m.sum())
    if total == 0:
        return [], 0.0
    covered = np.zeros_like(m)
    rows = []
    for el in elements:
        x0, y0, x1, y1 = el["box"]
        x0, y0 = max(int(x0), 0), max(int(y0), 0)
        x1, y1 = min(int(x1), w), min(int(y1), h)
        if x1 <= x0 or y1 <= y0:
            continue
        if (x1 - x0) * (y1 - y0) >= huge_frac * w * h:
            continue
        box = m[y0:y1, x0:x1]
        hits = int(box.sum())
        if hits == 0:
            continue
        covered[y0:y1, x0:x1] |= box
        rows.append({
            "name": el.get("name", "?"),
            "box": (x0, y0, x1, y1),
            "hits": hits,
            "own_pct": 100.0 * hits / box.size,
            "share_pct": 100.0 * hits / total,
        })
    rows.sort(key=lambda r: r["hits"], reverse=True)
    return rows, 1.0 - int(covered.sum()) / total


def hallucinated(mask, elements, orig_rgb, bg_rgb, bg_tol=14.0, min_bg_frac=0.92):
    """Элементы, которых в оригинале нет: их зона В ОРИГИНАЛЕ почти целиком
    фон, а различия там есть.

    Обратная сторона неотнесённой ошибки. Вместе они разделяют три случая,
    которые иначе сваливаются в один «MAE вырос»: нарисовал не так /
    не нарисовал / нарисовал лишнее.
    """
    m = np.asarray(mask)
    o = np.asarray(orig_rgb)
    h, w = m.shape
    bg = np.array(bg_rgb, dtype=np.int32)
    out = []
    for el in elements:
        x0, y0, x1, y1 = el["box"]
        x0, y0 = max(int(x0), 0), max(int(y0), 0)
        x1, y1 = min(int(x1), w), min(int(y1), h)
        if x1 <= x0 or y1 <= y0:
            continue
        crop = o[y0:y1, x0:x1].astype(np.int32)
        is_bg = np.abs(crop - bg).sum(axis=-1) <= bg_tol
        frac = float(is_bg.mean())
        hits = int(m[y0:y1, x0:x1].sum())
        if frac >= min_bg_frac and hits > 0:
            out.append({"name": el.get("name", "?"), "box": (x0, y0, x1, y1),
                        "bg_frac": frac, "hits": hits})
    out.sort(key=lambda r: r["hits"], reverse=True)
    return out


def element_color_delta(orig_rgb, render_rgb, box):
    """dE76 между доминирующими цветами зоны в оригинале и в рендере.

    Отвечает на «насколько промахнулся цвет плашки» одним числом, которое не
    размывается текстом внутри неё: берётся мода, а не среднее.
    """
    x0, y0, x1, y1 = [int(v) for v in box]
    a = np.asarray(orig_rgb)[y0:y1, x0:x1]
    b = np.asarray(render_rgb)[y0:y1, x0:x1]
    if a.size == 0 or b.size == 0:
        return None
    ca, _ = dominant_color(a)
    cb, _ = dominant_color(b)
    lab = srgb_to_lab(np.array([[ca], [cb]], dtype=np.uint8))
    return float(de76(lab[0, 0], lab[1, 0])), ca, cb


# ------------------ разделение текста и поверхностей ------------------
#
# Нужно для residual: ошибку формы глифов недоступного шрифта нельзя
# складывать с ошибкой геометрии — первая внешнее ограничение, вторую
# механизм обязан устранять. Прежняя версия residual.py знала текст из
# списка 24 зон с координатами конкретного скриншота; здесь текст
# определяется свойством, которое можно измерить на любом кадре.
def _erode(mask, r):
    """Бинарная эрозия квадратом (2r+1). Без scipy: сдвиги + AND."""
    if r <= 0:
        return mask.copy()
    m = np.asarray(mask)
    out = m.copy()
    h, w = m.shape
    for dy in range(-r, r + 1):
        for dx in range(-r, r + 1):
            if dx == 0 and dy == 0:
                continue
            sh = np.zeros_like(m)
            ys0, ys1 = max(0, dy), min(h, h + dy)
            xs0, xs1 = max(0, dx), min(w, w + dx)
            yd0, yd1 = max(0, -dy), min(h, h - dy)
            xd0, xd1 = max(0, -dx), min(w, w - dx)
            sh[yd0:yd1, xd0:xd1] = m[ys0:ys1, xs0:xs1]
            out &= sh
    return out


def _dilate(mask, r):
    """Бинарное расширение квадратом (2r+1)."""
    if r <= 0:
        return np.asarray(mask).copy()
    m = np.asarray(mask)
    out = m.copy()
    h, w = m.shape
    for dy in range(-r, r + 1):
        for dx in range(-r, r + 1):
            if dx == 0 and dy == 0:
                continue
            sh = np.zeros_like(m)
            ys0, ys1 = max(0, dy), min(h, h + dy)
            xs0, xs1 = max(0, dx), min(w, w + dx)
            yd0, yd1 = max(0, -dy), min(h, h - dy)
            xd0, xd1 = max(0, -dx), min(w, w - dx)
            sh[yd0:yd1, xd0:xd1] = m[ys0:ys1, xs0:xs1]
            out |= sh
    return out


def thin_structures(mask, max_stroke=3):
    """Часть маски, принадлежащая ТОНКИМ структурам (штрихи глифов), а не
    сплошным пятнам (иконки, спрайты, залитые плашки).

    Признак выбран измеримый и общий: глиф в UI — это штрих шириной 1..3px,
    поэтому эрозия радиусом max_stroke//2+1 его уничтожает целиком, а
    внутренность крупного пятна выживает. Пятно затем восстанавливается
    расширением и вычитается.

    Почему не «цвет ближе к ink чем к фону», как было раньше: для этого надо
    заранее знать цвет текста каждой зоны, то есть снова список, привязанный
    к одному скриншоту. Толщина штриха — свойство самого растра.
    """
    m = np.asarray(mask)
    r = max(1, int(max_stroke) // 2 + 1)
    blob_core = _erode(m, r)
    blobs = _dilate(blob_core, r) & m
    return m & ~blobs


def strip_border_lines(dif, band=4, frac=0.85):
    """Убрать из маски «отличается от поверхности» краевые линии рамки/bevel.

    ЗАЧЕМ (измерено, не предположение). Тонкая структура — необходимый, но не
    достаточный признак глифа: bevel-кромка кнопки это тоже штрих 1..3px, и
    без этого фильтра 29 из 67 боксов эталона отдавали в маску глифов
    преимущественно свой периметр. Тогда ошибка рамки записывалась бы в
    GLYPH-FORM, то есть в «внешнее ограничение, чинить нечего» — прямо
    противоположно правде.

    Разделяющий признак: у рамки краевая линия заполнена ЦЕЛИКОМ (>= frac
    ширины/высоты), потому что она обводит элемент по всему периметру. У
    текста в краевой полосе ни одна линия целиком не заполняется. Замер по
    эталону: поле ввода 377x34 — 10 полных линий, кнопка 137x35 — 11,
    текстовая метка 115x19 — 0, метка 109x18 — 0.
    """
    m = np.asarray(dif).copy()
    h, w = m.shape
    band = int(band)
    for y in list(range(min(band, h))) + list(range(max(0, h - band), h)):
        if m[y, :].mean() >= frac:
            m[y, :] = False
    for x in list(range(min(band, w))) + list(range(max(0, w - band), w)):
        if m[:, x].mean() >= frac:
            m[:, x] = False
    return m


def text_mask(orig_rgb, elements, tol=30, max_stroke=3, dilate=1,
              border_band=4, border_frac=0.85, rend_rgb=None):
    """Маска «пиксели формы глифов».

    Для каждого элемента: поверхность = доминирующий цвет его бокса, ink =
    всё, что отличается от неё сильнее tol (L1 по sRGB). Из ink убираются
    полные краевые линии (рамка/bevel), затем остаются только тонкие
    структуры. Затем ±dilate ВНУТРИ того же бокса — край antialias
    принадлежит форме буквы, а не геометрии; за пределами бокса расширения
    нет, иначе своей ошибкой можно было бы прикрыться.

    [rend_rgb] — ВТОРОЙ растр (рендер), опционально. ЗАЧЕМ, измерено на
    эталоне: маска только по оригиналу оставляла 31.0% различий в
    «геометрии», хотя это форма букв. Причина арифметическая — глиф чужого
    шрифта занимает ДРУГИЕ пиксели: там, где в оригинале фон, в рендере
    штрих. Такой пиксель в маске-по-оригиналу отсутствует и попадает в
    «чинится», то есть отчёт отправляет править вёрстку из-за недоступного
    шрифта. С обоими растрами доля падает 31.0% -> 10.8%.

    Это НЕ «маска из рендера»: зоны по-прежнему заданы боксами ОРИГИНАЛА, и
    внутри бокса берутся только тонкие структуры. Объявить текстом промах
    геометрии нельзя — сдвинутая рамка не тонкая структура (её отсекает
    strip_border_lines), а промах цвета поверхности не отличается от
    поверхности и в ink не попадает вовсе.

    ОГОВОРКА о честности названия: маска ловит тонкие структуры, а это не
    только глифы шрифта, но и штрихи растровых иконок (крестик закрытия).
    Для ассетов, вырезанных из оригинала, их вклад в ошибку ~0, поэтому на
    вердикт это не влияет; но если иконка нарисована CSS-ом, её ошибка
    попадёт в GLYPH-FORM. Признака, отделяющего штрих буквы от штриха иконки
    по одному растру, у меня нет — и выдумывать его вместо измерения хуже,
    чем сказать об ограничении.
    """
    out = _thin_mask_one(orig_rgb, elements, tol, max_stroke, dilate,
                         border_band, border_frac)
    if rend_rgb is not None:
        out |= _thin_mask_one(rend_rgb, elements, tol, max_stroke, dilate,
                              border_band, border_frac)
    return out


def _thin_mask_one(orig_rgb, elements, tol, max_stroke, dilate,
                   border_band, border_frac):
    """Тонкие структуры внутри боксов ОДНОГО растра. См. [text_mask]."""
    o = np.asarray(orig_rgb)
    h, w = o.shape[:2]
    out = np.zeros((h, w), dtype=bool)
    for el in elements:
        x0, y0, x1, y1 = [int(v) for v in el["box"]]
        x0, y0 = max(x0, 0), max(y0, 0)
        x1, y1 = min(x1, w), min(y1, h)
        if x1 - x0 < 2 or y1 - y0 < 2:
            continue
        crop = o[y0:y1, x0:x1]
        surface, _ = dominant_color(crop)
        sv = np.array(surface, dtype=np.int32)
        differs = np.abs(crop.astype(np.int32) - sv).sum(axis=2) > tol
        if not differs.any():
            continue
        inner = strip_border_lines(differs, border_band, border_frac)
        if not inner.any():
            continue
        thin = thin_structures(inner, max_stroke)
        if dilate > 0:
            thin = _dilate(thin, dilate) & inner
        out[y0:y1, x0:x1] |= thin
    return out
