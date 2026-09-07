# FASTSCAN: каноничный конвейер снятия выдачи (замерено 2026-09-07)

## Лимиты (проверено)
- execute_js return: ~3КБ (3072 б) — жёсткий потолок ответа
- b64: 1 лот raw=120+url-без-query = 190-230 байт -> 12 лотов/вызов
- raw=70 ДУШИТ фильтр delivery ("Доставка от N дней" отрезается хвостом)
- file_write от агента ломается на кириллице; offload НЕ ломается
  (b64 - ascii) -> полный ответ всегда в /var/minis/offloads/tools/

## Каноничный скрипт execute_js (chunk START, N=12)
var items = document.querySelectorAll('[data-marker="item"]');
var out = [];
for (var i = START; i < START+12; i++) {
  if (!items[i]) break;
  var t = (items[i].innerText || '').replace(/\n+/g, ' ').trim();
  if (!t) continue;
  var a = items[i].querySelector('a');
  var u = (a ? a.href : '').split('?')[0].replace('https://www.avito.ru', '');
  out.push({raw: t.slice(0, 120), url: u});
}
return btoa(unescape(encodeURIComponent(JSON.stringify(out))));

## Снятие страницы (30 лотов) = 3 вызова: START=0,12,24

## Разбор: БЕЗ ручного перебивания b64!
avito bridge <mission> --from-offload        # последний execute_js -> append? НЕТ:
# финал: python3 bridge.py <mission> --from-offload  (последний чанк + буфер)
# промежуточные: python3 bridge.py <mission> --from-offload --append

## Скорость
- 1 страница (30 лотов): 3 вызова execute_js (~3с каждый) + 1 bridge = ~15с
- 1000 лотов (34 стр): ~8-10 мин последовательно; с 3 вкладками ~3-4 мин
- токены агента: только финальный разбор топ-5, скан почти бесплатен
