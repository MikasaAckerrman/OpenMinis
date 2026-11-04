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
