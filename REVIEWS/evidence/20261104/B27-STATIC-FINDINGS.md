# B27 статический разбор фоновых путей (частичный, 2026-11-05, тихий режим)
Проверено по коду; остальное — зоны для следующего прохода.

## Верифицированные факты
1. FGS (service/AgentForegroundService.kt):
   - startForeground с FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK (189/228/276),
     разрешение объявлено (manifest:27), тип в manifest:507 ✓ —
     Android 14 FGS-type crash путь прикрыт.
   - START_STICKY (228+) — система АВТОМАТИЧЕСКИ рестартует сервис после
     убийства = легальный фоновый старт без действий пользователя
     (вместе с BOOT_COMPLETED/AlarmReceiver — три механизма фоновых стартов).
   - Safe-mode: CrashFrequencyDetector.isSafeMode() → stub-уведомление
     + stopSelf (краш-луп защита присутствует) ✓.
   - ACTION_STOP → cancelAllActiveStreams ✓.
2. AlarmReceiver: BOOT_COMPLETED → rescheduleAll в try/catch ✓;
   notify обёрнут в SecurityException catch ✓ — ресивер краш-безопасен.
3. Разрешения: SCHEDULE_EXACT_ALARM + RECEIVE_BOOT_COMPLETED (manifest:50-51)
   — будильники в фоне легальны.

## Гипотезы, требующие подтверждения (не факты)
- Серия SIGABRT на DefaultDispatcher: подозреваемые proot/dispatcher/pthread
  (классификатор в crash_handler.cpp:296) — причина не доказана, хендлер
  маскирует (см. FINDINGS.md).
- FGS onDestroy с активными сессиями (BgDiag-лог «STILL ACTIVE») —
  перерасклейка сервисом: краш-путь или нормальная ревизия — требует трассы.

## Остаточные зоны (следующий проход)
- ScheduledTaskManager.rescheduleAll (176 строк) — не прочитан.
- Process-death restore: MainActivity recreate + AppNavigation 310-378
  (автопереходы/детекторы) — не прочитаны.
- MinisApp uncaughtException цепочка: порядок ACRA/MinisApp-хендлеров
  (ACRA collect() на main — HANG 2.9s из stall-лога) — дизайн-вопрос.
- DebugServer 0.0.0.0:5322 в релизе — surface-аудит.

## ДОРАБОТКА (2026-11-05): ScheduledTaskManager + DebugServer — запрошенный объём
Commit: $(git rev-parse --short HEAD) (HEAD на момент аудита). Объём: два файла
прочитаны полностью. Статусы: «статически проверено; runtime не проверен».

### ScheduledTaskManager.kt (176 строк, прочитан целиком)
- registerAlarm: setExactAndAllowWhileIdle(RTC_WAKEUP) + SecurityException →
  fallback setAndAllowWhileIdle (revocation S+ обработана). [статически: краш-путей
  не найдено; СЕМАНТИКА СРОКА НЕ ПРОВЕРЕНА — inexact/Doze может отложить доставку,
  требования задач к точности не формализованы; runtime не проверен]
- buildPendingIntent: FLAG_IMMUTABLE|UPDATE_CURRENT ✓. requestCode =
  taskId.hashCode() and 0x7FFFFFFF → [LOW→ПОДТВЕРЖДЕНО ПО КОНТРАКТУ] коллизия
  requestCode = перезапись PendingIntent: все Intent задач имеют ОДИНАКОВЫЕ
  identity-поля (action=com.openminis.app.scheduled.FIRE, component=
  ScheduledTaskAlarmReceiver, data/type отсутствуют), а extras (task_id) в
  filterEquals НЕ участвуют → коллизия requestCode с необходимостью
  перезаписывает. Runtime-проверки нет. Остаточное: receiver получит task_id
  другой задачи → пропущенное/чужое срабатывание (не краш).
- markFired/upsert в onReceive-контексте: главный поток — ANR-риск зависит от
  ScheduledTaskStore (в этом объёме НЕ ПРОВЕРЕНО; store не читался).
- rescheduleAll: обёрнут в try/catch вызывающим AlarmReceiver ✓ (проверено ранее).
- update(): cancel→upsert→register — окно сироты-аларма отсутствует (cancel перед
  register, enabled-гейт) [статически].

### DebugServer.kt (437 строк) + точка старта
- Release-гейт ЕСТЬ: MinisApp.kt:1014 `if (BuildConfig.DEBUG)` → сервер стартует
  только в debug. [статически проверено; runtime не проверен]
- Auth: non-loopback требует X-Minis-Token; loopback (adb forward) token-free;
  сравнение constant-time (isAuthorized); пустой expectedToken → false. ✓
- Ограничения: MAX_BODY_CHARS=8МБ, parallelism=8 (limitedParallelism). ✓
- [LOW] MinisApp.kt:1016 лог «debug RPC server on 127.0.0.1:$rpcPort» вводит в
  заблуждение: сокет на 0.0.0.0 (class doc). Вводит в заблуждение диагностику,
  не безопасность.
- НЕ ПРОВЕРЕНО в этом объёме: поверхность DebugRPCHandler (1890 строк — что может
  делать авторизованный LAN-клиент: чтение логов/файлов, расход квоты), поведение
  runBlocking-коннекшенов при остановке сервера.

Вывод по двум файлам: существенных краш-путей не найдено; найдены [LOW] хеш-коллизия
PendingIntent и [LOW] вводящий в заблуждение лог. Оба файла: «статически проверено;
runtime не проверен». Остальные зоны (DebugRPCHandler surface, ScheduledTaskStore)
остаются вне подтверждённого покрытия.

## ДОРАБОТКА 2 (2026-11-05): DebugRPCHandler — маршруты, auth, транспорт
Commit: $(git rev-parse --short HEAD). Read-only; строки актуальны на HEAD.

### Авторизация — где применяется
DebugServer.handleConnection:243-248: auth ПРОВЕРЯЕТСЯ ДО парсинга/dispatch —
единая точка, покрывает все 50 маршрутов. Non-loopback требует X-Minis-Token;
loopback (adb forward) — token-free. [статически]

### Транспорт
Голый HTTP без TLS (JSON-RPC через сокеты, sendResponse writer) → токен для
non-loopback передаётся в открытом виде — ПЕРЕХВАТЫВАЕМ В ПУТИ (внешнее ревью
подтверждено статически). ServerSocket на 0.0.0.0:5322.

### Маршруты: 50 методов. Классы опасности (не исчерпывающе, есть DEBUG-гейты только у 4):
- ПРОИЗВОЛЬНОЕ ИСПОЛНЕНИЕ в песочнице: debug.shellExecute (1158-1175,
  ExecutionCoordinator.execute, timeout≤900с) — БЕЗ BuildConfig.DEBUG-гейта;
  debug.shizuku.exec / debug.modelUse.exec / debug.sessions.exec /
  debug.minisConfig.exec (287-325) — С гейтом (throw -32601 вне debug).
- ФАЙЛЫ: debug.writeFile / debug.readFile (690) / debug.rawLs (636) —
  границы путей НЕ проверены в этом объёме.
- UI-управление: debug.tap (779) / scroll / inputText / viewTree / highlight.
- БРАУЗЕР: debug.browser.executeJS (222) / pageInfo / getText / screenshot.
- КВОТА/АГЕНТЫ: agent.graph.run/seed, debug.llmRequests, debug.fetch (1092 —
  SSRF-профиль из устройства), debug.update.check.
- СОСТОЯНИЕ: debug.engineSwap, debug.cloudSync, debug.logs.setEnabled,
  debug.minisConfig.exec (ConfigBridge set/set-batch — мутация конфига).

### Текущая экспозиция (владелец уведомлён)
На iQOO СЕЙЧАС установлен диагностический DEBUG-билд (гаечная установка
69b2de06) → DebugServer активен на 0.0.0.0:5322 в Wi-Fi-сети; токен в
files/debug_server_token (читаем рутом). Любой клиент LAN с токеном (или
MITM-перехват) получает произвольное исполнение в песочнице + чтение файлов.
Рекомендация владельцу: заменить билд на release/обычный при первой
возможности или отключить Wi-Fi на телефоне до замены.

## ЭТАП 2 — старт (2026-11-05, ~23:00)
- APK d5624f14 установлен на Infinix (092233731C001204, arm64), pid жив.
- Unstripped arm64/x86_64 библиотеки сохранены: /tmp/d5624f14/intermediates/cxx/
  Debug/1n5gn25q/obj/{arm64-v8a,x86_64}/{libminis_crash_handler,libpty_bridge,
  libjieba_jni,libc++_shared}.so — готовы к ndk-stack.
- Вотч: /tmp/infinix_watch.sh — 45 мин, детект "Fatal signal" / смерть процесса;
  при краше полный logcat дамп → символизация ndk-stack с сохранёнными либами.
- s11-статус: карантин снят, реворк (детерминированный 5-командный прогон с деко-
  байтами) — CI Unit Tests SUCCESS на 038fbfc5 (run 41919638753, внешний ревьюер
  подтвердил: «4 policy-теста + 3 Robolectric-теста DraftStore + s11 реворк прошли»).

## КОРРЕКЦИЯ ЭТАПА 2 (2026-11-05, по ревью стенда Infinix)
- Вотч v1 ОПРОВЕРГНУТ: прошивка XOS/Transsion глушит живой logcat
  (live.logcat=disable, ro.logd.size=0) — детект по Fatal signal был слепым;
  мгновенное исчезновение pid дало ложный ALL_DONE.
- ФАКТ (run-as, build отладочный): stall-2026-11-05 — HANG 3016ms main:
  AgentForegroundService.buildNotification → ICU getQuantityString
  (AgentForegroundService.kt:812/225). Отдельная находка, не смешивать.
- v2 развёрнут: мониторинг /data/tombstones/ (новые файлы = событие,
  автоснятие) + run-as files/logs + авторестарт приложения при смерти;
  3 часа, тихий. Томбстоунов сейчас 0 (падений на диагностическом билде
  ещё не было).
- Ремоуты: origin=mirror=e46e2143 (ревьюер подтвердил байт-в-байт);
  артефакт unstripped-native-libs 2.35МБ готов (run 42072257759).

## ПРИОРИТЕТ 1 ПРИМЕНЁН (2026-11-05): FGS ICU warm-up + label cache
Commit: след. [test]. AgentForegroundService.kt:
- sessionLabelCache (ConcurrentHashMap) + sessionLabelCached() — чтение метки
  из кэша в buildNotification (main не трогает ICU).
- onCreate: фоновый прогрев counts 0..12 ("fgs-icu-warmup" тред).
- Поведение/локализация идентичны (та же getQuantityString, перенесена с main).
Ожидаемый эффект: HANG ~3s в startForeground-окне уходит; риск системного
убийства за FGS-таймаут на строгих прошивках снижен. Runtime-подтверждение —
по stall-логу после установки следующего билда (HANG-профиль исчезает).
Infinix де-скоуплен владельцем; вотч Infinix остановлен (28371/29840 —
живые pid процессов, не вотч).
