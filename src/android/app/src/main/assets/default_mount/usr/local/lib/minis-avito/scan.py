#!/usr/bin/env python3
"""scan.py — единый сканер миссий Авито.

Архитектура:
  1. CLI: `avito scan <mission>` → URL из mission YAML, сохранить задачу.
  2. Агент: открывает URL в браузере, через execute_js вызывает
     SCAN.run(mission), получает JSON, передаёт в этот же Python.
  3. Python-сторона: применяет фильтры по словарю, считает score,
     сохраняет в /var/minis/shared/_data/avito/scans/.

Один источник истины — больше нет JS-копии логики в avito-scan.js.
JS в браузере делает только extract, всё остальное — на Python.
"""
import json
import re
import sys
from pathlib import Path

try:
    import yaml
except ImportError:
    yaml = None

HERE = Path(__file__).parent
CONFIG = HERE / 'config'
DATA = Path('/var/minis/shared/_data/avito/scans')
DATA.mkdir(parents=True, exist_ok=True)

# Импорт ScanLock из соседнего файла (тот же пакет)
sys.path.insert(0, str(HERE))
try:
    from scanlock import ScanLock
except ImportError:
    ScanLock = None   # если файла нет, работаем без блокировки


# ----- I/O YAML -------------------------------------------------------------

def load_yaml(p: Path) -> dict:
    if yaml is None:
        raise RuntimeError('pyyaml не установлен')
    with open(p, encoding='utf-8') as f:
        return yaml.safe_load(f)


def load_mission(name: str) -> dict:
    p = CONFIG / 'missions' / f'{name}.yaml'
    if not p.exists():
        avail = ', '.join(p.stem for p in (CONFIG / 'missions').glob('*.yaml'))
        raise FileNotFoundError(f'нет миссии {name}; доступны: {avail}')
    return load_yaml(p)


def load_filter(name: str) -> dict:
    p = CONFIG / 'filters' / f'{name}.yaml'
    return load_yaml(p)


# ----- URL builder ----------------------------------------------------------

def build_url(mission: dict, overrides: dict = None) -> str:
    """Собрать URL по mission-конфигу. overrides перекрывает pmin/pmax/q/s/p.
    search.aliases: список альтернативных запросов («ртх 3070ти» и т.п.).
    Возвращает список URL — по одному на alias (первый = base_query)."""
    region = mission.get('region', 'rossiya')
    cat = mission.get('category_path', '/tovary_dlya_kompyutera')
    search = mission.get('search', {})
    params = dict(search.get('params', {}))
    if overrides:
        for k in ('pmin', 'pmax', 'q', 's', 'p'):
            if overrides.get(k) is not None:
                params[k] = overrides[k]
    q = params.pop('q', search.get('base_query', ''))
    # Явный --q (overrides) = точное намерение пользователя: алиасы миссии
    # НЕ подмешиваются (иначе «ryzen 7 5700x» смешивался с «ртх 3070 ти»).
    explicit_q = bool(overrides and overrides.get('q'))
    queries = [q] if q else []
    if not explicit_q:
        for a in search.get('aliases', []):
            if a not in queries:
                queries.append(a)
    urls = []
    for query in queries:
        qs = [f'q={str(query).replace(" ", "+")}'] if query else []
        for k, v in params.items():
            qs.append(f'{k}={v}')
        urls.append(f'https://www.avito.ru/{region}{cat}?' + '&'.join(qs))
    return urls


# ----- Фильтры --------------------------------------------------------------

def _compile(patterns):
    return [re.compile(p) for p in patterns]


def apply_filter_hard_exclude(items, fdef):
    """Жёсткие исключения + обязательные маркеры (require_title) в сыром тексте."""
    rules = fdef.get('rules', {})
    patterns = _compile(rules.get('hard_exclude_title', []))
    require = _compile(rules.get('require_title', []))
    kept, dropped = [], []
    for it in items:
        excluded = False
        for pat in patterns:
            if pat.search(it['raw']):
                excluded = True
                it['reason'] = f'hard_exclude: {pat.pattern}'
                break
        if not excluded and require:
            hit = any(p.search(it['raw']) for p in require)
            if not hit:
                excluded = True
                it['reason'] = 'require_title: нет GPU-маркера'
        (dropped if excluded else kept).append(it)
    return kept, dropped


def apply_filter_delivery(items, fdef):
    """Только лоты с доставкой. Помечает риски от дальности."""
    rules = fdef.get('rules', {})
    keep_pats = _compile(rules.get('require_delivery', {}).get('keep_if_text_matches', []))
    far_pat = re.compile(rules.get('far_delivery', {}).get('pattern', r'(?i)доставка\s*от\s*[3-9]'))
    near_pat = re.compile(rules.get('near_delivery', {}).get('pattern', r'(?i)доставка\s*от\s*[1-2]'))
    full_pat = re.compile(rules.get('full_delivery_bonus', {}).get('pattern', r'(?i)только доставка'))

    kept, dropped = [], []
    for it in items:
        matched = any(p.search(it['raw']) for p in keep_pats)
        if not matched:
            dropped.append({**it, 'reason': 'no_delivery'})
            continue
        # Метрики доставки
        it['delivery_far'] = bool(far_pat.search(it['raw']))
        it['delivery_near'] = bool(near_pat.search(it['raw']))
        it['full_delivery'] = bool(full_pat.search(it['raw']))
        kept.append(it)
    return kept, dropped




def apply_filter_model_required(items, rules, mission=None):
    """Модель из mission.target.model обязана быть в заголовке.
    desktop-only пропускает любую rtx/gtx — здесь сужаем до своей."""
    import re as _re
    model = (mission or {}).get('target', {}).get('model', '')
    if not model:
        return items, []
    pat = _re.compile(r'(?i)\b' + _re.escape(model) + r'\b')
    kept, dropped = [], []
    for it in items:
        text = (it.get('raw') or '')
        if pat.search(text):
            kept.append(it)
        else:
            it['reason'] = f'model-required: нет "{model}" в заголовке'
            dropped.append(it)
    return kept, dropped




def apply_filter_delivery_preferred(items, rules, mission=None):
    """Доставка желательна, но не обязательна: без неё лот получает
    флаг no_delivery (+12 риск), не выбрасывается."""
    import re as _re
    pat = _re.compile(r'Доставка от (\d)')
    kept = []
    for it in items:
        m = pat.search(it.get('raw') or '')
        if m:
            it['delivery_days'] = int(m.group(1))
            it.setdefault('flags', []).append('near' if it['delivery_days'] <= 2 else 'far')
        else:
            fl = it.setdefault('flags', [])
            fl.append('no_delivery')
        kept.append(it)
    return kept, []


FILTERS = {
    'desktop-only': apply_filter_hard_exclude,
    'delivery-required': apply_filter_delivery,
    'model-required': apply_filter_model_required,
    'delivery-preferred': apply_filter_delivery_preferred,
}


# ----- Парсинг полей -------------------------------------------------------

def parse_fields(raw_item):
    text = raw_item['raw']
    out = dict(raw_item)
    # Цена: число ≥10 000 (у GPU сотни не бывают) либо с явным ₽.
    # Это отсекает модель «rtx 3070», время «8 часов», «(6)» отзывов.
    def _ru(int_part):
        return int(int_part.replace('\xa0', '').replace('\u202f', '').replace(' ', ''))
    # Цена: только если рядом ₽. Без ₽ цена не берётся ВООБЩЕ:
    # в grep без ₽ легко сбивается модель («rtx 3070 15000») и отзывы («(6)»).
    # Лагаем только суммы ≥10 000: GPU дешевле — битвая/кузовная/доставка, не карта.
    pm = re.search(r'(?<!\d)(\d{1,3}(?:[\s\xa0\u202f]\d{3}){1,}|[1-9]\d{4,5})\s*₽', text)
    if pm:
        price_val = _ru(pm.group(1))
        if price_val >= 10000:
            out['price'] = price_val
    if out.get('price'):
        sm = re.search(r'(\d{1,3}(?:[\s\xa0\u202f]\d{3})+)\s*₽\s+(\d{1,3}(?:[\s\xa0\u202f]\d{3})+)\s*₽\s*[−\-]\s*(\d+)\s*%', text)
        if sm:
            out['old_price'] = _ru(sm.group(2))
            out['discount_pct'] = int(sm.group(3))
    # Рейтинг и отзывы
    rm = re.search(r'(\d[.,]\d)\s*\((\d+)\)', text)
    if rm:
        out['rating'] = float(rm.group(1).replace(',', '.'))
        out['reviews'] = int(rm.group(2))
    # Гео
    out['has_metro'] = bool(re.search(r'\d+[–\-]\d+\s*мин', text))
    out['location_text'] = _extract_location(text)
    return out


def _extract_location(text):
    """Берём кусок между продавцом и доставкой/датами."""
    m = re.search(r'(?:\d,\d\s*\(\d+\))\s+(.+?)(?:\s+Доставка|\s+\d\s+дня|\s+\d+\s+час)', text)
    return m.group(1).strip() if m else ''


# ----- Скоринг --------------------------------------------------------------

def score_items(items, mission):
    """Каждому лоту проставляется risk_score. Возвращает (items, median)."""
    score_cfg = mission.get('score', {})
    prices = [it['price'] for it in items if it.get('price')]
    n = len(prices)
    if n == 0:
        return items, 0
    sorted_p = sorted(prices)
    median = sorted_p[n // 2] if n % 2 else (sorted_p[n // 2 - 1] + sorted_p[n // 2]) / 2

    # Паттерны риска из заголовка
    title_risk = score_cfg.get('title_risk_patterns', {})
    compiled_title = {
        k: (re.compile(v['pattern']), v['score'])
        for k, v in title_risk.items() if 'pattern' in v
    }

    # Пороги рейтинга. Храним как (имя, числовой_порог, score).
    # 'no_rating' даёт score при rating==None (отдельно).
    rating_thresholds = []  # list of (name, threshold_value, score)
    no_rating_score = 0
    for k, v in score_cfg.get('rating_thresholds', {}).items():
        thr = _parse_threshold(k)
        if thr == 'no_rating':
            no_rating_score = v['score']
        elif isinstance(thr, float):
            rating_thresholds.append((k, thr, v['score']))
    rating_thresholds.sort(key=lambda x: x[1], reverse=True)

    for it in items:
        s = 0
        text = it['raw']
        # Title risk
        for name, (pat, sc) in compiled_title.items():
            if pat.search(text):
                s += sc
                it.setdefault('flags', []).append(name)
        # Rating
        r = it.get('rating')
        if r is None:
            s += no_rating_score
            if no_rating_score:
                it.setdefault('flags', []).append('no_rating')
        else:
            matched = False
            # Сначала плохие пороги (lt_*), потом хорошие (gte_*).
            # Если ничего не сматчилось — нейтральный score.
            for name, thr, sc in rating_thresholds:
                if matched:
                    break
                if name.startswith('lt_') and r < thr:
                    s += sc
                    it.setdefault('flags', []).append('low_rating')
                    matched = True
                elif name.startswith('gte_') and r >= thr:
                    s += sc
                    it.setdefault('flags', []).append('high_rating')
                    matched = True
        # Price vs median
        p = it.get('price')
        if p and median:
            ratio = (median - p) / median
            for name, cfg in score_cfg.get('price_vs_median', {}).items():
                if name == 'below_25pct' and ratio > 0.25:
                    s += cfg['score']; it.setdefault('flags', []).append('too_cheap'); break
                if name == 'below_15pct' and 0.15 < ratio <= 0.25:
                    s += cfg['score']; it.setdefault('flags', []).append('cheap'); break
                if name == 'above_15pct' and ratio < -0.15:
                    s += cfg['score']; it.setdefault('flags', []).append('above_market'); break
        # Delivery
        if it.get('delivery_far'):
            s += score_cfg.get('delivery', {}).get('far_3plus_days', {}).get('score', 10)
            it.setdefault('flags', []).append('far')
        if it.get('delivery_near'):
            s += score_cfg.get('delivery', {}).get('near_1_2_days', {}).get('score', -3)
            it.setdefault('flags', []).append('near')
        # Без цены торг невозможен — лот вниз списка до ручного разбора
        if not it.get('price'):
            s += score_cfg.get('no_price', 40)
            it.setdefault('flags', []).append('no_price')
        # Флаги описания (descflags.py): перекуп/гарантия/майнинг/торг
        desc_score = score_cfg.get('desc_flags', {})
        for f in it.get('desc_flags') or []:
            s += desc_score.get(f, 0)
        it['risk_score'] = s
    return items, median


def _parse_threshold(name):
    """Переводит 'gte_4_8' → 4.8, 'lt_4_5' → 4.5, 'no_rating' → None.

    Формат: <направление>_<целая>[_<дробь>]. Подчёркивание — разделитель
    дробной части, т.к. YAML-ключи без точек. 'gte_4_8' = 4.8, 'lt_4' = 4.0.
    """
    if name == 'no_rating':
        return 'no_rating'
    m = re.match(r'(gte|lt)_(\d)(?:_(\d))?$', name)
    if not m:
        return None
    frac = m.group(3) or '0'
    return float(f'{m.group(2)}.{frac}')


def summarize(items, median):
    prices = sorted([it['price'] for it in items if it.get('price')])
    n = len(prices)
    if n == 0:
        return {'n': 0}
    mean = sum(prices) / n
    return {
        'n': n,
        'min': prices[0], 'max': prices[-1],
        'median': int(median) if median == int(median) else round(median, 2),
        'mean': int(mean) if mean == int(mean) else round(mean, 2),
        'q1': int(prices[int(n * 0.25)]) if n >= 4 else None,
        'q3': int(prices[int(n * 0.75)]) if n >= 4 else None,
    }


def rank(items, by='total_score'):
    if by == 'total_score':
        return sorted(items, key=lambda x: (x.get('risk_score', 0), x.get('price', 0)))
    if by == 'price':
        return sorted(items, key=lambda x: x.get('price', 0))
    return items


# ----- Главный запуск -------------------------------------------------------

def dedup_by_url(items):
    """Убирает дубли по url (пагинация сдвигает выдачу: лот на p=1
    повторяется на p=2). Оставляет ПЕРВОЕ вхождение — с более ранней
    страницы, свежие данные вторых копий теряются, но медиана честнее,
    чем задвоение. Возвращает (unique, dup_count)."""
    seen, unique, dups = set(), [], 0
    for it in items:
        key = it.get('url') or f"raw:{it.get('raw', '')[:120]}"
        if key in seen:
            dups += 1
            continue
        seen.add(key)
        unique.append(it)
    return unique, dups


def _drop_stats(dropped):
    """Счётчик причин дропа: {'hard_exclude': 20, 'model-required': 9, ...}"""
    from collections import Counter
    c = Counter((x.get('reason') or '?').split(':')[0] for x in dropped)
    return dict(c)


def run_on_items(raw_items, mission):
    """raw_items = [{raw, url, ...}, ...] — то, что вернул браузер.
    Применяем фильтры, считаем score, возвращаем dict."""
    items, dup_count = dedup_by_url([parse_fields(r) for r in raw_items])
    dropped_total = [{'raw': '(дубль по url)', 'reason': 'dedup'}] * dup_count
    for f in mission.get('post_filters', []):
        name = f.split(':')[0]
        fn = FILTERS.get(name)
        if fn is None:
            continue
        if name in ('model-required', 'delivery-preferred'):
            items, dropped = fn(items, None, mission)
        else:
            items, dropped = fn(items, load_filter(name), mission) if fn.__code__.co_argcount == 3 else fn(items, load_filter(name))
        dropped_total.extend(dropped)

    items, median = score_items(items, mission)
    stats = summarize(items, median)
    items_ranked = rank(items, mission.get('output', {}).get('rank_by', 'total_score'))

    return {
        'mission': mission.get('mission'),
        'url': build_url(mission),
        'target': mission.get('target'),
        'found_raw': len(raw_items),
        'kept': len(items),
        'dropped': len(dropped_total),
        'stats': stats,
        'items': items_ranked,
        'dropped_log': dropped_total,
        'drop_stats': _drop_stats(dropped_total),
    }


def save_result(result, mission_name, session_id=None):
    """Сохранить JSON и короткую сводку.

    Параллельная безопасность:
      - session_id добавляется к имени файла, чтобы 2 сессии не перезаписали друг друга
      - lock-файл на миссию на время записи (atomic write через rename)
    """
    import datetime as dt
    import os as _os
    ts = dt.datetime.now().strftime('%Y-%m-%d_%H%M%S')
    ms = dt.datetime.now().microsecond // 1000
    sess = session_id or _os.environ.get('MINIS_SESSION_ID', 'main')
    base_name = f'{mission_name}-{ts}-{ms:03d}-{sess}'
    base = DATA / base_name
    json_path = base.with_suffix('.json')

    # Блокировка на время записи (если доступна)
    lock_ctx = ScanLock(mission_name) if ScanLock else None
    if lock_ctx:
        lock_ctx.__enter__()
    try:
        # Атомарная запись: пишем во временный файл, затем rename.
        tmp_path = json_path.with_suffix('.tmp')
        try:
            with open(tmp_path, 'w', encoding='utf-8') as f:
                json.dump(result, f, ensure_ascii=False, indent=2)
                f.flush()
                _os.fsync(f.fileno())
            _os.replace(tmp_path, json_path)
        except Exception:
            if tmp_path.exists():
                tmp_path.unlink()
            raise
    finally:
        if lock_ctx:
            lock_ctx.__exit__(None, None, None)

    summary = {
        'mission': result['mission'], 'ts': ts, 'session': sess, 'url': result['url'],
        'found_raw': result['found_raw'], 'kept': result['kept'],
        'stats': result['stats'],
        'top5': [
            {'price': it.get('price'), 'rating': it.get('rating'),
             'reviews': it.get('reviews'), 'risk_score': it.get('risk_score'),
             'flags': it.get('flags', []), 'raw': it['raw'][:140]}
            for it in result['items'][:5]
        ],
    }
    return json_path, summary


def cli_mission_info(name):
    print(json.dumps({
        'mission': name,
        'url': build_url(load_mission(name)),
    }, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    if len(sys.argv) < 2 or sys.argv[1] in ('-h', '--help'):
        print('usage: scan.py <mission_name> [--url-only|--info] '
              '[--pmin N] [--pmax N] [--q "text"]')
        print()
        print('missions:')
        for p in (CONFIG / 'missions').glob('*.yaml'):
            print('  ' + p.stem)
        sys.exit(0)

    name = sys.argv[1]
    # Оверрайды: --pmin/--pmax/--q поверх миссии
    ov, rest = {}, []
    i = 2
    while i < len(sys.argv):
        a = sys.argv[i]
        if a in ('--pmin', '--pmax') and i + 1 < len(sys.argv):
            ov[a[2:]] = int(sys.argv[i + 1]); i += 2
        elif a == '--q' and i + 1 < len(sys.argv):
            ov['q'] = sys.argv[i + 1]; i += 2
        else:
            rest.append(a); i += 1
    flag = rest[0] if rest else '--info'
    if flag == '--url-only':
        print(build_url(load_mission(name), overrides=ov))
    elif flag == '--info':
        cli_mission_info(name)
    else:
        # Принимаем JSON лотов из stdin и применяем пайплайн
        raw = json.load(sys.stdin)
        items = raw if isinstance(raw, list) else raw.get('items', [])
        result = run_on_items(items, load_mission(name))
        json_path, summary = save_result(result, name)
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        print(f'\nполный JSON: {json_path}')
