# Пакет для внешнего ревью: рекурсия sendMessage⇄enqueuePrompt
Коммит-цепочка: 2db40b1d (токен-слот) → 4c161ab2 → 851e27ad (CE-провайдеры) → e144ab54 → 78697807 (гвард v1, CI: Build+Tests SUCCESS) → текущий (гвард v2 = always-park).
Диффы: fix-patch-round2.diff (токен-слот), recursion-guard-packet.diff (гвард v1+v2, SessionConcurrencyManager probe, тесты).

## Форензика iQOO (crash buffer, logcat -b crash)
4 FATAL Minis, все StackOverflowError на main; один — 23372 фрейма:
sendMessage:10562 → tryLockSession → enqueuePrompt:10113 → sendMessage:10565 → …
Окно: Stop → cancelStream force-релизит СЛОТ мгновенно; SESSION LOCK держит
умирающий turn-хвост (секунды teardown). Сообщение в этом окне: клейм слота
успешен (слот свободен) → tryLock фейл → release+enqueue → флаг уже false →
старый код делегировал в sendMessage → рекурсия. Все 4 падения — ДО vc149
(гвард релиза 3ab98efb закрывал только чужой release).

## Финальный фикс (v2, по замечанию внешнего ревью о TOCTOU isLocked)
enqueuePrompt больше НИКОГДА не делегирует в sendMessage: паркинг
безусловный, лог различает cancel-window (lock held) / idle race.
Рекурсия невозможна по построению (enqueue не создаёт фрейм sendMessage).
Доставка: last-breath kick в хвостах send/retryLast/graph, reload kick
на открытии чата, resumeQueueAfterCancel (fresh-drain семантика stop=stop).
isSessionBusy (mutex.isLocked) оставлен как ДИАГНОСТИКА в логе.

## Аргумент завершимости (для ревью)
- enqueue→sendMessage путей нет ни одного (grep: sendMessage вызывается
  из performSendOrEnqueue/retryLast/resumeQueueAfterCancel/drain/
  handleUserCancelledCleanup-пути — enqueue в списке отсутствует).
- Повторная доставка: park идёт в _promptQueue ровно один раз; drain
  изымает с persist; дубли исключены durable-first порядком
  (DB-строка до изъятия, см. ревью раунда 1, пункт «чисто»).
- Порядок: FIFO очереди не нарушается (append в конец, изъятие с головы).
- TOCTOU-возражение снято: решение о паркинге не влияет на
  корректность — парк всегда безопасен, делегирования нет.

## Ограничения (честно)
- Стресс-гонку Stop→send и process-death восстановление возьмёт
  Robolectric/инструментальный спринт (тест-харденинг) — в очереди.
- «На свежем билде не увидишь» подтверждается: CI-зелёный + установка
  ИМЕННО проверенного артефакта (851e27ad APK уже на стенде Waydroid) +
  соак-прогон; на iQOO — после установки свежего APK владельцем.
- Macrobenchmark для запусков/джанка — в очереди оптимизационного
  спринта (после замеров, до любых Rust-переносов).
