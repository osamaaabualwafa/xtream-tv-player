#!/usr/bin/env python3
"""Review item 1.1Y: Home in the Default theme, every background x every colour, as one picture.

  python scripts/uitest/bg_grid.py before      .uitest/bg/<tag>-grid.jpg (and each screenshot)
"""
import os
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(__file__))
import mock_server
import run as r
import player_checks as c

BACKGROUNDS = ["Glow", "Black", "Ocean", "Sunset"]
COLOURS = ["Blue", "Purple", "Teal", "Orange", "Pink", "Green", "Red", "Gold"]


def main():
    tag = sys.argv[1] if len(sys.argv) > 1 else "now"
    out = os.path.join(r.WORK, "bg")
    os.makedirs(out, exist_ok=True)
    server = mock_server.serve()
    threading.Thread(target=server.serve_forever, daemon=True).start()
    lists = "cache/lists/" + __import__("hashlib").md5(f"{mock_server.BASE}|{mock_server.USER}".encode()).hexdigest()
    try:
        r.install()
        r.adb("reverse", f"tcp:{mock_server.PORT}", f"tcp:{mock_server.PORT}")
        r.adb("shell", "am", "force-stop", r.PKG)
        r.add_test_playlist()
        r.only_test_playlist()
        for b in range(len(BACKGROUNDS)):
            for a in range(len(COLOURS)):
                c.start(theme=0, palette=0, accent=a, background_style=b, diag_on="false")
                time.sleep(2.5)
                c.shot(os.path.join("bg", f"{tag}-{b}-{a}"))
    finally:
        r.app_shell(f"rm -f shared_prefs/playlist_{r.TEST_ID}.xml\nrm -rf {lists}\n")
        r.restore()
        r.adb("reverse", "--remove", f"tcp:{mock_server.PORT}")
        server.shutdown()
    from PIL import Image, ImageDraw
    w, h, pad, label = 480, 270, 8, 34
    sheet = Image.new("RGB", (pad + len(COLOURS) * (w + pad) + 90, label + len(BACKGROUNDS) * (h + pad)), (24, 24, 24))
    d = ImageDraw.Draw(sheet)
    for a, name in enumerate(COLOURS):
        d.text((90 + pad + a * (w + pad) + 4, 10), name, fill=(230, 230, 230))
    for b, name in enumerate(BACKGROUNDS):
        d.text((8, label + b * (h + pad) + h // 2), name, fill=(230, 230, 230))
        for a in range(len(COLOURS)):
            img = Image.open(os.path.join(out, f"{tag}-{b}-{a}.png")).convert("RGB").resize((w, h))
            sheet.paste(img, (90 + pad + a * (w + pad), label + b * (h + pad)))
    sheet.save(os.path.join(out, f"{tag}-grid.jpg"), quality=85)
    print("saved", os.path.join(out, f"{tag}-grid.jpg"))


if __name__ == "__main__":
    main()
