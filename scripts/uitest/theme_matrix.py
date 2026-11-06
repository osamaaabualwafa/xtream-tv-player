#!/usr/bin/env python3
"""Review item 1.1R: screenshots of the main themes on the playlist screen, Home, Settings and the
player controls, each with something highlighted (.uitest/matrix/<theme>-<screen>.png).

  python scripts/uitest/theme_matrix.py            all themes below
  MATRIX_ONLY=glass_midnight python scripts/uitest/theme_matrix.py
"""
import os
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(__file__))
import mock_server
import run as r
import player_checks as c

THEMES = {
    "default_blue": {"theme": 0, "palette": 0, "accent": 0},
    "default_pink": {"theme": 0, "palette": 0, "accent": 4},
    "default_coastal": {"theme": 0, "palette": 1, "accent": 0},
    "default_coconut": {"theme": 0, "palette": 2, "accent": 0},
    "cinema": {"theme": 1},
    "glass_midnight": {**r.GLASS, "glass_scene": 3},
    "glass_aurora_frosted_lift": {**r.GLASS, "glass_scene": 0, "glass_style": 1, "focus_style": 3},
}
OUT = os.path.join(r.WORK, "matrix")


def in_app(activity):
    """Only press keys while the app is in front (a stray press can land in the TV's own menus)."""
    return f"{r.PKG}/.{activity}" in r.adb("shell", "dumpsys", "activity", "activities").split("topResumedActivity", 1)[-1][:300]


def shot(name):
    c.shot(os.path.join("matrix", name))


def main():
    os.makedirs(OUT, exist_ok=True)
    server = mock_server.serve()
    threading.Thread(target=server.serve_forever, daemon=True).start()
    lists = "cache/lists/" + __import__("hashlib").md5(f"{mock_server.BASE}|{mock_server.USER}".encode()).hexdigest()
    only = os.environ.get("MATRIX_ONLY", "")
    try:
        r.install()
        r.adb("reverse", f"tcp:{mock_server.PORT}", f"tcp:{mock_server.PORT}")
        r.adb("shell", "am", "force-stop", r.PKG)
        r.add_test_playlist()
        r.only_test_playlist()
        c.use_stream("file")
        for name, prefs in THEMES.items():
            if only and only != name:
                continue
            prefs = {**prefs, "diag_on": "false"}
            # Home: a tile, then a row of posters.
            c.start(**prefs)
            time.sleep(4)
            shot(f"{name}-2-home")
            c.press(c.D, c.D)
            time.sleep(1.5)
            shot(f"{name}-3-home-row")
            # Playlists (the top bar's playlist button): the test playlist highlighted.
            if in_app("HomeActivity") and c.until_focus("playlist_chip", c.U):
                c.press(c.OK)
                r.wait_activity("PlaylistsActivity", 10)
                time.sleep(2)
                if in_app("PlaylistsActivity"):
                    shot(f"{name}-1-playlists")
            # Settings (the top bar's Settings button), from a fresh Home: a row and its choices.
            c.start(**prefs)
            time.sleep(2)
            if in_app("HomeActivity") and c.until_focus("playlist_chip", c.U, tries=4) and c.until_focus("settings_button", c.RT, tries=4):
                c.press(c.OK)
                r.wait_activity("SettingsActivity", 10)
                time.sleep(2)
                if in_app("SettingsActivity"):
                    c.press(c.D, c.RT)
                    time.sleep(1.2)
                    shot(f"{name}-4-settings")
            # Player: the controls shown.
            c.play_movie(**prefs)
            c.press(c.OK)    # Paused: the controls stay up.
            time.sleep(1.2)
            shot(f"{name}-5-player")
            r.adb("shell", "am", "force-stop", r.PKG)
            print(name, "done", flush=True)
    finally:
        r.app_shell(f"rm -f shared_prefs/playlist_{r.TEST_ID}.xml\nrm -rf {lists}\n")
        r.restore()
        r.adb("reverse", "--remove", f"tcp:{mock_server.PORT}")
        server.shutdown()


if __name__ == "__main__":
    main()
