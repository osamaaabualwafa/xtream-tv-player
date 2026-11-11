#!/usr/bin/env python3
"""Walks the main screens at the biggest sizes and lists text the debug build reports as cut off
("SizeClip"), plus highlights being cut ("FocusClip"). About 2 minutes.

  python scripts/uitest/size_check.py                  every step
  python scripts/uitest/size_check.py avatar settings  only these steps
"""
import hashlib
import os
import re
import subprocess
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(__file__))
os.environ["MOCK_BIG"] = "1"  # Real-size lists: "21,790 movies" needs more room than "280 movies".
import mock_server
import run as r

L, RT, U, D, OK, BACK = "DPAD_LEFT", "DPAD_RIGHT", "DPAD_UP", "DPAD_DOWN", "DPAD_CENTER", "BACK"
found = {}
# Only the test playlist's saved lists, so other test runs get their small ones back.
TEST_LISTS = "cache/lists/" + hashlib.md5(f"{mock_server.BASE}|{mock_server.USER}".encode()).hexdigest()


def press(*keys):
    for k in keys:
        r.key(k)
        time.sleep(0.65)


def collect(where):
    time.sleep(0.8)
    for line in r.adb("logcat", "-d", "-v", "tag").splitlines():
        m = re.search(r"(SizeClip|FocusClip)\s*:\s*(.*)", line)
        if m:
            found.setdefault(m.group(2)[:90], set()).add(where)
        if f"Process: {r.PKG}" in line or f"ANR in {r.PKG}" in line:
            found.setdefault("CRASH " + line[-70:], set()).add(where)
    r.adb("logcat", "-c")


def home(**prefs):
    r.adb("shell", "am", "force-stop", r.PKG)
    r.set_prefs(prefs)
    r.adb("shell", "am", "start", "-n", f"{r.PKG}/.PlaylistsActivity")
    r.wait_activity("HomeActivity")
    time.sleep(1.2)


def reach(activity):
    """A walk that never got to the screen must not pass as "no problems"."""
    if not r.wait_activity(activity):
        found.setdefault(f"never reached {activity}", set()).add("walk")


def home_layouts(name):
    for style, label in ((0, "Hero"), (1, "Side"), (3, "Tabs"), (4, "Dock")):
        home(home_style=style)
        press(RT, D, D)
        collect(f"{name} Home {label}")


def settings(name):
    open_settings()                   # the old way in (Side layout: Up, Right, OK) no longer reached Settings
    press(RT, D, D, L)                # Home layout
    for _ in range(5):                # Movies, Ratings, Player, Look, Playlists
        press(D, RT, D, D, L)
    press(D, D)
    collect(f"{name} Settings")


def open_settings():
    """Home (Tabs layout): up to the top bar, along it to the gear, OK."""
    home(home_style=3)
    press(U, U, U, U, U, RT, RT, RT, RT, OK)
    reach("SettingsActivity")


def ratings(name):
    """Settings > Ratings to the bottom (Your keys, Whole playlist, Library data), then How it works."""
    open_settings()
    press(D, D, RT)                   # Ratings, into the first card
    for _ in range(4):                # down the cards, a few rows at a time
        press(D, D, D, D)
        collect(f"{name} Settings Ratings cards")
    press(RT, RT, RT, L, L, L)        # along the Library data buttons
    collect(f"{name} Settings Ratings cards")
    open_settings()
    press(D, D, RT, D, D, D, OK)      # How it works (after the two key buttons and Refresh)
    collect(f"{name} Settings How it works")
    press(OK)                         # its second page
    collect(f"{name} Settings How it works, page 2")
    press(BACK)


def avatar(name):
    home(home_style=2)
    press(U, RT, OK)
    reach("SettingsActivity")
    time.sleep(1)
    press(D, D, D, D, RT, D, OK)
    time.sleep(1.5)
    press(RT, RT, D, U)
    collect(f"{name} Avatar window")
    press(BACK)


def movies(name):
    home(home_style=2, browse_style=0)
    press(RT, OK)
    reach("BrowseActivity")
    time.sleep(1.5)
    press(D, D, RT, D, U, U, RT)
    collect(f"{name} Movies")


def series(name):
    home(home_style=2, browse_style=0)
    press(RT, RT, OK)
    reach("BrowseActivity")
    time.sleep(1.5)
    press(D, D)
    time.sleep(1.5)                   # The list reloads for the new category.
    press(RT, OK)
    reach("DetailsActivity")
    time.sleep(1.5)
    press(D, D, RT)
    collect(f"{name} Series page")


STEPS = {"home": home_layouts, "settings": settings, "ratings": ratings, "avatar": avatar, "movies": movies, "series": series}


def main():
    started = time.time()
    server = mock_server.serve()
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        r.install()
        r.adb("reverse", f"tcp:{mock_server.PORT}", f"tcp:{mock_server.PORT}")
        r.adb("shell", "am", "force-stop", r.PKG)
        r.app_shell(f"if [ -f {r.BACKUP} ]; then cp {r.BACKUP} {r.PREFS}; rm {r.BACKUP}; fi\n")
        r.add_test_playlist()
        r.app_shell(f"rm -f shared_prefs/playlist_{r.TEST_ID}.xml\n")
        r.app_shell(f"rm -rf {TEST_LISTS}" + chr(10))
        r.adb("logcat", "-c")
        # Large + Extra large has the least room; Normal + Extra large has the biggest letters for their boxes.
        biggest = {"focus_style": 0, "ui_size": 2, "text_size": 3}
        tightest = {"focus_style": 0, "ui_size": 1, "text_size": 3}
        r.set_prefs({**r.GLASS, **biggest})
        for word, step in STEPS.items():
            if len(sys.argv) < 2 or word in sys.argv[1:]:
                step("Glass")
        # The other themes share these screens; only Settings differs much between them.
        if len(sys.argv) < 2 or "settings" in sys.argv[1:]:
            r.set_prefs({**r.GLASS, **tightest})
            settings("Glass tightest")
            for name, theme in (("Default", r.DEFAULT),):
                r.set_prefs({**theme, **biggest})
                settings(name)
                r.set_prefs({**theme, **tightest})
                settings(name + " tightest")
    finally:
        r.app_shell(f"rm -rf {TEST_LISTS}" + chr(10))
        r.restore()
        r.adb("reverse", "--remove", f"tcp:{mock_server.PORT}")
        server.shutdown()
    print(f"{len(found)} problems in {(time.time() - started) / 60:.1f} min")
    for k, v in sorted(found.items()):
        print(f"  {k}  [{', '.join(sorted(v))}]")
    sys.exit(1 if found else 0)


if __name__ == "__main__":
    main()
