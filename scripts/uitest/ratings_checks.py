#!/usr/bin/env python3
"""Ratings (review 1.1E) on the emulator, with the fake playlist and a made-up key only.

  python scripts/uitest/ratings_checks.py

Checks: Settings > Ratings before a key (Get started), the key window and the phone page (form, a
wrong address refused, a made-up key sent from the "phone" and refused by MDBList, nothing saved),
the page with a key (two cards), scores on posters, and no provider ★ left.

The owner's own key and saved scores (if any) are put aside first and brought back unchanged at the
end. The made-up key is never a real one; MDBList simply refuses it.
"""
import json
import os
import re
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(__file__))
import mock_server
import player_checks as c
import recent_rows as rr
import run as r

PREFS = "shared_prefs/ratings.xml"
CACHE = "files/ratings-cache.json"
FAKE_KEY = "madeupkey0123456789abcdef"


def open_ratings():
    """Home > Settings > Ratings, with the highlight on the first option."""
    c.until_focus("playlist_chip", c.U, tries=4)
    c.until_focus("settings_button", c.RT, tries=4)
    c.press(c.OK)
    r.wait_activity("SettingsActivity", 10)
    time.sleep(1.5)
    for _ in range(8):
        if "Ratings" in rr.focused_text():
            break
        c.press(c.D, gap=0.5)
    time.sleep(1)


def saved_key():
    return 'name="key"' in r.app_shell(f"cat {PREFS} 2>/dev/null\n")


def main():
    s = mock_server.serve()
    threading.Thread(target=s.serve_forever, daemon=True).start()
    try:
        r.install()
        r.adb("reverse", f"tcp:{mock_server.PORT}", f"tcp:{mock_server.PORT}")
        r.add_test_playlist()
        # The owner's key and scores aside (brought back in finally). Never the made-up key, and never
        # over a copy already set aside by a run that was cut off (the emulator closed): that copy is
        # the owner's, so it stays and the test files are simply removed.
        r.app_shell(f"if grep -q {FAKE_KEY} {PREFS} 2>/dev/null; then rm -f {PREFS} {CACHE}; fi\n"
                    f"for f in {PREFS} {CACHE}; do if [ -f $f.owner ]; then rm -f $f; elif [ -f $f ]; then mv $f $f.owner; fi; done\n")

        # 1. Before a key: Get started.
        c.start(home_style=2, **r.GLASS)
        time.sleep(3)
        open_ratings()
        t = c.texts()
        c.check("Ratings without a key shows Get started with Add key", "Get started" in t and "Add key" in t)

        # 2. The key window and the phone page.
        c.press(c.RT)
        r.adb("logcat", "-c")
        c.press(c.OK)
        time.sleep(2.5)
        c.check("Add key opens the key window", any(x.startswith("Add your MDBList key") for x in c.texts()))
        m = re.search(r"page (/k/\w+)", r.adb("logcat", "-d", "-s", "RatingsKey:D"))
        c.check("The phone page starts", bool(m))
        if m:
            r.adb("forward", "tcp:18096", "tcp:8096")
            try:
                page = urllib.request.urlopen("http://127.0.0.1:18096" + m.group(1), timeout=10).read().decode()
                c.check("The phone page has the paste box and Send to TV", 'name=key' in page and "Send to TV" in page)
                try:
                    urllib.request.urlopen("http://127.0.0.1:18096/k/wrongtoken", timeout=10)
                    c.check("A wrong address is refused", False, "it answered")
                except urllib.error.HTTPError as e:
                    c.check("A wrong address is refused", e.code == 404, str(e.code))
                data = urllib.parse.urlencode({"key": FAKE_KEY}).encode()
                sent = urllib.request.urlopen("http://127.0.0.1:18096" + m.group(1), data=data, timeout=10).read().decode()
                c.check("Sending a key from the phone answers Sent", "Sent" in sent)
                time.sleep(6)
                t = " ".join(c.texts())
                refused = "didn't accept" in t
                offline = "Couldn't reach" in t
                c.check("A made-up key is refused (MDBList asked for real)", refused or offline,
                        "no internet on the emulator: MDBList not reached" if offline else "")
            finally:
                r.adb("forward", "--remove", "tcp:18096")
        c.check("A refused key is not saved", not saved_key())
        c.press(c.BACK)
        time.sleep(1)

        # 3. With a key: the two cards. Scores for every fake movie, saved today.
        r.adb("shell", "am", "force-stop", r.PKG)
        now = int(time.time() * 1000)
        cache = {f"{r.TEST_ID}:MOVIE:{500 + i}": {"i": "7.8", "t": 92, "a": now, "h": "ID", "r": f"tmdb/movie/{i + 1}", "v": "", "y": 2020}
                 for i in range(280)}
        r.app_shell(
            f"""echo '<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map><string name="key">{FAKE_KEY}</string><int name="u_used" value="12" /><int name="u_limit" value="1000" /></map>' > {PREFS}
echo '{json.dumps(cache)}' > {CACHE}
""")
        c.start(home_style=2, **r.GLASS)
        time.sleep(3)
        open_ratings()
        t = " | ".join(c.texts())
        c.check("With a key: the key card (Add or change key, Remove key)", all(x in t for x in ("Your MDBList key", "Add or change key", "Remove key")))
        missing = [x for x in ("Show ratings", "Show on posters", "Find by name", "Today", "Check matching") if x not in t]
        c.check("With a key: the Ratings card (switches, Today, Check matching)", not missing, "missing: " + ", ".join(missing) if missing else "")

        # 4. Posters: real scores, and no provider ★ (from a fresh Home).
        c.start(home_style=2, **r.GLASS)
        time.sleep(3)
        c.press(c.RT, c.OK)
        r.wait_activity("BrowseActivity", 20)
        time.sleep(3)
        c.press(c.D, c.D, c.RT)
        time.sleep(3)
        t = c.texts()
        scored = [x for x in t if x.startswith("IMDb")]
        c.check("Movie posters show their saved scores", len(scored) >= 3, f"{len(scored)} posters")
        stars = [x for x in t if x.startswith("★ ") and "Favorites" not in x]
        c.check("No provider ★ rating on the posters", not stars, ", ".join(stars[:3]))
    finally:
        r.adb("shell", "am", "force-stop", r.PKG)
        r.app_shell(f"rm -f {PREFS} {CACHE}\nfor f in {PREFS} {CACHE}; do [ -f $f.owner ] && mv $f.owner $f; done\n"
                    f"rm -f shared_prefs/playlist_{r.TEST_ID}.xml\n")
        r.restore()
        r.adb("reverse", "--remove", f"tcp:{mock_server.PORT}")
        s.shutdown()
    failed = [n for ok, n in c.results if not ok]
    print(f"\n{len(c.results) - len(failed)} of {len(c.results)} passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
