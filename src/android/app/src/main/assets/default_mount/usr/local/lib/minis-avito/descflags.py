#!/usr/bin/env python3
"""Читает описания карточек из offload-файла execute_js.

Вход: b64 JSON [{url, desc}] от execute_js на страницах карточек
(описание выдирается по data-marker="item-view/item-params" или
fallback: body.innerText от "Описание" до 1200 симв.).

Применяет описательные флаги к скорингу:
  trade_in / warranty / seals / artifact / parts_swap
Выход: JSON {url: flags[]}, читается report.py при генерации вопросов.
"""
import base64, binascii, json, os, re, sys

# Омоглифы: продавцы маскируют текст латиницей ("ТPЕЙД-ИН", "ЛИЧHАЯ
# ГAPАНТИЯ") — обходит фильтры Авито и наши регэкспы. Нормализуем.
_HOMOGLYPHS = str.maketrans({
    'A': 'А', 'B': 'В', 'C': 'С', 'E': 'Е', 'H': 'Н', 'K': 'К',
    'M': 'М', 'O': 'О', 'P': 'Р', 'T': 'Т', 'X': 'Х',
    'a': 'а', 'c': 'с', 'e': 'е', 'o': 'о', 'p': 'р', 'x': 'х',
})

def _normalize(desc):
    return desc.translate(_HOMOGLYPHS)

FLAG_PATTERNS = [
    ('trade_in', r'(?i)трейд[\s-]*ин|trade[\s-]*in'),
    ('warranty', r'(?i)гаранти\w*\s+\d+\s*(мес|месяц|год)'),
    ('seals', r'(?i)пломб\w*'),
    ('artifacts_ok', r'(?i)артефакт\w*\s*(нет|не\s*(замечено|обнаружено))'),
    ('artifacts_bad', r'(?i)артефакт\w*\s*(есть|замечено|обнаружено)'),
    ('mining_mentioned', r'(?i)майн(инг|ил)|ферм\w+'),
    ('mining_denied', r'(?i)(не|без)\s*(использовал\w*\s*)?(в\s*)?майн(инг|е)'),
    ('parts_swap', r'(?i)термопаст\w+|термопрокладк\w+'),
    ('box', r'(?i)коробк\w+|комплект'),
    ('negotiable', r'(?i)торг'),
    ('installment', r'(?i)рассрочка|кредит'),
]


def extract_flags(desc):
    flags = []
    desc_n = _normalize(desc)
    for name, pat in FLAG_PATTERNS:
        if re.search(pat, desc_n) or re.search(pat, desc):
            flags.append(name)
    # противоречия: артефакты и да, и нет
    if 'artifacts_ok' in flags and 'artifacts_bad' in flags:
        flags.remove('artifacts_ok')
        flags.append('artifacts_conflict')
    return flags


def main():
    raw = sys.stdin.read()
    try:
        data = json.loads(base64.b64decode(raw.strip()).decode('utf-8'))
    except (binascii.Error, json.JSONDecodeError, ValueError) as e:
        raise SystemExit(f'битый b64/json: {e}')
    out = {}
    for it in data:
        if isinstance(it, str):
            it = {'desc': it}
        # Два формата снимка: {url, desc} и {len, text} (разные execute_js)
        url = it.get('url', '') or it.get('text', '')[:0]
        desc = it.get('desc', '') or it.get('text', '')
        out[url or f'item{len(out)}'] = extract_flags(desc)
    print(json.dumps(out, ensure_ascii=False, indent=1))


if __name__ == '__main__':
    main()
