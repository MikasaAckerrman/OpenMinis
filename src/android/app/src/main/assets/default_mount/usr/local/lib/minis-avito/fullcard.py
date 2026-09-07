#!/usr/bin/env python3
"""fullcard: b64 полного снимка карточки -> JSON полей.
stdin: b64([{url, title, price, desc, seller, rating, reviews, views, badges}])
stdout: JSON {url: {...поля, desc_flags}}"""
import base64, binascii, json, sys

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from descflags import extract_flags


def main():
    raw = sys.stdin.read()
    try:
        data = json.loads(base64.b64decode(raw.strip()).decode("utf-8"))
    except (binascii.Error, json.JSONDecodeError, ValueError) as e:
        raise SystemExit(f"битый b64/json: {e}")
    if isinstance(data, dict):
        data = [data]
    out = {}
    for it in data:
        if not isinstance(it, dict):
            continue
        url = it.get("url") or f"item{len(out)}"
        desc = it.get("desc") or it.get("text") or ""
        # XHR-HTML: описание после 'Описание</h3><div data-marker="item-description/text">'
        # до следующего тега. Чистим HTML-теги и сущности.
        import re as _re
        m2 = _re.search(r'item-description/text"[^>]*>([\s\S]{30,2000}?)</div>', desc)
        if m2:
            desc = _re.sub(r'<[^>]+>', ' ', m2.group(1))
            desc = _re.sub(r'&[a-z#0-9]+;', ' ', desc)
            desc = _re.sub(r'\s+', ' ', desc).strip()
        rec = {k: it.get(k) for k in ("title", "price", "seller", "rating", "reviews", "views", "badges")}
        rec["desc_len"] = len(desc)
        rec["desc_flags"] = extract_flags(desc)
        rec["desc"] = desc[:400]
        out[url] = rec
    print(json.dumps(out, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
