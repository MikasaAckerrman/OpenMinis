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
