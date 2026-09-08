// avito live-DOM extractor — РЕЗЕРВ для случая, когда страницу пришлось открыть
// настоящим navigate (проверка безопасности, капча, авторизованный вид).
// crawl.js/one.js берут HTML через fetch — быстрее, но не видят того, что
// дорисовал JS уже после ответа сервера. Здесь читается живой DOM текущей вкладки.
(async () => {
  const t = m => { const e = document.querySelector('[data-marker="' + m + '"]'); return e ? e.innerText.trim() : null; };
  const num = s => { if (s == null) return null; const d = String(s).split(/[—–-]/)[0].replace(/[^\d]/g, ''); return d ? +d : null; };

  const more = document.querySelector('[data-marker="item-description/show-more-link"]');
  if (more) { more.click(); await new Promise(r => setTimeout(r, 500)); }

  // Две разметки характеристик: item-properties-item(N) (товары) и
  // item-card-parameters-name/value (автомобили). Берём обе.
  const props = {};
  document.querySelectorAll('[data-marker^="item-properties-item("]').forEach(el => {
    const m = el.getAttribute('data-marker');
    if (!/\)$/.test(m)) return;                       // парент отдаёт склеенный текст
    const k = el.querySelector('[data-marker="' + m + '/title"]');
    const v = el.querySelector('[data-marker="' + m + '/description"]');
    if (k && v) props[k.innerText.replace(/:\s*$/, '').trim()] = v.innerText.trim();
  });
  const nm = [...document.querySelectorAll('[data-marker="item-card-parameters-name"]')].map(e => e.innerText.trim());
  const vl = [...document.querySelectorAll('[data-marker="item-card-parameters-value"]')].map(e => e.innerText.trim());
  nm.forEach((n, i) => { props[n.replace(/:\s*$/, '')] = vl[i] || null; });

  // Рейтинг: иконки-звёзды НЕ считать (их 5 на блок, блоков 2 → «10»).
  const rvEl = document.querySelector('[itemprop="ratingValue"]');
  const rating = rvEl ? parseFloat((rvEl.getAttribute('content') || rvEl.innerText || '').replace(',', '.')) : null;
  const siTxt = (document.querySelector('[data-marker="seller-info"]') || {}).innerText || '';

  return {
    id: (location.pathname.match(/_(\d{6,})$/) || [])[1],
    url: location.origin + location.pathname,
    title: t('item-description/title'),
    price: num(t('item-description/price')),
    price_text: t('item-description/price'),
    descr: t('item-description/text'),
    props,
    seller: t('seller-info/name') || t('seller-contact/name'),
    seller_type: t('seller-info/postfix') || t('item-card-seller-top'),
    seller_online: t('seller-contact/online'),
    seller_rating: isNaN(rating) ? null : rating,
    seller_reviews: +((siTxt.match(/(\d+)\s*отзыв/) || [])[1]) || null,
    address: (t('single-address') || t('item-card-address/text') || '').split('\n')
      .filter(s => s && !/Местоположение|Узнать подробности|Показать на карте/.test(s)).join(', '),
    badges: [...document.querySelectorAll('[data-marker^="badge-title-"]')].map(e => e.innerText.trim()),
    images: [...document.querySelectorAll('[data-marker="card-gallery/image"] img, img[src*="img.avito.st"]')]
      .map(i => i.getAttribute('src')).filter(Boolean).slice(0, 20),
    can_message: !!document.querySelector('[data-marker="item-contact-bar/message"]'),
    can_call: !!document.querySelector('[data-marker="item-contact-bar/call"]'),
    blocked: /Доступ ограничен|проблема с IP|captcha/i.test(document.body.innerText) || null
  };
})();
