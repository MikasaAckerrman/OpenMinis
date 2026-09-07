"""Интеграционный контракт цепочки uicopy: detector -> identity -> topology
-> run_id -> post-pair.

Проверяет НЕ качество отдельного слоя (это делают selftest_*), а сцепку:

  1. ПОРЯДОК ФАЗ. Топология считается раньше post-pair на каждом рендере.
     Не «так написано», а: при блокирующем вердикте post-pair возвращает
     NOT_RUN и НУЛЬ связей.

  2. ОТСУТСТВИЕ ОБХОДА. В дереве нет второго пути к post_pair(), который
     не проходил бы через вердикт топологии. Проверяется по исходникам,
     потому что тест на поведение не увидит путь, который просто не вызван.

  3. ОДНА ИСТИНА НА ПОНЯТИЕ. min_area, допуск идентичности, отношение
     соседства строки не имеют второй копии в верхних слоях: значение,
     прочитанное из нижнего слоя, совпадает с использованным.

  4. НАПРАВЛЕНИЕ ЗАВИСИМОСТЕЙ. Нижние слои не импортируют верхние —
     иначе «matcher не тронут» недоказуемо: цикл означает, что поведение
     matcher зависит от того, что делает post-pair.

  5. BASELINE MATCHER. Снимок пар/фрагментов на всех рендерах совпадает
     с сохранённым эталоном побайтово.

  6. ВОСПРОИЗВОДИМОСТЬ. Повторный прогон той же цепочки на том же входе
     даёт тот же результат (нет зависимости от порядка обхода множеств).

Запуск: python3 integration.py <каталог с ORIGINAL.png и render_*.png>
"""

import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import numpy as np                      # noqa: E402
from PIL import Image                   # noqa: E402

import layout as L                      # noqa: E402
import metrics as M                     # noqa: E402
import postpair as PP                   # noqa: E402
import topology as T                    # noqa: E402
import topocheck                        # noqa: E402

FAILS = []
CHECKS = [0]


def check(name, cond, detail=""):
    CHECKS[0] += 1
    if cond:
        print("  ok   %s%s" % (name, ("  " + detail) if detail else ""))
    else:
        print("  FAIL %s  %s" % (name, detail))
        FAILS.append(name)
    return cond


def head(title):
    print()
    print("=" * 74)
    print(title)
    print("=" * 74)


# --------------------------------------------------------------------------
# полная цепочка на одном рендере
# --------------------------------------------------------------------------

def chain(orig_path, rend_path):
    """Один прогон всей цепочки. Возвращает то, что нужно для проверок."""
    oa = np.asarray(Image.open(orig_path).convert("RGB"))
    ra = np.asarray(Image.open(rend_path).convert("RGB"))
    bg, _f = M.dominant_color(oa)

    otop = L.detect(oa, bg=bg)
    onest = L.detect_nested(oa, otop, bg=bg)
    rtop = L.detect(ra, bg=bg)
    rnest = L.detect_nested(ra, rtop, bg=bg)

    trep = topocheck.analyze_files(orig_path, rend_path)
    allowed, why, who = T.post_pair_allowed(trep)
    status = PP.CLEAN if allowed else why
    dup_parents = {p for g in trep["groups"] for p in g["render_parents"]}

    layers = {}
    for layer, oc, rc in (("top", otop, rtop), ("nested", onest, rnest)):
        # Остатки ПОСЛЕ объясняющих фаз — тот же конвейер, что у вердиктов.
        ph = L.explaining_phases(oc, rc)
        links, rep = PP.post_pair(oc, rc, ph["missing"], ph["spurious"],
                                  L._identity_cost, L.in_text_run,
                                  topology_status=status)
        layers[layer] = {
            "pairs": len(ph["pairs"]), "missing": ph["missing"],
            "spurious": ph["spurious"], "links": links, "rep": rep,
            "orig": oc, "rend": rc, "arr_o": oa, "arr_r": ra, "bg": bg,
        }
    return {"status": status, "owner": who, "topo_groups": len(trep["groups"]),
            "allowed": allowed, "dup_parents": dup_parents, "layers": layers}


def ink_of(res, layer, edge):
    """ОТБРОШЕНО как способ проверки связи. Оставлено с замером почему.

    ink_shift слеп при насыщенном окне: когда ink_bbox упирается во все четыре
    границы (free_pad = 0, сосед вплотную), сдвиг внутри окна не наблюдаем и
    функция возвращает (0,0) — «не мерится», а не «не двигалось». Замер: из 54
    связей 10 давали ровно (0,0), и у всех десяти окно насыщено. Проверять
    связи этим значит принимать слепоту метода за подтверждение.

    Возвращает (dx, dy, saturated): последнее поле обязательно, чтобы
    потребитель не мог случайно прочесть ноль как факт.
    """
    d = res["layers"][layer]
    a = d["orig"][edge["i"]]
    surf = L.ref_color(a, d["bg"])
    pad = L.free_pad(a["box"], d["orig"])
    win = (a["box"][0] - pad, a["box"][1] - pad,
           a["box"][2] + pad, a["box"][3] + pad)
    ib = L.ink_bbox(d["arr_o"], win, surf)
    saturated = ib is None or (ib[0] <= win[0] and ib[1] <= win[1]
                               and ib[2] >= win[2] and ib[3] >= win[3])
    sh = L.ink_shift(d["arr_o"], d["arr_r"], a["box"], d["orig"], surf,
                     clip=a.get("clip"),
                     tol=a.get("ink_tol", L.BG_TOL))
    if sh is None:
        return (None, None, saturated)
    return (round(sh[0], 2), round(sh[1], 2), saturated)


def template_shift(res, layer, edge, radius=20, sep=0.15):
    """Независимый замер сдвига: поиск содержимого бокса оригинала в рендере.

    Не зависит ни от насыщения окна замера, ни от границ компонентов, ни от
    детектора — только от пикселей. Возвращает
    (dx, dy, mae_best, mae_at_origin, determined).

    determined = False означает, что величина НЕ определена, и результат нельзя
    использовать ни за, ни против связи. Два независимых источника
    неопределённости, оба измерены:

      * минимум на границе области поиска — настоящий может лежать дальше;
      * САМОПОДОБИЕ. Замер: у подчёркивания 14x3 второй независимый минимум
        (дальше 4px от лучшего) отделён на 2%, у глифа подписи — на 0%, и в
        пределах +10% от минимума лежат 44 и 54 позиции соответственно. У
        объекта с внутренней структурой (буква 13x13) — 1 позиция и отделение
        47%. То есть для ровной полосы и для глифа повторяющегося шрифта
        «лучшая» позиция выбирается шумом, и совпадать с чем-либо она не
        обязана.
    """
    d = res["layers"][layer]
    box = d["orig"][edge["i"]]["box"]
    x0, y0, x1, y1 = box
    tpl = d["arr_o"][y0:y1, x0:x1].astype(float)
    if tpl.size == 0:
        return None
    h, w = d["arr_r"].shape[:2]
    field, at0 = {}, None
    for dy in range(-radius, radius + 1):
        for dx in range(-radius, radius + 1):
            if x0 + dx < 0 or y0 + dy < 0 or x1 + dx > w or y1 + dy > h:
                continue
            cand = d["arr_r"][y0 + dy:y1 + dy, x0 + dx:x1 + dx].astype(float)
            if cand.shape != tpl.shape:
                continue
            m = float(np.abs(tpl - cand).mean())
            field[(dx, dy)] = m
            if dx == 0 and dy == 0:
                at0 = m
    if not field:
        return None
    best = min(field, key=field.get)
    bval = field[best]
    far = {k: v for k, v in field.items()
           if max(abs(k[0] - best[0]), abs(k[1] - best[1])) > 4}
    second = min(far.values()) if far else None
    determined = (abs(best[0]) < radius and abs(best[1]) < radius
                  and second is not None
                  and second >= bval * (1.0 + sep))
    return (best[0], best[1], round(bval, 2),
            None if at0 is None else round(at0, 2), determined)


# --------------------------------------------------------------------------
# 1. ПОРЯДОК ФАЗ на реальном наборе
# --------------------------------------------------------------------------

def phase_order(work):
    head("1. ПОРЯДОК ФАЗ: топология раньше post-pair на КАЖДОМ рендере")
    orig = os.path.join(work, "ORIGINAL.png")
    # СОГЛАШЕНИЕ ОБ ИМЕНАХ. Материал интеграции — только `render_*.png`,
    # то есть КАНДИДАТЫ РЕКОНСТРУКЦИИ. Диагностические рендеры (той же
    # разметки с выключенным слоем — например `*{color:transparent}` для
    # замера площади текста) обязаны называться `diag_*.png`.
    # ЗАМЕР, ПОЧЕМУ ЭТО НЕ КОСМЕТИКА: на рендере с прозрачным текстом у
    # post-pair нет остаточных кандидатов, он даёт 0 связей, и проверка
    # «на чистых рендерах дошёл до графа кандидатов» падает — справедливо,
    # она для этого и написана. Ослаблять её значит научить тест игнорировать
    # ровно тот случай, который он ловит; правильно — не подавать инструмент
    # замера как кандидата.
    rends = sorted(f for f in os.listdir(work)
                   if f.startswith("render_") and f.endswith(".png"))
    if not check("набор рендеров найден", len(rends) >= 6,
                 "рендеров %d" % len(rends)):
        return {}

    results, blocked, clean = {}, [], []
    for f in rends:
        res = chain(orig, os.path.join(work, f))
        results[f] = res
        (clean if res["allowed"] else blocked).append(f)
        tot = sum(len(res["layers"][x]["links"]) for x in res["layers"])
        print("    %-28s %-24s групп %d  связей %d"
              % (f, res["status"], res["topo_groups"], tot))

    check("есть и блокируемые, и чистые рендеры",
          len(blocked) >= 1 and len(clean) >= 5,
          "блок %d / чисто %d" % (len(blocked), len(clean)))

    # блокирующий вердикт => НУЛЬ связей, а не «меньше связей»
    for f in blocked:
        res = results[f]
        tot = sum(len(res["layers"][x]["links"]) for x in res["layers"])
        check("%s: блокер => НУЛЬ связей" % f, tot == 0, "связей %d" % tot)
        for layer in res["layers"]:
            rep = res["layers"][layer]["rep"]
            check("%s/%s: статус NOT_RUN с причиной" % (f, layer),
                  rep["status"] == "NOT_RUN"
                  and res["status"] in rep["reason"],
                  rep.get("reason", ""))

    # на чистых рендерах фаза ДОШЛА до графа кандидатов
    reached = []
    for f in clean:
        res = results[f]
        if any(res["layers"][x]["rep"].get("candidates", 0) > 0
               for x in res["layers"]):
            reached.append(f)
    check("на чистых рендерах post-pair дошёл до графа кандидатов",
          len(reached) == len(clean),
          "%d из %d" % (len(reached), len(clean)))
    return results


# --------------------------------------------------------------------------
# 2. НЕТ ОБХОДА БЛОКЕРА
# --------------------------------------------------------------------------

def no_bypass():
    head("2. ОБХОД БЛОКЕРА: второго пути к post-pair не существует")
    src = {}
    for f in sorted(os.listdir(HERE)):
        if f.endswith(".py"):
            with open(os.path.join(HERE, f)) as fh:
                src[f] = fh.read()

    callers = [f for f, s in src.items()
               if re.search(r"(?<!def )post_pair\s*\(", s)
               and f != "postpair.py"]
    print("    вызывают post_pair: %s" % ", ".join(callers))

    # каждый вызывающий обязан передать статус топологии
    for f in callers:
        if f.startswith("selftest"):
            continue    # тесты подставляют статус намеренно, это их предмет
        has_topo = "post_pair_allowed" in src[f] or "topology_status" in src[f]
        check("%s передаёт вердикт топологии" % f, has_topo)

    # ключевое: аргумент по умолчанию не должен позволять «забыть» топологию
    # молча. Проверяем, что при явно НЕ-CLEAN статусе выход пуст (поведение),
    # и что в единственной production-точке входа статус берётся из topology.
    body = src["postpair.py"]
    m = re.search(r"def post_pair\([^)]*\):(.*?)\n    rid_o", body, re.S)
    check("post_pair проверяет статус ДО любых вычислений",
          m is not None and "topology_status != CLEAN" in m.group(1),
          "проверка найдена" if m else "тело не разобрано")

    order = src["postpaircheck.py"].find("post_pair_allowed")
    call = src["postpaircheck.py"].find("PP.post_pair(")
    check("в CLI топология вызывается раньше post-pair",
          0 < order < call, "поз. %d < %d" % (order, call))


# --------------------------------------------------------------------------
# 3. ОДНА ИСТИНА НА ПОНЯТИЕ
# --------------------------------------------------------------------------

def single_source():
    head("3. ОДНА ИСТИНА: верхние слои не переизобретают нижние границы")
    src = {}
    for f in ("topology.py", "postpair.py", "layout.py", "identity.py"):
        with open(os.path.join(HERE, f)) as fh:
            src[f] = fh.read()

    # min_area: читается из сигнатуры detect_nested, а не скопирован
    ma = T.nested_min_area()
    sig = re.search(r"def detect_nested\([^)]*min_area\s*=\s*(\d+)",
                    src["layout.py"], re.S)
    check("min_area topology == min_area detect_nested",
          sig is not None and ma == int(sig.group(1)),
          "%s против %s" % (ma, sig.group(1) if sig else "?"))
    check("min_area не продублирован литералом в topology",
          not re.search(r"min_area\s*=\s*\d+", src["topology.py"]),
          "литерального присваивания нет")

    # допуск идентичности: берётся из identity._match_key
    tol = T.child_tolerance()
    key = re.search(r"def _match_key\(([^)]*)\)", src["identity.py"])
    lit = re.search(r"tolerance\s*=\s*(\d+)", key.group(1)) if key else None
    check("допуск идентичности == default identity._match_key",
          lit is not None and tol == int(lit.group(1)),
          "topology tol=%s, identity default=%s"
          % (tol, lit.group(1) if lit else "?"))
    check("допуск не продублирован литералом в topology",
          not re.search(r"tolerance\s*=\s*\d+", src["topology.py"]),
          "литерального присваивания нет")

    # отношение соседства строки: одно на систему
    peers_pp = re.search(r"def _peers\(([^)]*)\)", src["postpair.py"])
    check("post-pair не заводит свой порог по координате строки",
          not re.search(r"top\s*//\s*\d+|//\s*4", src["postpair.py"]),
          "квантования координаты нет")
    check("run_id строится замыканием, а не сеткой",
          "_peers" in src["postpair.py"] and "parent[" in src["postpair.py"],
          "объединение по отношению соседства")
    print("    _peers(%s)" % (peers_pp.group(1) if peers_pp else "?"))


# --------------------------------------------------------------------------
# 4. НАПРАВЛЕНИЕ ЗАВИСИМОСТЕЙ
# --------------------------------------------------------------------------

LOWER = ("metrics.py", "layout.py", "identity.py", "history.py",
         "baseline_matcher.py")
UPPER = ("topology", "postpair", "topocheck", "postpaircheck")


def dep_direction():
    head("4. ЗАВИСИМОСТИ: нижние слои не знают о верхних")
    with open(os.path.join(HERE, "postpair.py")) as fh:
        pp = fh.read()
    for f in LOWER:
        p = os.path.join(HERE, f)
        if not os.path.exists(p):
            continue
        with open(p) as fh:
            body = fh.read()
        bad = [u for u in UPPER
               if re.search(r"^\s*(import|from)\s+%s\b" % u, body, re.M)]
        check("%s не импортирует верхние слои" % f, not bad, str(bad))

    # обратное направление обязано существовать, иначе слои не сцеплены
    check("postpair не копирует нижние слои, а получает их извне",
          "def post_pair(" in pp and "identity_cost" in pp
          and "in_text_run" in pp and "import layout" not in pp,
          "нижние функции приходят параметрами")

    # Резолв импортов: только от собственного файла. Замер, почему это часть
    # контракта, а не стиль: baseline_matcher и trace_pair имели литерал
    # /var/minis/shared/uicopy-plus, и копия дерева с ИСПОРЧЕННЫМ layout
    # (iou_min 0.30 -> 0.99) прогонялась через оригинальные модули — снимок
    # выходил побайтно идентичным, то есть отрицательный контроль молча не
    # работал и подтверждал целостность вместо её проверки.
    hard = []
    for f in sorted(os.listdir(HERE)):
        if not f.endswith(".py"):
            continue
        with open(os.path.join(HERE, f)) as fh:
            body = fh.read()
        if re.search(r"sys\.path\.insert\(\s*0\s*,\s*['\"]/", body):
            hard.append(f)
    check("ни один модуль не резолвит импорты абсолютным литералом пути",
          not hard, str(hard))

    # Конвейер остатков: единственная реализация — layout.explaining_phases.
    # Замер, почему это часть контракта: две копии уже разошлись с ним
    # молча. postpaircheck/integration вычитали claims без absorb_fragments
    # (20 остатков против 9 на верхнем слое), а baseline_matcher считал
    # объяснённость по двум фазам из трёх и называл необъяснёнными 33 объекта
    # на 9 рендерах, которые полный конвейер объясняет.
    rebuilt = []
    for f in sorted(os.listdir(HERE)):
        if not f.endswith(".py") or f in ("layout.py", "trace_pair.py"):
            continue
        with open(os.path.join(HERE, f)) as fh:
            body = fh.read()
        if f.startswith("selftest"):
            continue        # тесты вызывают фазы поштучно, это их предмет
        calls_gm = re.search(r"\bL\.group_match\s*\(|\blayout\.group_match\s*\(",
                             body)
        uses_ph = "explaining_phases" in body
        if calls_gm and not uses_ph:
            rebuilt.append(f)
    check("никто не собирает остатки в обход explaining_phases",
          not rebuilt, str(rebuilt))


# --------------------------------------------------------------------------
# 5. BASELINE MATCHER на всём наборе
# --------------------------------------------------------------------------

def baseline(work):
    head("5. BASELINE MATCHER: пары и фрагменты не изменились")
    store = os.path.join(HERE, "baselines")
    os.makedirs(store, exist_ok=True)
    orig = os.path.join(work, "ORIGINAL.png")
    rends = sorted(f for f in os.listdir(work)
                   if f.startswith("render_") and f.endswith(".png"))

    created, same, diff = [], [], []
    for f in rends:
        ref = os.path.join(store, f.replace(".png", ".json"))
        cur = "/tmp/bl_cur.json"
        subprocess.run([sys.executable,
                        os.path.join(HERE, "baseline_matcher.py"),
                        orig, os.path.join(work, f), cur],
                       capture_output=True, check=True)
        with open(cur) as fh:
            snap = fh.read()
        if not os.path.exists(ref):
            with open(ref, "w") as fh:
                fh.write(snap)
            created.append(f)
            continue
        with open(ref) as fh:
            old = fh.read()
        (same if old == snap else diff).append(f)

    if created:
        print("    эталон создан впервые: %d рендеров" % len(created))
    check("эталон matcher совпадает побайтово на всех рендерах",
          not diff, "разошлись: %s" % (diff or "нет"))
    check("сверено рендеров", len(same) + len(created) == len(rends),
          "%d из %d" % (len(same) + len(created), len(rends)))


# --------------------------------------------------------------------------
# 6. ВОСПРОИЗВОДИМОСТЬ
# --------------------------------------------------------------------------

def reproducible(work, results):
    head("6. ВОСПРОИЗВОДИМОСТЬ: повторный прогон даёт тот же результат")
    orig = os.path.join(work, "ORIGINAL.png")
    for f in sorted(results)[:3]:
        a = results[f]
        b = chain(orig, os.path.join(work, f))
        sig_a = [(x, a["layers"][x]["rep"]["links"],
                  sorted((e["i"], e["j"]) for e in a["layers"][x]["links"]))
                 for x in sorted(a["layers"])]
        sig_b = [(x, b["layers"][x]["rep"]["links"],
                  sorted((e["i"], e["j"]) for e in b["layers"][x]["links"]))
                 for x in sorted(b["layers"])]
        check("%s: тот же вердикт и те же связи" % f,
              a["status"] == b["status"] and sig_a == sig_b)


# --------------------------------------------------------------------------
# 7. СВЯЗИ ПОДТВЕРЖДАЮТСЯ НЕЗАВИСИМЫМ ЗАМЕРОМ
# --------------------------------------------------------------------------

def links_are_real(results):
    head("7. СВЯЗИ: проверка НЕЗАВИСИМЫМ замером (поиск шаблона)")
    print("    Проверяются только связи, для которых замер ОПРЕДЕЛЁН:")
    print("    у самоподобного объекта (ровная полоса, глиф) минимум MAE")
    print("    выбирается шумом — второй независимый минимум отделён на 0-2%.")
    total = agree = stands = diverge = undef = 0
    for f, res in sorted(results.items()):
        if not res["allowed"]:
            continue
        for layer in res["layers"]:
            d = res["layers"][layer]
            for e in d["links"]:
                total += 1
                a, b = d["orig"][e["i"]], d["rend"][e["j"]]
                dxc, dyc = b["cx"] - a["cx"], b["cy"] - a["cy"]
                t = template_shift(res, layer, e)
                if t is None or not t[4]:
                    undef += 1
                    continue
                tdx, tdy, mb, m0, _det = t
                if abs(tdx - dxc) <= 2.0 and abs(tdy - dyc) <= 2.0:
                    agree += 1
                    continue
                if m0 is not None and m0 <= mb + 1.0:
                    stands += 1
                    kind = "СТОИТ НА МЕСТЕ -> связь ложная"
                else:
                    diverge += 1
                    kind = "РАСХОДИТСЯ шаблон=(%+d,%+d) MAE %.1f->%.1f" % (
                        tdx, tdy, -1 if m0 is None else m0, mb)
                print("    %-26s %-7s %s->%s связь=(%+.1f,%+.1f) %s"
                      % (f, layer, a["name"].split("_")[0],
                         b["name"].split("_")[0], dxc, dyc, kind))
    print("    связей %d: замер определён у %d — согласуется %d, "
          "стоит на месте %d, расходится %d"
          % (total, total - undef, agree, stands, diverge))
    check("нет связей на объектах, которые стоят на месте", stands == 0,
          "ложных %d" % stands)
    check("все связи с ОПРЕДЕЛЁННЫМ замером согласуются с ним",
          diverge == 0, "расходится %d" % diverge)
    check("замер определён хотя бы у части связей", total - undef > 0,
          "определено %d из %d" % (total - undef, total))


def pollution(results):
    head("8. ЗАГРЯЗНЕНИЕ ОСТАТКОВ: почему блокер необходим, а не осторожен")
    for f, res in sorted(results.items()):
        d = res["layers"]["nested"]
        dup = res["dup_parents"]
        inside = [j for j in d["spurious"]
                  if d["rend"][j].get("parent") in dup]
        frac = 100.0 * len(inside) / max(1, len(d["spurious"]))
        print("    %-28s %-24s остатков %3d, в копиях %3d (%.0f%%)"
              % (f, res["status"], len(d["spurious"]), len(inside), frac))
        if res["allowed"]:
            check("%s: чисто => остатков в копиях нет" % f, not inside,
                  "в копиях %d" % len(inside))
        else:
            check("%s: блокировано => остатки загрязнены копиями" % f,
                  frac >= 50.0, "%.0f%%" % frac)


def residual_input(results):
    head("9. ВХОД POST-PAIR: остатки, а не сырой выход match")
    print("    Признак наблюдаемый: связь на объекте рендера, который уже")
    print("    объяснён фазой frag/merg/absorb. На остатках таких нет; на")
    print("    сыром выходе match замер даёт по одной на каждом рендере.")
    print("    Множество объяснённых объектов считается ЗДЕСЬ, независимо от")
    print("    того, что chain положил в результат: иначе подмена входа")
    print("    убирала бы вместе с входом и сам признак.")
    bad_total = 0
    for f, res in sorted(results.items()):
        if not res["allowed"]:
            continue
        for layer in res["layers"]:
            d = res["layers"][layer]
            ph = L.explaining_phases(d["orig"], d["rend"])
            claimed_r = (set(j for _, g, _ in ph["frag"] for j in g)
                         | set(j for j, _, _ in ph["merg"])
                         | set(j for _, g, _ in ph["absorbed"] for j in g))
            bad = [e for e in d["links"] if e["j"] in claimed_r]
            bad_total += len(bad)
            for e in bad:
                print("    %-26s %-7s связь на объяснённом объекте %s"
                      % (f, layer, d["rend"][e["j"]]["name"]))
    check("ни одна связь не построена на уже объяснённом объекте",
          bad_total == 0, "таких связей %d" % bad_total)


def main():
    work = sys.argv[1] if len(sys.argv) > 1 else "/tmp/uicopy-test"
    print("ИНТЕГРАЦИОННЫЙ КОНТРАКТ ЦЕПОЧКИ  (материал: %s)" % work)
    results = phase_order(work)
    no_bypass()
    single_source()
    dep_direction()
    baseline(work)
    if results:
        reproducible(work, results)
        links_are_real(results)
        pollution(results)
        residual_input(results)

    print()
    print("=" * 74)
    if FAILS:
        print("ПРОВАЛЕНО %d из %d: %s" % (len(FAILS), CHECKS[0], FAILS))
        return 1
    print("ИНТЕГРАЦИОННЫЙ КОНТРАКТ ПРОЙДЕН: %d проверок" % CHECKS[0])
    return 0


if __name__ == "__main__":
    sys.exit(main())
