#!/usr/bin/env python3
"""Fake Xtream Codes server for the screen tests: instant, always the same data, no real account.

The account here is fake and only exists on this machine. Posters are generated locally, so every
screen looks the same on every run. Video is the small test clip in qa/server/media.
"""
import json
import os
import struct
import sys
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

USER, PASS = "uitest", "uitest"
PORT = 8090
MP4 = "https://filesamples.com/samples/video/mp4/sample_640x360.mp4"
EXPIRES = 1830000000  # Fixed, so the expiry line never changes between runs.
BASE = f"http://127.0.0.1:{PORT}"
HLS_DIR = os.path.join(os.path.dirname(__file__), "..", "..", "qa", "server", "media", "hls")
VIDEO = os.path.join(os.path.dirname(__file__), "..", "..", "qa", "server", "media", "movie.mp4")


def img(seed):
    return f"{BASE}/img/{seed}.png"


LONG = "A Very Long Title That Has To Be Cut Off Somewhere On The Card"
# MOCK_BIG: as many items as a real playlist, so counts on Home are as wide as on a real TV.
BIG = bool(os.environ.get("MOCK_BIG"))
live_cats = [{"category_id": str(i), "category_name": f"Live group {i}"} for i in range(1, 15)]
live = [{"num": i + 1, "name": LONG if i == 3 else f"Channel {i + 1}", "stream_type": "live", "stream_id": 100 + i,
         "stream_icon": img(f"ch{i % 7}"), "category_id": str(i % 14 + 1)} for i in range(5537 if BIG else 140)]
vod_cats = [{"category_id": str(i), "category_name": f"Movie group {i}"} for i in range(20, 34)]
vod = [{"num": i, "name": LONG if i == 2 else f"Movie {i}", "stream_type": "movie", "stream_id": str(500 + i),
        "stream_icon": img(f"mv{i % 11}"), "rating": str(5 + i % 5), "category_id": str(20 + i % 14),
        "added": str(1800000000 - i), "container_extension": "mp4"} for i in range(21790 if BIG else 280)]
series_cats = [{"category_id": str(i), "category_name": f"Series group {i}"} for i in range(40, 50)]
series = [{"num": i, "name": LONG if i == 1 else f"Show {i}", "series_id": 900 + i, "cover": img(f"sr{i % 9}"),
           "rating": "8", "category_id": str(40 + i % 10)} for i in range(6525 if BIG else 120)]


def episodes(sid):
    return {str(s): [{"id": str(sid * 100 + s * 10 + e), "episode_num": e, "title": f"Episode {e}",
                      "container_extension": "mp4", "season": s,
                      "info": {"plot": "Something happens.", "duration": "00:42:00"}} for e in range(1, 13)]
            for s in range(1, 9)}


def poster(seed):
    """A small, fixed gradient picture for this seed."""
    w, h = 120, 180
    n = zlib.crc32(seed.encode())
    a, b, c = n & 255, (n >> 8) & 255, (n >> 16) & 255
    rows = b"".join(b"\0" + bytes((a * y // h) if i % 3 == 0 else (b * (h - y) // h) if i % 3 == 1 else c for i in range(w * 3))
                    for y in range(h))
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(rows, 1)) + chunk(b"IEND", b""))


class Handler(BaseHTTPRequestHandler):
    def send_body(self, body, kind):
        self.send_response(200)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def send_json(self, obj):
        self.send_body(json.dumps(obj).encode(), "application/json")

    def send_hls(self, name):
        """A 10 minute stream with two audio tracks and two subtitle tracks (MOCK_TRACKS)."""
        base = f"{BASE}/hls/"
        text = None
        if name == "master.m3u8":
            text = ("#EXTM3U\n"
                    f'#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="English",LANGUAGE="en",DEFAULT=YES,AUTOSELECT=YES,URI="{base}video.m3u8"\n'
                    f'#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="Arabic",LANGUAGE="ar",AUTOSELECT=YES,URI="{base}video.m3u8"\n'
                    f'#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="s",NAME="English",LANGUAGE="en",AUTOSELECT=YES,URI="{base}subs_en.m3u8"\n'
                    f'#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="s",NAME="Arabic",LANGUAGE="ar",AUTOSELECT=YES,URI="{base}subs_ar.m3u8"\n'
                    f'#EXT-X-STREAM-INF:BANDWIDTH=800000,AUDIO="a",SUBTITLES="s"\n{base}video.m3u8\n')
        elif name == "video.m3u8":
            segments = sorted(f for f in os.listdir(HLS_DIR) if f.endswith(".ts"))
            lines = ["#EXTM3U", "#EXT-X-VERSION:3", "#EXT-X-TARGETDURATION:4", "#EXT-X-MEDIA-SEQUENCE:0", "#EXT-X-PLAYLIST-TYPE:VOD"]
            for i in range(150):
                if i and i % len(segments) == 0:
                    lines.append("#EXT-X-DISCONTINUITY")
                lines += ["#EXTINF:4.0,", base + segments[i % len(segments)]]
            text = "\n".join(lines + ["#EXT-X-ENDLIST", ""])
        elif name.startswith("subs_") and name.endswith(".m3u8"):
            text = ("#EXTM3U\n#EXT-X-TARGETDURATION:600\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n"
                    f"#EXTINF:600.0,\n{base}{name[:-5]}.vtt\n#EXT-X-ENDLIST\n")
        elif name.endswith(".vtt"):
            word = "ENGLISH TEST SUBTITLE" if "en" in name else "ARABIC TEST SUBTITLE"
            text = "WEBVTT\n\n00:00:00.000 --> 00:10:00.000\n" + word + "\n"
            return self.send_body(text.encode(), "text/vtt")
        if text is not None:
            return self.send_body(text.encode(), "application/vnd.apple.mpegurl")
        with open(os.path.join(HLS_DIR, os.path.basename(name)), "rb") as f:
            return self.send_body(f.read(), "video/mp2t")

    def do_GET(self):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        parts = u.path.strip("/").split("/")
        if parts[0] == "img" and len(parts) == 2:
            # MOCK_IMG_DELAY: seconds per poster, like a real server over the internet.
            __import__("time").sleep(float(os.environ.get("MOCK_IMG_DELAY") or 0))
            return self.send_body(poster(parts[1]), "image/png")
        if parts[0] == "hls":
            return self.send_hls(parts[1])
        if parts[0] in ("movie", "series") and len(parts) == 4 and os.environ.get("MOCK_TRACKS"):
            return self.send_hls("video.m3u8" if os.environ["MOCK_TRACKS"] == "plain" else "master.m3u8")
        if parts[0] in ("live", "movie", "series") and len(parts) == 4:
            if os.path.exists(VIDEO):
                with open(VIDEO, "rb") as f:
                    return self.send_body(f.read(), "video/mp4")
            self.send_response(302)
            self.send_header("Location", MP4)
            self.end_headers()
            return
        if u.path != "/player_api.php":
            self.send_response(404)
            self.end_headers()
            return
        if q.get("username") != USER or q.get("password") != PASS:
            return self.send_json({"user_info": {"auth": 0}})
        action = q.get("action")
        cat = q.get("category_id")
        def filtered(items):
            return [x for x in items if cat is None or str(x["category_id"]) == cat]
        if action is None:
            return self.send_json({"user_info": {"username": USER, "auth": 1, "status": "Active", "exp_date": str(EXPIRES),
                                                 "max_connections": "1", "active_cons": "0",
                                                 "allowed_output_formats": ["m3u8", "ts"]},
                                   "server_info": {"url": "127.0.0.1", "port": str(PORT)}})
        tables = {"get_live_categories": live_cats, "get_vod_categories": vod_cats, "get_series_categories": series_cats}
        if action in tables:
            return self.send_json(tables[action])
        lists = {"get_live_streams": live, "get_vod_streams": vod, "get_series": series}
        if action in lists:
            return self.send_json(filtered(lists[action]))
        if action == "get_vod_info":
            return self.send_json({"info": {"plot": "A test movie plot.", "rating": "7.5", "genre": "Action",
                                            "releasedate": "2021-05-01", "duration": "01:40:00",
                                            "director": "Someone", "cast": "A, B, C"},
                                   "movie_data": {"stream_id": q.get("vod_id"), "name": "Movie",
                                                  "container_extension": "mp4"}})
        if action == "get_series_info":
            return self.send_json({"info": {"plot": "A test series.", "genre": "Drama", "rating": "8"},
                                   "episodes": episodes(int(q.get("series_id", "0")))})
        self.send_json({"epg_listings": []} if action == "get_short_epg" else [])

    def log_message(self, fmt, *args):
        pass


def serve():
    return ThreadingHTTPServer(("127.0.0.1", PORT), Handler)


if __name__ == "__main__":
    print(f"Fake Xtream server on {BASE}", file=sys.stderr)
    serve().serve_forever()
