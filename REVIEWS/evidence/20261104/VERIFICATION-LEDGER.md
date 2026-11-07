# VERIFICATION LEDGER (реестр утверждений) — 2026-11-05
Правило: каждое утверждение = [статус | улика | чего не хватает]. Commit этого
реестра: см. git log. HEAD на момент записи: 6719cbd2.

## 1. CI (ссылки на прогоны GitHub Actions)
| SHA | Workflow | Run ID | Результат |
|---|---|---|---|
| 2db40b1d | Build APK | 41727330539 | success (по API-поллу сессии) |
| 2db40b1d | Unit Tests | 41727330400 | failure (s11 pre-existing, закарантинен) |
| 4c161ab2 | Unit Tests | 41729159120 | **success** |
| 851e27ad | Build APK | 41732617508 | **success** |
| 851e27ad | Unit Tests | 41732617552 | failure (LiveTurnBuffer OOM-флак, пофикшен e144ab54) |
| 66ec77c2 | Unit Tests | 42081839005 | **success** (утро 05.11: ACRA LOGCAT removal) |
| 0575f47b | Unit Tests | 42084272975 | **success** (утро 05.11: promptId-дедуп; Build APK skipped — тег [test] only, head_sha сверен с полным SHA) |
| 511cba19 | Unit Tests | 42093519149 | **success** (вечер 05.11: FGS cold-start edge — miss без ICU на main) |
| e144ab54 | Unit Tests | (id не записан) | **success** |
| 69b2de06 | Build APK | 41874767970 | **success** (источник diag-артефакта) |
Чего не хватает: прогоны для HEAD 6719cbd2 (docs-only) не
запрашивались; независимой перепроверки логов ревьюером не было.

## 2. Артефакт/устройства
- diag-APK (69b2de06) sha256: e111ca4c9065338a8902fe32546c176b… (файл /tmp/diag_apk/app-clone.apk)
- Waydroid: установлен этот файл, запуск подтверждён (pid + gate-лог) —
  [проверено], identity-сверка pm-path↔sha на Waydroid — [не выполнялась]
- iQOO: установлен этот файл (adb install -r), гейт-подтверждение в logcat —
  [проверено]; on-device sha-сверка и tombstone — [BLOCKED_DEVICE, adb потерян]

## 3. Утверждения по B27/нативным крашам
- «FGS/alarms безопасны» → ПЕРЕФОРМУЛИРОВАНО: «статически проверено в указанном
  объёме (AgentForegroundService.kt onStartCommand/safe-mode/тип; AlarmReceiver;
  manifest:16/27/50/507), дефектов не найдено; runtime не проверен; ограничения
  зависят от targetSdk/способа запуска/doze-режима». Остаточные случаи: FGS-onDestroy
  при активных сессиях; alarm-точность в doze; ScheduledTaskManager — не прочитан.
- «Хендлер маскирует причину SIGABRT-серии» → ГИПОТЕЗА. Факт: 0 установок хендлера
  на диагностической сборке; Факт: контролируемый SIGABRT убил процесс БЕЗ
  системного дампа до потери adb. Не установлено: получил бы debuggerd дамп при
  живом adb. SA_RESETHAND/починка unwind — ГИПОТЕЗЫ, не «фикс».
- Причина SIGABRT-серии: НЕИЗВЕСТНА.

## 4. Очередь/рекурсия
- Рекурсивный путь enqueue→sendMessage отсутствует в коде HEAD [проверено grep-ом;
  независимо НЕ перепроверено]. Стресс A–D не гонялся → поведение под нагрузкой —
  UNVERIFIED. at-least-once = РЕАЛЬНОЕ поведение (дубли возможны), exactly-once —
  не заявляется.
- [0575f47b, утро 05.11] Дубль окна сценария B (смерть между appendMessage и
  persistPromptQueue) закрыт на уровне КОДА: маркер принятия атомарен строке
  (messages.source_prompt_ids, тот же INSERT), дедуп на трёх точках приёма,
  fail-open в сторону дубля (никогда потери). Тесты pure+Robolectric в CI
  42084272975 SUCCESS [проверено]. Стресс-B (реальный kill -9 в окне) и
  runtime-проверка на устройстве — BLOCKED_DEVICE. Гарантия доставки:
  at-least-once с дедупликацией ПРИНЯТИЯ (exactly-once приёма сообщений в DB);
  exactly-once внешних действий НЕ заявляется.

## 5. Мост
- Компоненты смоукнуты (очередь/дедуп/watchdog/хуки) [проверено].
- ZCode-headless: вход найден (ELECTRON_RUN_AS_NODE + zcode.cjs, версия 0.17.1),
  НО реальный запрос не выполнен: 2 попытки → BLOCKED_PROVIDER_SELECTION
  (dashscope/gpt-6-astra с неверным ключом; --model в этой сборке отсутствует).
  ELECTRON_RUN_AS_NODE вход = НЕСТАБИЛЬНЫЙ внутренний интерфейс до реального
  smoke. agy: не установлен → BLOCKED. Сквозной «агент завершил → результат
  передан» — UNVERIFIED.

## 6. Статус приложения
**Кандидатный билд с известными непроверенными рисками.** Не «можно спокойно
пользоваться».


## 7. Доработка B27 (2026-11-05): ScheduledTaskManager + DebugServer
- ScheduledTaskManager: прочитан целиком; краш-путей не найдено [LOW: PendingIntent
  hash-коллизия, ANR-риск store-зависимый — store НЕ проверен]. Статус: статически
  проверено; runtime не проверен.
- DebugServer: прочитан; release-гейт подтверждён статически (BuildConfig.DEBUG в
  MinisApp.kt:1014); auth constant-time + token-gate для non-loopback ✓; [LOW]
  вводящий в заблуждение лог 127.0.0.1 (фактически 0.0.0.0). DebugRPCHandler
  surface (1890 строк) — НЕ ПРОВЕРЕНО в этом объёме. Статус: статически проверено;
  runtime не проверен.


## 8. Доработка B27 (2026-11-05): DebugRPCHandler + PendingIntent уточнение
- PendingIntent: коллизия requestCode ПОДТВЕРЖДЕНА ПО КОНТРАКТУ — identity-поля
  Intent задач идентичны (action/component совпадают, extras в filterEquals не
  участвуют) → коллизия requestCode с необходимостью перезаписывает. Runtime не
  проверен. Последствие: пропущенное/чужое срабатывание будильника, не краш.
- Inexact fallback: СЕМАНТИКА СРОКА НЕ ПРОВЕРЕНА (Doze может откладывать;
  требования задач к точности не формализованы).
- DebugRPCHandler: 50 маршрутов; auth — единая точка ДО dispatch (Server:243-248),
  покрывает все маршруты; non-loopback требует токен, loopback token-free.
  Транспорт = голый HTTP → токен перехватываем в пути (подтверждено статически).
  [HIGH-экспозиция] debug.shellExecute (произвольное исполнение в песочнице,
  БЕЗ DEBUG-гейта в отличие от 4 гейтованных exec-методов), writeFile/readFile/
  rawLs, browser.executeJS, tap/inputText, agent.graph.run/seed, engineSwap,
  minisConfig.exec — доступны LAN-клиенту с токеном. На iQOO СЕЙЧАС стоит
  диагностический DEBUG-билд → сервер активен в Wi-Fi до замены билда.
  Владелец уведомлён; рекомендация: замена билда при первой возможности.

## 9. Именование символов исправлено (по ревью 6517cc1a)
- d5624f14: symbol-libs сохраняют ОРИГИНАЛЬНЫЕ имена в per-ABI структуре
  (symbol-libs/<rel-path>/arm64-v8a/libminis_crash_handler.so) — ndk-stack
  -sym работает без переименований; фильтр -path "*obj/*" исключает
  stripped-копии из merged_native_libs.
- Прогон 42070450067 (6517cc1a) даст библиотеки со старым md5-именованием —
  пригодны для llvm-addr2line вручную (path: 6517cc1a).
- Проверка появления артефакта unstripped-native-libs — по завершении
  прогона d5624f14.

## 10. Статус на утро 2026-11-05
- FGS ICU-фикс (1833260c): Unit Tests SUCCESS (compile+регресс в облаке) —
  [проверено]. Runtime-подтверждение (HANG-профиль исчез из stall-лога) —
  BLOCKED_DEVICE: Waydroid adb-сессия уснула, iQOO отключён владельцем.
- Оба устройства ждут восстановления ADB; протоколы готовы
  (iQOO: D1 kill-6/logcat-стрим; Waydroid: tombstone+logcat вотч, 15с подъём).
- Приложение на устройствах: iQOO — фикс-билд 09e99632+; Infinix — diag 69b2de06
  (де-скоуплен владельцем); Waydroid — diag 69b2de06 (сессия остановлена).

## 11. Утренний раунд 2026-11-05 (кодоые приоритеты чек-листа, без устройств)
- **Приоритет 2 — ACRA LOGCAT** (66ec77c2): ReportField.LOGCAT + мёртвый
  withLogcatArguments удалены из ACRA-конфига (MinisApp.attachBaseContext) →
  синхронный logcat-коллектор (FutureTask.get ~2.9s в crash-пути из stall-лога)
  недостижим. Безопасность перехода: CrashFileSender читает поле через
  isNullOrBlank-guard (CrashFileReporter.kt:46) — секция просто пропускается;
  AppLogger уже зеркалирует logcat в дневной файл ([LOGCAT]-строки) — контекст
  краша не теряется. Unit Tests 42081839005 SUCCESS [проверено]. Runtime
  (HANG-профиль исчез из stall-лога нового билда) — BLOCKED_DEVICE.
- **Приоритет 3 — promptId-дедуп** (0575f47b): схема = атомарный маркер в строке
  (source_prompt_ids, тот же INSERT) + дедуп на трёх точках приёма; миграция
  15→16 по прецеденту MIGRATION_2_3 (nullable TEXT, валидация Room проходит);
  retry-пути проверены читкой — ни один не пере-ставляет использованный id,
  [T-queue-dedup-killer] (тихий съедатель повторного ввода) структурно
  исключён (UUID-ids, не текст). Unit Tests 42084272975 SUCCESS [проверено].
  Что не проверено: стресс-B на устройстве (kill -9 в окне), поведение
  миграции на живой БД 10k+ строк (CI гоняет in-memory), независимый
  ревью-проход по диффу 0575f47b.
- Ремоуты origin/mirror синхронизированы на 0575f47b [проверено rev-parse].
- **Внешний ревью: SHIP (одобрено к внедрению)** на 66ec77c2 + 0575f47b —
  независимо сверены: null-guard CrashFileSender, O(1) миграция 15→16,
  fail-open на 3 точках, одноразовость promptId, 9 тестов, оба CI-прогона
  по API, sync 4610cd44 [проверено ревьюером]. Остатки — BLOCKED_DEVICE:
  stall-профиль на реальном краше; стресс-B kill -9; миграция поверх
  исторической БД телефона.

## 12. Вечер 2026-11-05 — бэклог без устройств (после SHIP)
- **Бэклог №1 закрыт — FGS cold-start edge** (511cba19, ГО внешнего ревьюера):
  getOrPut в sessionLabelCached возвращал ~3s холодную загрузку ICU-таблиц на
  main, если onStartCommand выигрывал гонку у fgs-icu-warmup (1-3мс на
  Thread.start). Теперь: miss в прогретом диапазоне 0..12 → ASCII-фоллбек
  ("1 session"/"N sessions", форма совпадает с values/strings.xml плюралом),
  warmup-тред пишет локализованные строки НАПРЯМУЮ в кэш; miss вне диапазона
  (>12) → прямой getQuantityString (таблицы уже загружены warmup-тредом) —
  наивный вариант «ASCII на любой miss» заморозил бы английские метки для
  >12 навсегда. Unit Tests 42093519149 SUCCESS [проверено]. Runtime —
  BLOCKED_DEVICE (первый тик FGS на устройстве).
- **Бэклог №2 закрыт БЕЗ правок** (аудит ревьюера): debug.shellExecute
  отсечён транспортом — DebugServer стартует только в DEBUG
  (MinisApp.kt:1022), единый isAuthorized-гейт ДО RPC-диспатча: loopback
  доверенный, LAN требует X-Minis-Token из files/debug_server_token
  (root/run-as). Дополнительных правок не требуется.
- **Бэклог №3 закрыт БЕЗ правок** (аудит ревьюера): ScheduledTaskStore —
  SharedPreferences + JSON, датасет 0-5 задач, запись .apply() (L55),
  чтение runCatching с пропуском битых строк (L26-28). ANR-риска нет.
- Ремоуты origin/mirror синхронизированы [проверено rev-parse].
- **Финальный ревью-вердикт: SHIP (безусловно одобрено)** на 511cba19 +
  6f3051e6; отклонение от предписания (split 0..12 ASCII / >12 прямой вызов)
  оценено как «отличная инженерная находка»: гонка устранена структурно,
  локализация больших счётчиков не поломана. Реестр дня (независимо
  подтверждено ревьюером): 038fbfc5 рекурсия очереди • 09e99632
  вложения+commit() • d5624f14 unstripped .so • 1833260c FGS-фриз •
  66ec77c2 ACRA • 0575f47b сценарий B • 511cba19 cold-start edge — все
  SHIP; shellExecute + ScheduledTaskStore — закрыто аудитами без правок.
  Кодовый фронт: пуст. Аппаратный фронт: BLOCKED_DEVICE (Waydroid
  tombstone-watch / iQOO D1 при подъёме ADB).

## 13. Ночь 2026-11-06 — B14 (SecretRedaction) и J6 (Background Monitoring)
- **B14 — Системный слой маскировки секретов в логах** (08fc594d):
  - `logging/SecretRedaction.kt`: консервативный набор регулярных выражений:
    Bearer (payload >= 12 символов И содержит цифру - легитимные фразы
    вроде "bearer token rotation" выживают), sk- с поддержкой дефисов
    (включая Anthropic sk-ant-api03-...) и lookbehind, GitHub токены
    (ghp_, gho_, ghu_, ghs_, ghr_), x-minis-token (ключ сохраняется,
    значение маскируется). Общая key=value сеть сознательно исключена,
    чтобы не портить диагностику путей и идентификаторов.
  - Врезка во все 3 файловых sink'а `AppLogger.kt`: `log()`,
    `writeLogcatLine()`, `writeFileLine()`.
  - 9 pure-JVM тестов в `SecretRedactionTest.kt`. При локальном ревью
    выявлена и исправлена опечатка в тесте (строка без вызова маски),
    предотвратив сбой компиляции в CI.
- **J6 / B6 — Вшитый промпт фонового мониторинга**:
  - Подтверждена интеграция `SystemPromptBuilder.BACKGROUND_MONITORING`
    в базовый промпт чата (`ChatViewModel.kt:18652`).
  - Добавлен проверочный JVM-тест `BackgroundMonitoringPromptTest.kt`
    (проверка наличия, этапов жизненного цикла run_id, лимитов API и
    строгих запретов sleep/таймеров).
  - Статус жалобы J6 в `AGENTS.md` и пункта B6 в `FIXES_TABLE.md` обновлён
    на «исправлено / закрыто».
- **CI верификация**:
  - Run ID: `42106809652` (Unit Tests) - SUCCESS [проверено через API].
  - 9 тестов SecretRedactionTest + 4 теста BackgroundMonitoringPromptTest
    прошли в общем наборе unit тестов.
  - Ремоуты origin и mirror синхронизированы на `d5e907b6` [проверено rev-parse].
  - Вердикт: **SHIP** для B14 (SecretRedaction) и J6/B6 (BackgroundMonitoring).
- **Аппаратный фронт**: BLOCKED_DEVICE (устройства оффлайн, ждём ADB).

## 14. Ночь 2026-11-06 — B11 (Zip-slip защита в TarExtractor)
- **B11 — Защита от path traversal при распаковке tar**:
  - `sandbox/TarExtractor.kt`: выделен чистый JVM-компонент распаковки POSIX tar.
    Добавлена проверка выхода за пределы targetDir через `canonicalOut.path.startsWith(canonicalTarget.path + File.separator)`.
    Аналогичная проверка для целей hard link (`canonicalLinkTarget`).
    При попытке побега выбрасывается `SecurityException`.
  - `sandbox/RootfsManager.kt`: делегирует распаковку в `TarExtractor`,
    сохраняя бинарную и сигнатурную совместимость.
  - `test/…/TarExtractionTest.kt`: удалена устаревшая копия `extractTarHelper`,
    тесты переведены на реальный production-код `TarExtractor`.
    Добавлено 4 теста на защиту от path traversal:
    относительный путь (`../../evil.sh`), prefix-поле, hard link наружу,
    и корректный относительный путь внутри каталога (`dir/../dir/safe.txt`).
- **CI верификация**:
  - Run ID: `42108930079` (Unit Tests) - SUCCESS [проверено через API].
  - 18 тестов TarExtractionTest прошли в общем наборе unit тестов.
  - Ремоуты origin и mirror синхронизированы на `e0b7ecd1` [проверено rev-parse].
  - Вердикт ревьюера: **SHIP** для B11 (Zip-slip защита в TarExtractor).
- **Текущий статус ночи (01:08 06.11.2026)**:
  - B14 (SecretRedaction) - SHIP (`08fc594d`, CI `42106809652`).
  - J6/B6 (BackgroundMonitoring) - SHIP (`d5e907b6`, CI `42106809652`).
  - B11 (TarExtractor Zip-slip) - SHIP (`e0b7ecd1`, CI `42108930079`).
  - Аппаратный фронт: BLOCKED_DEVICE (устройства оффлайн, ждём ADB).



## 15. Утро 2026-11-06 — B13 (idle-vs-max watchdog scheduled-прогонов)

- **Задача** ([статус | улика | чего не хватает]): плоский `RUN_TIMEOUT_MS=10 мин`
  (ScheduledAgentRunner.kt:34) — два режима отказа: живые 15-20-минутные прогоны
  убивались на потолке; зависший сокет сжигал все 10 минут до констатации.
  Улика: ревью-разбор + код wait-точек (`withTimeoutOrNull(timeoutMs){isStreaming.first{!it}}`
  ×3 в HeadlessChatRunner). Не хватало: runtime-прогона на устройстве (устройство
  вернулось, но стресс-B ещё впереди).
- **Решение (8c8e481e, push origin+mirror)**:
  1. `debug/RunWatchdogPolicy.kt` — чистая политика: `(elapsedRun, elapsedSilent, idle?, max) →
     Continue | Abort(IDLE) | Abort(MAX_RUN)`; `>=` = нарушено; при двойном нарушении
     MAX_RUN приоритетен; `idle=null` = легаси-плоское поведение. 5 boundary-тестов.
  2. `ChatViewModel`: `_toolHeartbeat` StateFlow — счётчик тикает каждые 30с ВНУТРИ
     `executeTool` (обёртка `withToolHeartbeat`, тело → `executeToolImpl`, choke point
     единственный — параллельный и последовательный пути оба идут через executeTool).
     Обоснование: тихий тул-запуск (build/тесты) НЕ мутирует `_messages` между
     dispatch и результатом — без этого пульса idle-детектор убивал бы живую сборку
     (тот же класс ложного срабатывания, что чиним). TTFT-ожидание пульса НЕ даёт
     сознательно: сокет без следующего чанка — это и есть целевой stall.
  3. `HeadlessChatRunner`: общий `awaitTurnSettled(vm, timeoutMs, idleMs)` на
     Dispatchers.Default заменил все три плоских wait'а (prompt/retry/rerunFromToolBlock).
     Сигнатура пульса: размер списка + hash последнего ChatMessage (data class →
     хэш следует за контентом) + флаг стриминга + heartbeat. Окно сна = min(остаток
     idle, остаток max); пульс внутри окна сбрасывает счётчик тишины; тишина дольше
     окна → вердикт. `idleMs: Long? = null` → RPC-поверхность (ChatMutationMethods,
     контракт harness) семантически не тронута; opt-in только из ScheduledAgentRunner.
  4. `ScheduledAgentRunner`: idle=3 мин, max=30 мин (было плоско 10 мин).
- **Отличие от андроид-референса** (AndCode ScheduleRunWatchdog.kt:40-74): пульс
  по toolHeartbeat + hash последнего ChatMessage вместо хэша частей транскрипта —
  причина: транскрипт-хэш не спасает от того же окна тишины внутри тул-запуска.
- **НЕ входит в дифф (атомарность)**: retry-лестница 10/20/30 и reconcileStaleRuns
  при рестарте — остались отдельными пунктами бэклога.
- **CI верификация**: Run ID `42263011261` (Unit Tests) — SUCCESS [проверено через
  API, head_sha сверен с полным SHA 8c8e481e5bf224614c5ed7403356c691ebeccfbe].
  Build APK skipped ([test]-маршрут). Все 5 тестов RunWatchdogPolicyTest прошли;
  компиляция всего B13-кода подтверждена CI.
- **Чего не хватает (BLOCKED_DEVICE / runtime)**: реальный idle-abort на зависшем
  сокете и спасение 15-минутного прогона наблюдаются только на устройстве
  (стресс-B/D1-протокол при iQOO). Пока [статус: РЕАЛИЗОВАНО+CI-зелёный | улика:
  код+тесты+CI | не хватает: runtime-демонстрация].

## 16. День 2026-11-06 — P1 (CPU/IO-ворота, движок №2) + P2 (prefix-батчи, движок №1)

Направление ревьюера: «параллелизм как в zcode» — разбор двух движков,
диагноз (408 МБ minis.db, весь список в памяти, O(N*blocks) flatItems),
Шаги 1-3. Реализованы Шаги 1-2 двумя АТОМАРНЫМИ диффами; Шаг 3 —
дизайн-предложение без кода (риск для restore/dedup/compaction — ждёт
благословения).

### Дифф A — P1-io-concurrency (2d0e9c10, CI 42269766733 SUCCESS)
[статус: РЕАЛИЗОВАНО+CI | улика: код+7 новых тестов+run 42269766733 |
не хватает: runtime-демо параллельных file_write — движок №2 opt-in]
- `AgentConcurrency.ioParallelCeiling`: 16 (≥8 ядер) / 8; БЕЗ thermal-делителя
  (обоснование в док-комменте: thermal про CPU-работу, мелкие записи — нет).
- `ToolConcurrencyClass` (pure): IO-набор = file_read/file_write/file_edit/
  read_image/webfetch; всё остальное (shell/browser/spawn/mcp) — CPU-ворота.
- `ToolScheduler`: ctor `ioMaxConcurrent` + `ioCap()`; webfetch в
  KEY_DECLARING_TOOLS.
- `JsonPathExtractor`: webfetch → `net:<url>`. БЫЛО: webfetch без ключей =
  GLOBAL-герметик в planReactive (dependencies обе стороны) — т.е. кроме
  семафора он сериализовался сам. Стало: разные URL независимы; одинаковый —
  read+read (параллелен по правилу чтений; GET идемпотентен); пустой/битый
  url → null (GLOBAL) — никогда пустой ключ.
- `EngineAgentLoop`: cpuGate/ioGate, выбор ворота на вызов ПОСЛЕ await
  зависимостей (ждущий вызов по-прежнему не держит слот).
- Решение о thermal для IO — осознанное отклонение от возможного
  «зеркалить divider»: зафиксировано в коде и таблице.

### Дифф B — P2-prefix-batches (59a99502, CI 42271048740 SUCCESS)
[статус: РЕАЛИЗОВАНО+CI | улика: код+5 тестов+run 42271048740 |
не хватает: runtime-демо смешанного батча на устройстве]
- `ToolBatchPlanner.parallelPrefixSize`: максимальный префикс safe+non-conflict;
  первый барьерный тул и далее — последовательно. canParallelize не тронут
  (полный параллельный случай + его тесты).
- Экзекьютор (ChatViewModel ~15293-15676): параллельная ветвь над
  take(prefix) (mark-RUNNING, async, пост-обработка, лог); исходный
  последовательный путь НЕ изменён, работает над drop(prefix) либо всем
  батчем; порядок resultParts/model intent сохранён; шаред пост-код раунда
  (awaiting-model update, persistAssistantTurn, ...) общий для обеих фаз.
- Честная оговорка: префикс — компромисс; полный «reactive-DAG» параллелизм
  в проде достигается только свапом на движок №2 (И3) — решение продуктовое.

### Шаг 3 — НЕ реализован (ждёт благословения)
Hot window (последние ~50 сообщений в _messages, остальное лениво) затрагивает
сердце: restore, source_prompt_ids-дедуп, compaction-границы, queued prompts,
T180 attachment-restore. Rust seam (файловые манипуляции/индексация вне
Android Context) — отдельный модуль. Проект дизайна — в отчёте ZCode.

## 17. День 2026-11-06 (вечер) — первый блок спринта B32+B33+B28

Ревьюер: SHIP по P1/P2; реестр B28-B33 зафиксирован его коммитом 36a8c8c3;
ТЗ с точными координатами. Реализован первый блок — три атомарных диффа.

### B32 — скролл-якорь (d1b4c249, CI 42276415825 SUCCESS)
[статус: РЕАЛИЗОВАНО+CI | улика: код+run | не хватает: подтверждение на устройстве]
Корень (сверен по коду): `listState` — VM-scoped LazyListState (анти-white-flash,
переживает смены чата, ChatViewModel:702); `flatItems = remember(sessionId)
{emptyList()}` (ChatScreen:3002). Первая непустая публикация наследовала якорь
предыдущей сессии → застревание в середине. Фикс: `LaunchedEffect(sessionId,
flatItems.isNotEmpty()){ if (flatItems.isNotEmpty()) listState.scrollToItem(0,0) }`
— ровно один прыжок на empty→filled; ручной скролл не затронут.

### B33 — двойной нижний отступ (59a2ac75, CI 42276561879 SUCCESS)
[статус: РЕАЛИЗОВАНО+CI | улика: код+run | не хватает: визуальное подтверждение]
Иерархия сверена лично: Scaffold→Box(imePadding)→Column→[Box(weight(1f)){LazyColumn};
…; Column-композер с navigationBarsPadding] (ChatScreen 2799-2821, 4625-4631) —
композер НЕ оверлей, резерв `composerHeightDp+8dp` удваивал инпут. Отклонение от
ТЗ: константа 20dp вместо 8..12dp — история [B22]/0a6d3c92: 14dp = «слегка
обрезано», 20dp = благословённый фолбэк; 8..12 вернуло бы закрытый баг.
Floating-ветвь не тронута. Замечание вне ТЗ: floating-ветвь тоже держит
composerHeightDp — кандидат на пересмотр, менял без device-доказательства.

### B28 — ретрай-политика (1cbbc57a, CI 42278177490 SUCCESS)
[статус: РЕАЛИЗОВАНО+CI | улика: код+тесты+run | не хватает: живой обрыв линка]
maxAttempts OFFLINE 6→10, CONNECTION 4→10; лестница 2/3/5/8/12/16/20/25/30/30
(~151с, `linkRetryLadderSec` с плато); OFFLINE сохраняет connectivity-await
поверх лестницы. НЕ тронуты (документированные причины): NO_RESPONSE=2 (каждая
попытка жжёт 120s watchdog), GENERIC=3 (регрессионный guard теста). Статус:
AppLogger `connect N/10 (waiting Ds for network) kind=… err=…`; баннер
CONNECTION/GENERIC `… — connect N/M (waiting Ds)…`; OFFLINE — локализованные
строки + waiting-network состояние. Инвариант теста расширен 0..15с→0..30с.

## 18. Ночь 2026-11-06/07 — B29 этап 1 (Usage-бридж + шелл-изоляция + дефолт свапа)

Ревьюер: SHIP по B32/B33/B28 (§17); ТЗ на B29 с находкой №1 (потеря токенов).

### Usage-бридж (20a4a0d0, CI 42285115383 SUCCESS)
[статус: РЕАЛИЗОВАНО+CI | улика: код+3 теста+run | не хватает: живой движковый ход на устройстве]
- Корень (сверен по коду): EngineAgentLoop:211 `is StreamEvent.Usage -> Unit` при том,
  что raw StreamEvents заканчиваются внутри лупа — «caller's seam» вел в никуда;
  runEngineTurn звал persistAssistantTurn(usage = null) → token_usage=NULL.
- Решение: AgentEvent.Usage (input/output); мост в лупе; reducer — stateless-ветка
  (when исчерпывающий — иначе компиляция падала, поймано до CI).
- Аккумуляция (решение по семантике, задокументировано): outputs = Σ по раундам
  (мультираундовый тул-ход иначе UNDERCOUNT-ит стоимость), input = финального
  раунда (= живой размер контекста). Публикация КАЖДОГО раунда через
  publishContextUsage — тот же seam, что у legacy на usage-чанке (14391).
- Инкрементальный ряд: тул-ходы ОБХОДЯТ persistAssistantTurn (upsertEngineRow
  рождает ряд с usage=null, финал — updateMessageParts без usage) → новый
  ChatDao.updateMessageTokenUsage; tokenUsageJson() — единая форма для обоих сайтов
  (второй сайт найден дублем при правке — dedup сделан replace_all).
- Engine gateway НЕ репортит cache-сплиты — нули, та же JSON-форма, что legacy.

### Шелл-изоляция (тесты в EngineAgentLoopTest)
- Смешанный батч [file_write(/a), shell_execute, file_read(/b)] → строгий порядок
  fw→shell→fr: GLOBAL без ключей — двусторонний барьер planReactive, ничего не
  бежит СБОКУ от шелла и не перепрыгивает его (детерминировано виртуальными
  зависимостями + Thread.sleep-окно 120мс против скрытого оверлапа).
- Регрессионный guard P1: три дизъюнктных I/O тула стартуют ДО завершения любого
  (барьер старта) — шелл-барьер не ресериализует мир.

### Дефолт свапа (EngineSwapPrefs)
- isEnabled(): getBoolean(KEY, true) — решение ревьюер+юзер 06.11; история
  08.10-ON → 09.10-OFF → 06.11-ON переписана в KDoc (устаревшие комменты — грабли).
- Upgrade-reset: putBoolean(KEY, true) — взводит ТЕКУЩИЙ дефолт; vc98/vc120
  landmine остаётся обезврежен (флаг не переживает апгрейд в ОБЕ стороны).
- Оставлено консервативным (сознательное решение, задокументировано в тесте):
  null-prefs pre-init → false (legacy) — init() в MinisApp делает ветку
  недостижимой на практике; это guard сломанного сетапа, не дефолт.
- Тесты: EngineSwapPrefsLogicTest — ассерты не изменились (null-prefs → false),
  комменты переписаны; UpgradeResetTest не задет (пинит mustResetOnUpgrade).

### Чего не хватает
Живой движковый ход на устройстве (свап де-факто, token_usage в DB, FGS-тик),
прогон PersistentShell-стресса s11 — при ADB. B30/B31 — следующий блок по ТЗ.

## 19. Ночь 2026-11-07 — B31 (список сессий) + B30 этап 1 (Idle RAM)

Ревьюер: SHIP по B29 (§18); ТЗ на B31 (2 узких места) и B30.

### B31 — SessionListScreen (41531ec3, CI 42289637271 SUCCESS)
[статус: РЕАЛИЗОВАНО+CI | улика: код+дифференциальный тест+run | не хватает: замер кадров на 200+ сессиях]
- Узкое место №1 (пер-строчные коллекторы): 2N StateFlow-подписок в items(),
  каждый тик активности фонового агента инвалидовал все видимые строки.
  ОТКЛОНЕНИЕ от буквального рецепта (документировано): наивный top-level
  `by collectAsState` вернул бы регрессию, которую уже чинил
  [T-perf-row-global-collectors] (скоуп экрана перезапускался целиком,
  нестабильные лямбды с захватом viewmodel блокировали skipping). Решение:
  2 коллектора на экране + `derivedStateOf`-проекции в строках — тик
  рекомпозит только строки с изменившимся значением; скоуп экрана State-ы
  не читает и не инвалидируется вообще. Цель ревьюера (2 подписки вместо
  2N) достигнута без рецидива.
- Узкое место №2 (Calendar в цикле): datePeriod строил 4 Calendar на
  сессию. Теперь SessionDateBoundaries.compute() один раз за проход
  (midnight-границы через Calendar, НЕ minus-24h — DST-корректно), маппинг
  — datePeriodFast по чистым Long. Семантика запинена дифференциальным
  sweep: 400 дней с шагом 7ч против СТАРОГО алгоритма, скопированного в
  тест verbatim (>1000 точек, edge-кверки floor/strict-gt сохранены:
  ровно now-7d → THIS_MONTH, ровно now-1month → EARLIER).

### B30 этап 1 — Idle RAM (e752eb64, CI 42292119733 SUCCESS)
[статус: РЕАЛИЗОВАНО+CI | улика: код+4 теста+run | не хватает: meminfo до/после на iQOO]
- keysToTrimOnBackground (чистая): фон = весь idle-набор сбрасывается;
  pinned (живая работа) неприкосновенны; foregrounded-сессия выживает
  (обоснование: rebuild самой тяжёлой VM на возврате превращает «уйти в
  фон» в «перечитать всю историю»).
- trimIdleOnBackground() в ChatViewModelStore + MinisApp.onTrimMemory
  (level >= TRIM_MEMORY_UI_HIDDEN) — canonical «UI невидим», one-shot;
  RUNNING_* (<20) = приложение ещё видно, не фон.
- DB-часть B30 (408 МБ minis.db: incremental_vacuum / вынос parts_json) —
  сознательно НЕ в диффе: схема+миграция = отдельный дизайн-раунд
  (согласуется с Шагом 3 направления по параллелизму).

## 20. Ночь 2026-11-07 — B30 этап 2 (санация SQLite: bounded deleted_messages + трим pagecache)

Ревьюер: SHIP диффа ДО коммита (по запросу); конфликт [T-archive-every-delete]
разрешён вердиктом (старая «восстановимость» — иллюзия); правка
rowid → archive_id в cap-запросе принята.

### B30 этап 2 — a2190d98 + тест-фикс 27601e24, CI 42304574840 SUCCESS (attempt 2)
[статус: РЕАЛИЗОВАНО+CI | улика: код+6 тестов+run | не хватает: meminfo + размер minis.db до/после свипов на iQOO]
- onTrimMemory(≥UI_HIDDEN): SessionHistoryCache.clear() (ПЕРВЫЙ вызывающий
  clear() в кодовой базе — 2 полных распарсенных сессии больше не висят
  в статике) + PRAGMA shrink_memory (pagecache возвращается ОС; file-size
  не трогает — VACUUM отдельный follow-up) + IO-корутина
  runDeletedArchiveMaintenance.
- Удаление сессии: dao.deleteSessionWithArchive (@Transaction) — архив
  сессии уходит атомарно с сессией; бессмысленная копия сообщений перед
  делитом удалена (FK CASCADE, MessageEntity:16);
  MutationJournal.recordWipe сохранён (форензика не потеряна).
- Политический конфликт разрешён ревьюером кодом: restore() без
  родительской строки sessions падает SQLiteConstraintException
  (DeletedMessagesMethods → insertMessages), UI восстановления не
  существует. Архив теперь bounded: сироты (session_id NOT IN sessions) /
  retention 30 дней / потолок 100 новейших на сессию (коррелированный
  COUNT — без window-функций; тай-брейк archive_id — правка ревьюера:
  Room-валидатор не принимает псевдо-колонку rowid вне FTS).
  retry/edit/rerun не тронуты — [T-no-destructive-retry] сохранён.
- Тесты: 5 Robolectric (DeletedArchiveMaintenanceTest: сироты;
  транзакционный делит архива, чужая сессия нетронута; retention-cutoff;
  cap 130→100 newest-first; интеграционный sweep репо-точки входа) +
  clear-контракт (SessionHistoryCacheTest).
- CI-постмортем (2 раунда): (1) прогон a2190d98 — failure на 1/2089,
  МОЯ тестовая фикстура: «ok»-строка deletedAt=5000 оказалась НИЖЕ
  cutoff'а 10000 (nowMs 30d+10s − 30d), retention законно её снёс —
  assert DeletedArchiveMaintenanceTest.kt:141 expected 1 was 0 (строка
  128 в консоли — внешняя frame runBlocking; полный трейс восстановлен
  из артефакта unit-test-report-no-apk). Прод-код НЕ виноват — свип
  отработал ровно по спецификации. Фикс 27601e24: deletedAt=20000.
  (2) Прогон 2 на 27601e24 — транзиентный InstallFailedException
  ndk;27.0.12077973 при конфигурации Gradle (CDN; в прогоне 1 та же
  авто-установка прошла за 13с; до компиляции Kotlin не дошло) →
  rerun-failed-jobs → SUCCESS (run_attempt 2).

## 21. Утро 2026-11-07 — B16 этап 1 (категории тулов + полный реестр + экран кастомных агентов)

ТЗ = спецификация из AGENT_PLAN.md, синхронизированная ревьюером (84cd27f4):
6 категорий (File, Shell, Web, Memory, Subagent, System), экран в настройках,
Markdown+YAML в /var/minis/agents/<name>.md, подхват без рестарта.

### B16 этап 1 — 4fbf71a9 + 9723b27a, CI 42309842293 SUCCESS
[статус: РЕАЛИЗОВАНО+CI | улика: код+9 тестов+run | не хватает: экран на устройстве + спавн кастомного агента с новыми тулами]
- РЕКОГНОСЦИРОВКА изменила масштаб задачи: «зажат в 8» — это ТРИ реестра:
  диспетчер ChatViewModel (35 веток, включая 18 константных
  SubagentTools/BgTaskTools/Todo/Search/Mcp/WebFetch/AskUser/PlanSubmit —
  первый grep по строковым литералам нашёл только 9), schema-реестр
  ToolSurfaceAdapter.buildRegistry (34 тула под feature-гейтами),
  валидация (AgentTools.ALL_TOOL_NAMES=8 + AgentFileParser.KNOWN_TOOL_NAMES=8).
  Расширь я ТОЛЬКО парсер — UI записал бы валидный для парсера файл,
  который schema-фильтр обрезал (риск «EMPTY schema», DebugRPCHandler:1787).
- Решение: engine/ToolCategories (чистый) — 6 категорий × 34 тула,
  ALL_TOOLS = union; AgentTools.ALL_TOOL_NAMES := ToolCategories.ALL_TOOLS;
  AgentFileParser.KNOWN_TOOL_NAMES := тот же set. Один структурный источник,
  drift структурно невозможен; парсер≡энфорсер запинен тестом.
- plan_submit вне реестра СОЗНАТЕЛЬНО (план-выход, инжектится движком
  только в PLAN-режиме; у воркера план-режима нет — allowlist обещал бы
  тул, которого в схеме никогда не будет); subagent_task — iOS-имя.
  ToolCategoriesTest пинит ВСЕ 35 веток диспетчера verbatim и разрешает
  ровно одну асимметрию (plan_submit) — новая ветка диспетчера без
  категории = красный CI.
- Экран: Settings → Agent Runtime → «Custom agents» (после Skills,
  иконка SmartToy); список/создание/редактирование/удаление; пикер тулов
  аккордеоном по 6 категориям со счётчиком; Restrict-tools тумблер
  (OFF = нет строки tools: = дефолт раннера — 1:1 с форматом файла);
  modelRole чипами (6 ролей + default), model entry (beats role),
  maxTurns 1..64; имя [A-Za-z0-9_-]+ — ':' ломал бы custom:<name>
  роутинг, path-символы исключают traversal; fileFor-гейт в сторе;
  сериализация — точный фронтматтер парсера; AgentFileStore без кэша →
  сейв живой (spawn/list_agents видят сразу).
- Компил-постмортем (прогон 1, 2 ошибки в моём новом файле):
  (1) `runCatching{ fileFor(...)?.delete() }.getOrDefault(false)` дал
  Boolean? — `!ok` на nullable запрещён → нормализация `== true`;
  (2) `horizontalScroll()` требует ScrollState (перепутал с
  verticalScroll(state) — у которого state тоже обязателен, в соседней
  строке он был) → rememberScrollState(). Фикс 9723b27a → SUCCESS.
- Поведенческое влияние расширения ALL_TOOL_NAMES: строители схем НЕ
  читают его (expandAllowlist только канонизирует, регистрация — по
  гейтам), ToolSurfaceAdapterTest/PlanFilter/ScopedToggles не задеты;
  меняется ТОЛЬКО валидация: больше валидных имён принимается, опечатки
  по-прежнему отвергаются.

## 22. День 2026-11-07 — B24 этап 1 + B26 (hot window 40 + merge-пагинация)

ТЗ = директива ревьюера по итогам аудита (5 дефектов: гигантское окно,
LOADING-гейт, отсутствие фидбека, snapshot-discard, SELECT всего хвоста).

### B24+B26 — b3ad1ee7, CI 42352865416 SUCCESS (на ЗЕРКАЛЕ)
[статус: РЕАЛИЗОВАНО+CI | улика: код+17 тестов окна/merge+run на canon | не хватает: замер кадров + кнопка на устройстве — при ADB]
- Окно 120→40 (B24): 5-10 composable-нод на агент-ход ⇒ 120 строк =
  600-1200 нод. Пин тестом; параметризационный тест остался.
- Пагинация (B26): LOADING-гейт снят; ровно страница (limit=page.count,
  messageCount выпал); snapshot-discard заменён чистым
  ChatHistoryWindow.mergeOlder (дедуп по id / prepend / идемпотентность);
  _isLoadingOlderMessages + спиннер 16dp/2dp, клик-таргет снят.
  ОСОЗНАННЫЕ решения (все УТВЕРЖДЕНЫ ревьюером): (а) streaming/queue-гейты
  тоже сняты — merge читает состояние в момент установки, все прочие
  писатели _messages перестраивают от текущего (`+`/filter/map, проверены
  все 27 сайтов), токен-уровень идёт мимо списка (_streamingById);
  COMPACTING остался (bookkeeping окна/маркера меняется под ногами);
  (в) notices-переприцепка удалена (merge сохраняет их в current).
- (б) Гонка, порождённая снятием гейта (ревьюер: «ОТЛИЧНАЯ НАХОДКА»):
  пере-паблиш окна на завершении полной загрузки стёр бы смёрженные
  страницы и отмотал bookkeeping → installFromIndex =
  loadedHistoryFromIndex.coerceAtMost(loaded.uiFromIndex), пересборка
  глубокого окна из loaded.messages.drop(install) (index-hardened),
  graying с windowFromIndex=installFromIndex.
- ИНФРАСТРУКТУРНОЕ: Actions на origin (MikasaAckerrman/OpenMinis) ОТКЛЮЧЁН
  уровнем выше настроек (0 ранов по валидному триггеру + dispatch 422
  «Actions has been disabled» при permissions enabled=true и ПУБЛИЧНОМ
  репо; последний успешный ран 23:08:35Z). CI-верификация перенесена на
  ЗЕРКАЛО (OpenMinis-canon): workflow_dispatch tests.yml по b3ad1ee7 →
  ран 42352865416 SUCCESS. Владельцу — включить Actions в Settings.
  Push в mirror: HTTP/2 рвался SSL «unexpected eof» (read жив) ×2,
  попытка №3 с `-c http.version=HTTP/1.1` → УСПЕХ; workaround зафиксирован.
  Наблюдение вне скоупа (передано ревьюеру): restore queued-бабблов на
  раннем гейте стоит перед тем же пере-паблишем — та же семья гонок.

## 23. День 2026-11-07 — B19 (правка сообщений: кэш-инвалидация + toolBlocks)

Вердикт ревьюера: SHIP диффа до коммита; корень вскрыт ЕГО аудитом — два
100% доказанных факта.

### B19 — 33a1efc7 + 1829800e, CI 42355453075 SUCCESS (на зеркале)
[статус: РЕАЛИЗОВАНО+CI | улика: код+3 Robolectric-кейса+run на canon | не хватает: живое репро на устройстве (правка → перезаход; правка в стриме) — при ADB]
- Факт 1 (главный): dao-запись в обход репозитория → invalidateMessage
  не звался; stamp-preserving UPDATE оставляет fingerprint (rowCount +
  MAX(created_at)) ИДЕНТИЧНЫМ → кэш отдавал старьё из Java Heap при верной
  БД. Фикс: ChatRepository.updateMessagePartsPreserveStamp(sessionId, id,
  partsJson) — запись + invalidate(sessionId) + invalidateMessage(id);
  оба dao-сайта (строка + сиблинги merged-хода) переведены на обёртку.
- Факт 2: in-place правка в стриме меняла только content — рендер читает
  текст из toolBlocks (AssistantBlock kind="text"). Фикс: блоки тоже
  переписываются (b.kind=="text" → b.copy(content=newText)).
- Тест RewriteCacheInvalidationTest: (1) fingerprint НЕДВИЖИМ после записи —
  пин причины невидимости; (2) hit → обёртка → miss + read-back свежих
  parts (путь «правка откатывается» закрыт структурно); (3) message-id
  индекс гасит связанные кэш-записи (s2-view).
- CI-постмортем: прогон 1 (33a1efc7) — компил моего теста: хелпер
  cacheSessionWithCurrentFingerprint зовёт suspend loadMessages, но не был
  suspend (в DeletedArchiveMaintenanceTest хелперы suspend — проморгал).
  Фикс 1829800e → run 42355453075 SUCCESS. Пуш-триггер на зеркале работает
  без dispatch (в отличие от B24/B26, где ран потребовалось дёргать руками —
  вероятно, задержка доставки события тогда же).
- Наблюдение (передано ревьюеру, вне скоупа): updateMessageParts (путь
  rerun) инвалидирует только invalidateMessage — для владеющей сессии
  достаточно (индекс гасит её entry); симметрия с новой обёрткой = один
  параметр, по команде.

## 24. День 2026-11-07 — B16 этап 2 (локализация CustomAgentsScreen + AgentDraftTest)

Вердикт ревьюера: SHIP.

### B16 этап 2 — 5d9b391f, CI 42360094031 SUCCESS (на зеркале)
[статус: РЕАЛИЗОВАНО+CI | улика: код+33 ключа на 8 языках+AgentDraftTest (5 тестов)+run на canon | не хватает: визуальная проверка на iQOO — при ADB]
- Локализация (8 языков): 33 строковых ресурса `agents_*` переведены и добавлены
  в `res/values/strings.xml`, `values-ru`, `values-de`, `values-fr`, `values-ja`,
  `values-ko`, `values-zh`, `values-zh-rTW`. Все 8 XML файлов валидны, без пропусков.
- Технические токены сохранены без перевода: frontmatter keywords (name, description,
  modelRole, tools, maxTurns), имена инструментов диспетчера, значения ролей моделей.
- `CustomAgentsScreen.kt` полностью переведён на ресурсы:
  - `AgentDraft.validate(): @StringRes Int?` — возврат ID ресурса вместо хардкода;
  - `categoryTitle(key)` — сопоставление 6 категорий с локализованными заголовками;
  - чип `default` переведён (`R.string.agents_default_role`), остальные чипы сохраняют технические имена;
  - подсказки строк списка (role, tools, default model, default tools).
- `AgentDraftTest` (5 тестов):
  - `validate catches bad names` (пробелы, слэши, двоеточия, пустые строки);
  - `validate catches empty instructions`;
  - `validate catches invalid maxTurns` (диапазон 1..64);
  - `roundtrip markdown with AgentFileParser` (полный цикл сериализации/парсинга);
  - `unrestricted tools omits tools line in markdown`.
- CI run: `42360094031` на `OpenMinis-canon` — SUCCESS.

## 25. День 2026-11-07 — B16 этап 2b (серверный allowlist MCP для кастомных агентов)

Вердикт ревьюера: SHIP.

### B16 этап 2b — c805a98f + 4467d3d1 + 671e68af + 8319039c + dbebbbff, CI 42363798076 attempt 2 SUCCESS (на зеркале)
[статус: РЕАЛИЗОВАНО+CI | улика: код+AgentMcpPolicyTest+локализация 4 ключей на 8 языках+run на canon | не хватает: UI-тест на iQOO при ADB]
- Архитектурная цель: изоляция MCP-серверов per-agent. Если кастомному агенту нужен
  только filesystem или telegram, он не должен видеть или вызывать остальные подключенные серверы.
- Схема данных и фронтматтер:
  - `AgentNode.kt:43`: добавлено поле `val mcpServers: List<String>? = null`;
  - `AgentFileParser.kt:40`: парсер распознает `mcpServers:` и алиас `mcp_servers:` в YAML фронтматтере;
  - `AgentDraft.kt`: сериализует `mcpServers:` в YAML при включенном ограничении.
- Политика и хранилище:
  - `AgentMcpPolicyStore.kt`: потокобезопасный process-scoped реестр на `ConcurrentHashMap<String, Set<String>>`
    (хранит lowercase-имена разрешенных серверов по sessionId, структурный аналог `AgentToolPolicyStore`);
  - `AgentSessionManager.kt:61` и `AgentGraphRunner.kt:783`: регистрация при старте сессии/ноды и очистка при завершении.
- Гейткипинг в `McpCallTool.kt`:
  - `filterListOutput`: фильтрует ответ команды `list`, оставляя только разрешенные серверы;
  - команды `tools` и `call`: отклоняются с диагностическим сообщением до запуска гостевого процесса,
    если сервер не входит в allowlist сессии.
- UI в `CustomAgentsScreen.kt`:
  - Редактор читает доступные серверы из `servers.json`;
  - Переключатель ограничения MCP-серверов и чекбоксы для каждого сконфигурированного сервера;
  - Карточка агента в списке отображает бейдж MCP со счетчиком разрешенных серверов;
  - 4 строковых ресурса (`agents_row_mcp`, `agents_restrict_mcp`, `agents_restrict_mcp_hint`, `agents_no_mcp_servers`)
    локализованы во всех 8 локалях (`values/`, `values-ru/`, `values-de/`, `values-fr/`, `values-ja/`, `values-ko/`, `values-zh/`, `values-zh-rTW/`).
- Тесты:
  - `AgentMcpPolicyTest.kt`: unit-тесты политик allow/block, case-insensitivity, фильтрации и очистки.
- CI постмортем:
  - `42361668834` (4467d3d1): AAPT syntax error в `values-fr/strings.xml` (неэкранированный апостроф `l\'instant`). Исправлено.
  - `42361999564` (671e68af): лишняя закрывающая фигурная скобка в `McpCallTool.kt`. Исправлено.
  - `42362480250` (8319039c): `LocalContext` не был захвачен внутри `AgentEditorScreen`. Исправлено.
  - `42363111866` (dbebbbff): Robolectric пытался поднять `MinisApp` из манифеста, падая на `RootfsManager`. Добавлен `@Config(application = Application::class)`.
  - `42363798076` attempt 2: SUCCESS на `OpenMinis-canon`.


