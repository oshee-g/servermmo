#!/usr/bin/env python3
"""Собирает атласы значков HUD из нарезанных иконок 64x64.

Размеры согласованы с игроком:
  * цифры и буквы — 8 px (шрифт не трогаем);
  * значки панели (валюты, клан, свиток) — ячейки 24x24, чтобы не задевать
    рамку панели;
  * значки хотбара (сердце, меч, щит) — ячейки 20x20, как слот при
    масштабе интерфейса 2;
  * в центре хотбара — одна пустая стальная ромб-рамка 24x24;
  * ромбы уровней игрока и щиты уровней клана — ячейки 24x24 (номер уже
    нарисован на них кодом, без «lvl»);
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
LEVEL = ROOT / "tools" / "pack_assets" / "level"
CLAN = ROOT / "tools" / "pack_assets" / "clan"
OUT = ROOT / "tools" / "pack_assets"

ICON = 24          # ячейка значка панели: height=24 в шрифте
HOTBAR = 20        # ячейка значка хотбара: height=20, как ячейка хотбара
ABILITY_FRAME = 24 # ромб-рамка способностей немного крупнее слота
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
PANEL_COLS = 2     # сетка 2x5 = 10 панелей


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


def diamond_frame() -> Image.Image:
    """Empty, steel-edged diamond placeholder for future ability artwork."""
    size = ABILITY_FRAME
    center = size // 2
    hi = size - 1

    def points(inset: int) -> list[tuple[int, int]]:
        low, high = inset, hi - inset
        return [(center, low), (high - 1, center - 1), (high, center),
                (center, high), (center - 1, high), (low, center),
                (low, center - 1)]

    image = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    shadow = [(x, min(hi, y + 1)) for x, y in points(0)]
    draw.polygon(shadow, fill=(3, 5, 8, 220))
    draw.polygon(points(0), fill=(10, 13, 17, 255))
    draw.polygon(points(1), fill=(105, 117, 130, 255))
    draw.polygon(points(2), fill=(43, 53, 64, 255))
    draw.polygon(points(3), fill=(21, 28, 36, 255))
    # Transparent center leaves the middle slot visible and ready for art.
    draw.polygon(points(4), fill=(0, 0, 0, 0))
    # Beveled silver-blue highlights on the upper-left and a darker lower-right edge.
    draw.line([(center, 1), (2, center - 1), (2, center), (center, hi - 1)],
              fill=(166, 177, 188, 255), width=1)
    draw.line([(center, hi - 1), (hi - 1, center), (hi - 2, center - 1)],
              fill=(37, 46, 57, 255), width=1)
    draw.line([(center, 4), (4, center), (center, hi - 4),
               (hi - 4, center)], fill=(58, 70, 82, 255), width=1)
    return image


def build_ability_atlas() -> None:
    ability = tile([fit(name, (HOTBAR, HOTBAR)) for name in ABILITIES],
                   len(ABILITIES), (HOTBAR, HOTBAR))
    ability.save(OUT / "hud_ability.png", optimize=True)
    print(f"hud_ability.png {ability.size[0]}x{ability.size[1]}: "
          f"{len(ABILITIES)} квадратных иконок способностей")


def build_hotbar_center_atlas() -> None:
    frame = diamond_frame()
    frame.save(OUT / "hud_hotbar_center.png", optimize=True)
    print(f"hud_hotbar_center.png {frame.size[0]}x{frame.size[1]}: "
          "одна центральная ромб-рамка")


def main() -> int:
    gear = tile([fit(n, (HOTBAR, HOTBAR)) for n in GEAR], len(GEAR),
                (HOTBAR, HOTBAR))
    gear.save(OUT / "hud_gear.png", optimize=True)
    print(f"hud_gear.png {gear.size[0]}x{gear.size[1]}: {', '.join(GEAR)}")

    money = tile([load(n, (ICON, ICON)) for n in MONEY], len(MONEY), (ICON, ICON))
    money.save(OUT / "hud_money.png", optimize=True)
    print(f"hud_money.png {money.size[0]}x{money.size[1]}: {', '.join(MONEY)}")

    build_ability_atlas()
    build_hotbar_center_atlas()

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

    clan_badges = []
    for n in range(1, 101):
        path = CLAN / f"level_{n}.png"
        if not path.is_file():
            sys.exit(f"нет значка {path} — сначала tools/build_level_badges.py")
        clan_badges.append(Image.open(path).convert("RGBA").resize((ICON, ICON),
                                                                   Image.LANCZOS))
    clan_sheet = tile(clan_badges, LEVEL_COLS, (ICON, ICON))
    clan_sheet.save(OUT / "hud_clan.png", optimize=True)
    print(f"hud_clan.png {clan_sheet.size[0]}x{clan_sheet.size[1]}: щиты клана "
          f"1..100 сеткой {LEVEL_COLS}x{10}")

    panels = []
    for name in CLASSES:
        path = ICONS / f"panel_{name}.png"
        if not path.is_file():
            sys.exit(f"нет панели {path}")
        source = Image.open(path).convert("RGBA")
        # Оставляем один полностью прозрачный столбец справа: BitmapProvider
        # задаёт advance = последняя видимая колонка + 1, и глиф должен быть
        # ровно 256 px, а не 257.
        fitted = source.resize((PANEL[0] - 1, PANEL[1]), Image.LANCZOS)
        canvas = Image.new("RGBA", PANEL, (0, 0, 0, 0))
        canvas.paste(fitted, (0, 0), fitted)
        panels.append(canvas)
    sheet = tile(panels, PANEL_COLS, PANEL)
    sheet.save(OUT / "hud_panel.png", optimize=True)
    print(f"hud_panel.png {sheet.size[0]}x{sheet.size[1]}: панели классов "
          f"сеткой {PANEL_COLS}x{len(panels) // PANEL_COLS}")
    return 0


if __name__ == "__main__":
    if sys.argv[1:] == ["--ability-only"]:
        build_ability_atlas()
        raise SystemExit(0)
    if sys.argv[1:] == ["--center-only"]:
        build_hotbar_center_atlas()
        raise SystemExit(0)
    raise SystemExit(main())
