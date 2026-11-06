#!/usr/bin/env python3
"""Text contrast for every theme, background and highlight style, from the theme files. No emulator.

Works out what text sits on (translucent fills over the background) and checks at least 4.5:1,
at the background's base colour and over its brightest glow. Run on its own, or by run.py.
"""
import os
import re
import xml.etree.ElementTree as ET

VALUES = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main", "res", "values")
MIN_RATIO = 4.5


def load_styles():
    styles = {}
    for name in ("themes.xml", "themes_palette.xml", "themes_scene.xml", "themes_glass.xml", "themes_look.xml", "themes_accent.xml"):
        path = os.path.join(VALUES, name)
        if not os.path.exists(path):
            continue
        for style in ET.parse(path).getroot().findall("style"):
            styles[style.get("name")] = {i.get("name"): (i.text or "").strip() for i in style.findall("item")}
    return styles


def resolve(values, name, depth=0):
    v = values.get(name)
    if v is None or depth > 8:
        return None
    if v.startswith("?attr/"):
        return resolve(values, v[6:], depth + 1)
    return v if re.fullmatch(r"#[0-9A-Fa-f]{6,8}", v) else None


def rgba(hex_colour):
    h = hex_colour[1:]
    if len(h) == 6:
        h = "FF" + h
    return int(h[2:4], 16), int(h[4:6], 16), int(h[6:8], 16), int(h[:2], 16) / 255


def over(top, under):
    r, g, b, a = rgba(top) if isinstance(top, str) else top
    return tuple(c * a + u * (1 - a) for c, u in zip((r, g, b), under))


def luminance(c):
    def part(v):
        v /= 255
        return v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4
    return 0.2126 * part(c[0]) + 0.7152 * part(c[1]) + 0.0722 * part(c[2])


def ratio(fg, bg):
    a, b = sorted((luminance(fg), luminance(bg)), reverse=True)
    return (a + 0.05) / (b + 0.05)


def worst(fg, fills, values):
    """Lowest contrast of the text over the fills, at the base colour and over the glow."""
    fg_value = resolve(values, fg)
    if not fg_value:
        return None
    lowest = None
    bases = [rgba(resolve(values, "appBg"))[:3]]
    glow = resolve(values, "appGlowA")
    if glow:
        bases.append(over(glow, bases[0]))
    for base in bases:
        c = base
        for fill in fills:
            v = resolve(values, fill)
            if v:
                c = over(v, c)
        r = ratio(over(fg_value, c), c)
        lowest = r if lowest is None else min(lowest, r)
    return lowest


CHECKS = [
    ("main text on a panel", "appText", ["appGlassFill"]),
    ("secondary text on a panel", "appTextSecondary", ["appGlassFill"]),
    ("highlighted row text", "appFocusText", ["appGlassFill", "appFocusFill"]),
    ("highlighted row secondary", "appFocusSecondaryText", ["appGlassFill", "appFocusFill"]),
    ("highlighted pill text", "appFocusText", ["appGlassFill", "appFocusPill"]),
    ("highlighted button text", "appButtonFocusText", ["appButtonFocus"]),
]


def report():
    styles = load_styles()
    base = styles["AppTheme"]
    problems = []
    def run(label, *layers):
        values = dict(base)
        for layer in layers:
            values.update(styles.get(layer, {}))
        for what, fg, fills in CHECKS:
            r = worst(fg, fills, values)
            bar = MIN_RATIO  # Every text, buttons too (review 1.1R).
            if r is not None and r < bar:
                problems.append(f"{label}: {what} {r:.1f}:1")
    for accent in [n for n in styles if n.startswith("Accent.")]:
        run(f"Default {accent[7:]}", accent)
    for palette in ("Coastal", "Coconut"):
        run(f"Default {palette} colours", "Accent.Blue", f"Palette.{palette}")
    run("Cinema", "Accent.Blue", "Palette.Cinema", "Look.Cinema")
    scenes = [n for n in styles if n.startswith("Scene.")]
    for scene in scenes:
        daylight = scene == "Scene.Daylight"
        for glass in ["Glass.Daylight"] if daylight else ["Glass.Clear", "Glass.Frosted", "Glass.Smoked", "Glass.Perf"]:
            for focus in ("Ring", "White", "Solid", "Lift"):
                layer = "Focus.Daylight" + focus if daylight else "Focus." + focus
                run(f"Glass {scene[6:]} {glass[6:]} {focus}", "Accent.Blue", "Palette.Glass", "Look.Glass", scene, glass, layer)
    for p in problems:
        print("  " + p)
    print(f"  {len(problems)} below {MIN_RATIO}:1" if problems else f"  all at least {MIN_RATIO}:1")
    return len(problems)


if __name__ == "__main__":
    report()
