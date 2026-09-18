#!/usr/bin/env python3
"""humanize.py — превращает вопросы в человеческую переписку.

Использование:
    python3 humanize.py --questions q1.txt q2.txt ...
    python3 humanize.py --yaml questions_list.yaml

Без LLM, чистые правила. Не претендует на идеальную маскировку — просто
убирает самые явные ИИ-маркеры (канцелярит, идеальная пунктуация,
структурированные списки). Если нужно больше — подключай LLM поверх.
"""
import argparse
import random
import re
import sys
from pathlib import Path

try:
    import yaml
except ImportError:
    yaml = None


HERE = Path(__file__).parent
CONFIG = HERE / 'config' / 'qa'


def load_rules():
    """Загрузить humanization.yaml и qa/dictionary.yaml."""
    rules = yaml.safe_load(open(CONFIG / 'humanization.yaml', encoding='utf-8'))
    return rules


def apply_replacements(text, rules):
    """Замены по словарю (прямой порядок — слева направо)."""
    for src, dst in rules.get('replacements', {}).items():
        # Регистронезависимо заменяем
        pattern = re.compile(re.escape(src), re.IGNORECASE)
        text = pattern.sub(dst, text)
    return text


def apply_abbreviations(text, rules):
    """Сокращения в духе живого общения. С вероятностью."""
    abbr = rules.get('live_abbreviations', [])
    if not abbr:
        return text
    for full, short in abbr:
        # Только если в нижнем регистре, чтобы не портить имена
        pattern = re.compile(r'\b' + re.escape(full) + r'\b')
        if pattern.search(text) and random.random() < 0.35:
            text = pattern.sub(short, text)
    return text


def apply_natural_typos(text, rules):
    """Одна-две естественные опечатки на сообщение."""
    typos = rules.get('natural_typos', [])
    if not typos:
        return text
    for src, dst, prob in typos:
        if src in text and random.random() < prob:
            text = text.replace(src, dst, 1)
    return text


def split_sentences(text):
    """Разбивка на предложения по точке/вопросу/восклицанию."""
    parts = re.split(r'(?<=[.!?])\s+', text.strip())
    return [p for p in parts if p]


def trim_to_live(text, rules):
    """Ограничение: 1-3 предложения в сообщении."""
    style = rules.get('style', {})
    max_s = style.get('max_sentences', 3)
    parts = split_sentences(text)
    if len(parts) <= max_s:
        return text
    return ' '.join(parts[:max_s])


def remove_banned_patterns(text, rules):
    """Убираем самые явные ИИ-маркеры структуры, не контента."""
    banned = rules.get('banned', [])
    # Что точно НЕ должно быть в письменной речи (структурные маркеры):
    structural = [
        'Маркированные списки',
        'Заголовки внутри сообщения',
        'Идеальная пунктуация',
        'Длинное тире',
        'Конструкции В первую очередь',
        'Конструкции Также хотел бы',
        'Конструкции Помимо этого',
        'Данное устройство вместо карта',
        'Осуществлять вместо делать',
    ]
    for s in structural:
        # Пары "слово вместо X" — это наши правила, не сам текст
        if ' вместо ' in s:
            src = s.split(' вместо ')[0].strip()
            # Заменяем только если это слово использовано В ИЗОЛЯЦИИ, а не как тема разговора
            pass
    # Длинное тире → дефис (это точно надо)
    text = text.replace(' — ', ' - ')
    text = text.replace('—', '-')
    return text


def humanize(text, rules=None):
    """Главный пайплайн."""
    if rules is None:
        rules = load_rules()
    if not text:
        return ''
    text = apply_replacements(text, rules)
    text = remove_banned_patterns(text, rules)
    text = apply_abbreviations(text, rules)
    text = trim_to_live(text, rules)
    text = apply_natural_typos(text, rules)
    # Чистка лишних пробелов и заглавная только в начале
    text = re.sub(r'\s+', ' ', text).strip()
    if text and text[0].isalpha():
        text = text[0].upper() + text[1:]
    return text


def select_questions(context, rules=None, max_n=5):
    """Выбирает вопросы из словаря по контексту объявления.
    context = {
      'title': 'KFA2 RTX 3070 SG',
      'description': '...',
      'flags': ['too_cheap', 'no_photos_real'],
      'model_match': 'kfa2_sg',
    }
    Возвращает список отранжированных вопросов.
    """
    if rules is None:
        rules = load_rules()
    qa = yaml.safe_load(open(CONFIG / 'dictionary.yaml', encoding='utf-8'))

    title = context.get('title', '').lower()
    flags = set(context.get('flags', []))
    model_match = context.get('model_match')

    selected = []
    # Universal
    for qid, q in qa.get('universal', {}).items():
        selected.append((q.get('priority', 50), 'universal', qid, q['text']))
    # Photo-driven
    for qid, q in qa.get('photo_driven', {}).items():
        when = q.get('when', '').lower()
        # Простая эвристика: текст 'когда' матчится по флагам
        if 'фото' in when and any(k in title for k in ('видеокарт', 'видео', 'rtx')):
            selected.append((q.get('priority', 50), 'photo', qid, q['text']))
    # Risk-driven
    for qid, q in qa.get('risk_driven', {}).items():
        when = q.get('when', '').lower()
        for flag in flags:
            if flag in when or any(w in when for w in flag.split('_')):
                selected.append((q.get('priority', 50) + 5, 'risk', qid, q['text']))
                break
    # Model-specific
    for qid, q in qa.get('model_specific', {}).items():
        if qid == model_match:
            selected.append((q.get('priority', 50), 'model', qid, q['text']))

    selected.sort(reverse=True, key=lambda x: x[0])
    return selected[:max_n]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--questions', nargs='*', help='строки вопросов через stdin или args')
    ap.add_argument('--mode', choices=['one', 'bundle', 'select'], default='bundle')
    ap.add_argument('--context', help='JSON с контекстом для --mode select')
    ap.add_argument('--max', type=int, default=5)
    args = ap.parse_args()

    rules = load_rules()
    if args.mode == 'select':
        import json as _j
        ctx = _j.loads(args.context) if args.context else {}
        sel = select_questions(ctx, rules, args.max)
        for priority, cat, qid, text in sel:
            hum = humanize(text, rules)
            print(f'[{cat}/{qid}] {hum}')
    else:
        if not args.questions:
            questions = [line.strip() for line in sys.stdin if line.strip()]
        else:
            questions = args.questions
        if args.mode == 'one':
            # Один вопрос — без объединения
            for q in questions:
                print(humanize(q, rules))
                print('---')
        else:
            # Bundle: каждое сообщение как отдельное, с ограничением по max
            cleaned = [humanize(q, rules) for q in questions[:args.max]]
            # Если max=1 — собираем в один текст, иначе через перевод строки
            if args.max == 1:
                print(cleaned[0] if cleaned else '')
            else:
                print('\n'.join(cleaned))


if __name__ == '__main__':
    main()
