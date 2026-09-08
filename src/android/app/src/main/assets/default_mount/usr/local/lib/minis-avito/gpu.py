#!/usr/bin/env python3
"""gpu.py — подбор видеокарты из собранной выдачи Авито.

Зачем отдельно от report.py: тому дан один список и одна медиана. Для покупки
GPU нужно другое — сравнивать лот с медианой ЕГО модели (3060 и 3070 стоят
разного), выкидывать не-карты (коробки, кулеры, блоки питания, «куплю»),
понимать объём памяти (3060 бывает 8 и 12 ГБ — это разные товары) и считать
рублей за единицу производительности.

  python3 gpu.py                      топ по «цена/риск» в рамках бюджета
  python3 gpu.py --budget 25000 --top 12
  python3 gpu.py --model "3060 ti"    только одна модель
  python3 gpu.py --delivery           только с Авито Доставкой
  python3 gpu.py --json               машинный вывод
"""
import argparse, json, os, re, statistics as st, sys
import datetime as dt

# Межлотовые сигналы (один продавец с потоком карт, украденные фото, шаблонные
# описания, скорость просмотров) считает cross.py — он рядом, без сети.
try:
    import cross
except ImportError:
    cross = None

DATA = os.environ.get('AVITO_RAW', '/var/minis/shared/_data/avito/items.json')

# Относительная производительность (1080p, среднее по обзорам; 3060 12G = 1.00).
# Нужна не для точности, а чтобы сравнивать ₽ за производительность между моделями.
PERF = {
    '4060 ti': 1.55, '4060': 1.28, '3070 ti': 1.55, '3070': 1.42,
    '3060 ti': 1.25, '3060': 1.00, '2060 super': 0.92, '2060': 0.80,
    '1660 super': 0.72, '1660': 0.62,
}
# Порядок важен: «3060 ti» должно проверяться раньше «3060».
MODELS = ['4060 ti', '4060', '3070 ti', '3070', '3060 ti', '3060',
          '2060 super', '2060', '1660 super', '1660']

# Не видеокарта. Проверяется по заголовку — в описании эти слова законны.
NOT_GPU = re.compile(
    r'коробк|кулер|вентилятор|турбин|райзер|переходник|блок\s*питан|'
    r'\bбп\b|водоблок|термопрокладк|термопаст|бэкплейт|backplate|'
    r'систем[аы]\s*охлажд|кронштейн|держател|стойк|подставк|'
    r'наклейк|шлейф|кабел|адаптер|мониторе?\b|процессор|материнск', re.I)
WANTED = re.compile(r'\b(куплю|куплю|ищу|нужна|обмен|trade\s*-?in|обменяю)\b', re.I)

# Заведомо мёртвая карта. ЖЁСТКОЕ исключение, а не штраф в риске: пока такие
# лоты оставались в выборке, они утягивали медиану 4060 к 7 692 ₽ (реальная
# цена рабочей ~25 000 ₽), и весь рейтинг «рубль за производительность»
# заполнялся донорами по 3-6 тысяч. Медиана обязана считаться по рабочим.
DEAD = re.compile(
    r'нерабоч|не\s*рабоч|неисправ|\bдонор\b|на\s*запчаст|под\s*ремонт|'
    r'не\s*включ|не\s*выводит|без\s*ремонт|требует(ся)?\s*ремонт|'
    r'артефакт|мигает\s*экран|\bне\s*работает\b|'
    r'картинки\s*нет|нет\s*картинки|нет\s*изображ|не\s*показывает\s*изображ|'
    r'кристалл\s*не\s*работает|без\s*возвратов\s*и\s*претензий|'
    r'как\s*есть\s*на\s*запч|восстановлени|'
    # Замерено на живой выдаче: эти формулировки стояли у лотов, которые скоринг
    # ставил в топ с risk<25, хотя карта нерабочая.
    # «ошибка 43» — код 43 в диспетчере устройств Windows, чип не отвечает.
    # Падеж любой: «с ошибкой 43», «ошибка 43», «ошибки 43».
    r'ошибк\w*\s*43|код\s*43|\berror\s*43|'
    # «зелёные точки», «полосы» — артефакты видеопамяти.
    r'зелен(ые|ых|ые)\s*точк|цветн(ые|ых)\s*точк|полос(ы|ки)\s*на\s*экран|'
    # честное признание дефекта без слова «нерабочая»
    r'с\s*дефект|есть\s*дефект|дефект[:\s]|не\s*совсем\s*(корректно|правильно)|'
    r'причин[аы]\s*поломк|поломк[аи]|глюч|троит|перезагружа', re.I)

# Ноутбучная карта (MXM / распаянный чип) в десктоп НЕ ставится: другой разъём,
# нет питания PCIe, драйверы под мобильный ID. Замерено на живой выдаче: 5 из
# топ-10 по «рублю за производительность» оказались 3070m / mobile / laptop —
# то есть без этого фильтра инструмент советует то, что не соберётся.
# «m» проверяем только как отдельное слово после номера: «3070 m», «3070m».
MOBILE = re.compile(
    r'\b(mobile|мобильн|laptop|ноутбучн|для\s*ноут|\bmxm\b|'
    r'\d{4}\s*ti\s*m\b|\d{4}\s*m\b|\d{4}m\b)', re.I)

# Красные флаги в тексте. Веса: «убитая карта» должна перевешивать «дешевле рынка».
RED = [
    (r'\bмайнин|\bmining|\bферм[аеы]|хешрейт|hashrate', 'майнинг', 25),
    (r'артефакт|полос(ы|ит)|мигает|не выводит изображ|глюч', 'дефект изображения', 40),
    (r'на запчаст|под ремонт|не работает|нерабоч|неисправ|донор', 'нерабочая / на запчасти', 60),
    (r'прошит|перепрош|мод(ифицирован)?\s*bios|разлочен', 'модифицированный BIOS', 20),
    (r'реболл|перепа(й|йк)|ремонтир|замена чипа|перепрогрев', 'после ремонта чипа', 30),
    (r'предоплат|перевод(ом)?\s*(на|заранее)|только\s*налич', 'просит предоплату', 45),
    (r'cmp\s*\d+hx|нет\s*(видео)?выход|без\s*видеовыход|p10[46]', 'майнинг-версия без выходов', 70),
    (r'сроч(но|ная)\s*продаж|уезжаю|переезд', 'давление срочностью', 10),
    (r'без\s*(гарант|проверк|возврат)', 'без проверки/гарантии', 10),
    (r'\blhr\b', 'LHR-ревизия (2021+, часто из майнинга)', 5),
]
GOOD = [
    (r'гаранти[яйи]\s*(до|до\s*\d|\d+\s*(мес|год|лет))|на\s*гарантии', 'на гарантии', -20),
    (r'чек|коробк[аеу]\s*(есть|в наличии)|полный\s*комплект|документ', 'есть чек/комплект', -8),
    (r'нов(ая|ый|ое)\b|не\s*распакован|запечатан', 'новая', -15),
    (r'проверк[аеу]\s*(при|на месте)|можно\s*проверить|тест(ы|ирование)?\s*при', 'даёт проверить', -12),
]


def load():
    """Выдача + детали, склеенные по id. details перекрывают serp."""
    items = {}
    if os.path.exists(DATA):
        for x in json.load(open(DATA, encoding='utf-8')):
            if x.get('id'):
                items[str(x['id'])] = x
    dp = DATA + '.details'
    if os.path.exists(dp):
        for x in json.load(open(dp, encoding='utf-8')):
            if x.get('id'):
                items.setdefault(str(x['id']), {}).update(x)
    return list(items.values())


def norm_title(x):
    t = (x.get('title') or '').lower()
    return re.sub(r'\s+', ' ', deconfuse(t.replace('ё', 'е')))


# Гомоглифы: продавцы пишут «в нe pабoчeм cocтoянии», подменяя русские буквы
# латинскими. Замерено на живых карточках: до 32% символов описания — латиница
# внутри русских слов. Из-за этого regex «нерабоч» НЕ срабатывал, и мёртвая
# карта проходила как рабочая. Разворачиваем подмену перед любым поиском.
CONFUSE = {
    'a': 'а', 'c': 'с', 'e': 'е', 'o': 'о', 'p': 'р', 'x': 'х', 'y': 'у',
    'k': 'к', 'm': 'м', 'h': 'н', 't': 'т', 'b': 'в', 'r': 'г', 'n': 'п',
    'u': 'и', 'i': 'і', 'l': 'l', 'g': 'g', 'd': 'd', 's': 's', 'f': 'f',
    'A': 'А', 'B': 'В', 'C': 'С', 'E': 'Е', 'H': 'Н', 'K': 'К', 'M': 'М',
    'O': 'О', 'P': 'Р', 'T': 'Т', 'X': 'Х', 'Y': 'У',
}
LAT = re.compile(r'[A-Za-z]')
CYR = re.compile(r'[А-Яа-яЁё]')


def deconfuse(s):
    """Латиница внутри русского слова -> русские буквы.

    Только там, где слово смешанное: «Palit» и «GamingPro» остаются как есть,
    а «pабoчeм» становится «рабочем». Иначе поломались бы названия брендов.
    """
    out = []
    for word in re.split(r'(\W+)', s):
        if CYR.search(word) and LAT.search(word):
            word = ''.join(CONFUSE.get(ch, ch) for ch in word)
        out.append(word)
    return ''.join(out)


def homoglyph_ratio(s):
    """Доля латиницы внутри смешанных слов — признак намеренного обхода фильтров."""
    mixed = 0
    for word in re.split(r'\W+', s):
        if CYR.search(word) and LAT.search(word):
            mixed += len(LAT.findall(word))
    letters = len(CYR.findall(s)) + len(LAT.findall(s))
    return mixed / letters if letters else 0.0


def detect_model(x):
    """Модель по заголовку. Пробел/дефис/слитно: «3060ti», «3060 Ti», «3060-ti»."""
    t = norm_title(x)
    t = re.sub(r'(\d{4})\s*[-\s]?\s*(ti|тi|тай)\b', r'\1 ti', t)
    for m in MODELS:
        if re.search(r'\b' + m.replace(' ', r'\s*') + r'\b', t):
            return m
    return None


def detect_vram(x):
    """Объём памяти: 3060 бывает 8 и 12 ГБ — по цене это разные товары."""
    hay = norm_title(x) + ' ' + (x.get('descr') or '').lower()
    props = x.get('props') or {}
    for k, v in props.items():
        if 'памят' in k.lower():
            m = re.search(r'(\d{1,2})\s*(гб|gb)', str(v).lower())
            if m:
                return int(m.group(1))
    m = re.search(r'(\d{1,2})\s*(?:гб|gb)\b', hay)
    return int(m.group(1)) if m else None


def text_of(x):
    parts = [x.get('title') or '', x.get('descr') or '',
             ' '.join(x.get('badges') or [])]
    parts += ['%s %s' % (k, v) for k, v in (x.get('props') or {}).items()]
    return deconfuse(' '.join(parts).lower().replace('ё', 'е'))


def is_gpu(x):
    t = norm_title(x)
    if NOT_GPU.search(t) or WANTED.search(t):
        return False
    return detect_model(x) is not None


def is_dead(x):
    """Мёртвая карта: слова в заголовке ИЛИ в описании (после deconfuse).

    Проверять только заголовок нельзя: «Видеокарт Palit RTX 3070 TI» за 11 499 ₽
    в описании имеет «в не pабoчeм cocтoянии... на запчacти» с подменой букв.
    """
    return bool(DEAD.search(norm_title(x)) or DEAD.search(text_of(x)))


def is_mobile(x):
    """Ноутбучная карта — в настольный ПК не встанет."""
    return bool(MOBILE.search(norm_title(x)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--budget', type=int, default=25000)
    ap.add_argument('--model', default=None, help='только эта модель, напр. "3060 ti"')
    ap.add_argument('--delivery', action='store_true', help='только с Авито Доставкой')
    ap.add_argument('--mobile', action='store_true',
                    help='не отбрасывать ноутбучные карты (по умолчанию отброшены)')
    ap.add_argument('--top', type=int, default=12)
    ap.add_argument('--max-risk', type=int, default=100)
    ap.add_argument('--json', action='store_true')
    a = ap.parse_args()

    raw = load()
    gpus = [x for x in raw if x.get('price') and is_gpu(x)]
    dead = [x for x in gpus if is_dead(x)]
    gpus = [x for x in gpus if not is_dead(x)]
    mobile = [x for x in gpus if is_mobile(x)]
    if not a.mobile:
        gpus = [x for x in gpus if not is_mobile(x)]
    if not gpus:
        print('нет данных: сначала собери выдачу (avito → crawl.js)')
        return 1

    for x in gpus:
        x['_model'] = detect_model(x)
        x['_vram'] = detect_vram(x)

    # Медиана по модели, а не по всему списку: иначе 3070 всегда «дороже рынка».
    med = {}
    for m in set(x['_model'] for x in gpus):
        ps = sorted(x['price'] for x in gpus if x['_model'] == m)
        if len(ps) >= 4:                       # обрезаем крайние 10% с двух сторон
            lo, hi = int(len(ps) * .1), max(int(len(ps) * .9), 1)
            ps = ps[lo:hi] or ps
        med[m] = st.median(ps)

    rows = []
    for x in gpus:
        m = x['_model']
        mm = med[m]
        # Слишком дешёвый для своей модели — почти всегда не карта или обман.
        if x['price'] < mm * .35:
            continue
        dev = (x['price'] - mm) / mm * 100
        t = text_of(x)
        risk, why = 0, []
        for pat, label, w in RED + GOOD:
            if re.search(pat, t):
                risk += w
                why.append(label)
        if dev < -35:
            risk += 35; why.append('на %.0f%% ниже медианы модели' % abs(dev))
        elif dev < -20:
            risk += 15; why.append('на %.0f%% ниже медианы модели' % abs(dev))
        r = x.get('seller_rating') if x.get('seller_rating') is not None else x.get('rating')
        rv = x.get('seller_reviews') if x.get('seller_reviews') is not None else x.get('reviews')
        if r is None:
            risk += 15; why.append('рейтинг неизвестен')
        elif r < 4.5:
            risk += 20; why.append('рейтинг %s' % r)
        elif rv is not None and rv <= 1:
            risk += 10; why.append('отзывов мало (%s)' % rv)
        # Подмена русских букв латиницей — намеренный обход фильтров.
        # Замерено: у лотов «на запчасти», замаскированных под рабочие, 29-32%.
        hg = homoglyph_ratio((x.get('descr') or '') + ' ' + (x.get('title') or ''))
        if hg > 0.12:
            risk += 25; why.append('подмена букв в тексте (%.0f%%) — обход фильтров' % (hg * 100))
        if x.get('delivery') or x.get('safe_deal'):
            risk = max(int(risk * .6), risk - 15)
            why.append('Авито Доставка')
        perf = PERF.get(m, 1.0)
        rows.append(dict(x, _med=mm, _dev=dev, _risk=max(0, min(risk, 100)),
                         _why=why, _perf=perf, _rub_per_perf=x['price'] / perf))

    # Межлотовые сигналы: считаются по ВСЕЙ выборке, поэтому подмешиваются после
    # основного скоринга. Веса могут быть отрицательными («висит 3 недели» —
    # повод торговаться, а не риск).
    if cross is not None:
        try:
            sig = cross.analyze(*cross.load_all())
        except Exception as e:
            sig = {}
            print('cross.py: %s' % str(e)[:80], file=sys.stderr)
        for r in rows:
            s = sig.get(str(r.get('id'))) or {}
            for text, w in s.get('flags', []):
                r['_risk'] = max(0, min(r['_risk'] + w, 100))
                r['_why'].append(text)
            r['_notes'] = s.get('notes', [])

    if a.model:
        key = a.model.lower().strip()
        rows = [r for r in rows if r['_model'] == key]
    if a.delivery:
        rows = [r for r in rows if r.get('delivery') or r.get('safe_deal')]
    fit = [r for r in rows if r['price'] <= a.budget and r['_risk'] <= a.max_risk]

    if a.json:
        # --top уважается и здесь: иначе `avito shortlist --top 24` отдавал 111
        # ссылок и добор карточек шёл впустую по всей выдаче.
        best = sorted(fit, key=lambda r: (r['_rub_per_perf'], r['_risk']))
        print(json.dumps(best[:a.top], ensure_ascii=False))
        return 0

    fmt = lambda n: '{:,}'.format(int(n)).replace(',', ' ')
    print('видеокарт в выборке: %d (из %d записей), бюджет %s ₽'
          % (len(rows), len(raw), fmt(a.budget)))
    print('исключено как нерабочие/донор: %d' % len(dead))
    if mobile:
        print('исключено ноутбучных (MXM/mobile, в десктоп не встанут): %d%s'
              % (len(mobile), '' if not a.mobile else ' — но показаны (--mobile)'))
    print()
    print('медиана по модели:')
    for m in sorted(med, key=lambda k: -PERF.get(k, 0)):
        n = sum(1 for x in gpus if x['_model'] == m)
        print('  %-9s %8s ₽   лотов %-3d  перф %.2f  ₽/перф %s'
              % (m, fmt(med[m]), n, PERF.get(m, 1.0), fmt(med[m] / PERF.get(m, 1.0))))

    print()
    print('=== ЛУЧШИЕ: рубль за производительность, при риске <= %d ===' % a.max_risk)
    best = sorted(fit, key=lambda r: (r['_rub_per_perf'], r['_risk']))
    for r in best[:a.top]:
        tag = 'OK  ' if r['_risk'] < 25 else ('?   ' if r['_risk'] < 50 else 'RISK')
        vram = '%dG' % r['_vram'] if r['_vram'] else '  '
        print('[%s risk=%3d] %8s ₽  %-8s %-3s ₽/перф %-7s (%+.0f%% к медиане)'
              % (tag, r['_risk'], fmt(r['price']), r['_model'], vram,
                 fmt(r['_rub_per_perf']), r['_dev']))
        print('      %s' % (r.get('title') or '')[:70])
        when = r.get('posted')
        if not when and r.get('posted_ts'):
            when = dt.datetime.fromtimestamp(r['posted_ts']).strftime('%d.%m.%Y')
        rr = r.get('seller_rating') or r.get('rating') or '—'
        rv = r.get('seller_reviews') or r.get('reviews')
        meta = [r.get('seller') or '—', 'рейтинг %s' % rr,
                '%s отз.' % rv if rv else '', r.get('city') or '', when or '']
        print('      %s' % '  '.join(str(v) for v in meta if v))
        if r['_why']:
            print('      флаги: %s' % '; '.join(r['_why']))
        if r.get('_notes'):
            print('      факты: %s' % '; '.join(r['_notes'][:6]))
        print('      %s' % (r.get('url') or ''))
    over = sum(1 for r in rows if r['price'] > a.budget)
    if over:
        print()
        print('%d лотов выше бюджета — не показаны' % over)
    return 0


if __name__ == '__main__':
    sys.exit(main())
