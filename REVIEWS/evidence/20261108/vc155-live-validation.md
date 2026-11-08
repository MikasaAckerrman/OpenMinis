# vc155 live validation — iQOO Neo 10 (10CF5719KM002JZ), 08.11.2026, 21:28–22:0x

Устройство: versionCode=155 (sha256 386d4208…, release v155, install 21:04:07).
Экспериментатор: агент устройства (оркестратор). Базовое время: 21:28:44 baseline (workspace чист).

## П.1 — «Мост против изоляции»: ОБЕ ОСИ ПЕРЕВЁРНУТЫ

| Канал воркера | Ожидание (гипотеза ревьюера) | ФАКТ | Улика |
|---|---|---|---|
| `file_write` → `bridge_check/worker_result.txt` | ВИДЕН оркестратору | **НЕ ВИДЕН** | `file_read`: `Error: File not found /var/minis/workspace/bridge_check/worker_result.txt`; bounded `find /var/minis/workspace maxdepth 3` — отсутствует нигде; «BRIDGE_OK» встречается только в копиях handoff-текста |
| `shell_execute` → `echo SHELL_MARK > shell_mark.txt` (cwd=/root) | ОТСУТСТВУЕТ (изоляция) | **ВИДЕН** | `cat /root/shell_mark.txt` → `SHELL_MARK`; `ls -la` → `-rw------- 1 root root 11 Nov 8 21:30`; `file_read` → 11 bytes «SHELL_MARK» |

Контроль консистентности оркестратора: свой `file_write` ↔ свой шелл из той же точки согласованы (`CTRL_OK_ORCH` читается обоими каналами). Асимметрия строго орх↔субагент на tool-уровне.

### Механика (ps-лог PRoot воркера)
Воркер смонтирован как `-b …/sessions/<sid-орха>/workspace/spawn-7bad8663…:/var/minis/workspace`
— т.е. tool-`file_write` воркера уходит в ИНДИВИДУАЛЬНЫЙ спавн-подкаталог, который оркестратор
не читает (его `/var/minis/workspace` = корень сессии). А шелл воркера живёт в ОБЩЕМ alpine-rootfs
(спавн-бинд перекрывает только `/var/minis/workspace`), поэтому `/root` — общий у орха и субагента.

### Следствия (на вердикт ревьюера)
1. **Одобренный контракт «артефакты родителю — только через file_write» НЕ ДЕРЖИТСЯ в текущей реализации**:
   мост забинжен в spawn-subdir, а не в workspace родителя.
2. **Дыра контейнера**: общий `/root` rootfs — субагент может писать/читать /root орха
   (и наоборот) в обход спавн-изоляции. Security-вопрос к M-R3 (изоляция шелл-слоя воркера
   или отдельный rootfs-бинд на спавн).
3. Файл-мост воркера ≠ файл-мост орха: чинится либо перенаправлением tool-моста воркера
   на workspace родителя (host-side routing по session id), либо чтением родителем
   spawn-пути. Решение — за ревьюером (M-R3).

## Дефект: нативные grep/glob тула мертвы на устройстве (rg exit 2)

Симптомы: `rg error (exit 2)` / `Error: cannot resolve path: …`; агент ушёл на shell
`ls`/`find maxdepth`. Широкий обход `find /var/minis | grep -rl BRIDGE_OK` — 12+ мин CPU →
foreground-drain → лечение по канону `bgrun` + `kill -9`.

Корень (читка кода `SearchTools.kt` + `PRootKernel.resolveSessionHostPath`):
1. **Дефолт тула `/var/minis` не резолвим на хосте**: резолвер умеет только
   `/var/minis/<subdir>`; пустой subdir проваливается в `resolveHostPath("/var/minis")`
   — guest-маунта на хосте нет → rg exit 2.
2. Worker-сессии: `minis-sessions/<spawn-sid>/workspace` отсутствует → тот же exit 2.

Фикс (pre-M-R3, исполняется ZCode отдельным диффом): дефолт → workspace сессии;
явная проверка существования хост-цели до rg с actionable-сообщением модели.
M-R3 (нативный рутирование в minisd) замещает SearchTools целиком.

## Прочее из прогона
- Первый spawn (7bad8663… первый attempt) — infra-fail: «All attempts exhausted,
  каталог spawn-ecc0eadc… с пустым ARTIFACTINDEX: {}»; retry — COMPLETE, полный handoff.
  (Похоже на тот же класс, что видели в vc152: пустой артефакт-индекс при истощении попыток.)
- B42 Wi-Fi-тест: инструкция передана, результат ожидается (баннер «Network glitch,
  retrying in Xs (attempt Y/3)…» + самовосстановление; при 3+ обрывах — error+Resume).
