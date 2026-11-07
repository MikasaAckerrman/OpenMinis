# PARALLEL_TOOL_ENGINE_SPEC — Архитектура реактивной параллельности выполнения инструментов

> **Статус:** УТВЕРЖДЁННАЯ АРХИТЕКТУРНАЯ СПЕЦИФИКАЦИЯ (ТЗ для реализации после завершения этапа M-R2).
> **Автор:** Senior Architect / Reviewer.
> **Исполнитель:** Senior Implementer (ZCode).
> **Платформа:** Android (Kotlin Coroutines) + Нативный рантайм (Rust / Tokio в `minisd`).
> **Цель:** ликвидация псевдо-параллельности, барьерных простоев и достижение максимального параллелизма независимых тулов в рамках одного хода модели (паттерн современных AI IDE: ZCode, Cline, Cursor, Claude Code).

---

## 1. Диагностика текущего состояния: почему параллельность в Minis сегодня «хромает»

Анализ кодовой базы выявил 3 фундаментальных узких места, из-за которых параллельное выполнение либо вырождается в последовательное, либо необоснованно блокируется:

### 1.1 Ложные барьеры в `JsonPathExtractor.kt:33-46`
В текущем экстракторе ключей ресурсов `jsonObjectResourceKeys`:
```kotlin
// JsonPathExtractor.kt:33-46
when (toolName) {
    "file_read", "file_write", "file_edit", "read_image" -> ...
    "webfetch" -> ...
    "memory_write", "memory_blocks_edit" -> ...
    else -> null // <-- КОРНЕВАЯ ПРОБЛЕМА!
}
```
**Следствие в `ToolScheduler.kt:151-155`:**
Любой инструмент, возвращающий `null` ключей, помечается как `GLOBAL`. Для `GLOBAL` действует правило:
*«waits for EVERYTHING earlier, and every later call waits for it (runs alone, both directions)»*.
Из-за этого:
- `grep` и `glob` возвращают `null` - становятся **глобальным барьером**!
- `web_search` возвращает `null` - становится **глобальным барьером**!
- Все MCP-инструменты (`tg_*`, `gmail_*`, `github_*`) возвращают `null` - становятся **глобальным барьером**!
- `memory_get`, `todo_read` - становятся **глобальным барьером**!

Если модель в одном ходе вызывает `[web_search, file_read, file_read]`, то `web_search` блокирует параллельный старт файловых операций, и батч исполняется строго последовательно.

### 1.2 Отсутствие канонизации путей (Path Equivalence / Hierarchy)
В `JsonPathExtractor.kt:35`:
Ключ формируется как сырая строка `"fs:$p"`.
- `{"path": "./src/main.rs"}` получает ключ `fs:./src/main.rs`
- `{"path": "src/main.rs"}` получает ключ `fs:src/main.rs`
- Разные строковые представления одного и того же файла не распознаются как конфликт (гонка записи).
- Отсутствует иерархическая проверка: запись в `fs:/workspace/src/main.rs` не видит конфликта с чтением каталога `fs:/workspace/src` в `grep` или `glob`.

### 1.3 Аварийный откат на префикс в Legacy Loop (`ChatViewModel.kt:15440-15500`)
В legacy-контуре используется устаревший `ToolBatchPlanner`:
- `BATCH_SAFE_TOOLS` зажат всего в 5 инструментов (`file_read`, `read_image`, `memory_get`, `file_write`, `file_edit`).
- Вычисляется `parallelPrefixSize`: параллельно запускается только начальный непрерывный префикс до первого «небезопасного» инструмента.
- Все инструменты, идущие после первого барьера, исполняются поштучно в цикле `for`, даже если между собой они полностью независимы (например, 5 параллельных `file_read` после одного `shell_execute`).

---

## 2. Архитектурный эталон современных AI IDE (ZCode / Cline / Claude Code / Cursor)

В передовых AI-движках параллельность устроена не на жестких батчах («волнах»), а на **реактивном графе зависимостей (DAG — Directed Acyclic Graph) с гранулярными блокировками (Reader-Writer Locks)**:

```
Модель выдала 5 тулов:
T1: web_search("query")       [READ net:google]
T2: file_read("src/a.rs")     [READ fs:src/a.rs]
T3: file_read("src/b.rs")     [READ fs:src/b.rs]
T4: file_write("src/a.rs")    [WRITE fs:src/a.rs]  <-- зависит ТОЛЬКО от T2!
T5: file_read("src/c.rs")     [READ fs:src/c.rs]

Хронология выполнения:
Время 0:    Запускаются одновременно [T1, T2, T3, T5] (4 параллельных потока)
Время T2+:  Как только T2 завершён -> СРАЗУ запускается T4 (не дожидаясь T1, T3, T5!)
```

### Принципы эталонной модели:
1. **Множественные параллельные читатели (Shared Read):**
   Любое количество операций чтения одного и того же ресурса (`file_read`, `grep`, `glob` на один файл/каталог) исполняются **одновременно**. Конфликта между двумя чтениями не существует.
2. **Изолированный эксклюзивный писатель (Exclusive Write):**
   Операция записи (`file_write`, `file_edit`) требует эксклюзивного владения целевым ресурсом. Она ожидает завершения только тех операций (чтений или записей), которые были вызваны **до неё** по порядку модели и затрагивают **тот же самый ресурс** (зависимости RAW — Read-After-Write, WAR — Write-After-Read, WAW — Write-After-Write).
3. **Раздельные пулы ресурсов (Concurrency Pools):**
   - **I/O Pool (High Concurrency, cap = 16):** операции с файловой системой, HTTP-запросы, MCP-чтение. Не нагружают процессор, ждут сеть/диск.
   - **CPU / Compute Pool (Conservative cap = 4..6):** компиляция, запуск тяжелых скриптов, PRoot-процессы, инференс локальных эмбеддингов.
   - **Isolated Subagent Pool:** каждый субагент изолирован в своей сессии/директории и выполняется параллельно.
4. **Бесбарьерная реактивность (Zero-Barrier Reactive Scheduling):**
   Нет разделения хода на жесткие шаги или волны. Каждый инструмент запускается в момент, когда выполнены его прямые предшественники в графе.

---

## 3. Спецификация классификации и ресурсов инструментов Minis

### 3.1 Таксономия эффектов инструментов

| Категория | Инструменты | Эффект | Ресурсы (Resource Keys) | Пул |
|---|---|---|---|---|
| **Filesystem Read** | `file_read`, `read_image` | READ | `fs:<canonical_file_path>` | IO |
| **Filesystem Tree Read** | `grep`, `glob`, `list_dir` | READ | `fs_tree:<canonical_dir_path>` | IO |
| **Filesystem Write** | `file_write`, `file_edit` | WRITE | `fs:<canonical_file_path>` + `fs_parent:<canonical_dir>` | IO |
| **Network Read** | `web_search`, `webfetch` | READ | `net:<host_or_url>` (для поиска: `net:search`) | IO |
| **Telegram MCP Read** | `tg_read`, `tg_search`, `tg_list_chats`, `tg_get_chat`, `tg_unread`, `tg_global_search` | READ | `mcp:tg:chat_<id>` (для глобальных: `mcp:tg:global_read`) | IO |
| **Telegram MCP Write** | `tg_send_message`, `tg_join` (будущие) | WRITE | `mcp:tg:chat_<id>` | IO |
| **Memory Read** | `memory_get`, `supermemory_search`, `memory_blocks_view`, `todo_read` | READ | `mem:store` | IO |
| **Memory Write** | `memory_write`, `memory_blocks_edit`, `todo_write` | WRITE | `mem:store` | IO |
| **Subagents** | `spawn_subagent`, `subagent_task` | WRITE (isolated) | `agent:task_<id>` (уникален для каждого вызова) | CPU |
| **Shell Command (General)** | `shell_execute`, `bg_run`, `root_shell` | GLOBAL_BARRIER | `sys:shell` (барьер для всех предшественников и последователей) | CPU |
| **Interactive / UI** | `ask_user`, `plan_submit` | GLOBAL_BARRIER | `sys:ui` | CPU |

### 3.2 Канонизация путей и иерархический резолвер
Для любого пути `p` из аргументов инструмента:
1. Относительные пути приводятся к абсолютному виду относительно текущего рабочего каталога сессии (`/var/minis/workspace/...` или `minis-sessions/<sid>/workspace/`).
2. Разрешаются переходы `.` и `..` (нормализация `normalizePath`).
3. Симлинки приводятся к каноническому реальному пути.
4. **Правило пересечения иерархий:**
   - Чтение `fs_tree:/app/src` конфликтует с записью в `fs:/app/src/main.rs` (каталог является префиксом пути файла).
   - Запись в `fs:/app/src/main.rs` конфликтует как с `fs:/app/src/main.rs`, так и с `fs_tree:/app/src` и `fs_tree:/app`.

---

## 4. Алгоритм построения DAG зависимостей (Reactive DAG Dispatcher)

Для списка вызовов инструментов $[T_1, T_2, \dots, T_n]$ в порядке, предложенном моделью:

### 4.1 Структура графа
Каждый узел $T_i$ содержит:
- `id`: уникальный идентификатор вызова
- `name`: имя инструмента
- `argsJson`: аргументы
- `effect`: `READ`, `WRITE` или `GLOBAL_BARRIER`
- `resources`: множество ресурсов $R(T_i)$
- `dependencies`: множество ID предшествующих вызовов $\{T_k \mid k < i\}$, чьего завершения обязан дождаться $T_i$.

### 4.2 Правила генерации ребер зависимостей
Для каждого нового узла $T_i$:
1. Если $T_i$ имеет эффект `GLOBAL_BARRIER`:
   - $T_i$ зависит от **всех** предшествующих инструментов $T_1, \dots, T_{i-1}$.
   - Переменная `lastGlobalBarrier = T_i.id`.
2. Если $T_i$ имеет эффект `READ`:
   - Если `lastGlobalBarrier != null`: добавить `lastGlobalBarrier` в `dependencies`.
   - Для каждого ресурса $r \in R(T_i)$:
     * Если существует последний писатель `lastWriter[r]`: добавить `lastWriter[r]` в `dependencies` (зависимость RAW).
     * Зависимость от других читателей (`lastReaders[r]`) **НЕ добавляется** (чтения параллельны!).
3. Если $T_i$ имеет эффект `WRITE`:
   - Если `lastGlobalBarrier != null`: добавить `lastGlobalBarrier` в `dependencies`.
   - Для каждого ресурса $r \in R(T_i)$:
     * Если существует последний писатель `lastWriter[r]`: добавить `lastWriter[r]` в `dependencies` (зависимость WAW).
     * Для всех активных читателей `lastReaders[r]`: добавить их в `dependencies` (зависимость WAR).
     * Очистить `lastReaders[r]`.
     * Установить `lastWriter[r] = T_i.id`.
4. Если после $T_i$ появляется `GLOBAL_BARRIER`, этот барьер автоматически включит $T_i$ в свои зависимости.

---

## 5. План поэтапной реализации

### Этап 1: Исправление Kotlin Engine (Сразу после завершения M-R2)
1. **Обновление `JsonPathExtractor.kt`:**
   - Поддержка канонизации путей через `File(workspace, path).canonicalPath`.
   - Извлечение ресурсов для `grep`, `glob` (`fs_tree`), `web_search` (`net:search`), `tg_*` (`mcp:tg`).
2. **Модернизация `ToolScheduler.kt`:**
   - Внедрение иерархического сравнения ключей (`fs_tree` против `fs`).
   - Расширение набора `Effect.READ` для `grep`, `glob`, `web_search`, `tg_*`.
3. **Окончательное удаление устаревшего префиксного планировщика:**
   - Перевод всех вызовов из `ChatViewModel` на `EngineAgentLoop` со встроенным DAG планировщиком (полный отказ от legacy `ToolBatchPlanner.parallelPrefixSize`).
4. **Тестирование:**
   - Набор тестов на смешанные батчи: `[web_search, file_read_A, file_read_B, file_write_A, file_read_C]` должен запускать 4 инструмента параллельно на старте.

### Этап 2: Нативный Rust DAG Executor в `minisd` (Блок 3)
1. **Портирование планировщика на Rust (`src/native/minisd/src/engine/`):**
   - Структуры: `ToolCallNode`, `ResourceKey`, `ToolEffect`.
   - Построение графа за $O(N \cdot K)$, где $N$ - число вызовов, $K$ - число ресурсов (на практике $N \le 20$, время построения < 50 микросекунд).
2. **Асинхронный исполнитель на Tokio:**
   - Использование `tokio::sync::watch` или `tokio::sync::Notify` для сигналов готовности предшественников.
   - Пул семафоров: `tokio::sync::Semaphore` для IO (16 разрешений) и CPU (4..6 разрешений).
   - Потоковая передача результатов в JSON-RPC канал по мере завершения каждого инструмента в реальном времени.

---

## 6. Метрики успеха и критерии приёмки

1. **Время прогона типичного хода исследования (Research Turn):**
   - Сценарий: 1 `web_search` + 4 `file_read` + 2 `grep`.
   - Было: последовательное выполнение (~3.5 - 5.0 секунд).
   - Цель: параллельное выполнение в 1 волне (время = $\max(t_i) \approx 0.8 - 1.2$ секунды, ускорение в **3-4 раза**).
2. **Отсутствие гонок данных:**
   - Тест на 10 параллельных записей в разные файлы: 100% успех.
   - Тест на чтение и запись в один и тот же файл: строгая детерминированная очередность в соответствии с порядком модели.
3. **Плавность UI на 120 Гц:**
   - Никаких блокировок главного потока при планировании и обновлении статусов карточек инструментов.
