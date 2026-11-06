#!/usr/bin/env python3
"""Review item 1.1Y: the texture pictures laid over the Default backgrounds (Settings > Look > Background).

  python scripts/make_textures.py      writes app/src/main/res/drawable-nodpi/tex_*.png

They hold only light and shade (white or dark with transparency), so the theme colour underneath
still shows through. Fixed seeds: running it again gives the same pictures.
  tex_grain.png    256 x 256, repeated: fine grain (Ocean, Sunset)
  tex_bubbles.png  1920 x 1080, stretched: small bubbles, more towards the bottom (Ocean)
  tex_hills.png    1920 x 1080, stretched: three dark hills on the horizon (Sunset)
Needs numpy and Pillow.
"""
import os

import numpy as np
from PIL import Image, ImageDraw

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "drawable-nodpi")


def grain():
    rng = np.random.default_rng(11)
    n = rng.normal(0, 1, (256, 256))
    alpha = np.clip(np.abs(n) * 9, 0, 40).astype(np.uint8)
    rgb = np.where(n[..., None] > 0, 255, 0).astype(np.uint8) * np.ones((1, 1, 3), np.uint8)
    Image.fromarray(np.dstack([rgb, alpha]), "RGBA").save(os.path.join(OUT, "tex_grain.png"), optimize=True)


def bubbles():
    w, h = 1920, 1080
    rng = np.random.default_rng(7)
    pic = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(pic)
    for _ in range(140):
        x, y = rng.uniform(0, w), rng.uniform(0, h)
        r = rng.uniform(1.2, 4.4)
        depth = min(1.0, y / h + 0.2)
        a = int(rng.uniform(40, 120) * 0.5 * depth)
        d.ellipse((x - r, y - r, x + r, y + r), fill=(210, 240, 255, a))
    pic.save(os.path.join(OUT, "tex_bubbles.png"), optimize=True)


def hills():
    # Full screen (mostly empty, so small): stretched to the screen, the hills keep their place.
    w, h = 1920, 1080
    full = 1080
    top = 0
    xx = np.arange(w, dtype=np.float32)[None, :]
    yy = np.arange(top, full, dtype=np.float32)[:, None]
    alpha = np.zeros((h, w), np.float32)
    for k, (y0, amp, per, a) in enumerate(((0.80, 44, 420, 0.35), (0.87, 32, 300, 0.5), (0.93, 20, 220, 0.65))):
        ridge = y0 * full + amp * np.sin(xx / per + k * 1.7) + amp * 0.5 * np.sin(xx / (per / 2.3) + k)
        inside = np.clip(yy - ridge + 0.5, 0, 1)          # soft edge, one pixel
        alpha = alpha + (1 - alpha) * inside * a
    rgba = np.zeros((h, w, 4), np.uint8)
    rgba[..., 0], rgba[..., 1], rgba[..., 2] = 18, 10, 24
    rgba[..., 3] = (alpha * 255).astype(np.uint8)
    Image.fromarray(rgba, "RGBA").save(os.path.join(OUT, "tex_hills.png"), optimize=True)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    grain()
    bubbles()
    hills()
    for f in sorted(os.listdir(OUT)):
        if f.startswith("tex_"):
            print(f, os.path.getsize(os.path.join(OUT, f)) // 1024, "KB")
