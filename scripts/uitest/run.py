#!/usr/bin/env python3
"""Screen tests on the emulator. Choose the level that matches the change:

  python scripts/uitest/run.py quick home-tabs settings-look   one change: those screens, Glass + Lift
  python scripts/uitest/run.py push                            before a push: every screen, Glass + Lift and Default
  python scripts/uitest/run.py large series                    those screens at large interface and text sizes
  python scripts/uitest/run.py full                            theme or highlight code changed: all themes and styles
  python scripts/uitest/run.py stop                            stop a run and put the emulator back

Options: --baseline  accept the current screens as correct.  --no-install  keep the installed app.

It backs up the app's settings on the emulator, adds a fake playlist (mock_server.py), and restores
everything at the end, without reading the real login. It reports what the debug build's audit logged
("FocusClip": a highlighted item was cut), any crash or freeze, and which screens differ from their
baseline. Only one run at a time: a second one refuses to start.
"""
import gzip
import os
import re
import struct
import subprocess
import sys
import threading
import time
import zlib

sys.path.insert(0, os.path.dirname(__file__))
import check_contrast
import mock_server

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
WORK = os.path.join(ROOT, ".uitest")
BASE_DIR = os.path.join(WORK, "baseline")
DIFF_DIR = os.path.join(WORK, "diff")
LOCK = os.path.join(WORK, "run.pid")
APK = os.path.join(ROOT, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
PKG = "com.osama.xtreamtv"
PREFS = "shared_prefs/xtream.xml"
BACKUP = "files/uitest-settings.bak"
TEST_ID = "uitest"
ADB = ["adb", "-s", os.environ.get("UITEST_DEVICE", "emulator-5554")]

# The clock changes every minute: not compared. (left, top, right, bottom) as fractions of the screen.
MASKS = [(0.88, 0.0, 1.0, 0.14), (0.20, 0.05, 0.27, 0.12)]

# name: (settings, steps). Steps: keys, "shot name", "wait Activity", "text abc". All start on Home.
L, R, U, D, OK, MENU, BACK = "DPAD_LEFT", "DPAD_RIGHT", "DPAD_UP", "DPAD_DOWN", "DPAD_CENTER", "MENU", "BACK"
HOME_WALK = [R, R, L, "shot 1", D, D, R, "shot 2", D, L, "shot 3", U, U, U, U, "shot 4"]
SETTINGS = [U, R, OK, "wait SettingsActivity"]
def movies(style):
    return {"browse_style": style}, [R, OK, "wait BrowseActivity", D, D, R, "shot 1", D, R, R, D, "shot 2", U, U, U, "shot 3"]
SCREENS = {
    "home-hero": ({"home_style": 0}, HOME_WALK),
    "home-side": ({"home_style": 1}, HOME_WALK),
    "home-tiles": ({"home_style": 2}, HOME_WALK),
    "home-tabs": ({"home_style": 3}, HOME_WALK),
    "home-dock": ({"home_style": 4}, HOME_WALK),
    "drawer": ({"home_style": 2}, [MENU, "shot 1", R, R, "shot 2", BACK]),
    "settings-home": ({"home_style": 2}, SETTINGS + [R, "shot h1", R, "shot h2", D, L, "shot h3", D, "shot bottom"]),
    "settings-player": ({"home_style": 2}, SETTINGS + [D, D, R, "shot 1"] + [D] * 12 + ["shot 2", L, "shot 3"]),
    "settings-look": ({"home_style": 2}, SETTINGS + [D, D, D, R, "shot 1", D, R, "shot 2", D, D, "shot 3"]),
    "movies-sidebar": movies(0),
    "movies-folding": movies(1),
    "movies-chips": movies(2),
    "movies-preview": movies(3),
    "live": ({"home_style": 2, "channel_preview": "false"}, [OK, "wait BrowseActivity", D, D, R, "shot 1"] + [D] * 8 + ["shot 2", L, "shot 3"]),
    "series": ({"home_style": 2, "browse_style": 0}, [R, R, OK, "wait BrowseActivity", D, D, R, OK, "wait DetailsActivity", "shot 1", D, D, "shot 2", R, R, "shot 3"]),
    "search": ({"home_style": 2}, [R, R, R, OK, "wait SearchActivity", "text mov", "shot 1", D, D, D, "shot 2", R, "shot 3"]),
    "playlists": ({"home_style": 2}, [MENU, R, R, OK, "wait PlaylistsActivity", "shot 1", R, "shot 2"]),
    "drawer-guide": ({"home_style": 2}, [MENU, R, R, R, OK, "wait GuideActivity", "shot 1"]),
    "dock-switch": ({"home_style": 2}, SETTINGS + [R, D, D, OK, "shot chosen", BACK, "wait HomeActivity", "shot home"]),
}
# Audit only: video never looks the same twice.
AUDIT_ONLY = {"player": ({"home_style": 2, "browse_style": 0, "player_menu_style": 2}, [R, OK, "wait BrowseActivity", D, D, R, OK, "wait DetailsActivity", OK, "wait PlayerActivity", OK, "shot 1", R, R, "shot 2"])}
GLASS = {"theme": 4, "glass_scene": 0, "glass_style": 0, "focus_style": 3, "ui_size": 0, "text_size": 1, "palette": 0}
DEFAULT = {"theme": 0, "palette": 0, "ui_size": 0, "text_size": 1, "focus_style": 0}
COMBOS = {
    "quick": [("glass-lift", GLASS)],
    "large": [("default-large", {**DEFAULT, "ui_size": 2, "text_size": 3}), ("glass-large", {**GLASS, "ui_size": 2, "text_size": 3})],
    "push": [("glass-lift", GLASS), ("default", DEFAULT)],
    "full": [("default", DEFAULT), ("cinema", {**DEFAULT, "theme": 1}),
             ("glass-ring", {**GLASS, "focus_style": 0}), ("glass-white", {**GLASS, "focus_style": 1}),
             ("glass-solid", {**GLASS, "focus_style": 2}), ("glass-lift", GLASS),
             ("daylight", {**GLASS, "focus_style": 0, "glass_scene": 8}),
             ("default-large", {**DEFAULT, "ui_size": 2, "text_size": 3}), ("glass-large", {**GLASS, "ui_size": 2, "text_size": 3})],
}


def adb(*args, stdin=None, binary=False):
    r = subprocess.run(ADB + list(args), input=stdin, capture_output=True, timeout=120)
    return r.stdout if binary else r.stdout.decode("utf-8", "replace")


def app_shell(script):
    """Commands inside the app's private folder (sent on stdin, so nothing needs quoting twice)."""
    return adb("shell", "run-as", PKG, "sh", stdin=script.encode())


def key(k):
    adb("shell", "input", "keyevent", k)


def grab():
    raw = adb("exec-out", "screencap", binary=True)
    w, h = struct.unpack("<II", raw[:8])
    header = len(raw) - w * h * 4
    return w, h, raw[header:]


def differs(a, b, w, h):
    """Rows and pixels that differ, outside the clock. Returns (count, box)."""
    if a == b:
        return 0, None
    rects = [(int(l * w), int(t * h), int(r * w), int(bm * h)) for l, t, r, bm in MASKS]
    count, box = 0, None
    stride = w * 4
    for y in range(h):
        ra, rb = a[y * stride:(y + 1) * stride], b[y * stride:(y + 1) * stride]
        if ra == rb:
            continue
        for x in range(w):
            if ra[x * 4:x * 4 + 3] != rb[x * 4:x * 4 + 3] and not any(l <= x < r and t <= y < bm for l, t, r, bm in rects):
                count += 1
                box = (min(box[0], x), min(box[1], y), max(box[2], x), max(box[3], y)) if box else (x, y, x, y)
    return count, box


def settle(timeout=6.0, calm=0.9):
    """Waits until the picture has stopped changing for a while (posters and swaps come late)."""
    time.sleep(0.45)
    end = time.time() + timeout
    w, h, last = grab()
    since = time.time()
    while time.time() < end and time.time() - since < calm:
        time.sleep(0.2)
        w, h, now = grab()
        if now != last and differs(now, last, w, h)[0] != 0:
            since = time.time()
        last = now
    return w, h, last


def wait_activity(name, timeout=25):
    end = time.time() + timeout
    while time.time() < end:
        if name in adb("shell", "dumpsys", "activity", "activities").split("topResumedActivity", 1)[-1][:200]:
            return True
        time.sleep(0.3)
    return False


def write_png(path, w, h, rgba):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    stride = w * 4
    rows = b"".join(b"\0" + rgba[y * stride:(y + 1) * stride] for y in range(h))
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(rows, 1)) + chunk(b"IEND", b""))


def set_prefs(values):
    """Sets simple settings; a missing one is added."""
    lines = []
    for name, value in values.items():
        tag = "boolean" if isinstance(value, str) else "int"
        lines.append(f"grep -q 'name=\"{name}\"' {PREFS} && sed -i 's|name=\"{name}\" value=\"[^\"]*\"|name=\"{name}\" value=\"{value}\"|' {PREFS} || "
                     f"sed -i 's|<map>|<map><{tag} name=\"{name}\" value=\"{value}\" />|' {PREFS}")
    app_shell("\n".join(lines) + "\n")


def add_test_playlist():
    """Backs up the settings, then puts a fake playlist first and opens it. The real ones stay untouched."""
    entry = ("{&quot;id&quot;:&quot;%s&quot;,&quot;name&quot;:&quot;Test&quot;,&quot;base&quot;:&quot;%s&quot;,"
             "&quot;user&quot;:&quot;%s&quot;,&quot;pass&quot;:&quot;%s&quot;}" % (TEST_ID, mock_server.BASE, mock_server.USER, mock_server.PASS))
    entry = entry.replace("&", r"\&").replace("/", r"\/")
    app_shell(f"""mkdir -p files
cp {PREFS} {BACKUP}
grep -q 'name="playlists">\\[{{' {PREFS} && sed -i 's/name="playlists">\\[/&{entry},/' {PREFS} || sed -i 's/<map>/<map><string name="playlists">[{entry}]<\\/string>/' {PREFS}
sed -i 's/<string name="active">[^<]*<\\/string>//; s/<string name="default_playlist">[^<]*<\\/string>//' {PREFS}
sed -i 's/<map>/<map><string name="active">{TEST_ID}<\\/string><string name="default_playlist">{TEST_ID}<\\/string>/' {PREFS}
""")


def restore():
    adb("shell", "am", "force-stop", PKG)
    app_shell(f"if [ -f {BACKUP} ]; then cp {BACKUP} {PREFS}; rm {BACKUP}; fi\n")


def is_running(pid):
    out = subprocess.run(["tasklist", "/FI", f"PID eq {pid}", "/NH"], capture_output=True, text=True).stdout if os.name == "nt" else ""
    if os.name == "nt":
        return str(pid) in out
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def stop():
    if os.path.exists(LOCK):
        pid = int(open(LOCK).read() or 0)
        if pid and is_running(pid):
            subprocess.run(["taskkill", "/F", "/T", "/PID", str(pid)] if os.name == "nt" else ["kill", "-9", str(pid)], capture_output=True)
        os.remove(LOCK)
    restore()
    print("Stopped; emulator settings restored.")


def run_screen(combo, name, setup, steps, compare, update, results):
    adb("shell", "am", "force-stop", PKG)
    # Every screen starts with a clean test account (the player step adds to Continue watching).
    app_shell(f"rm -f shared_prefs/playlist_{TEST_ID}.xml" + chr(10))
    set_prefs(setup)
    adb("logcat", "-c")
    for attempt in (1, 2):
        adb("shell", "am", "start", "-n", f"{PKG}/.PlaylistsActivity")
        if wait_activity("HomeActivity"):
            break
        seen = adb("shell", "dumpsys", "activity", "activities").split("topResumedActivity", 1)[-1][:90].strip()
        if attempt == 2:
            results["problems"].append(f"{combo} {name}: Home did not open, twice (on screen: {seen})")
            return
        results["slow"].append(f"{combo} {name} (on screen: {seen})")
        adb("shell", "am", "force-stop", PKG)
    settle()
    for step in steps:
        word, _, rest = step.partition(" ")
        if word == "wait":
            if not wait_activity(rest):
                results["problems"].append(f"{combo} {name}: {rest} did not open (test stopped on this screen)")
                break
            settle()
        elif word == "text":
            adb("shell", "input", "text", rest)
            settle()
        elif word == "shot":
            w, h, rgba = settle()
            results["shots"] += 1
            label = f"{combo}-{name}-{rest}"
            if not compare:
                continue
            base = os.path.join(BASE_DIR, label + ".raw.gz")
            if update or not os.path.exists(base):
                with gzip.open(base, "wb", compresslevel=1) as f:
                    f.write(struct.pack("<II", w, h) + rgba)
                results["new"] += 1
                continue
            old = gzip.open(base).read()[8:]
            count, box = differs(old, rgba, w, h) if len(old) == len(rgba) else (w * h, (0, 0, w, h))
            if count and box[2] - box[0] > 6:  # Narrower is the blinking text cursor.
                write_png(os.path.join(DIFF_DIR, label + ".png"), w, h, rgba)
                results["changed"].append(f"{label}: {100 * count / (w * h):.2f}% at x{box[0]}-{box[2]} y{box[1]}-{box[3]}")
        else:
            key(word)
    time.sleep(1.0)  # The audit looks 0.9 s after the last move.
    log = adb("logcat", "-d", "-v", "tag")
    for line in log.splitlines():
        m = re.search(r"FocusClip\s*:\s*(.*)", line)
        if m:
            results["crops"].setdefault(f"{name}: {m.group(1)}", set()).add(combo)
        if f"Process: {PKG}" in line or f"ANR in {PKG}" in line:
            results["problems"].append(f"{combo} {name}: {re.sub(r'https?://[^ ]+', '<url>', line)[:120]}")


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    level = args[0] if args else "quick"
    os.makedirs(BASE_DIR, exist_ok=True)
    os.makedirs(DIFF_DIR, exist_ok=True)
    if level == "stop":
        return stop()
    if level not in COMBOS:
        sys.exit(__doc__)
    if os.path.exists(LOCK):
        old = open(LOCK).read().strip()
        if old and is_running(int(old)):
            sys.exit(f"A test is already running (process {old}). Use: run.py stop")
    with open(LOCK, "w") as f:
        f.write(str(os.getpid()))
    started = time.time()
    server = mock_server.serve()
    threading.Thread(target=server.serve_forever, daemon=True).start()
    results = {"shots": 0, "new": 0, "changed": [], "crops": {}, "problems": [], "slow": []}
    try:
        print("Contrast check:")
        contrast_problems = check_contrast.report()
        wanted = args[1:] or list(SCREENS)
        unknown = [s for s in wanted if s not in SCREENS and s not in AUDIT_ONLY]
        if unknown:
            sys.exit(f"Unknown screens: {unknown}. Screens: {', '.join(list(SCREENS) + list(AUDIT_ONLY))}")
        if "--no-install" not in sys.argv:
            subprocess.run(ADB + ["install", "-r", APK], capture_output=True, timeout=180)
        adb("reverse", f"tcp:{mock_server.PORT}", f"tcp:{mock_server.PORT}")
        adb("shell", "am", "force-stop", PKG)
        app_shell(f"if [ -f {BACKUP} ]; then cp {BACKUP} {PREFS}; rm {BACKUP}; fi\n")  # A run that was killed.
        add_test_playlist()
        for f in os.listdir(DIFF_DIR):
            os.remove(os.path.join(DIFF_DIR, f))
        for combo, prefs in COMBOS[level]:
            adb("shell", "am", "force-stop", PKG)
            set_prefs(prefs)
            for name in wanted:
                setup, steps = SCREENS.get(name) or AUDIT_ONLY[name]
                print(f"  {combo} / {name}", flush=True)
                run_screen(combo, name, setup, steps, name in SCREENS, "--baseline" in sys.argv, results)
            if level != "quick" and "player" not in wanted:
                setup, steps = AUDIT_ONLY["player"]
                print(f"  {combo} / player", flush=True)
                run_screen(combo, "player", setup, steps, False, False, results)
    finally:
        restore()
        adb("reverse", "--remove", f"tcp:{mock_server.PORT}")
        server.shutdown()
        if os.path.exists(LOCK):
            os.remove(LOCK)
    lines = [f"\nLevel {level}: {results['shots']} screens in {(time.time() - started) / 60:.1f} min.",
             f"Highlight cut off: {len(results['crops'])}"]
    lines += [f"  {k}  [{', '.join(sorted(v))}]" for k, v in sorted(results["crops"].items())]
    lines.append(f"Crashes or problems: {len(results['problems'])}")
    lines += ["  " + p for p in results["problems"]]
    if results["slow"]:
        lines.append(f"Home needed a second try: {len(results['slow'])}")
        lines += ["  " + x for x in results["slow"]]
    lines.append(f"Text contrast below 4.5:1: {contrast_problems}")
    lines.append(f"New baselines saved: {results['new']}")
    lines.append(f"Different from baseline: {len(results['changed'])}" + (f" (pictures in {DIFF_DIR})" if results["changed"] else ""))
    lines += ["  " + c for c in results["changed"]]
    text = "\n".join(lines)
    print(text)
    with open(os.path.join(WORK, "report.txt"), "w", encoding="utf-8") as f:
        f.write(text + "\n")
    sys.exit(1 if results["crops"] or results["problems"] else 0)


if __name__ == "__main__":
    main()
