#!/usr/bin/env python3
"""Splits the test classes over parallel emulators. Prints the GitHub matrix.
Usage: shards.py "<comma separated classes or empty>" [event]   (empty = full suite; event "push" = short list)"""
import json
import sys

P = "com.osama.xtreamtv.qa."
SHARDS = [
    ("data-login-series-search", ["T01_DataTest", "T02_LoginAndPlaylistsTest", "T05_SeriesTest", "T06_SearchHomeTest"], False),
    ("live-movies", ["T03_LiveTvTest", "T04_MoviesTest"], False),
    ("settings-new-network", ["T07_SettingsTest", "T11_NewIn12Test", "T12_New20Test", "T10_BadNetworkTest"], False),
    ("themes-a", ["T08_ThemesTest#themeDefaultHome+themeDefaultBrowse+themeDefaultScreens+themeGlassHome+themeGlassBrowse+themeGlassScreens+removedThemesMoveToDefault"], False),
    ("themes-b", ["T08_ThemesTest#glassBackgrounds1+glassBackgrounds2+glassBackgrounds3+glassStyles+glassHighlightsDark+glassHighlightsDaylight+glassMovingBackground"], False),
    ("themes-c", ["T08_ThemesTest#colours+palettesAndBackgrounds+sizesSmallSmall+sizesLargeExtraLarge+sizesCompactExtraLarge+sizesNormalLarge+sizesLargeLarge+effects"], False),
    ("stress-random", ["T09_StressTest"], True),
]

# After each push to main: the core checks, what's new, and past crashes. Warns only.
PUSH_SHARDS = [
    ("short-data-live", ["T01_DataTest", "T03_LiveTvTest"], False),
    ("short-new-network", ["T11_NewIn12Test", "T10_BadNetworkTest#brokenServer", "T08_ThemesTest#sizesLargeExtraLarge"], False),
    # New in 2.0.0: Sort button, Arrange Home rows, message pill, Ratings Get started, HD logo, Cinema gone.
    ("short-new-20", ["T12_New20Test", "T08_ThemesTest#removedThemesMoveToDefault", "T07_SettingsTest"], False),
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
    event = sys.argv[2] if len(sys.argv) > 2 else ""
    chosen = PUSH_SHARDS if event == "push" else SHARDS
    matrix = [{"shard": n, "tests": expand(c), "monkey": "1" if m else "0"} for n, c, m in chosen]
print("matrix=" + json.dumps({"include": matrix}))
