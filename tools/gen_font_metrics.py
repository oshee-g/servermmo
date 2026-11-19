#!/usr/bin/env python3
"""Считает ширину глифов HUD-шрифта и пишет таблицу для плагина.

Плагин собирает панель из глифов пака и должен знать, сколько пикселей
занимает каждый символ: иначе строки разной длины встанут по центру по
разному и панель будет «рваной». Ширина глифа в bitmap-провайдере — это
последний непрозрачный пиксель + 1, поэтому считаем её по самим PNG.
Пробельные глифы и сдвиги курсора описаны в space-провайдере, их ширины
берём оттуда же: без них плагин посчитает любой отступ как 7 px и сдвинет
хотбар.

Результат: ``plugins/servermmo-hud/src/main/resources/font-metrics.properties``.
CI (``tools/check_pack.py``) сверяет этот файл с готовым паком и падает,
если они разошлись.

Запуск:  python3 tools/gen_font_metrics.py
"""
from __future__ import annotations

from pathlib import Path

from gen_resourcepack import (ABILITY_CHARS, DIGIT_CHARS, FADE_STEPS, GEAR_CHARS,
                              HOTBAR_DIGIT_CHARS, ICON_CHARS, LEVEL_CHARS,
                              LETTER_CHARS, MONEY_CHARS, PANEL_CHARS, LEVEL_COLS,
                              PANEL_COLS, fade_chars, hotbar_fade_chars,
                              build_space_advances)
from png_io import glyph_advance, glyph_advance_grid, png_rgba

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "tools" / "pack_assets"
OUT = (ROOT / "plugins" / "servermmo-hud" / "src" / "main" / "resources"
       / "font-metrics.properties")

def _fade_chars(step: int) -> str:
    base = 0xE200 + step * 16
    return "".join(chr(base + i) for i in range(12))


# (файл, символы, столбцов в сетке). Значки панели идут ячейкой 32x32, значки
# хотбара — 20x20 (height равен ячейке, масштаб 1:1), панели — глифами 160x80,
# поэтому считать ширину надо по своей сетке, а не «ширина / число символов».
ATLASES = (
    ("hud_digits.png", DIGIT_CHARS, len(DIGIT_CHARS)),
    ("hud_digits.png", HOTBAR_DIGIT_CHARS, len(HOTBAR_DIGIT_CHARS)),
    ("hud_icons.png", ICON_CHARS, len(ICON_CHARS)),
    ("hud_letters.png", LETTER_CHARS, len(LETTER_CHARS)),
    *(("hud_fade%d.png" % step, _fade_chars(step), len(_fade_chars(step)))
      for step in range(FADE_STEPS)),
    *(("hud_fade%d.png" % step, hotbar_fade_chars(step),
       len(hotbar_fade_chars(step))) for step in range(FADE_STEPS)),
    ("hud_gear.png", GEAR_CHARS, len(GEAR_CHARS)),
    ("hud_money.png", MONEY_CHARS, len(MONEY_CHARS)),
    ("hud_ability.png", ABILITY_CHARS, len(ABILITY_CHARS)),
    ("hud_level.png", LEVEL_CHARS, LEVEL_COLS),
    ("hud_panel.png", PANEL_CHARS, PANEL_COLS),
)


def collect() -> dict[str, int]:
    metrics: dict[str, int] = {}
    # Пробелы и сдвиги живут в space-провайдере, а не в PNG: их ширины
    # берём из того же описания, что кладётся в hud.json.
    for char, advance in build_space_advances().items():
        metrics[char] = int(advance)
    for name, chars, cols in ATLASES:
        width, height, pixels = png_rgba((ASSETS / name).read_bytes())
        rows = (len(chars) + cols - 1) // cols
        cell_w, cell_h = width // cols, height // rows
        for index, char in enumerate(chars):
            col, row = index % cols, index // cols
            metrics[char] = glyph_advance_grid(width, height, pixels,
                                               cell_w, cell_h, col, row)
    return metrics


def main() -> int:
    metrics = collect()
    line = ",".join(f"{ord(char):x}:{advance}" for char, advance in sorted(metrics.items()))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(
        "# Сгенерировано tools/gen_font_metrics.py — не править руками.\n"
        "# Ширина каждого глифа шрифта servermmo:hud в пикселях: code:advance.\n"
        f"advances={line}\n",
        encoding="utf-8")
    print("font-metrics.properties:", ", ".join(
        f"{char if char.isprintable() and char != ' ' else hex(ord(char))}={advance}"
        for char, advance in sorted(metrics.items())))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
