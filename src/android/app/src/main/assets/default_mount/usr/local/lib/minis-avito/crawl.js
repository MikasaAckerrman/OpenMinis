// avito crawl v3 — сбор выдачи и карточек Авито.
// Сеть, паттерны состояния, ретраи, гидратация — из общего слоя wdlib.js
// (web-deep). Здесь ТОЛЬКО доменные знания об Авито.
//
// Загружается так (одним execute_js):
//   window.__AV = {q:'rtx 3060', pages:3, params:{pmax:25000,s:104}, details:8};
//   if (!window.__WD_LIB) await eval(await (await fetch('http://localhost:8791/wdlib.js')).text());
//   await eval(await (await fetch('http://localhost:8791/crawl.js')).text())
//
// window.__AV:
//   q, path ('/all/tovary_dlya_kompyutera'), pages, params{pmax,pmin,s,radius,geoCoords,user,...}
//   details — сколько карточек раскрыть, includeExtra — брать добор из других городов
(async () => {
  const L = window.__WD_LIB;
  if (!L) return { error: 'сначала загрузи wdlib.js: eval(await (await fetch(SINK+"/wdlib.js")).text())' };

  const bad = L.requireHost(/(^|\.)avito\.ru$/);
  if (bad) return bad;

  const C = Object.assign({
    path: '/all/tovary_dlya_kompyutera', q: '', pages: 2, params: {},
    details: 0, includeExtra: false
  }, window.__AV || {});
  const { sleep, num } = L;

  // ---------------------------------------------------------------- выдача
  // Карточка выдачи приходит в двух форматах:
  //   «beduin» — freeForm (дерево вёрстки) + price.current
  //   классический (vakansii) — uri = ru.avito://…, реальный путь в uri_mweb,
  //   price строкой, description прямо в выдаче
  function parseSerpItem(id, v, extra) {
    const si = v.sellerInfo || {};

    // freeForm: дату и доставку брать ПО ID УЗЛА. Регуляркой по JSON ловилось
    // «1 дня» из «Доставка от 1 дня» вместо даты публикации.
    const ff = {};
    (function walk(o, d) {
      if (!o || typeof o !== 'object' || d > 10) return;
      if (Array.isArray(o)) { o.forEach(x => walk(x, d + 1)); return; }
      if (o.id && typeof o.text === 'string') ff[o.id] = o.text;
      for (const k in o) walk(o[k], d + 1);
    })(v.freeForm, 0);

    const rawUri = String(v.uri || '');
    const path = (rawUri.startsWith('ru.avito') ? String(v.uri_mweb || '') : rawUri).split('?')[0];
    const priceRaw = (v.price && typeof v.price === 'object')
      ? (v.price.current || v.price.priceWithoutDiscount) : v.price;
    const g0 = (v.galleryItems || [])[0];

    return {
      id, title: v.title,
      price: num(priceRaw),
      price_text: typeof priceRaw === 'string' ? priceRaw : null,
      price_sale: num((v.price || {}).sale),
      url: path ? 'https://www.avito.ru' + path : null,
      seller: si.name || null, is_shop: !!si.isShop,
      rating: si.rating ? (si.rating.scoreFloat ?? si.rating.score) : null,
      reviews: si.rating ? num(si.rating.text) : null,
      delivery: !!v.isDeliveryAvailable,
      delivery_terms: ff.deliveryTermsGrid || null,
      posted: ff.sortTimeGrid || null,          // бывает пустым — тогда см. posted_ts
      posted_ts: v.time || null,
      subtitle: v.subTitle || null,
      address: v.address || null,
      user_type: v.userType || null,
      descr: v.description || null,
      badges: (((v.badgeBar || {}).badges) || [])
        .map(b => (b.title && b.title.text) || b.title || '').filter(Boolean),
      city: path.split('/')[1] || null,
      extra_region: extra || null,
      thumb: g0 && g0.value ? (g0.value['372x372'] || Object.values(g0.value)[0]) : null
    };
  }

  // Резерв, если состояние не разобралось: мобильная разметка выдачи.
  function parseSerpDom(html, seen) {
    const doc = new DOMParser().parseFromString(html, 'text/html');
    const out = [];
    for (const el of doc.querySelectorAll('[data-marker="item"]')) {
      const T = m => { const e = el.querySelector('[data-marker="' + m + '"]'); return e ? e.textContent.trim() : null; };
      const a = el.querySelector('a[data-marker="item/link"]') || el.querySelector('a[href]');
      if (!a) continue;
      const url = 'https://www.avito.ru' + a.getAttribute('href').split('?')[0];
      const id = (url.match(/_(\d{6,})$/) || [])[1];
      if (!id || seen.has(id)) continue;
      seen.add(id);
      const rr = T('sellerNameAndRatingSpreadContainer/rightChildren') || '';
      const mm = rr.match(/([\d,.]+)\s*\((\d+)\)/);
      out.push({
        id, url, title: T('titleLabelGrid'), price: num(T('priceLabelGrid')),
        seller: T('sellerAndRatingSellerLabel'),
        rating: mm ? parseFloat(mm[1].replace(',', '.')) : null,
        reviews: mm ? +mm[2] : null,
        posted: T('sortTimeGrid'), delivery: !!T('deliveryTermsGrid'),
        city: url.split('/')[3], _dom: true
      });
    }
    return out;
  }

  const seen = new Set(), items = [], errors = [];
  let total = null;
  for (let p = 1; p <= C.pages; p++) {
    const qs = new URLSearchParams(Object.assign({}, C.params,
      C.q ? { q: C.q } : {}, p > 1 ? { p } : {}));
    const r = await L.grab('https://www.avito.ru' + C.path + '?' + qs);
    if (!r.ok) { errors.push({ page: p, why: 'status ' + r.status }); break; }

    const j = (r.state || {}).__initialData__;
    let got = 0;
    if (j && j.search && j.search.allItems) {
      total = j.search.mainCount || total;
      for (const id in j.search.allItems) {
        const v = (j.search.allItems[id] || {}).value;
        if (!v || seen.has(id)) continue;
        // allItems мешает ДВА списка: свою выдачу (place=serp-items) и добор
        // «похожее из других городов» (place=extra-items). По Ульяновску
        // mainCount=16, а записей 46 — без фильтра отчёт врал.
        const extra = (((v.analyticParams || {}).place) || '') === 'extra-items';
        if (extra && !C.includeExtra) continue;
        seen.add(id); got++;
        items.push(parseSerpItem(id, v, extra));
      }
    } else {
      const dom = parseSerpDom(r.body, seen);
      got = dom.length;
      items.push(...dom);
      if (!got) errors.push({ page: p, why: 'ни состояния, ни карточек в разметке' });
    }
    if (!got) break;                            // страниц больше нет
    // Своего sleep здесь больше нет: темп задаёт канал через L.grab -> /slot.
    // Двойная задержка только удлиняла сбор, не снижая риск блокировки.
  }
  // ?tool=avito — sink кладёт данные в свой каталог инструмента, а не в общий
  const savedSerp = items.length ? await L.post('/items?tool=avito', { items }) : null;

  // ---------------------------------------------------------------- карточки
  // Три источника, по убыванию полноты:
  //   1) item.item в __initialData__ — товары, телефоны, недвижимость, вакансии
  //   2) SSR-разметка — редкий случай
  //   3) гидратация в iframe — автомобили: в HTML полей НЕТ вообще,
  //      только item.itemCardView с аналитикой
  function fromState(x, base) {
    const s = x.seller || {}, pr = {};
    (((x.parameters || {}).flat) || []).forEach(p => { pr[p.title] = p.description; });
    return Object.assign({}, base, {
      descr: x.description || null, props: pr,
      address: x.address || x.addressTitle || null,
      coords: x.coords || null,
      posted_ts: x.time || base.posted_ts || null,
      views: ((x.stats || {}).views) || null,
      user_type: x.userType || null,            // private | company
      seller: s.name || base.seller,
      seller_type: s.postfix || s.title || null,
      seller_hash: s.userHash || null,          // ключ профиля: /user/<hash>/profile
      seller_rating: s.rating ? (s.rating.scoreFloat ?? s.rating.score) : base.rating,
      seller_reviews: s.rating ? num(s.rating.text) : base.reviews,
      seller_reply: (s.replyTime || {}).text || null,
      seller_since: s.registrationInfo || null,
      seller_ads: s.summary || null,
      seller_verified: !!s.isVerified,
      seller_confirmed: (s.connection || {}).title || null,
      seller_online: !!s.online,
      badges_item: (((x.badgeBar || {}).badges) || [])
        .map(b => (b.title && b.title.text) || b.title || '').filter(Boolean),
      images: (x.images || []).map(im => im['720x960'] || im['640x480'] || Object.values(im).pop())
        .filter(Boolean),
      safe_deal: !!x.safeDeal
    });
  }

  // Характеристики: две разметки. item-properties-item(N) — фильтр /\)$/
  // обязателен, иначе родитель отдаёт склеенный текст и в словарь падают
  // тройные дубли («Состояние: Б/у», «Состояние:», «Б/у»).
  function propsFromDoc(d) {
    const pr = {};
    const nm = [...d.querySelectorAll('[data-marker="item-card-parameters-name"]')].map(e => e.textContent.trim());
    const vl = [...d.querySelectorAll('[data-marker="item-card-parameters-value"]')].map(e => e.textContent.trim());
    nm.forEach((n, i) => { pr[n.replace(/:\s*$/, '')] = vl[i] || null; });
    d.querySelectorAll('[data-marker^="item-properties-item("]').forEach(el => {
      const mk = el.getAttribute('data-marker');
      if (!/\)$/.test(mk)) return;
      const k = el.querySelector('[data-marker="' + mk + '/title"]');
      const v = el.querySelector('[data-marker="' + mk + '/description"]');
      if (k && v) pr[k.textContent.replace(/:\s*$/, '').trim()] = v.textContent.trim();
    });
    return pr;
  }

  function readCard(d) {
    const T = m => { const e = d.querySelector('[data-marker="' + m + '"]'); return e ? e.textContent.trim() : null; };
    return {
      title: T('item-description/title'),
      price_text: T('item-description/price'),
      price: num(T('item-description/price')),
      props: propsFromDoc(d),
      descr: T('item-description/text'),
      address: (T('item-card-address/text') || T('single-address') || '').split('\n')[0] || null,
      seller_type: T('seller-info/postfix') || T('item-card-seller-top'),
      rating_model: T('item-card-rating/badge/text'),   // у авто — рейтинг МОДЕЛИ, не продавца
      images: [...d.querySelectorAll('img[src*="img.avito.st"]')]
        .map(i => i.getAttribute('src')).slice(0, 15),
      _hydrated: true
    };
  }

  let det = 0;
  const detErrors = [];
  if (C.details > 0) {
    const pack = [];
    for (const it of items.slice(0, C.details)) {
      const r = await L.grab(it.url);
      if (!r.ok) { detErrors.push({ id: it.id, why: 'status ' + r.status }); continue; }

      const j = (r.state || {}).__initialData__;
      if (j && j.item && j.item.item && j.item.item.title) {
        pack.push(fromState(j.item.item, it));
        continue;
      }
      // SSR-разметка карточки
      const doc = new DOMParser().parseFromString(r.body, 'text/html');
      const ssr = readCard(doc);
      if (Object.keys(ssr.props).length || ssr.descr) {
        delete ssr._hydrated;
        pack.push(Object.assign({}, it, ssr, { _dom: true }));
        continue;
      }
      // CSR-обход через iframe для Авито запрещён (подресурсы идут вне lease).
      // Такой лот помечается как неразобранный, а не догружается мимо лимитера.
      detErrors.push({ id: it.id,
                       why: 'ни JSON, ни SSR-разметка не дали полей; CSR-обход запрещён' });
    }
    if (pack.length) { await L.post('/details?tool=avito', { details: pack }); det = pack.length; }
  }

  const res = { total_found: total, serp: items.length, details: det, sink: L.sink };
  if (!L.sink) res.warn = 'SINK НЕДОСТУПЕН — данные НЕ сохранены';
  if (savedSerp) res.saved = savedSerp;
  if (errors.length) res.serp_errors = errors;
  if (detErrors.length) res.detail_errors = detErrors.slice(0, 5);
  return res;
})();
