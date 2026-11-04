#!/usr/bin/env python3
"""Splits the test classes over parallel emulators. Prints the GitHub matrix.
Usage: shards.py "<comma separated classes or empty>"   (empty = full suite)"""
import json
import sys

P = "com.osama.xtreamtv.qa."
SHARDS = [
    ("data-login-series-search", ["T01_DataTest", "T02_LoginAndPlaylistsTest", "T05_SeriesTest", "T06_SearchHomeTest"], False),
    ("live-movies", ["T03_LiveTvTest", "T04_MoviesTest"], False),
    ("settings-new-network", ["T07_SettingsTest", "T11_NewIn12Test", "T10_BadNetworkTest"], False),
    ("themes-a", ["T08_ThemesTest#themeDefault+themeCinema+themeGlass+removedThemesMoveToDefault"], False),
    ("themes-b", ["T08_ThemesTest#glassBackgrounds+glassStylesAndHighlights+glassMovingBackground+colours+palettesAndBackgrounds+sizes+effects"], False),
    ("stress-random", ["T09_StressTest"], True),
]


def expand(classes):
    out = []
    for c in classes:
        if "#" in c:
            cls, methods = c.split("#", 1)
            out += ["%s%s#%s" % (P, cls, m) for m in methods.split("+")]
        else:
            out.append(P + c)
    return ",".join(out)


custom = sys.argv[1].strip() if len(sys.argv) > 1 else ""
if custom:
    matrix = [{"shard": "custom", "tests": custom, "monkey": "0"}]
else:
    matrix = [{"shard": n, "tests": expand(c), "monkey": "1" if m else "0"} for n, c, m in SHARDS]
print("matrix=" + json.dumps({"include": matrix}))
