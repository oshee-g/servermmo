#!/usr/bin/env python3
"""Extract the ten class HUD panels from hud_panels_all_10.png.

The source image is a 2-column by 5-row sheet on a black background. This
script flood-removes the connected near-black background, then writes the ten
transparent 256x96 panel source files consumed by build_icon_atlases.py.
Requires Pillow (the same environment as the other asset-generation tools).
"""
from __future__ import annotations

from collections import deque
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
SHEET = ROOT / "hud_panels_all_10.png"
OUT = ROOT / "tools" / "pack_assets" / "icons64"
NAMES = [
    ["archer", "blacksmith"],
    ["druid", "farmer"],
    ["mage", "necromancer"],
    ["paladin", "vampire"],
    ["viking", "warrior"],
]
CELL_W, CELL_H = 504, 208
TARGET = (256, 96)
BG_LIMIT = 36


def remove_connected_black(image: Image.Image) -> Image.Image:
    """Make the connected black canvas transparent, retaining soft edge pixels."""
    rgba = image.convert("RGBA")
    pixels = list(rgba.getdata())
    width, height = rgba.size
    outside = bytearray(width * height)
    queue: deque[int] = deque()

    def is_background(i: int) -> bool:
        r, g, b, _ = pixels[i]
        return max(r, g, b) <= BG_LIMIT

    # Start flood fill at every border pixel. Four-way connectivity prevents
    # diagonal contact with the artwork from erasing its dark outlines.
    for x in range(width):
        for y in (0, height - 1):
            i = y * width + x
            if not outside[i] and is_background(i):
                outside[i] = 1
                queue.append(i)
    for y in range(height):
        for x in (0, width - 1):
            i = y * width + x
            if not outside[i] and is_background(i):
                outside[i] = 1
                queue.append(i)

    while queue:
        i = queue.popleft()
        x, y = i % width, i // width
        for j in ((i - 1 if x else -1), (i + 1 if x + 1 < width else -1),
                  (i - width if y else -1), (i + width if y + 1 < height else -1)):
            if j >= 0 and not outside[j] and is_background(j):
                outside[j] = 1
                queue.append(j)

    for i, (r, g, b, _a) in enumerate(pixels):
        if outside[i]:
            # The uploaded sheet was composited on black. Restore partial edge
            # coverage from the dark anti-aliased pixels to avoid a black halo.
            coverage = max(r, g, b)
            alpha = min(255, round(coverage * 255 / BG_LIMIT))
            pixels[i] = (min(255, round(r * 255 / max(1, alpha))),
                         min(255, round(g * 255 / max(1, alpha))),
                         min(255, round(b * 255 / max(1, alpha))), alpha)
    rgba.putdata(pixels)
    return rgba


def main() -> None:
    if not SHEET.is_file():
        raise SystemExit(f"Missing panel sheet: {SHEET}")
    sheet = Image.open(SHEET).convert("RGB")
    expected = (CELL_W * 2, CELL_H * 5)
    if sheet.size != expected:
        raise SystemExit(f"Expected sheet size {expected}, got {sheet.size}")

    for row, names in enumerate(NAMES):
        for col, name in enumerate(names):
            cell = sheet.crop((col * CELL_W, row * CELL_H,
                               (col + 1) * CELL_W, (row + 1) * CELL_H))
            keyed = remove_connected_black(cell)
            bbox = keyed.getchannel("A").getbbox()
            if bbox is None:
                raise SystemExit(f"No art found for panel {name}")
            panel = keyed.crop(bbox).resize(TARGET, Image.Resampling.LANCZOS)
            panel.save(OUT / f"panel_{name}.png", optimize=True)
            print(f"panel_{name}.png: {panel.width}x{panel.height}, source bounds {bbox}")


if __name__ == "__main__":
    main()
