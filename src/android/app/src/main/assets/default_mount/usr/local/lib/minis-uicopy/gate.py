#!/usr/bin/env python3
"""gate.py — transactional candidate gate for UI Copy.

The gate compares a candidate only with ACTIVE.  Best is computed only from
accepted, comparable, non-forced states.  It never edits ACTIVE until ACCEPT.
"""
import argparse
import hashlib
import json
import os
import shutil
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import history as H  # noqa: E402

GATE_SCHEMA_VERSION = 1

# ЕДИНСТВЕННЫЙ источник структурных порогов. structure_checks() обязана читать
# только отсюда: вторая копия чисел в теле функции означает, что правка словаря
# не меняет поведение, а читающий думает, что изменил gate.
# Значения выведены замером на 18 версиях (9 здоровых, 9 дефектных):
#   top    — здоровые дают до +4.3% (v18), дефекты начинаются с -7.7%
#   growth — девять здоровых дали РОВНО 0% роста вложенных
#   fall   — легитимный трекинг 3.5px даёт -1.1%, поэтому допуск 2%
#   ratio  — здоровые x1.230, D31 x2.72, разрыв рамки x4.55: зазор двукратный
STRUCTURE = {
    "top_objects_render": {"mode": "relative", "tol": 0.05, "direction": "both"},
    "nested_objects_render": {"mode": "asymmetric", "growth": 0.0, "fall": 0.02},
    "nested_objects_render_vs_original": {"mode": "upper_ratio", "tol": 2.0},
}


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def now():
    return time.strftime("%Y-%m-%dT%H:%M:%S%z")


def load_json(path):
    with open(path) as f:
        return json.load(f)


def append_jsonl(path, obj):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "a") as f:
        f.write(json.dumps(obj, ensure_ascii=False) + "\n")


def load_jsonl(path):
    if not os.path.exists(path):
        return []
    out = []
    with open(path) as f:
        for line in f:
            if line.strip():
                out.append(json.loads(line))
    return out


def atomic_write_text(path, text):
    directory = os.path.dirname(os.path.abspath(path))
    os.makedirs(directory, exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix=".active-", suffix=".tmp", dir=directory)
    try:
        with os.fdopen(fd, "w") as f:
            f.write(text)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


def active_id(work):
    p = os.path.join(work, "ACTIVE")
    if not os.path.exists(p):
        return None
    with open(p) as f:
        v = f.read().strip()
    return v or None


def accepted_dirs(work):
    root = os.path.join(work, "accepted")
    if not os.path.isdir(root):
        return []
    return sorted(os.path.join(root, x) for x in os.listdir(root)
                  if os.path.isdir(os.path.join(root, x)))


def accepted_meta(work, aid):
    p = os.path.join(work, "accepted", aid, "meta.json")
    return load_json(p) if os.path.exists(p) else None


class IntegrityError(Exception):
    """Принятое состояние на диске больше не совпадает со своим html_sha256.

    Это НЕ плохой кандидат, а нарушенное состояние accepted — поэтому
    исход BLOCKED, а не REJECT: чинить надо инфраструктуру, а не вёрстку.
    """


def accepted_integrity(work, aid):
    """(ok, stored, actual). ok=None — сверять нечего (нет meta или sha в нём)."""
    meta = accepted_meta(work, aid)
    html = os.path.join(work, "accepted", aid, "index.html")
    stored = (meta or {}).get("html_sha256")
    if not os.path.exists(html):
        return False, stored, None
    actual = sha256_file(html)
    if not stored:
        return None, None, actual
    return (stored == actual), stored, actual


def require_accepted_integrity(work, aid):
    """Сверка целостности принятого состояния. meta.json НЕ правится: расхождение
    — это улика, и автоматическое «исправление» её уничтожает."""
    ok, stored, actual = accepted_integrity(work, aid)
    if ok is False:
        raise IntegrityError(
            "accepted state %s changed on disk: meta html_sha256=%s, actual=%s; "
            "meta.json left untouched as evidence. Use "
            "'refresh %s --override-integrity \"reason\"' only if the edit was "
            "intentional" % (aid, str(stored)[:16], str(actual)[:16], aid))
    if ok is None:
        # Отсутствие html_sha256 нельзя трактовать как «сверять нечего»: иначе
        # удаление поля навсегда отключает проверку целостности. Протокол v1
        # гарантирует это поле, поэтому его отсутствие при текущей версии — само
        # по себе нарушенное состояние.
        meta = accepted_meta(work, aid)
        if meta is not None and \
                meta.get("gate_schema_version") == GATE_SCHEMA_VERSION:
            raise IntegrityError(
                "accepted state %s claims gate_schema_version=%d but has no "
                "html_sha256 in meta.json; integrity cannot be verified"
                % (aid, GATE_SCHEMA_VERSION))
    return ok


def required_metric_names():
    return list(H.METRICS.keys())


def comparable_meta(meta):
    if not meta or meta.get("gate_schema_version") != GATE_SCHEMA_VERSION:
        return False
    if meta.get("forced") is True:
        return False
    metrics = meta.get("metrics", {})
    return all(metrics.get(k) is not None for k in required_metric_names())


def best_accepted(work):
    vals = []
    for d in accepted_dirs(work):
        aid = os.path.basename(d)
        meta = accepted_meta(work, aid)
        if not comparable_meta(meta):
            continue
        # Состояние с нарушенной целостностью не может задавать планку: его
        # числа описывают HTML, которого на диске уже нет.
        if accepted_integrity(work, aid)[0] is False:
            continue
        vals.append((aid, meta["metrics"]))
    if not vals:
        return None
    best = {}
    for aid, metrics in vals:
        for k, (direction, _tol, _label) in H.METRICS.items():
            v = metrics[k]
            if k not in best:
                best[k] = (v, aid)
            elif (direction == "down" and v < best[k][0]) or (direction == "up" and v > best[k][0]):
                best[k] = (v, aid)
    return {k: {"value": v, "accepted_id": aid} for k, (v, aid) in best.items()}


def metric_checks(candidate, active):
    results = []
    for k, (direction, tol, label) in H.METRICS.items():
        cv = candidate.get(k)
        av = active.get(k)
        if cv is None or av is None:
            results.append({"name": k, "label": label, "result": "INCOMPARABLE",
                            "candidate": cv, "active": av, "tolerance": tol,
                            "direction": direction})
            continue
        delta = cv - av
        worse = delta > tol if direction == "down" else delta < -tol
        better = delta < -tol if direction == "down" else delta > tol
        results.append({"name": k, "label": label,
                        "result": "FAIL" if worse else "PASS",
                        "candidate": cv, "active": av, "delta": delta,
                        "tolerance": tol, "direction": direction,
                        "better": bool(better)})
    return results


def structure_checks(candidate_report, active_report, original_top, original_nested):
    """Структурные инварианты. ВСЕ пороги читаются из STRUCTURE, ни одного числа
    в теле функции — иначе правка словаря не меняет поведение."""
    c = candidate_report.get("structure", {})
    a = active_report.get("structure", {})
    results = []

    cfg_top = STRUCTURE["top_objects_render"]
    top_tol = cfg_top["tol"]
    ct = c.get("top_objects_render")
    at = a.get("top_objects_render")
    if ct is None or at is None or at == 0:
        results.append({"name": "top_objects_render", "result": "INCOMPARABLE",
                        "candidate": ct, "active": at})
    else:
        rel = abs(ct - at) / float(at)
        results.append({"name": "top_objects_render",
                        "result": "FAIL" if rel > top_tol else "PASS",
                        "candidate": ct, "active": at, "relative_delta": rel,
                        "tolerance": top_tol})

    cfg_nest = STRUCTURE["nested_objects_render"]
    growth_tol = cfg_nest["growth"]
    fall_tol = cfg_nest["fall"]
    cn = c.get("nested_objects_render")
    an = a.get("nested_objects_render")
    if cn is None or an is None or an == 0:
        results.append({"name": "nested_objects_render", "result": "INCOMPARABLE",
                        "candidate": cn, "active": an})
    else:
        growth = (cn - an) / float(an)
        fall = (an - cn) / float(an)
        fail = growth > growth_tol or fall > fall_tol
        results.append({"name": "nested_objects_render",
                        "result": "FAIL" if fail else "PASS",
                        "candidate": cn, "active": an, "growth": growth,
                        "fall": fall, "growth_tolerance": growth_tol,
                        "fall_tolerance": fall_tol})

    ratio_tol = STRUCTURE["nested_objects_render_vs_original"]["tol"]
    if cn is None or original_nested == 0:
        results.append({"name": "nested_objects_render_vs_original", "result": "INCOMPARABLE",
                        "candidate": cn, "original": original_nested})
    else:
        ratio = cn / float(original_nested)
        results.append({"name": "nested_objects_render_vs_original",
                        "result": "FAIL" if ratio > ratio_tol else "PASS",
                        "candidate": cn, "original": original_nested,
                        "ratio": ratio, "upper_ratio": ratio_tol})
    return results


def load_original_counts(work):
    cat = load_json(os.path.join(work, "catalog.json"))
    objs = cat.get("objects", {})
    return (sum(v.get("layer") == "top" for v in objs.values()),
            sum(v.get("layer") == "nested" for v in objs.values()))


def original_sha(work):
    cat = load_json(os.path.join(work, "catalog.json"))
    return cat.get("original", {}).get("sha256")


def validate_candidate(work, candidate_dir, report):
    real = os.path.realpath(candidate_dir)
    work_real = os.path.realpath(work)
    accepted_real = os.path.realpath(os.path.join(work, "accepted"))
    if real == work_real or real.startswith(accepted_real + os.sep):
        raise ValueError("candidate must be physically separate from ACTIVE/accepted")
    expected = original_sha(work)
    got = report.get("original_sha256")
    if not expected or got != expected:
        raise ValueError("candidate report ORIGINAL sha256 does not match catalog")


def verdict_from(metric_results, structure_results):
    allr = metric_results + structure_results
    if any(x["result"] == "INCOMPARABLE" for x in allr):
        return "BLOCKED"
    if any(x["result"] == "FAIL" for x in allr):
        return "REJECT"
    return "ACCEPT"


def _report_metrics(report):
    return {
        "recon_px_over_jnd": report.get("reconstruction", {}).get("px_over_jnd"),
        "recon_mae": report.get("reconstruction", {}).get("mae"),
        "ssim": report.get("pixels", {}).get("ssim"),
        "layout_defects": report.get("layout_defects"),
        "layout_missing": len(report.get("layout", {}).get("missing", [])),
        "layout_spurious": len(report.get("layout", {}).get("spurious", [])),
        "nested_defects": report.get("nested_defects"),
        "mae_nonbg": report.get("pixels", {}).get("mae_nonbg"),
    }


def ensure_attempt(work, candidate_dir):
    if not os.path.isdir(candidate_dir):
        raise ValueError("candidate directory does not exist: %s" % candidate_dir)
    report_path = os.path.join(candidate_dir, "report.json")
    if not os.path.exists(report_path):
        raise ValueError("candidate report.json missing")
    report = load_json(report_path)
    return report


def make_verdict(work, candidate_dir, forced=False, force_reason=None,
                 baseline=False):
    report = ensure_attempt(work, candidate_dir)
    validate_candidate(work, candidate_dir, report)
    aid = os.path.basename(os.path.abspath(candidate_dir))
    if not aid.startswith("att-") and not baseline:
        raise ValueError("candidate must be an attempts/att-NNN directory")
    top_orig, nested_orig = load_original_counts(work)
    active = active_id(work)
    active_report = None
    active_meta = None
    if active:
        # ЦЕЛОСТНОСТЬ ACTIVE — до любого сравнения. Иначе кандидат сверяется с
        # числами, описывающими HTML, которого на диске уже нет.
        require_accepted_integrity(work, active)
        active_report = load_json(os.path.join(work, "accepted", active, "report.json"))
        active_meta = accepted_meta(work, active)

    metrics = _report_metrics(report)
    cand_gate = report.get("gate_schema_version", GATE_SCHEMA_VERSION)
    if baseline:
        if active:
            raise ValueError("--baseline is allowed only when ACTIVE does not exist")
        metric_results = []
        structure_results = []
        gate_result = "BASELINE"
        final = "ACCEPT"
    elif not active:
        metric_results = [{"name": "ACTIVE", "result": "INCOMPARABLE",
                           "reason": "no ACTIVE baseline; use explicit --baseline"}]
        structure_results = []
        final = "BLOCKED"
    else:
        if active_meta is None:
            metric_results = [{"name": "gate_schema_version", "result": "INCOMPARABLE",
                               "reason": "ACTIVE has no meta.json for the current gate protocol"}]
            structure_results = []
            final = "BLOCKED"
            am = None
            active_gate = None
        else:
            am = active_meta.get("metrics", {})
            active_gate = active_meta.get("gate_schema_version")
        if active_meta is not None:
            if active_gate != cand_gate:
                metric_results = [{"name": "gate_schema_version", "result": "INCOMPARABLE",
                                   "candidate": cand_gate, "active": active_gate}]
                structure_results = []
            else:
                metric_results = metric_checks(metrics, am)
                structure_results = structure_checks(report, active_report,
                                                     top_orig, nested_orig)
            final = verdict_from(metric_results, structure_results)

    if not baseline:
        gate_result = final
    if forced and final == "REJECT":
        final = "ACCEPT"
    elif forced and final != "REJECT":
        raise ValueError("--force is only a bypass for REJECT, not %s" % final)

    return {
        "schema_version": 1,
        "gate_schema_version": GATE_SCHEMA_VERSION,
        "attempt_id": aid,
        "candidate": {
            "html_sha256": sha256_file(os.path.join(candidate_dir, "index.html")),
            "render_sha256": sha256_file(os.path.join(candidate_dir, report.get("render", "render.png")))
            if os.path.exists(os.path.join(candidate_dir, report.get("render", "render.png"))) else None,
            "gate_schema_version": cand_gate,
        },
        "active": ({"accepted_id": active,
                     "gate_schema_version": active_meta.get("gate_schema_version") if active_meta else None}
                    if active else None),
        "verdict": final,
        "gate_result": gate_result,
        "metrics": {"snapshot": metrics, "checks": metric_results},
        "structure": {"snapshot": report.get("structure", {}), "checks": structure_results},
        "best": best_accepted(work),
        "forced": bool(forced),
        "force_reason": force_reason if forced else None,
        "baseline": bool(baseline),
        "timestamp": now(),
    }


def next_accepted_id(work):
    return "acc-%03d" % (len(accepted_dirs(work)) + 1)


def promote(work, candidate_dir, verdict, accepted_id=None):
    aid = verdict["attempt_id"]
    accepted_id = accepted_id or next_accepted_id(work)
    accepted_dir = os.path.join(work, "accepted", accepted_id)
    os.makedirs(os.path.dirname(accepted_dir), exist_ok=True)
    if os.path.exists(accepted_dir):
        raise RuntimeError("accepted id collision: %s" % accepted_id)
    shutil.copytree(candidate_dir, accepted_dir)
    meta = {
        "schema_version": 1,
        "gate_schema_version": GATE_SCHEMA_VERSION,
        "accepted_id": accepted_id,
        "source_attempt_id": aid,
        "metrics": _report_metrics(load_json(os.path.join(accepted_dir, "report.json"))),
        "structure": load_json(os.path.join(accepted_dir, "report.json")).get("structure", {}),
        "forced": bool(verdict.get("forced")),
        "baseline": bool(verdict.get("baseline")),
        "html_sha256": sha256_file(os.path.join(accepted_dir, "index.html")),
        "timestamp": now(),
    }
    with open(os.path.join(accepted_dir, "meta.json"), "w") as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)
        f.write("\n")
    # The pointer is the transaction boundary. os.replace is atomic and allowed
    # by the sandbox guard, unlike mv -T/symlink replacement.
    atomic_write_text(os.path.join(work, "ACTIVE"), accepted_id + "\n")
    verdict["accepted_id"] = accepted_id
    return accepted_id, meta


def run(work, candidate_dir, force_reason=None, baseline=False):
    forced = force_reason is not None
    verdict = make_verdict(work, candidate_dir, forced=forced,
                           force_reason=force_reason, baseline=baseline)
    if verdict["verdict"] == "ACCEPT":
        verdict["accepted_id"] = next_accepted_id(work)

    vpath = os.path.join(candidate_dir, "verdict.json")
    with open(vpath, "w") as f:
        json.dump(verdict, f, ensure_ascii=False, indent=2)
        f.write("\n")
    append_jsonl(os.path.join(work, "attempts.jsonl"), verdict)

    if verdict["verdict"] == "ACCEPT":
        accepted_id, meta = promote(work, candidate_dir, verdict, verdict["accepted_id"])
        append_jsonl(os.path.join(work, "accepted.jsonl"), {
            "schema_version": 1,
            "gate_schema_version": GATE_SCHEMA_VERSION,
            "accepted_id": accepted_id,
            "source_attempt_id": verdict["attempt_id"],
            "forced": verdict["forced"],
            "baseline": verdict["baseline"],
            "html_sha256": meta["html_sha256"],
            "timestamp": meta["timestamp"],
        })
        with open(vpath, "w") as f:
            json.dump(verdict, f, ensure_ascii=False, indent=2)
            f.write("\n")
    return verdict


def refresh(work, accepted_id, override_reason=None):
    """Дописать новые метрики к уже принятому состоянию, НЕ меняя его HTML.

    Назначение refresh предполагает, что index.html не менялся, — поэтому при
    расхождении html_sha256 это BLOCKED, а не тихая перезапись: иначе refresh
    узаконивает подмену и уничтожает единственную улику.
    """
    d = os.path.join(work, "accepted", accepted_id)
    if not os.path.isdir(d):
        raise ValueError("accepted state does not exist: %s" % accepted_id)
    report_path = os.path.join(d, "report.json")
    html_path = os.path.join(d, "index.html")
    if not os.path.exists(report_path) or not os.path.exists(html_path):
        raise ValueError("accepted state needs index.html and report.json")

    if override_reason is None:
        require_accepted_integrity(work, accepted_id)

    report = load_json(report_path)
    meta_path = os.path.join(d, "meta.json")
    old = load_json(meta_path) if os.path.exists(meta_path) else {}
    actual = sha256_file(html_path)
    meta = {
        "schema_version": 1,
        "gate_schema_version": GATE_SCHEMA_VERSION,
        "accepted_id": accepted_id,
        "source_attempt_id": old.get("source_attempt_id"),
        "metrics": _report_metrics(report),
        "structure": report.get("structure", {}),
        "forced": bool(old.get("forced", False)),
        "baseline": bool(old.get("baseline", False)),
        "html_sha256": actual,
        "timestamp": now(),
        "refreshed": True,
    }
    if override_reason is not None:
        # Событие в журнале, а не тишина: прежний sha сохраняется, чтобы факт
        # переустановки целостности остался виден.
        prev = old.get("html_sha256")
        meta["integrity_override"] = {
            "reason": override_reason,
            "previous_html_sha256": prev,
            "new_html_sha256": actual,
            "timestamp": now(),
        }
        append_jsonl(os.path.join(work, "attempts.jsonl"), {
            "schema_version": 1,
            "gate_schema_version": GATE_SCHEMA_VERSION,
            "event": "integrity_override",
            "accepted_id": accepted_id,
            "reason": override_reason,
            "previous_html_sha256": prev,
            "new_html_sha256": actual,
            "timestamp": now(),
        })
    with open(meta_path, "w") as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)
        f.write("\n")
    return meta


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="command", required=True)

    gp = sub.add_parser("gate")
    gp.add_argument("candidate")
    gp.add_argument("--dir", default=os.environ.get("UICOPY_DIR", os.getcwd()))
    gp.add_argument("--force")
    gp.add_argument("--baseline", action="store_true")

    rp = sub.add_parser("refresh")
    rp.add_argument("accepted_id")
    rp.add_argument("--dir", default=os.environ.get("UICOPY_DIR", os.getcwd()))
    rp.add_argument("--override-integrity", dest="override_integrity",
                    metavar="REASON",
                    help="принять расхождение html_sha256 (нужна причина; "
                         "пишется в meta.json и attempts.jsonl)")

    vp = sub.add_parser("verify")
    vp.add_argument("--dir", default=os.environ.get("UICOPY_DIR", os.getcwd()))

    ns = p.parse_args()
    work = os.path.abspath(ns.dir)
    try:
        if ns.command == "refresh":
            meta = refresh(work, ns.accepted_id,
                           override_reason=ns.override_integrity)
            note = " (integrity override)" if ns.override_integrity else ""
            print("refreshed %s: %d metrics%s"
                  % (ns.accepted_id, len(meta["metrics"]), note))
            return
        if ns.command == "verify":
            bad = 0
            for d in accepted_dirs(work):
                aid = os.path.basename(d)
                ok, stored, actual = accepted_integrity(work, aid)
                if ok is True:
                    print("%-10s OK   %s" % (aid, str(stored)[:16]))
                elif ok is None:
                    print("%-10s ?    нет html_sha256 в meta.json" % aid)
                else:
                    bad += 1
                    print("%-10s ПОДМЕНА  meta=%s actual=%s"
                          % (aid, str(stored)[:16], str(actual)[:16]))
            act = active_id(work)
            print("ACTIVE = %s" % act)
            if bad:
                raise SystemExit(2)
            return
        v = run(work, os.path.abspath(ns.candidate),
                force_reason=ns.force, baseline=ns.baseline)
    except IntegrityError as e:
        print("BLOCKED (integrity): %s" % e)
        raise SystemExit(2)
    except Exception as e:
        print("BLOCKED: %s" % e)
        raise SystemExit(2)
    print("%s (gate_result=%s)" % (v["verdict"], v["gate_result"]))
    if v["verdict"] == "BLOCKED":
        raise SystemExit(2)
    if v["verdict"] == "REJECT":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
