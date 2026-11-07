# M_R2_TOOL_MAPPING — Техническая спецификация маппинга инструментов Telegram Vault на grammers (Блок 3)

> **Статус:** ТЗ НА РЕВЬЮ / УТВЕРЖДЕНИЕ ПЕРЕД РЕАЛИЗАЦИЕЙ (согласно `docs/RUST_MIGRATION_SPEC.md` §6).
> **Автор:** Senior Reviewer / Architect.
> **Исполнитель:** Senior Implementer (ZCode).
> **Платформа:** iQOO Neo 10, Snapdragon 8 Gen 3 (ARMv9.2), Android 15 / OriginOS 5, 120 Гц.
> **Контекст:** замена Python/Pyrogram vault-демона (`/vault/telegram.py`) на нативный Rust-демон `minisd` (`src/native/minisd/`) на базе `grammers`.

---

## 1. Архитектурный контекст и цели

### 1.1 Граница изоляции
- **Безопасность:** агент никогда не получает прямой доступ к MTProto auth_key сессии. Сессия хранится в `filesDir/tg-vault/` (недоступна для sandboxed PRoot процессов, `TelegramVaultManager.kt:15-38`).
- **Транспорт:** HTTP/1.1 JSON-RPC 2.0 на loopback `127.0.0.1:7824`, аутентификация через заголовок `Authorization: Bearer <hex>` (`TelegramVaultManager.kt:47-65`, семантика B12).
- **Совместимость контракта:** существующие потребители (`MCPRepository`, `TelegramWhitelistRepository`, `TelegramLoginScreen`, `ChatViewModel`) взаимодействуют с портом 7824 без изменений кода со стороны Android.

### 1.2 Сессия и ре-логин
- Сессии Pyrogram (`.session` SQLite) и `grammers` (`grammers-session` бинарный формат) бинарно несовместимы.
- Переход на `minisd` требует однократного ре-логина пользователя через штатный экран `TelegramLoginScreen` (B4/B17-C).
- Хранилище сессии `minisd`: `filesDir/tg-vault/grammers.session`.

---

## 2. Общая обвязка: Whitelist, Кэширование, Форматирование

### 2.1 Whitelist / Blacklist гейт
В точности повторяет семантику `telegram.py:251-267`:
- Файл конфигурации: `tg_whitelist.json` в каталоге хранилища (читается при каждом запросе или с кэшем mtime).
- Структура:
  ```json
  {
    "mode": "whitelist" | "blacklist",
    "allowed": [12345678, -10012345678],
    "blocked": [98765432],
    "blocked_types": ["BOT", "PRIVATE"]
  }
  ```
- Правила:
  1. Если файла нет или режим не задан: доступ разрешён (setup mode).
  2. Режим `whitelist`: разрешены только ID из `allowed`. Ошибка: `"Chat {chat_id} is not in whitelist. Add it to tg_whitelist.json."`.
  3. Режим `blacklist`: запрещены типы из `blocked_types` и ID из `blocked`. Ошибка: `"Chat {chat_id} is blocked in blacklist."` / `"Chat type {chat_type} is blocked in blacklist (blocked_types)."`.

### 2.2 Кэш диалогов
В точности повторяет `telegram.py:178-250`:
- Файл кэша: `tg_chats_cache.json` в каталоге хранилища.
- TTL: 30 минут (1800 секунд).
- При протухании или отсутствии: загрузка первых 200 диалогов через MTProto, сериализация в кэш.
- Ошибка при частичной загрузке: частичный список не кэшируется во избежание сокрытия каналов.

### 2.3 Канонический формат сообщений (`_fmt_msg`)
В точности повторяет схему `telegram.py:268-298`:
```json
{
  "id": 12345,
  "date": "2026-11-07T18:42:00",
  "sender": "John Doe (@johndoe)",
  "text": "Текст сообщения до 2000 символов...",
  "media": "[photo]" | "[video]" | "[document: file.pdf]" | "[audio]" | "[voice]" | "[sticker: 👍]" | "[animation]" | "[location]" | "[poll: Question]" | "",
  "reply_to": 12340 | null
}
```

### 2.4 Обработка FloodWait и Rate Limiting
- Механика `telegram.py:20-43`: минимальный интервал между вызовами на чтение 0.3 с (`RATE_READ_SEC`).
- Обработка RPC-ошибки `FLOOD_WAIT_X`:
  * Если Telegram возвращает `FLOOD_WAIT_X` (где X - секунды): ожидание `min(X + 1, 60)` секунд с последующим повтором запроса (до 3 попыток).
  * Если X > 60 секунд: немедленный возврат ошибки вызывающей стороне без блокировки демона.

---

## 3. Маппинг 16 основных Read-Only инструментов

| # | Инструмент | Legacy Pyrogram (`telegram.py`) | Маппинг на `grammers` (Client / Raw TL) | Схема ответа (Result JSON) |
|---|---|---|---|---|
| 1 | `tg_whoami` | `client.get_me()` (:384-422) | `client.get_me().await` или TL `tl::functions::users::GetUsers { id: vec![InputUser::Self_] }` | `{"userId": i64, "username": String, "firstName": String, "lastName": String}`. Обновляет `accounts.json`. |
| 2 | `tg_list_chats` | `_get_dialogs_cached(client, limit)` (:299-334) | `client.iter_dialogs()` / кэш `tg_chats_cache.json` + фильтр `tg_whitelist.json` | `{"chats": [...], "total": usize, "whitelist_active": bool}` |
| 3 | `tg_read` | `client.get_chat_history(chat_id, limit, offset_id)` (:335-360) | `client.iter_messages(peer).limit(limit).offset_id(offset_id)` | `{"chat_id": i64, "messages": [FormattedMsg], "count": usize}` |
| 4 | `tg_search` | `client.search_messages(chat_id, query, limit)` (:361-383) | Raw TL `tl::functions::messages::Search` с `peer`, `q=query`, `filter=InputMessagesFilterEmpty` | `{"chat_id": i64, "query": String, "messages": [FormattedMsg], "count": usize}` |
| 5 | `tg_unread` | `client.get_dialogs()` с `unread_messages_count > 0` (:423-440) | `client.iter_dialogs()` с проверкой `dialog.unread_count() > 0` | `{"unread_chats": [{"id": i64, "title": String, "unread": i32}], "total_unread": i32}` |
| 6 | `tg_get_chat` | `client.get_chat(chat_id)` (:441-463) | Raw TL: `GetFullChannel` для каналов/супергрупп, `GetFullUser` для ЛС/ботов, `GetFullChat` для групп | `{"id": i64, "title": String, "type": String, "username": Option<String>, "members": Option<i32>, "description": String, "bio": String}` |
| 7 | `tg_global_search` | Raw MTProto `messages.SearchGlobal` (:633-688) | Raw TL `tl::functions::messages::SearchGlobal { q: query, filter: InputMessagesFilterEmpty, offset_peer: InputPeerEmpty, limit }` | `{"query": String, "results": [{"chat": String, "chat_id": i64, "msg_id": i32, "date": i32, "text": String}], "total": usize}` |
| 8 | `tg_channel_digest` | `client.get_chat_history(chat_id, limit)` (:689-718) | `client.iter_messages(peer).limit(limit)` со сборкой preview (до 120 симв. в 1 строку) | `{"chat": String, "chat_id": i64, "digest": [{"msg_id": i32, "date": String, "preview": String, "has_media": bool}], "count": usize}` |
| 9 | `tg_scan_channels` | Пакетный опрос до 30 каналов по 3 сообщения (:719-764) | Итерация по каналам с `client.iter_messages(peer).limit(per_channel)` | `{"channels_scanned": usize, "channels_with_content": usize, "digest": {"<Title>": {"chat_id": i64, "recent": [{"d": String, "p": String}]}}}` |
| 10 | `tg_period_read` | `client.get_chat_history` с фильтром по датам и ранним выходом (:464-529) | `client.iter_messages(peer)` с отсечением по `date_from`..`date_to`, лимит 40 батчей / 4000 сообщений | `{"chat_id": i64, "date_from": String, "date_to": String, "messages": [FormattedMsg], "count": usize, "note": String}` |
| 11 | `tg_replies` | `client.get_discussion_replies(chat_id, message_id)` (:530-557) | Raw TL `tl::functions::messages::GetDiscussionMessage` / `GetReplies` | `{"chat_id": i64, "message_id": i32, "replies": [FormattedMsg], "count": usize}` |
| 12 | `tg_stats` | Оценка активности топ каналов из диалогов (:558-598) | Выборка каналов из диалогов + срез последних 100 сообщений через `iter_messages(peer).limit(100)` | `{"stats": [{"id": i64, "title": String, "type": String, "recent_messages": i32, "last_activity": Option<String>, "unread": 0}], "total_channels": usize}` |
| 13 | `tg_link_search` | `get_chat_history` с regex-поиском `https?://` (:599-632) | `client.iter_messages(peer).limit(limit * 3)` + парсинг ссылок через regex | `{"chat_id": i64, "messages_with_links": [{"msg_id": i32, "date": String, "text": String, "links": [String]}], "count": usize}` |
| 14 | `tg_multi_search` | Последовательный поиск по нескольким чатам (:765-815) | Цикл по `chat_ids` (до 20 чатов) с `tl::functions::messages::Search` | `{"query": String, "chats_searched": usize, "chats_with_results": usize, "total_messages": usize, "results": {"<Title>": [...]}}` |
| 15 | `tg_download_media` | `client.download_media(msg)` (:1106-1150) | High-level `client.download_media(&message, &path).await` | `{"chat_id": i64, "message_id": i32, "file": String, "size_bytes": u64, "size_mb": f64, "caption": String}`. Защита от path traversal обязательна. |
| 16 | `tg_archive_chat` | Выгрузка канала в SQLite `/var/minis/shared/tg/archive.db` (:1182-1276) | Постраничная выгрузка через `iter_messages` + запись в SQLite через `rusqlite` | `{"archived_messages": usize, "db_file": String, "total_archived": usize}` |

---

## 4. Расширенные / Вспомогательные инструменты

| # | Инструмент | Legacy статус | Поведение в `minisd` |
|---|---|---|---|
| 17 | `tg_read_parallel` | Параллельное чтение каналов через semaphore (:936-992) | Tokio task join set с `tokio::sync::Semaphore(10)` поверх `iter_messages` |
| 18 | `tg_read_new` | Чтение только новых сообщений с курсором `tg_cursor.json` | Поддержка курсора `tg_cursor.json` на диске + отсечение сообщений `<= cursor` |
| 19 | `tg_read_chunked` | Параллельное чтение одного канала окнами смещения (:993-1063) | Расчёт окон смещения + параллельные запросы к истории |
| 20 | `tg_search_parallel` | Параллельный мультипоиск по чатам (:1064-1105) | Concurrency через `tokio::sync::Semaphore` |
| 21 | `tg_analyze_chat` | Анализ тем/ключевых слов из архива или live (:1277-1355) | Локальный SQL-запрос к `archive.db` или парсинг сообщений |
| 22 | `yt_transcript` | YouTube transcript API (:1151-1181) | **НЕ Telegram инструмент**. Оставляется в гостевом сэндбоксе, в `minisd` не переносится. |

---

## 5. Формат ошибок и совместимость с MCP

### 5.1 JSON-RPC 2.0 ошибки
- Метод не найден: `{"jsonrpc":"2.0","id":mid,"error":{"code":-32601,"message":"unknown tool: ..."}}`
- Пропущены обязательные параметры схемы:
  ```json
  {
    "jsonrpc": "2.0",
    "id": mid,
    "result": {
      "content": [
        {
          "type": "text",
          "text": "{\"error\": \"missing required parameters: chat_id\"}"
        }
      ]
    }
  }
  ```
  *(Примечание: в точности соответствует `telegram.py:1434-1440`)*.

### 5.2 Бизнес-ошибки (внутри `content[0].text`)
- Не авторизован: `{"error": "not authorized - run send-code/verify first"}`
- Whitelist блокировка: `{"error": "Chat 12345 is not in whitelist. Add it to tg_whitelist.json."}`
- FloodWait лимит: `{"error": "FloodWait: 15s"}` (после исчерпания попыток бэкоффа)

---

## 6. План контрактных Golden-тестов (M-R2 CI)

1. **Host-тесты `cargo test` (`src/native/minisd`):**
   - Сериализация и парсинг всех 21 JSON-RPC схем в `tools/list` и `tools/call`.
   - Проверка валидации обязательных параметров (отсутствие `chat_id` в `tg_read` возвращает ошибку схемы).
   - Тесты логики `WhitelistFilter`: whitelist mode, blacklist mode, blocked_types.
   - Тесты форматирования `FormattedMessage` (структурный эквивалент `_fmt_msg`).
   - Тесты защиты от path traversal в `tg_download_media`.
2. **Golden-дифф тесты:**
   - Эталонные JSON-ответы `telegram.py` сохраняются в `src/native/minisd/tests/fixtures/golden_*.json`.
   - Тестовый стенд прогоняет mock-ответы через десериализатор `minisd` и проверяет совпадение полей и типов.
