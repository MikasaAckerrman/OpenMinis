import sys, subprocess, json
scan = {"items": [{"price": 15000, "flags": ["too_cheap"], "risk_score": 5, "url": "https://avito.ru/x", "raw": "x"}]}
json.dump(scan, open("/tmp/r.json", "w"))
r = subprocess.run(["python3", "/var/minis/skills/avito/report.py", "/tmp/r.json", "--n", "1"], capture_output=True, text=True)
ok = "Ссылка:" in r.stdout and "Что спросить" in r.stdout and "слишком дёшево" not in r.stdout
cond = "ниже рынка" in r.stdout  # conditional по флагу too_cheap
print(f"report: базовые={ok}, условные={cond}")
sys.exit(0 if (ok and cond) else 1)
