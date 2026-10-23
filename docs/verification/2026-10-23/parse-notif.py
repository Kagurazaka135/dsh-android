import re
data = open('docs/verification/2026-10-23/notif-dump.txt', encoding='utf-8', errors='replace').read()
seen = set()
for m in re.finditer(r'channel=(dsh_\w+)', data):
    chunk = data[m.start():m.start()+4000]
    t = re.search(r'android\.title=String \(([^)]{1,60})\)', chunk)
    x = re.search(r'android\.text=String \(([^)]{1,90})\)', chunk)
    if t:
        key = (m.group(1), t.group(1))
        if key not in seen:
            seen.add(key)
            print(m.group(1), '|', t.group(1), '|', x.group(1) if x else '')
