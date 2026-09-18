// Preflight Avito через уже открытую вкладку. Один запрос, без массового сбора.
// Результат: capability, а не «найдено N» — 200 без состояния не считается готовым.
(async () => {
  const L = window.__WD_LIB;
  if (!L) return {error: 'сначала wdlib.js'};
  const bad = L.requireHost(/(^|\.)avito\.ru$/);
  if (bad) return bad;
  const q = window.__AV_PREFLIGHT_QUERY || 'rtx 3060';
  const path = window.__AV_PREFLIGHT_PATH || '/all/tovary_dlya_kompyutera';
  const u = 'https://www.avito.ru' + path + '?q=' + encodeURIComponent(q);
  const started = Date.now();
  const r = await L.grab(u, {retries: 1, channel: 'avito-browser'});
  const st = r.state || {};
  const j = st.__initialData__;
  const mfe = st.__mfe_state__;
  const search = j && j.search;
  // Обе оболочки читаются вместе: residual пустой __initialData__ не должен
  // скрывать заполненный SSR-каталог (та же логика, что в preflight.py).
  let own = [];
  let mainCount = null;
  if (search && search.allItems) {
    mainCount = search.mainCount || null;
    own = own.concat(Object.values(search.allItems)
      .map(w => (w && w.value) ? w.value : w)
      .filter(v => ((v || {}).analyticParams || {}).place !== 'extra-items'));
  }
  const data = (((mfe || {}).loaderData || {}).data) || {};
  const catalog = data.catalog || {};
  if (Array.isArray(catalog.items)) {
    mainCount = mainCount || data.mainCount || data.count || null;
    own = own.concat(catalog.items
      .filter(w => w && (w.type === 'item' || w.title))
      .map(w => (w && w.value) ? w.value : w)
      .filter(v => ((v || {}).analyticParams || {}).place !== 'extra-items'));
  }
  const priced = own.filter(v => {
    const p = v && v.price != null && typeof v.price === 'object'
      ? (v.price.current || v.price.priceWithoutDiscount || v.price.value)
      : (v && (v.price || (v.priceDetailed || {}).value));
    const rawUrl = v.urlPath || v.uri_mweb || v.uri || '';
    return v.title && p && rawUrl && !String(rawUrl).startsWith('ru.avito:');
  });
  const body = r.body || '';
  const wafCode = [403, 429, 439, 503].includes(Number(r.status));
  const challenge = r.status === 200 && L.isBlocked(r.status, body);
  const unknown = Number(r.status || 0) === 0 || (r.status && ![200,403,429,439,503].includes(Number(r.status)));
  const report = {
    source: 'avito', query: q,
    status: unknown ? 'unknown' : ((wafCode || challenge) ? 'blocked' :
            (priced.length ? 'ready' : 'no_data')),
    http_status: r.status, main_count: mainCount,
    own_items: own.length, priced_items: priced.length, items: priced.length,
    state: !!(search || mfe), elapsed_ms: Date.now() - started,
    evidence: {channel: 'browser+lease', url: u, blocked: challenge},
    observed_at: new Date().toISOString()
  };
  if (L.sink) {
    const saved = await L.post('/preflight?tool=avito', {preflight: report});
    report.saved = saved;
  } else {
    report.warn = 'SINK недоступен — capability не сохранена';
  }
  return report;
})();
