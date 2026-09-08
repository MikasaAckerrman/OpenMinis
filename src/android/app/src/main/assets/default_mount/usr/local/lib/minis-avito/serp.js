// Avito SERP extractor — селекторы сверены 2026-09-02 на живой выдаче.
// Вставляется через execute_js ИЛИ подтягивается страницей: fetch('http://127.0.0.1:8791/serp.js')
(() => {
  const num = s => { if (!s) return null; const d = String(s).replace(/[^\d]/g, ''); return d ? +d : null; };
  const t = (el, m) => { const e = el.querySelector('[data-marker="' + m + '"]'); return e ? e.innerText.trim() : null; };

  const out = [...document.querySelectorAll('[data-marker="item"]')].map(el => {
    const a = el.querySelector('a[data-marker="item/link"]') || el.querySelector('a[href]');
    const href = a ? a.getAttribute('href').split('?')[0] : null;
    const url = href ? (href.startsWith('http') ? href : 'https://www.avito.ru' + href) : null;
    const rr = t(el, 'sellerNameAndRatingSpreadContainer/rightChildren');
    let rating = null, reviews = null;
    if (rr) { const m = rr.match(/([\d,.]+)\s*\((\d+)\)/); if (m) { rating = parseFloat(m[1].replace(',', '.')); reviews = +m[2]; } }
    const body = el.innerText.replace(/\s+/g, ' ');
    return {
      id: url ? (url.match(/_(\d{6,})$/) || [])[1] : null,
      title: t(el, 'titleLabelGrid'),
      price: num(t(el, 'priceLabelGrid')),
      seller: t(el, 'sellerAndRatingSellerLabel'),
      rating, reviews,
      posted: t(el, 'sortTimeGrid'),
      delivery: t(el, 'deliveryTermsGrid'),
      city: url ? url.split('/')[3] : null,
      shop: /Магазин|Компания/i.test(body) || null,
      badge_lowprice: /ниже рыночной/i.test(body) || null,
      installment: /рассрочк/i.test(body) || null,
      url
    };
  }).filter(x => x.url && x.price);

  return { page: location.href, count: out.length, items: out };
})();
