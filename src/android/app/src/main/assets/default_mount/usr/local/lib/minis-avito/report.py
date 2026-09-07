#!/usr/bin/env python3
"""Финальный отчёт по лоту: ссылка + вопросы продавцу.

Формат для пользователя:
  Ссылка: <url>
  Что спросить у продавца:
    1. <обязательный>
    2. <условный по флагам>
  Если ответит "X" -> ветка из дерева answers.
"""
import json
import sys
from pathlib import Path

import yaml

HERE = Path(__file__).parent
TREE = yaml.safe_load(open(HERE / 'config' / 'qa' / 'tree.yaml'))


def build_report(item):
    lines = [f"Ссылка: {item.get('url') or '(url не снят)'}", "Что спросить у продавца:"]
    n = 1
    for q in TREE['always']:
        lines.append(f"  {n}. {q['q']}")
        n += 1
    flags = set(item.get('flags', []))
    for c in TREE['conditional']:
        if c['flag'] in flags:
            lines.append(f"  {n}. {c['q']}   # флаг: {c['flag']}")
            n += 1
    # Ветки дерева — компактно, по одной строке
    for trigger, branch in TREE['answers'].items():
        nxt = '; '.join(branch.get('next', []))
        v = branch.get('verdict')
        tail = f" ВЕРДИКТ: {v}." if v else ''
        lines.append(f"Если ответит «{trigger.split('|')[0]}» -> {nxt}.{tail}")
    return '\n'.join(lines)


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else None
    if not src:
        print('usage: report.py <scan.json> [--n 3]')
        sys.exit(2)
    n = 3
    if '--n' in sys.argv:
        n = int(sys.argv[sys.argv.index('--n') + 1])
    scan = json.load(open(src))
    items = scan['items'][:n]
    for i, it in enumerate(items, 1):
        print(f"\n=== ЛОТ {i} (риск {it.get('risk_score')}) ===")
        print(build_report(it))


if __name__ == '__main__':
    main()
