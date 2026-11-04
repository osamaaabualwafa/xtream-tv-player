#!/usr/bin/env python3
"""Fake Xtream Codes server for the screen tests: instant, always the same data, no real account.

The account here is fake and only exists on this machine. Posters are generated locally, so every
screen looks the same on every run. Video redirects to a public test clip (player screen only).
"""
import json
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


def img(seed):
    return f"{BASE}/img/{seed}.png"


LONG = "A Very Long Title That Has To Be Cut Off Somewhere On The Card"
live_cats = [{"category_id": str(i), "category_name": f"Live group {i}"} for i in range(1, 15)]
live = [{"num": i + 1, "name": LONG if i == 3 else f"Channel {i + 1}", "stream_type": "live", "stream_id": 100 + i,
         "stream_icon": img(f"ch{i % 7}"), "category_id": str(i % 14 + 1)} for i in range(140)]
vod_cats = [{"category_id": str(i), "category_name": f"Movie group {i}"} for i in range(20, 34)]
vod = [{"num": i, "name": LONG if i == 2 else f"Movie {i}", "stream_type": "movie", "stream_id": str(500 + i),
        "stream_icon": img(f"mv{i % 11}"), "rating": str(5 + i % 5), "category_id": str(20 + i % 14),
        "added": str(1800000000 - i), "container_extension": "mp4"} for i in range(280)]
series_cats = [{"category_id": str(i), "category_name": f"Series group {i}"} for i in range(40, 50)]
series = [{"num": i, "name": LONG if i == 1 else f"Show {i}", "series_id": 900 + i, "cover": img(f"sr{i % 9}"),
           "rating": "8", "category_id": str(40 + i % 10)} for i in range(120)]


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

    def do_GET(self):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        parts = u.path.strip("/").split("/")
        if parts[0] == "img" and len(parts) == 2:
            return self.send_body(poster(parts[1]), "image/png")
        if parts[0] in ("live", "movie", "series") and len(parts) == 4:
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
