#!/usr/bin/env python3
"""[И5 self-check] Static sweep for bug-breeding Kotlin patterns.

Born from the real bugs of this project: the silent dead Telegram button
was a `runCatching { }.getOrDefault("")` swallowing an IOException, and
the zombie RUNNING rows were swallowed CancellationExceptions. This sweep
makes the whole class visible WITHOUT waiting for user reports.

Patterns (informational, not fatal — every hit needs eyes, some are fine):
  1. SWALLOW   — runCatching{...}.getOrDefault(...) / getOrNull()+elvis-noise:
                 an exception is converted into a default value.
  2. EMPTYCATCH— catch (...) {} with an empty body.
  3. BANGBANG  — !! non-null assertions (crash-on-null by design).
  4. CANCELCATCH— catch of CancellationException: must rethrow; swallowing
                 it breaks structured concurrency (zombie jobs/rows).

Usage:  python3 scripts/check_dangerous_patterns.py [--gate MAX_TOTAL]
Exit 0 always in report mode; --gate exits 1 when total > MAX_TOTAL.
"""
import argparse
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "src" / "android" / "app" / "src" / "main"

PATTERNS = {
    "SWALLOW": re.compile(r"runCatching\s*\{"),
    "GETDEFAULT": re.compile(r"\.getOrDefault\("),
    "EMPTYCATCH": re.compile(r"catch\s*\([^)]*\)\s*\{\s*\}"),
    "BANGBANG": re.compile(r"!!(?!\')"),
    "CANCELCATCH": re.compile(r"catch\s*\(\s*\w*:\s*(?:kotlinx\.coroutines\.)?CancellationException"),
}

TEST_PATH = "/src/test/"


def scan() -> dict:
    hits = {k: [] for k in PATTERNS}
    files = 0
    for path in sorted(ROOT.rglob("*.kt")):
        rel = str(path).replace(str(ROOT), "")
        files += 1
        try:
            lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            continue
        for no, line in enumerate(lines, 1):
            for name, rx in PATTERNS.items():
                if rx.search(line):
                    hits[name].append(f"{rel}:{no}")
    return {"files_scanned": files, "hits": {k: v for k, v in hits.items()}}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--gate", type=int, default=None,
                    help="exit 1 when the total hit count exceeds this")
    ap.add_argument("--json", action="store_true", help="machine-readable output")
    args = ap.parse_args()

    report = scan()
    totals = {k: len(v) for k, v in report["hits"].items()}
    summary = {
        "files_scanned": report["files_scanned"],
        "totals": totals,
        "grand_total": sum(totals.values()),
    }
    if args.json:
        print(json.dumps({**summary, "hits": report["hits"]}, ensure_ascii=False, indent=1))
    else:
        print(f"scanned {report['files_scanned']} .kt files (src/main)")
        for k, v in totals.items():
            print(f"  {k:12} {v}")
        print(f"  {'TOTAL':12} {summary['grand_total']}")
        for k in ("CANCELCATCH", "EMPTYCATCH", "SWALLOW"):
            for h in report["hits"][k][:25]:
                print(f"    [{k}] {h}")
    if args.gate is not None and summary["grand_total"] > args.gate:
        print(f"GATE FAILED: {summary['grand_total']} > {args.gate}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
