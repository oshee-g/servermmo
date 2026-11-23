#!/usr/bin/env python3
"""Считает ширину глифов HUD-шрифта для Java-плагина.

Все варианты вертикального положения используют один и тот же арт, поэтому
метрики измеряются по исходным PNG и копируются на соответствующие PUA-коды.
"""
from __future__ import annotations

from pathlib import Path

from gen_resourcepack import (
    ABILITY_CHARS, CLAN_CHARS, DIGIT_CHARS, FADE_STEPS, GEAR_CHARS,
    HOTBAR_CENTER_DIGIT_BASE, HOTBAR_CENTER_DIGIT_COUNT,
    HOTBAR_CENTER_DARK_BASE, HOTBAR_CENTER_HEIGHT, HOTBAR_CENTER_START_INDEX,
    HOTBAR_DIGIT_CHARS, HOTBAR_ENERGY_BASE, HOTBAR_HEIGHT,
    HOTBAR_LEFT_DIGIT_FADE_COUNT,
    HOTBAR_LEFT_DIGIT_OFFSET, HOTBAR_LEFT_GEAR_COUNT, HOTBAR_LEFT_GEAR_OFFSET,
    HOTBAR_LEFT_START_INDEX, HOTBAR_LEFT_VARIANT_GLYPH_COUNT,
    HOTBAR_RIGHT_START_INDEX,
    HOTBAR_RIGHT_VARIANT_GLYPH_COUNT, HOTBAR_VARIANT_HEIGHT,
    ICON_CHARS, ICON_HEIGHT,
    ICON_OFFSET_RANGE, INLINE_GLYPH_COUNT, INLINE_GLYPHS, INLINE_START_INDEX,
    LEVEL_CHARS, LEVEL_COLS, LETTER_CHARS, MONEY_CHARS, PANEL_CLASS_COUNT,
    PANEL_COLS, PANEL_OFFSET_RANGE, PANEL_START_INDEX,
    PANEL_VARIANT_GLYPH_COUNT, TEXT_CHARS, TEXT_COLS, TEXT_HEIGHT,
    INLINE_VARIANT_HEIGHT, PANEL_VARIANT_HEIGHT, TEXT_SMALL_START_INDEX,
    TEXT_START_INDEX, TEXT_VARIANT_HEIGHT, build_space_advances, chars,
    encode_rgba_png, fade_chars, select_atlas_cells, hotbar_center_atlas_data,
    hotbar_center_digits_atlas_data, hotbar_digits_fade_atlas_data,
    hotbar_icon_atlas_data,
    hotbar_fade_chars, padded_atlas,
    padded_digit_atlas, pua_chars, variant_index,
)
from png_io import glyph_advance_grid, png_rgba

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "tools" / "pack_assets"
OUT = (ROOT / "plugins" / "servermmo-hud" / "src" / "main" / "resources"
       / "font-metrics.properties")


def collect() -> dict[int, int]:
    """Return codepoint-to-advance metrics for every provider glyph."""
    metrics: dict[int, int] = {}
    source_cache: dict[str, bytes] = {}
    advance_cache: dict[tuple[str, int, int, int, int], list[int]] = {}

    def add_atlas(name: str, glyphs: str, cols: int, draw_h: int,
                  padded_cell_height: int = 0, source_data: bytes | None = None) -> None:
        if name not in source_cache:
            if source_data is not None:
                source_cache[name] = source_data
            elif name == "hud_digits_inline.png":
                width, height, pixels = padded_digit_atlas(
                    (ASSETS / "hud_digits.png").read_bytes())
                source_cache[name] = encode_rgba_png(width, height, pixels)
            elif name == "hud_digits_basic.png":
                width, height, pixels = select_atlas_cells(
                    (ASSETS / "hud_digits.png").read_bytes(), len(DIGIT_CHARS),
                    tuple(range(12)))
                source_cache[name] = encode_rgba_png(width, height, pixels)
            else:
                source_cache[name] = (ASSETS / name).read_bytes()
        rows = (len(glyphs) + cols - 1) // cols
        cache_key = (name, cols, rows, draw_h, padded_cell_height)
        if cache_key not in advance_cache:
            if padded_cell_height:
                width, height, pixels = padded_atlas(
                    source_cache[name], cols, rows, padded_cell_height, draw_h)
                cell_w, cell_h = width // cols, height // rows
                scale = 1.0
            else:
                width, height, pixels = png_rgba(source_cache[name])
                cell_w, cell_h = width // cols, height // rows
                scale = draw_h / cell_h
            advance_cache[cache_key] = [
                glyph_advance_grid(width, height, pixels, cell_w, cell_h,
                                   index % cols, index // cols, scale)
                for index in range(rows * cols)
            ]
        advances = advance_cache[cache_key]
        for index, char in enumerate(glyphs):
            metrics[ord(char)] = advances[index]

    atlases = (
        ("hud_digits.png", DIGIT_CHARS, len(DIGIT_CHARS), 8),
        ("hud_digits_basic.png", HOTBAR_DIGIT_CHARS, len(HOTBAR_DIGIT_CHARS), 8),
        ("hud_icons.png", ICON_CHARS, len(ICON_CHARS), 8),
        ("hud_letters.png", LETTER_CHARS, len(LETTER_CHARS), 8),
        *((f"hud_fade{step}.png", fade_chars(step), 12, 8)
          for step in range(FADE_STEPS)),
        *((f"hud_fade{step}.png", hotbar_fade_chars(step), 12, 8)
          for step in range(FADE_STEPS)),
        ("hud_gear.png", GEAR_CHARS, len(GEAR_CHARS), HOTBAR_HEIGHT),
        ("hud_money.png", MONEY_CHARS, len(MONEY_CHARS), ICON_HEIGHT),
        ("hud_ability.png", ABILITY_CHARS, 8, HOTBAR_HEIGHT),
        ("hud_level.png", LEVEL_CHARS, LEVEL_COLS, ICON_HEIGHT),
        ("hud_clan.png", CLAN_CHARS, LEVEL_COLS, ICON_HEIGHT),
    )
    for name, glyphs, cols, draw_h in atlases:
        add_atlas(name, glyphs, cols, draw_h)

    # Inline digits, currencies and level/clan emblems follow /hud texty.
    group_offset = 0
    for stem, source_chars, cols, _rows, draw_h, _ascent in INLINE_GLYPHS:
        for offset in range(-ICON_OFFSET_RANGE, ICON_OFFSET_RANGE + 1):
            start = INLINE_START_INDEX + variant_index(offset) * INLINE_GLYPH_COUNT + group_offset
            add_atlas(f"{stem}.png", pua_chars(start, len(source_chars)), cols,
                      draw_h, INLINE_VARIANT_HEIGHT)
        group_offset += len(source_chars)

    # Regular and compact text each have a full set of 201 variants.
    for offset in range(-ICON_OFFSET_RANGE, ICON_OFFSET_RANGE + 1):
        variant = variant_index(offset)
        add_atlas("hud_text.png",
                  pua_chars(TEXT_START_INDEX + variant * len(TEXT_CHARS), len(TEXT_CHARS)),
                  TEXT_COLS, TEXT_HEIGHT, TEXT_VARIANT_HEIGHT)
        add_atlas("hud_text_small.png",
                  pua_chars(TEXT_SMALL_START_INDEX + variant * len(TEXT_CHARS), len(TEXT_CHARS)),
                  TEXT_COLS, TEXT_HEIGHT, TEXT_VARIANT_HEIGHT)

    # Each panel variant is the same atlas with a different ascent.
    panel_width = PANEL_CLASS_COUNT
    for offset in range(-PANEL_OFFSET_RANGE, PANEL_OFFSET_RANGE + 1):
        start = PANEL_START_INDEX + variant_index(offset) * PANEL_VARIANT_GLYPH_COUNT
        panel_glyphs = pua_chars(start, panel_width)
        add_atlas("hud_panel.png", panel_glyphs, PANEL_COLS,
                  96, PANEL_VARIANT_HEIGHT)
        if offset == 0:
            advances = [metrics[ord(char)] for char in panel_glyphs]
            if set(advances) != {256}:
                raise ValueError(
                    "панели классов должны иметь одинаковую ширину advance=256: "
                    f"{advances}")

    # One icon atlas serves both sides; the codepoint variant still applies
    # independently to each side when HudText chooses its glyphs.
    icon_source = hotbar_icon_atlas_data()
    digit_fade_source = hotbar_digits_fade_atlas_data()
    center_source = hotbar_center_atlas_data()
    center_digits_source = hotbar_center_digits_atlas_data()
    icon_count = HOTBAR_LEFT_GEAR_COUNT + 1 + HOTBAR_RIGHT_VARIANT_GLYPH_COUNT
    for offset in range(-ICON_OFFSET_RANGE, ICON_OFFSET_RANGE + 1):
        variant = variant_index(offset)
        left_start = HOTBAR_LEFT_START_INDEX + variant * HOTBAR_LEFT_VARIANT_GLYPH_COUNT
        right_start = HOTBAR_RIGHT_START_INDEX + variant * HOTBAR_RIGHT_VARIANT_GLYPH_COUNT
        add_atlas("hud_hotbar_icons_offset.png",
                  pua_chars(left_start + HOTBAR_LEFT_GEAR_OFFSET,
                            HOTBAR_LEFT_GEAR_COUNT)
                  + chr(HOTBAR_ENERGY_BASE + variant)
                  + pua_chars(right_start, HOTBAR_RIGHT_VARIANT_GLYPH_COUNT),
                  icon_count, HOTBAR_HEIGHT, HOTBAR_VARIANT_HEIGHT, icon_source)
        add_atlas("hud_hotbar_digits_fade_offset.png",
                  pua_chars(left_start + HOTBAR_LEFT_DIGIT_OFFSET,
                            HOTBAR_LEFT_DIGIT_FADE_COUNT),
                  14, HOTBAR_VARIANT_HEIGHT, source_data=digit_fade_source)
        center_glyphs = pua_chars(HOTBAR_CENTER_START_INDEX + variant, 1)
        center_glyphs += chr(HOTBAR_CENTER_DARK_BASE + variant)
        add_atlas("hud_hotbar_center_offset.png", center_glyphs,
                  2, HOTBAR_CENTER_HEIGHT, HOTBAR_VARIANT_HEIGHT, center_source)
        center_digit_start = HOTBAR_CENTER_DIGIT_BASE + variant * HOTBAR_CENTER_DIGIT_COUNT
        center_digit_chars = "".join(chr(center_digit_start + digit)
                                     for digit in range(HOTBAR_CENTER_DIGIT_COUNT))
        add_atlas("hud_hotbar_center_digits_offset.png",
                  center_digit_chars,
                  HOTBAR_CENTER_DIGIT_COUNT, HOTBAR_VARIANT_HEIGHT,
                  source_data=center_digits_source)

    # Spaces and cursor shifts are defined by the font's space provider.
    for char, advance in build_space_advances().items():
        metrics[ord(char)] = int(advance)
    return metrics


def main() -> int:
    metrics = collect()
    line = ",".join(f"{codepoint:x}:{advance}" for codepoint, advance in sorted(metrics.items()))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(
        "# Сгенерировано tools/gen_font_metrics.py — не править руками.\n"
        "# Ширина каждого глифа шрифта servermmo:hud в пикселях: code:advance.\n"
        f"advances={line}\n",
        encoding="utf-8")
    print(f"font-metrics.properties: {len(metrics)} глифов")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
