#!/usr/bin/env python3
"""Review item 1.1R: every theme's text and highlight colours with their contrast ratios (JSON).

  python scripts/uitest/colour_table.py > .uitest/colour_table.json

One row per theme / scene / glass style. Ratios are the lowest over the background's base colour and
its brightest glow, and (Glass) over all four highlight styles. Uses check_contrast.py's maths.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
import check_contrast as cc

TOKENS = [("Background", "appBg"), ("Panel", "appGlassFill"), ("Main text", "appText"),
          ("Secondary text", "appTextSecondary"), ("Highlight", "appFocusFill"),
          ("Highlighted text", "appFocusText"), ("Highlighted secondary", "appFocusSecondaryText"),
          ("Accent", "appAccent")]


def main():
    styles = cc.load_styles()
    base = styles["AppTheme"]
    rows = []

    def values_for(*layers):
        v = dict(base)
        for layer in layers:
            v.update(styles.get(layer, {}))
        return v

    def add(theme, variant, layer_sets):
        """layer_sets: one list of layers per highlight style; ratios are the lowest over all of them."""
        checks = {}
        for layers in layer_sets:
            v = values_for(*layers)
            for what, fg, fills in cc.CHECKS:
                r = cc.worst(fg, fills, v)
                if r is not None:
                    checks[what] = min(checks.get(what, 99), r)
        shown = values_for(*layer_sets[0])
        rows.append({
            "theme": theme, "variant": variant,
            "tokens": {label: cc.resolve(shown, name) for label, name in TOKENS},
            "ratios": {k: round(v, 1) for k, v in checks.items()},
            "lowest": round(min(checks.values()), 1),
        })

    for accent in [n for n in styles if n.startswith("Accent.")]:
        add("Default", f"{accent[7:]} accent", [[accent]])
    for palette in ("Coastal", "Coconut"):
        add("Default", f"{palette} colours", [["Accent.Blue", f"Palette.{palette}"]])
    add("Cinema", "", [["Accent.Blue", "Palette.Cinema", "Look.Cinema"]])
    for scene in [n for n in styles if n.startswith("Scene.")]:
        daylight = scene == "Scene.Daylight"
        for glass in ["Glass.Daylight"] if daylight else ["Glass.Clear", "Glass.Frosted", "Glass.Smoked"]:
            focus = [("Focus.Daylight" if daylight else "Focus.") + f for f in ("Ring", "White", "Solid", "Lift")]
            add("Glass", f"{scene[6:]} · {glass[6:]}",
                [["Accent.Blue", "Palette.Glass", "Look.Glass", scene, glass, f] for f in focus])
    json.dump({"min": cc.MIN_RATIO, "checks": [c[0] for c in cc.CHECKS], "rows": rows}, sys.stdout, indent=1)


if __name__ == "__main__":
    main()
