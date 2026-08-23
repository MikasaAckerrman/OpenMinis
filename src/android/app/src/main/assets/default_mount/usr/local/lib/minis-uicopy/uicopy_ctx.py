#!/usr/bin/env python3
"""uicopy_ctx.py — общий контекст для всех инструментов ui-copy.

ЗАЧЕМ. В первой версии путь к оригиналу был захардкожен в каждом скрипте
(`/var/minis/workspace/copyexam/ORIGINAL.png`), из-за чего инструмент работал
только в одной папке. Здесь единая точка: рабочая папка берётся из
UICOPY_DIR (env) или из первого аргумента, иначе — текущая папка.

Использование внутри скрипта:
    from uicopy_ctx import ctx
    C = ctx()
    C.orig      # Image оригинала (RGB)
    C.px        # пиксели оригинала
    C.W, C.H    # размеры
    C.path("render_v1.png")   # путь внутри рабочей папки
"""
import os
import sys


class Ctx:
    def __init__(self, work=None, orig_name="ORIGINAL.png"):
        self.dir = os.path.abspath(
            work
            or os.environ.get("UICOPY_DIR")
            or os.getcwd()
        )
        self.orig_path = os.path.join(self.dir, orig_name)
        self._orig = None
        self._px = None
        self._size = None

    def path(self, *parts):
        return os.path.join(self.dir, *parts)

    @property
    def orig(self):
        if self._orig is None:
            from PIL import Image
            if not os.path.exists(self.orig_path):
                sys.exit(f"ui-copy: не найден оригинал {self.orig_path}\n"
                         f"положи скриншот как ORIGINAL.png в рабочую папку "
                         f"или задай UICOPY_DIR=<папка>")
            self._orig = Image.open(self.orig_path).convert("RGB")
            self._px = self._orig.load()
            self._size = self._orig.size
        return self._orig

    @property
    def px(self):
        self.orig
        return self._px

    @property
    def W(self):
        self.orig
        return self._size[0]

    @property
    def H(self):
        self.orig
        return self._size[1]

    def load(self, name):
        """Открыть другой файл (рендер) из рабочей папки."""
        from PIL import Image
        p = name if os.path.isabs(name) else self.path(name)
        if not os.path.exists(p):
            sys.exit(f"ui-copy: нет файла {p}")
        im = Image.open(p).convert("RGB")
        return im, im.load()

    @property
    def tools_dir(self):
        """Папка, где лежат сами инструменты (для вызова render.sh)."""
        return os.path.dirname(os.path.abspath(__file__))


_C = None


def ctx(work=None):
    global _C
    if _C is None:
        _C = Ctx(work)
    return _C


def hx(c):
    return "#%02x%02x%02x" % c


def dist(c1, c2):
    return abs(c1[0] - c2[0]) + abs(c1[1] - c2[1]) + abs(c1[2] - c2[2])
