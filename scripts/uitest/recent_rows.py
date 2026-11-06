#!/usr/bin/env python3
"""Review item 1.1T(a): Home's "Recently added" switches (Settings > Home layout).

  python scripts/uitest/recent_rows.py            all 33 cases
  python scripts/uitest/recent_rows.py --short    one case per layout and the Settings switch (for test.py push)

Every combination (both, movies only, series only, none) in Home layouts H1-H4, after opening the
app and again after restarting it; then one switch flipped in Settings and Home checked straight away.
"""
import os
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(__file__))
import mock_server
import run as r
import player_checks as c

MOVIES, SERIES = "Recently added movies", "Recently added series"
LAYOUTS = ["H1 Hero", "H2 Side menu", "H3 Big tiles", "H4 Top tabs"]
SHORT = "--short" in sys.argv
COMBOS = [(0, 1)] if SHORT else [(1, 1), (1, 0), (0, 1), (0, 0)]
results = []


def focused_text():
    for n in c.dump().iter():
        if n.get("focused") == "true":
            return " | ".join(x.get("text", "") for x in n.iter() if x.get("text"))
    return ""


def lists_loaded(timeout=40):
    """Home's tile counts appear once the lists are in (the first start downloads them)."""
    end = time.time() + timeout
    while time.time() < end:
        if any(t.endswith(" movies") and t[0].isdigit() for t in c.texts()):
            return True
        time.sleep(1)
    return False


def rows_on_home():
    """Titles seen on Home, moving down through it (rows below the screen come into view)."""
    lists_loaded()
    time.sleep(1.5)
    seen = set(c.texts())
    for _ in range(7):
        c.press(c.D, gap=0.5)
        seen |= set(c.texts())
    return MOVIES in seen, SERIES in seen


def check(label, want, got):
    ok = want == got
    results.append(ok)
    print(f"{'PASS' if ok else 'FAIL'}  {label}: want movies={want[0]} series={want[1]}, got movies={got[0]} series={got[1]}", flush=True)


def main():
    server = mock_server.serve()
    threading.Thread(target=server.serve_forever, daemon=True).start()
    lists = "cache/lists/" + __import__("hashlib").md5(f"{mock_server.BASE}|{mock_server.USER}".encode()).hexdigest()
    try:
        r.install()
        r.adb("reverse", f"tcp:{mock_server.PORT}", f"tcp:{mock_server.PORT}")
        r.adb("shell", "am", "force-stop", r.PKG)
        r.add_test_playlist()
        r.only_test_playlist()
        r.app_shell(f"rm -rf {lists}\n")
        for style, layout in enumerate(LAYOUTS):
            for m, s in COMBOS:
                prefs = {"theme": 0, "home_style": style, "home_recent_movies": m, "home_recent_series": s, "diag_on": "false"}
                c.start(**prefs)
                time.sleep(3)
                check(f"{layout}, movies {'on' if m else 'off'}, series {'on' if s else 'off'}", (bool(m), bool(s)), rows_on_home())
                if SHORT:
                    continue
                c.start(**prefs)      # Restart: the choice is remembered.
                time.sleep(3)
                check(f"{layout}, same after restarting", (bool(m), bool(s)), rows_on_home())
        # Straight away: Home open, Settings > Home layout, switch series off, back to Home.
        c.start(theme=0, home_style=2, home_recent_movies=1, home_recent_series=1, diag_on="false")
        time.sleep(3)
        c.until_focus("playlist_chip", c.U, tries=4)
        c.until_focus("settings_button", c.RT, tries=4)
        c.press(c.OK)
        r.wait_activity("SettingsActivity", 10)
        time.sleep(2)
        c.press(c.RT)     # Into the Home layout section (first).
        time.sleep(1)
        for _ in range(12):
            if "Most recent series" in focused_text():
                break
            c.press(c.D, gap=0.5)
        c.press(c.OK)
        time.sleep(1)
        c.press(c.BACK)
        r.wait_activity("HomeActivity", 10)
        time.sleep(3)
        check("H3, series switched off in Settings, back on Home straight away", (True, False), rows_on_home())
    finally:
        r.app_shell(f"rm -f shared_prefs/playlist_{r.TEST_ID}.xml\nrm -rf {lists}\n")
        r.restore()
        r.adb("reverse", "--remove", f"tcp:{mock_server.PORT}")
        server.shutdown()
    print(f"\n{sum(results)} of {len(results)} passed")


if __name__ == "__main__":
    main()
