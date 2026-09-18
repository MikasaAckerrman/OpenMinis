#!/usr/bin/env python3
"""scanlock.py — межпроцессная блокировка для scan.py.

Когда две сессии пишут сканы одной и той же миссии в одну и ту же секунду,
без блокировки последний writer перезапишет результат первого. Lock-файл
на mission-имя гарантирует, что параллельные сессии выстраиваются в очередь.

Использование:
    with ScanLock('rtx3070-russia') as lock:
        # write scan
        ...
    # lock освобождается автоматически

Таймаут 30 секунд — если другая сессия висит дольше, считаем её мёртвой.
"""
import fcntl
import os
import time
from pathlib import Path

LOCK_DIR = Path('/var/minis/shared/_data/avito/.locks')
LOCK_DIR.mkdir(parents=True, exist_ok=True)


class ScanLock:
    def __init__(self, mission_name, timeout=30):
        self.mission = mission_name
        self.timeout = timeout
        self.path = LOCK_DIR / f'{mission_name}.lock'
        self.fd = None

    def __enter__(self):
        deadline = time.monotonic() + self.timeout
        while True:
            try:
                # O_CREAT | O_RDWR, mode 0o644
                self.fd = os.open(str(self.path), os.O_CREAT | os.O_RDWR, 0o644)
                try:
                    fcntl.flock(self.fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    # Получили lock — пишем свой PID для диагностики
                    os.write(self.fd, f'{os.getpid()}\n'.encode())
                    os.fsync(self.fd)
                    return self
                except BlockingIOError:
                    # Другая сессия держит lock
                    os.close(self.fd)
                    self.fd = None
                    if time.monotonic() >= deadline:
                        raise TimeoutError(
                            f'scan lock busy: {self.mission} '
                            f'(waited {self.timeout}s)')
                    time.sleep(0.5)
            except FileNotFoundError:
                # Параллельный процесс удалил каталог; пересоздаём
                LOCK_DIR.mkdir(parents=True, exist_ok=True)

    def __exit__(self, *exc):
        if self.fd is not None:
            try:
                fcntl.flock(self.fd, fcntl.LOCK_UN)
            finally:
                os.close(self.fd)
                self.fd = None
                # Не удаляем файл — пусть живёт как диагностика
