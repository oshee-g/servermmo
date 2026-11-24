#!/usr/bin/env python3
"""Собирает атласы значков HUD из нарезанных иконок 64x64.

Размеры согласованы с игроком:
  * цифры и буквы — 8 px (шрифт не трогаем);
  * значки панели (валюты, клан, свиток) — ячейки 24x24, чтобы не задевать
    рамку панели;
  * значки хотбара (сердце, меч, щит) — ячейки 20x20, как слот при
    масштабе интерфейса 2;
  * в центре хотбара — квадратная иконка способности агрессии 32x32,
    с нормальным и затемнённым состоянием;
  * ромбы уровней и щиты клана — номер заново рисуется пиксельной цифрой
    уже в конечной 24x24 ячейке, отдельно подобранными цветами;
  * панели классов — 256x96 целиком, одним глифом (шире 256 нельзя:
    клиент запекает глифы в атлас 256x256).

Важно про bitmap-провайдер Minecraft (проверено по исходникам 26.3,
BitmapProvider.Definition.load): текстура делится на сетку по размеру
массива chars, ячейка = (ширина / столбцов) x (высота / строк), а
объявленная height масштабирует ячейку до нужной высоты
(pixelScale = height / высота_ячейки). Значит ячейку 20x20 с height=20
игра нарисует 1:1 — поэтому исходники 64x64 ужимаются здесь, а не
масштабируются на видеокарте (иначе теряются тонкие линии).

Запуск (нужен Pillow):
    /tmp/venv/bin/python tools/build_icon_atlases.py
"""
from __future__ import annotations

import sys
from pathlib import Path

try:
    from PIL import Image, ImageDraw
except ImportError:  # pragma: no cover
    sys.exit("нужен Pillow: /tmp/venv/bin/python tools/build_icon_atlases.py")

ROOT = Path(__file__).resolve().parent.parent
ICONS = ROOT / "tools" / "pack_assets" / "icons64"
OUT = ROOT / "tools" / "pack_assets"

ICON = 24          # ячейка значка панели: height=24 в шрифте
HOTBAR = 20        # ячейка значка хотбара: height=20, как ячейка хотбара
HOTBAR_CENTER_HEIGHT = 32  # центральная квадратная способность
# Ячейка панели класса. Высота не исходные 64, а 96: три строки HUD
# занимают 70 px, и панель с запасом накрывает их, а заодно выше и
# представительнее (игрок просил панель повыше, а не пошире — ширину 256
# оставляем как у оригинальных панелей). Шаг между строками босс-бара у
# клиента 10 + lineHeight (lineHeight = 9, константа) = 19 px. Считаем от
# baseline первой строки:
#   строка 1, ник (ванильный текст 8 px, ascent 7):  занимает -7..1
#   строка 2, клан (щит 24 px, ascent 15):           19-15..19+9 = 4..28
#   строка 3, валюты (значки 24 px, ascent 15):      38-15..38+9 = 23..47
# Итого весь текст: -7..47, то есть 54 px. Панель 96 px при ascent 7 занимает
# -7..89 и накрывает его с запасом — иначе нижняя строка висит на пустом фоне.
# Ширина 256 — предел атласа FontTexture 256x256: глиф шире не влезет.
PANEL = (256, 96)  # панель класса целиком, один глиф

GEAR = ["heart", "sword", "axe", "staff", "hammer", "scythe", "trident",
        "bow", "spear", "shield"]
MONEY = ["coin_pile", "coin_star", "nanogem", "clan", "scroll", "battle_points"]
ABILITIES = ["ability_slash", "ability_flame", "ability_bolt", "ability_shield",
             "ability_skull", "ability_leaf", "ability_wave", "ability_arrow"]
CLASSES = ["warrior", "archer", "mage", "paladin", "vampire", "necromancer",
           "viking", "farmer", "blacksmith", "druid"]

LEVEL_COLS = 10    # 10x10 = 100 уровней
PLAYER_LEVEL_GLYPHS = "0123456789lvl"
PANEL_COLS = 2     # сетка 2x5 = 10 панелей

PIXEL_DIGITS = {
    "0": ("1111", "1001", "1001", "1001", "1111"),
    "1": ("0110", "1110", "0110", "0110", "1111"),
    "2": ("1111", "0001", "1111", "1000", "1111"),
    "3": ("1111", "0001", "1111", "0001", "1111"),
    "4": ("1001", "1001", "1111", "0001", "0001"),
    "5": ("1111", "1000", "1111", "0001", "1111"),
    "6": ("1111", "1000", "1111", "1001", "1111"),
    "7": ("1111", "0001", "0010", "0100", "0100"),
    "8": ("1111", "1001", "1111", "1001", "1111"),
    "9": ("1111", "1001", "1111", "0001", "1111"),
}


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


def fit(name: str, size: tuple[int, int]) -> Image.Image:
    """Иконка хотбара: вырезаем по непрозрачному контуру и растягиваем на всю
    ячейку, чтобы все значки были одного размера (меч в исходнике меньше
    сердца, и без нормализации он так и выглядит мельче)."""
    path = ICONS / f"{name}.png"
    if not path.is_file():
        sys.exit(f"нет иконки {path} — сначала tools/slice_sheet.py")
    image = Image.open(path).convert("RGBA")
    alpha = image.getchannel("A").point(lambda v: 255 if v >= 128 else 0)
    box = alpha.getbbox()
    if box is not None:
        image = image.crop(box)
    scale = min(size[0] / image.width, size[1] / image.height)
    w = max(1, round(image.width * scale))
    h = max(1, round(image.height * scale))
    image = image.resize((w, h), Image.LANCZOS)
    canvas = Image.new("RGBA", size, (0, 0, 0, 0))
    canvas.paste(image, ((size[0] - w) // 2, (size[1] - h) // 2), image)
    return canvas


def numbered_badge(base_name: str, value: int, clan_style: bool) -> Image.Image:
    """Draw sharp, purpose-styled pixel numerals after scaling the badge art."""
    badge = load(base_name, (ICON, ICON))
    text = str(value)
    scale = 2 if len(text) == 1 else 1
    glyph_width = 4 * len(text) + max(0, len(text) - 1)
    x_start = (ICON - glyph_width * scale) // 2
    y_start = (ICON - 5 * scale) // 2
    draw = ImageDraw.Draw(badge)

    # Level numerals are icy white with a deep teal shadow; clan numerals use
    # warm gold over burgundy. Drawing at final atlas resolution avoids the
    # blur caused by painting text on a 64px icon and shrinking it twice.
    shadow = (4, 25, 39, 255) if not clan_style else (49, 10, 22, 255)
    bright = (242, 255, 255, 255) if not clan_style else (255, 247, 190, 255)
    face = (137, 246, 255, 255) if not clan_style else (255, 190, 67, 255)
    for index, digit in enumerate(text):
        pattern = PIXEL_DIGITS[digit]
        origin_x = x_start + index * 5 * scale
        for y, row in enumerate(pattern):
            for x, pixel in enumerate(row):
                if pixel != "1":
                    continue
                px = origin_x + x * scale
                py = y_start + y * scale
                draw.rectangle((px + 1, py + 1, px + scale, py + scale), fill=shadow)
                color = bright if y == 0 else face
                draw.rectangle((px, py, px + scale - 1, py + scale - 1), fill=color)
    return badge


def chroma_badge(master_name: str) -> Image.Image:
    """Remove the vivid green key, crop the artwork, and fit it into an icon cell."""
    image = Image.open(ICONS / master_name).convert("RGBA")
    pixels = image.load()
    for y in range(image.height):
        for x in range(image.width):
            r, g, b, a = pixels[x, y]
            excess = g - max(r, b)
            if g > 80 and excess > 35:
                # Feather the chroma key on antialiased edges and despill green.
                alpha = max(0, min(255, round(255 * (1 - (excess - 35) / 45))))
                pixels[x, y] = (r, min(g, max(r, b)), b, min(a, alpha))
    box = image.getchannel("A").getbbox()
    if box:
        image = image.crop(box)
    scale = min(22 / image.width, 22 / image.height)
    image = image.resize((max(1, round(image.width * scale)),
                          max(1, round(image.height * scale))), Image.LANCZOS)
    cell = Image.new("RGBA", (ICON, ICON), (0, 0, 0, 0))
    cell.alpha_composite(image, ((ICON - image.width) // 2,
                                 (ICON - image.height) // 2))
    return cell


def build_generated_level_digits() -> None:
    """Thick teal pixel glyphs for the player's level number and `lvl`."""
    patterns = {
        "0": ("11111", "11011", "10101", "10101", "10101", "11011", "11111"),
        "1": ("00110", "01110", "00110", "00110", "00110", "00110", "01111"),
        "2": ("11111", "00011", "00011", "11111", "11000", "11000", "11111"),
        "3": ("11111", "00011", "00011", "01111", "00011", "00011", "11111"),
        "4": ("11011", "11011", "11011", "11111", "00011", "00011", "00011"),
        "5": ("11111", "11000", "11000", "11111", "00011", "00011", "11111"),
        "6": ("11111", "11000", "11000", "11111", "11011", "11011", "11111"),
        "7": ("11111", "00011", "00110", "00110", "01100", "01100", "01100"),
        "8": ("11111", "11011", "11011", "11111", "11011", "11011", "11111"),
        "9": ("11111", "11011", "11011", "11111", "00011", "00011", "11111"),
        "l": ("11000", "11000", "11000", "11000", "11000", "11000", "11111"),
        "v": ("11011", "11011", "11011", "11011", "11011", "01110", "00100"),
    }
    atlas = Image.new("RGBA", (8 * len(PLAYER_LEVEL_GLYPHS), 8), (0, 0, 0, 0))
    draw = ImageDraw.Draw(atlas)
    for i, char in enumerate(PLAYER_LEVEL_GLYPHS):
        for y, row in enumerate(patterns[char]):
            for x, bit in enumerate(row):
                if bit == "1":
                    px, py = i * 8 + x + 1, y
                    if y < 6:
                        draw.point((px + 1, py + 1), fill=(0, 52, 62, 255))
                    draw.point((px, py), fill=(45, 255, 238, 255))
    atlas.save(OUT / "hud_player_level.png", optimize=True)
    print(f"hud_player_level.png {atlas.width}x{atlas.height}: digits and lvl")


def build_ability_atlas() -> None:
    ability = tile([fit(name, (HOTBAR, HOTBAR)) for name in ABILITIES],
                   len(ABILITIES), (HOTBAR, HOTBAR))
    ability.save(OUT / "hud_ability.png", optimize=True)
    print(f"hud_ability.png {ability.size[0]}x{ability.size[1]}: "
          f"{len(ABILITIES)} квадратных иконок способностей")


def build_hotbar_center_atlas() -> None:
    # The center aggression slot uses the fist; the adjacent ability slot has a sword.
    icon = fit("ability_aggression", (HOTBAR_CENTER_HEIGHT - 8,
                                      HOTBAR_CENTER_HEIGHT - 8))
    framed = Image.new("RGBA", (HOTBAR_CENTER_HEIGHT, HOTBAR_CENTER_HEIGHT),
                       (22, 27, 29, 255))
    draw = ImageDraw.Draw(framed)
    # Layered bevel makes the center slot read as forged iron rather than a
    # floating ability icon. The ability art sits inside a 24px square opening.
    draw.rectangle((0, 0, 31, 31), fill=(17, 21, 23, 255))
    draw.rectangle((1, 1, 30, 30), fill=(173, 181, 183, 255))
    draw.rectangle((2, 2, 29, 29), fill=(52, 61, 65, 255))
    draw.rectangle((3, 3, 28, 28), fill=(20, 24, 26, 255))
    framed.alpha_composite(icon, (4, 4))
    dim = framed.copy()
    pixels = dim.load()
    for y in range(dim.height):
        for x in range(dim.width):
            red, green, blue, alpha = pixels[x, y]
            pixels[x, y] = (red * 55 // 100, green * 55 // 100,
                            blue * 55 // 100, alpha)
    atlas = tile([framed, dim], 2, (HOTBAR_CENTER_HEIGHT, HOTBAR_CENTER_HEIGHT))
    atlas.save(OUT / "hud_hotbar_center.png", optimize=True)
    print(f"hud_hotbar_center.png {atlas.size[0]}x{atlas.size[1]}: "
          "иконка агрессии, обычное и затемнённое состояния")


def build_panel_atlas() -> None:
    panels = []
    for name in CLASSES:
        path = ICONS / f"panel_{name}.png"
        if not path.is_file():
            sys.exit(f"нет панели {path}")
        source = Image.open(path).convert("RGBA")
        # У изображений разная прозрачная кайма, поэтому глифы иначе получают
        # разный advance и центр рамки «прыгает» между классами. Сначала
        # обрезаем только внешнюю прозрачную кайму, затем нормализуем видимую
        # рамку до одной и той же ячейки 255x96.
        visible = source.getchannel("A").point(
            lambda alpha: 255 if alpha >= 32 else 0).getbbox()
        if visible is None:
            sys.exit(f"пустая панель {path}")
        source = source.crop(visible)
        fitted = source.resize((PANEL[0] - 1, PANEL[1]), Image.LANCZOS)
        canvas = Image.new("RGBA", PANEL, (0, 0, 0, 0))
        canvas.alpha_composite(fitted, (0, 0))
        panels.append(canvas)
    sheet = tile(panels, PANEL_COLS, PANEL)
    sheet.save(OUT / "hud_panel.png", optimize=True)
    print(f"hud_panel.png {sheet.size[0]}x{sheet.size[1]}: панели классов "
          f"сеткой {PANEL_COLS}x{len(panels) // PANEL_COLS}")


def main() -> int:
    gear = tile([fit(n, (HOTBAR, HOTBAR)) for n in GEAR], len(GEAR),
                (HOTBAR, HOTBAR))
    gear.save(OUT / "hud_gear.png", optimize=True)
    print(f"hud_gear.png {gear.size[0]}x{gear.size[1]}: {', '.join(GEAR)}")

    energy = fit("energy", (HOTBAR, HOTBAR))
    energy.save(OUT / "hud_energy.png", optimize=True)
    print(f"hud_energy.png {energy.size[0]}x{energy.size[1]}: energy bolt")

    money = tile([load(n, (ICON, ICON)) for n in MONEY], len(MONEY), (ICON, ICON))
    money.save(OUT / "hud_money.png", optimize=True)
    print(f"hud_money.png {money.size[0]}x{money.size[1]}: {', '.join(MONEY)}")

    build_ability_atlas()
    build_hotbar_center_atlas()
    build_generated_level_digits()

    badges = [numbered_badge("level_diamond_new", n, False)
              for n in range(1, 101)]
    sheet = tile(badges, LEVEL_COLS, (ICON, ICON))
    sheet.save(OUT / "hud_level.png", optimize=True)
    print(f"hud_level.png {sheet.size[0]}x{sheet.size[1]}: уровни 1..100 "
          f"сеткой {LEVEL_COLS}x{10}")

    clan_badges = [numbered_badge("level_shield", n, True)
                   for n in range(1, 101)]
    clan_sheet = tile(clan_badges, LEVEL_COLS, (ICON, ICON))
    clan_sheet.save(OUT / "hud_clan.png", optimize=True)
    chroma_badge("clan_shield_cape_master.png").save(OUT / "hud_clan_empty.png", optimize=True)
    chroma_badge("enemy_level_badge_master.png").save(OUT / "hud_enemy_level.png", optimize=True)
    print(f"hud_clan.png {clan_sheet.size[0]}x{clan_sheet.size[1]}: щиты клана "
          f"1..100 сеткой {LEVEL_COLS}x{10}")

    build_panel_atlas()
    return 0


if __name__ == "__main__":
    if sys.argv[1:] == ["--ability-only"]:
        build_ability_atlas()
        raise SystemExit(0)
    if sys.argv[1:] == ["--center-only"]:
        build_hotbar_center_atlas()
        raise SystemExit(0)
    if sys.argv[1:] == ["--panels-only"]:
        build_panel_atlas()
        raise SystemExit(0)
    raise SystemExit(main())
