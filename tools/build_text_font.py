#!/usr/bin/env python3
"""Генерирует битмап-шрифт панели (hud_text.png) из DejaVuSans.

Текст панели раньше рисовался ванильным шрифтом клиента — его базовую
линию сервер сдвинуть не может, поэтому текст нельзя двигать. Свои глифы
имеют высоту (ascent) в паке, и текст становится двигаемым (команда
/texty). Набор символов: кириллица, латиница, цифры и знаки для ников,
классов, «Без клана», названий кланов и дробей опыта.
"""
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "tools" / "pack_assets" / "hud_text.png"
FONT_PATH = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"

# Кириллица, латиница, цифры, знаки. Пробел — space-провайдер, не глиф.
TEXT_CHARS = (
    "АБВГДЕЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ"
    "абвгдежзийклмнопрстуфхцчшщъыьэюя"
    "Ёё"
    "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    "abcdefghijklmnopqrstuvwxyz"
    "0123456789"
    "_/-·.,!?:;()[]{}+=*#@%&|<>"
    "—–«»№°±×÷§`'\"\\~^$"
)
TEXT_COLS = 19
CELL = 16          # ячейка атласа, px
BASELINE = 9       # базовая линия внутри ячейки (от верха)
SIZE = 11          # кегль DejaVuSans: заглавные около 8 px


def render_char(font: ImageFont.FreeTypeFont, ch: str) -> Image.Image:
    img = Image.new("L", (CELL * 2, CELL * 2), 0)
    draw = ImageDraw.Draw(img)
    # Буквы ставим у левого края ячейки (anchor "ls"), а не по центру:
    # ширина глифа считается по последнему непрозрачному столбцу, поэтому
    # центровка давала 5-7 px просветов между буквами. Слева оставляем 1 px.
    draw.text((1, BASELINE), ch, font=font, fill=255, anchor="ls")
    img = img.crop((CELL // 2 - CELL // 2, 0, CELL // 2 + CELL // 2, CELL))
    return img.point(lambda p: 255 if p > 128 else 0)


def main() -> int:
    font = ImageFont.truetype(FONT_PATH, SIZE)
    rows = (len(TEXT_CHARS) + TEXT_COLS - 1) // TEXT_COLS
    atlas = Image.new("RGBA", (TEXT_COLS * CELL, rows * CELL), (0, 0, 0, 0))
    for index, ch in enumerate(TEXT_CHARS):
        alpha = render_char(font, ch)
        # Белый глиф: цвет задаёт стиль текста, альфа — форма.
        white = Image.new("RGBA", alpha.size, (255, 255, 255, 255))
        white.putalpha(alpha)
        col, row = index % TEXT_COLS, index // TEXT_COLS
        atlas.alpha_composite(white, (col * CELL, row * CELL))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    atlas.save(OUT, optimize=True)
    print(f"hud_text.png: {atlas.width}x{atlas.height}, глифов {len(TEXT_CHARS)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
