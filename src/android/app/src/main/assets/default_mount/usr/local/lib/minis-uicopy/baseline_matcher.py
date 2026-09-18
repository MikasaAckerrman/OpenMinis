#!/usr/bin/env python3
"""baseline_matcher.py — полный МАШИННЫЙ снимок поведения matcher.

ЗАЧЕМ. Перед изолированной правкой matching нужен baseline, с которым можно
сравнить результат построчно, а не «на глаз». Снимок фиксирует не агрегаты
(3 замечания / 65 объяснено), а КАЖДУЮ пару и КАЖДЫЙ вердикт: правка, которая
сохранит счётчики и при этом переставит пары, иначе прошла бы незамеченной.

Пишет baseline_matcher.json: пары O->R, группы, вердикты, объяснённость.
"""
import json
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import layout as L          # noqa: E402
import metrics as M         # noqa: E402


def snapshot(orig_path, rend_path):
    oa = np.asarray(Image.open(orig_path).convert("RGB"))
    ra = np.asarray(Image.open(rend_path).convert("RGB"))
    bg, bg_frac = M.dominant_color(oa)

    out = {"original": os.path.basename(orig_path),
           "render": os.path.basename(rend_path),
           "bg": list(bg), "layers": {}}

    oc = L.detect(oa, bg=bg)
    rc = L.detect(ra, bg=bg)
    out["layers"]["top"] = layer_snapshot(oc, rc, oa, ra, bg)

    no = L.detect_nested(oa, oc, bg)
    nr = L.detect_nested(ra, rc, bg)
    out["layers"]["nested"] = layer_snapshot(no, nr, oa, ra, bg)
    return out


def layer_snapshot(oc, rc, oa, ra, bg):
    # Фазы берутся из ЕДИНОГО конвейера, а не вычисляются здесь заново.
    # Замер, почему это не косметика: снимок считал объяснённость по двум
    # фазам из трёх (pairs + frag/merg, без absorb и overlap_groups) и на
    # наборе из 9 рендеров называл необъяснёнными 33 объекта, которые полный
    # конвейер объясняет. Та же ошибка измерения уже опровергнута в
    # identity.py: «прежний вывод 2 необъяснённых был артефактом измерения».
    ph = L.explaining_phases(oc, rc)
    pairs, missing, spurious = ph["pairs"], ph["missing"], ph["spurious"]
    frag, merg = ph["frag"], ph["merg"]
    res = L.analyze(oc, rc, orig_arr=oa, rend_arr=ra, bg=bg)

    # Пары фиксируем по КООРДИНАТАМ, не по индексам: индекс зависит от порядка
    # обхода детектора, координата — свойство растра.
    def box(c):
        return list(c["box"])

    pair_list = sorted(
        [{"orig": box(oc[i]), "rend": box(rc[j]),
          "orig_name": oc[i]["name"], "rend_name": rc[j]["name"],
          "how": how} for i, j, how in pairs],
        key=lambda p: (p["orig"][1], p["orig"][0]))

    frag_list = sorted(
        [{"orig": box(oc[i]), "rend_group": sorted(box(rc[j]) for j in grp),
          "n": len(grp)} for i, grp, _u in frag],
        key=lambda p: (p["orig"][1], p["orig"][0]))

    merg_list = sorted(
        [{"rend": box(rc[j]), "orig_group": sorted(box(oc[i]) for i in grp),
          "n": len(grp)} for j, grp, _u in merg],
        key=lambda p: (p["rend"][1], p["rend"][0]))

    # Объяснённость по ВСЕМ фазам, включая absorb (3b) и overlap_groups (4).
    # Замер расхождения с двухфазным счётом: 33 объекта на 9 рендерах.
    split, merged = L.overlap_groups(oc, rc, missing, spurious)
    explained = (set(i for i, _, _ in pairs)
                 | set(i for i, _, _ in frag)
                 | set(i for _, grp, _ in merg for i in grp)
                 | set(i for i, _, _ in ph["absorbed"])
                 | set(i for i, _ in split)
                 | set(i for _, hits in merged for i in hits))
    unexplained = sorted(
        [{"box": box(oc[i]), "name": oc[i]["name"],
          "is_glyph_fragment": bool(L.is_glyph_fragment(oc[i], oc))
          if hasattr(L, "is_glyph_fragment") else None,
          "in_text_run": bool(L.in_text_run(oc[i], oc))
          if hasattr(L, "in_text_run") else None}
         for i in range(len(oc)) if i not in explained],
        key=lambda p: (p["box"][1], p["box"][0]))

    def verdicts(key):
        # Вердикты неоднородны по типу: часть — dict, часть — строки/имена.
        # Приводить силой нельзя, иначе снимок потеряет часть данных.
        items = res.get(key, [])
        norm = []
        for it in items:
            if isinstance(it, dict):
                norm.append({k: (round(v, 3) if isinstance(v, float) else v)
                             for k, v in it.items() if k != "idx"})
            else:
                norm.append(it)
        return sorted(norm, key=lambda d: json.dumps(d, sort_keys=True,
                                                     ensure_ascii=False))

    return {
        "counts": {"orig": len(oc), "rend": len(rc),
                   "pairs": len(pairs), "frag": len(frag), "merg": len(merg),
                   "explained": len(explained),
                   "unexplained": len(unexplained)},
        "pairs": pair_list,
        "frag": frag_list,
        "merg": merg_list,
        "unexplained": unexplained,
        "verdicts": {k: verdicts(k) for k in
                     ("missing", "spurious", "shifted", "resized",
                      "guides", "rhythm", "order")},
        "analyze_counts": res.get("counts", {}),
    }


def main():
    if len(sys.argv) < 3:
        sys.exit("usage: baseline_matcher.py <ORIGINAL.png> <render.png> [out.json]")
    orig, rend = sys.argv[1], sys.argv[2]
    out = sys.argv[3] if len(sys.argv) > 3 else "baseline_matcher.json"
    snap = snapshot(orig, rend)
    with open(out, "w") as f:
        json.dump(snap, f, ensure_ascii=False, indent=1, sort_keys=True)
    for layer in ("top", "nested"):
        c = snap["layers"][layer]["counts"]
        print("%-7s ориг %3d ренд %3d | пар %3d frag %2d merg %2d | "
              "объяснено %3d не объяснено %d"
              % (layer, c["orig"], c["rend"], c["pairs"], c["frag"], c["merg"],
                 c["explained"], c["unexplained"]))
    print("-> %s" % out)


if __name__ == "__main__":
    main()
