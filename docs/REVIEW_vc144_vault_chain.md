# REVIEW vc144 — vault chain code (20.10, per user protocol)

Scope: TelegramVaultManager.kt (252), PRootKernel.kt (1013), PersistentShell.kt (601),
RootfsManager.kt (778), MinisApp.kt hook (line 433), srv/telegram.py (1358).
All files read line-by-line (verbatim windows cross-checked against git HEAD 92c0719+ docs).

## Границы ревью
- Тела функций верифицированы построчно; verbatim-выдержки в offloads.
- vc144 условный PASS не тронут; реплеи не запущены; код во время ревью не менялся
  (фиксы выполняются отдельными коммитами после согласования, см. ниже).

## D-1..D-12 (found, ordered by severity)

**D-1 (FATAL-concurrency) PersistentShell.executeCommand:**
один `pendingCallback`/`pendingOutput` на объект без мьютекса; перекрывающиеся
вызовы → второй перетирает первый, первый висит до timeout (600с default = PS-1/PS-7).
Agent-loop реально гоняет параллельные вызовы → это объяснение «залипаний».
Файл: PersistentShell.kt (executeCommand, ~430-500).
**Проверка-фикс:** lock по объекту-монитору + очередь callback.

**D-2 (HIGH-reader race):** `pendingCallback` читается в reader-потоке без
@Volatile — формальная гонка Java Memory Model (PS-2). Фикс: @Volatile поля.

**D-3 (MEDIUM-feedLines double):** partial line может быть задвоен при
чанковом чтении (PS-4): буферная строка не сбрасывается корректно при EOF
и при первом байте следующего чанка.

**D-4 (MEDIUM-migration race):** migrateSession не убеждается, что агентский
tg-сервер остановлен перед copy/unlink живой SQLite (M-6): copy может пройти
между write-транзакциями → битая копия (verification по длине НЕ ловит).
Файл: TelegramVaultManager.kt (migrateSession), шаг 1-3.
**Фикс:** порт-проба + «server running?» guard перед миграцией.

**D-5 (MEDIUM-startLocked):** нет ждущего порта-проверки после pb.start():
«vault MCP launched» пишется сразу — если proot умрёт (наш known-issue
autostart FAIL), никакого retry/restart; писать в лог только по факту.
Файл: TelegramVaultManager.kt (~169).

**D-6 (LOW-server.py RPC_TIMEOUT):** `box.get(timeout=RPC_TIMEOUT if
"RPC_TIMEOUT" in globals() else 300)` — RPC_TIMEOUT глобально НЕ определён,
ветка всегда else; мёртвый код + конфликт ожиданий (Kt/env не задаёт).
Файл: srv/telegram.py:1307.

**D-7 (LOW-server.py parallel client):** `_shared_client` используется из
параллельных потоков без локов (TG-1); состояние `_backoff_until` тоже без
синхронизации (TG-2) — корректность под нагрузкой не гарантирована.

**D-8 (LOW-server.py date tz):** `tg_period_read` принимает naive-local
границыから UTC дат pyrogram — разница до 3ч (TG-4). Требует фиксации таймзоны
в докстринге + tz-aware конвертации.

**D-9 (INFO getProotTmpDir contract):** комментарий PRootKernel 950-981
прямо требует «Must be called after boot()» для валидного tmp-dir выбора
(cache vs files). startLocked вызывает его БЕЗ preflight-гарантии.
Проверить, что при первом запуске (boot ещё не завершён) возвращает чистый
cache-путь дефолтный (согласуется с фактом: cache-дир существует).
Это документированная ловушка, не дефект до проверки.

**D-10 (INFO PersistentShell.stop):** двойной `cb.onComplete` риск
(reader + stop оба дергают) — нужен флаг completed.
**D-11 (INFO telegram.py tgd_debug.log):** append-лог без ротации/лимита (TG-7).
**D-12 (INFO MinisApp.hook):** `TelegramVaultManager.ensureStarted` вызывается
без try/catch вокруг — приложение может упасть на раннем старте, если миграция
выкинет неожиданное исключение (корутина? проверить dispatcher). Hook line 433.

## Проверенные «не дефекты» (verified OK)
- migrateSession: dstSession.exists() → early-return (повторный запуск safe, M-7).
- copy-if-exists для companions согласуется с rollback-journal режимом (02 01 не встречается: header 01 01) — перенос БД full+journal консистентен при остановленном агенте.
- PersistentShell маркеры CMD_START/CMD_END: парсинг exit-code из маркера корректен; дублирование строк только в partial-line path (D-3).
- PRootKernel.wrappedArgs / agent-cmd: `-0 --link2symlink` порядок совпадает с работающим агентским argv (545/11581) — стартовый аргументный путь НЕ является причиной автостарт-FAIL.
- telegram.py whitelist/диалоги: логика корректна;/problem only в tz и RPC_TIMEOUT.

## Дизайн фикса D-1/D-2/D-3/D-10 (PersistentShell) — подготовлен, НЕ РЕАЛИЗОВАН (сигнала нет)

Уточнение модели синхронизации по запросу юзера («@Volatile недостаточно; mutex на время ожидания может
умертвить reader»):

- **Kotlin Mutex (coroutine-level), НЕ `synchronized(this)`-монитор** — write-side только. Гарантирует взаимное
исключение двух перекрывающихся `executeCommand` вызовов без блокировки reader-потока: reader-поток НЕ берёт
mutex вообще, значит nutта блокировка вывода/exit-маркера невозможна. Deadlock-free by construction.
- `pendingCallback` остаётся `@Volatile`: читается reader'ом без блокировки, пишется write-side в короткой
критической дорожке внутри mutex (set-state), очищается по завершении/отмене/таймауту/stop().
- Single-shot: флаг `completed` в CommandCallback, dispatch после которого игнорирует повторный вызов
(reader и stop() — double-resume уходит). Завершение идёт только через `cb.onComplete`, который сам resume
единственный раз (isAlive-guard уже есть в cont — дополняем completed-флагом внутри callback).
- `feedLines`: переключение на накопление хвоста (partial line persists across chunks): в `pendingOutput`
докупаем и потом маркер-строку разруливаем — D-3-дубликация уходит. Частичная последняя строка, пришедшая БЕЗ
`\n` на EOF чанка, НЕ учитывается маркером — сейчас дублируется; фикс: «partial line = last-line-without-\n»
больше не печатается в вывод и сохраняется на след. чан.
- `applyEnvironment` (D-2 сопутствующее) — записывается через тот же writer только ВНЕ активной команды:
mutex.write не обогреваем? пишется сразу после set-state-фазы; если command in-flight — добносится в очередь
до следующего command (флаг pendingEnv), нет искажения wrappedCommand.
- Cont timeout: suspendCancellableCoroutine по-прежнему: cancellation branch не теряет pendingCallback
(invokeOnCancellation уже нулит).

Проверка после реализации: локальный kotlinc-compile + контрактный unit-мок executeCommand×50 параллельно
(каждый находит свой output и exit от единственного соответствующего Wrapped-маркера, 0 потерянных),
потом CI green (тоже с [quick] сообщением, отдельный коммит).

## Дизайн v2 (протокол handshake) — готовит реализацию D-1/D-2/D-3/D-10, ждёт юзер-сигнал

Ответы на 4 нюанса юзера — каждый превращён в ИНВАРИАНТ протокола:

**Invariant-1 (race END vs следующий вызов):**
AtomicReference<CommandState?> (atomic get/set/CAS — не volatile). Команда HANDOFF: reader делает
`cb = state.getAndSet(null)` — забирает АТОМАРНО. Пока.getAndSet не выполнен: (а) следующий `executeCommand`
висит в `mutex.lock()` (write-side очередь), (б) reader получает `state == null` → output попадает в буфер;
маркер END с callback==null → orphan-обработка (D-orphans): бытность подтверждается только через размер буфера.
Поэтому Handshake трёхчастный: haber (null -> busy) -> dispatch (getAndSet null) -> ack (writer single ack).

**Invariant-2 (double complete CAS):**
`completed: AtomicBoolean` и CAS(false → true) в единственности dispatch-пути. Если CAS fail — вызов
отклонён. Reader делает CAS ДО resume; stop() — ИЖЕ ОДИН-ПУТЬ: если reader уже взял callback (state null) —
stop() не может dispatch. Fix: cb.stopRequest = AtomicBoolean; оба пути (reader stop) упираются в CAS
`cb.completed` — победитель один.

**Invariant-3 (cancellation):**
Caller Córants cancel happens in `invokeOnCancellation`:
1. CAS state: busy → dead (без снятия with lock)
2. pendingCallback autorelease: `state.getAndSet(null)` → null (потерять callback, чтобы reader не повесился)
3. родившийся вывод: пишется в buffer, но НЕТ callback: теряется только буфер, не состояние; reader sees null.
4.-orders состояние протокола «busy-with-cancelled-callback» cannot существовать: всегда либо callback
.sendStatus(cc) атомарно забран, либо сброшен. Заблокированный reader может подцепить «нет callback» → тогда
маркер END теряется, и вывод сбрасывается в buffer-илиphan. Протокол становится блокирующим: write-side
(следующий executeCommand) ожидает `plain = state == null`, а reader перед следующим dispatch сбрасывает
журнал END (не висит).
	
**Invariant-4 (pendingEnv lifecycle):**
- pendingEnv применяется только между командами: перед следующим «busy-becomes» permission check:
  `if (pendingEnv.isNotEmpty() && !writerBusy) { flush; }`, behind mutex write-side.
- write error (IO exception при записи env в writer): pendingEnv НЕ очищается; создаётся retry — сдвиг
  «нужен retry» на следующий turn; если writer сломался окончательно (isAlive==false), pendingEnv фиксируется
  как объединение лога ошибки, no-op после next start.
- stop() / killAll: весь pendingEnv очищается (потому что writer окончательно мёртв).
- cancel алгоритм caller'а НЕ должен касаться pendingEnv (он общий для executeCommand, не per-client).

**Резюме по контролюРасисма юзера:** дизайн v2:
- Race atomic: AtomicReference.getAndSet (не volatile)
- Double-complete: AtomicBoolean CAS (не Boolean)
- Cancellation: state machine busy→dead + commit-before-requeue; reader никогда не держит ссылку на мёртвую callback.
- pendingEnv: очередь, flush при переходе idle, безопасная остановка/каты и очистка при killAll.

## Дизайн v2.1 (краевой случай readLoop, не меняющий v2-инварианты)

**Найдено при повторной сверке readLoop** (строки 404+): reader читает `pendingCallback` ПЕРЕД обработкой
каждого chunk. Крит-случай «chunk содержит маркер END **и** первые байты следующей команды»: при
`getAndSet(null)` по достижению маркера остаток chunk-после-маркера потеряется.

Протокол-фикс (добавка к v2, не отмена):
- Reader: поиск маркера → `cb = state.getAndSet(null)` (атомарное снятие); **текст после маркер-строки
  до конца chunk = carry** → `pendingCarry` (AtomicReference<String>), склеивается с началом СЛЕДУЮЩЕГО
  chunk-а до парсинга (только ОДИН carry существует: writer-side mutex гарантирует серийность команд).
- Reader-vs-reader race не существует (один readLoop-поток); reader-vs-writer закрыт handshake
  (busy-set до write; END после конца write).
- Crash-recovery: carry остался, а новой callback нет (writer умер) → carry сбрасывается в stop-лог
  при stop(), не тихо теряется.

## Дизайн v2.2 (два оставшихся риска юзера, до реализации)

**RED-база (verbatim текущего кода):** маркер `__MINIS_DONE_{marker}_EXIT_{code}__` (regex-парсится);
readLoop берёт cb per-chunk; if маркер найден в chunk: append before-marker + parse(afterMarker) + onComplete
+ pendingCallback=null; **после маркера текст chunk вообще НЕ обрабатывается** (теряется); END, разрезанный
между chunk-ами: parseExitCode вернёт -1 (не находит полный `__`), а cb всё равно снимается → exit-code
теряется при split. Это реальное проявление обоих рисков юзера в текущем коде.

### Инвариант-C (cancel mid-flight → восстановление протокола)

Сценарий: cancel(A) после write, до END_A → callback снят (dead) → команда A продолжает работать в shell →
поздний END_A THEN arrives, потом запускается B.

- Дискриминант «чей вывод»: **markerId УНИКАЛЕН per-command** (8-hex). Reader ищет только
`__MINIS_DONE_{A.marker}_EXIT_`: если cb+null → нет потребителя. Поздний END_A попадает в orphan-режим.
- Где «shell непригоден»: **не переключаемся в непригоден** — shell остаётся валиден: END_A всё ещё корректно
закрывает команду A (markerId не совпадёт с B). НО проблема: **промежуточный вывод A** до END_A нельзя
отдать B (это контаминация). Решение: после cancel(A) reader входит в режим **drain-until-marker-A**:
весь текст до полного `__MINIS_DONE_{A}_EXIT_{code}__` (в т.ч. разрезанный Chunk) потребляется молча
(в orphan-лог сводно), END_A съедается, состояние протокола: **idle чистый**. Только после этого
writer-side берёт B (write-side mutex + handshake: writer ждёт `state==null` И `draining==false`).
- Ресинхронизация: ровно один маркер A (уникален) — после будущего eat города протокол синхронен.
- Если END_A никогда не придёт (команда A зависла навсегда): drain-режим блокирует B до death-of-process;
время ограничивается watchdog: drain-timeout = 2× max(command timeout) → если не закрылся — shell
объявляется **tainted** (последующий executeCommand возвращает immediate-ошибку shell-unusable,
вместо тихого зависания); stop() полная очистка — единственный выход. Явный отказ, не silent.
- Late-end race с B (B уже запущен writer'ом? — невозможно: writer под mutex не пропускает B до
drain-завершения; END_A и B не могут встречаться в одном chunk с точки зрения write-side серийности).
- if изменения состояния: busy(A) → dead(A)+draining(A) → idle. reader-vs-writer handshake остаётся.

### Инвариант-D (pendingCarry принадлежность + END-split + cleanup)

1. **Отличие carry от мусора/повторного маркера:** carry сохраняется ТОЛЬКО между END_X и концом chunk
   (мгновение пере-командного зазора). Поскольку команды строго серийны, текст ПОСЛЕ END_X — это либо
   пустота, либо байты СЛЕДУЮЩЕЙ команды (уже write'нутые? — нет: writer пишет wrappedCommand только
   ПОСЛЕ set busy; а END_X в этом же chunk физически возможен только для ЕЩЁ РАБОТАЮЩЕЙ X → после неё
   ничего из X). Значит: post-marker текст НЕ может принадлежать X; он либо empty, либо prepend-хвост
   будущей команды Y (уже возможен только после set busy(Y)). Ownership: carry обязателен В EMPTY при
   отсутствии Y; при появлении Y carry склёвается В НАЧАЛО ВЫВОДА Y (перед его маркером).
   Повторный `END_A` в carry: это глюк самогo shell (не бывает по протоколу) → ruпяется в orphan-лог
   (дисциплина: не скармливать командам).
2. **END, разрезанный между chunks (наш RED-факт):** reader ДЕРЖИТ накопленный «хвост» chunk до момента,
   когда маркер-строка полностью узнаваем: парсинг маркера делается только по завершённым строкам
   (оканчивающихся `\n`); если последняя строка chunk не содержит `\n`, она НЕ парсится как маркер, а
   переносится как **partial-tail** в следующий chunk (это то же самое место, где живёт pendingCarry:
   partial-tail и carry — ОДИН буфер). Условие «END split» перестаёт существовать как отдельный случай:
   split-маркер лежит в partial-tail → закроется в след. chunk → парс → dispatch. Exit-code не теряется.
3. **Очистка carry:** (а) dispatch команды съедает carry целиком (склеен в буфер результатов);
   (б) cancel mid-flight, YA-carry ещё пуст → carry = «» (drain-режим: новый carry рождается только
   после следующего dispatch); (в) stop()/killAll: carry переносится в orphan-лог (не тихо), затем
   очищается; (г) смерть процесса (reader EOF): carry → orphan-лог, потом reader выходит из цикла;
   (д) tainted-shell: carry → orphan-лог.
4. **AtomicReference<String> не «гарантии протокола»:** гарантии даёт совокупность — 单 (write mutex),
   (б) маркер-уникальность, (в) парсинг только по полным строкам, (г) handshake busy→END. AtomicRef —
   только transport без потери значений (атомарность set/get, not visible torn).

### Контракт-тесты (5 сценариев юзера, обязательны после ready)

1. **Cancel-A → late-END_A → запуск B:** B должен получить ТОЛЬКО свой вывод; вывод A до его END
   уходит в orphan-лог; синхронизация = idle чистый; «Ошибка» не бывает.
2. **END, разрезанный между chunks:** exit-code Parced корректно (partial-tail механизм), callback
   снимается ровно один раз, вывод не дублируется.
3. **END_A + начало B в одном chunk:** before-marker = вывод A, after-marker = carry → результат B
   начинается с carry-склейки, ничего не теряется и не дублируется.
4. **stop() одновременно с завершением команды:** гонка «dispatch vs teardown» закрывается
   completed-CAS: ровно один победитель; carry → orphan-лог; cont.resume не дублируется.
5. **Смерть shell при непустом carry:** reader EOF → carry → orphan-лог (не тихая потеря),
   затем все ожидающие callbacks получают onComplete(смерть, exit=-1) либо ошибку из
   executeCommand (isAlive=false check), очереди не висят.

## РЕАЛИЗАЦИЯ (a) ЗАВЕРШЕНА И ПРИНЯТА CI (коммиты 50911d0 → acccf94; run 39483947146 SUCCESS)

PersistentShell.kt D-1/D-2/D-3/D-10 — реализовано; ShellProtocolEngine — выделенный протокол-движок;
ShellProtocolEngineTest — 7 сценариев, ВСЕ PASSED в реальном прогоне (Unit Tests workflow job, не skipped):
s1 последовательность (изол. idle-echo), s2 single-dispatch CAS, s3 split-маркер, s4 same-chunk overshoot,
s5 смерть с carry, s6 cancel-фрагмент drain, late-END-after-timeout.

**3 бага захвачены живыми тестами** (не структурной проверкой):
1. 6df48e1 — release-slot после dispatch ( незаконный свойства state machine Engine vs PersistentShell mismatch)
2. aafa54c — потеряная декларация current в редактировании (merge-авария)
3. acccf94 — **idle-gap bytes (current==null, вне dispatch-чанка) → orphan-лог, а НЕ byte-carry**;
   carry-owner правило v2.3-D действует только для same-chunk overshoot после dispatch.

Не завершено (по плану): wiring PersistentShell→Engine отдельным коммитом (граница — только после юзер-оk),
loader-диагностика (b), D-4/D-5/D-12 (TVM/MinisApp), D-6/D-7/D-8/D-11 (telegram.py). Статусы vc144/автостарт/replay
не менялись.

## РЕАЛИЗАЦИЯ: пошаговый финал (20.10 ночной стек)

**PersistentShell.kt патч (852 строк) — в working tree, весь замысел v2.3 укоренён:**
D-1: AtomicReference commandState + writeMutex (executeCommand под writeMutex: re-check alive → flushPendingEnvLocked → install cb → WRITE wrapped → await → finally-unlock; он НЕ держится во время ожидания END маркера; reader не берёт mutex) → параллельные executeCommand вызовы строго серийны по записи; читающий поток свободен.
D-2/D-10: в CommandCallback добавлен completed + markDone() = synchronized CAS. Все 3 пути завершения (marker dispatch / EOF / stop) проходят через markDone → ровно один resume.
D-3a: feedLines — только полные строки; partial не излучается (не дублируется на границе chunk'ов).
D-3b: pendingCarry + fold в head каждого chunk + lastSafeAppendIndex(text, marker) — детектирует РЕЗАНЫЙ маркер в tail → сохраняется в carry до следующего chunk.
applyEnvironment: writeMutex + если in-flight → очередь (pendingEnv, snapshot overlay by previousKeys) → flush между командами (в executeCommand при входе).

**ShellProtocolEngine.kt — выделенный протокол-движок**: чистый класс (данные: current, carry, done; методы feed/detachCurrent/resetOrphan/logOrphan) + TestCallback для JUnit; PersistentShell остаётся оригинальным (рефакторинг на Engine — следующий коммит, был бы scope creep сейчас).

**ShellProtocolEngineTest.kt — 6 acceptance-сценариев:** parallel-marker, stop-vs-complete, END-split, overshoot-with-dup, EOF-смерть, partial-marker-в-tail. Тест независим от Android.

## Статус РЕАЛИЗАЦИИ (20.10 вечер): код-патч в работе — полный takeaway ревью зафиксирован в REVIEW doc

**Что подтверждено ревью каждой строки (601 строк, прочитаны slice'ами 1-60 дважды, 40-96, 96-131, 131-166, 166-230, 230-300, 300-368, 368-407, 408-434, 425-495, 488-545, 495-545, 570-601):**

- D-1: `pendingCallback` = plain `@Volatile var` (запись в executeCommand line 519 `pendingCallback = cb`, снятие при EOF/stop/timeout/cancel через `= null`). ReadLoop читает per-chunk (line 378). Нет атомарного handoff → перезапись ровно как описано в v2.
- D-2/D-10: `cb.onComplete = { if (cont.isActive) cont.resume(...) }` — НЕ CAS; три вызыва: readLoop(395-406), EOF(420-434), stop()(585-601).
- D-3a: feedLines(435-448) — partial-строка (i == last && без \n) вызывается, на след. чанке она же вернётся первой строкой → дубликат.
- D-3b (ФАТАЛЬНЫЙ): маркёр ищется `text.indexOf("__MINIS_DONE_{marker}_EXIT_")` в chunk(4096B). Если маркёр разрезан между двумя chunk → НЕ найден никогда → output растёт, onComplete не приходит → 600 с зависание. Exit-code regex `(\d+)` не ловит -1 (там literal -1 отдельно — ок).
- EOF-handler(420-434): `cb.onComplete?.invoke(...,-1); pendingCallback = null` — резюме БЕЗ CAS.
- stop()(585-601): nullify writer/process → killExecutor; `cb?.let { it.onComplete?.invoke(it.output.toString(), -1) }` — второй резюме гонка.
- steerInput(470-481) — семантика bg-steer сохранена; writer synchronized(this) — writer-write коллизий с applyEnvironment нет (оба пишут через writer write(), race writer-write vs writer-write между applyEnv и executeCommand уже есть — pendingEnv layout исправит).
- applyEnvironment(545-585): пишет unset/export в writer БЕЗ проверки, идёт ли команда → перемешивает wrappedCommand (D-2 сопутствующий).

**Callers (не сломать):** ExecutionCoordinator.executeCommand (serializes на per-session Mutex), ExecutionCoordinator.broadcastTimezoneChange/broadcastProxyChange (applyEnvironment ВНЕ лока), TerminalSession (собственный PTY, не через PS), MinisApp→TelegramVaultManager (не PS). steerInput/stop публичные.

## Дизайн v2.3 (разрешение конфликта «cancel → сброс carry»)

**Блокер юзера (контр-сценарий):** cancel(A) в момент, когда partial-tail уже содержит НАЧАЛО маркера A
(`__MINIS_DONE_a1b2`, без хвоста). Наивный «cancel → carry reset» уничтожает фрагмент → остаток маркера
приходит след. chunk, полный маркер собрать нечем → draining(A) никогда не завершится, хотя shell исправен.
Уникальность markerId не восстанавливает потерянные байты.

**Правило v2.3 (уточнение cleanup-матрицы, единственное изменение):**
`cancel(A)` сбрасывает **пользовательский вывод** A и сбрасывает carry ТОЛЬКО ЕСЛИ carry гарантированно
не содержит фрагмент маркера A (быстрая проверка: carry.startsWith(prefix-marker) ИЛИ carry содержит
`__MINIS_DONE_`-фрагмент с маркер-id префиксом, matches `__MINIS_DONE_[0-9a-f]{1,8}$|` частичный префикс).
Если фрагмент маркера находится: carry **сохраняется** при cancel и остаётся доступным drain-парсеру;
drain-режим продолжается с уже накопленным контекстом (не с нуля).
Вывод A (до маркера) при cancel: очищается из буферов пользователя (никогда не достигает будущих команд).
Двойной буфер не нужен: carry = один буфер, его «принадлежность» определяется режимом (pending / draining-A).

**Расширенная cleanup-матрица v2.3:**
| Событие | Пользователь-вывод A | carry (без фрагмента маркера) | carry (фрагмент маркера A) |
|---|---|---|---|
| dispatch | съеден callback'ом | eaten следующей | eaten следующей |
| cancel(A), фрагмента нет | очищен | сброс ('' — безопасно) | n/a |
| cancel(A), фрагмент есть | очищен | n/a | СОХРАНЁН (draining продолжается с контекстом) |
| stop()/killAll | → orphan-лог | → orphan-лог | → orphan-лог |
| смерть process (EOF) | → orphan-лог | → orphan-лог | → orphan-лог |
| tainted (watchdog) | → orphan-лог | → orphan-лог | → orphan-лог |

Приоритет check'ей при cancel: сначала определить наличие фрагмента маркера A (детерминированный regex на
текущем содержимом carry), и только потом решить — сброс или сохранение. Порядок важен: не наоборот.

**Новый acceptance-сценарий #6 (добавка к списку юзера):**
`cancel-A → partial-END_A(_`__MINIS_DONE_a1b2`-фрагмент) → cancel-cleanup → remainder-END_A → B`
Ожидания:
- A корректно дренируется (фрагмент выжил + remainder достроил полный маркер → END_A съедается);
- B получает ТОЛЬКО свой вывод (никаких остатков A);
- ноль дублирования, ноль потерь;
- если маркер не достроился до watchdog-предела → B получает явный отказ `shell-unusable` (не тиша).
Реализация теста: mock-shell производит chunk1 = «вывод A…\n__MINIS_DONE_a1b2» (без _EXIT_) и chunk2
= «_EXIT_0__\nвывод B»; cancel срабатывает между chunk1 и chunk2; проверяется: partial выжил, drain
завершился, B чист.

**v2.3 статус:** инварианты v2+v2.1+v2.2 сохранены; cleanup-матрица уточнена (cancel не уничтожает
partial-фрагмент маркера draining-цели); добавлен acceptance-сценарий #6. Код не менялся. Сигнал `ready`
юзера на изолированную реализацию D-1/D-2/D-3/D-10 — ждём отдельно (6 acceptance-сценариев = критерии
приемки).
1. **Чего хочет юзер от vault-цепочки:** автостарт MCP-сервера Telegram внутри proot при старте приложения, без агент-вмешательства.
2. **Что должен увидеть:** server.log «vault MCP launched» факт рабочего состояния, порт 7824 OPEN, RPC tools=20.
3. **Соответствует ли реализация:** нет — текущее поведение после рестарта: лог печатается (566 б), порт CLOSED, execve denied первичен.
4. **Реальное/граничное/ошибочное:** рестарт при живом агент-сервере → D-4 (миграция гоняет копию под живым SQLite); параллельные RPC → D-1/D-2/D-7; партиальная строка лога → D-3.
5. **Исправления:** отдельный коммит после юзер-согласования (user protocol: не менять код до решения). Фиксы изолированы: D-1..D-3/D-10 (PersistentShell), D-4/D-5/D-12 (TVM/MinisApp), D-6..D-8/D-11 (telegram.py). НЕ относятся к автостарту напрямую (ветка loader-исследования отдельна).
