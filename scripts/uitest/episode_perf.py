#!/usr/bin/env python3
"""Review item 1.1F: how smooth the episode row is while Right is held for 10 seconds.

  python scripts/uitest/episode_perf.py            Glass and Default themes
  python scripts/uitest/episode_perf.py --record   also a screen recording (.uitest/)

The series has 300 episodes in one season, each with its own picture that takes a little time to
arrive, like a real server. Same measurements as scroll_perf.py (frames, late frames, picture times).
"""
import json
import os
import re
import subprocess
import sys
import threading
import time

os.environ.setdefault("MOCK_BIG", "1")
os.environ.setdefault("MOCK_IMG_DELAY", "0.15")
sys.path.insert(0, os.path.dirname(__file__))
import mock_server
import run as r
import player_checks as c
from scroll_perf import gfx_reset, gfx_read

RECORD = "--record" in sys.argv
RIGHT = 22
rows = []


def shot(name):
    with open(os.path.join(r.WORK, f"{name}.png"), "wb") as f:
        f.write(subprocess.run(r.ADB + ["exec-out", "screencap", "-p"], capture_output=True).stdout)


def focused_episode():
    """The episode number on the highlighted card (how far the hold got in 10 seconds)."""
    r.adb("shell", "uiautomator", "dump", "/sdcard/u.xml")
    x = r.adb("shell", "cat", "/sdcard/u.xml")
    i = x.find('focused="true"')
    m = re.search(r'text="E(\d+)', x[i:]) if i >= 0 else None
    return int(m.group(1)) if m else "?"


def hold(key, seconds):
    r.adb("shell", "am", "broadcast", "-a", "com.osama.xtreamtv.debug.HOLD", "--ei", "key", str(key), "--ei", "ms", str(int(seconds * 1000)))
    time.sleep(seconds + 1.5)


def measure(name, record=False):
    gfx_reset()
    rec = None
    if record:
        rec = subprocess.Popen(r.ADB + ["shell", "screenrecord", "--time-limit", "12", f"/sdcard/{name}.mp4"])
        time.sleep(0.5)
    hold(RIGHT, 10)
    result = gfx_read()
    result["reached"] = focused_episode()
    if rec:
        rec.wait()
        r.adb("pull", f"/sdcard/{name}.mp4", os.path.join(r.WORK, f"{name}.mp4"))
        r.adb("shell", "rm", f"/sdcard/{name}.mp4")
    rows.append((name, result))
    print(f"{name:14} " + "  ".join(f"{k}={v}" for k, v in result.items()), flush=True)


def open_series():
    """Home > Series > first category's first show > its episode row (focused when nothing is resumed)."""
    c.start()
    c.press(c.RT, c.RT)
    c.press(c.OK)
    r.wait_activity("BrowseActivity", 30)
    time.sleep(8)
    c.press(c.RT)
    time.sleep(3)
    c.press(c.OK)
    r.wait_activity("DetailsActivity", 30)
    time.sleep(6)


def main():
    server = mock_server.serve()
    threading.Thread(target=server.serve_forever, daemon=True).start()
    lists = "cache/lists/" + __import__("hashlib").md5(f"{mock_server.BASE}|{mock_server.USER}".encode()).hexdigest()
    themes = {"glass": {**r.GLASS, "glass_scene": 3}, "default": {"theme": 0}}
    try:
        r.install()
        r.adb("reverse", f"tcp:{mock_server.PORT}", f"tcp:{mock_server.PORT}")
        r.adb("shell", "am", "force-stop", r.PKG)
        r.app_shell(f"if [ -f {r.BACKUP} ]; then cp {r.BACKUP} {r.PREFS}; rm {r.BACKUP}; fi\n")
        r.add_test_playlist()
        c.use_stream("file")
        only = os.environ.get("PERF_ONLY", "")
        for name, prefs in themes.items():
            if only and only != name:
                continue
            r.app_shell(f"rm -rf {lists} cache/image_cache\n")
            r.set_prefs({**prefs, "home_style": 2, "browse_style": 0, "diag_on": "false",
                         **json.loads(os.environ.get("PERF_PREFS", "{}"))})
            open_series()
            shot(f"episodes-{name}-before")
            measure(f"episodes-{name}", record=RECORD and name == "glass")
            shot(f"episodes-{name}-after")
            r.adb("shell", "am", "force-stop", r.PKG)
    finally:
        r.app_shell(f"rm -f shared_prefs/playlist_{r.TEST_ID}.xml\nrm -rf {lists}\n")
        r.restore()
        r.adb("reverse", "--remove", f"tcp:{mock_server.PORT}")
        server.shutdown()
    print("\n| Theme | Frames | Frames over 33 ms | 50% / 90% / 99% frame | Episode reached | Pictures | Picture time (median / 90%) |")
    print("|---|---|---|---|---|---|---|")
    for name, x in rows:
        print(f"| {name} | {x['frames']} | {x['over33']} | {x['p50']} / {x['p90']} / {x['p99']} ms | {x['reached']} | {x['images']} | {x['img_med']} / {x['img_p90']} ms |")


if __name__ == "__main__":
    main()
