#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Post-pair: восстановление сдвига из остатков сопоставления.

ЗАЧЕМ. Основной matcher корректно отбрасывает пару, когда тонкий объект
сместился на величину, сравнимую с его собственным размером: у пары
подчёркивание 14x3 против 11x2 боксы КАСАЮТСЯ, но не перекрываются, поэтому
IoU = 0, а shape_cost = 1.27 против порога 0.55. Разбор слагаемых стоимости:
вклад высоты 0.667 при расхождении всего в 1px, потому что нормировка идёт на
размер самого объекта. Решение неравенства даёт: у объектов высотой <= 3px пара
невозможна при промахе 1px, сколько бы ни совпадали цвет и заполненность.

В результате один физический сдвиг выходил ДВУМЯ противоположными вердиктами:
ПРОПУЩЕНО для оригинала и ЛИШНЕЕ для рендера. Замер подтверждает, что элемент
нарисован: ink в боксе оригинала 0.0000, в том же боксе со сдвигом +3px — 0.25.
ПРОПУЩЕНО означает «не нарисовано» и отправляет чинить то, что уже есть.

ПОЧЕМУ НЕ ПРАВИТЬ MATCHER. Проверено экспериментом до написания кода: пол
нормировки (floor 4/5/6/8) пары не находит вовсе — вклад ширины остаётся, а до
фазы формы дело не доходит из-за нулевого IoU. Абсолютная разница вместо
нормировки находит её, но ТЕРЯЕТ 9 существующих связей: ослабление главной
метрики платит настоящими парами. Post-pair работает только с остатками,
поэтому переставить уже найденную пару структурно не может.

ВХОД — ОСТАТКИ ПОСЛЕ ОБЪЯСНЯЮЩИХ ФАЗ, а не сырой выход match. Замер на
эталоне: match даёт 24 пропуска и 41 лишний, после фаз группировки остаётся
15 и 16. Разница не косметическая: объект 20x14 в оригинале против двух
компонентов 8x14 и 11x12 в рендере — это фрагментация, её объясняет фаза frag.
На сыром входе post-pair связывал такой объект с ОДНОЙ из половин и сообщал
сдвиг +5.5px у объекта, который по прямому сравнению пикселей стоит на месте
(MAE 36.54 в его собственных координатах — минимум по всей окрестности +-20px).
Поэтому конвейер фаз вынесен в [layout.explaining_phases] и читается оттуда:
своя копия здесь означала бы, что «остаток» определяется дважды.

РАЗДЕЛЕНИЕ ОТВЕТСТВЕННОСТИ (каждый пункт выведен из замера, не из удобства)

  topology        БЛОКЕР. Ни одна фаза не выполняется при неоднозначной
                  топологии родителей.
  run_id          ПРЕДУСЛОВИЕ корректности. Монотонность по оси осмысленна
                  только внутри одной строки.
  ident, dist/own, size_ratio, in_text_run
                  ГЕЙТЫ допустимости кандидата.
  граф кандидатов ПРЕДСТАВЛЕНИЕ. Сначала все допустимые рёбра, выбор потом.
  монотонность    ОГРАНИЧЕНИЕ на множество решений.
  1 - dist/own    ЦЕЛЬ. Единственная функция предпочтения.

ЦЕЛЬ ГЕОМЕТРИЧЕСКАЯ, НЕ ВИЗУАЛЬНАЯ. Замер на конфликте: n71 стоит на 14.5px от
кандидата и имеет ident 0.147, n72 — на 4.0px и ident 0.282. Жадный выбор по
ident отдаёт кандидата дальнему n71 и оставляет n72 без пары, то есть строит
связь через голову соседа. Глифы одного шрифта визуально неразличимы, поэтому
ident годится только как допуск.

ЦЕЛЬ НЕ ЧИСЛО СВЯЗЕЙ. Замер: вес 1 на ребро даёт 9 связей и 5 из 6
подтверждённых, геометрический вес — 7 связей и 6 из 6. Максимизация числа
объяснённых объектов теряет верную связь, получая больше неверных.

ЧЕМ СВЯЗЬ ПРОВЕРЯЕТСЯ, А ЧЕМ НЕТ. ink_shift для этого НЕ годится: он слеп,
когда ink_bbox упирается во все четыре границы окна замера (free_pad = 0 у
объекта, у которого сосед вплотную). Замер: из 54 связей на наборе 10 давали
ровно (0,0), и у ВСЕХ десяти окно насыщено — то есть (0,0) означало «не
мерится», а не «не двигалось». Независимая величина — поиск шаблона: содержимое
бокса оригинала ищется в рендере по окрестности с минимизацией MAE. Она не
зависит ни от насыщения окна, ни от границ компонентов.

ПОЧЕМУ НЕТ ГЕЙТА ПО ЗАЗОРУ. Проверено: снятие ограничения на зазор добавляет 7
связей и НИ ОДНОЙ ложной на элементах вёрстки. Он выглядел значимым только
потому, что у соседних глифов зазор и так нулевой.

ГРАНИЦА МЕТОДА. Сдвиг, меняющий компонентную структуру, не восстанавливается:
вертикально-тонкий объект 3x15, сдвинутый на 6px вдоль короткой стороны,
сливается с соседом, и остаточного компонента для него не существует. Это
граница входных данных механизма missing x spurious, а не порога.
"""
import sys
from collections import defaultdict

CLEAN = "CLEAN"

# Гейты допустимости. Числа выведены из замеров, каждое со своей причиной.
SIZE_RATIO_MAX = 2.0    # сдвиг не меняет габарит в разы
DIST_OWN_MAX = 1.5      # граница «тот же объект»: при 2.0 появляется первая
                        # ложная связь на элементе вёрстки, при 1.5 их ноль
                        # при любом ident вплоть до 0.50
IDENT_MAX = 0.35        # консервативный предел внутри проверенной области, а НЕ
                        # граница ложности: её задаёт dist/own. При 0.50 ложных
                        # тоже нет, но проверить каждую из 111 связей нельзя


def _peers(a, b, base_frac=0.6, h_frac=0.4):
    """Отношение соседства по строке — то же, что внутри in_text_run."""
    if a.get("parent") != b.get("parent"):
        return False
    ha = a["box"][3] - a["box"][1]
    hb = b["box"][3] - b["box"][1]
    if ha <= 0 or hb <= 0:
        return False
    overlap = min(a["box"][3], b["box"][3]) - max(a["box"][1], b["box"][1])
    if overlap < base_frac * min(ha, hb):
        return False
    return abs(hb - ha) <= h_frac * ha


def run_ids(comps):
    """Идентификатор строки: связная компонента отношения соседства.

    НЕ квантование координаты. Замер: деление top на константу разрезает от 5
    до 12 строк в каждом наборе, потому что разброс top внутри одной строки
    достигает 4px — у разных букв разная высота. Хуже: у распределений «внутри
    строки» и «между строками» минимумы пересекаются (0.5px против медианы 3px),
    поэтому порога по координате не существует ни для top, ни для bottom, ни для
    центра. bottom не лучше top (разброс до 55px против 45px): базовая линия в
    растре без шрифтовых метрик не наблюдаема, у букв с выносом низ уходит ниже.

    Прямое следствие для DP, проверенное замером: на квантованных строках
    подтверждённая связь n72->n90 НЕ находится — разрез разводит объект и его
    соседей по разным строкам, компонента становится неоднозначной и
    пропускается.
    """
    byparent = defaultdict(list)
    for c in comps:
        byparent[c.get("parent")].append(c)
    parent = {}

    def find(x):
        parent.setdefault(x, x)
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    def union(x, y):
        rx, ry = find(x), find(y)
        if rx != ry:
            parent[rx] = ry

    for _, group in byparent.items():
        for i in range(len(group)):
            for j in range(i + 1, len(group)):
                if _peers(group[i], group[j]):
                    union(group[i]["name"], group[j]["name"])
    out = {}
    for c in comps:
        out[c["name"]] = ("run", find(c["name"]))
    return out


def run_axis(members):
    """Главная ось строки по разбросу центров её членов.

    Определяется по строке, а не по форме объекта: строка из узких букв даёт
    большой разброс по x при малом по y. Вертикальные строки в проверенном
    материале отсутствуют (ось x у всех 21 строки обоих слоёв), поэтому ветка
    'y' существует, но НЕ ПРОВЕРЕНА на реальных данных.
    """
    if len(members) < 2:
        return "x"
    xs = [c["cx"] for c in members]
    ys = [c["cy"] for c in members]
    return "x" if (max(xs) - min(xs)) >= (max(ys) - min(ys)) else "y"


def own_scale(a, b):
    """Собственный масштаб пары: наибольшая сторона любого из объектов."""
    return max(a["w"], a["h"], b["w"], b["h"])


def center_distance(a, b):
    return ((a["cx"] - b["cx"]) ** 2 + (a["cy"] - b["cy"]) ** 2) ** 0.5


def size_ratio(a, b):
    ra = max(a["w"], 1) / float(max(b["w"], 1))
    rb = max(a["h"], 1) / float(max(b["h"], 1))
    ra = ra if ra >= 1 else 1.0 / ra
    rb = rb if rb >= 1 else 1.0 / rb
    return max(ra, rb)


def eligible(a, b, orig_all, rend_all, rid_o, rid_r, identity_cost,
             in_text_run, size_ratio_max=SIZE_RATIO_MAX,
             dist_own_max=DIST_OWN_MAX, ident_max=IDENT_MAX):
    """Может ли этот render-объект быть этим original-объектом.

    Возвращает (bool, причина_отказа, измерения). Причина возвращается всегда:
    отказ без названной причины не позволяет отличить «гейт сработал» от
    «объект не рассматривался».
    """
    m = {}
    if not in_text_run(a, orig_all) or not in_text_run(b, rend_all):
        return False, "not_in_text_run", m
    # Строка — предусловие, а не гейт качества: монотонность по оси не имеет
    # смысла между объектами разных строк.
    if rid_o.get(a["name"]) is None or rid_r.get(b["name"]) is None:
        return False, "no_run", m
    m["size_ratio"] = size_ratio(a, b)
    if m["size_ratio"] > size_ratio_max:
        return False, "size_ratio", m
    own = own_scale(a, b)
    d = center_distance(a, b)
    m["own"] = own
    m["dist"] = d
    m["dist_own"] = d / own if own else float("inf")
    if m["dist_own"] > dist_own_max:
        return False, "dist_own", m
    m["ident"] = identity_cost(a, b)
    if m["ident"] > ident_max:
        return False, "ident", m
    return True, None, m


def candidate_graph(orig, rend, missing, spurious, rid_o, rid_r,
                    identity_cost, in_text_run, **gates):
    """ФАЗА A: все допустимые рёбра. Никакого выбора.

    Кандидат не равен паре: один глиф геометрически похож сразу на несколько
    соседних. Замер: 41% original-объектов имеют больше одного допустимого
    кандидата, компоненты доходят до 3x4 и 2x6. Поэтому выбор — отдельная
    задача, а жадный отбор не является её решением.
    """
    edges = []
    for i in missing:
        a = orig[i]
        for j in spurious:
            b = rend[j]
            ok, _why, m = eligible(a, b, orig, rend, rid_o, rid_r,
                                   identity_cost, in_text_run, **gates)
            if not ok:
                continue
            e = {"i": i, "j": j,
                 "run_o": rid_o[a["name"]], "run_r": rid_r[b["name"]]}
            e.update(m)
            edges.append(e)
    return edges


def components(edges):
    """Связные компоненты двудольного графа кандидатов."""
    parent = {}

    def find(x):
        parent.setdefault(x, x)
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    def union(x, y):
        rx, ry = find(x), find(y)
        if rx != ry:
            parent[rx] = ry

    for e in edges:
        union(("o", e["i"]), ("r", e["j"]))
    groups = defaultdict(list)
    for e in edges:
        groups[find(("o", e["i"]))].append(e)
    return list(groups.values())


def objective(edge):
    """Единственная функция предпочтения: геометрическая близость."""
    return 1.0 - edge["dist_own"]


def monotone_match(edges, orig, rend, axis="x"):
    """Монотонное 1<->1 сопоставление вдоль оси строки.

    Максимизируется СУММА выгод, не число рёбер. Порядок сохраняется: если два
    оригинала идут вдоль оси, их пары должны идти в том же порядке. Реализация —
    DP по двум отсортированным спискам.
    """
    key_o = (lambda i: orig[i]["cx"]) if axis == "x" else (
        lambda i: orig[i]["cy"])
    key_r = (lambda j: rend[j]["cx"]) if axis == "x" else (
        lambda j: rend[j]["cy"])
    os_ = sorted({e["i"] for e in edges}, key=key_o)
    rs = sorted({e["j"] for e in edges}, key=key_r)
    w = {(e["i"], e["j"]): objective(e) for e in edges}
    m, n = len(os_), len(rs)
    dp = [[0.0] * (n + 1) for _ in range(m + 1)]
    mv = [[None] * (n + 1) for _ in range(m + 1)]
    for a in range(1, m + 1):
        for b in range(1, n + 1):
            best, move = dp[a - 1][b], "skip_o"
            if dp[a][b - 1] > best:
                best, move = dp[a][b - 1], "skip_r"
            k = (os_[a - 1], rs[b - 1])
            if k in w:
                cand = dp[a - 1][b - 1] + w[k]
                if cand > best:
                    best, move = cand, "take"
            dp[a][b], mv[a][b] = best, move
    out, a, b = [], m, n
    while a > 0 and b > 0:
        if mv[a][b] == "take":
            k = (os_[a - 1], rs[b - 1])
            out.append(next(e for e in edges
                            if e["i"] == k[0] and e["j"] == k[1]))
            a, b = a - 1, b - 1
        elif mv[a][b] == "skip_o":
            a -= 1
        else:
            b -= 1
    return out[::-1]


def post_pair(orig, rend, missing, spurious, identity_cost, in_text_run,
              topology_status=CLEAN, **gates):
    """Восстановление сдвигов из остатков. Возвращает связи и отчёт.

    ВХОД: missing и spurious — остатки ПОСЛЕ объясняющих фаз
    ([layout.explaining_phases]), а не сырой выход match. На сыром входе сюда
    попадают объекты, уже объяснённые фрагментацией, и связь 1<->1 описывает
    половину объекта.

    При любой топологии, кроме CLEAN, не выполняется вовсе. Замер, почему это
    семантически необходимо, а не осторожность: на рендере с продублированными
    родителями 86% остаточных компонентов рендера (48 из 56) лежат внутри копий
    контейнеров, тогда как на шести чистых рендерах — ни одного. Среди
    построенных на таком входе связей есть связь В КОПИЮ контейнера: её dx
    смешивает смещение элемента со смещением между копиями.
    """
    if topology_status != CLEAN:
        return [], {"status": "NOT_RUN", "reason": "topology_%s"
                    % topology_status, "links": 0}
    rid_o, rid_r = run_ids(orig), run_ids(rend)
    edges = candidate_graph(orig, rend, missing, spurious, rid_o, rid_r,
                            identity_cost, in_text_run, **gates)
    obyrun = defaultdict(list)
    for c in orig:
        obyrun[rid_o[c["name"]]].append(c)

    links, skipped = [], []
    for comp in components(edges):
        runs_o = {e["run_o"] for e in comp}
        runs_r = {e["run_r"] for e in comp}
        # Компонента, накрывающая больше одной строки, отбрасывается: порядок
        # объектов из разных строк сравнивать нельзя.
        if len(runs_o) > 1 or len(runs_r) > 1:
            skipped.append({"runs_o": len(runs_o), "runs_r": len(runs_r),
                            "edges": len(comp)})
            continue
        axis = run_axis(obyrun[next(iter(runs_o))])
        links += monotone_match(comp, orig, rend, axis=axis)

    return links, {
        "status": "OK",
        "candidates": len(edges),
        "components": len(components(edges)),
        "skipped_multi_run": len(skipped),
        "links": len(links),
        "gates": {"size_ratio_max": gates.get("size_ratio_max",
                                              SIZE_RATIO_MAX),
                  "dist_own_max": gates.get("dist_own_max", DIST_OWN_MAX),
                  "ident_max": gates.get("ident_max", IDENT_MAX)},
    }
