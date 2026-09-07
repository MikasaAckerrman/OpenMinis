#!/usr/bin/env python3
"""history.py — история итераций и обнаружение регресса. Чистые функции.

ЗАЧЕМ. Каждый прогон diff/residual/layout сейчас живёт сам по себе: числа
печатаются в stdout и исчезают. Значит «починил одно, сломал другое»
обнаруживается только если человек помнит прошлое число наизусть. В
ui-agent-loop (MIT, (c) 2026 Marvin Seiferling) для этого есть history.jsonl и
отслеживание лучшего результата — идея взята оттуда, реализация своя.

ПОЧЕМУ НЕТ ЕДИНОГО «SCORE». У аналогов один процент совпавших пикселей. Наш
метод специально разделяет GLYPH-FORM (недоступный шрифт — внешнее
ограничение) и RECONSTRUCTION (то, что механизм обязан устранить), а также
расположение и цвет. Свернуть это в одно число значит вернуть ровно ту
путаницу, ради устранения которой разделение и делалось: композит скрыл бы,
ЧТО именно ухудшилось. Поэтому регресс считается по каждой метрике отдельно,
и вердикт — список ухудшившихся, а не одна цифра.
"""
import json
import os
import time

# Метрики, которые отслеживаются, и в какую сторону лучше.
# "down" — чем меньше, тем лучше; "up" — чем больше.
#
# Порог — минимальное изменение, которое считается значимым. Не ноль, потому
# что рендер не строго детерминирован: сглаживание текста может дать разницу
# в единицы пикселей на идентичном HTML, и без порога каждый прогон
# рапортовал бы «регресс» на шуме.
METRICS = {
    "recon_px_over_jnd": ("down", 8, "RECONSTRUCTION px выше порога заметности"),
    "recon_mae": ("down", 0.01, "RECONSTRUCTION MAE"),
    "ssim": ("up", 0.0005, "SSIM (структурное сходство)"),
    "layout_defects": ("down", 1, "замечаний по расположению"),
    "layout_missing": ("down", 1, "пропущенных элементов"),
    "layout_spurious": ("down", 1, "лишних элементов"),
    "nested_defects": ("down", 1, "замечаний внутри элементов"),
    "mae_nonbg": ("down", 0.01, "MAE по не-фону"),
}


def load(path):
    """Прочитать history.jsonl. Битые строки пропускаются, не роняют разбор.

    Одна испорченная строка (обрыв записи) не должна лишать доступа ко всей
    истории — это ровно тот случай, когда «строгий разбор» стоит дороже, чем
    потеря одной записи.
    """
    if not os.path.exists(path):
        return []
    out = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                out.append(json.loads(line))
            except ValueError:
                continue
    return out


def best_values(history):
    """Лучшее значение каждой метрики за всю историю -> {имя: (значение, итерация)}."""
    best = {}
    for h in history:
        it = h.get("iteration")
        for k, (direction, _tol, _label) in METRICS.items():
            v = h.get(k)
            if v is None:
                continue
            if k not in best:
                best[k] = (v, it)
                continue
            cur = best[k][0]
            better = v < cur if direction == "down" else v > cur
            if better:
                best[k] = (v, it)
    return best


def compare(history, entry):
    """Сравнить новую запись с предыдущей и с лучшей.

    Возвращает {"vs_prev": [...], "vs_best": [...], "improved": [...]},
    где элемент — dict с name, label, from, to, delta.

    Сравнение с ЛУЧШЕЙ, а не только с предыдущей: цепочка мелких ухудшений, ни
    одно из которых не превышает порог по отдельности, иначе проходит
    незамеченной. Именно так «слегка подправил» превращается в откат на пять
    итераций назад.
    """
    prev = history[-1] if history else None
    best = best_values(history)

    def diff_against(ref_get, tag):
        worse, better = [], []
        for k, (direction, tol, label) in METRICS.items():
            new = entry.get(k)
            old = ref_get(k)
            if new is None or old is None:
                continue
            delta = new - old
            if abs(delta) < tol:
                continue
            is_worse = delta > 0 if direction == "down" else delta < 0
            rec = {"name": k, "label": label, "from": old, "to": new,
                   "delta": delta}
            (worse if is_worse else better).append(rec)
        return worse, better

    worse_prev, better_prev = ([], [])
    if prev is not None:
        worse_prev, better_prev = diff_against(lambda k: prev.get(k), "prev")

    worse_best, _ = ([], [])
    if best:
        worse_best, _ = diff_against(
            lambda k: best[k][0] if k in best else None, "best")

    return {
        "vs_prev": worse_prev,
        "vs_best": worse_best,
        "improved": better_prev,
        "best": {k: {"value": v, "iteration": it} for k, (v, it) in best.items()},
        "prev_iteration": prev.get("iteration") if prev else None,
    }


def append(path, entry):
    """Дописать запись. Только append: прошлые итерации не переписываются.

    История, которую можно переписать, не улика. Тот же принцип, что у
    MutationJournal в приложении.
    """
    with open(path, "a") as f:
        f.write(json.dumps(entry, ensure_ascii=False) + "\n")


def build_entry(history, render_name, metrics):
    """Собрать запись: номер итерации, время, имя рендера, метрики."""
    entry = {
        "iteration": len(history) + 1,
        "ts": time.strftime("%Y-%m-%d %H:%M:%S"),
        "render": render_name,
    }
    for k in METRICS:
        if k in metrics and metrics[k] is not None:
            entry[k] = metrics[k]
    return entry


def format_report(cmp_result, entry):
    """Человекочитаемый вердикт. Возвращает список строк."""
    lines = []
    it = entry.get("iteration")
    prev = cmp_result.get("prev_iteration")
    if prev is None:
        lines.append("итерация %d — первая, сравнивать не с чем" % it)
    else:
        lines.append("итерация %d (предыдущая %d)" % (it, prev))

    if cmp_result["vs_prev"]:
        lines.append("")
        lines.append("!! РЕГРЕСС относительно предыдущей итерации:")
        for r in cmp_result["vs_prev"]:
            lines.append("     %-46s %s -> %s (%+g)"
                         % (r["label"], _fmt(r["from"]), _fmt(r["to"]),
                            r["delta"]))

    only_best = [r for r in cmp_result["vs_best"]
                 if r["name"] not in {x["name"] for x in cmp_result["vs_prev"]}]
    if only_best:
        lines.append("")
        lines.append("!! ХУЖЕ ЛУЧШЕГО РЕЗУЛЬТАТА (но не хуже предыдущего —")
        lines.append("   значит ухудшение накопилось за несколько итераций):")
        for r in only_best:
            lines.append("     %-46s лучшее %s, сейчас %s"
                         % (r["label"], _fmt(r["from"]), _fmt(r["to"])))

    if cmp_result["improved"]:
        lines.append("")
        lines.append("улучшилось:")
        for r in cmp_result["improved"]:
            lines.append("     %-46s %s -> %s"
                         % (r["label"], _fmt(r["from"]), _fmt(r["to"])))

    if prev is not None and not cmp_result["vs_prev"] and not only_best \
            and not cmp_result["improved"]:
        lines.append("без значимых изменений (все сдвиги ниже порога шума)")

    return lines


def _fmt(v):
    if isinstance(v, float):
        return "%.4f" % v if abs(v) < 10 else "%.2f" % v
    return str(v)
