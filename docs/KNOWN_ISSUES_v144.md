# KNOWN-ISSUE vc144-01: автостарт vault-сервера 7824 не работает

**Дата открытия:** 2026-10-19, smoke-прогон после установки vc144 (versionCode=144, md5 c541b439f2)
**Статус:** OPEN — зафиксирован по протоколу юзера; исправление НЕ выполняется в рамках smoke
**Replay:** заблокирован до решения по приёмке (правило юзера)

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
