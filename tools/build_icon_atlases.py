#!/usr/bin/env python3
"""Собирает атласы значков HUD из нарезанных иконок 64x64.

Разделение по высоте согласовано с игроком: цифры и буквы остаются
8-пиксельными (шрифт не трогаем), а значки идут ячейками 32x32 и
рисуются в 4 раза крупнее. Панели классов — отдельный провайдер 256x64:
это подложка панели, она крупнее значков.

Важно проbitmap-провайдер Minecraft (проверено по исходникам 26.3,
BitmapProvider.Definition.load): текстура делится на сетку по размеру
массива chars, ячейка = (ширина / столбцов) x (высота / строк), а
объявленная height масштабирует ячейку до нужной высоты
(pixelScale = height / высота_ячейки). Значит ячейку 32x32 с height=32
игра нарисует 1:1 — поэтому исходники 64x64 ужимаются здесь, а не
масштабируются на видеокарте (иначе теряются тонкие линии).

Запуск (нужен Pillow):
    /tmp/venv/bin/python tools/build_icon_atlases.py
"""
from __future__ import annotations

import sys
from pathlib import Path

try:
    from PIL import Image
except ImportError:  # pragma: no cover
    sys.exit("нужен Pillow: /tmp/venv/bin/python tools/build_icon_atlases.py")

ROOT = Path(__file__).resolve().parent.parent
ICONS = ROOT / "tools" / "pack_assets" / "icons64"
LEVEL = ROOT / "tools" / "pack_assets" / "level"
OUT = ROOT / "tools" / "pack_assets"

ICON = 32          # ячейка значка: height=32 в шрифте
PANEL = (256, 64)  # ячейка панели класса

GEAR = ["heart", "sword", "axe", "staff", "hammer", "scythe", "trident",
        "bow", "spear", "shield"]
MONEY = ["coin_pile", "coin_star", "battle_points", "nanogem", "clan"]
ABILITIES = ["ability_slash", "ability_flame", "ability_bolt", "ability_shield",
             "ability_skull", "ability_leaf", "ability_wave", "ability_arrow"]
CLASSES = ["warrior", "mage", "archer", "paladin", "farmer", "viking",
           "necromancer", "spearman"]

LEVEL_COLS = 10    # 10x10 = 100 уровней
PANEL_COLS = 2     # 2x4 = 8 классов


def tile(images: list[Image.Image], cols: int, cell: tuple[int, int]) -> Image.Image:
    """Раскладывает картинки в сетку cols x rows ячейками cell."""
    rows = (len(images) + cols - 1) // cols
    sheet = Image.new("RGBA", (cols * cell[0], rows * cell[1]), (0, 0, 0, 0))
    for index, image in enumerate(images):
        col, row = index % cols, index // cols
        sheet.paste(image, (col * cell[0], row * cell[1]), image)
    return sheet


def load(name: str, size: tuple[int, int]) -> Image.Image:
    path = ICONS / f"{name}.png"
    if not path.is_file():
        sys.exit(f"нет иконки {path} — сначала tools/slice_sheet.py")
    image = Image.open(path).convert("RGBA")
    return image.resize(size, Image.LANCZOS)


def main() -> int:
    gear = tile([load(n, (ICON, ICON)) for n in GEAR], len(GEAR), (ICON, ICON))
    gear.save(OUT / "hud_gear.png", optimize=True)
    print(f"hud_gear.png {gear.size[0]}x{gear.size[1]}: {', '.join(GEAR)}")

    money = tile([load(n, (ICON, ICON)) for n in MONEY], len(MONEY), (ICON, ICON))
    money.save(OUT / "hud_money.png", optimize=True)
    print(f"hud_money.png {money.size[0]}x{money.size[1]}: {', '.join(MONEY)}")

    ability = tile([load(n, (ICON, ICON)) for n in ABILITIES], len(ABILITIES),
                   (ICON, ICON))
    ability.save(OUT / "hud_ability.png", optimize=True)
    print(f"hud_ability.png {ability.size[0]}x{ability.size[1]}: 8 способность")

    badges = []
    for n in range(1, 101):
        path = LEVEL / f"level_{n}.png"
        if not path.is_file():
            sys.exit(f"нет значка {path} — сначала tools/build_level_badges.py")
        badges.append(Image.open(path).convert("RGBA").resize((ICON, ICON),
                                                             Image.LANCZOS))
    sheet = tile(badges, LEVEL_COLS, (ICON, ICON))
    sheet.save(OUT / "hud_level.png", optimize=True)
    print(f"hud_level.png {sheet.size[0]}x{sheet.size[1]}: уровни 1..100 "
          f"сеткой {LEVEL_COLS}x{10}")

    panels = []
    for name in CLASSES:
        path = ICONS / f"panel_{name}.png"
        if not path.is_file():
            sys.exit(f"нет панели {path}")
        panels.append(Image.open(path).convert("RGBA").resize(PANEL, Image.LANCZOS))
    sheet = tile(panels, PANEL_COLS, PANEL)
    sheet.save(OUT / "hud_panel.png", optimize=True)
    print(f"hud_panel.png {sheet.size[0]}x{sheet.size[1]}: панели классов "
          f"сеткой {PANEL_COLS}x{len(CLASSES) // PANEL_COLS}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
