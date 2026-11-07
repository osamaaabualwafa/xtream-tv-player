#!/usr/bin/env python3
"""Reads a diagnostics report the TV shared (saved by diag_fetch.py) and prints what matters first:

  1. the report's summary, compared with the previous share (what got better or worse);
  2. every problem, grouped by screen, with the keys pressed before it and the settings in use;
  3. the slowest screens and content-ready times.

  python scripts/diag_read.py                      (the newest share in .uitest/reports)
  python scripts/diag_read.py share-#5-....txt     (a given one; compared with the share before it)

Reports never contain addresses, logins or keys (the TV cleans every line); titles only when the owner
turned on "Include titles".
"""
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
DIR = os.path.join(ROOT, ".uitest", "reports")
ENTRY = re.compile(r"^#(\d+)\s+(\S+ \S+)\s+(\S+)\s+(.*)$")
SHARE = re.compile(r"^share-#(\d+)-.*\.txt$")
PROBLEMS = {"crash", "ended", "freeze", "error", "ui", "MARK"}


def shares():
    """Saved shares, oldest first: (number, path)."""
    out = []
    for name in os.listdir(DIR) if os.path.isdir(DIR) else []:
        m = SHARE.match(name)
        if m:
            out.append((int(m.group(1)), os.path.join(DIR, name)))
    return sorted(out)


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read().splitlines()


def summary(lines):
    """The 'Summary' block as {name: value text}; empty for reports from before 2026-11-07."""
    out, inside = {}, False
    for line in lines:
        if line.startswith("Summary ("):
            inside = True
            continue
        if inside:
            if not line.startswith("  "):
                break
            name, _, value = line.strip().partition(": ")
            out[name] = value
    return out


def number(text):
    m = re.match(r"(?:median )?([\d.]+)", text or "")
    return float(m.group(1)) if m else None


def compare(now, before):
    print("== Summary" + (" (compared with the previous share)" if before else ""))
    if not now:
        print("  (this report has no summary: it was made by a version before 2.1.1)")
        return
    for name, value in now.items():
        old = before.get(name) if before else None
        a, b = number(value), number(old)
        mark = ""
        if a is not None and b is not None and a != b:
            # Fewer problems, faster times and more free memory are better.
            better = (a > b) if "free" in name else (a < b)
            mark = f"   ({'better' if better else 'WORSE'}: was {old})"
        elif before and old is None:
            mark = "   (new)"
        print(f"  {name}: {value}{mark}")
    if before:
        for name in before:
            if name not in now:
                print(f"  {name}: gone (was {before[name]})")


def screen_of(text):
    # Screen names start with a capital ("on Details"); "on top of each other" is not a screen.
    for pattern in (r" on ([A-Z]\w+)", r"^([A-Z]\w+):", r"^([A-Z]\w+) "):
        m = re.search(pattern, text)
        if m:
            return m.group(1)
    return "?"


def problems(lines, since):
    found = {}
    for line in lines:
        e = ENTRY.match(line)
        if not e or int(e.group(1)) <= since or e.group(3) not in PROBLEMS:
            continue
        text = e.group(4)
        found.setdefault(screen_of(text), []).append(f"#{e.group(1)} {e.group(2)} {e.group(3):6} {text}")
    print("\n== Problems by screen" + (f" (entries after #{since})" if since else ""))
    if not found:
        print("  none")
    for screen in sorted(found, key=lambda s: -len(found[s])):
        print(f"  {screen} ({len(found[screen])})")
        # The same kind of problem again and again: once, with how many times and the latest example.
        groups = {}
        for item in found[screen]:
            text = item.split(" ", 4)[-1]
            key = re.sub(r'"[^"]*"|\d+(\.\d+)?|· keys:.*$|\[[^\]]*\]', "", text)
            key = re.sub(r"\s+", " ", key).strip()[:90]
            groups.setdefault(key, []).append(item)
        for key, items in sorted(groups.items(), key=lambda g: -len(g[1])):
            prefix = f"{len(items)}x " if len(items) > 1 else ""
            print(f"    {prefix}{items[-1][:420]}")


def main():
    all_shares = shares()
    if len(sys.argv) > 1:
        path = os.path.join(DIR, os.path.basename(sys.argv[1])) if not os.path.exists(sys.argv[1]) else sys.argv[1]
    elif all_shares:
        path = all_shares[-1][1]
    else:
        sys.exit("No saved shares in " + DIR)
    m = SHARE.match(os.path.basename(path))
    number_now = int(m.group(1)) if m else None
    previous = [p for n, p in all_shares if number_now is not None and n < number_now]
    lines = read(path)
    before_lines = read(previous[-1]) if previous else []
    print(lines[0] if lines else path)
    for line in lines[1:6]:
        print(line)
    compare(summary(lines), summary(before_lines) if before_lines else None)
    # The problems that are new since the previous share.
    since = max((int(e.group(1)) for e in map(ENTRY.match, before_lines) if e), default=0)
    problems(lines, since)


if __name__ == "__main__":
    main()
