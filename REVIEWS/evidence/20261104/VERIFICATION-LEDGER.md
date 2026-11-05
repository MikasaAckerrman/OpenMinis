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
- **Аппаратный фронт**: BLOCKED_DEVICE (устройства оффлайн, ждём ADB).

