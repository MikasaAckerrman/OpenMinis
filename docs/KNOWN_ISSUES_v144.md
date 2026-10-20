# KNOWN-ISSUE vc144-01: автостарт vault-сервера 7824 не работает

**Дата открытия:** 2026-10-19, smoke-прогон после установки vc144 (versionCode=144, md5 c541b439f2)
**Статус:** OPEN — известный дефект автостарта; исправление НЕ выполняется до сбора данных loader-ветки
**Приёмка vc144 (решение юзера 19.10, поздно):** **УСЛОВНЫЙ PASS** — проверенные smoke-пункты закрыты
(идентичность APK, session+journal в vault, отсутствие старого файла, pyrogram-error, tools=20 после ручного
подъёма), полнота миграции вопрос ЗАКРЫТ (copy-if-exists + rollback-journal mode: отсутствие wal/shm = норма);
**исключение: автостарт сервера = FAIL** (host/proot-путь не стартует, ручной запуск работал). Полный lifecycle
приложения НЕ принят. Не называть это полным PASS.
**Replay:** ЗАБЛОКИРОВАН — не разблокировать автоматически; право снять блокировку = юзер после отдельной
оценки «допустима ли работа с известным дефектом автостарта для задач». Если нет — исправление запуска
становится отдельным проектом со своим baseline и приёмкой.
**Следующая техническая задача (отдельный этап, при следующем юзер-рестарте):** сравнительный сбор данных
loader-ветки по плану ниже, без изменений кода и БЕЗ выбора окончательной причины: текст proot
«the loader was not found or doesn't work» — диагностическая зацепка, НЕ диагноз. Собрать фактические
cmdline+env ДВУХ путей запуска (vault vs agent) одного и того же окружения, полный server.log,
появление/состояние proot-tmp — сравнивать живые данные, не реконструировать аргументы завершившихся
процессов.

## Расследование (22:4x-23:0x — только чтение, изменений кода нет)

Разобран ПОЛНЫЙ текст `server.log` (ранее я приводил только первые строки):

```
proot error: execve("/usr/bin/python3"): Permission denied
proot info: possible causes:
  * the program is a script but its interpreter (eg. /bin/sh) was not found;
  * the program is an ELF but its interpreter (eg. ld-linux.so) was not found;
  * the program is a foreign binary but qemu was not specified;
  * qemu does not work correctly (if specified);
  * the loader was not found or doesn't work.
fatal error: see `libproot.so --help`.
proot error: can't chmod '<tmp>/proot-<pid>-XXXXXX': No such file or directory
```

**Порядок: execve-denied первичен; chmod-fail — вторичный cleanup** (proot
чистит temp после fatal; файла уже нет). Гипотеза «chmod раньше execve» —
ОТВЕРГНУТА.

**Гипотеза 1 (SELinux/права) — ОТВЕРГНУТА:** контексты идентичны — app-проот
и агент-проот оба `u:r:untrusted_app:s0:c89,c257,c512,c768`;
`cache/proot-tmp` доступен на запись app-uid (write-probe OK);
`drwx--S---` не мешает владельцу.

**Гипотеза 3 (путь) — ОТВЕРГНУТА:** `/usr/bin/python3` физически существует
в rootfs; ELF interp (`/lib/ld-musl-aarch64.so.1`) идентичен рабочему
агентскому rootfs.

**Оставшийся кандидат:** loader-цепочка — proot сам указывает:
«the loader was not found or doesn't work». `libproot-loader.so` в
`nativeLibraryDir` существует, exec-бит есть, SELinux `apk_data_file`.
Разница между рабочим агент-запуском и сбоящим vault-запуском — точечная
проверка при следующем рестарте app (см. план ниже).

## План сбора данных при СЛЕДУЮЩЕМ рестарте app (только сбор, без правок)

1. До: `su 0 stat .../server.log` (mtime baseline).
2. Сразу после рестарта: полный `cat server.log`; `ls -la cache/proot-tmp/`
   (появился ли temp-файл попытки), `ls -la files/proot-tmp 2>/dev/null`,
   `ps -A | grep libproot`, `nc -z 7824`.
3. Код-чтение (только diff-анализ): сравнить `cmd`/`env` в Telegram VaultManager с рабочим агент-билдером — найти опущенный флаг/env.

## Сбор при реальном рестарте 20.10 07:07 (план выполнен целиком)

Baseline до: server.log mtime 21:07:59 19.10. Рестарт: app pid **21603**, лог обновлён
**07:07:19 20.10** (566 б, полный текст 6 строк — идентичен ранее задокументированному:
execve denied первичен, chmod-fail вторичен, temp id `proot-23682-TH4g6k`).
Порт 7824 после: CLOSE, `serve-http` нет.

**Живые proot (обе — агентские):** pid 24409 (сессия `57af66c1`, ET 03:28) и
pid 25158 (сессия `4ef5a778`, ET 00:01). Diff их argv — **только пути сессий**
(attachments/offloads/browser bind); хвост `/bin/sh` и native-offload
идентичны. Vault-proot (23682) умирает мгновенно — живьём не пойман;
его lifecycle подтверждён только логом и mtime каталога.

**proot-tmp состояние:** `cache/proot-tmp` dir-mtime **07:11 20.10** —
создание temp-файла vault-попыткой СЕГОДНЯ состояло (create→cleanup цикл:
создан и удалён до факт-чека; потому в ls только сентябрьские сокеты).
`files/proot-tmp` не существует (fallback не потребовался).

**Разбор chmod-fail (пересмотр):** раз temp-создание УСПЕШНО состоялось,
сообщение `can't chmod '...proot-23682-TH4g6k': No such file or directory`
толкуется как chmod уже удалённого при cleanup файла **или** ошибки
внутреннего пути proot при второй фазе — это НЕ hindrance создания.

**Код exec-хвоста (строки 128-141 TelegramVaultManager):** proot →
`-0 --link2symlink -r rootfs -b /dev /proc /sys -b vault:/vault -w /vault
/usr/bin/python3 /vault/telegram.py --serve-http 127.0.0.1:7824`.
env: PROOT_TMP_DIR, LD_LIBRARY_PATH(nativeLibDir), PROOT_LOADER,
PROOT_LOADER_32, TG_SESSION_DIR, TG_API_ID/HASH из репозитория envvars.
Agent-cmd отличается: `-w /root`, хвост `/bin/sh`, bind'ы сессий/mounts.

**Фактическая сверка файлов (обе стороны rootfs одинаковы):**
`/usr/bin/python3`→symlink→`python3.12` (ELF shared object, 67240 б,
exec-ok, interp `/lib/ld-musl-aarch64.so.1`); `/bin/sh`→`/bin/busybox`
(тот же ld-musl interp — и busybox exec через агента РАБОТАТ).
`ld-musl-aarch64.so.1` в rootfs: exec-ok, 723480 б.
⇒ Права/interp/ELF-класс НЕ различаются между РАБОЧИМ (/bin/busybox)
и ОТКАЗНЫМ (/usr/bin/python3) хвостом.

**Вывод по 5 пунктам плана (без выбора причины):** loader-цепочка
функционально активна (temp создан), файлы exec-ok и interp одинаковы,
причина execve-denied на хвосте `/usr/bin/python3` остаётся НЕ
установленной. Сопоставление «живых» cmdline показало: cmd/env различия
vault-vs-agent сводятся к bind-набору, cwd и хвостовой команде
(python3 vs sh) — все уже перечислены. Дальнейшее сужение требует
целевого диагностического запуска (тест exec разных хвостов в
vault-конфиге), что = изменению поведения — отдельно с юзером.

## Этап сбора данных ЗАКРЫТ (решение юзера, 20.10)

Сбор завершён полностью (`d258b29`), техническое расследование **НЕ объявляется
завершённым**: оно достигло границы, за которой следующий шаг — уже изменение
поведения запуска, а не наблюдение.

**Зафиксировано:**
- Условный PASS vc144 сохраняется; автостарт = известное исключение (3 репродукции).
- Replay остаётся заблокированным.
- Код, конфигурация запуска и работающий агентский proot НЕ трогаются.
- Причина НЕ установлена и НЕ выбирается: ни loader, ни симлинк python —
  живой argv умершего vault-процесса не получен, статическая сверка ELF/interp
  отказ не объясняет.
- **Целевой диагностический запуск = отдельный этап** с собственным
  mini-baseline, точной фиксацией изменяемого параметра vault-конфига,
  критериями успеха/отката и отдельным согласованием. Не разрешён до появления
  этого проекта.

## Симптом (воспроизводится 2/2 прогона)

При старте app хост вызывает proot для подъёма vault-сервера 7824 → proot умирает,
порт закрыт, serve-http отсутствует:

```
proot error: execve("/usr/bin/python3"): Permission denied
proot error: can't chmod '/data/data/com.openminis.app.clone/cache/proot-tmp/proot-9841-gYQfxh': No such file or directory
```

Временные метки: server.log в tg-vault обновился на рестарте app 21:07:59
(mtime 1792429679, 565 б); nc-пробы 7824: CLOSED ×5 (T+39..99s).

## Что подтверждено / не подтверждено

- Приложение рестартилось: новый app-pid 9638 (ELAPSED 00:39 на момент T+39s) ✅
- Автостарт-механизм вызывается: server.log перезаписан при старте ✅
- Ручной запуск из agent-пески работает: pid 9620 → RPC tools=20 ✅ (отдельный путь)
- `libproot.so` pid 11581 (живой, PPID=app) — это АГЕНТСКАЯ песка (argv 35 элементов,
  `/bin/sh` в хвосте, bind'ы minis-sessions/mcp-servers/mounts, native-offload) —
  НЕ vault-попытка; в её argv отсутствуют и `/usr/bin/python3`, и vault-бины
- chmod-fail относится к `cache/proot-tmp/proot-9841-gYQfxh` — такой записи в
  proot-tmp СЕЙЧАС нет (каталог содержит только сентябрьские сокеты 23316);

## Корневая причина — НЕ устоявшаяся, гипотезы

**Не доказано, почему execve отказан.** Кандидаты (проверка — отдельная задача,
не в smoke):

1. Права/SELinux-контекст файлов rootfs: python3.12 в rootfs приложения —
   надо сверить px-разрешения с аналогом в agent-песке (у нашего /usr/bin/python3.12
   interp = /lib/ld-musl-aarch64.so.1, исполнимый). Сверка между vault-запуском
   и agent-запуском сделана только по argv, НЕ по SELinux/perm.
2. Дата chmod-fail до execve говорит, что proot не смог создать/подготовить
   tmp-каталог → возможно, execve-fail — вторичный (proot умер на подготовке),
   а не про сам python. Порядок сообщений в логе: chmod → No such file («создал?
   не создал?»). Нужен лог-файл попытки proot ДО execve.
3. APP-путь запуска proot из REQUIREMENTS (<launcher>) с su-(uid?) — В TelegramVaultManager
   (строки ~93-183) процесс строится в cmd с exec-обёрткой; на строке что-то
   заняло `/usr/bin/python3` ВНУТРИ rootfs (vault ЗапускОТ системе) — отсюда
   спрос: proot должен интерпретировать «/usr/bin/python3» как rootfs-внутренний
   путь, а он упал. Возможно путь резолвится ВНУТРИ rootfs, но там нужные права
   ENHANCE.

## Отдельные вопросы для минимального изменения (не сейчас)

- Какой EXACT cmd строит TelegramVaultManager для запуска: где только
  используется строка "/usr/bin/python3", есть ли "-b rootfs-внутрь" пути
- chmod-fail: ничего в "proot-tmp/proot-9841-gYQfxh" — это подойдёт пыли…

---

**Правило юзера:** исправление — отдельная задача, со своим baseline, планом и
критериями. Приёмка vc144 — условная (условное исключение: автостарт НЕ PASS,
остальное по smoke-отчёту).
