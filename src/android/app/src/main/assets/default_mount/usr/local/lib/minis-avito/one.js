// avito one — разбор ОДНОЙ ссылки объявления. Данные возвращаются в диалог.
// Сеть и разбор состояния — общий слой wdlib.js (web-deep).
//
//   window.__AV_URL='https://www.avito.ru/...';
//   if (!window.__WD_LIB) await eval(await (await fetch('http://localhost:8791/wdlib.js')).text());
//   await eval(await (await fetch('http://localhost:8791/one.js')).text())
(async () => {
  const L = window.__WD_LIB;
  if (!L) return { error: 'сначала загрузи wdlib.js' };
  const bad = L.requireHost(/(^|\.)avito\.ru$/);
  if (bad) return bad;

  const url = (window.__AV_URL || location.href).split('?')[0];
  const { num } = L;

  const r = await L.grab(url);
  if (!r.ok) return { error: 'не открылось после 3 попыток', status: r.status, url };

  const j = (r.state || {}).__initialData__;

  // Путь 1: полный JSON карточки — товары, телефоны, недвижимость, вакансии.
  if (j && j.item && j.item.item) {
    const x = j.item.item;
    // Мёртвая ссылка отвечает 200: item.item есть, но внутри только redirect
    // в категорию, без title. Отличаем именно так.
    if (x.redirect && !x.title)
      return { error: 'объявление не найдено или снято (redirect в категорию)',
               url, redirect: x.redirect.url || null };
    const s = x.seller || {}, pr = {};
    (((x.parameters || {}).flat) || []).forEach(p => { pr[p.title] = p.description; });
    return {
      src: 'json', url, id: x.id, title: x.title,
      price: num((x.price || {}).value_signed || (x.price || {}).value),
      price_text: (x.price || {}).value_signed || null,
      descr: x.description || null, props: pr,
      address: x.address || x.addressTitle || null, coords: x.coords || null,
      posted_ts: x.time || null, views: (x.stats || {}).views || null,
      user_type: x.userType || null,
      seller: s.name || null, seller_type: s.postfix || s.title || null,
      seller_hash: s.userHash || null,          // ключ профиля: /user/<hash>/profile
      seller_rating: s.rating ? (s.rating.scoreFloat ?? s.rating.score) : null,
      seller_reviews: s.rating ? num(s.rating.text) : null,
      seller_reply: (s.replyTime || {}).text || null,
      seller_since: s.registrationInfo || null, seller_ads: s.summary || null,
      seller_verified: !!s.isVerified, seller_confirmed: (s.connection || {}).title || null,
      seller_online: !!s.online,
      badges: (((x.badgeBar || {}).badges) || [])
        .map(b => (b.title && b.title.text) || b.title || '').filter(Boolean),
      images: (x.images || []).map(im => im['720x960'] || im['640x480'] || Object.values(im).pop())
        .filter(Boolean),
      // Способы связи: поле contacts НЕПОСТОЯННО — на той же карточке оно то есть
      // (contacts.list с type phone/messenger), то отсутствует. Поэтому null,
      // когда его нет: false означало бы «связаться нельзя», а это неизвестно.
      can_call: x.contacts ? ((x.contacts.list || []).some(c => c.type === 'phone')) : null,
      can_message: x.contacts ? ((x.contacts.list || []).some(c => c.type === 'messenger')) : null,
      safe_deal: !!x.safeDeal
    };
  }

  // CSR-fallback через hydrate для Авито запрещён: iframe тянет страницу и все
  // её подресурсы вне lease, то есть обходит общий IP-бюджет. Честный ответ —
  // «полей нет», а не тихая догрузка мимо лимитера.
  return {
    status: 'unknown', url,
    id: (url.match(/_(\d{6,})$/) || [])[1] || null,
    why: 'ни JSON-состояние, ни SSR-разметка не дали полей; CSR-обход запрещён',
    hint: 'повтори позже: канал соблюдает cooldown, лот мог быть под challenge'
  };
})();
