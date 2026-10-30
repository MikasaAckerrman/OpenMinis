--- 

## БЛОК 5 — БАГ-1 «КНОПКА SEND CODE МОЛЧИТ» (30.10, ZCode-сессия, новый IDE по хэндоффу)

**Симптом юзера:** ввёл все данные, кнопка «отправить код» не рабочая.

**Диагноз (по коду, ветка feat/message-rewrite-visual):**

| # | Факт | Где |
|---|---|---|
| 1 | Кнопка Send code вызывает `runLogin(...)`, тот строит `ProcessBuilder("python3", "/var/minis/mcp-servers/srv/tg-login.py", …)` | `ui/settings/TelegramLoginScreen.kt`, fun runLogin + LaunchedEffect(Unit) (status) |
| 2 | `/var/minis` существует только внутри proot-песочницы агентов; у app-процесса нет python3 в PATH | архитектура: `sandbox/TelegramVaultManager.kt` (vault-сервер запускается хостом через proot НЕ из /var/minis app-процесса) |
| 3 | IOException глотается: `runCatching { … }.getOrDefault("")`, пустая строка даёт пустой JSONObject, ветка error не срабатывает, onDone({}) — UI молчит | тот же файл, runLogin |
| 4 | `TelegramLoginState.kt` (vault-стор) корректен — не источник | `ui/settings/TelegramLoginState.kt` |

**Фикс (2 файла):**

| # | Правка | Файл |
|---|---|---|
| 1 | НОВЫЙ `TelegramLoginRunner` — host-owned proot-запуск драйвера (паттерн 1:1 из `TelegramVaultManager.startLocked`): бинд srv на канонический `/var/minis/mcp-servers/srv`, cwd туда же (оригинальный path-контракт скрипта сохранён), env-креды TG_API_ID/TG_API_HASH (экранные в приоритете, иначе EnvVarRepository), таймаут с drain-потоком, ошибки поверх отрицательных exit-кодов | `sandbox/TelegramLoginRunner.kt` (новый) |
| 2 | Экран переведён на раннер (status + runLogin); тихое глотание убрано: пустой JSON при exit!=0 показывает баннер «Driver failed (exit N): …»; после каждого прогона `sweepSessionIntoVault()` — сессия уезжает в vault сразу, не оставаясь агент-видимой | `ui/settings/TelegramLoginScreen.kt` |

**Безопасность:** креды транзитом только через app-процесс и env приватного proot (не через агент-песочницу); сессия свипается в `filesDir/tg-vault` публичным `TelegramVaultManager.migrateSession` (boot-миграция остаётся backstop-ом).

**Статус проверки:** CI НЕ запускался — по правилу юзера сборка APK и тег [quick] только с его явного разрешения. Проверка компиляции вынесена в согласованную сборку. На устройстве не проверено.

**Открытые хвосты этого фикса:** (1) sync зеркала OpenMinis-canon через API не даёт совпадения SHA — нужен настоящий git push с машины, где есть доступ; (2) tg-login.py в репо отсутствует (живёт только на устройстве в minis-global/mcp-servers/srv) — контракт сверен по вызовам UI: status/send-code/verify/password, JSON с error/code_sent/needs_password/logged_in; (3) после успешного логина работающий vault-сервер мог закэшировать отсутствие сессии — проверить на устройстве, при необходимости перезапустить приложение.
