#!/usr/bin/env python3
"""Готовит текстуры HUD для ресурспака.

Иконки и панель нарисованы ИИ (`tools/assets_src/`, фон — чистый зелёный),
здесь они вырезаются, уменьшаются до пиксель-арта и складываются в
`tools/pack_assets/` (эти PNG коммитятся). Цифры рисуются кодом: ровный ряд
из десяти цифр генератор картинок не выдаёт.

ВАЖНО про размеры. В шрифте Minecraft один символ — это квадратная ячейка
размером `height`x`height` пикселей, и видимая ширина глифа считается по
правому непрозрачному пикселю (+1 пиксель отступа). Поэтому все наши глифы
сделаны как у ванильного шрифта: ячейка 8x8, height=8, отрисовка 1:1.
Полоса 160x14 одним символом не работает: клиент берёт из неё только первые
14 пикселей — именно поэтому HUD раньше не появлялся.

`tools/gen_resourcepack.py` (без сторонних библиотек) раскладывает готовые
PNG по структуре ресурспака и собирает zip.

Запуск (нужен Pillow):

    python3 -m venv /tmp/venv && /tmp/venv/bin/pip install pillow
    /tmp/venv/bin/python tools/build_hud_assets.py
"""
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "tools" / "assets_src"
OUT = ROOT / "tools" / "pack_assets"

CELL = 8            # как у ванильного шрифта: ячейка 8x8, height=8

CHROMA = (0, 255, 0)

GOLD = (255, 214, 92, 255)
GOLD_HI = (255, 246, 196, 255)
GOLD_DARK = (120, 74, 16, 255)
SHADOW = (28, 18, 6, 255)


# ---------------------------------------------------------------------------
# Вырезание фона
# ---------------------------------------------------------------------------
def key_out_green(image: Image.Image) -> Image.Image:
    """Делает зелёный фон прозрачным.

    Зелёный канал у арта всегда ниже, чем у фона, поэтому пиксель считается
    фоном, когда зелёный заметно выше красного и синего. Края сглаживаются,
    а зелёный ореол убирается подмешиванием соседнего цвета.
    """
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
    alpha = image.getchannel("A").point(lambda value: 255 if value >= threshold else 0)
    return alpha.getbbox()


def to_icon(path: Path, size: int) -> Image.Image:
    """Вырезает иконку, центрирует и уменьшает до пиксель-арта."""
    image = key_out_green(Image.open(path))
    box = opaque_bbox(image)
    if box is not None:
        image = image.crop(box)
    side = max(image.width, image.height)
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.paste(image, ((side - image.width) // 2, (side - image.height) // 2))
    small = canvas.resize((size, size), Image.LANCZOS)
    # жёсткая альфа: у пиксель-арта не бывает полупрозрачной каймы
    small.putdata([(r, g, b, 255 if a >= 110 else 0) for (r, g, b, a) in list(small.getdata())])
    return small


# ---------------------------------------------------------------------------
# Атласы глифов
# ---------------------------------------------------------------------------
DIGITS_5x7 = {
    "0": ["01110", "10001", "10011", "10101", "11001", "10001", "01110"],
    "1": ["00010", "00110", "01010", "00010", "00010", "00010", "01110"],
    "2": ["01110", "10001", "00001", "00010", "00100", "01000", "11111"],
    "3": ["11111", "00010", "00100", "00010", "00001", "10001", "01110"],
    "4": ["00010", "00110", "01010", "10010", "11111", "00010", "00010"],
    "5": ["11111", "10000", "11110", "00001", "00001", "10001", "01110"],
    "6": ["00110", "01000", "10000", "11110", "10001", "10001", "01110"],
    "7": ["11111", "00001", "00010", "00100", "01000", "01000", "01000"],
    "8": ["01110", "10001", "10001", "01110", "10001", "10001", "01110"],
    "9": ["01110", "10001", "10001", "01111", "00001", "00010", "01100"],
    "/": ["00001", "00001", "00010", "00100", "01000", "10000", "10000"],
    " ": ["00000", "00000", "00000", "00000", "00000", "00000", "00000"],
}


def digits_atlas() -> Image.Image:
    """Цифры: ячейка 8x8, цифра 5x7 с тёмной обводкой — как в ванильном шрифте."""
    chars = "0123456789/ "
    image = Image.new("RGBA", (CELL * len(chars), CELL), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    for index, char in enumerate(chars):
        x0 = index * CELL
        pattern = DIGITS_5x7[char]
        ink = {(x, y) for y, line in enumerate(pattern)
               for x, bit in enumerate(line) if bit == "1"}
        if not ink:
            continue
        # тень вправо-вниз: не заливает внутренние просветы цифр (у нуля, 8)
        for (x, y) in ink:
            if (x + 1, y + 1) not in ink:
                draw.point((x0 + 1 + x + 1, y + 1), fill=SHADOW)
        # заливка: сверху блик, ниже золото
        for (x, y) in ink:
            draw.point((x0 + 1 + x, y), fill=GOLD_HI if (x, y - 1) not in ink else GOLD)
    return image


def icons_atlas(icons: list[Image.Image]) -> Image.Image:
    """Иконки по ячейкам 8x8: [сердце, меч, щит, кристалл]."""
    image = Image.new("RGBA", (CELL * len(icons), CELL), (0, 0, 0, 0))
    for index, icon in enumerate(icons):
        image.alpha_composite(icon, (index * CELL, 0))
    return image


def heart_atlas(heart: Image.Image) -> Image.Image:
    """Одно сердечко 8x8 для подписей над мобами."""
    image = Image.new("RGBA", (CELL, CELL), (0, 0, 0, 0))
    image.alpha_composite(heart, (0, 0))
    return image


def pack_icon(bar: Image.Image, heart: Image.Image) -> Image.Image:
    """pack.png 64x64: деревянная панель + сердце."""
    panel = bar.resize((64, 64), Image.LANCZOS)
    draw = ImageDraw.Draw(panel)
    draw.rectangle([0, 0, 63, 63], outline=(20, 12, 6, 255))
    big = heart.resize((40, 40), Image.LANCZOS)
    panel.alpha_composite(big, (12, 12))
    return panel


def preview(digits: Image.Image, icons: Image.Image) -> Image.Image:
    """Как выглядит HUD: строки «иконка + число»."""
    rows = [("0", "10"), ("1", "1"), ("2", "1"), ("3", "0/200")]
    scale = 6
    width, height = 120, 8 * len(rows) + 2
    canvas = Image.new("RGBA", (width, height), (26, 26, 30, 255))
    y = 1
    for icon_index, value in rows:
        icon_index = int(icon_index)
        icon = icons.crop((icon_index * CELL, 0, icon_index * CELL + CELL, CELL))
        canvas.alpha_composite(icon, (0, y))
        x = CELL + 3
        for char in value:
            offset = "0123456789/ ".index(char) * CELL
            glyph = digits.crop((offset, 0, offset + CELL, CELL))
            canvas.alpha_composite(glyph, (x, y))
            x += CELL - 2
        y += CELL
    return canvas.resize((canvas.width * scale, canvas.height * scale), Image.NEAREST)


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    bar = Image.open(SRC / "bar.png").convert("RGBA")

    icons = [to_icon(SRC / f"{name}.png", CELL)
             for name in ("heart", "sword", "level", "orb")]

    digits = digits_atlas()
    digits.save(OUT / "hud_digits.png", optimize=True)
    print(f"hud_digits.png: {digits.width}x{digits.height}")

    atlas = icons_atlas(icons)
    atlas.save(OUT / "hud_icons.png", optimize=True)
    print(f"hud_icons.png: {atlas.width}x{atlas.height} (сердце, меч, щит, кристалл)")

    heart_atlas(icons[0]).save(OUT / "hud_heart.png", optimize=True)
    print("hud_heart.png: 8x8 (для подписи над мобом)")

    pack_icon(bar, icons[0]).save(OUT / "pack_icon.png", optimize=True)
    print("pack_icon.png: 64x64")

    preview(digits, atlas).save(ROOT / "resourcepack" / "hud-preview.png", optimize=True)
    print("resourcepack/hud-preview.png")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
