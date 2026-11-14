#!/usr/bin/env python3
"""Собирает превью-лист всех иконок HUD (для глаза: арт генерится ИИ).

Просто раскладывает готовые PNG из tools/pack_assets на один лист с
подписями, чтобы оценить арт перед сборкой ресурспака.

Запуск (нужен Pillow):
    /tmp/venv/bin/python tools/build_icons_preview.py
"""
from __future__ import annotations

import sys
from pathlib import Path

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:  # pragma: no cover
    sys.exit("нужен Pillow: /tmp/venv/bin/python tools/build_icons_preview.py")

ROOT = Path(__file__).resolve().parent.parent
ICONS = ROOT / "tools" / "pack_assets" / "icons64"
LEVEL = ROOT / "tools" / "pack_assets" / "level"
OUT = ROOT / "tools" / "pack_assets" / "icons_preview.png"

ICON = 72      # размер иконки на листе
PANEL = (256, 64)
GAP = 14
WIDTH = 1080

# (заголовок, [(подпись, имя файла)] или None для значков уровня)
GROUPS: list[tuple[str, list[tuple[str, str]] | None]] = [
    ("Оружие и статы (хотбар), наклонены как трезубец", [
        ("сердце", "heart"), ("меч", "sword"), ("топор", "axe"),
        ("посох", "staff"), ("молот", "hammer"), ("коса", "scythe"),
        ("вилы/трезубец", "trident"), ("лук", "bow"), ("копьё", "spear"),
        ("щит", "shield"),
    ]),
    ("Валюта и клан", [
        ("монеты", "coin_pile"), ("коины", "coin_star"),
        ("боевые очки", "battle_points"), ("нанагемы", "nanogem"),
        ("клан", "clan"),
    ]),
    ("Эмблемы классов", [
        ("воин", "warrior"), ("маг", "mage"), ("лучник", "archer"),
        ("паладин", "paladin"), ("фермер", "farmer"), ("викинг", "viking"),
        ("некромант", "necromancer"), ("копейщик", "spearman"),
    ]),
    ("Способности (заглушки)", [
        ("удар", "ability_slash"), ("огонь", "ability_flame"),
        ("молния", "ability_bolt"), ("щит", "ability_shield"),
        ("череп", "ability_skull"), ("лист", "ability_leaf"),
        ("волна", "ability_wave"), ("стрела", "ability_arrow"),
    ]),
    ("Значки уровня 1..100 (показаны 1, 7, 42, 100)", None),
    ("Панели классов — силуэт под класс (256x64)", [
        ("воин", "panel_warrior"), ("маг", "panel_mage"),
        ("лучник", "panel_archer"), ("паладин", "panel_paladin"),
        ("фермер", "panel_farmer"), ("викинг", "panel_viking"),
        ("некромант", "panel_necromancer"), ("копейщик", "panel_spearman"),
    ]),
]


def level_preview() -> list[tuple[str, Path]]:
    return [(str(n), LEVEL / f"level_{n}.png") for n in (1, 7, 42, 100)]


def main() -> int:
    font = ImageFont.load_default(size=14)
    title = ImageFont.load_default(size=17)

    # Сначала прикидываем высоту листа.
    rows: list[tuple[str, list[tuple[str, Path]], tuple[int, int]]] = []
    for header, items in GROUPS:
        files = level_preview() if items is None else [
            (label, ICONS / f"{name}.png") for label, name in items]
        size = PANEL if "Панели" in header else (ICON, ICON)
        rows.append((header, files, size))

    height = 30
    for _, files, (iw, ih) in rows:
        per_row = max(1, (WIDTH - 32) // (iw + GAP))
        lines = (len(files) + per_row - 1) // per_row
        height += 26 + lines * (ih + 26) + 22

    canvas = Image.new("RGB", (WIDTH, height), (22, 23, 29))
    draw = ImageDraw.Draw(canvas)
    y = 20
    for header, files, (iw, ih) in rows:
        draw.text((16, y), header, font=title, fill=(240, 240, 250))
        y += 26
        x = 16
        for label, path in files:
            if not path.is_file():
                print(f"нет файла: {path}")
                continue
            img = Image.open(path).convert("RGBA").resize((iw, ih), Image.LANCZOS)
            if x + iw > WIDTH - 16:
                x = 16
                y += ih + 26
            canvas.paste(img, (x, y), img)
            box = draw.textbbox((0, 0), label, font=font)
            draw.text((x + (iw - (box[2] - box[0])) / 2, y + ih + 5), label,
                      font=font, fill=(185, 188, 205))
            x += iw + GAP
        y += ih + 48

    canvas.save(OUT)
    print(f"превью: {canvas.size[0]}x{canvas.size[1]} -> {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
