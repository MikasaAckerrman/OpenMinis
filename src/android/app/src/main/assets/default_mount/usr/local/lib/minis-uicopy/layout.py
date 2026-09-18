#!/usr/bin/env python3
"""layout.py — проверка РАСПОЛОЖЕНИЯ элементов. 0 токенов, только пиксели.

ЗАЧЕМ ОТДЕЛЬНАЯ ПРОВЕРКА, ЕСЛИ ЕСТЬ ПИКСЕЛЬНЫЙ diff.

Пиксельная метрика отвечает на «насколько отличается», но не на «что не на
своём месте». Элемент, уехавший на 30px, даёт две зоны различий — там, где
его нет, и там, где он лишний, — и ни одно число не сообщает, что это ОДИН
элемент, просто не там. Хуже: MAE такого дефекта может быть меньше, чем у
безобидного промаха по оттенку крупной плашки, то есть ранжирование по MAE
уводит от настоящей ошибки.

ЧТО ДЕЛАЕТ ЭТОТ МОДУЛЬ. Детектирует компоненты в ОБОИХ растрах одним и тем
же способом, сопоставляет их между собой и выдаёт вердикты в терминах
вёрстки:

  СМЕЩЁН            элемент найден, но его центр уехал на (dx, dy)
  РАЗМЕР            найден, но габарит другой (dw, dh)
  ПРОПУЩЕН          есть в оригинале, в рендере нет ничего похожего
  ЛИШНИЙ            есть в рендере, в оригинале нет
  СЛИПЛИСЬ / РАЗБИТ один компонент против нескольких (потеря/появление зазора)
  ВЫРАВНИВАНИЕ      группа, стоявшая по одной линии, разъехалась
  РИТМ              равный шаг в ряду перестал быть равным
  ПОРЯДОК           два элемента поменялись местами

ПОЧЕМУ ЭТО ДЕШЕВО. Ни одного обращения к модели, ни одного скриншота на
просмотр: детекция компонент + сопоставление + арифметика по координатам.
Вход — те же два PNG, что и у diff.

ЧЕГО ЭТОТ МЕТОД НЕ МОЖЕТ (границы честно, а не «работает всегда»):
  * Компоненты — это связные области «не фон». Два элемента, соприкасающиеся
    хотя бы одним пикселем, склеиваются в один. Поэтому вердикты СЛИПЛИСЬ /
    РАЗБИТ существуют как отдельный класс, а не маскируются под смещение.
  * Оригинал — растр, DOM у него отсутствует. «Элемент» здесь = пятно
    пикселей, а не узел дерева; имён вроде `#cta` быть не может.
  * Текстовые компоненты меняют ink-bbox на ±2px при смене шрифта законно.
    Поэтому смещения печатаются с числами и с пометкой thin, а не режутся
    порогом молча.

Заимствования: идея «считать НЕсопоставленное в две стороны» — из метрики
Block-Match (NoviScl/Design2Code); идея объективного сигнала вместо
впечатления — ui-agent-loop (MIT, (c) 2026 Marvin Seiferling) и pixelpilot
(MIT, (c) 2026 Manav Patel). Сопоставление, направляющие и ритм — своё:
у аналогов сравниваются два DOM, а здесь второго DOM нет.
"""
import numpy as np

# Порог L1 по sRGB для «пиксель отличается от фона». Тот же, что в boxes.py:
# оба модуля обязаны видеть одни и те же компоненты, иначе сопоставление
# будет спорить с поэлементным diff.
BG_TOL = 14

# Мельче этого — шум JPEG/antialias, не элемент. Тоже как в boxes.py.
MIN_AREA = 40

# Смещение центра, ниже которого не сообщаем. 2px: детектор строит ink-bbox,
# и при другом хинтинге шрифта край глифа законно гуляет на пиксель в каждую
# сторону. Всё, что выше, — уже не шум измерения.
SHIFT_TOL = 2

# Разница габарита, ниже которой не сообщаем. Те же ±2px по той же причине.
SIZE_TOL = 2

# Толщина, до которой компонент считается «тонким» (текст/штрих). Для таких
# ink-bbox нестабилен по природе, и вердикт помечается, чтобы промах шрифта
# не читался как промах вёрстки.
THIN_PX = 4

# Минимальная площадь и минимальная сторона, при которых компонент считается
# САМОСТОЯТЕЛЬНЫМ элементом вёрстки, а не фрагментом (глифом, засечкой,
# куском штриха).
#
# ЗАЧЕМ (измерено на эталоне, а не выбрано на глаз). Дальний поиск пары
# осмыслен для элемента: кнопка 51x31, переехавшая на 400px, — реальный
# дефект, и сказать «переехала» полезнее, чем «пропала и появилась похожая».
# Для глифа 12x11 то же правило дало 24 записи вида «уехал на +273,-201»:
# отдельная буква не переезжает по экрану, это артефакт сопоставления, а он
# хоронит настоящие дефекты в шуме. 132 px² глифа против 1581 px² кнопки —
# разрыв на порядок, поэтому порог 400 не подгонка под один кадр.
STANDALONE_AREA = 400
STANDALONE_SIDE = 8

# Максимум, на сколько бокс расширяется при замере сдвига ЧЕРНИЛ. Глиф,
# вышедший за границу исходного компонента, должен попасть в окно; чужой
# элемент — нет. 14px: строчная буква эталона 10-12px в поперечнике, то есть
# запаса хватает на глиф целиком, а типичный зазор между соседними элементами
# вёрстки здесь 15px и больше, и половина зазора (см. free_pad) не даёт окнам
# соседей пересечься даже при вплотную стоящих элементах.
NEIGHBOUR_PAD_CAP = 14


# --------------------------- детекция ---------------------------

def label_components(mask):
    """Маркировка связных областей (4-связность) -> (labels, count).

    Использует scipy.ndimage, если он есть: на кадре 1097x654 питоновский
    flood fill измеримо медленнее, а результат обязан быть тем же. Свой путь
    оставлен не «на всякий случай», а потому что ensure_env инструмента ставит
    только Pillow — без fallback модуль падал бы на чистом окружении.
    Совпадение обоих путей зафиксировано в selftest.
    """
    m = np.asarray(mask, dtype=bool)
    try:
        from scipy import ndimage
        structure = np.array([[0, 1, 0], [1, 1, 1], [0, 1, 0]], dtype=bool)
        labels, n = ndimage.label(m, structure=structure)
        return labels, int(n)
    except Exception:
        pass

    h, w = m.shape
    labels = np.zeros((h, w), dtype=np.int32)
    cur = 0
    for sy in range(h):
        for sx in range(w):
            if not m[sy, sx] or labels[sy, sx]:
                continue
            cur += 1
            stack = [(sx, sy)]
            labels[sy, sx] = cur
            while stack:
                x, y = stack.pop()
                for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
                    if 0 <= nx < w and 0 <= ny < h and m[ny, nx] and not labels[ny, nx]:
                        labels[ny, nx] = cur
                        stack.append((nx, ny))
    return labels, cur


def detect(arr, bg=None, tol=BG_TOL, min_area=MIN_AREA, max_frac=0.5):
    """Компоненты «не фон» в растре -> список dict.

    [bg] можно передать снаружи, и для рендера это ОБЯЗАТЕЛЬНО делать, передав
    фон ОРИГИНАЛА. Иначе так: рендер, у которого фон промахнулся, измерит
    собственный неверный фон как эталонный, вычтет его и покажет ровно те же
    компоненты — то есть промах фона станет невидимым для проверки. Фон должен
    приходить из оригинала, потому что оригинал и есть истина.

    [max_frac] отбрасывает компоненты крупнее половины кадра: это рамка
    диалога, внутри которой лежит всё остальное. Оставленная, она
    сопоставлялась бы «сама с собой» и своим размером маскировала бы
    смещения внутри.
    """
    a = np.asarray(arr)
    h, w = a.shape[:2]
    if bg is None:
        from metrics import dominant_color
        bg, _ = dominant_color(a)
    bgv = np.array(bg, dtype=np.int32)
    mask = np.abs(a.astype(np.int32) - bgv).sum(axis=2) > tol

    labels, n = label_components(mask)
    out = []
    frame_area = max_frac * w * h
    for lab in range(1, n + 1):
        ys, xs = np.nonzero(labels == lab)
        if ys.size < min_area:
            continue
        x0, x1 = int(xs.min()), int(xs.max()) + 1
        y0, y1 = int(ys.min()), int(ys.max()) + 1
        bw, bh = x1 - x0, y1 - y0
        if bw * bh >= frame_area:
            continue
        crop = a[y0:y1, x0:x1]
        from metrics import dominant_color
        dom, dom_frac = dominant_color(crop)
        out.append({
            "box": (x0, y0, x1, y1),
            "w": bw, "h": bh,
            "cx": (x0 + x1) / 2.0, "cy": (y0 + y1) / 2.0,
            "pixels": int(ys.size),
            "fill": ys.size / float(bw * bh),
            "dom": tuple(int(c) for c in dom),
            "dom_frac": float(dom_frac),
            "thin": min(bw, bh) <= THIN_PX,
            # самостоятельный элемент вёрстки, а не фрагмент глифа —
            # разрешает дальний поиск пары, см. STANDALONE_AREA
            "standalone": (bw * bh >= STANDALONE_AREA
                           and min(bw, bh) >= STANDALONE_SIDE),
        })
    out.sort(key=lambda c: (c["box"][1], c["box"][0]))
    for i, c in enumerate(out, 1):
        c["name"] = "e%02d_%dx%d@%d,%d" % (i, c["w"], c["h"], c["box"][0], c["box"][1])
    return out


# --------------------------- сопоставление ---------------------------

def iou(a, b):
    """Пересечение над объединением двух боксов."""
    ax0, ay0, ax1, ay1 = a
    bx0, by0, bx1, by1 = b
    ix0, iy0 = max(ax0, bx0), max(ay0, by0)
    ix1, iy1 = min(ax1, bx1), min(ay1, by1)
    if ix1 <= ix0 or iy1 <= iy0:
        return 0.0
    inter = (ix1 - ix0) * (iy1 - iy0)
    union = (ax1 - ax0) * (ay1 - ay0) + (bx1 - bx0) * (by1 - by0) - inter
    return inter / float(union) if union else 0.0


def _shape_cost(a, b):
    """Стоимость «это не тот же элемент» по форме и цвету, БЕЗ учёта позиции.

    Нужна отдельно от IoU: элемент, уехавший дальше своего размера, имеет
    IoU = 0 со своим оригиналом, и по одному IoU он выглядел бы как
    «пропущен + лишний» — то есть как две ошибки вместо одной, и без числа
    смещения. Позиция здесь намеренно не участвует, иначе именно тот дефект,
    который мы ищем, увеличивал бы стоимость и мешал найти пару.
    """
    dw = abs(a["w"] - b["w"]) / float(max(a["w"], b["w"]))
    dh = abs(a["h"] - b["h"]) / float(max(a["h"], b["h"]))
    dfill = abs(a["fill"] - b["fill"])
    dcol = sum(abs(int(x) - int(y)) for x, y in zip(a["dom"], b["dom"])) / 765.0
    return 2.0 * (dw + dh) + 0.5 * dfill + 1.0 * dcol


def _identity_cost(a, b):
    """Стоимость «это разные элементы» по ЦВЕТУ и заполненности, БЕЗ размера.

    Отдельно от [_shape_cost], и это принципиально. Competition-гейт фазы 1
    существует, чтобы отличить перестановку соседей от смещения: там ошибка в
    ИДЕНТИЧНОСТИ элемента. Идентичность несёт цвет, а не габарит — габарит это
    ровно та величина, которую мы измеряем как дефект.

    Замер, который к этому привёл: гейт на полной стоимости отвергал верную
    пару треугольника dropdown, сузившегося на 4px (cost 0.556 из-за
    dw = 4/15), в пользу «более похожего» второго треугольника на 75px выше
    (cost 0.024). Подсаженный дефект не находился вовсе. При этом на
    перестановке синего и красного соседей цвет расходится на dcol 0.418 —
    то есть по цвету перестановка видна так же ясно, как раньше.
    """
    dfill = abs(a["fill"] - b["fill"])
    dcol = sum(abs(int(x) - int(y)) for x, y in zip(a["dom"], b["dom"])) / 765.0
    return 0.5 * dfill + 1.0 * dcol


def match(orig, rend, iou_min=0.30, shape_max=0.55, search_frac=0.35,
          swap_cost=0.25, ambig_margin=0.10, frag_span=3.0, frag_floor=30.0):
    """Сопоставить компоненты оригинала и рендера.

    Две фазы, и порядок принципиален.

    ФАЗА 1 — по IoU: уверенные пары «элемент примерно там же». Жадно по
    убыванию IoU, поэтому два соседних одинаковых элемента не могут
    перехватить друг друга.

    Но одного IoU НЕДОСТАТОЧНО, и это измерено: у перестановки двух соседей
    одинакового габарита IoU(синий@A, красный@A) = 1.0, то есть геометрия
    заявляет идеальную пару для РАЗНЫХ элементов, а dE76 между их цветами при
    этом 107. Приняв такую пару, мы получаем два встречных «смещения» вместо
    одного вердикта ПОРЯДОК, то есть теряем причину. Поэтому кандидат фазы 1
    отвергается, если у этого элемента оригинала есть заметно более похожий
    по внешнему виду компонент рендера (competition-гейт, [swap_cost] —
    минимальный отрыв). Гейт относительный, а не абсолютный порог по цвету:
    крупный, но настоящий промах цвета должен оставаться СМЕЩЕНИЕМ с
    измеренным dE, а не превращаться в ПРОПУЩЕН+ЛИШНИЙ — цвет и так
    сообщается поэлементно в diff.

    ФАЗА 2 — остаток по подписи формы и цвета, с ограничением дальности.
    Дальность считается ОТ РАЗМЕРА КОМПОНЕНТА, а не от диагонали кадра:

      самостоятельный элемент  ->  search_frac * диагональ кадра
      фрагмент (глиф, засечка) ->  max(frag_span * своя сторона, frag_floor)

    Замер, который к этому привёл: на кадре 1097x654 доля диагонали давала
    лимит 447px, и глиф 14x11 «переезжал» на 339px, проходя проверку. Таких
    записей набралось 22 и они хоронили настоящие дефекты. Буква не
    перемещается по экрану сама — её положение задаёт строка, а строка
    проверяется как группа (см. group_match). Кнопка же, уехавшая на 400px, —
    настоящий дефект, и для неё дальний поиск сохранён.

    Возвращает (pairs, missing, spurious), где pairs — список
    (orig_idx, rend_idx, how): how = "iou" | "shape".
    """
    if not orig or not rend:
        return [], list(range(len(orig))), list(range(len(rend)))

    # матрица стоимости внешнего вида нужна обеим фазам
    cost = [[_shape_cost(a, b) for b in rend] for a in orig]
    best_app = [min(row) for row in cost]
    # Матрица ИДЕНТИЧНОСТИ (цвет + заполненность, без размера) — опора
    # competition-гейта фазы 1. Отдельно от [cost]: гейт решает «тот ли это
    # элемент», а не «насколько он изменился», и размер в этом решении
    # участвовать не должен — он и есть измеряемый дефект.
    ident = [[_identity_cost(a, b) for b in rend] for a in orig]
    ident_best = [min(row) for row in ident]

    cands = []
    for i, a in enumerate(orig):
        for j, b in enumerate(rend):
            v = iou(a["box"], b["box"])
            if v < iou_min:
                continue
            # competition-гейт: есть ли у i заметно более похожий кандидат
            # СРЕДИ ТЕХ, ЧТО ПЕРЕКРЫВАЮТСЯ С НИМ. Гейт создан против
            # перестановки соседей, а сосед по определению рядом; сравнение по
            # всему кадру давало обратный эффект — замер: у второго треугольника
            # dropdown (тот же элемент 15x7 на 75px выше) shape_cost 0.024, и он
            # выигрывал у настоящей пары с cost 0.556, из-за чего подсаженное
            # сужение треугольника на 4px не находилось вовсе.
            if ident[i][j] > ident_best[i] + swap_cost:
                continue
            cands.append((v, i, j))
    cands.sort(reverse=True)

    used_o, used_r = set(), set()
    pairs = []
    for v, i, j in cands:
        if i in used_o or j in used_r:
            continue
        used_o.add(i)
        used_r.add(j)
        pairs.append((i, j, "iou"))

    # фаза 2: остаток по подписи формы
    rest_o = [i for i in range(len(orig)) if i not in used_o]
    rest_r = [j for j in range(len(rend)) if j not in used_r]
    if rest_o and rest_r:
        h = max(max(c["box"][3] for c in orig), max(c["box"][3] for c in rend))
        w = max(max(c["box"][2] for c in orig), max(c["box"][2] for c in rend))
        diag = (w * w + h * h) ** 0.5
        sc = []
        for i in rest_o:
            # Уникальность считается по ВСЕМУ кадру, а не по остатку после
            # фазы 1. Иначе она становится артефактом порядка сопоставления:
            # из трёх одинаковых квадратов два забирает IoU, третий остаётся
            # единственным в остатке и выглядит «уникальным», хотя по внешнему
            # виду он неотличим от уже сопоставленных — то есть ровно тот
            # случай, где решать должно расстояние.
            all_viable = sorted(c for c in cost[i] if c <= shape_max)
            unique = len(all_viable) == 1 or (
                len(all_viable) > 1 and all_viable[1] - all_viable[0] > ambig_margin)
            a = orig[i]
            if a.get("standalone"):
                limit = search_frac * diag
            else:
                limit = max(frag_span * max(a["w"], a["h"]), frag_floor)
            viable = sorted((cost[i][j], j) for j in rest_r if cost[i][j] <= shape_max)
            for c, j in viable:
                b = rend[j]
                dist = ((a["cx"] - b["cx"]) ** 2 + (a["cy"] - b["cy"]) ** 2) ** 0.5
                # Дальность — вето, кроме случая «уникальная форма И оба
                # компонента самостоятельные». Требование standalone добавлено
                # по замеру: без него отдельные глифы сопоставлялись через
                # пол-экрана и давали ложные «смещения».
                far_ok = unique and a.get("standalone") and b.get("standalone")
                if dist > limit and not far_ok:
                    continue
                # расстояние входит в тай-брейк, но не в порог: иначе величина
                # искомого дефекта решала бы, найдём ли мы пару вообще
                sc.append((c + 0.0005 * dist, i, j))
        sc.sort()
        for c, i, j in sc:
            if i in used_o or j in used_r:
                continue
            used_o.add(i)
            used_r.add(j)
            pairs.append((i, j, "shape"))

    missing = [i for i in range(len(orig)) if i not in used_o]
    spurious = [j for j in range(len(rend)) if j not in used_r]
    pairs.sort(key=lambda p: (orig[p[0]]["box"][1], orig[p[0]]["box"][0]))
    return pairs, missing, spurious


def containment(inner, outer):
    """Доля площади [inner], попавшая внутрь [outer]. 1.0 = вложен целиком.

    Нужна там, где IoU принципиально не работает: глиф 12x14 внутри строки
    115x19 даёт IoU 0.077 (площади различаются в 13 раз), то есть по IoU
    «не связаны», хотя буква физически лежит внутри строки. Измерено на
    эталоне: именно этот порог превращал одну фрагментированную строку в
    «1 пропуск + 8 лишних элементов».
    """
    ix0, iy0, ix1, iy1 = inner
    ox0, oy0, ox1, oy1 = outer
    x0, y0 = max(ix0, ox0), max(iy0, oy0)
    x1, y1 = min(ix1, ox1), min(iy1, oy1)
    if x1 <= x0 or y1 <= y0:
        return 0.0
    inter = (x1 - x0) * (y1 - y0)
    area = (ix1 - ix0) * (iy1 - iy0)
    return inter / float(area) if area else 0.0


def union_box(boxes):
    """Объединяющий бокс списка боксов."""
    x0 = min(b[0] for b in boxes)
    y0 = min(b[1] for b in boxes)
    x1 = max(b[2] for b in boxes)
    y1 = max(b[3] for b in boxes)
    return (x0, y0, x1, y1)


def group_match(orig, rend, missing, spurious, min_cont=0.60,
                side_tol=4, frac_tol=0.25):
    """Сопоставить ОДИН компонент с ГРУППОЙ компонентов другой стороны.

    ЗАЧЕМ. Связность зависит от рендерера, а не только от вёрстки. Замер на
    эталоне: строка «Настройки» в оригинале (битмап-шрифт, глифы
    соприкасаются) — один компонент 115x19; в рендере (TrueType, чистые
    зазоры) — восемь компонентов по ~10x12. Расположение при этом верное:
    объединение глифов 53..166 против 53..168 в оригинале. Без этой фазы такой
    случай печатался как «1 пропущен + 8 лишних», и 187 подобных записей
    хоронили настоящие дефекты.

    Возвращает (frag, merg):
      frag — [(orig_idx, [rend_idx...], union_box)]  один -> много
      merg — [(rend_idx, [orig_idx...], union_box)]  много -> один

    ИДЕНТИЧНОСТЬ ГРУППЫ проверяется по РАЗМЕРУ объединения, а не по его
    положению. Прежняя версия требовала, чтобы объединение лежало внутри
    исходного бокса с допуском 3px, и это была ошибка того же класса, от
    которой защищён `match`: величина искомого дефекта решала, найдём ли мы
    пару. Замер: три текстовых блока эталона смещены на 4px влево — guard
    отверг их, и настоящее смещение вышло в отчёт как «3 пропущено + 9
    лишних». Теперь смещение группы ИЗМЕРЯЕТСЯ и сообщается.

    Положение при этом не бесконтрольно: каждый член группы обязан лежать
    внутри исходного бокса минимум на [min_cont] своей площади, а это само
    ограничивает, насколько далеко группа может уехать.
    """
    def size_ok(u, box):
        uw, uh = u[2] - u[0], u[3] - u[1]
        bw, bh = box[2] - box[0], box[3] - box[1]
        return (abs(uw - bw) <= max(side_tol, frac_tol * bw)
                and abs(uh - bh) <= max(side_tol, frac_tol * bh))

    frag, merg = [], []
    used_r, used_o = set(), set()

    for i in missing:
        ob = orig[i]["box"]
        grp = [j for j in spurious
               if j not in used_r and containment(rend[j]["box"], ob) >= min_cont]
        if len(grp) < 2:
            continue
        u = union_box([rend[j]["box"] for j in grp])
        if not size_ok(u, ob):
            continue
        used_r.update(grp)
        frag.append((i, grp, u))

    for j in spurious:
        if j in used_r:
            continue
        rb = rend[j]["box"]
        grp = [i for i in missing
               if i not in used_o and i not in [f[0] for f in frag]
               and containment(orig[i]["box"], rb) >= min_cont]
        if len(grp) < 2:
            continue
        u = union_box([orig[i]["box"] for i in grp])
        if not size_ok(u, rb):
            continue
        used_o.update(grp)
        merg.append((j, grp, u))

    return frag, merg


def overlap_groups(orig, rend, missing, spurious, min_iou=0.10):
    """Найти слипания и разбиения среди НЕсопоставленного.

    Один компонент оригинала, перекрытый несколькими компонентами рендера, —
    это потерянный зазор (в оригинале элементы соприкасались, в рендере нет)
    или наоборот. Без этого класса такой дефект разложился бы на ПРОПУЩЕН +
    несколько ЛИШНИХ, и причина «изменился зазор» не читалась бы.
    """
    merged, split = [], []
    for i in missing:
        hits = [j for j in range(len(rend))
                if iou(orig[i]["box"], rend[j]["box"]) >= min_iou]
        if len(hits) >= 2:
            split.append((i, hits))
    for j in spurious:
        hits = [i for i in range(len(orig))
                if iou(orig[i]["box"], rend[j]["box"]) >= min_iou]
        if len(hits) >= 2:
            merged.append((j, hits))
    return split, merged


# --------------------------- направляющие ---------------------------

def guides(comps, edge, tol=2, min_members=3):
    """Линии выравнивания: значения координаты [edge], общие для >= N элементов.

    edge: "left" | "right" | "top" | "bottom" | "cx" | "cy".

    Зачем именно это. Человек видит не абсолютные координаты, а строй: колонка
    подписей по одному левому краю, ряд кнопок по одному низу. Элемент,
    выпавший из строя на 3px, заметен глазом сразу, но его вклад в MAE мал и в
    ранжировании он утонет. Направляющая — самая дешёвая формализация «строя»:
    она не требует ни DOM, ни семантики.
    """
    key = {
        "left": lambda c: c["box"][0],
        "right": lambda c: c["box"][2],
        "top": lambda c: c["box"][1],
        "bottom": lambda c: c["box"][3],
        "cx": lambda c: c["cx"],
        "cy": lambda c: c["cy"],
    }[edge]
    vals = sorted((key(c), idx) for idx, c in enumerate(comps))
    out = []
    i = 0
    while i < len(vals):
        j = i + 1
        members = [vals[i][1]]
        while j < len(vals) and vals[j][0] - vals[i][0] <= tol:
            members.append(vals[j][1])
            j += 1
        if len(members) >= min_members:
            pos = float(np.mean([key(comps[m]) for m in members]))
            out.append({"edge": edge, "pos": pos, "members": members})
        i = j
    return out


def guide_violations(orig, rend, pairs, tol=2, min_members=3, slack=1.0,
                     eff=None):
    """Какие направляющие оригинала развалились в рендере.

    Для каждой линии оригинала берём её членов, у которых есть пара, и
    смотрим разброс той же координаты в рендере. Если разброс вырос выше
    tol + slack — строй нарушен, и сообщаем, КТО именно выбился.

    Проверяется разброс, а не абсолютное положение: если ряд целиком сместился
    на 5px, строй сохранён — это одно смещение блока, а не поломка
    выравнивания, и смешивать эти два дефекта нельзя.
    """
    o2r = {i: j for i, j, _ in pairs}
    key = {
        "left": lambda c: c["box"][0],
        "right": lambda c: c["box"][2],
        "top": lambda c: c["box"][1],
        "bottom": lambda c: c["box"][3],
        "cx": lambda c: c["cx"],
        "cy": lambda c: c["cy"],
    }
    eff = eff or {}

    def rend_of(m):
        """Где элемент фактически лежит в рендере.

        Из [eff] — бокс оригинала, сдвинутый по измеренным чернилам. Бокс
        компонента рендера используется только если чернила измерить не
        удалось: его край задаёт разбиение рендерера, а не вёрстка, и на
        эталоне это давало ложные вердикты (-4px у элементов, чернила которых
        совпадают пиксель в пиксель).
        """
        return eff.get(m) or rend[o2r[m]]

    def is_glyph(m):
        """См. [is_glyph_fragment] — единственное определение «это глиф»."""
        return is_glyph_fragment(orig[m], orig)

    out = []
    for edge in ("left", "right", "top", "bottom"):
        k = key[edge]
        for g in guides(orig, edge, tol=tol, min_members=min_members):
            mem = [m for m in g["members"] if m in o2r]
            if len(mem) < min_members:
                continue
            rv = [k(rend_of(m)) for m in mem]
            spread = max(rv) - min(rv)
            if spread <= tol + slack:
                continue
            med = float(np.median(rv))
            worst = sorted(
                ((abs(k(rend_of(m)) - med), m) for m in mem), reverse=True)
            offenders = [(orig[m]["name"], float(k(rend_of(m)) - med))
                         for d, m in worst[:3] if d > tol and not is_glyph(m)]
            # Если все «виновные» — отдельные глифы, вердикта нет: приписывать
            # поломку строя ширине буквы недоступного шрифта значит отправлять
            # править вёрстку из-за шрифта.
            if not offenders:
                continue
            out.append({
                "edge": edge,
                "pos": g["pos"],
                "n": len(mem),
                "spread": float(spread),
                "offenders": offenders,
            })
    out.sort(key=lambda v: -v["spread"])
    return out


def rhythm_violations(orig, rend, pairs, axis="x", tol=2, min_run=3,
                      align_tol=3, eff=None):
    """Ряды с равным шагом: что стало с шагом в рендере.

    Ряд = >= min_run элементов, выровненных по поперечной оси (для axis="x" —
    по вертикали) и стоящих с постоянным шагом в оригинале.

    Различаются ДВА разных дефекта, и это главное здесь:

      "rhythm" — шаг перестал быть постоянным: один зазор разъехался, а
                 остальные нет. Лечится правкой одного места.
      "gap"    — шаг остался постоянным, но другой (90 -> 100 у всех).
                 Лечится одним свойством контейнера (gap/margin).

    Прежняя версия объявляла нарушением ритма любое отклонение шага, поэтому
    пропорционально растянутый ряд давал N-1 «нарушений» подряд — то есть
    один дефект контейнера выглядел как несколько независимых поломок, и
    отчёт вёл чинить не туда.
    """
    o2r = {i: j for i, j, _ in pairs}
    lead = (lambda c: c["cx"]) if axis == "x" else (lambda c: c["cy"])
    cross = (lambda c: c["cy"]) if axis == "x" else (lambda c: c["cx"])

    idxs = [i for i in range(len(orig)) if i in o2r]
    idxs.sort(key=lambda i: (round(cross(orig[i]) / max(1, align_tol)), lead(orig[i])))

    out = []
    run = []
    for i in idxs:
        if run and abs(cross(orig[i]) - cross(orig[run[-1]])) <= align_tol:
            run.append(i)
            continue
        if len(run) >= min_run:
            out.extend(_check_run(orig, rend, o2r, run, lead, tol, axis, eff))
        run = [i]
    if len(run) >= min_run:
        out.extend(_check_run(orig, rend, o2r, run, lead, tol, axis, eff))
    out.sort(key=lambda v: -v["delta"])
    return out


def _check_run(orig, rend, o2r, run, lead, tol, axis, eff=None):
    eff = eff or {}
    ov = [lead(orig[i]) for i in run]
    # положение в рендере — из eff (чернила), см. effective_boxes
    rv = [lead(eff.get(i) or rend[o2r[i]]) for i in run]
    og = np.diff(ov)
    rg = np.diff(rv)
    if og.size == 0:
        return []
    # ряд считается ритмичным в оригинале, только если шаги там ровные;
    # иначе проверять нечего и «нарушение» было бы выдумкой
    if float(og.max() - og.min()) > tol:
        return []

    # шаг в рендере тоже ровный => это не поломка ритма, а другой шаг
    if float(rg.max() - rg.min()) <= tol:
        d = abs(float(np.mean(rg) - np.mean(og)))
        if d <= tol:
            return []
        return [{
            "kind": "gap",
            "axis": axis,
            "between": (orig[run[0]]["name"], orig[run[-1]]["name"]),
            "orig_gap": float(np.mean(og)),
            "rend_gap": float(np.mean(rg)),
            "delta": d,
            "n": len(run),
        }]

    out = []
    for k in range(og.size):
        d = abs(float(rg[k] - og[k]))
        if d > tol:
            out.append({
                "kind": "rhythm",
                "axis": axis,
                "between": (orig[run[k]]["name"], orig[run[k + 1]]["name"]),
                "orig_gap": float(og[k]),
                "rend_gap": float(rg[k]),
                "delta": d,
            })
    return out


def order_violations(orig, rend, pairs, axis="x", align_tol=3):
    """Пары, поменявшиеся местами вдоль оси.

    Отдельный класс, потому что перестановка двух соседей даёт два смещения
    навстречу друг другу — по отдельности они читаются как «оба чуть уехали»,
    и настоящая причина (перепутан порядок) не видна.
    """
    o2r = {i: j for i, j, _ in pairs}
    lead = (lambda c: c["cx"]) if axis == "x" else (lambda c: c["cy"])
    cross = (lambda c: c["cy"]) if axis == "x" else (lambda c: c["cx"])
    idxs = sorted((i for i in range(len(orig)) if i in o2r), key=lambda i: lead(orig[i]))
    out = []
    for a in range(len(idxs)):
        for b in range(a + 1, len(idxs)):
            i, j = idxs[a], idxs[b]
            if abs(cross(orig[i]) - cross(orig[j])) > align_tol:
                continue
            if lead(rend[o2r[i]]) > lead(rend[o2r[j]]):
                out.append((orig[i]["name"], orig[j]["name"],
                            float(lead(rend[o2r[i]]) - lead(rend[o2r[j]]))))
    return out


def ink_bbox(arr, box, bg, tol=BG_TOL):
    """Границы «не фона» внутри бокса, или None если пусто.

    Отличие от [ink_fraction]: та отвечает «есть ли тут что-нибудь», эта — «где
    именно оно лежит». Нужна, чтобы вердикт СМЕЩЁН проверялся по чернилам, а не
    по границам компонента: границы задаёт связность рендерера, а она у
    битмапного и TrueType-шрифта разная.
    """
    x0, y0, x1, y1 = [int(v) for v in box]
    a = np.asarray(arr)
    h, w = a.shape[:2]
    x0, y0 = max(x0, 0), max(y0, 0)
    x1, y1 = min(x1, w), min(y1, h)
    if x1 <= x0 or y1 <= y0:
        return None
    crop = a[y0:y1, x0:x1].astype(np.int32)
    m = np.abs(crop - np.array(bg, dtype=np.int32)).sum(axis=2) > tol
    if not m.any():
        return None
    ys, xs = np.where(m)
    return (x0 + int(xs.min()), y0 + int(ys.min()),
            x0 + int(xs.max()) + 1, y0 + int(ys.max()) + 1)


def free_pad(box, comps, cap=NEIGHBOUR_PAD_CAP):
    """Насколько можно расширить бокс, не дойдя до соседнего элемента.

    Половина зазора до ближайшего соседа, но не больше [cap]. Половина — чтобы
    окна двух соседей не пересекались: иначе чернила соседа попадут в оба и
    сдвиг одного «объяснится» другим.

    Зазор ищется БЕЗ верхней границы, и только результат ограничивается [cap].
    Первая версия инициализировала поиск значением cap и делила на два уже его,
    отчего фактический предел был 7 вместо 14 — то есть константа в шапке файла
    описывала не то, что делает код. Поймано собственным тестом.
    """
    x0, y0, x1, y1 = box
    d = None
    for c in comps:
        b = c["box"]
        if b == box:
            continue
        gaps = []
        if min(y1, b[3]) > max(y0, b[1]):
            if b[0] >= x1:
                gaps.append(b[0] - x1)
            if b[2] <= x0:
                gaps.append(x0 - b[2])
        if min(x1, b[2]) > max(x0, b[0]):
            if b[1] >= y1:
                gaps.append(b[1] - y1)
            if b[3] <= y0:
                gaps.append(y0 - b[3])
        for g in gaps:
            d = g if d is None else min(d, g)
    if d is None:
        return cap
    return max(0, min(cap, int(d) // 2))


def ink_shift(orig_arr, rend_arr, box, comps, bg, clip=None, tol=BG_TOL):
    """Сдвиг ЧЕРНИЛ в окрестности бокса: (dx, dy) или None если мерить нечем.

    ЗАЧЕМ, измерено на эталоне. Вердикт СМЕЩЁН считался по центрам
    сопоставленных компонентов, и 11 из 13 вердиктов были ложными: `dx=+31`
    при том, что чернила в этом месте стоят на месте с точностью до пикселя.
    Причина не в сопоставлении, а в том, что центр компонента — не то же, что
    положение элемента: битмапный шрифт оригинала склеивает строку в одно
    пятно, TrueType рендера рвёт её на глифы, и «центр» первого глифа лежит
    совсем не там, где центр строки. Сопоставление при этом верное — врёт
    измеряемая величина.

    Окно = бокс плюс [free_pad], чтобы поймать глифы, вышедшие за исходные
    границы, но не задеть соседний элемент.
    """
    d = ink_edge_deltas(orig_arr, rend_arr, box, comps, bg, clip=clip, tol=tol)
    if d is None:
        return None
    return ((d[0] + d[2]) / 2.0, (d[1] + d[3]) / 2.0)


def ref_color(comp, bg):
    """Опорный цвет для замера чернил: поверхность родителя или фон кадра.

    Вложенный объект лежит НЕ на фоне кадра, а на поверхности своего элемента.
    Меряя его относительно фона кадра, «чернилами» становится вся площадь
    родителя, и любое смещение внутри него читается как ноль — именно так
    треугольник dropdown, который на 3px шире, давал deltas (0,0,0,0).
    """
    return comp.get("surf", bg) if isinstance(comp, dict) else bg


def ink_edge_deltas(orig_arr, rend_arr, box, comps, bg, clip=None,
                    tol=BG_TOL):
    """Сдвиг КАЖДОЙ стороны ink-бокса: (dleft, dtop, dright, dbottom).

    Сторона, а не центр, потому что вердикты о выравнивании и ритме говорят
    именно про край: «шесть элементов стояли по left=85». Центр там не
    подходит — ряд элементов разной ширины имеет разные центры при идеальном
    строе.
    """
    p = free_pad(box, comps)
    win = [box[0] - p, box[1] - p, box[2] + p, box[3] + p]
    if clip is not None:
        win[0] = max(win[0], clip[0])
        win[1] = max(win[1], clip[1])
        win[2] = min(win[2], clip[2])
        win[3] = min(win[3], clip[3])
    win = tuple(win)
    bo = ink_bbox(orig_arr, win, bg, tol=tol)
    br = ink_bbox(rend_arr, win, bg, tol=tol)
    if bo is None or br is None:
        return None
    return tuple(int(br[k] - bo[k]) for k in range(4))


def effective_boxes(orig_comps, pairs, orig_arr, rend_arr, bg):
    """Где в РЕНДЕРЕ фактически лежит каждый сопоставленный элемент.

    Бокс оригинала, сдвинутый по сторонам на измеренные по чернилам смещения.
    Это единственное место, где «положение элемента в рендере» определяется, и
    все вердикты о геометрии (сдвиг, строй, ритм) читают отсюда — иначе каждый
    из них заново решал бы, что считать границей элемента, и они разошлись бы.

    ЗАЧЕМ не боксы компонентов рендера: на эталоне вердикт ВЫРАВНИВАНИЕ винил
    `e21` и `e42` в отклонении на -4px по правому краю, тогда как их чернила
    совпадают с оригиналом ПИКСЕЛЬ В ПИКСЕЛЬ. Разница была в границе
    компонента: рендер разбил кнопку иначе, и её «правый край» как компонента
    оказался левее. Тот же корень, что у ложных СМЕЩЕНО.
    """
    if orig_arr is None or rend_arr is None or bg is None:
        return {}
    out = {}
    for i, _j, _how in pairs:
        ob = orig_comps[i]["box"]
        d = ink_edge_deltas(orig_arr, rend_arr, ob, orig_comps,
                            ref_color(orig_comps[i], bg),
                            clip=orig_comps[i].get("clip"),
                            tol=orig_comps[i].get("ink_tol", BG_TOL))
        if d is None:
            continue
        nb = (ob[0] + d[0], ob[1] + d[1], ob[2] + d[2], ob[3] + d[3])
        out[i] = {"box": nb,
                  "cx": (nb[0] + nb[2]) / 2.0, "cy": (nb[1] + nb[3]) / 2.0,
                  "w": nb[2] - nb[0], "h": nb[3] - nb[1]}
    return out


# --------------------------- профиль кромок ---------------------------

EDGE_BAND = 6
EDGE_FLAT_TOL = 8
EDGE_DIFF_TOL = 6


def side_cuts(arr, box, side, band=EDGE_BAND):
    """Все срезы вдоль стороны бокса: матрица (длина_стороны, band, 3).

    Срез всегда идёт ОТ края ВНУТРЬ, поэтому профили четырёх сторон
    сравниваются между собой без учёта направления оси.
    """
    a = np.asarray(arr)
    x0, y0, x1, y1 = [int(v) for v in box]
    h, w = a.shape[:2]
    x0, y0 = max(0, x0), max(0, y0)
    x1, y1 = min(w, x1), min(h, y1)
    if x1 <= x0 or y1 <= y0:
        return np.zeros((0, 0, 3), dtype=a.dtype)
    if side == "left":
        return a[y0:y1, x0:min(x1, x0 + band)]
    if side == "right":
        return a[y0:y1, max(x0, x1 - band):x1][:, ::-1]
    if side == "top":
        return np.transpose(a[y0:min(y1, y0 + band), x0:x1], (1, 0, 2))
    return np.transpose(a[max(y0, y1 - band):y1, x0:x1], (1, 0, 2))[:, ::-1]


def is_edge_side(cuts, tol=EDGE_FLAT_TOL, min_len=8, corner=4,
                 min_flat_frac=0.75, min_span=24):
    """Несёт ли сторона КРОМКУ (фаску), а не текст.

    ДВА условия, и второе не менее важно первого:

    1. Профиль одинаков по длине стороны (после отсечения [corner] строк с
       концов — там стык двух кромок, его цвет по построению не равен ни одной).
       Замер на кнопке OK: 49.5 / 85.2 / 28.0 на первых трёх строках, 0.0 в
       середине, 105.3 / 106.2 на последних. Без отсечения средняя 10.7 — выше
       порога, и настоящая фаска не опознавалась.

    2. Сторона ДОСТАТОЧНО ДЛИННАЯ ([min_span]). Это не «оптимизация», а
       единственное, что отличает фаску от вертикального штриха буквы. Замер
       на эталоне, подпись «Система» 40x14: буква «С» тянется через всю высоту
       элемента, поэтому её штрих даёт profile deviation РОВНО 0.0 на всех
       центральных строках — по признаку плоскости это идеальная «фаска».
       Отличие только в масштабе: буква высотой 14px, фаска идёт вдоль стороны
       кнопки в 35px и более. Порог 24 лежит между.

    Без обоих условий проверка кромок бесполезна: на эталоне «расхождение
    профиля» находилось у 31 элемента из 45, и почти все — текстовые строки,
    где сравнивался не профиль фаски, а случайный столбец глифа.
    """
    c = np.asarray(cuts)
    if c.shape[0] < max(min_len, min_span) or c.size == 0:
        return False
    if c.shape[0] > 2 * corner + min_len // 2:
        c = c[corner:-corner]
    med = np.median(c.astype(np.int32), axis=0)
    per_row = np.abs(c.astype(np.int32) - med).sum(axis=2).mean(axis=1)
    flat = float((per_row <= tol).mean())
    return flat >= min_flat_frac and float(per_row.mean()) <= tol


def edge_profile_diff(orig_arr, rend_arr, comps, band=EDGE_BAND,
                      tol=EDGE_DIFF_TOL, min_w=24, min_h=14, corner=4):
    """Расхождение профиля кромок: список (элемент, сторона, L1-разница).

    ЗАЧЕМ отдельно от MAE. Кромка — 3-6 полос по 1px, то есть у кнопки 137x35
    это ~2% площади. Перепутанный порядок полос или промах одной из них меняет
    глобальный MAE на сотые доли, а фаска выглядит «не той» — именно тот класс
    дефекта, который пиксельная метрика ранжирует последним, а глаз замечает
    первым. Сравниваются медианные профили: одна строка с артефактом не должна
    решать за всю сторону; углы отбрасываются, там стык двух кромок.
    """
    out = []
    for c in comps:
        b = c["box"]
        if (b[2] - b[0]) < min_w or (b[3] - b[1]) < min_h:
            continue
        for side in ("left", "top", "right", "bottom"):
            co = side_cuts(orig_arr, b, side, band)
            cr = side_cuts(rend_arr, b, side, band)
            if co.size == 0 or cr.size == 0 or not is_edge_side(co, corner=corner):
                continue
            n = min(co.shape[1], cr.shape[1])
            if co.shape[0] > 2 * corner + 4:
                co = co[corner:-corner]
                cr = cr[corner:-corner] if cr.shape[0] > 2 * corner + 4 else cr
            po = np.median(co[:, :n].astype(np.int32), axis=0)
            pr = np.median(cr[:, :n].astype(np.int32), axis=0)
            d = int(np.abs(po - pr).sum())
            if d > tol:
                out.append({"name": c["name"], "side": side, "diff": d,
                            "orig": [tuple(int(v) for v in px) for px in po],
                            "rend": [tuple(int(v) for v in px) for px in pr]})
    out.sort(key=lambda v: -v["diff"])
    return out


# --------------------------- сводка ---------------------------

def is_glyph_fragment(comp, comps, max_pad=2):
    """Отдельный глиф внутри строки, а не элемент вёрстки.

    Его габарит и край нельзя приписать вёрстке: ширина буквы задана шрифтом,
    а в направляющую он входит только потому, что случайно оказался первым в
    строке. Признаки, оба нужны:
      * не standalone — площадь меньше STANDALONE_AREA, то есть это фрагмент
        строки, а не элемент;
      * соседи ближе [max_pad]*2 пикселей, значит окно замера сжато ими и
        внутри него стоят РАЗНЫЕ буквы двух шрифтов.

    Порог [max_pad] равен 2, а не 0: замер на эталоне — буква «A» в «VIP/Admin»
    (16x14, площадь 224) имеет free_pad ровно 1, потому что соседняя буква
    отстоит на 2px. При пороге 0 она проходила как элемент вёрстки, и вердикт
    сообщал `РАЗМЕР dw=-7` о ширине буквы недоступного шрифта. Элементу вёрстки
    2px зазора не бывает: минимальный зазор между элементами на эталоне 15px.
    """
    if comp.get("standalone", True):
        return False
    return free_pad(comp["box"], comps) <= max_pad


NESTED_TOL = 60
# Максимальный зазор между низом текстового ряда и низким фрагментом под ним
# (подчёркивание, засечка). Замер: «_» в «cts_team» стоит вплотную, зазор 0.
TEXT_RUN_GAP = 2


def detect_nested(arr, comps, bg, min_area=3000, min_obj=20, tol=NESTED_TOL,
                  border=4, max_frac=0.6):
    """Компоненты ВНУТРИ элементов со своей поверхностью.

    ЗАЧЕМ, найдено по прямому указанию на пропущенные дефекты. [detect] ищет
    связность «не фон КАДРА», поэтому поле ввода, dropdown или кнопка
    становятся ОДНИМ компонентом, а всё, что нарисовано внутри них, исчезает
    из списка. Замер на эталоне: 12 крупных компонентов содержат **72**
    внутренних объекта — больше, чем весь список компонентов (67).

    Два конкретных дефекта, которые из-за этого не находились:
      * треугольник dropdown: в оригинале основание 11px, в рендере 16px
        (border-left/right 9px вместо 6px) — вердикта РАЗМЕР не было, потому
        что треугольник не компонент;
      * иконка «глаз»: в оригинале 867..897 (31px), в рендере ассет 872..899 —
        вырезан на 5px короче, левая слабая часть ушла в фон поля.

    Поверхность берётся как доминирующий цвет самого элемента, а не фон кадра —
    иначе внутри поля «не фоном» окажется вся его площадь. Краевые [border]
    пикселей отбрасываются: там рамка и фаска, они принадлежат родителю.
    Объект, занявший больше [max_frac] площади родителя, отбрасывается — это
    сам родитель, увиденный изнутри.
    """
    a = np.asarray(arr)
    out = []
    for parent in comps:
        x0, y0, x1, y1 = [int(v) for v in parent["box"]]
        if (x1 - x0) * (y1 - y0) < min_area:
            continue
        crop = a[y0:y1, x0:x1]
        from metrics import dominant_color
        surf, _frac = dominant_color(crop)
        m = np.abs(crop.astype(np.int32)
                   - np.array(surf, dtype=np.int32)).sum(axis=2) > tol
        m[:border] = False
        m[-border:] = False
        m[:, :border] = False
        m[:, -border:] = False
        labels, n = label_components(m)
        area_parent = (x1 - x0) * (y1 - y0)
        for k in range(1, n + 1):
            ys, xs = np.where(labels == k)
            if ys.size < min_obj:
                continue
            bw = int(xs.max() - xs.min()) + 1
            bh = int(ys.max() - ys.min()) + 1
            if bw * bh > max_frac * area_parent:
                continue
            nx0, ny0 = x0 + int(xs.min()), y0 + int(ys.min())
            nx1, ny1 = nx0 + bw, ny0 + bh
            # Доминирующий цвет считается по ПИКСЕЛЯМ ОБЪЕКТА, а не по всему
            # его боксу. Замер: у треугольника dropdown 41% бокса — поверхность
            # поля, и dominant_color(bbox) возвращал (62,70,55) — цвет ФОНА, а
            # не (176,174,174) самого треугольника. Из-за этого _shape_cost
            # сравнивал цвет фона с цветом фона, competition-гейт находил
            # «более похожего» кандидата и ОТВЕРГАЛ верную пару: подсаженное
            # сужение треугольника на 4px не находилось вовсе.
            objm = (labels == k)[int(ys.min()):int(ys.max()) + 1,
                                 int(xs.min()):int(xs.max()) + 1]
            sub = crop[int(ys.min()):int(ys.max()) + 1,
                       int(xs.min()):int(xs.max()) + 1]
            objpx = np.asarray(sub)[objm]
            if objpx.size:
                vals, counts = np.unique(objpx.reshape(-1, 3), axis=0,
                                         return_counts=True)
                dom = tuple(int(v) for v in vals[int(counts.argmax())])
                dom_frac = float(counts.max()) / float(len(objpx))
            else:
                dom, dom_frac = dominant_color(sub)
            out.append({
                "box": (nx0, ny0, nx1, ny1),
                "w": bw, "h": bh,
                "cx": (nx0 + nx1) / 2.0, "cy": (ny0 + ny1) / 2.0,
                "pixels": int(ys.size),
                "fill": ys.size / float(bw * bh),
                "dom": tuple(int(c) for c in dom),
                "dom_frac": float(dom_frac),
                "thin": min(bw, bh) <= THIN_PX,
                "standalone": (bw * bh >= STANDALONE_AREA
                               and min(bw, bh) >= STANDALONE_SIDE),
                "parent": parent.get("name"),
                # Поверхность родителя. Без неё ink_bbox взял бы фон КАДРА, и
                # «не фоном» оказалась бы вся площадь родителя: замер показал
                # deltas (0,0,0,0) у треугольника, который реально шире на 3px.
                "surf": tuple(int(c) for c in surf),
                # ВНУТРЕННЯЯ область родителя (без рамки и фаски). Окно
                # замера чернил нельзя расширять за неё по двум причинам,
                # обе замерены: за пределами родителя лежит фон кадра, который
                # отличается от поверхности поля на 45 (> BG_TOL), а на самой
                # границе — рамка и фаска. И то и другое попадает в ink и
                # заполняет всё окно, из-за чего deltas выходили (0,0,0,0) у
                # треугольника, который реально шире на 3px.
                "clip": (x0 + border, y0 + border, x1 - border, y1 - border),
                # Порог «что считать содержимым» — тот же, которым объект был
                # НАЙДЕН. Иначе детекция и замер расходятся: при BG_TOL=14
                # ink_bbox треугольника захватывал слабый градиент поля и
                # выдавал 17x8 вместо 15x7, из-за чего dw=+3 превращался в +1
                # и вердикт исчезал под порогом.
                "ink_tol": tol,
                "name": "n%02d_%dx%d@%d,%d" % (len(out), bw, bh, nx0, ny0),
            })
    return out


def in_text_run(comp, comps, min_run=2, base_frac=0.6, h_frac=0.4,
                x_gap=None):
    """Стоит ли объект в РЯДУ похожих на одной базовой линии.

    Признак текста, работающий там, где не работает [is_glyph_fragment]. Внутри
    поля ввода буквы стоят с зазором 4px — это больше max_pad, поэтому по
    близости соседей они не отличались от элементов, и их ширина приписывалась
    вёрстке (замер на эталоне: 4 вердикта о буквах строки «[B] К о Н Т р Е»).

    Здесь признак другой и более прямой: у буквы есть >= [min_run] соседей
    того же родителя, с общей базовой линией (перекрытие по вертикали не менее
    [base_frac] меньшей высоты) и похожей высотой (± [h_frac]). У одиночного
    элемента — треугольника dropdown, иконки — соседей нет вовсе. Замер: 8
    соседей у каждой буквы, 0 у треугольника и у иконки «глаз».

    Второй проход для НИЗКИХ фрагментов: подчёркивание «_» в «cts_team» имеет
    высоту 3px против 14px у букв, поэтому по гейту высоты соседей не находит и
    выглядит элементом вёрстки. Но его положение задаёт шрифт (замер: в
    оригинале y=236..238, в рендере y=239..240 — базовая линия та же, отличается
    только форма глифа). Признак: фрагмент лежит ВНУТРИ горизонтального
    диапазона текстового ряда и вплотную под ним ([x_gap] пикселей).
    """
    b = comp["box"]
    h = b[3] - b[1]
    if h <= 0:
        return False
    n = 0
    line = []
    for o in comps:
        if o is comp or o.get("parent") != comp.get("parent"):
            continue
        ob = o["box"]
        oh = ob[3] - ob[1]
        overlap = min(b[3], ob[3]) - max(b[1], ob[1])
        hmin = min(h, oh)
        if hmin <= 0 or overlap < base_frac * hmin:
            continue
        if abs(oh - h) > h_frac * h:
            continue
        n += 1
        if n >= min_run:
            return True
    # Второй проход: низкий фрагмент под текстовым рядом.
    if x_gap is None:
        x_gap = TEXT_RUN_GAP
    peers = [o for o in comps
             if o is not comp and o.get("parent") == comp.get("parent")
             and (o["box"][3] - o["box"][1]) >= 2 * h]
    for o in peers:
        ob = o["box"]
        same_run = [q for q in peers
                    if abs((q["box"][3] - q["box"][1]) - (ob[3] - ob[1])) <= 3
                    and abs(q["box"][3] - ob[3]) <= 3]
        if len(same_run) < min_run:
            continue
        rx0 = min(q["box"][0] for q in same_run)
        rx1 = max(q["box"][2] for q in same_run)
        ry1 = max(q["box"][3] for q in same_run)
        if b[0] >= rx0 and b[2] <= rx1 and 0 <= b[1] - ry1 <= x_gap:
            return True
    return False


def ink_fraction(arr, box, bg, tol=BG_TOL):
    """Доля «не фон» внутри бокса в данном растре.

    Нужна, чтобы вердикт ПРОПУЩЕН означал именно то, что говорит: на этом
    месте в рендере ПУСТО. Замер на эталоне: 5 «пропусков» оказались текстовыми
    строками, где рендер нарисовал ink с другими границами компонентов
    (соседние глифы вышли за исходный бокс, внутренние не заполнили его). Ink
    там был — значит это расхождение границ, а не отсутствие элемента, и
    называть его пропуском значит отправлять читателя искать не ту неисправность.
    """
    x0, y0, x1, y1 = [int(v) for v in box]
    a = np.asarray(arr)
    h, w = a.shape[:2]
    x0, y0 = max(x0, 0), max(y0, 0)
    x1, y1 = min(x1, w), min(y1, h)
    if x1 <= x0 or y1 <= y0:
        return 0.0
    crop = a[y0:y1, x0:x1].astype(np.int32)
    bgv = np.array(bg, dtype=np.int32)
    return float((np.abs(crop - bgv).sum(axis=2) > tol).mean())


def absorb_fragments(orig, rend, pairs, missing, spurious, min_cont=0.90,
                     side_tol=SIZE_TOL):
    """Дополнить УЖЕ сопоставленную пару фрагментами, осевшими в том же боксе.

    ЗАЧЕМ (найдено на эталоне, не гипотеза). `group_match` работает только с
    несопоставленным остатком, а фаза IoU забирает пару раньше: у строки
    «Мышь» IoU(ориг 39x14, один фрагмент рендера 13x14) = 0.333, то есть выше
    порога 0.30. Пара принята, остальные фрагменты той же строки остались в
    `spurious`, и отчёт сообщал `РАЗМЕР dw = -26` — «сделай элемент на 26px
    шире». Ширина при этом верная: объединение фрагментов совпадает с боксом
    оригинала. Вердикт отправлял чинить то, что не сломано.

    КРИТЕРИЙ — совпадение объединения с боксом, и только оно. Предыдущая
    версия требовала ещё, чтобы сопоставленная часть была в 1.8 раза меньше
    оригинала. Замер на эталоне: у настоящих случаев это отношение 1.11–1.13
    (пара — крупный фрагмент, а не один глиф), то есть гейт отсекал ровно то,
    для чего написан — пять ложных вердиктов размера остались в отчёте.

    Защита от «приклеить соседа и объяснить настоящий промах» держится на трёх
    вещах, каждая необходима:
      * поглощаются только компоненты из [spurious] — не сопоставленные ни с
        одним элементом оригинала. Чужой элемент туда не попадёт: он
        сопоставлен со своим;
      * [min_cont] 0.90, а не 0.60 — фрагмент должен лежать в боксе практически
        целиком. Рендер, вылезающий за бокс, containment не проходит и остаётся
        настоящим промахом;
      * допуск АБСОЛЮТНЫЙ [side_tol], тот же, по которому считается сам вердикт.
        Доля от размера (было 0.25) на кнопке 234px разрешила бы назвать
        совпадением расхождение в 58px.

    Возвращает (extra_frag, claimed_r): группы, дополняющие существующие пары,
    и индексы рендера, которые они забрали из [spurious].
    """
    def size_ok(u, box):
        return (abs((u[2] - u[0]) - (box[2] - box[0])) <= side_tol
                and abs((u[3] - u[1]) - (box[3] - box[1])) <= side_tol)

    extra, claimed = [], set()
    for i, j, _how in pairs:
        ob = orig[i]["box"]
        grp = [k for k in spurious
               if k not in claimed and containment(rend[k]["box"], ob) >= min_cont]
        if not grp:
            continue
        allr = [j] + grp
        u = union_box([rend[k]["box"] for k in allr])
        if not size_ok(u, ob):
            continue
        claimed.update(grp)
        extra.append((i, allr, u))
    return extra, claimed


def explaining_phases(orig_comps, rend_comps):
    """Сопоставление и ВСЕ фазы, объясняющие остатки, в одном месте.

    ЗАЧЕМ ОТДЕЛЬНОЙ ФУНКЦИЕЙ. «Остаток» — это не выход [match], а то, что
    осталось необъяснённым после фаз группировки. Разница измерена: сырой выход
    match на эталоне даёт 24 пропуска и 41 лишний, после фаз — 15 и 16. Пока
    этот конвейер жил внутри [analyze], потребитель снаружи (post-pair) собирал
    свою версию и получал на вход объекты, уже объяснённые фрагментацией: 20x14
    в оригинале против двух компонентов 8x14 и 11x12 в рендере — фаза frag
    объясняет это как «один -> много», а post-pair строил связь 1<->1 с ОДНОЙ
    из половин и сообщал сдвиг +5.5px у объекта, который стоит на месте.

    Возвращает словарь, потому что вердиктам ниже нужны и сами группы, а не
    только остатки.
    """
    pairs, missing, spurious = match(orig_comps, rend_comps)

    # Фаза 3: один компонент против группы. Обязательна ДО подсчёта пропусков,
    # иначе разная связность рендерера (битмап-шрифт склеивает глифы, TrueType
    # нет) читается как десятки пропусков и лишних элементов.
    frag, merg = group_match(orig_comps, rend_comps, missing, spurious)
    claimed_o = set(i for i, _, _ in frag) | set(
        i for _, grp, _ in merg for i in grp)
    claimed_r = set(j for _, grp, _ in frag for j in grp) | set(
        j for j, _, _ in merg)
    missing = [i for i in missing if i not in claimed_o]
    spurious = [j for j in spurious if j not in claimed_r]

    # Фаза 3b: фрагменты, осевшие внутри УЖЕ сопоставленной пары. Обязательна
    # до подсчёта размера — иначе строка, распавшаяся на глифы, у которой IoU с
    # первым глифом прошёл порог, отчитывается как «элемент на 26px уже», хотя
    # объединение глифов совпадает с оригиналом.
    absorbed, absorbed_r = absorb_fragments(
        orig_comps, rend_comps, pairs, missing, spurious)
    spurious = [j for j in spurious if j not in absorbed_r]

    return {"pairs": pairs, "missing": missing, "spurious": spurious,
            "frag": frag, "merg": merg, "absorbed": absorbed}


def analyze(orig_comps, rend_comps, shift_tol=SHIFT_TOL, size_tol=SIZE_TOL,
            orig_arr=None, rend_arr=None, bg=None, empty_frac=0.05):
    """Полный набор вердиктов по расположению. Чистая функция, без I/O."""
    ph = explaining_phases(orig_comps, rend_comps)
    pairs, missing, spurious = ph["pairs"], ph["missing"], ph["spurious"]
    frag, merg, absorbed = ph["frag"], ph["merg"], ph["absorbed"]
    absorbed_union = {i: u for i, _, u in absorbed}

    # Где элементы ФАКТИЧЕСКИ лежат в рендере — один раз, по чернилам, для всех
    # вердиктов о геометрии. Считается до них, потому что и сдвиг, и строй, и
    # ритм должны читать одно и то же определение «положения элемента».
    eff = effective_boxes(orig_comps, pairs, orig_arr, rend_arr, bg)

    # Смещение группы: тоже по ЧЕРНИЛАМ, а не по объединяющему боксу.
    # Объединение фрагментов зависит от того, сколько кусков дал рендерер и где
    # он их разрезал; чернила от этого не зависят. На эталоне разница ровно та
    # же, что у одиночных пар: `dx=+6.5` по объединению против `0.0` по
    # чернилам. Фрагментация — свойство шрифта, поэтому она не должна попадать
    # в вердикт о положении ни через центр, ни через объединение.
    group_shifted = []
    for i, grp, u in frag:
        ob = orig_comps[i]["box"]
        dx = ((u[0] + u[2]) - (ob[0] + ob[2])) / 2.0
        dy = ((u[1] + u[3]) - (ob[1] + ob[3])) / 2.0
        if orig_arr is not None and rend_arr is not None and bg is not None:
            s = ink_shift(orig_arr, rend_arr, ob, orig_comps,
                          ref_color(orig_comps[i], bg),
                          clip=orig_comps[i].get("clip"),
                          tol=orig_comps[i].get("ink_tol", BG_TOL))
            if s is not None:
                dx, dy = s
        if max(abs(dx), abs(dy)) > shift_tol:
            group_shifted.append({
                "name": orig_comps[i]["name"], "dx": float(dx), "dy": float(dy),
                "how": "group", "thin": orig_comps[i]["thin"],
                "dist": float((dx * dx + dy * dy) ** 0.5),
                "parts": len(grp),
            })
    for j, grp, u in merg:
        rb = rend_comps[j]["box"]
        dx = ((rb[0] + rb[2]) - (u[0] + u[2])) / 2.0
        dy = ((rb[1] + rb[3]) - (u[1] + u[3])) / 2.0
        if orig_arr is not None and rend_arr is not None and bg is not None:
            s = ink_shift(orig_arr, rend_arr, u, orig_comps, bg)
            if s is not None:
                dx, dy = s
        if max(abs(dx), abs(dy)) > shift_tol:
            group_shifted.append({
                "name": "группа->" + rend_comps[j]["name"],
                "dx": float(dx), "dy": float(dy), "how": "group",
                "thin": rend_comps[j]["thin"],
                "dist": float((dx * dx + dy * dy) ** 0.5),
                "parts": len(grp),
            })

    split, merged = overlap_groups(orig_comps, rend_comps, missing, spurious)

    # Разделить «пусто» и «граница компонента другая». Оба растра нужны, чтобы
    # ответ был измеренным: без них вердикт остаётся прежним (пропуск), но
    # честно помеченным как непроверенный.
    empty, boundary = list(missing), []
    extra, extra_boundary = list(spurious), []
    if orig_arr is not None and rend_arr is not None and bg is not None:
        empty, boundary = [], []
        for i in missing:
            # Опорный цвет — поверхность родителя, если элемент вложенный.
            # Замер: удалённый треугольник dropdown попадал в «границы
            # расходятся» вместо ПРОПУЩЕНО, потому что ink_fraction мерил
            # относительно фона КАДРА, а на месте треугольника осталась
            # поверхность поля — «не фон кадра» на 100%.
            ref = ref_color(orig_comps[i], bg)
            tol = orig_comps[i].get("ink_tol", BG_TOL)
            if ink_fraction(rend_arr, orig_comps[i]["box"], ref,
                            tol=tol) <= empty_frac:
                empty.append(i)
            else:
                boundary.append(i)
        extra, extra_boundary = [], []
        for j in spurious:
            # Симметрично missing: опорный цвет из компонента РЕНДЕРА.
            ref = ref_color(rend_comps[j], bg)
            tol = rend_comps[j].get("ink_tol", BG_TOL)
            if ink_fraction(orig_arr, rend_comps[j]["box"], ref,
                            tol=tol) <= empty_frac:
                extra.append(j)
            else:
                extra_boundary.append(j)

    shifted, resized = [], []
    for i, j, how in pairs:
        a, b = orig_comps[i], rend_comps[j]
        # Если пара была дополнена фрагментами, геометрия считается по
        # объединению — оно и есть то, что видит глаз.
        u = absorbed_union.get(i)
        if u is not None:
            bcx, bcy = (u[0] + u[2]) / 2.0, (u[1] + u[3]) / 2.0
            bw, bh = u[2] - u[0], u[3] - u[1]
        else:
            bcx, bcy, bw, bh = b["cx"], b["cy"], b["w"], b["h"]
        dx = bcx - a["cx"]
        dy = bcy - a["cy"]
        # Сдвиг ПЕРЕПРОВЕРЯЕТСЯ по чернилам. Центр компонента задаётся
        # связностью рендерера, а не вёрсткой: битмапный шрифт склеивает строку
        # в одно пятно, TrueType рвёт её на глифы, и центр первого глифа лежит
        # далеко от центра строки. На эталоне это давало 11 ложных вердиктов из
        # 13, вплоть до `dx=+31` там, где чернила стоят пиксель в пиксель.
        # Замер по чернилам не заменяет сопоставление — он заменяет только
        # измеряемую величину, поэтому пара остаётся той же.
        if orig_arr is not None and rend_arr is not None and bg is not None:
            s = ink_shift(orig_arr, rend_arr, a["box"], orig_comps,
                          ref_color(a, bg), clip=a.get("clip"),
                          tol=a.get("ink_tol", BG_TOL))
            if s is not None:
                dx, dy = s
        if max(abs(dx), abs(dy)) > shift_tol:
            shifted.append({"name": a["name"], "dx": float(dx), "dy": float(dy),
                            "how": ("group" if u is not None else how),
                            "thin": a["thin"] or b["thin"],
                            "dist": float((dx * dx + dy * dy) ** 0.5)})
        # Размер — тоже по ЧЕРНИЛАМ, из eff. Габарит компонента задаёт
        # связность: у e28 вердикт по компонентам говорил `dw=-26`, а чернила
        # в том же окне расходятся на 1px. Тот же корень, что у СМЕЩЕНО и
        # ВЫРАВНИВАНИЯ, и лечится тем же единым определением положения.
        eb = eff.get(i)
        if eb is not None:
            dw = eb["w"] - a["w"]
            dh = eb["h"] - a["h"]
        else:
            dw = bw - a["w"]
            dh = bh - a["h"]
        # Габарит ОТДЕЛЬНОГО ГЛИФА не является дефектом вёрстки, и измерить
        # его как дефект нечем. Признак: элемент не standalone (площадь меньше
        # STANDALONE_AREA, то есть это фрагмент строки, а не элемент вёрстки) И
        # free_pad == 0, то есть окно замера сжато вплотную соседними глифами
        # и внутри него стоят РАЗНЫЕ буквы двух шрифтов.
        # Замер на эталоне: 4 из 5 оставшихся вердиктов РАЗМЕР — такие глифы,
        # при том что строки, которым они принадлежат, совпадают по ширине с
        # точностью 1px («Мультиплеер» 146 против 145, «Изменить цвет» 250
        # против 249). То есть вердикт сообщал о форме недоступного шрифта под
        # видом дефекта геометрии — ровно то, что GUIDE требует разделять.
        glyph_only = is_glyph_fragment(a, orig_comps)
        if not glyph_only and max(abs(dw), abs(dh)) > size_tol:
            resized.append({"name": a["name"], "dw": int(dw), "dh": int(dh),
                            "thin": a["thin"] or b["thin"],
                            "mag": max(abs(dw), abs(dh))})
    shifted.extend(group_shifted)
    shifted.sort(key=lambda v: -v["dist"])
    resized.sort(key=lambda v: -v["mag"])

    return {
        "pairs": pairs,
        "missing": [orig_comps[i]["name"] for i in empty],
        "spurious": [rend_comps[j]["name"] for j in extra],
        "boundary": [orig_comps[i]["name"] for i in boundary],
        "boundary_extra": [rend_comps[j]["name"] for j in extra_boundary],
        "ink_checked": (orig_arr is not None and rend_arr is not None
                        and bg is not None),
        "fragmented": ([(orig_comps[i]["name"], len(grp)) for i, grp, _ in frag]
                       + [(orig_comps[i]["name"], len(grp)) for i, grp, _ in absorbed]),
        "coalesced": [(rend_comps[j]["name"], len(grp)) for j, grp, _ in merg],
        "split": [(orig_comps[i]["name"], [rend_comps[j]["name"] for j in hits])
                  for i, hits in split],
        "merged": [(rend_comps[j]["name"], [orig_comps[i]["name"] for i in hits])
                   for j, hits in merged],
        "shifted": shifted,
        "resized": resized,
        "guides": guide_violations(orig_comps, rend_comps, pairs, eff=eff),
        # Профиль кромок — отдельно от MAE: фаска это ~2% площади элемента,
        # поэтому перепутанные полосы почти не двигают пиксельную метрику.
        "edges": (edge_profile_diff(orig_arr, rend_arr, orig_comps)
                  if orig_arr is not None and rend_arr is not None else []),
        "rhythm": (rhythm_violations(orig_comps, rend_comps, pairs, "x", eff=eff)
                   + rhythm_violations(orig_comps, rend_comps, pairs, "y", eff=eff)),
        "order": (order_violations(orig_comps, rend_comps, pairs, "x")
                  + order_violations(orig_comps, rend_comps, pairs, "y")),
        "counts": {"orig": len(orig_comps), "rend": len(rend_comps),
                   "matched": len(pairs), "grouped": len(frag) + len(merg)},
    }
