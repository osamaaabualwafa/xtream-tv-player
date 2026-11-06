#!/usr/bin/env python3
"""All the emulator tests on this computer, in four levels. Pick the one that matches the change:

  python scripts/test.py quick home-tabs settings-look   only the screens a change touched, plus ratings (3-4 min)
  python scripts/test.py push                            before a push: every screen, focus, sizes,
                                                         contrast, Home switches (about 15 min)
  python scripts/test.py player                          seeking, the bubble, OK, resume, tracks (about 8 min)
  python scripts/test.py look                            themes, backgrounds, colours, scrolling speed (about 15 min)

  python scripts/test.py list                            every check, the level that runs it, its last result
  python scripts/test.py stop                            stop a run and put the emulator back

Each level runs the checks listed in TESTS.md (repository root) one after another and writes one
report in the same layout as GitHub's: .uitest/reports/<date>-<level>.md (the full output of every
check next to it). It never reads the real login: the checks use a fake playlist (mock_server.py)
and put the owner's settings and playlists back at the end.

GitHub runs its own two levels by itself: a short test after each push to main and the full test
on Wednesdays ([full qa] in a commit message runs the full one right after a push).
"""
import datetime
import json
import os
import re
import subprocess
import sys
import time

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
UITEST = os.path.join(ROOT, "scripts", "uitest")
REPORTS = os.path.join(ROOT, ".uitest", "reports")
STATUS = os.path.join(REPORTS, "status.json")

# Every check: name, what it covers, the command (in scripts/uitest), review items.
CHECKS = {
    "screens":     ("Every screen in Default and Glass: highlight cut off, crashes, changes from the saved pictures, text contrast",
                    ["run.py", "push"], "1.1C-1.1Z"),
    "screens-few": ("The named screens only, Glass with Lift", ["run.py", "quick"], ""),
    "sizes":       ("Every screen at the biggest interface and text sizes: cut text", ["size_check.py"], "size rule"),
    "focus":       ("Where the highlight lands on Home, Settings, Movies and the avatar picker", ["focus_checks.py"], "1.1C, 1.1S-1.1Z"),
    "home-rows":   ("Recently added switches: one case per Home layout and the Settings switch", ["recent_rows.py", "--short"], "1.1T(a)"),
    "home-rows-all": ("Recently added switches: every combination in H1-H4, also after a restart", ["recent_rows.py"], "1.1T(a)"),
    "ratings":     ("Ratings: Settings before and after a key, the key window and phone page, a made-up key refused, scores on posters, no provider ★",
                    ["ratings_checks.py"], "1.1E"),
    "contrast":    ("Text against what it sits on, every theme, at least 4.5:1 (no emulator)", ["check_contrast.py"], "1.1R"),
    "player":      ("Seeking and the bubble, OK, resume, screensaver, tracks, picture, login keyboard", ["player_checks.py"], "1.1I, 1.1J, 1.1M, 1.1P, 1.2A, 1.2B"),
    "themes":      ("Pictures: playlists, Home, Settings and the player in 7 themes", ["theme_matrix.py"], "1.1R"),
    "backgrounds": ("Pictures: Home with every Default background and colour", ["bg_grid.py", "latest"], "1.1Y"),
    "colours":     ("Colour table with contrast ratios for every theme (no emulator)", ["colour_table.py"], "1.1R"),
    "scrolling":   ("Holding Down through Movies and Series: frames, stutters, picture times", ["scroll_perf.py"], "1.1D"),
    "episodes":    ("Holding Right along a 300-episode row: frames, stutters, how far it got", ["episode_perf.py"], "1.1F"),
}

LEVELS = {
    "quick":  ["screens-few", "contrast", "ratings"],
    "push":   ["screens", "focus", "home-rows"],          # "screens" at this level also runs contrast and sizes
    "player": ["player"],
    "look":   ["contrast", "colours", "themes", "backgrounds", "home-rows-all", "scrolling", "episodes"],
}
MINUTES = {"quick": "3-4", "push": "about 15", "player": "about 8", "look": "about 15"}


def version():
    gradle = open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read()
    name = re.search(r'versionName = "([^"]+)"', gradle)
    sha = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=ROOT, capture_output=True, text=True).stdout.strip()
    dirty = subprocess.run(["git", "status", "--porcelain", "--", "app"], cwd=ROOT, capture_output=True, text=True).stdout.strip()
    return f"{name.group(1) if name else '?'} · {sha}{' + changes not committed yet' if dirty else ''}"


def build():
    """A fresh debug build of the code as it is now (the checks install it)."""
    print("Building the app…", flush=True)
    gradlew = os.path.join(ROOT, "gradlew.bat" if os.name == "nt" else "gradlew")
    out = subprocess.run([gradlew, "-q", "assembleDebug", "-PbuildNumber=99"], cwd=ROOT, capture_output=True, text=True)
    if out.returncode != 0:
        sys.exit("The build failed:\n" + (out.stdout + out.stderr)[-3000:])


def run_check(name, extra, folder):
    """Runs one check; returns (passed, failed, status, minutes, failures, notes)."""
    _, cmd, _ = CHECKS[name]
    args = [sys.executable, os.path.join(UITEST, cmd[0])] + cmd[1:] + extra
    print(f"\n── {name}: {' '.join(cmd + extra)}", flush=True)
    started = time.time()
    env = dict(os.environ, PYTHONIOENCODING="utf-8")
    proc = subprocess.Popen(args, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace", env=env)
    lines = []
    for line in proc.stdout:
        lines.append(line.rstrip("\n"))
        print("   " + line.rstrip("\n"), flush=True)
    code = proc.wait()
    minutes = (time.time() - started) / 60
    with open(os.path.join(folder, f"{name}.log"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    passes = [l for l in lines if l.startswith("PASS")]
    fails = [l[5:].strip() for l in lines if l.startswith("FAIL")]
    notes = []
    # Results some checks print in their own words.
    for l in lines:
        if re.match(r"\s*(Highlight cut off|Crashes or problems|Text contrast below|Different from baseline): [1-9]", l):
            fails.append(l.strip())
        m = re.match(r"\s*(\d+) below 4\.5:1", l)
        if m:
            fails.append(f"{m.group(1)} texts below 4.5:1 contrast")
        if l.startswith("| ") and "---" not in l:
            notes.append(l)
        if re.match(r"\s*(saved|Level \w+:|\d+ problems in)", l):
            notes.append(l.strip())
    if code != 0 and not fails:
        tail = [l for l in lines if l.strip()][-3:]
        fails.append(f"stopped with an error: {' / '.join(tail)[:300]}")
    status = "passed" if code == 0 and not fails else "failed"
    return len(passes), len(fails), status, minutes, fails, notes


def save_status(results):
    os.makedirs(REPORTS, exist_ok=True)
    try:
        status = json.load(open(STATUS, encoding="utf-8"))
    except Exception:
        status = {}
    stamp = datetime.datetime.now().strftime("%Y-%m-%d %H:%M")
    for name, (p, f, st, mins, _, _) in results.items():
        status[name] = {"when": stamp, "status": st, "passed": p, "failed": f}
    json.dump(status, open(STATUS, "w", encoding="utf-8"), indent=1)


def write_report(level, results, started, folder):
    when = datetime.datetime.now().strftime("%Y-%m-%d %H:%M")
    total_p = sum(r[0] for r in results.values())
    total_f = sum(r[1] for r in results.values())
    out = [f"## Tests on this computer — {level} — {when}", "",
           f"Version {version()} · {(time.time() - started) / 60:.0f} min", "",
           "| Check | Passed | Failed | Result | Minutes |", "|---|---|---|---|---|"]
    for name, (p, f, st, mins, _, _) in results.items():
        out.append(f"| {name} | {p} | {f} | {'✅' if st == 'passed' else '❌'} {st} | {mins:.1f} |")
    out.append(f"| **all** | **{total_p}** | **{total_f}** | {'✅ passed' if total_f == 0 else '❌ failed'} | |")
    failed = [(n, x) for n, r in results.items() for x in r[4]]
    if failed:
        out += ["", "### Failed"] + [f"- **{n}** — {x}" for n, x in failed]
    notes = [(n, x) for n, r in results.items() for x in r[5]]
    if notes:
        out += ["", "### Measurements and pictures"]
        for n, x in notes:
            out.append(x if x.startswith("|") else f"- **{n}** — {x}")
    out += ["", f"Full output of every check: .uitest/reports/{os.path.basename(folder)}/", ""]
    path = folder + ".md"
    open(path, "w", encoding="utf-8").write("\n".join(out))
    return path, total_f


def list_checks():
    try:
        status = json.load(open(STATUS, encoding="utf-8"))
    except Exception:
        status = {}
    level_of = {}
    for lv, names in LEVELS.items():
        for n in names:
            level_of.setdefault(n, []).append(lv)
    level_of.setdefault("sizes", []).append("push (with screens)")
    print(f"{'Check':13} {'Levels':22} {'Last result':28} What it checks")
    for name, (what, _, items) in CHECKS.items():
        s = status.get(name)
        last = f"{s['status']} {s['passed']}/{s['passed'] + s['failed']} ({s['when']})" if s else "not run yet"
        print(f"{name:13} {', '.join(level_of.get(name, ['-'])):22} {last:28} {what}" + (f"  [{items}]" if items else ""))


def main():
    args = sys.argv[1:]
    level = args[0] if args else ""
    if level == "list":
        return list_checks()
    if level == "stop":
        return subprocess.run([sys.executable, os.path.join(UITEST, "run.py"), "stop"]).returncode
    if level not in LEVELS:
        sys.exit(__doc__)
    if level == "quick" and len(args) < 2:
        sys.exit("quick needs the screens to check, for example: python scripts/test.py quick home-tabs settings-look")
    started = time.time()
    stamp = datetime.datetime.now().strftime("%Y-%m-%d_%H%M")
    folder = os.path.join(REPORTS, f"{stamp}-{level}")
    os.makedirs(folder, exist_ok=True)
    print(f"Level {level} ({MINUTES[level]} min): {', '.join(LEVELS[level])}")
    build()
    results = {}
    for name in LEVELS[level]:
        extra = args[1:] if name == "screens-few" else []
        results[name] = run_check(name, extra, folder)
    save_status(results)
    path, failed = write_report(level, results, started, folder)
    print("\n" + open(path, encoding="utf-8").read())
    print(f"Report: {os.path.relpath(path, ROOT)}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
