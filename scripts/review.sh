#!/usr/bin/env bash
# [review] Prepare static-review material for the working tree diff.
# Produces REVIEW_MATERIAL.md: the diff + the checklist of known trap
# classes, ready to be handed to a reviewer (human or agent).
# Does NOT approve anything — analysis only.
set -euo pipefail
cd "$(dirname "$0")/.."

OUT="REVIEW_MATERIAL.md"
{
    echo "# Review material — $(date -Is)"
    echo "## Status"
    git status -sb | head -5
    echo
    echo "## Diff (working tree vs HEAD)"
    git diff
    echo
    echo "## Checklist for the reviewer (trap classes)"
    cat <<'CHK'
- [ ] SharedFlow/StateFlow updates без distinctUntilChanged → recomposition-шторм?
- [ ] Захват stale-стейта в лямбдах/coroutineScope (remember + mutableStateOf)?
- [ ] Тихие return'ы в хэндлерах кнопок без фидбека пользователю?
- [ ] tryLock/claim без парного unlock/finally; двойной release слота?
- [ ] Job'ы без cancel (утечки viewModelScope-детей)?
- [ ] Compose: ключи LazyColumn, стабильность лямбд, чтение стейта вне чтения?
- [ ] Флаги UI ↔ стейт VM рассинхрон (кнопка активна, когда действие невалидно)?
CHK
} > "$OUT"

echo "[review] material written to $OUT ($(wc -l < "$OUT") lines)"
