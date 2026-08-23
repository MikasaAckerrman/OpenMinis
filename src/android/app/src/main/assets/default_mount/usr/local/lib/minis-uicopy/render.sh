#!/bin/sh
# render.sh — рендер HTML → PNG для ui-copy, полностью автономный.
#
# ПОЧЕМУ ОТДЕЛЬНЫЙ СКРИПТ (измерено, не предположение):
# chromium --headless с точной высотой окна обрезает страницу на ~7px раньше
# (низ диалога терялся: BG до y=646 вместо y=651). Тот же HTML с окном выше
# рендерится правильно. Обход: рендерим с запасом MARGIN, затем кропаем до
# точных W×H через Pillow.
#
# КРИТИЧНО: путь к HTML должен быть АБСОЛЮТНЫМ. С относительным chromium
# отдаёт белую страницу, и все последующие измерения становятся ложью.
# Скрипт сам приводит путь к абсолютному и проверяет результат.
#
# Использование: render.sh <index.html> <out.png> [W] [H]
set -e

HTML="$1"; OUT="$2"; W="${3:-0}"; H="${4:-0}"
[ -z "$HTML" ] && { echo "usage: render.sh <index.html> <out.png> [W] [H]"; exit 2; }

# абсолютные пути
case "$HTML" in /*) ;; *) HTML="$(pwd)/$HTML";; esac
case "$OUT" in /*) ;; *) OUT="$(pwd)/$OUT";; esac
[ -f "$HTML" ] || { echo "render.sh: нет файла $HTML"; exit 2; }

# размеры: если не заданы — берём из ORIGINAL.png рядом с HTML
if [ "$W" = "0" ] || [ "$H" = "0" ]; then
  DIR="$(dirname "$HTML")"
  if [ -f "$DIR/ORIGINAL.png" ]; then
    SIZE=$(python3 -c "from PIL import Image;im=Image.open('$DIR/ORIGINAL.png');print(im.size[0],im.size[1])")
    W=$(echo "$SIZE" | cut -d' ' -f1)
    H=$(echo "$SIZE" | cut -d' ' -f2)
  else
    echo "render.sh: задай W и H или положи ORIGINAL.png рядом с HTML"; exit 2
  fi
fi

MARGIN=120
RENDER_H=$((H + MARGIN))
TMP="${OUT}.tall.png"

chromium --headless=new --no-sandbox --disable-gpu \
  --disable-dev-shm-usage --disable-software-rasterizer \
  --no-first-run --no-default-browser-check \
  --hide-scrollbars --force-device-scale-factor=1 \
  --default-background-color=00000000 \
  --window-size="${W},${RENDER_H}" \
  --screenshot="$TMP" \
  --virtual-time-budget=3000 \
  "file://$HTML" > /dev/null 2>&1

[ -f "$TMP" ] || { echo "render.sh: chromium не создал скриншот"; exit 1; }

python3 - "$TMP" "$OUT" "$W" "$H" <<'PY'
import sys
from PIL import Image, ImageStat
tmp, out, w, h = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
im = Image.open(tmp).convert("RGB")
if im.size != (w, h):
    im = im.crop((0, 0, w, h))
# САНИТИ-ЧЕК: пустая/белая страница = ложные измерения дальше по конвейеру
st = ImageStat.Stat(im)
if sum(st.stddev) / 3.0 < 2.0:
    sys.exit("render.sh: страница почти однотонная (stddev<2) — "
             "вероятно белый экран. Проверь путь к HTML и наличие ассетов.")
im.save(out)
PY

rm -f "$TMP"
echo "rendered $OUT (${W}x${H})"
