// avito seller — профиль продавца: отзывы, роль, стаж, все активные лоты.
// Зачем: рейтинг «5,0» ничего не значит, если это 1 отзыв от покупателя, аккаунт
// создан неделю назад, а в лотах ещё 12 таких же карт «на запчасти».
//
//   window.__AV_SELLERS = ['<hash>', ...];   // seller_hash из details
//   if (!window.__WD_LIB) await eval(await (await fetch('http://localhost:8791/wdlib.js')).text());
//   await eval(await (await fetch('http://localhost:8791/seller.js')).text())
//
// Если __AV_SELLERS не задан — хеши берутся из уже собранных details через sink.
// Данные уходят в sink (/sellers?tool=avito), в диалог — счётчики.
(async () => {
  const L = window.__WD_LIB;
  if (!L) return { error: 'сначала загрузи wdlib.js' };
  const bad = L.requireHost(/(^|\.)avito\.ru$/);
  if (bad) return bad;
  const { sleep, num } = L;

  let hashes = (window.__AV_SELLERS || []).map(String).filter(Boolean);
  if (!hashes.length && L.sink) {
    try {
      const r = await fetch(L.sink + '/hashes?tool=avito');
      if (r.ok) hashes = (await r.json()).hashes || [];
    } catch (e) { /* соберём из явного списка */ }
  }
  if (!hashes.length) return { error: 'нет хешей: задай window.__AV_SELLERS' };

  const limit = window.__AV_LIMIT || 3;      // execute_js обрывается на 30 с
  let done = new Set();
  if (L.sink) {
    try {
      const r = await fetch(L.sink + '/collected?tool=avito&kind=sellers');
      if (r.ok) done = new Set((await r.json()).ids || []);
    } catch (e) { }
  }
  const todo = hashes.filter(h => !done.has(h)).slice(0, limit);
  if (!todo.length) return { asked: hashes.length, got: 0, note: 'все профили уже собраны' };

  // Отзыв: текст + роль. «19 отзывов» != 19 продаж — в профиле смешаны отзывы
  // продавцу и покупателю, роль лежит в titleCaption записи.
  function readReviews(root) {
    const out = [];
    const walk = (o, d) => {
      if (!o || typeof o !== 'object' || d > 12) return;
      if (Array.isArray(o)) { o.forEach(x => walk(x, d + 1)); return; }
      const t = o.textSections || o.text;
      const hasScore = o.score != null || o.stageTitle || o.titleCaption;
      if (hasScore && (typeof t === 'string' || Array.isArray(t))) {
        const body = Array.isArray(t)
          ? t.map(s => (s && (s.text || s.title)) || '').join(' ')
          : String(t);
        out.push({
          score: o.score ?? null,
          role: o.titleCaption || o.stageTitle || null,
          when: o.deliveryTitle || o.createdTime || o.date || null,
          item: (o.itemTitle || (o.item && o.item.title)) || null,
          text: body.replace(/\s+/g, ' ').trim().slice(0, 400)
        });
      }
      for (const k in o) walk(o[k], d + 1);
    };
    walk(root, 0);
    // Дубли: одна и та же запись попадается в нескольких ветках состояния.
    const seen = new Set();
    return out.filter(r => {
      const k = (r.text || '') + '|' + (r.score ?? '') + '|' + (r.role || '');
      if (seen.has(k) || !r.text) return false;
      seen.add(k);
      return true;
    });
  }

  const pack = [], errs = [];
  for (const h of todo) {
    const rec = { hash: h, url: 'https://www.avito.ru/user/' + h + '/profile' };

    const r = await L.grab(rec.url, { retries: 2 });
    if (!r.ok) { errs.push({ hash: h, why: 'профиль: status ' + r.status }); }
    else {
      const st = r.state || {};
      const j = st.__initialData__ || st.__NEXT_DATA__ || st.__preloadedState__ || null;
      if (j) {
        rec.state_keys = Object.keys(j).slice(0, 12);
        rec.reviews = readReviews(j);
        // Числа рейтинга: itemprop надёжнее подсчёта иконок звёзд (их два блока
        // по пять, наивный подсчёт давал «10»).
        const doc = new DOMParser().parseFromString(r.body, 'text/html');
        const rv = doc.querySelector('[itemprop="ratingValue"]');
        const rc = doc.querySelector('[itemprop="reviewCount"],[itemprop="ratingCount"]');
        rec.rating = rv ? parseFloat(String(rv.getAttribute('content') || rv.textContent).replace(',', '.')) : null;
        rec.reviews_total = rc ? num(rc.getAttribute('content') || rc.textContent) : null;
        // Стаж: «На Авито с ноября 2025» — прямой текст, без вычислений.
        const since = (r.body.match(/На Авито с ([а-яё]+ \d{4})/i) || [])[1] || null;
        rec.since = since;
        rec.name = (doc.querySelector('h1') || {}).textContent || null;
      } else {
        rec.no_state = true;
        rec.since = (r.body.match(/На Авито с ([а-яё]+ \d{4})/i) || [])[1] || null;
      }
    }

    // Все активные лоты продавца. Единственный работающий роут (остальные 404).
    // Через L.grab, а не голым fetch: это такой же запрос к avito.ru и он
    // обязан считаться в лимите по IP, иначе канал недоучитывает темп.
    //
    // Формат проверен на живом ответе: {status, result:{count, items:[{type,
    // value:{...}}]}}. Лоты завёрнуты в {type:'item', value:{...}}, цена лежит
    // в value.price.string, ссылка в value.uri_mweb (uri содержит
    // ru.avito://... — приложенческую схему, в браузере бесполезна).
    // Прежний разбор (j.items с полями id/title/price) не совпадал ни с одним
    // уровнем ответа и молча давал ads_total = 0 при 41 реальном лоте.
    try {
      const ri = await L.grab('https://www.avito.ru/api/1/user/profile/items?sellerId=' + h,
                              { retries: 2, parse: false });
      if (ri.ok) {
        let j = null;
        try { j = JSON.parse(ri.body); } catch (e) { j = null; }
        const res = (j && j.result) || {};
        const arr = (res.items || []).filter(v => v && v.type === 'item' && v.value);
        rec.ads = arr.map(w => {
          const v = w.value;
          const raw = String(v.uri_mweb || v.uri || '');
          const path = raw.startsWith('ru.avito') ? '' : raw.split('?')[0];
          // price здесь СТРОКА («6 399 ₽»), а не объект: проверено на живом
          // ответе. Обращение к v.price.string давало undefined -> null.
          return {
            id: String(v.id || ''),
            title: v.title || null,
            price: num(typeof v.price === 'string'
                       ? v.price
                       : (v.price && (v.price.string || v.price.value))),
            time: v.time || null,
            url: path ? (path.startsWith('http') ? path : 'https://www.avito.ru' + path) : null
          };
        }).filter(a => a.title);
        // count — сколько всего у продавца, items — только первая страница.
        rec.ads_total = (res.count != null) ? res.count : rec.ads.length;
        rec.ads_page = rec.ads.length;
        if (!j) rec.ads_err = 'ответ не JSON';
      } else {
        rec.ads_status = ri.status;
      }
    } catch (e) { rec.ads_err = String(e).slice(0, 60); }

    pack.push(rec);
  }

  let saved = null;
  if (pack.length) saved = await L.post('/sellers?tool=avito', { sellers: pack });
  const res = { asked: hashes.length, batch: todo.length, got: pack.length,
                left: hashes.length - done.size - pack.length, saved, sink: L.sink };
  if (!L.sink) res.warn = 'SINK НЕДОСТУПЕН — данные НЕ сохранены';
  if (errs.length) res.errors = errs;
  res.summary = pack.map(p => ({ hash: p.hash.slice(0, 8), name: p.name,
                                 rating: p.rating, reviews: (p.reviews || []).length,
                                 since: p.since, ads: p.ads_total ?? null }));
  return res;
})();
