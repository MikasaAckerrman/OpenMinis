# Build Changelog

> Машиночитаемый + человекочитаемый лог каждой сборки.
> Агент читает этот файл чтобы понять: какая сборка, что в ней, с чем работать.
> CI auto-append при каждой сборке (sha, branch, date, ci_run_id из GitHub env).

## versionCode 40 — 2026-09-12 — fix/preview-position @ 3c5d802

- **Что:** fullscreen session browser + Firefox-style tools hub + media permission gates
- **Зачем:** агент может показывать браузер юзеру для CAPTCHA; инструменты в стиле Firefox
- **Сессия:** 2026-09-12 вечер
- **CI:** run 34714103795
- **Ветка:** fix/preview-position
- **Коммит:** 3c5d802 (fix: missing backslash continuation in build.yml test list)
- **Schema:** v12
- **Ключевые файлы:**
  - WebViewHolder.kt — превью с zoom, viewport-meta override
  - BrowserSheet.kt — полноценный браузер с вкладками
  - BrowserTabPool.kt — пул вкладок per-session
  - WebPreviewPositionStore.kt — позиция браузера переживает закрытие
  - DeletedMessageEntity.kt — удаление сообщений (user-only)
  - ScreenDimOverlay.kt — затемнение экрана
  - AgentGraphRunner.kt — parallel agents (ВНИМАНИЕ: mutableMapOf race condition)
- **Известные проблемы:**
  - AgentGraphRunner использует mutableMapOf вместо ConcurrentHashMap (race condition)
  - Fix есть в recovered/lost-agents, не влит в эту сборку
- **Содержит из main (через reunite 29d9067):**
  - ContentFilterDetection (provider/ContentFilterDetection.kt)
  - ModelCompaction (data/ModelCompaction.kt)
  - CompactRoute rolling windows
  - RequestBudget wire-size accounting
  - AutoResumePolicy, TransientRetryBudget, TransportErrorClassifier
  - Stream durability (partial turns)

## Не собрано — 8 коммитов на fix/preview-position (2026-09-13, без APK)

Несобранные правки — ждут CI сборки:

1. `5b8b14d` — Build Tracking: BuildConfig GIT_SHA/GIT_BRANCH/CI_RUN_ID/BUILD_DATE + InstallHistory.kt + debug.installHistory
2. `8d6a585` — keepScreenOn: ScreenDimOverlay не вызывал view.keepScreenOn → экран засыпал → Doze → дисконнект
3. `8bab5f1` — AgentGraphRunner: mutableMapOf → ConcurrentHashMap (race condition FIXED)
4. `3b2c286` — Auto-resume: NetworkError теперь триггерит auto-resume (stream was reset: CANCEL FIXED)
5. `c0a96c7` — Ghost message: немедленное удаление из _messages.value перед async reload
6. `74bdfb2` — Error diagnostics: formatErrorForUser — 📱 сеть / 🔌 соединение / ⏱️ сервер / 🖥️ шлюз
7. `01b76b5` — RU i18n: thinking_level_xhigh (Сверхвысокое), thinking_level_ultra (Ультра)
8. `a4088fd` — Delete dialog: preview содержимого сообщения в подтверждении

**Известные проблемы ИСПРАВЛЕННЫЕ в этих коммитах:**
- ✅ AgentGraphRunner race condition — БЫЛ известной проблемой выше, ТЕПЕРЬ fixed
- ✅ keepScreenOn — экран не засыпал при "не гасить экран"
- ✅ Auto-resume — "stream was reset: CANCEL" не ретраился
- ✅ Ghost message — удалённое сообщение висело как призрак
