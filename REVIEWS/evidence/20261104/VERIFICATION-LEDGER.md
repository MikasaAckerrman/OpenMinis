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
