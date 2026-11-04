# iQOO (V2425A, vc149) — findings 2026-11-04
1. JVM: 4 FATAL Minis StackOverflowError на main (все ДО vc149) —
   рекурсия sendMessage⇄enqueuePrompt, 23372 фрейма. Закрыто
   78697807+a074a5f4 (токен-слот + always-park + liveness-kick).
2. NATIVE: серия SIGABRT на DefaultDispatcher ТОЛЬКО на vc149
   (13:31/15:51/15:54/16:09/16:32/17:05/20:34) — «вылетает даже без
   запуска». Системных томбстоунов Minis НЕТ: собственный
   libminis_crash_handler.so перехватывает сигнал, его arm64-unwind
   ломается (мусорные фреймы в native-crash-*.log), первопричина
   ЗАМАСКИРОВАНА. Fatal signal строк в crash-буфере нет (сигнал
   перехвачен до системного хендлера).
   → SPRINT: починить arm64-unwind хендлера ИЛИ временно выпустить
   сигнал системному хендлеру (SA_RESETHAND/деинсталляция) чтобы
   увидеть настоящую причину. До этого нативные краши невидимы.
3. LAGS (B24/B25): stall-2026-11-04 — 50 IDLE-FREEZE + 3 HANG:
   - HANG 2.9s: ACRA collect() на main внутри uncaughtException
     (MinisApp.kt:396 → FutureTask.get) — репорт крашей вешает UI
     во время краша; рассмотреть async-репорт.
   - HANG 3.4s: Surface.nativeIsValid в performTraversals — вывод/композиция.
4. System-шум (не Minis): media.module Thread-6 SQLite краши,
   librootkotlinx «Root service handoff rejected».
Файлы: iqoo_crash.txt (logcat -b crash целиком), native-crash-*.log,
stall-2026-11-04.log.
