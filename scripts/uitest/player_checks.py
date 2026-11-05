#!/usr/bin/env python3
"""Checks for the playback items in the review file (1.1I, 1.1J, 1.1M, 1.2A, 1.2B, 1.1P).

  python scripts/uitest/player_checks.py              everything, about 8 minutes
  python scripts/uitest/player_checks.py ok resume    only these: leave ok resume screensaver tracks picture login

Pictures go to .uitest/ (tracks, subtitles, picture sizes, login fields).
"""
import json
import os
import re
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET
from html import unescape

sys.path.insert(0, os.path.dirname(__file__))
import mock_server
import run as r

L, RT, U, D, OK, BACK, HOME = "DPAD_LEFT", "DPAD_RIGHT", "DPAD_UP", "DPAD_DOWN", "DPAD_CENTER", "BACK", "HOME"
results = []


def check(name, ok, detail=""):
    results.append((ok, name))
    print(("PASS " if ok else "FAIL ") + name + (f"  [{detail}]" if detail else ""), flush=True)


def press(*keys, gap=0.6):
    for k in keys:
        r.key(k)
        time.sleep(gap)


def top():
    text = r.adb("shell", "dumpsys", "activity", "activities").split("topResumedActivity", 1)[-1][:160]
    m = re.search(r"/\.?(\w+)", text)
    return m.group(1) if m else "?"


def launcher():
    r.adb("shell", "am", "start", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LEANBACK_LAUNCHER",
          "-f", "0x10200000", "-n", f"{r.PKG}/.PlaylistsActivity")
    time.sleep(3)


def use_stream(kind):
    """"multi": several audio and subtitle tracks. "plain": one audio track, no subtitles. "file": a short video file."""
    os.environ["MOCK_TRACKS"] = "" if kind == "file" else kind
    r.adb("shell", "am", "force-stop", r.PKG)
    value = "false" if kind == "file" else "true"
    r.app_shell(f"rm -f shared_prefs/playlist_{r.TEST_ID}.xml\n"
                f"echo '<map><boolean name=\"vod_hls\" value=\"{value}\" /></map>' > shared_prefs/playlist_{r.TEST_ID}.xml\n")


def set_language(name, value):
    r.app_shell(f"sed -i 's|<string name=\"{name}\">[^<]*</string>||' {r.PREFS}\n"
                + (f"sed -i 's|<map>|<map><string name=\"{name}\">{value}</string>|' {r.PREFS}\n" if value else ""))


def start(how="launcher", **prefs):
    r.adb("shell", "am", "force-stop", r.PKG)
    r.set_prefs({"home_style": 2, "browse_style": 0, **prefs})
    if how == "launcher":
        launcher()
    else:
        r.adb("shell", "am", "start", "-a", "android.intent.action.MAIN", "-f", "0x10000000", "-n", f"{r.PKG}/.PlaylistsActivity")
        time.sleep(3)
    r.wait_activity("HomeActivity")
    time.sleep(1.2)


def details(kind):
    press(*{"movie": [RT], "series": [RT, RT]}[kind])
    press(OK)
    r.wait_activity("BrowseActivity")
    time.sleep(1.5)
    press(D, D)
    time.sleep(1.2)
    press(RT, OK)
    return r.wait_activity("DetailsActivity")


def play_movie(**prefs):
    start(**prefs)
    details("movie")
    time.sleep(1)
    press(OK)
    r.wait_activity("PlayerActivity")
    time.sleep(6)


def dump():
    for _ in range(3):
        xml = r.adb("exec-out", "uiautomator", "dump", "/dev/tty").split("UI hierchary")[0].strip()
        try:
            return ET.fromstring(xml[xml.index("<?xml"):])
        except (ET.ParseError, ValueError):
            time.sleep(0.5)
    return ET.fromstring("<a/>")


def texts():
    return [n.get("text", "") for n in dump().iter() if n.get("text")]


def bounds(res_id):
    for n in dump().iter():
        if n.get("resource-id", "").endswith("/" + res_id):
            return [int(x) for x in re.findall(r"\d+", n.get("bounds"))]
    return None


def focused_ids():
    """Ids of the focused view and the views around it."""
    def walk(n, chain):
        for c in list(n):
            ids = chain + [c.get("resource-id", "").split("/")[-1]]
            if c.get("focused") == "true":
                return ids
            found = walk(c, ids)
            if found:
                return found
        return None
    return walk(dump(), []) or []


def until_focus(view_id, key, tries=12):
    for _ in range(tries):
        if view_id in focused_ids():
            return True
        press(key)
    return view_id in focused_ids()


def shot(name):
    w, h, pic = r.settle()
    r.write_png(os.path.join(r.WORK, name + ".png"), w, h, pic)
    return pic


def is_playing():
    out = r.adb("shell", "dumpsys", "media_session")
    states = re.findall(r"state=PlaybackState \{state=(\w+)", out)
    return bool(states) and states[0] in ("PLAYING", "3")


def continue_list():
    xml = r.adb("exec-out", "run-as", r.PKG, "cat", f"shared_prefs/playlist_{r.TEST_ID}.xml")
    m = re.search(r'name="continue">(.*?)</string>', xml, re.S)
    return json.loads(unescape(m.group(1))) if m else []


def ime_shown():
    return "mInputShown=true" in r.adb("shell", "dumpsys", "input_method")


# 1.1M: leave with the TV Home button, come back, press Back.
def leave():
    use_stream("file")
    for how in ("launcher", "installer"):
        for kind in ("series", "movie"):
            for killed in (False, True):
                start(how)
                details(kind)
                press(HOME)
                time.sleep(1)
                if killed:
                    r.adb("shell", "am", "kill", r.PKG)
                    time.sleep(1)
                launcher()
                back_on = top()
                press(BACK)
                time.sleep(1.5)
                after = top()
                check(f"1.1M {kind}, first opened by {how}, app closed by Android={killed}",
                      back_on == "DetailsActivity" and after == "BrowseActivity", f"return {back_on}, Back {after}")


# 1.1J: every OK plays or pauses at once, and nothing queues up.
def ok():
    use_stream("multi")
    play_movie()
    r.adb("logcat", "-c")
    playing = is_playing()
    for _ in range(20):
        press(OK, gap=1.0)
        playing = not playing
    check("1.1J 20 presses, one a second: state matches", is_playing() == playing)
    for _ in range(10):
        press(OK, gap=0.05)
        playing = not playing
    time.sleep(1.5)
    check("1.1J 10 fast presses: state matches", is_playing() == playing)
    delays = [int(x) for x in re.findall(r"ok-to-state (\d+) ms", r.adb("logcat", "-d", "-s", "PlayerOk"))]
    check("1.1J answer within 300 ms", bool(delays) and max(delays) <= 300, f"{len(delays)} measured, slowest {max(delays or [0])} ms")
    press(BACK)


# 1.2A: only real playback changes Resume Watching.
def resume():
    use_stream("multi")
    start()
    details("series")
    time.sleep(1)
    until_focus("episodes", D, tries=6)
    press(D, OK)
    reached = r.wait_activity("PlayerActivity")
    time.sleep(40)
    press(BACK)
    time.sleep(2)
    entries = continue_list()
    first = entries[0] if entries else {}
    pos = first.get("position", 0)
    check("1.2A 40 s of playback is saved", reached and 25_000 <= pos <= 55_000, f"{first.get('key')} at {pos} ms")
    press(D, OK)        # The next episode in the list: opened and left soon.
    reached = r.wait_activity("PlayerActivity")
    time.sleep(10)
    press(BACK)
    time.sleep(2)
    r.adb("shell", "am", "force-stop", r.PKG)
    after = continue_list()
    newest = after[0] if after else {}
    same = reached and newest.get("key") == first.get("key") and newest.get("position") == pos
    check("1.2A opening another episode for 10 s changes nothing", same, f"{newest.get('key')} at {newest.get('position')} ms")


# 1.2B: OK on the screensaver plays in one press; other keys as before.
def screensaver():
    use_stream("multi")
    play_movie()
    press(OK)           # Pause.
    time.sleep(64)
    shot("screensaver")
    was_paused = not is_playing()
    press(OK)
    time.sleep(1.5)
    check("1.2B OK plays in one press", was_paused and is_playing())
    press(OK)           # Pause again.
    time.sleep(64)
    press(D)
    time.sleep(1.5)
    check("1.2B Down only takes the screensaver away", not is_playing() and top() == "PlayerActivity")
    r.adb("shell", "am", "force-stop", r.PKG)


# 1.1I: audio and subtitles from the toolbar and from Settings.
def open_tracks():
    """Reading the screen is slow while video plays, so this pauses and uses fixed key presses."""
    time.sleep(6)       # The controls have hidden.
    press(OK)           # Pauses, and shows the controls with focus on Play/Pause.
    press(RT, RT, OK)   # Forward, Audio and subtitles.
    time.sleep(1)


def choose(*keys):
    press(*keys)
    r.key("MEDIA_PLAY")
    time.sleep(6)


def tracks():
    use_stream("multi")
    set_language("sub_lang", None)
    set_language("audio_lang", None)
    play_movie()
    open_tracks()
    listed = texts()
    shot("tracks-list")
    joined = " | ".join(t for t in listed if ":" in t)
    check("1.1I toolbar lists the audio tracks", sum("Audio:" in t for t in listed) >= 2, joined[:190])
    check("1.1I toolbar lists Off and two subtitles", sum("Subtitles:" in t for t in listed) == 3)
    choose(D, OK)       # Second audio track.
    open_tracks()
    audio = [t for t in texts() if "Audio:" in t]
    check("1.1I audio switches", len(audio) >= 2 and "●" in audio[1] and "●" not in audio[0], " | ".join(audio))
    choose(*[D] * (len(audio) + 1), OK)  # First subtitle, after the audio tracks and Off.
    shot("subtitle-english")
    open_tracks()
    check("1.1I subtitle switches", any("●" in t and "Subtitles:" in t and "English" in t for t in texts()))
    press(BACK)
    r.adb("shell", "am", "force-stop", r.PKG)
    # Settings values apply on the next playback.
    set_language("sub_lang", "ar")
    set_language("audio_lang", "en")
    play_movie()
    time.sleep(3)
    shot("subtitle-arabic-from-settings")
    open_tracks()
    listed = texts()
    check("1.1I Settings choices apply next time", any("●" in t and "Subtitles:" in t and "Arabic" in t for t in listed)
          and any("●" in t and "Audio:" in t for t in listed), " | ".join(t for t in listed if ":" in t)[:150])
    press(BACK)
    r.adb("shell", "am", "force-stop", r.PKG)
    set_language("sub_lang", None)
    set_language("audio_lang", None)
    # A stream with no alternatives.
    use_stream("plain")
    play_movie()
    open_tracks()
    listed = texts()
    shot("tracks-none")
    check("1.1I no subtitles says Not available", any("Subtitles:" in t and "Not available" in t for t in listed), " | ".join(t for t in listed if ":" in t)[:150])
    press(BACK)
    r.adb("shell", "am", "force-stop", r.PKG)


def picture():
    use_stream("multi")
    pictures = {}
    for i, name in enumerate(("fit", "fill", "stretch", "43", "zoom")):
        play_movie(picture_size=i)
        time.sleep(1)
        pictures[name] = shot("picture-" + name)
        r.adb("shell", "am", "force-stop", r.PKG)
    r.set_prefs({"picture_size": 0})


# 1.1P: the active field stays in the top part of the screen, with or without a keyboard.
def open_add_playlist():
    start()
    press(U, OK)
    r.wait_activity("PlaylistsActivity")
    time.sleep(1.5)
    until_focus("add_tile", RT, tries=4)
    press(*[RT] * 3)
    press(OK)
    return r.wait_activity("LoginActivity", 6)


def login():
    """One theme is enough: this code is the same in all of them."""
    use_stream("file")
    h = int(re.findall(r"\d+", r.adb("shell", "wm", "size"))[-1])
    fields = ("name", "server", "username", "password")
    r.adb("shell", "am", "force-stop", r.PKG)
    r.set_prefs({**r.GLASS, "focus_style": 0})
    if not open_add_playlist():
        return check("1.1P add-playlist screen opens", False, top())
    time.sleep(1)
    rest = {f: bounds(f) for f in fields}
    for index, field in enumerate(fields):
        until_focus(field, D, tries=4)
        time.sleep(0.8)
        check(f"1.1P {field}: moving here without the keyboard moves nothing", bounds(field) == rest[field], str(bounds(field)))
        press(OK)
        time.sleep(1.5)
        keyboard = ime_shown()
        r.adb("shell", "input", "text", "abc")
        time.sleep(1.2)
        box = bounds(field)
        shot(f"login-{field}")
        above = [bounds(f) for f in fields[:index]]
        whole = all(b and b[3] - b[1] >= box[3] - box[1] - 2 and b[1] >= 0 for b in above) if box else False
        check(f"1.1P {field}: above the keyboard, with the fields above it", keyboard and bool(box) and box[3] <= h * 0.53 and whole,
              f"keyboard={keyboard}, bottom edge {box[3] if box else None} of {h}")
        press(BACK)
        time.sleep(1)
        check(f"1.1P {field}: closing the keyboard puts the card back", bounds(field) == rest[field], str(bounds(field)))
    r.adb("shell", "am", "force-stop", r.PKG)


STEPS = {"leave": leave, "ok": ok, "resume": resume, "screensaver": screensaver, "tracks": tracks, "picture": picture, "login": login}


def main():
    words = sys.argv[1:] or list(STEPS)
    server = mock_server.serve()
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        subprocess.run(r.ADB + ["install", "-r", r.APK], capture_output=True, timeout=180)
        r.adb("reverse", f"tcp:{mock_server.PORT}", f"tcp:{mock_server.PORT}")
        r.adb("shell", "am", "force-stop", r.PKG)
        r.app_shell(f"if [ -f {r.BACKUP} ]; then cp {r.BACKUP} {r.PREFS}; rm {r.BACKUP}; fi\n")
        r.add_test_playlist()
        r.set_prefs({**r.GLASS, "focus_style": 0})
        for word in words:
            STEPS[word]()
    finally:
        r.app_shell(f"rm -f shared_prefs/playlist_{r.TEST_ID}.xml\n")
        r.restore()
        r.adb("reverse", "--remove", f"tcp:{mock_server.PORT}")
        server.shutdown()
    failed = [x for x in results if not x[0]]
    print(f"\n{len(results) - len(failed)} of {len(results)} passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
