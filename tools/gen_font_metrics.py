#!/usr/bin/env python3
"""Считает ширину глифов HUD-шрифта и пишет таблицу для плагина.

Плагин собирает панель из глифов пака и должен знать, сколько пикселей
занимает каждый символ: иначе строки разной длины встанут по центру по
разному и панель будет «рваной». Ширина глифа в bitmap-провайдере — это
последний непрозрачный пиксель + 1, поэтому считаем её по самим PNG.

Результат: ``plugins/servermmo-hud/src/main/resources/font-metrics.properties``.
CI (``tools/check_pack.py``) сверяет этот файл с готовым паком и падает,
если они разошлись.

Запуск:  python3 tools/gen_font_metrics.py
"""
from __future__ import annotations

from pathlib import Path

from png_io import glyph_advance, png_rgba

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "tools" / "pack_assets"
OUT = (ROOT / "plugins" / "servermmo-hud" / "src" / "main" / "resources"
       / "font-metrics.properties")

def _fade_chars(step: int) -> str:
    base = 0xE200 + step * 16
    return "".join(chr(base + i) for i in range(12))


ATLASES = (
    ("hud_digits.png", "0123456789/ "),
    ("hud_icons.png", "\ue010\ue011\ue013"),
    ("hud_letters.png", "LVXP"),
    *(("hud_fade%d.png" % step, _fade_chars(step)) for step in range(5)),
)


def collect() -> dict[str, int]:
    metrics: dict[str, int] = {}
    for name, chars in ATLASES:
        width, height, pixels = png_rgba((ASSETS / name).read_bytes())
        cell = width // len(chars)
        for index, char in enumerate(chars):
            metrics[char] = glyph_advance(width, height, pixels, cell, index)
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
