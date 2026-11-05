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
INK_H = 7           # высота заглавных букв и цифр
# У ванильного ascii.png (ячейка 8x8, height 8, ascent 7) чернила заглавных и
# цифр лежат в строках 1..7, то есть нижняя строка совпадает с базовой линией.
# Наши глифы обязаны попадать туда же, иначе в неймтеге, где рядом ванильное
# имя моба, значки «прыгают» на пиксель вверх.
INK_TOP = 1

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
    "1": ["00100", "01100", "00100", "00100", "00100", "00100", "01110"],
    "2": ["01110", "10001", "00001", "00010", "00100", "01000", "11111"],
    "3": ["11111", "00010", "00100", "00010", "00001", "10001", "01110"],
    "4": ["00010", "00110", "01010", "10010", "11111", "00010", "00010"],
    "5": ["11111", "10000", "11110", "00001", "00001", "10001", "01110"],
    "6": ["00110", "01000", "10000", "11110", "10001", "10001", "01110"],
    "7": ["11111", "00001", "00010", "00100", "01000", "01000", "01000"],
    "8": ["01110", "10001", "10001", "01110", "10001", "10001", "01110"],
    "9": ["01110", "10001", "10001", "01111", "00001", "00010", "01100"],
    "/": ["00001", "00011", "00010", "00100", "01000", "11000", "10000"],
    " ": ["00000", "00000", "00000", "00000", "00000", "00000", "00000"],
}

# Буквы для значков LVL и XP: та же сетка 5x7, что у цифр.
LETTERS_5x7 = {
    "L": ["10000", "10000", "10000", "10000", "10000", "10000", "11111"],
    "V": ["10001", "10001", "10001", "10001", "10001", "01010", "00100"],
    "X": ["10001", "10001", "01010", "00100", "01010", "10001", "10001"],
    "P": ["11110", "10001", "10001", "11110", "10000", "10000", "10000"],
}
LETTER_CHARS = "LVXP"

def glyph_run(patterns: list[list[str]]) -> Image.Image:
    """Атлас символов 5x7 в ячейках 8x8, все на одной базовой линии."""
    image = Image.new("RGBA", (CELL * len(patterns), CELL), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    for index, pattern in enumerate(patterns):
        x0 = index * CELL
        ink = {(x, y) for y, line in enumerate(pattern)
               for x, bit in enumerate(line) if bit == "1"}
        # Заливка одним цветом: посветка «верх/низ» на 5x7 превращала
        # буквы в мешанину (L и V читались как одна фигура).
        for (x, y) in ink:
            draw.point((x0 + 1 + x, y + INK_TOP), fill=GOLD)
    return image


def letters_atlas() -> Image.Image:
    """Буквы LVL и XP: та же сетка, что у цифр."""
    return glyph_run([LETTERS_5x7[ch] for ch in LETTER_CHARS])


def icons_atlas(icons: list[Image.Image]) -> Image.Image:
    """Иконки 7x7 по ячейкам 8x8: [сердце, меч, кристалл].

    Высота 7, а не 8: у ванильного шрифта заглавные буквы и цифры занимают
    ровно 7 строк ячейки. Иконки на 8 пикселей выступали над строкой, из-за
    чего подпись смотрелась криво.
    """
    image = Image.new("RGBA", (CELL * len(icons), CELL), (0, 0, 0, 0))
    for index, icon in enumerate(icons):
        image.alpha_composite(icon, (index * CELL, INK_TOP))
    return image


def digits_atlas() -> Image.Image:
    """Цифры и дробь: ячейка 8x8, символ 5x7 на общей базовой линии."""
    return glyph_run([DIGITS_5x7[ch] for ch in "0123456789/ "])


def pack_icon(bar: Image.Image, heart: Image.Image) -> Image.Image:
    """pack.png 64x64: деревянная панель + сердце."""
    panel = bar.resize((64, 64), Image.LANCZOS)
    draw = ImageDraw.Draw(panel)
    draw.rectangle([0, 0, 63, 63], outline=(20, 12, 6, 255))
    big = heart.resize((40, 40), Image.LANCZOS)
    panel.alpha_composite(big, (12, 12))
    return panel


def preview(digits: Image.Image, icons: Image.Image, letters: Image.Image) -> Image.Image:
    """Как выглядит HUD: иконка + число, и значки LVL / XP."""
    rows = [(icons, 0, "10"), (icons, 1, "1"), ("LVL", None, "1"), ("XP", None, "0/200")]
    scale = 6
    canvas = Image.new("RGBA", (120, CELL * len(rows) + 2), (26, 26, 30, 255))
    y = 1
    for icon, index, value in rows:
        x = 1
        if index is not None:
            canvas.alpha_composite(icon.crop((index * CELL, 0, (index + 1) * CELL, CELL)), (x, y))
            x += CELL
        else:
            for char in icon:
                offset = LETTER_CHARS.index(char) * CELL
                canvas.alpha_composite(letters.crop((offset, 0, offset + CELL, CELL)), (x, y))
                x += CELL - 1
        x += 2
        for char in value:
            offset = "0123456789/ ".index(char) * CELL
            canvas.alpha_composite(digits.crop((offset, 0, offset + CELL, CELL)), (x, y))
            x += CELL - 1
        y += CELL
    return canvas.resize((canvas.width * scale, canvas.height * scale), Image.NEAREST)


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    bar = Image.open(SRC / "bar.png").convert("RGBA")

    icons = [to_icon(SRC / f"{name}.png", INK_H)
             for name in ("heart", "sword", "orb")]

    digits = digits_atlas()
    digits.save(OUT / "hud_digits.png", optimize=True)
    print(f"hud_digits.png: {digits.width}x{digits.height}")

    letters = letters_atlas()
    letters.save(OUT / "hud_letters.png", optimize=True)
    print(f"hud_letters.png: {letters.width}x{letters.height} ({LETTER_CHARS})")

    atlas = icons_atlas(icons)
    atlas.save(OUT / "hud_icons.png", optimize=True)
    print(f"hud_icons.png: {atlas.width}x{atlas.height} (сердце, меч, кристалл)")

    # Иконка пака — отдельно от HUD-глифа: там нужен крупный значок 8x8,
    # иначе pack.png меняется при каждой правке размера глифов.
    pack_icon(bar, to_icon(SRC / "heart.png", CELL)).save(OUT / "pack_icon.png", optimize=True)
    print("pack_icon.png: 64x64")

    preview(digits, atlas, letters).save(OUT / "hud_preview.png", optimize=True)
    print("hud_preview.png (как выглядит HUD)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
