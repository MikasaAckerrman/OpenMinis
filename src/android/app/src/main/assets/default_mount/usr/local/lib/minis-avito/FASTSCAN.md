# FASTSCAN: каноничный конвейер снятия выдачи (обновлено 2026-09-07)

## Лимиты (ПЕРЕЗАМЕРЕНО: старый потолок 3КБ был неверен!)
- execute_js return: до 9.5КБ+ проверено (30 лотов одним вызовом);
  генерация до 65КБ прошла. Реальный потолок не найден, 3КБ НЕ лимит.
- 30 лотов raw=120 + url-без-query = ~9.5КБ b64 — ОДИН вызов на страницу.
- raw=70 ДУШИТ delivery-фильтр ("Доставка от N дней" отрезается). raw>=120.
- query в url (?context=...) ~150 б/лот — всегда .split('?')[0].
- file_write от агента ломается на кириллице; offload НЕ ломается (b64=ascii).

## Каноничный скрипт execute_js (ВСЯ страница, 30 лотов)
var items = document.querySelectorAll('[data-marker="item"]');
var out = [];
for (var i = 0; i < 30; i++) {
  if (!items[i]) break;
  var t = (items[i].innerText || '').replace(/\n+/g, ' ').trim();
  if (!t) continue;
  var a = items[i].querySelector('a');
  var u = (a ? a.href : '').split('?')[0].replace('https://www.avito.ru', '');
  out.push({raw: t.slice(0, 120), url: u});
}
return btoa(unescape(encodeURIComponent(JSON.stringify(out))));

## Разбор: БЕЗ ручного b64
- последний чанк:  python3 bridge.py <mission> --from-offload
- промежуточные:   python3 bridge.py <mission> --from-offload --append

## Скорость (замерено)
- страница (30 лотов): 1 вызов execute_js + 1 bridge = ~5-7с
- 1000 лотов (34 стр): ~3-4 мин последовательно; 3 вкладки -> ~1-1.5 мин
- токены агента: 34 вызова на 1000 лотов (втрое меньше старого канона)
