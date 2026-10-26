#!/usr/bin/env python3
"""Builds the adaptive-icon foregrounds for Oguri's Alarms 1.2 and renders a
simulated Pixel launcher preview (circle + squircle masks).

Foreground: v4 artwork (build/icon-master.png, the flood-filled master)
centered at ~68% of the 108dp adaptive canvas on transparency, its square
edges softly alpha-feathered so it melts into the #260C5C background layer.
"""
from PIL import Image, ImageDraw, ImageFilter
import math
import os

APP = os.path.expanduser("~/workspace/alarm-app")
BG = (0x26, 0x0C, 0x5C, 255)
FRACTION = 0.68  # artwork size as a fraction of the foreground canvas

master = Image.open(os.path.join(APP, "build", "icon-master.png")).convert("RGBA")

# --- build one high-res foreground (1728px = 4x the xxxhdpi 432) ----------
SS = 4
canvas_hi = 432 * SS
art_hi = int(round(canvas_hi * FRACTION))
feather = max(8, int(art_hi * 0.12))

art = master.resize((art_hi, art_hi), Image.LANCZOS)
mask = Image.new("L", (art_hi, art_hi), 0)
d = ImageDraw.Draw(mask)
inset = feather // 2
d.rectangle([inset, inset, art_hi - inset - 1, art_hi - inset - 1], fill=255)
mask = mask.filter(ImageFilter.GaussianBlur(feather / 2.0))
art.putalpha(mask)

fg_hi = Image.new("RGBA", (canvas_hi, canvas_hi), (0, 0, 0, 0))
off = (canvas_hi - art_hi) // 2
fg_hi.alpha_composite(art, (off, off))

# --- export per-density foregrounds ---------------------------------------
DENSITIES = {
    "mipmap-mdpi": 108,
    "mipmap-hdpi": 162,
    "mipmap-xhdpi": 216,
    "mipmap-xxhdpi": 324,
    "mipmap-xxxhdpi": 432,
}
for folder, size in DENSITIES.items():
    out_dir = os.path.join(APP, "res", folder)
    os.makedirs(out_dir, exist_ok=True)
    fg = fg_hi.resize((size, size), Image.LANCZOS)
    fg.save(os.path.join(out_dir, "ic_launcher_foreground.png"))
    print("wrote", folder, size)

# --- simulated Pixel preview at 192px: circle + squircle -------------------
PREVIEW = 192


def apply_mask(img, kind):
    m = Image.new("L", (PREVIEW * SS, PREVIEW * SS), 0)
    px = m.load()
    s = PREVIEW * SS
    for y in range(s):
        for x in range(s):
            nx = (x + 0.5) / s * 2 - 1
            ny = (y + 0.5) / s * 2 - 1
            if kind == "circle":
                inside = nx * nx + ny * ny <= 1.0
            else:  # squircle, superellipse n=4
                inside = abs(nx) ** 4 + abs(ny) ** 4 <= 1.0
            px[x, y] = 255 if inside else 0
    m = m.resize((PREVIEW, PREVIEW), Image.LANCZOS)
    out = img.copy()
    out.putalpha(m)
    return out


def render(kind):
    bg = Image.new("RGBA", (PREVIEW, PREVIEW), BG)
    fg = fg_hi.resize((PREVIEW, PREVIEW), Image.LANCZOS)
    comp = Image.alpha_composite(bg, fg)
    return apply_mask(comp, kind)


circle = render("circle")
squircle = render("squircle")

preview = Image.new("RGBA", (PREVIEW * 2 + 24, PREVIEW + 16), (24, 24, 32, 255))
preview.alpha_composite(circle, (8, 8))
preview.alpha_composite(squircle, (16 + PREVIEW, 8))
preview.save(os.path.join(APP, "icon-preview-1.2.png"))
print("wrote icon-preview-1.2.png")
