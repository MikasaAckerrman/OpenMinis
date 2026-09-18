// avito details — пакетный добор КАРТОЧЕК по списку ссылок.
// Зачем отдельно от crawl.js: тот раскрывает первые N лотов своей выдачи, а для
// покупки нужно раскрыть ИМЕННО отобранный шортлист (иначе описания нет, а
// «не рабочем состоянии» пишут только в описании — по выдаче лот выглядит живым).
//
//   window.__AV_URLS = ['https://www.avito.ru/...', ...];   // или __AV_IDS
//   if (!window.__WD_LIB) await eval(await (await fetch('http://localhost:8791/wdlib.js')).text());
//   await eval(await (await fetch('http://localhost:8791/details.js')).text())
//
// Данные уходят в sink (/details?tool=avito), в диалог — только счётчики.
(async () => {
  const L = window.__WD_LIB;
  if (!L) return { error: 'сначала загрузи wdlib.js' };
  const bad = L.requireHost(/(^|\.)avito\.ru$/);
  if (bad) return bad;

  const { sleep, num } = L;
  // Список ссылок: либо явный __AV_URLS, либо шортлист из файла sink'а —
  // так URL не проходят через контекст диалога (24 ссылки это ~2 КБ текста).
  let urls = (window.__AV_URLS || []).map(u => String(u).split('?')[0]).filter(Boolean);
  if (!urls.length && L.sink) {
    try {
      const r = await fetch(L.sink + '/shortlist.json');
      if (r.ok) urls = (await r.json()).map(u => String(u).split('?')[0]);
    } catch (e) { /* нет файла — сообщим ниже */ }
  }
  if (!urls.length)
    return { error: 'пусто: задай window.__AV_URLS = [...] или положи shortlist.json рядом со скриптами' };
  // execute_js в браузере обрывается по таймауту 30 с, поэтому берём порцию:
  // сколько успеем, а уже добранные пропускаем (список ведёт sink).
  // Темп внутри порции задаёт канал (L.grab -> /slot), своей задержки нет.
  const limit = window.__AV_LIMIT || 8;
  let done = new Set();
  if (L.sink) {
    try {
      const r = await fetch(L.sink + '/collected?tool=avito');
      if (r.ok) done = new Set((await r.json()).urls || []);
    } catch (e) { /* нет данных — соберём всё */ }
  }
  const todo = urls.filter(u => !done.has(u)).slice(0, limit);
  if (!todo.length)
    return { asked: urls.length, got: 0, note: 'всё из шортлиста уже добрано' };

  function fromState(x, url) {
    const s = x.seller || {}, pr = {};
    (((x.parameters || {}).flat) || []).forEach(p => { pr[p.title] = p.description; });
    return {
      src: 'json', url, id: String(x.id || (url.match(/_(\d{6,})$/) || [])[1] || ''),
      title: x.title || null,
      price: num((x.price || {}).value_signed || (x.price || {}).value),
      price_text: (x.price || {}).value_signed || null,
      descr: x.description || null, props: pr,
      address: x.address || x.addressTitle || null, coords: x.coords || null,
      posted_ts: x.time || null, views: (x.stats || {}).views || null,
      user_type: x.userType || null,
      seller: s.name || null, seller_type: s.postfix || s.title || null,
      seller_hash: s.userHash || null,
      seller_rating: s.rating ? (s.rating.scoreFloat ?? s.rating.score) : null,
      seller_reviews: s.rating ? num(s.rating.text) : null,
      seller_reply: (s.replyTime || {}).text || null,
      seller_since: s.registrationInfo || null, seller_ads: s.summary || null,
      seller_verified: !!s.isVerified,
      seller_confirmed: (s.connection || {}).title || null,
      seller_online: !!s.online,
      badges: (((x.badgeBar || {}).badges) || [])
        .map(b => (b.title && b.title.text) || b.title || '').filter(Boolean),
      images: (x.images || [])
        .map(im => im['720x960'] || im['640x480'] || Object.values(im).pop())
        .filter(Boolean),
      safe_deal: !!x.safeDeal
    };
  }

  // SSR-разметка: путь для карточек без полного __initialData__.
  const readCard = d => {
    const T = m => {
      const e = d.querySelector('[data-marker="' + m + '"]');
      return e ? e.textContent.trim() : null;
    };
    const pr = {};
    // Фильтр /\)$/ обязателен: родитель item-properties-item(N) отдаёт
    // склеенный текст и роняет в словарь тройные дубли.
    d.querySelectorAll('[data-marker^="item-properties-item("]').forEach(el => {
      const mk = el.getAttribute('data-marker');
      if (!/\)$/.test(mk)) return;
      const k = el.querySelector('[data-marker="' + mk + '/title"]');
      const v = el.querySelector('[data-marker="' + mk + '/description"]');
      if (k && v) pr[k.textContent.replace(/:\s*$/, '').trim()] = v.textContent.trim();
    });
    return {
      title: T('item-description/title'),
      price_text: T('item-description/price'),
      price: num(T('item-description/price')),
      props: pr, descr: T('item-description/text'),
      address: (T('item-card-address/text') || T('single-address') || '').split('\n')[0] || null,
      seller_type: T('seller-info/postfix') || T('item-card-seller-top'),
      images: [...d.querySelectorAll('img[src*="img.avito.st"]')]
        .map(i => i.getAttribute('src')).slice(0, 15),
      _dom: true
    };
  };

  const pack = [], errs = [];
  for (const url of todo) {
    const r = await L.grab(url);
    if (!r.ok) { errs.push({ url, why: 'status ' + r.status }); continue; }

    const j = (r.state || {}).__initialData__;
    if (j && j.item && j.item.item) {
      const x = j.item.item;
      // Снятое объявление отвечает 200: есть redirect, нет title.
      if (x.redirect && !x.title) { errs.push({ url, why: 'снято (redirect)' }); continue; }
      pack.push(fromState(x, url));
      continue;
    }
    const doc = new DOMParser().parseFromString(r.body, 'text/html');
    const ssr = readCard(doc);
    if (ssr.title || ssr.descr || Object.keys(ssr.props).length) {
      pack.push(Object.assign({ url, id: (url.match(/_(\d{6,})$/) || [])[1] || '' }, ssr));
      continue;
    }
    errs.push({ url, why: 'ни JSON, ни разметка не дали полей' });
  }

  let saved = null;
  if (pack.length) saved = await L.post('/details?tool=avito', { details: pack });
  const res = { asked: urls.length, batch: todo.length, got: pack.length,
                left: urls.length - done.size - pack.length, saved, sink: L.sink };
  if (!L.sink) res.warn = 'SINK НЕДОСТУПЕН — данные НЕ сохранены';
  if (errs.length) res.errors = errs.slice(0, 8);
  // Сколько из добранных реально мёртвые — сразу видно, стоило ли раскрывать.
  const dead = pack.filter(p => /нерабоч|не\s*рабоч|запчаст|донор|картинки нет/i
    .test((p.descr || '') + ' ' + (p.title || ''))).length;
  res.dead_by_descr = dead;
  return res;
})();
