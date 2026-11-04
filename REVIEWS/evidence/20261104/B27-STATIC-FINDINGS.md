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
  не найдено; inexact-fallback = позднее срабатывание, не краш; runtime не проверен]
- buildPendingIntent: FLAG_IMMUTABLE|UPDATE_CURRENT ✓. requestCode =
  taskId.hashCode() and 0x7FFFFFFF → [LOW] коллизия hashCode двух задач = перезапись
  PendingIntent (чужая задача стреляет по id другой). Триггер: коллизия hash (редко).
  Остаточное: поведение receiver при отсутствующем task_id — вне этого объёма.
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
