# Review C — /import-compact range contract + visible-text arrow filter

Repo: `/var/minis/shared/openminis-backup/canonical-fresh` (branch feat/message-rewrite-visual)
Scope read in full: `ui/chat/ExternalExportCache.kt`, `ui/chat/ChatViewModel.kt` (compact path),
`engine/OutputSanitizer.kt`, `engine/EngineAgentLoop.kt` (TextDelta), `data/ExternalCompactionPlan.kt`,
`test/.../ExternalExportCacheTest.kt`, `test/.../ExternalExportCacheRangeTest.kt`, `test/.../OutputSanitizerTest.kt`.

Read-only review. No files were modified.

Counts: CONFIRMED DEFECTS 10 (1 critical, 1 high, 3 medium, 5 low) · SUSPECTED 4 · TEST GAPS 9.

---

## CONFIRMED DEFECTS

### C-1 CRITICAL — remembered range and file range are never compared: a foreign or stale file's summary is applied to this chat's live range
- **File/line:** `src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:3068-3069` (decision), `:3591-3592` (the drift check that does not cover it)
- **Fragments:**
  - 3068 `val fromFile = ExternalExportCache.rangeFromText(text)`
  - 3069 `val range = remembered ?: fromFile`
  - 3591-3592 `val drift = firstDbId != externalPlan.firstDbId ||` / `    lastDbId != externalPlan.lastDbId`
- **Why the drift check cannot catch it:** the drift check compares the LIVE range against the *plan's* range, and the plan's range is the *remembered* one (3069). `fromFile` is parsed and then thrown away, so the artifact's own range is never compared with anything. The goal ("the apply step must refuse any drift … so the app never folds the wrong messages") is not met for this case.
- **Failing input A (stale file, same chat — silent message loss):**
  1. Chat A exports range `A1..A20` → file F1 contains `(Служебная строка, не изменяй: RANGE A1..A20)`.
  2. The user writes 5 more messages and runs `/export-compact` again → prefs for A now hold `A1..A25` (file F2).
  3. The user compresses **F1** externally and imports **F1** (both files live in the same `Download/Minis/ExportCompact/` folder).
  4. `remembered = (A1,A25)` is present → plan range = `(A1,A25)`; F1's own `RANGE A1..A20` is discarded.
  5. Live range at apply = `(A1,A25)` → `drift == false` → **no refusal**. The summary describes messages 1..20 but is committed over the range 1..25 → **messages 21..25 are folded away and are not represented in the summary — permanent loss.**
- **Failing input B (foreign file — cross-chat contamination):** chat B's compressed file (carrying `RANGE B1..B30`) is imported into chat A, which has its own remembered export `(A1,A42)`. `remembered` wins → plan range `(A1,A42)` → live range equals it → no drift → **chat B's summary is committed as chat A's compaction summary** while A's messages 1..42 are folded away.
- **Minimal fix:** compare the two known ranges and refuse on disagreement, e.g. before line 3069:
  `val mismatch = remembered != null && fromFile != null && remembered != fromFile` → refuse with a truthful message ("файл экспорта относится к другому диапазону: RANGE a..b, в этом чате сохранён c..d"); only then pick a range. Also invalidate the remembered range after a successful import (see C-7).

### C-2 HIGH — the arrow filter is applied per streaming chunk, so a run split across chunks is never collapsed
- **File/line:** `src/android/app/src/main/java/com/openminis/app/engine/EngineAgentLoop.kt:133-137`
- **Fragment:**
  ```
  is StreamEvent.TextDelta -> {
      if (failure == null) {
          val clean = OutputSanitizer.sanitizeArrows(event.text)
          turnText.append(clean)
          emit(AgentEvent.TextDelta(clean))
  ```
- **Failing input:** the degeneration arrives as separate deltas. Deltas `"⇒"`, `"⇒"`, `"⇒"`: `sanitizeArrows("⇒")` = `"⇒"` (1 arrow < 3 → untouched) and `sanitizeArrows("⇒⇒")` = `"⇒⇒"` (2 < 3 → untouched), so the accumulated `turnText` = `"⇒⇒⇒"`. Deltas `"⇒⇒"` + `"⇒"` → accumulated `"⇒⇒⇒"` as well. Result: three arrows on the screen, three arrows in the saved assistant message, and three arrows back to the provider in `workingHistory` (EngineAgentLoop.kt:255-262). The filter never fires in exactly the degenerate case it exists for (one repeated token per delta).
- **Minimal fix:** sanitize the *accumulated* text, not the chunk: keep the raw buffer, sanitize the whole buffer, and emit only the newly produced suffix of the sanitized string — screen, saved message and context then all carry the collapsed text.

### C-3 MEDIUM — `rangeFromText()` takes the FIRST "RANGE" match; from the second round trip on the exported file contains the previous (stale) range first
- **File/line:** `src/android/app/src/main/java/com/openminis/app/ui/chat/ExternalExportCache.kt:87-92` (regex at `:29-30`)
- **Fragment:** `val match = RANGE_IN_TEXT.find(text) ?: return null`
- **Failing input:** the imported text is committed verbatim as the new summary (`ChatViewModel.kt:3743` `externalPlan.summary.trim()`), including the service line. The next export embeds that summary verbatim in the `=== ПРЕДЫДУЩАЯ ВЫЖИМКА ===` section (`ChatViewModel.kt:3113-3116`) and appends the NEW range at the end (`:3130`). The exported file therefore contains two RANGE lines, the stale one FIRST:
  ```
  === ПРЕДЫДУЩАЯ ВЫЖИМКА ===
  (Служебная строка, не изменяй: RANGE old1..old2)
  === ТРАНСКРИПТ СЕССИИ ДЛЯ СЖАТИЯ ===
  ...
  (Служебная строка, не изменяй: RANGE new1..new2)
  ```
  Importing that file without the remembered range (new install / cleared prefs) resolves the range to the STALE `old1..old2` → the drift check compares it with the live range → mismatch → the import is refused ("Диапазон сессии изменился после экспорта") although the file carries the correct new range.
- **Minimal fix:** take the LAST match (`RANGE_IN_TEXT.findAll(text).lastOrNull()`) and/or match the exact service-line form `не изменяй: RANGE <a>..<b>`.

### C-4 MEDIUM — the refusal's parenthetical is derived from the global index and is not verified
- **File/line:** `ChatViewModel.kt:3073-3077`, `:3081-3085`
- **Fragments:**
  - 3074-3076 `val tracked = ExternalExportCache.parseIndex(` / `externalCompactionPrefs.getString(ExternalExportCache.INDEX_KEY, null),`
  - 3077 `val exportedSession = tracked.lastOrNull { it != compactSid }`
  - 3082 `"В ЭТОМ чате нет сохранённого экспорта (в другом чате есть свой — он сюда не подходит). Сделай /export-compact в ЭТОМ чате."`
- **Failing input:** the index only records "this session exported at some point" (`ExternalExportCache.kt:130-145`); it is not evidence that the other chat still has a usable export — the file may be deleted, the export may be days old (`FRESH_MS` = 15 min), the session may be gone. Concrete: the user exported chat B yesterday (index = `["B"]`), deleted the file; today in chat A they import a file whose service line the model dropped → the message claims "в другом чате есть свой" — a false, index-derived statement about another chat. The goal explicitly forbids index-based statements of the "another session" kind.
- **Minimal fix:** only mention the other chat when its remembered export is actually present and fresh (check `atMs` with `isFresh` and the stored uri), otherwise print only the truthful "В ЭТОМ чате нет сохранённого экспорта — сделай /export-compact в этом чате."

### C-5 MEDIUM — the drift refusal message is not true when the range came from the file
- **File/line:** `ChatViewModel.kt:3593-3603` (message at `:3600`)
- **Fragment:** `"Диапазон сессии изменился после экспорта — экспортируй заново и сожми новый файл."`
- **Failing input:** chat A never exported; the user imports chat B's compressed file (carrying `RANGE B1..B30`). The range came from the file (`:3069`, no remembered range), the live range of A is `(A1,A42)` → drift → the message claims "the range of the session changed after the export", but the user never exported from A and nothing changed: **the file belongs to a different chat**. The goal requires the refusal to state the truth.
- **Minimal fix:** distinguish the two cases — if the range came from the file, say "файл экспорта сделан из другого чата (RANGE …) — в этом чате нужен свой экспорт"; keep the "range changed" wording only when the range came from the remembered export.

### C-6 LOW — the committed summary keeps the raw service line (and the whole raw file)
- **File/line:** `ChatViewModel.kt:3743` (`externalPlan.summary.trim()`), service line written at `:3130`
- **Failing input:** the imported text becomes the permanent compaction summary, so it stores "(Служебная строка, не изменяй: RANGE A1..A20)" forever, and every later export re-embeds it (this is the cause of C-3). If the user imports an *uncompressed* export file, the entire export (prompt header + full transcript) becomes the summary — the only guard is a 200-char floor (`:3605-3606`) and a 2 MB file cap (`:3021`).
- **Minimal fix:** strip the service line (and the `---` separator) from the text before committing it as the summary.

### C-7 LOW — the remembered range is never invalidated after a successful import
- **File/line:** read at `ChatViewModel.kt:3050-3057`; the only writes are the export paths `:2980-2982` and `:3194-3198`
- **Failing input:** after importing, the prefs still hold the range of the export that was just folded. A later import of a different file reuses that stale range (C-1 scenario A): the range of an already-folded export is presented as the range of the current history.
- **Minimal fix:** clear `firstDbIdKey`/`lastDbIdKey` (and uri/atMs) for `compactSid` after a successful import.

### C-8 LOW — the remembered-range read does not apply the blank guard that `rangeOf()` applies
- **File/line:** `ChatViewModel.kt:3050-3057` vs `ExternalExportCache.kt:62-67` (`if (first.isNullOrBlank() || last.isNullOrBlank()) return null`)
- **Failing input:** a remembered value of `""` (possible if a db id were ever empty) is accepted as a valid range → the file's range is shadowed → the drift check compares the live ids against `""` → drift → refusal with the misleading "Диапазон сессии изменился после экспорта" message, although the file did carry a usable range.
- **Minimal fix:** route both paths through one helper so the blank rule cannot diverge.

### C-9 LOW — `RANGE_IN_TEXT` has no left word boundary: a "RANGE" substring in prose is a false positive
- **File/line:** `ExternalExportCache.kt:29-30`
- **Fragment:** `Regex("RANGE\\s*[:\\-]?\\s*([A-Za-z0-9_-]{6,64})\\.\\.([A-Za-z0-9_-]{6,64})")`
- **Failing input:** `"Диапазон значений SUBRANGE 10000..20000 покрывает всё."` → the regex matches inside "SUBRANGE" → `rangeFromText` returns `("10000","20000")`. With no remembered range the import proceeds with that bogus range → the drift check mismatches the live ids → the user is told to "export again and compress the new file" — a loop the file can never satisfy, because the model keeps writing SUBRANGE. Same for date-like prose: `"RANGE 20240101..20240131"`.
- **Note:** a false positive cannot pass the drift check (live ids are not "10000"), so this causes a misleading refusal, not a wrong fold.
- **Minimal fix:** anchor the match (`(?<![A-Za-z])RANGE\b`, or match the exact `не изменяй: RANGE ` service form) and require ids that look like ids.

### C-10 LOW — the arrow filter eats the line breaks inside a run
- **File/line:** `src/android/app/src/main/java/com/openminis/app/engine/OutputSanitizer.kt:25`
- **Fragment:** `private val ARROW_RUN = Regex("⇒(?:\\s*⇒){2,}")`
- **Failing input:** `"⇒\n⇒\n⇒"` (three arrows on three lines) → `\s*` spans the newlines → the whole run becomes `"⇒"` and two line breaks are destroyed. (Whitespace-separated runs on one line are handled correctly: `"⇒ ⇒ ⇒"` → `"⇒"`.)
- **Minimal fix:** if line structure must survive, restrict the separator to spaces/tabs (`[ \t]*`).

---

## TEST GAPS

Existing tests cover: session-scoped cache behaviour (`ExternalExportCacheTest`), `rangeOf` blank rules (`ExternalExportCacheRangeTest`), and the pure arrow function on complete strings (`OutputSanitizerTest`). The gaps below are the concrete inputs that no test exercises.

- **T-1 (C-1, highest value)** — the remembered-vs-file disagreement has no test and no testable seam: the decision `remembered ?: fromFile` lives inside `ChatViewModel.applyExternalCompactionText` (ViewModel → not unit-testable). Extract the decision into `ExternalExportCache` and pin:
  `assertEquals(Refusal.RangeMismatch, ExternalExportCache.resolveRange("A1" to "A25", "A1" to "A20"))` and the foreign case `resolveRange("A1" to "A42", "B1" to "B30")`.
- **T-2 (C-3)** — a file with two RANGE lines (stale first, new last):
  `assertEquals("new1" to "new2", ExternalExportCache.rangeFromText("=== ПРЕДЫДУЩАЯ ВЫЖИМКА ===\n(Служебная строка, не изменяй: RANGE old1..old2)\n===\n(Служебная строка, не изменяй: RANGE new1..new2)"))`.
- **T-3 (C-2)** — an arrow run split across two deltas must collapse: deltas `"⇒⇒"` then `"⇒"` → the accumulated assistant text must be `"⇒"` (currently `"⇒⇒⇒"`). Needs a test at the `EngineAgentLoop` level, not on the pure function.
- **T-4 (C-2)** — a single or double arrow split across chunks must stay untouched: deltas `"⇒"` then `"⇒"` → `"⇒⇒"`.
- **T-5 (C-4/C-5)** — the refusal MESSAGE is not pinned anywhere. Pin that the no-range refusal says "в этом чате нет сохранённого экспорта", must NOT contain the old wording "из другой сессии", and must not assert anything about another chat's export on the strength of the index alone; and that the foreign-file refusal says the file is from another chat (not "диапазон изменился после экспорта").
- **T-6 (C-9)** — prose false positive: `assertNull(ExternalExportCache.rangeFromText("Диапазон SUBRANGE 10000..20000 покрывает всё"))` and `assertNull(ExternalExportCache.rangeFromText("RANGE 20240101..20240131"))`.
- **T-7 (S-1)** — the id-length bound `{6,64}` is untested against the real id format: `assertNull(ExternalExportCache.rangeFromText("RANGE 10..20"))` plus an assertion that the ids the app actually writes into the service line are ≥ 6 chars (otherwise the "file carries its range" fallback never parses).
- **T-8 (C-7)** — the remembered range must be cleared after a successful import (no test exists for the import write path).
- **T-9 (C-10)** — newline separator: `assertEquals("⇒\n⇒\n⇒", OutputSanitizer.sanitizeArrows("⇒\n⇒\n⇒"))` (pins whether line structure must survive).

---

## SUSPECTED (needs verification)

- **S-1 — the real db id length vs the regex bound `{6,64}` (high impact).** The existing tests use short ids (`ExternalExportCacheTest.kt` entry helper: `"m1"`/`"m9"`; `ExternalExportCacheRangeTest.kt`: `"10"`/`"42"`), while the regex requires 6..64 chars. If the real `dbMessageId` is shorter than 6 characters (e.g. a numeric row id), `rangeFromText` can NEVER parse the exported service line → the whole "the file carries its range" fallback is dead and every import without the remembered prefs is refused.
  **Verification (cheapest):** find the declaration of `dbMessageId` and read its type/format, then compare with the string written at `ChatViewModel.kt:3130`. Note: my `grep -rn "dbMessageId" --include=*.kt` in this session returned nothing because BusyBox grep does not support `--include=*.kt`; re-run with `grep -rn "dbMessageId" src/android/app/src/main/java | head -20` (no `--include`).
- **S-2 — a second TextDelta consumer outside the engine.** `sanitizeArrows` is called in exactly one place (`EngineAgentLoop.kt:134`; `grep -rn "sanitizeArrows\|OutputSanitizer" src/android/app/src/main/java` returns only that). If the ViewModel (or another loop) handles `StreamEvent.TextDelta`/`AgentEvent.TextDelta` on its own path, the arrows are not filtered there → the screen shows the raw run while the engine path shows the collapsed one, i.e. the "screen, saved message and context all match" goal fails on that path.
  **Verification:** `grep -rn "StreamEvent.TextDelta\|AgentEvent.TextDelta" src/android/app/src/main/java` and check every consumer passes its text through `OutputSanitizer.sanitizeArrows`.
- **S-3 — the import may be cut by the incremental slicing.** The drift check runs on the FULL range (`:3560-3565`), but the code below it (`:3617+`) applies the "[T-compact-incremental] at most ~25K tokens" bounding. If that bounding also applies to the Import plan, the committed summary (describing the whole range) replaces only the first slice → the messages after the slice stay in the history and are duplicated by the summary. The comment at `:3554-3561` claims the external path "folds the FULL un-compacted range", but the bounding code sits *after* the external block, so the claim needs confirming in code.
  **Verification:** read `ChatViewModel.kt:3614-3730` and check that the slice computation is skipped when `externalPlan != null`.
- **S-4 — the saved assistant message may be re-derived from raw deltas.** The engine emits and stores the sanitized text (`EngineAgentLoop.kt:135-136`, `:255-262`), but the DB message is written by the ViewModel from the event stream; if the ViewModel accumulates the raw provider text instead of the emitted text, the saved message would differ from the screen.
  **Verification:** trace the ViewModel's `AgentEvent.TextDelta` consumer to the message-save call.

---

## AREAS CHECKED WITH NO DEFECT FOUND

1. **Arrow thresholds (OutputSanitizer.kt:25-38)** — exactly 2 arrows untouched (`"a ⇒ ⇒ b"` → unchanged), exactly 3 collapse (`"⇒⇒⇒"` → `"⇒"`), 4+ collapse (`"⇒⇒⇒⇒"` → `"⇒"`), a spaced run collapses (`"⇒ ⇒ ⇒"` → `"⇒"`), text without arrows returns unchanged. No off-by-one at 2 or 3.
2. **No broken residual sequence** — the greedy match consumes the whole arrow run, so no leftover 2-arrow tail survives; `ARROW_RUN_TIGHT` (`:27`) is redundant (ARROW_RUN already covers tight runs) but harmless.
3. **The user's own text is never filtered** — `sanitizeArrows` is applied only to the model's `TextDelta`; user input never passes through it.
4. **The reasoning channel is not filtered by `sanitizeArrows` and does not need to be** — it has a dedicated guard: `EngineAgentLoop.kt:146-200` accumulates reasoning into `ReasoningLoopWatch`, drops the degenerate reasoning, emits `ThinkingDegenerate` and cancels the round, so it never reaches `reasoning_content` nor the provider (`:259`).
5. **The artifact and the memory carry the same range** — `runExternalCompactionExport` writes `firstDbId/lastDbId` into the service line (`:3130`) and into `firstDbIdKey/lastDbIdKey` (`:3194-3195`); no divergence between file and prefs.
6. **The drift check direction is correct** — the live range is recomputed from live history (`:3560-3565`) and compared with the plan's range (`:3591-3592`); a chat that changed after the export is refused (the C-1 gap is about *which* range the plan carries, not about the comparison itself).
7. **Refusals do not touch history** — both refusal branches (drift `:3593-3603`, short summary `:3605-3612`) set the card terminal, clear `_isCompacting` and return before the commit path.
8. **Pref keys are fully namespaced per session** (`ExternalExportCache.kt:94-98`) and eviction removes all five keys of a dropped session (`:139-145` + `:3199-3201`) — no cross-chat leak through the index.
9. **`pickFresh` (`:151-157`)** rejects a foreign entry, a blank uri, an entry without a range and a future timestamp — the ownership check is inside the function as documented.
10. **No truncation before parsing** — the import reads the whole file (≤2 MB, `:3007-3044`) and passes the whole text to `rangeFromText` (`:3046`), so a service line at the end of the file is found.
11. **The placeholder `?..?` is not parsed as a range** — the char class `[A-Za-z0-9_-]` excludes `?` (covered by `ExternalExportCacheTest.thePlaceholderRangeIsNotARange`).
12. **The service line dropped by the external model** — with a remembered range present the import proceeds on the remembered range and the drift check still applies (correct); with no remembered range the import refuses ("Нет активного экспорта…") and never uses a wrong range. The goal's "refuse, do not silently use a wrong range" holds.
13. **Markdown tables / code blocks / quoted user text** — the filter is applied to all visible model text by design, so a `⇒⇒⇒` inside a fenced code block or a table cell is collapsed too, and a model quote of the user's `⇒⇒⇒` is collapsed as well. This is consistent with the stated goal ("a run of 3+ ⇒ must collapse to one arrow" in the visible text) and the user's own typed text is untouched — accepted trade-off, flagged for the product owner, not counted as a defect.
14. **The file-size cap (2 MB) and the summary length floor (200 chars)** are applied before the commit.
