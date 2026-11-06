#!/usr/bin/env python3
"""Checks where the highlight lands for the focus rules in FocusMap.kt (review items 1.1C to 1.1Z).
Presses remote keys and reads the focused view. About 3 minutes:

  python scripts/uitest/focus_checks.py            all scenarios, Default and Glass
  python scripts/uitest/focus_checks.py settings   only scenarios with this word in their name
"""
import os
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(__file__))
import mock_server
import run as r

L, RT, U, D, OK = "DPAD_LEFT", "DPAD_RIGHT", "DPAD_UP", "DPAD_DOWN", "DPAD_CENTER"


def focus():
    """(id chain from the focused view upward, index among its siblings, its selected state)."""
    for _ in range(3):
        xml = r.adb("exec-out", "uiautomator", "dump", "/dev/tty").split("UI hierchary")[0].strip()
        try:
            root = ET.fromstring(xml[xml.index("<?xml"):] if "<?xml" in xml else xml)
        except (ET.ParseError, ValueError):
            time.sleep(0.5)
            continue
        def walk(n, chain):
            for i, c in enumerate(list(n)):
                ids = chain + [c.get("resource-id", "").split("/")[-1]]
                if c.get("focused") == "true":
                    return [x for x in reversed(ids) if x], i, c.get("text", "")
                found = walk(c, ids)
                if found:
                    return found
            return None
        return walk(root, []) or ([], -1, "")
    return [], -1, ""


def press(*keys):
    for k in keys:
        r.key(k)
        time.sleep(0.45)
    time.sleep(0.35)


results = []


def expect(name, want, index=None):
    ids, at, text = focus()
    ok = any(i.startswith(want) for i in ids) and (index is None or at == index)
    results.append((ok, name, want, ids[:2], at))
    print(("PASS " if ok else "FAIL ") + f"{name}: want {want}{'' if index is None else ' #%d' % index}, got {ids[:2]} #{at} {text[:20]!r}", flush=True)


def home(**prefs):
    r.adb("shell", "am", "force-stop", r.PKG)
    r.set_prefs(prefs)
    r.adb("shell", "am", "start", "-n", f"{r.PKG}/.PlaylistsActivity")
    r.wait_activity("HomeActivity")
    r.settle()


def seed_continue_watching():
    """Plays a movie briefly, so Home has a hero banner and a Continue watching row."""
    home(home_style=2, browse_style=0)
    press(RT, OK)
    r.wait_activity("BrowseActivity")
    r.settle()
    press(D, D, RT, OK)
    r.wait_activity("DetailsActivity")
    r.settle()
    press(OK)
    r.wait_activity("PlayerActivity")
    time.sleep(12)
    press("BACK")
    time.sleep(1)


def settings_open():
    press(U, RT, OK)
    r.wait_activity("SettingsActivity")
    r.settle()


def h1_hero(c):
    home(home_style=0)
    expect(f"{c} 1.1U H1 start", "tile_live")
    press(U)
    expect(f"{c} 1.1U Up from a section reaches the banner", "hero_resume")
    press(U)
    expect(f"{c} 1.1U second Up reaches playlist selection", "playlist_chip")
    press(RT, RT)
    expect(f"{c} 1.1U Right from Settings stays", "settings_button")
    press(D, D)
    expect(f"{c} 1.1U Down from the top passes the banner to the sections", "tile_")
    press(L, L, L, L)
    expect(f"{c} 1.1U Left stops at the row start", "tile_live")
    press(L)
    expect(f"{c} 1.1U Left from Live TV stays", "tile_live")
    press(RT, RT, RT, RT)
    expect(f"{c} 1.1U Right from Search stays", "tile_search")


def h2_side(c):
    home(home_style=1)
    press(D, D)
    expect(f"{c} 1.1S H2 menu", "tile_series")
    press(RT)
    expect(f"{c} 1.1S H2 Right enters a row", "row_continue")
    press(RT)
    press(L)
    press(L)
    expect(f"{c} 1.1S H2 Left returns to the same menu item", "tile_series")
    press(U, U, U, U)
    expect(f"{c} 1.1S H2 Up stops at the top of the menu", "tile_live")


def h3_h4(c):
    for style, name in ((2, "H3"), (3, "H4")):
        home(home_style=style)
        press(L)
        expect(f"{c} {name} Left from Live TV stays", "tile_live")
        press(RT, RT, RT, RT)
        expect(f"{c} {name} Right from Search stays", "tile_search")
        press(U)
        expect(f"{c} {name} Up reaches the top bar", "playlist_chip")
        press(RT, RT)
        expect(f"{c} {name} Right from Settings stays", "settings_button")


def settings_edges(c):
    home(home_style=2)
    settings_open()
    press(D, D, RT)
    first = focus()
    press(U)
    now = focus()
    ok = first[:2] == now[:2]
    results.append((ok, f"{c} 1.1V Up on the first row stays", "", now[0][:2], now[1]))
    print(("PASS " if ok else "FAIL ") + f"{c} 1.1V Up on the first row stays: {first[:2]} then {now[:2]}", flush=True)
    press(*[D] * 26)
    last = focus()
    press(D)
    now = focus()
    ok = last[:2] == now[:2] and "menu_player" not in now[0]
    results.append((ok, f"{c} 1.1V Down on the last row stays", "", now[0][:2], now[1]))
    print(("PASS " if ok else "FAIL ") + f"{c} 1.1V Down on the last row stays: {last[:2]} then {now[:2]}", flush=True)
    press(L)
    expect(f"{c} 1.1V Left returns to the open section", "menu_player")


def colours(c):
    if c != "default":
        return
    for accent in (0, 3, 7):
        home(home_style=2, accent=accent)
        settings_open()
        press(D, D, D, RT)
        press(D)
        expect(f"1.1X lands on the chosen colour {accent}", "section_body", index=accent)


def browse_search(c):
    home(home_style=2, browse_style=0)
    press(RT, OK)
    r.wait_activity("BrowseActivity")
    r.settle()
    press(D, D)
    expect(f"{c} category", "categories")
    press(RT)
    expect(f"{c} Right enters the items", "items")
    press(U)
    expect(f"{c} 1.1C Up from the first row reaches Search", "search")
    press(L)
    expect(f"{c} 1.1C Left from Search reaches the open category", "categories")
    press(RT, U, L)
    expect(f"{c} 1.1C Left again, same category", "categories")


def avatar(c):
    if c != "default":
        return
    home(home_style=2)
    settings_open()
    press(D, D, D, D, RT, D, OK)  # Past "Open at start" (the test adds a second playlist) to a playlist.
    time.sleep(1)
    expect("1.1Z avatar picker opens on a group", "avatar_groups")
    press(RT)
    group = focus()[1]
    press(D)
    expect("1.1Z Down reaches the first emoji choice", "avatar_emojis")
    press(U)
    expect("1.1Z Up returns to the group it came from", "avatar_groups", index=group)
    w, h, pic = r.settle()
    r.write_png(os.path.join(r.WORK, "avatar.png"), w, h, pic)
    press("BACK")
    home(home_style=2, ui_size=2, text_size=3)
    settings_open()
    press(D, D, D, D, RT, D, OK)
    time.sleep(3)
    w, h, pic = r.settle()
    r.write_png(os.path.join(r.WORK, "avatar-large.png"), w, h, pic)
    press("BACK")
    r.set_prefs({"ui_size": 0, "text_size": 1})


SCENARIOS = [h1_hero, h2_side, h3_h4, settings_edges, colours, browse_search, avatar]


def main():
    word = sys.argv[1] if len(sys.argv) > 1 else ""
    r.adb("logcat", "-c")
    server = mock_server.serve()
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        r.install()
        r.adb("reverse", f"tcp:{mock_server.PORT}", f"tcp:{mock_server.PORT}")
        r.adb("shell", "am", "force-stop", r.PKG)
        r.app_shell(f"if [ -f {r.BACKUP} ]; then cp {r.BACKUP} {r.PREFS}; rm {r.BACKUP}; fi\n")
        r.add_test_playlist()
        r.app_shell(f"rm -f shared_prefs/playlist_{r.TEST_ID}.xml\n")
        for c, prefs in (("default", r.DEFAULT), ("glass", {**r.GLASS, "focus_style": 0})):
            r.adb("shell", "am", "force-stop", r.PKG)
            r.set_prefs(prefs)
            if c == "default":
                seed_continue_watching()
            for s in SCENARIOS:
                if word in s.__name__:
                    s(c)
    finally:
        r.restore()
        r.adb("reverse", "--remove", f"tcp:{mock_server.PORT}")
        server.shutdown()
    failed = [x for x in results if not x[0]]
    print(f"\n{len(results) - len(failed)} of {len(results)} passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
