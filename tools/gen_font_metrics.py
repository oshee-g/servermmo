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

from gen_resourcepack import (ABILITY_CHARS, CLAN_CHARS, DIGIT_CHARS, FADE_STEPS,
                              GEAR_CHARS, HOTBAR_DIGIT_CHARS, ICON_CHARS,
                              LEVEL_CHARS, LETTER_CHARS, MONEY_CHARS, PANEL_CHARS,
                              PANEL_VARIANTS, TEXT_CHARS, TEXT_VARIANTS, TEXT_COLS,
                              TEXT_BASE, TEXT_SMALL_BASE, TEXT_SMALL_VARIANT_BASE,
                              chars, LEVEL_COLS, PANEL_COLS, fade_chars,
                              hotbar_fade_chars, build_space_advances)
from png_io import glyph_advance, glyph_advance_grid, png_rgba

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "tools" / "pack_assets"
OUT = (ROOT / "plugins" / "servermmo-hud" / "src" / "main" / "resources"
       / "font-metrics.properties")

def _fade_chars(step: int) -> str:
    base = 0xE200 + step * 16
    return "".join(chr(base + i) for i in range(12))


# (файл, символы, столбцов в сетке, высота отрисовки провайдера). Значки
# панели идут ячейкой 24x24, но рисуются 16 px (масштаб 16/24), значки
# хотбара — 20x20 (height равен ячейке, масштаб 1:1), панели — 96 px из
# ячеек 96 px (масштаб 1:1). Ширина глифа масштабируется как у игры.
ATLASES = (
    ("hud_digits.png", DIGIT_CHARS, len(DIGIT_CHARS), 8),
    ("hud_digits.png", HOTBAR_DIGIT_CHARS, len(HOTBAR_DIGIT_CHARS), 8),
    ("hud_icons.png", ICON_CHARS, len(ICON_CHARS), 8),
    ("hud_letters.png", LETTER_CHARS, len(LETTER_CHARS), 8),
    *(("hud_fade%d.png" % step, _fade_chars(step), len(_fade_chars(step)), 8)
      for step in range(FADE_STEPS)),
    *(("hud_fade%d.png" % step, hotbar_fade_chars(step),
       len(hotbar_fade_chars(step)), 8) for step in range(FADE_STEPS)),
    ("hud_gear.png", GEAR_CHARS, len(GEAR_CHARS), 20),
    ("hud_money.png", MONEY_CHARS, len(MONEY_CHARS), 14),
    ("hud_ability.png", ABILITY_CHARS, len(ABILITY_CHARS), 20),
    ("hud_level.png", LEVEL_CHARS, LEVEL_COLS, 14),
    ("hud_clan.png", CLAN_CHARS, LEVEL_COLS, 14),
    # Текст панели своими глифами (база + варианты для /hud texty).
    ("hud_text.png", chars(TEXT_BASE, len(TEXT_CHARS)), TEXT_COLS, 16),
    *(("hud_text.png", variant, TEXT_COLS, 16)
      for variant, _ in TEXT_VARIANTS),
    ("hud_text_small.png", chars(TEXT_SMALL_BASE, len(TEXT_CHARS)), TEXT_COLS, 16),
    *(("hud_text_small.png",
       chars(TEXT_SMALL_VARIANT_BASE + v * len(TEXT_CHARS), len(TEXT_CHARS)),
       TEXT_COLS, 16) for v, _ in enumerate(TEXT_VARIANTS)),
    # Панель — 21 вариант с разной высотой (команда /hud offsety), ширина у
    # всех одинаковая, но в таблице нужны все коды.
    *(("hud_panel.png", variant, PANEL_COLS, 96) for variant in PANEL_VARIANTS),
)


def collect() -> dict[str, int]:
    metrics: dict[str, int] = {}
    for name, chars, cols, draw_h in ATLASES:
        width, height, pixels = png_rgba((ASSETS / name).read_bytes())
        rows = (len(chars) + cols - 1) // cols
        cell_w, cell_h = width // cols, height // rows
        scale = draw_h / cell_h
        for index, char in enumerate(chars):
            col, row = index % cols, index // cols
            metrics[char] = glyph_advance_grid(width, height, pixels,
                                               cell_w, cell_h, col, row, scale)
    # Пробелы и сдвиги живут в space-провайдере, а не в PNG: их ширины
    # берём из того же описания, что кладётся в hud.json. Пишем ПОСЛЕ атласов,
    # чтобы advance пробела (4) не затёр пустым глифом hud_digits (ширина 1).
    for char, advance in build_space_advances().items():
        metrics[char] = int(advance)
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
