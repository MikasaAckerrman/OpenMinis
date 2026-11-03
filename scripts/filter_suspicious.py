#!/usr/bin/env python3
import sys
import re
from pathlib import Path

# Билдеры и контексты, где CancellationException и unbuffered tryEmit фатальны
COROUTINE_CONTEXT_RE = re.compile(
    r'\b(suspend\s+fun|flow\s*\{|channelFlow\s*\{|callbackFlow\s*\{|launch\b|async\b|withContext\b)'
)

# Паттерны, снимающие подозрение внутри блока catch (уже обработан)
SAFE_CATCH_RE = re.compile(
    r'(\bthrow\s+e\b|CancellationException|ensureActive\(\)|yield\(\))'
)

def analyze_match(file_path: str, line_no: int, matched_text: str):
    path = Path(file_path)
    if not path.is_file():
        return None

    try:
        lines = path.read_text(encoding="utf-8", errors="ignore").splitlines()
    except Exception:
        return None

    target_idx = line_no - 1
    if target_idx < 0 or target_idx >= len(lines):
        return None

    # 1. Сканируем до 50 строк вверх в поисках заголовка функции или лямбды
    is_coroutine_scope = False
    scope_decl = "unknown"
    search_limit = max(0, target_idx - 50)
    for i in range(target_idx, search_limit - 1, -1):
        m = COROUTINE_CONTEXT_RE.search(lines[i])
        if m:
            is_coroutine_scope = True
            scope_decl = lines[i].strip()
            break
        # Если упёрлись в обычную non-suspend функцию раньше билдеров — не корутина
        if re.search(r'\bfun\b', lines[i]) and 'suspend' not in lines[i]:
            break

    if not is_coroutine_scope:
        return None  # Отсекаем синхронный код / Java-потоки

    # 2. Если это catch — проверяем тело на наличие проброса
    if "catch" in matched_text:
        body_end = min(len(lines), target_idx + 15)
        catch_body = "\n".join(lines[target_idx:body_end])
        if SAFE_CATCH_RE.search(catch_body):
            return None  # Отмена уже пробрасывается или обрабатывается

    return {
        "file": file_path,
        "line": line_no,
        "scope": scope_decl[:60],
        "match": matched_text.strip()
    }

def main():
    report_file = sys.argv[1] if len(sys.argv) > 1 else "REVIEWS/evidence/20261104/pattern-check-cancellation-tryemit.txt"
    report_path = Path(report_file)

    if not report_path.is_file():
        print(f"Файл {report_file} не найден.")
        sys.exit(1)

    print(f"--- Фильтрация {report_file} на корутинные зоны ---\n")
    candidates = []

    # Наш чекер пишет "path:line text" (пробел после номера), поддержим и "path:line:text".
    line_parser = re.compile(r'^([^:]+):(\d+)[:\s](.*)$')

    for raw_line in report_path.read_text().splitlines():
        m = line_parser.match(raw_line.strip())
        if not m:
            continue
        file_path, line_str, text = m.groups()
        res = analyze_match(file_path, int(line_str), text)
        if res:
            candidates.append(res)

    for c in candidates:
        print(f"{c['file']}:{c['line']}")
        print(f"  Scope: {c['scope']}")
        print(f"  Code:  {c['match']}")
        print()

    print(f"Итог: из сырых срабатываний осталось {len(candidates)} реальных кандидатов в корутинах.")

if __name__ == "__main__":
    main()
