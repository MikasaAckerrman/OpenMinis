# РЕВЬЮ-БРИФ: await_result (B1.1) — раунд 2

Кому: агент-ревьюер (Antigravity IDE / opencode / любой).
Ты — независимый ревьюер. Репозиторий склонирован локально, всё можно читать.

## Контекст

Проект: OpenMinis (Android, Kotlin), ветка feat/message-rewrite-visual.
Локальный клон: /home/levia/.zcode/workspace/draft-2e52331c-9822-416d-89d0-9cf826d5d8cb/openminis
Фича: тул `await_result(spawn_id)` — поздний забор результата субагента
из durable-доски (Room, таблица agent_tasks).

Коммиты:
- `89562a3` — исходная реализация (git show 89562a3)
- `cd0d56cf` — исправления по раунду-1 внешнего ревьюера (git show cd0d56cf)

Возражения раунда-1 (были):
1. [BLOCKER] `taskById(spawnId)` без фильтра по команде — possession id не
   доказывает право доступа; id может прийти из произвольного текста.
2. [MAJOR] На чтении нет капа размера результата (кап 4000 есть только
   на записи в AgentBoardRecorder).

Исправления в cd0d56cf:
1. Новый DAO-метод `taskByIdForTeam(id, teamId)` + константа
   `SQL_TASK_BY_ID_FOR_TEAM` (data/db/AgentBoardDao.kt) — тот же скоуп, что
   task_board; чужой id и неизвестный id неразличимы (оба null).
   Вызов: ui/chat/ChatViewModel.kt, executeAwaitResult.
2. Читающий кап `.take(4000)` в executeAwaitResult (обоснование в коде:
   write-кап введён только в 9ec21b4d, докапные строки могут лежать до
   30-дневной чистки).
3. Тест изоляции на настоящем SQLite (JDBC): src/android/app/src/test/
   java/com/openminis/app/data/db/AgentBoardPanelQueriesTest.kt —
   метод «await_result read is team-scoped».

## Что проверить (по пунктам)

1. Открой `git show cd0d56cf` и проверь, что оба возражения закрыты
   именно так, как описано — не по описанию, по коду.
2. Пробей изоляцию по цепочке: executeAwaitResult → taskByIdForTeam →
   SQL-константа → тест. Есть ли ЛЮБОЙ другой путь прочитать строку
   доски по id без team-фильтра, достижимый из тулов агента
   (grep taskById / setResult / resultArtifact по src/android/app/src/main)?
3. ПроверьWriter-путь: все ли терминальные пути SubagentExecutor идут
   через AgentBoardRecorder.taskFinished (кап на записи). Найди пути,
   которые пишут result мимо капа, если есть.
4. Проверь тест: AgentBoardPanelQueriesTest — реально ли тестируется
   SQL-константа (не копия), достаточно ли кейсов (свой/чужой/неизвестный).
5. Регрессии: что могло сломаться (диспетчер, схема, legacy-билдер,
   AgentToolsScopedTogglesTest — expected-family).
6. Гонки: await_result читает строку, которую параллельно пишет
   taskFinished (finishTask — одна транзакция setStatusIf+setResult).
   Может ли читатель увидеть промежуточное состояние? Это проблема?

## Формат ответа

ВЕРДИКТ: SHIP | BLOCK
Затем находки списком: [BLOCKER|MAJOR|MINOR|INFO] файл:строка — суть.
Без пересказа кода. Если утверждаешь — одной строкой почему каждый
из двух исходных блокеров больше не блокирует.
