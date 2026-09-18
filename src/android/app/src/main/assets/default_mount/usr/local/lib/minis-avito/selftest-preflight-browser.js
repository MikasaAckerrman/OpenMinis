// Offline-проверка preflight-browser.js: обе оболочки Avito, null-цена,
// WAF и обрыв транспорта. Сеть не используется — wdlib подменяется.
const fs = require('fs');
const src = fs.readFileSync(__dirname + '/preflight-browser.js', 'utf8');

let pass = 0, fail = 0;
function check(name, cond, detail) {
  if (cond) { pass++; } else { fail++; console.log('FAIL ' + name + ' — ' + detail); }
}

function stubLib(state, status, body) {
  return {
    requireHost: () => null,
    isBlocked: (s, b) => s !== 200 || /Доступ ограничен|Вы не робот/.test(b || ''),
    grab: async () => ({ ok: status === 200, status, body, state }),
    sink: null,
    post: async () => ({ ok: true })
  };
}

async function run(state, status, body) {
  global.window = { __WD_LIB: stubLib(state, status, body) };
  return await eval(src);
}

(async () => {
  const legacy = { __initialData__: { search: { mainCount: 3, allItems: {
    a: { value: { title: 'RTX 3060', price: '20 000 ₽', uri_mweb: '/x_1' } },
    b: { value: { title: 'RTX 3070', price: '30 000 ₽', uri_mweb: '/y_2',
                  analyticParams: { place: 'extra-items' } } }
  } } } };
  const ssr = { __mfe_state__: { loaderData: { data: { mainCount: 2, catalog: { items: [
    { type: 'item', value: { title: 'RTX 4060', priceDetailed: { value: 24000 }, urlPath: '/z_3' } },
    { type: 'item', value: { title: 'no price', urlPath: '/q_4' } }
  ] } } } } };
  const both = Object.assign({}, ssr, { __initialData__: { search: {} } });
  const nullPrice = { __mfe_state__: { loaderData: { data: { catalog: { items: [
    { type: 'item', value: { title: 'RTX 3060', price: null, urlPath: '/n_1' } }
  ] } } } } };
  const deepLink = { __mfe_state__: { loaderData: { data: { catalog: { items: [
    { type: 'item', value: { title: 'RTX 3060', priceDetailed: { value: 20000 },
                             uri: 'ru.avito://1/item/show' } }
  ] } } } } };

  let r = await run(legacy, 200, '<html>ok</html>');
  check('legacy SSR даёт ready', r.status === 'ready' && r.priced_items === 1, JSON.stringify(r));
  check('extra-items не считаются', r.own_items === 1, JSON.stringify(r));

  r = await run(ssr, 200, '<html>ok</html>');
  check('data-mfe-state даёт ready', r.status === 'ready' && r.priced_items === 1, JSON.stringify(r));

  r = await run(both, 200, '<html>ok</html>');
  check('пустой __initialData__ не скрывает каталог',
        r.status === 'ready' && r.priced_items === 1, JSON.stringify(r));

  r = await run(nullPrice, 200, '<html>ok</html>');
  check('price=null не роняет и не считается ready', r.status === 'no_data', JSON.stringify(r));

  r = await run(deepLink, 200, '<html>ok</html>');
  check('ru.avito deep link не считается пригодным', r.status === 'no_data', JSON.stringify(r));

  r = await run({}, 429, '');
  check('429 = blocked', r.status === 'blocked', JSON.stringify(r));

  r = await run({}, 0, '');
  check('обрыв транспорта = unknown, не blocked', r.status === 'unknown', JSON.stringify(r));

  r = await run({}, 200, '<title>Доступ ограничен</title>');
  check('challenge при 200 = blocked', r.status === 'blocked', JSON.stringify(r));

  r = await run(ssr, 200, '<html>ok</html>');
  check('items совпадает с priced_items', r.items === r.priced_items, JSON.stringify(r));
  check('sink недоступен помечается', !!r.warn, JSON.stringify(r));

  console.log(pass + ' прошло, ' + fail + ' упало');
  process.exit(fail ? 1 : 0);
})();
