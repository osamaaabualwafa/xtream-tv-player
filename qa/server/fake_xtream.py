#!/usr/bin/env python3
"""
Fake Xtream Codes server for the weekly QA run.

Runs on the CI machine; the Android TV emulator reaches it at http://10.0.2.2:8080.
Everything is made up: no real provider, no real credentials.

Accounts (username = password):
  demo      normal account with varied, tricky data (Arabic names, long names, missing posters...)
  second    a second normal account (switching playlists)
  expiring  valid, but expires in 2 days (expiry warning)
  expired   subscription expired
  banned    status "Banned"
  slow      every call takes several seconds
  broken    logs in, then every list is broken (HTML, cut-off JSON, server errors)
  tsonly    allowed_output_formats = ["ts"] (no HLS)
  huge      10,000 channels, 50,000 movies, 5,000 series
  anything else -> wrong username or password

Control endpoints (used by the tests):
  /__down?seconds=N   drop every connection for N seconds (internet cut)
  /__up               end a /__down early
  /__log              last requests (JSON)
"""
import base64
import json
import os
import random
import re
import socketserver
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
MEDIA = os.path.join(HERE, "media")
PORT = int(os.environ.get("PORT", "8080"))
# Address the emulator uses to reach this machine (image URLs must point there).
PUBLIC = os.environ.get("PUBLIC_BASE", "http://10.0.2.2:%d" % PORT)

ACCOUNTS = {"demo", "second", "expiring", "expired", "banned", "slow", "broken", "tsonly", "huge"}
SEG_SECONDS = 4
SEG_COUNT = 8
BROKEN_CHANNEL = "13"
SLOW_CHANNEL = "14"
BROKEN_MOVIE = "113"

state = {"down_until": 0.0}
request_log = []
log_lock = threading.Lock()


def b64(s):
    return base64.b64encode(s.encode("utf-8")).decode("ascii")


def img(kind, i):
    return "%s/img/%s%d.%s" % (PUBLIC, kind, i % 8, "jpg" if kind == "backdrop" else "png")


# ----------------------------------------------------------------------------- demo data

LIVE_CATS = [
    ("1", "News"),
    ("2", "Sports"),
    ("3", "Kids"),
    ("4", "قنوات عربية"),
    ("5", "A very long category name that keeps going and going to test how it fits on a TV"),
    ("6", "Empty category"),
]
VOD_CATS = [("10", "Action"), ("11", "Comedy"), ("12", "دراما"), ("13", "Documentary"), ("14", "Empty movies")]
SERIES_CATS = [("20", "Drama"), ("21", "Kids"), ("22", "Anime")]

CHANNEL_NAMES = [
    "News 24", "World News HD", "Business Today", "Weather Now", "Sky Report", "Daily Brief",
    "Sports 1 HD", "Sports 2 HD", "Football Live", "Tennis Channel", "Racing TV", "Fight Night",
    "Broken channel (404)", "Slow channel (buffering)", "Kids Fun", "Cartoon Time", "Learn & Play",
    "Toon Classics", "الجزيرة التجريبية", "العربية تجريبي", "قناة الأخبار", "رياضة 1", "أطفال",
    "Channel with an extremely long name that will never fit in one line on any television",
    "4K Ultra Demo", "FHD | Movies Plus", "|EN| Documentary", "VIP ★ Premium", "Music Hits", "Comedy Central Demo",
    "Travel & Food", "History Demo", "Science Lab", "Nature Wild", "Auto Motors", "Fashion One",
    "Shopping Live", "Religious Channel", "Local TV", "Radio Visual", "Retro TV", "Gaming Live",
    "Cooking Show", "Health & Fitness", "Pets TV", "Space Channel", "Drama Hits", "Late Night",
]


def demo_channels(account):
    out = []
    for i, name in enumerate(CHANNEL_NAMES, start=1):
        sid = str(i)
        if i <= 6:
            cat = "1"
        elif i <= 12:
            cat = "2"
        elif i <= 14:
            cat = "1"
        elif i <= 18:
            cat = "3"
        elif i <= 23:
            cat = "4"
        else:
            cat = str(1 + i % 5)
        icon = img("logo", i)
        if i % 9 == 0:
            icon = ""  # no logo
        if i % 11 == 0:
            icon = PUBLIC + "/img/missing%d.png" % i  # logo URL that 404s
        out.append({
            "num": i,
            "name": name if account != "second" else "Second · " + name,
            "stream_type": "live",
            "stream_id": i if i % 2 else sid,  # ids as int or string
            "stream_icon": icon,
            "epg_channel_id": "ch%d" % i,
            "added": str(1700000000 + i * 1000),
            "category_id": cat,
            "category_ids": [int(cat)],
            "custom_sid": "",
            "tv_archive": 1 if i % 4 == 0 else 0,
            "direct_source": "",
            "tv_archive_duration": 3 if i % 4 == 0 else 0,
        })
    return out


MOVIE_WORDS = ["Desert", "Storm", "Night", "Falcon", "City", "Ocean", "Shadow", "Golden", "Last", "Silent",
               "Red", "Iron", "Lost", "Hidden", "Winter", "Fire"]
ARABIC_TITLES = ["رحلة إلى الصحراء", "ليلة في الرياض", "البحر الأحمر", "الصقر الذهبي"]


def demo_movies():
    rnd = random.Random(7)
    out = []
    for n in range(1, 73):
        sid = 100 + n
        cat = ["10", "11", "12", "13"][n % 4]
        if n % 9 == 0:
            name = ARABIC_TITLES[(n // 9) % len(ARABIC_TITLES)]
        elif n % 13 == 0:
            name = "An Extraordinarily Long Movie Title That Goes On And On: The Director's Extended Cut (2024)"
        else:
            name = "%s %s" % (rnd.choice(MOVIE_WORDS), rnd.choice(MOVIE_WORDS))
        if str(sid) == BROKEN_MOVIE:
            name = "Broken movie (file missing)"
        rating = [7.491, "8", "0", "", None, "6.2", 9][n % 7]
        out.append({
            "num": n,
            "name": name,
            "stream_type": "movie",
            "stream_id": sid,
            "stream_icon": "" if n % 10 == 0 else img("poster", n),
            "rating": rating,
            "rating_5based": 3.5,
            "added": str(int(time.time()) - n * 86400),
            "category_id": cat,
            "category_ids": [int(cat)] + ([11] if n % 5 == 0 else []),
            "container_extension": "mkv" if n % 6 == 0 else "mp4",
            "custom_sid": None,
            "direct_source": "",
            "genre": ["Action, Thriller", "Comedy", "Drama", "Documentary"][n % 4],
            "year": str(1995 + n % 30),
            "plot": "A test movie used by the weekly check. " * (1 + n % 4),
        })
    return out


def demo_series():
    out = []
    names = ["The Long Road", "Family Matters Demo", "Space Cadets", "Kingdom of Sand", "مسلسل تجريبي",
             "Detectives", "Hero Academy Demo", "Ninja Steps", "Cooking Duel", "Night Shift",
             "A Series With A Remarkably Long Name To Check Wrapping On Posters", "Mini Stories",
             "Comedy Club", "Ocean Life", "Empty series (no episodes)", "Legends", "Cartoon Town", "Robots"]
    for n, name in enumerate(names, start=1):
        out.append({
            "num": n,
            "name": name,
            "series_id": 200 + n,
            "cover": "" if n == 12 else img("poster", n + 3),
            "plot": "A test series with several seasons. " * (1 + n % 3),
            "cast": "Actor One, Actor Two",
            "director": "Director",
            "genre": ["Drama", "Kids", "Animation"][n % 3],
            "releaseDate": "20%02d-05-01" % (10 + n),
            "last_modified": str(int(time.time()) - n * 3600 * 20),
            "rating": ["8.1", "7", "0", 6.66][n % 4],
            "backdrop_path": [img("backdrop", n)],
            "youtube_trailer": "",
            "category_id": ["20", "21", "22"][n % 3],
            "category_ids": [int(["20", "21", "22"][n % 3])],
        })
    return out


def series_info(series_id):
    n = int(series_id) - 200
    seasons = {}
    if n == 15:
        episodes = []  # empty series
    else:
        count_seasons = 3 if n == 1 else 1 + n % 3
        per_season = 12 if n == 4 else 4
        episodes = []
        eid = int(series_id) * 100
        for s in range(1, count_seasons + 1):
            seasons[str(s)] = []
            for e in range(1, per_season + 1):
                eid += 1
                ep = {
                    "id": str(eid),
                    "episode_num": e,
                    "title": "S%02dE%02d - Episode %d" % (s, e, e) if e != 3 else "S%02dE03 - حلقة باللغة العربية" % s,
                    "container_extension": "mp4",
                    "season": s,
                    "info": {
                        "plot": "Episode plot for the weekly check.",
                        "duration": "00:00:45",
                        "movie_image": PUBLIC + "/img/still.jpg",
                    },
                }
                seasons[str(s)].append(ep)
    info = {
        "name": "Series %s" % series_id,
        "cover": img("poster", n + 3),
        "plot": "Series plot for the weekly check.",
        "cast": "Actor One, Actor Two, Actor Three",
        "director": "Director",
        "genre": "Drama",
        "releaseDate": "2020-01-01",
        "rating": "7.8",
        "backdrop_path": [img("backdrop", n)],
        "youtube_trailer": "",
    }
    # Even-numbered series send episodes as an array of arrays (the other format panels use).
    episodes_value = list(seasons.values()) if n % 2 == 0 else seasons
    return {"seasons": [{"season_number": int(k), "name": "Season " + k} for k in seasons], "info": info,
            "episodes": episodes_value}


def vod_info(vod_id):
    n = int(vod_id) - 100
    return {
        "info": {
            "movie_image": img("poster", n),
            "cover_big": img("poster", n),
            "backdrop_path": [img("backdrop", n)],
            "plot": "Full plot of test movie %s. It is long enough to need two or three lines on the TV, "
                    "so the details screen shows how it wraps." % vod_id,
            "cast": "Actor One, Actor Two, Actor Three, Actor Four",
            "director": "Test Director",
            "genre": "Action, Thriller",
            "releasedate": "2021-06-01",
            "duration": "00:00:45",
            "rating": "7.4",
            "youtube_trailer": "",
        },
        "movie_data": {"stream_id": int(vod_id), "name": "Movie %s" % vod_id,
                       "container_extension": "mp4", "category_id": "10"},
    }


# ----------------------------------------------------------------------------- huge data (cached)

_huge = {}


def huge(kind):
    if kind in _huge:
        return _huge[kind]
    if kind == "live_cats":
        data = [{"category_id": str(i), "category_name": "Big category %d" % i, "parent_id": 0} for i in range(1, 41)]
    elif kind == "vod_cats":
        data = [{"category_id": str(100 + i), "category_name": "Movies group %d" % i, "parent_id": 0} for i in range(1, 61)]
    elif kind == "series_cats":
        data = [{"category_id": str(300 + i), "category_name": "Series group %d" % i, "parent_id": 0} for i in range(1, 31)]
    elif kind == "live":
        data = [{"num": i, "name": "Channel %05d" % i, "stream_type": "live", "stream_id": i,
                 "stream_icon": img("logo", i), "category_id": str(1 + i % 40), "tv_archive": 0} for i in range(1, 10001)]
    elif kind == "vod":
        data = [{"num": i, "name": "Movie %05d" % i, "stream_type": "movie", "stream_id": 100000 + i,
                 "stream_icon": img("poster", i), "rating": "7", "added": str(1700000000 + i),
                 "category_id": str(101 + i % 60), "container_extension": "mp4"} for i in range(1, 50001)]
    elif kind == "series":
        data = [{"num": i, "name": "Series %05d" % i, "series_id": 500000 + i, "cover": img("poster", i),
                 "rating": "8", "last_modified": str(1700000000 + i), "category_id": str(301 + i % 30)}
                for i in range(1, 5001)]
    else:
        data = []
    _huge[kind] = json.dumps(data).encode("utf-8")
    return _huge[kind]


# ----------------------------------------------------------------------------- EPG

def short_epg(stream_id):
    now = int(time.time())
    start = now - 20 * 60
    items = [
        ("Morning show on channel %s" % stream_id, "What is on now: a test programme."),
        ("نشرة الأخبار", "البرنامج التالي للاختبار"),
    ]
    out = []
    for i, (title, desc) in enumerate(items):
        s = start + i * 3600
        out.append({"id": str(i), "epg_id": "1", "title": b64(title), "lang": "en",
                    "start": time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime(s)),
                    "end": time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime(s + 3600)),
                    "description": b64(desc), "channel_id": "ch" + str(stream_id),
                    "start_timestamp": str(s), "stop_timestamp": str(s + 3600)})
    return {"epg_listings": out}


def full_epg(stream_id):
    now = int(time.time())
    base = now - now % 3600 - 12 * 3600
    out = []
    for i in range(24):
        s = base + i * 3600
        out.append({"id": str(i), "title": b64("Programme %d on %s" % (i + 1, stream_id)),
                    "description": b64("Guide entry %d for the weekly check." % (i + 1)),
                    "start": time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime(s)),
                    "end": time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime(s + 3600)),
                    "start_timestamp": str(s), "stop_timestamp": str(s + 3600),
                    "now_playing": 1 if s <= now < s + 3600 else 0,
                    "has_archive": 1 if s + 3600 < now and int(stream_id) % 4 == 0 else 0})
    return {"epg_listings": out}


# ----------------------------------------------------------------------------- HTTP

def user_info(user):
    now = int(time.time())
    exp = now + 365 * 86400
    status = "Active"
    if user == "expiring":
        exp = now + 2 * 86400
    if user == "expired":
        exp = now - 5 * 86400
        status = "Expired"
    if user == "banned":
        status = "Banned"
    formats = ["ts"] if user == "tsonly" else ["m3u8", "ts", "rtmp"]
    return {
        "user_info": {
            "username": user, "password": user, "message": "Welcome to the fake test server",
            "auth": 1, "status": status, "exp_date": str(exp), "is_trial": "0",
            "active_cons": "0", "created_at": str(now - 400 * 86400), "max_connections": "2",
            "allowed_output_formats": formats,
        },
        "server_info": {"url": "10.0.2.2", "port": str(PORT), "https_port": "443", "server_protocol": "http",
                        "rtmp_port": "0", "timezone": "Asia/Riyadh", "timestamp_now": now,
                        "time_now": time.strftime("%Y-%m-%d %H:%M:%S")},
    }


def cats(rows):
    return [{"category_id": cid, "category_name": name, "parent_id": 0} for cid, name in rows]


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "FakeXtream/1.0"

    def log_message(self, fmt, *args):
        line = "%s %s" % (time.strftime("%H:%M:%S"), fmt % args)
        with log_lock:
            request_log.append(line)
            del request_log[:-500]
        sys.stderr.write(line + "\n")

    # --- helpers
    def send_bytes(self, body, ctype="application/json; charset=utf-8", code=200, extra=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass

    def send_json(self, obj, code=200):
        self.send_bytes(json.dumps(obj, ensure_ascii=False).encode("utf-8"), code=code)

    def send_file(self, path, ctype):
        if not os.path.exists(path):
            return self.send_bytes(b"not found", "text/plain", 404)
        size = os.path.getsize(path)
        rng = self.headers.get("Range")
        start, end = 0, size - 1
        code = 200
        if rng:
            m = re.match(r"bytes=(\d*)-(\d*)", rng)
            if m:
                if m.group(1):
                    start = int(m.group(1))
                if m.group(2):
                    end = min(int(m.group(2)), size - 1)
                code = 206
        if start >= size:
            return self.send_bytes(b"", ctype, 416, {"Content-Range": "bytes */%d" % size})
        with open(path, "rb") as f:
            f.seek(start)
            body = f.read(end - start + 1)
        extra = {"Accept-Ranges": "bytes"}
        if code == 206:
            extra["Content-Range"] = "bytes %d-%d/%d" % (start, end, size)
        self.send_bytes(body, ctype, code, extra)

    def drop(self):
        # Internet cut: close without an answer.
        self.close_connection = True
        try:
            self.connection.shutdown(2)
        except OSError:
            pass

    # --- routes
    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        url = urlparse(self.path)
        path = url.path
        q = {k: v[0] for k, v in parse_qs(url.query).items()}

        if path == "/__down":
            state["down_until"] = time.time() + float(q.get("seconds", "15"))
            return self.send_json({"down_until": state["down_until"]})
        if path == "/__up":
            state["down_until"] = 0
            return self.send_json({"up": True})
        if path == "/__log":
            with log_lock:
                return self.send_json(request_log[-200:])
        if path == "/__health":
            return self.send_json({"ok": True})
        if time.time() < state["down_until"]:
            return self.drop()

        if path.startswith("/img/"):
            name = os.path.basename(path)
            ctype = "image/jpeg" if name.endswith(".jpg") else "image/png"
            return self.send_file(os.path.join(MEDIA, "img", name), ctype)
        if path.startswith("/hlsseg/"):
            n = int(re.sub(r"\D", "", os.path.basename(path)) or "0")
            return self.send_file(os.path.join(MEDIA, "hls", "seg%d.ts" % (n % SEG_COUNT)), "video/mp2t")
        if path.endswith("player_api.php"):
            return self.api(q)
        if path.endswith("get.php"):
            return self.m3u(q)

        m = re.match(r"^/(live|movie|series|timeshift)/([^/]+)/([^/]+)/(.+)$", path)
        if m:
            kind, user, password, rest = m.groups()
            if user not in ACCOUNTS or password != user:
                return self.send_bytes(b"Forbidden", "text/plain", 403)
            return self.stream(kind, user, rest)
        return self.send_bytes(b"not found", "text/plain", 404)

    def stream(self, kind, user, rest):
        name = os.path.basename(rest)
        sid, _, ext = name.partition(".")
        if kind == "live":
            if sid == BROKEN_CHANNEL:
                return self.send_bytes(b"<html>404 Not Found</html>", "text/html", 404)
            if sid == SLOW_CHANNEL:
                time.sleep(6)
            if ext == "m3u8":
                if user == "tsonly":
                    return self.send_bytes(b"m3u8 not allowed", "text/plain", 403)
                return self.live_playlist()
            return self.send_file(os.path.join(MEDIA, "live.ts"), "video/mp2t")
        if kind == "timeshift":
            return self.send_file(os.path.join(MEDIA, "live.ts"), "video/mp2t")
        if kind == "movie" and sid == BROKEN_MOVIE:
            return self.send_bytes(b"<html>404</html>", "text/html", 404)
        ctype = "video/x-matroska" if ext == "mkv" else "video/mp4"
        return self.send_file(os.path.join(MEDIA, "movie.mp4"), ctype)

    def live_playlist(self):
        first = int(time.time() // SEG_SECONDS) - 3
        lines = ["#EXTM3U", "#EXT-X-VERSION:3", "#EXT-X-TARGETDURATION:%d" % SEG_SECONDS,
                 "#EXT-X-MEDIA-SEQUENCE:%d" % first, "#EXT-X-DISCONTINUITY-SEQUENCE:%d" % (first // SEG_COUNT)]
        for seq in range(first, first + 4):
            if seq != first and seq % SEG_COUNT == 0:
                lines.append("#EXT-X-DISCONTINUITY")
            lines.append("#EXTINF:%d.000000," % SEG_SECONDS)
            lines.append("%s/hlsseg/%d.ts" % (PUBLIC, seq))
        body = ("\n".join(lines) + "\n").encode()
        self.send_bytes(body, "application/vnd.apple.mpegurl", extra={"Cache-Control": "no-cache"})

    def m3u(self, q):
        user = q.get("username", "")
        if user not in ACCOUNTS or q.get("password") != user:
            return self.send_bytes(b"", "text/plain", 403)
        lines = ["#EXTM3U"]
        for ch in demo_channels(user)[:10]:
            lines.append('#EXTINF:-1 tvg-id="%s" tvg-logo="%s",%s' % (ch["epg_channel_id"], ch["stream_icon"], ch["name"]))
            lines.append("%s/live/%s/%s/%s.ts" % (PUBLIC, user, user, ch["stream_id"]))
        self.send_bytes(("\n".join(lines) + "\n").encode("utf-8"), "audio/x-mpegurl")

    def api(self, q):
        user = q.get("username", "")
        password = q.get("password", "")
        action = q.get("action")
        if user not in ACCOUNTS or password != user:
            return self.send_json({"user_info": {"auth": 0}})
        if user == "slow":
            time.sleep(3 if action is None else 6)
        if action is None:
            return self.send_json(user_info(user))

        if user == "broken":
            if "categories" in action:
                return self.send_bytes(b"<html><body>500 Internal Server Error</body></html>", "text/html", 500)
            if action in ("get_live_streams", "get_vod_streams", "get_series"):
                return self.send_bytes(b'[{"stream_id": 1, "name": "cut off', "application/json")
            return self.send_bytes(b"null", "application/json")

        if user == "huge":
            table = {
                "get_live_categories": "live_cats", "get_vod_categories": "vod_cats",
                "get_series_categories": "series_cats", "get_live_streams": "live",
                "get_vod_streams": "vod", "get_series": "series",
            }
            if action in table:
                body = huge(table[action])
                cid = q.get("category_id")
                if cid and action in ("get_live_streams", "get_vod_streams", "get_series"):
                    rows = [r for r in json.loads(body) if r["category_id"] == cid]
                    return self.send_json(rows)
                return self.send_bytes(body)

        cid = q.get("category_id")
        if action == "get_live_categories":
            return self.send_json(cats(LIVE_CATS))
        if action == "get_vod_categories":
            return self.send_json(cats(VOD_CATS))
        if action == "get_series_categories":
            return self.send_json(cats(SERIES_CATS))
        if action == "get_live_streams":
            rows = demo_channels(user)
            return self.send_json([r for r in rows if not cid or r["category_id"] == cid])
        if action == "get_vod_streams":
            rows = demo_movies()
            return self.send_json([r for r in rows if not cid or r["category_id"] == cid])
        if action == "get_series":
            rows = demo_series()
            return self.send_json([r for r in rows if not cid or r["category_id"] == cid])
        if action == "get_vod_info":
            return self.send_json(vod_info(q.get("vod_id", "101")))
        if action == "get_series_info":
            return self.send_json(series_info(q.get("series_id", "201")))
        if action == "get_short_epg":
            return self.send_json(short_epg(q.get("stream_id", "1")))
        if action == "get_simple_data_table":
            return self.send_json(full_epg(q.get("stream_id", "1")))
        return self.send_json([])


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


if __name__ == "__main__":
    print("Fake Xtream server on 0.0.0.0:%d (public %s)" % (PORT, PUBLIC), flush=True)
    Server(("0.0.0.0", PORT), Handler).serve_forever()
