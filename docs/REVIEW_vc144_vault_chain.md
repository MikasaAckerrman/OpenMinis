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

**v2.1 статус:** инварианты v2 сохранены, добавлен carry-invariant. Все 5 вопросов (race, double-complete,
cancellation, pendingEnv, carry) закрыты формально. Коммит: НЕТ кода, только этот док. signal у юзера —
отдельное явное подтверждение.
1. **Чего хочет юзер от vault-цепочки:** автостарт MCP-сервера Telegram внутри proot при старте приложения, без агент-вмешательства.
2. **Что должен увидеть:** server.log «vault MCP launched» факт рабочего состояния, порт 7824 OPEN, RPC tools=20.
3. **Соответствует ли реализация:** нет — текущее поведение после рестарта: лог печатается (566 б), порт CLOSED, execve denied первичен.
4. **Реальное/граничное/ошибочное:** рестарт при живом агент-сервере → D-4 (миграция гоняет копию под живым SQLite); параллельные RPC → D-1/D-2/D-7; партиальная строка лога → D-3.
5. **Исправления:** отдельный коммит после юзер-согласования (user protocol: не менять код до решения). Фиксы изолированы: D-1..D-3/D-10 (PersistentShell), D-4/D-5/D-12 (TVM/MinisApp), D-6..D-8/D-11 (telegram.py). НЕ относятся к автостарту напрямую (ветка loader-исследования отдельна).
