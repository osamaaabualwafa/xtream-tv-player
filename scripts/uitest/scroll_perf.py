#!/usr/bin/env python3
"""Review item 1.1D / 1.1F: how smooth Movies and Series are while Down is held for 10 seconds.

  python scripts/uitest/scroll_perf.py            Movies and Series, categories and posters
  python scripts/uitest/scroll_perf.py --record   also a screen recording of Movies posters (.uitest/)

Uses real-size lists (21,790 movies, 6,525 series) and makes every poster take a little time to
arrive, like a real server. Holds Down the
way a held remote button does (the test build plays it). Reports frames drawn and late frames
(debug builds log "FrameStat") and how long pictures take ("ImageTime").
"""
import os
import re
import statistics
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

RECORD = "--record" in sys.argv
rows = []


def gfx_reset():
    r.adb("shell", "dumpsys", "gfxinfo", r.PKG, "reset")
    r.adb("logcat", "-c")


def gfx_read():
    out = r.adb("shell", "dumpsys", "gfxinfo", r.PKG)
    def num(pattern, default="?"):
        m = re.search(pattern, out)
        return m.group(1) if m else default
    times = [int(x) for x in re.findall(r"ImageTime (\d+) ms", r.adb("logcat", "-d", "-s", "ImageTime"))]
    return {
        "frames": num(r"Total frames rendered: (\d+)"),
        "janky": num(r"Janky frames: \d+ \(([\d.]+)%\)"),
        "p50": num(r"50th percentile: (\d+)ms"),
        "p90": num(r"90th percentile: (\d+)ms"),
        "p99": num(r"99th percentile: (\d+)ms"),
        "slow_ui": num(r"Number Slow UI thread: (\d+)"),
        "slow_bitmap": num(r"Number Slow bitmap uploads: (\d+)"),
        "missed_vsync": num(r"Number Missed Vsync: (\d+)"),
        "input_latency": num(r"Number High input latency: (\d+)"),
        "slow_draw": num(r"Number Slow issue draw commands: (\d+)"),
        "over33": sum(int(n) for ms, n in re.findall(r"(\d+)ms=(\d+)", out.split("HISTOGRAM:")[1].splitlines()[0]) if int(ms) > 33) if "HISTOGRAM:" in out else "?",
        "images": len(times),
        "img_med": int(statistics.median(times)) if times else 0,
        "img_p90": int(sorted(times)[int(len(times) * 0.9)]) if times else 0,
    }


def hold_down(seconds):
    """The test build plays a held button: a press, a repeat every 50 ms, the release (see XtreamApp)."""
    r.adb("shell", "am", "broadcast", "-a", "com.osama.xtreamtv.debug.HOLD", "--ei", "key", "20", "--ei", "ms", str(int(seconds * 1000)))
    time.sleep(seconds + 1.5)


def measure(kind, where, record=False):
    gfx_reset()
    rec = None
    if record:
        rec = subprocess.Popen(r.ADB + ["shell", "screenrecord", "--time-limit", "12", f"/sdcard/{kind}-{where}.mp4"])
        time.sleep(0.5)
    hold_down(10)
    result = gfx_read()
    if rec:
        rec.wait()
        r.adb("pull", f"/sdcard/{kind}-{where}.mp4", os.path.join(r.WORK, f"{kind}-{where}.mp4"))
        r.adb("shell", "rm", f"/sdcard/{kind}-{where}.mp4")
    rows.append((kind, where, result))
    print(f"{kind:7} {where:10} " + "  ".join(f"{k}={v}" for k, v in result.items()), flush=True)


def main():
    server = mock_server.serve()
    threading.Thread(target=server.serve_forever, daemon=True).start()
    lists = "cache/lists/" + __import__("hashlib").md5(f"{mock_server.BASE}|{mock_server.USER}".encode()).hexdigest()
    try:
        r.install()
        r.adb("reverse", f"tcp:{mock_server.PORT}", f"tcp:{mock_server.PORT}")
        r.adb("shell", "am", "force-stop", r.PKG)
        r.app_shell(f"if [ -f {r.BACKUP} ]; then cp {r.BACKUP} {r.PREFS}; rm {r.BACKUP}; fi\n")
        r.add_test_playlist()
        c.use_stream("file")
        r.app_shell(f"rm -rf {lists} cache/image_cache\n")
        import json
        r.set_prefs({**r.GLASS, "glass_scene": 3, "home_style": 2, "browse_style": 0, "diag_on": "false", **json.loads(os.environ.get("PERF_PREFS", "{}"))})
        only = os.environ.get("PERF_ONLY", "")
        for kind, keys in (("movies", [c.RT]), ("series", [c.RT, c.RT])):
            if only and not only.startswith(kind):
                continue
            c.start()
            c.press(*keys)
            c.press(c.OK)
            r.wait_activity("BrowseActivity", 30)
            time.sleep(8)
            c.press(c.D, c.D)
            time.sleep(2)
            if not only.endswith("posters"):
                measure(kind, "categories")
            c.press(c.U, c.U, c.U, c.U)        # Back near the top, onto a big category.
            time.sleep(3)
            c.press(c.RT)
            time.sleep(3)
            measure(kind, "posters", record=RECORD and kind == "movies")
        r.adb("shell", "am", "force-stop", r.PKG)
    finally:
        r.app_shell(f"rm -f shared_prefs/playlist_{r.TEST_ID}.xml\nrm -rf {lists}\n")
        r.restore()
        r.adb("reverse", "--remove", f"tcp:{mock_server.PORT}")
        server.shutdown()
    print("\n| Screen | Hold Down in | Frames | Janky | 50% / 90% / 99% frame | Slow UI thread | Pictures | Picture time (median / 90%) |")
    print("|---|---|---|---|---|---|---|---|")
    for kind, where, x in rows:
        print(f"| {kind} | {where} | {x['frames']} | {x['janky']}% | {x['p50']} / {x['p90']} / {x['p99']} ms | {x['slow_ui']} | {x['images']} | {x['img_med']} / {x['img_p90']} ms |")


if __name__ == "__main__":
    main()
