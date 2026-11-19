#!/usr/bin/env python3
"""Обрабатывает свежий ИИ-арт из tools/assets_src/ в tools/pack_assets/icons64/.

Лист иконок (2x2, зелёный фон) режется на четыре иконки, панели классов
приводятся к 320x80. Зелёный фон вырезается тем же способом, что и в
tools/build_hud_assets.py (key_out_green).

Запуск (нужен Pillow):
    /tmp/venv/bin/python tools/process_new_art.py
"""
from __future__ import annotations

from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "tools" / "assets_src"
ICONS = ROOT / "tools" / "pack_assets" / "icons64"

PANEL_SIZE = (320, 80)   # панель класса целиком (два глифа по 160x80)
ICON_SIZE = 64           # исходник иконки в icons64


def key_out_green(image: Image.Image) -> Image.Image:
    """Делает зелёный фон прозрачным (копия из build_hud_assets.py)."""
    image = image.convert("RGBA")
    cleaned = []
    for r, g, b, _a in list(image.getdata()):
        dominance = g - max(r, b)
        if dominance <= 10:
            cleaned.append((r, g, b, 255))
            continue
        if dominance >= 110:
            cleaned.append((0, 0, 0, 0))
            continue
        alpha = 1.0 - (dominance - 10) / 100.0
        tint = max(0, g - max(r, b)) * 0.5
        r = min(255, int(round(r + tint * 0.15)))
        b = min(255, int(round(b + tint * 0.15)))
        g = int(round(max(0, g - tint)))
        cleaned.append((r, g, b, int(round(alpha * 255))))
    image.putdata(cleaned)
    return image


def opaque_bbox(image: Image.Image, threshold: int = 128):
    alpha = image.getchannel("A").point(lambda v: 255 if v >= threshold else 0)
    return alpha.getbbox()


def to_icon(image: Image.Image, size: int) -> Image.Image:
    """Вырезает иконку, центрирует и уменьшает до квадрата size x size."""
    image = key_out_green(image)
    box = opaque_bbox(image)
    if box is not None:
        image = image.crop(box)
    side = max(image.width, image.height)
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.paste(image, ((side - image.width) // 2, (side - image.height) // 2))
    small = canvas.resize((size, size), Image.LANCZOS)
    small.putdata([(r, g, b, 255 if a >= 110 else 0)
                   for (r, g, b, a) in list(small.getdata())])
    return small


def slice_sheet() -> None:
    """Лист 2x2 -> heart, shield, scroll, level_shield."""
    sheet = Image.open(SRC / "icons_sheet.png").convert("RGBA")
    w, h = sheet.size
    # (имя, квадрант): 0=левый верх, 1=правый верх, 2=левый низ, 3=правый низ
    names = ["heart", "shield", "scroll", "level_shield"]
    quads = [(0, 0, w // 2, h // 2), (w // 2, 0, w, h // 2),
             (0, h // 2, w // 2, h), (w // 2, h // 2, w, h)]
    for name, box in zip(names, quads):
        icon = to_icon(sheet.crop(box), ICON_SIZE)
        icon.save(ICONS / f"{name}.png", optimize=True)
        print(f"icons64/{name}.png: {icon.size[0]}x{icon.size[1]}")


def process_panels() -> None:
    """Панели классов: зелёный фон -> 320x80."""
    classes = ["warrior", "mage", "archer", "paladin",
               "farmer", "viking", "necromancer", "spearman"]
    for name in classes:
        path = SRC / f"panel_{name}.png"
        if not path.is_file():
            print(f"нет {path} — пропускаю")
            continue
        image = key_out_green(Image.open(path))
        box = opaque_bbox(image)
        if box is not None:
            image = image.crop(box)
        image = image.resize(PANEL_SIZE, Image.LANCZOS)
        image.putdata([(r, g, b, 255 if a >= 110 else 0)
                       for (r, g, b, a) in list(image.getdata())])
        image.save(ICONS / f"panel_{name}.png", optimize=True)
        print(f"icons64/panel_{name}.png: {image.size[0]}x{image.size[1]}")


def main() -> int:
    ICONS.mkdir(parents=True, exist_ok=True)
    slice_sheet()
    process_panels()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
