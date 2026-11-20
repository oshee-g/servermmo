#!/usr/bin/env python3
"""Генерирует обычный и компактный битмап-шрифт панели из DejaVuSans.

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
SIZE = 11          # обычный кегль DejaVuSans: заглавные около 8 px
SMALL_SIZE = 8     # компактный ник: меньше примерно на четверть
SMALL_OUT = OUT.with_name("hud_text_small.png")


def render_char(font: ImageFont.FreeTypeFont, ch: str) -> Image.Image:
    img = Image.new("L", (CELL * 2, CELL * 2), 0)
    draw = ImageDraw.Draw(img)
    # Буквы ставим у левого края ячейки (anchor "ls"), а не по центру:
    # ширина глифа считается по последнему непрозрачному столбцу, поэтому
    # центровка давала 5-7 px просветов между буквами. Слева оставляем 1 px.
    draw.text((1, BASELINE), ch, font=font, fill=255, anchor="ls")
    img = img.crop((CELL // 2 - CELL // 2, 0, CELL // 2 + CELL // 2, CELL))
    return img.point(lambda p: 255 if p > 128 else 0)


def build_atlas(size: int) -> Image.Image:
    font = ImageFont.truetype(FONT_PATH, size)
    rows = (len(TEXT_CHARS) + TEXT_COLS - 1) // TEXT_COLS
    atlas = Image.new("RGBA", (TEXT_COLS * CELL, rows * CELL), (0, 0, 0, 0))
    for index, ch in enumerate(TEXT_CHARS):
        alpha = render_char(font, ch)
        # Белый глиф: цвет задаёт стиль текста, альфа — форма.
        white = Image.new("RGBA", alpha.size, (255, 255, 255, 255))
        white.putalpha(alpha)
        col, row = index % TEXT_COLS, index // TEXT_COLS
        atlas.alpha_composite(white, (col * CELL, row * CELL))
    return atlas


def main() -> int:
    OUT.parent.mkdir(parents=True, exist_ok=True)
    for path, size in ((OUT, SIZE), (SMALL_OUT, SMALL_SIZE)):
        atlas = build_atlas(size)
        atlas.save(path, optimize=True)
        print(f"{path.name}: {atlas.width}x{atlas.height}, глифов {len(TEXT_CHARS)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
