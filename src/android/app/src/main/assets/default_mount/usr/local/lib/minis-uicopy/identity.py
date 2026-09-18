#!/usr/bin/env python3
"""identity.py — stable ORIGINAL-side object identity.

The ORIGINAL raster is immutable.  Its detected components receive persistent
IDs; render components remain ephemeral and are related to those IDs through
the existing layout matcher.  This module deliberately does not implement a
second matcher.
"""
import hashlib
import json
import os
import sys
import tempfile

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import layout as L  # noqa: E402
import metrics as M  # noqa: E402

SCHEMA_VERSION = 1

# Реестр измеренных ограничений matcher, привязанный к конкретному ORIGINAL
# (охраняется sha256, поэтому не может молча переехать на другой скриншот).
#
# ПУСТ ОСОЗНАННО. Здесь лежали ("top",(138,263,175,274)) и
# ("top",(138,399,175,410)) — e33/e47 — с причиной «area 407 > threshold 400,
# in_text_run=True при is_glyph_fragment=False». Замер продакшн-пути
# (`analyze`, не `match`+`group_match`) показал, что утверждение неверно:
# у `analyze` есть третья фаза `overlap_groups`, и оба объекта объясняются как
# `split` (по 2 компонента рендера каждый). TOP объяснён 67/67, дефектов ноль.
# Прежний вывод «2 необъяснённых» был артефактом измерения — я считал
# объяснённость по двум фазам из трёх.
#
# Гипотеза «in_text_run должен побеждать is_glyph_fragment» ОПРОВЕРГНУТА
# экспериментом, а не отложена: подмена обеих функций в рантайме оставила
# список необъяснённых идентичным (top 2->2, nested 15->15 по двухфазному
# счёту). Реальная причина отказа `group_match` другая — union кандидатов
# 24x11 против бокса 37x11, |dw|=13 > 9.2, потому что третий фрагмент лежит
# левее бокса (containment=0.00).
#
# Запись сюда допустима ТОЛЬКО при выполнении всех четырёх условий:
#   1. конкретный объект (layer + box на этом ORIGINAL);
#   2. конкретная версия detector/matcher, на которой замер сделан;
#   3. воспроизводимая причина отказа с числами;
#   4. подтверждение, что продакшн-путь `analyze` объект НЕ объясняет —
#      то есть он попадает в дефекты (missing/spurious/shifted/...),
#      а не в раздел «разная связность, расположение верное».
# Без пункта 4 пометка описывает несуществующее ограничение и лжёт в каталоге,
# который объявлен источником правды.
KNOWN_LIMITATIONS_ORIGINAL_SHA256 = "9383d6d651b4d9e31af7098b77b25afcc8c9b13ff50db67ae1a05d79ba1c44f1"
KNOWN_LIMITATIONS = {}


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def _rgb(v):
    return [int(x) for x in v]


def _object_record(c, object_id, layer, parent_id=None, status="canonical",
                   reason=None):
    x0, y0, x1, y1 = [int(v) for v in c["box"]]
    rec = {
        "layer": layer,
        "box": [x0, y0, x1, y1],
        "w": int(c["w"]),
        "h": int(c["h"]),
        "pixels": int(c["pixels"]),
        "cx": float(c["cx"]),
        "cy": float(c["cy"]),
        "dom": _rgb(c["dom"]),
        "dom_frac": float(c["dom_frac"]),
        "fill": float(c["fill"]),
        "thin": bool(c["thin"]),
        "standalone": bool(c["standalone"]),
        "status": status,
    }
    if layer == "nested":
        rec["surf"] = _rgb(c["surf"])
        rec["ink_tol"] = int(c["ink_tol"])
        rec["parent_id"] = parent_id
    if status == "known_limitation":
        rec["reason"] = reason or {}
    return rec


def _key(layer, box):
    return (layer, tuple(int(v) for v in box))


def _match_key(key, keys, tolerance=2):
    layer, box = key
    exact = [k for k in keys if k[0] == layer and k[1] == box]
    if len(exact) == 1:
        return exact[0]
    candidates = []
    x0, y0, x1, y1 = box
    for k in keys:
        if k[0] != layer:
            continue
        a, b, c, d = k[1]
        if (abs(a - x0) <= tolerance and abs(b - y0) <= tolerance and
                abs(c - x1) <= tolerance and abs(d - y1) <= tolerance):
            candidates.append(k)
    if len(candidates) == 1:
        return candidates[0]
    if not candidates:
        return None
    raise ValueError("ambiguous identity key %r: %r" % (key, candidates))


def build_catalog(original_path):
    """Build immutable ORIGINAL-side catalog from the raster only."""
    original_path = os.path.abspath(original_path)
    im = Image.open(original_path).convert("RGB")
    arr = np.asarray(im)
    bg, bg_frac = M.dominant_color(arr)
    original_hash = sha256_file(original_path)
    top = L.detect(arr, bg=bg)
    nested = L.detect_nested(arr, top, bg)

    # Persistent IDs are assigned after sorting by layer and canonical bbox.
    all_objs = [("top", c) for c in top] + [("nested", c) for c in nested]
    all_objs.sort(key=lambda p: (p[0], p[1]["box"][1], p[1]["box"][0],
                                 p[1]["box"][3], p[1]["box"][2]))

    ids = {}
    for n, (layer, c) in enumerate(all_objs, 1):
        ids[_key(layer, c["box"])] = "O%04d" % n

    objects = {}
    for layer, c in all_objs:
        key = _key(layer, c["box"])
        object_id = ids[key]
        status_info = (KNOWN_LIMITATIONS.get(key)
                       if original_hash == KNOWN_LIMITATIONS_ORIGINAL_SHA256 else None)
        parent_id = None
        if layer == "nested":
            parent = c.get("parent")
            if parent:
                parent_comp = next((x for x in top if x.get("name") == parent), None)
                if parent_comp is None:
                    raise ValueError("nested parent not found: %s" % parent)
                parent_key = _key("top", parent_comp["box"])
                parent_id = ids[parent_key]
        objects[object_id] = _object_record(
            c, object_id, layer, parent_id=parent_id,
            status="known_limitation" if status_info else "canonical",
            reason=status_info["reason"] if status_info else None,
        )

    return {
        "schema_version": SCHEMA_VERSION,
        "original": {
            "file": os.path.basename(original_path),
            "sha256": original_hash,
            "w": int(im.width),
            "h": int(im.height),
        },
        "detector": {
            "bg": _rgb(bg),
            "bg_frac": float(bg_frac),
            "BG_TOL": int(L.BG_TOL),
            "NESTED_TOL": int(L.NESTED_TOL),
            "MIN_AREA": int(L.MIN_AREA),
            "nested_min_area": 3000,
            "nested_min_obj": 20,
            "nested_border": 4,
            "nested_max_frac": 0.6,
            "tool_version": "identity-v1",
        },
        "limitations_fingerprint": limitations_fingerprint(),
        "objects": objects,
    }


def write_atomic(path, data):
    directory = os.path.dirname(os.path.abspath(path))
    os.makedirs(directory, exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix=".catalog-", suffix=".tmp", dir=directory)
    try:
        with os.fdopen(fd, "w") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
            f.write("\n")
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


def limitations_fingerprint():
    """Отпечаток реестра ограничений + порогов детектора.

    Записывается в каталог и сверяется при verify. Без него каталог на диске
    может противоречить коду и проходить проверку: именно так ложные пометки
    e33/e47 пережили удаление из реестра — `catalog` отказывался пересобирать,
    а `verify` смотрел только sha/размеры/schema.
    """
    payload = {
        "guard_sha256": KNOWN_LIMITATIONS_ORIGINAL_SHA256,
        "keys": sorted([[k[0], list(k[1]), v.get("reason", {})]
                        for k, v in KNOWN_LIMITATIONS.items()],
                       key=lambda x: json.dumps(x, sort_keys=True)),
        "detector": {
            "BG_TOL": int(L.BG_TOL),
            "NESTED_TOL": int(getattr(L, "NESTED_TOL", -1)),
            "MIN_AREA": int(getattr(L, "MIN_AREA", -1)),
            "STANDALONE_AREA": int(getattr(L, "STANDALONE_AREA", -1)),
            "STANDALONE_SIDE": int(getattr(L, "STANDALONE_SIDE", -1)),
            "THIN_PX": int(getattr(L, "THIN_PX", -1)),
        },
    }
    blob = json.dumps(payload, sort_keys=True, ensure_ascii=False).encode()
    return hashlib.sha256(blob).hexdigest()


def verify_catalog(catalog, original_path):
    original_path = os.path.abspath(original_path)
    im = Image.open(original_path).convert("RGB")
    got = sha256_file(original_path)
    ref = catalog.get("original", {})
    if got != ref.get("sha256"):
        raise ValueError("catalog ORIGINAL sha256 mismatch")
    if [im.width, im.height] != [ref.get("w"), ref.get("h")]:
        raise ValueError("catalog ORIGINAL dimensions mismatch")
    if catalog.get("schema_version") != SCHEMA_VERSION:
        raise ValueError("unsupported catalog schema_version")
    stored = catalog.get("limitations_fingerprint")
    current = limitations_fingerprint()
    if stored is None:
        raise ValueError(
            "catalog has no limitations_fingerprint: it was built before this "
            "check existed and may carry stale known_limitation marks; rebuild "
            "with 'identity.py build --rebuild'")
    if stored != current:
        raise ValueError(
            "catalog was built under a different limitations/detector config "
            "(stored %s, current %s); its known_limitation marks and boxes may "
            "be stale. Rebuild with 'identity.py build --rebuild'"
            % (stored[:16], current[:16]))
    return True


def main():
    args = sys.argv[1:]
    rebuild = "--rebuild" in args
    args = [a for a in args if a != "--rebuild"]
    if not args or args[0] not in ("build", "verify"):
        raise SystemExit("usage: identity.py build [original] [--rebuild] | "
                         "verify [catalog] [original]")
    op = args[0]
    original = os.path.abspath(args[1] if len(args) > 1 else "ORIGINAL.png")
    catalog_path = os.path.join(os.path.dirname(original), "catalog.json")
    if op == "verify":
        catalog_path = os.path.abspath(args[1]) if len(args) > 1 else catalog_path
        original = os.path.abspath(args[2]) if len(args) > 2 else original
        with open(catalog_path) as f:
            cat = json.load(f)
        verify_catalog(cat, original)
        print("catalog OK: %s" % catalog_path)
        return
    if os.path.exists(catalog_path) and not rebuild:
        with open(catalog_path) as f:
            old = json.load(f)
        # verify_catalog теперь сверяет и отпечаток реестра/порогов, поэтому
        # устаревший каталог здесь ПАДАЕТ с указанием на --rebuild, а не молча
        # объявляется актуальным.
        verify_catalog(old, original)
        raise SystemExit("catalog already exists and matches ORIGINAL; "
                         "refusing to rebuild (use --rebuild to force)")
    cat = build_catalog(original)
    write_atomic(catalog_path, cat)
    print("catalog written: %s (%d objects, %d known_limitation)"
          % (catalog_path, len(cat["objects"]),
             sum(1 for v in cat["objects"].values()
                 if v.get("status") == "known_limitation")))


if __name__ == "__main__":
    main()
