---
name: avito
description: >
  Работа с Авито: разбор конкретной ссылки объявления, сбор выдачи с фильтрами
  (цена, гео, категория, тип продавца), характеристики, описание, репутация
  продавца, фото, скоринг «цена/риск». Триггеры: «посмотри эту авито-ссылку»,
  «что за объявление», «проверь лот», «найди на авито», «собери объявления»,
  «сравни цены на авито», «проверь продавца», «посмотри фото объявления».
---

# avito

Доменный скил над движком `web-deep`. Сеть, разбор состояния, ретраи и
гидратация CSR — там. Здесь только знания об Авито: структура карточек,
поля продавца, скоринг.

## Быстрый старт

```sh
wd-sink start                 # один приёмник на всю песочницу
avito preflight 3060 '3060 ti' 3070 4060 --json
```

`preflight` запускается ДО поиска. Он не считает HTTP 200 доказательством:
источник должен отдать структурированные товары с ценой, пройти challenge-проверку
и иметь свежий capability-report. Avito-browser preflight выполняется отдельно
в открытой вкладке через `preflight-browser.js` и сохраняется в sink; shell CLI
читает только свежий отчёт. Regard проверяется через общий `shops` channel.

Старый/неизвестный источник не выбирается автоматически. Если доступен только
один магазин, это честно отражается как одна нижняя граница новой цены, а не как
«полный рынок».

Одна ссылка (данные приходят в диалог):

```js
// browser_use navigate → любая страница avito.ru, затем execute_js:
await eval(await (await fetch('http://localhost:8791/wdlib.js')).text());
window.__AV_URL = 'https://www.avito.ru/.../..._8415191510';
await eval(await (await fetch('http://localhost:8791/one.js')).text())
```

Сбор выдачи (в диалог — только счётчики, данные в файл):

```js
await eval(await (await fetch('http://localhost:8791/wdlib.js')).text());
window.__AV = { q:'rtx 3060', path:'/all/tovary_dlya_kompyutera',
                pages:3, params:{pmax:25000, s:104}, details:8 };
await eval(await (await fetch('http://localhost:8791/crawl.js')).text())
// → {total_found:1650, serp:90, details:8, saved:{file:'.../avito/items.json'}}
```

```sh
avito report --budget 25000 --must "3060" --top 10
avito photo 8269924243 --n 3        # осмотр фото моделью зрения
avito probe <url>                   # разведка канала (движок)
avito status
```

## Файлы

| файл | что делает |
|---|---|
| `avito` | CLI: preflight / gpu / cross / watch / report / photo / probe / dump |
| `preflight.py` | проверка capability источников и выбор до поиска |
| `preflight-browser.js` | одна проверка выдачи Avito в браузере, с lease и сохранением отчёта |
| `crawl.js` | обход выдачи + карточек. Доменный разбор, сеть — из `wdlib.js` |
| `details.js` | добор карточек шортлиста порциями |
| `seller.js` | профиль: отзывы, роли, стаж, активные лоты |
| `cross.py` | межлотовые связи и поведенческие сигналы |
| `watch.py` | снимки цен во времени |
| `report.py` / `gpu.py` | медиана рынка + модельный скоринг |
| `photo.py` | фото → модель со зрением через `avito-images` channel |

Данные: `/var/minis/shared/_data/avito/` (`items.json`, `items.json.details`).

## Параметры выдачи (проверено на живой выдаче)

`path` — раздел: `/all/tovary_dlya_kompyutera`, `/all/avtomobili/s_probegom`,
`/all/kvartiry/prodam`, `/all/telefony`, `/all/vakansii`.
Город — в путь вместо `all`: `/omsk/tovary_dlya_kompyutera`, либо `rossiya`
для всей страны (если доставка устраивает — это лучший источник данных).

`params` (все проверены по `searchParams` в ответе): `pmin`/`pmax` (цена,
1086→193), `user=1` только частники (→909), `user=2` только компании (→140),
`withDeliveryOnly=true` (→798), `s=104|1|2` сортировка по дате/дешевле/дороже,
`radius`+`geoCoords` точка и радиус в км, `localPriority=1` строго свой город
(1360→24, но всё равно подмешивает чужие). `includeExtra:true` — добавить блок
«похожее из других городов».

**НЕ работают** в URL: `params[110396]` (состояние Б/у), `params[196498]`
(рейтинг 4,7+) — в `searchParams` не попадают, выдача не меняется. Состояние
фильтровать по полю `props` после сбора.

## Сканер миссий

В `config/missions/<name>.yaml` лежит задача. Сейчас три миссии:
`rtx3070-ulyanovsk`, `rtx4060-moskva`, `rtx3070-russia`. Добавлять новые —
копировать существующую и менять `target`, `region`, `search.params`,
`post_filters`, `score`.

```sh
# 1. Получить URL для вкладки
avito scan rtx3070-russia --url-only

# 2. Открыть в browser_use, дождаться DOM, вытащить лоты:
avito scan rtx3070-russia --process < items.json
```

`--process` принимает JSON-массив лотов из браузера на stdin, прогоняет
через фильтры и скоринг, сохраняет в
`/var/minis/shared/_data/avito/scans/<mission>-<ts>.json` и печатает топ-5.

Фильтры (в `config/filters/`):
- `desktop-only` — жёсткое отсечение: Ti/M/laptop/майнинг/на запчасти.
- `delivery-required` — оставляет лоты с доставкой, помечает
  `near` (1–2 дня) и `far` (3+ дня).
- `in-this-region:<name>` — для случаев без доставки, по тексту адреса.

Скоринг: меньше = лучше. Базовый 0, паттерны заголовка и пороги рейтинга
дают положительные значения (риск), высокий рейтинг и короткая доставка —
отрицательные (понижение). Ранжируется по сумме, ничьи — по цене.

## Ловушки

Полный каталог измеренных грабель — [TRAPS.md](TRAPS.md). Самое важное:

- `allItems` мешает свою выдачу и добор из других городов — фильтр по
  `analyticParams.place` обязателен, иначе счётчики врут.
- Гео задаётся ПУТЁМ (`/omsk/...`), `locationId` в query не работает.
- Автомобили рисуются на клиенте: нужна `L.hydrate()`, в HTML полей нет.
- Описание в DOM обрезано («Читать полностью») — `hydrate` раскрывает сам.
- Рейтинг брать из `[itemprop="ratingValue"]`, не считать иконки звёзд.
- Канал нестабилен: мерить `probe --tries 3`, ретраи в сборе обязательны.

## Формат данных

Выдача: `id, title, price, price_text, url, seller, is_shop, rating, reviews,
posted, posted_ts, delivery, delivery_terms, badges, city, extra_region, thumb`.

Карточка (`details>0` или `one.js`) добавляет: `descr, props{}, address, coords,
views, user_type, seller_type, seller_hash, seller_rating, seller_reviews,
seller_reply, seller_since, seller_ads, seller_verified, seller_confirmed,
safe_deal, images[]`.

`seller_hash` — ключ профиля: `/user/<hash>/profile` отдаёт тексты отзывов,
роль (продавец/покупатель), распределение по звёздам и все активные лоты.
Список отзывов режется на 25 записей, API пагинации не найден (все `/web/` и
`/api/` роуты → 404).

## Скоринг

Рынок = медиана центральных 80% цен (устойчива к аксессуарам в выдаче).
Отсечка снизу = 35% медианы. Риск = сумма весов: майнинг 25, артефакты 40,
на запчасти 60, мод BIOS 20, реболл 30, предоплата 45, без видеовыходов 70,
цена ниже рынка на 20/35% → 15/35, нет рейтинга 15, рейтинг < 4.5 → 20.
Авито Доставка умножает риск на 0.6 (защищает платёж, но не от дефектов).

Ограничение: regex видит только то, что продавец написал сам. «Работает без
нареканий» даёт ноль флагов, хотя LHR-ревизия 2021 года — повод для вопросов.
Такие выводы пока делает агент в ответе, а не скрипт.

## Чего скил НЕ делает

- **Не читает личный кабинет и переписку.** Сессия анонимная: `/profile`
  редиректит на `#login`, `/web/1/user/me`, `/web/1/messenger/*` → 404.
- **Не пишет продавцам.** Нужен логин по SMS и это аккаунт пользователя.
  Единственный безопасный путь — приложение Авито + AccessibilityService.
  Автоматическая рассылка = нарушение правил Авито и бан аккаунта.
- **Не показывает телефон** (требует сессии и капчи).
- **Не следит за новыми лотами по расписанию** — для этого `minis-scheduled`.

## Шпаргалка «найти карту»

```
# 1. Создать mission (один yaml, ~30 строк)
$EDITOR config/missions/rtx4070-moscow.yaml

# 2. Открыть URL в браузере Minis (вызывает никаких сетевых эффектов)
avito scan rtx4070-moscow --url-only

# 3. В браузере: extract, save JSON, прогнать сканер
# (агент делает это сам через execute_js + python scan --process)

# 4. Получить топ-5 с риском
cat /var/minis/shared/_data/avito/scans/rtx4070-moscow-*.json | jq '.items[:5]'

# 5. Точечно открыть карточку лидера
# (агент делает navigate, читает address + описание)
```

Все mission'ы пишутся в `/var/minis/shared/_data/avito/scans/` —
через час/день можно сравнить с прошлыми замерами.
