#!/usr/bin/env python3
"""Downloads a diagnostics report the TV is sharing (Settings > About > Share report).

  python scripts/diag_fetch.py 192.168.1.45:8095 4821

Each share is saved as it came (.uitest/reports/share-#N-date_time.txt; .uitest is never committed),
and merged into .uitest/reports/all.log by entry number, so a report shared twice adds nothing twice.
Prints the report's summary and the entries that are new to the computer.
"""
import os
import re
import sys
import urllib.error
import urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
DIR = os.path.join(ROOT, ".uitest", "reports")
ALL = os.path.join(DIR, "all.log")
ENTRY = re.compile(r"^#(\d+)\s")


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    address, code = sys.argv[1], sys.argv[2]
    try:
        with urllib.request.urlopen(f"http://{address}/report?code={code}", timeout=20) as r:
            body = r.read().decode("utf-8", "replace")
            disposition = r.headers.get("Content-Disposition", "")
    except urllib.error.HTTPError as e:
        sys.exit(f"The TV refused ({e.code}): check the code on the TV screen.")
    except OSError as e:
        sys.exit(f"Couldn't reach the TV at {address}: {e}")
    os.makedirs(DIR, exist_ok=True)
    m = re.search(r'filename="([^"]+)"', disposition)
    name = m.group(1) if m else "share.txt"
    with open(os.path.join(DIR, name), "w", encoding="utf-8") as f:
        f.write(body)
    known = {}
    if os.path.exists(ALL):
        with open(ALL, encoding="utf-8") as f:
            for line in f:
                e = ENTRY.match(line)
                if e:
                    known[int(e.group(1))] = line.rstrip("\n")
    lines = body.splitlines()
    head = [l for l in lines if not ENTRY.match(l)]
    new = []
    for line in lines:
        e = ENTRY.match(line)
        if e and int(e.group(1)) not in known:
            known[int(e.group(1))] = line
            new.append(line)
    with open(ALL, "w", encoding="utf-8") as f:
        for n in sorted(known):
            f.write(known[n] + "\n")
    print(f"Saved {name}")
    print("\n".join(l for l in head if l.strip()))
    print(f"\n{len(new)} entries new to this computer ({len(lines) - len(head) - len(new)} already seen). All entries: {ALL}")
    for line in new[-60:]:
        print(line)


if __name__ == "__main__":
    main()
