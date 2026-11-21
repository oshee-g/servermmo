#!/usr/bin/env python3
"""Проверка собранного ресурспака перед публикацией.

Запускается в CI после ``tools/gen_resourcepack.py``. Проверяет то, из-за
чего HUD раньше не отображался:

* шрифт HUD на месте, а bitmap-провайдеры используют ячейки нужного размера;
* исходные мелкие текстуры глифов квадратные; диапазоны сдвигов проверены;
* чернила глифов лежат в строках 0..6 ячейки — ровно как заглавные буквы и
  цифры ванильного ``ascii.png`` (height 8, ascent 7, строка 7 — базовая
  линия); иначе в подписи моба значки «прыгают» относительно имени, которое
  рисуется ванильным шрифтом;
* ванильные сердца (включая ``_blinking``, ``hardcore_`` и ``vehicle_``)
  перекрыты прозрачными спрайтами;
* старые спрайты ``icons.png`` тоже пустые.

Запуск:  python3 tools/check_pack.py [путь к zip]
"""
from __future__ import annotations

import json
import struct
import sys
import zipfile
from pathlib import Path

from png_io import glyph_advance_grid, png_rgba
from gen_resourcepack import (
    HOTBAR_CENTER_TOTAL, HOTBAR_LEFT_TOTAL, HOTBAR_RIGHT_TOTAL,
    INLINE_TOTAL, PANEL_TOTAL,
    TEXT_VARIANT_TOTAL, pua_codepoint,
)

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_ZIP = ROOT / "server" / "resourcepack" / "servermmo-hud.zip"
GLYPH_HEIGHT = 8

REQUIRED = [
    "assets/servermmo/font/hud.json",
    "assets/servermmo/textures/font/hud_text_small.png",
    "assets/servermmo/textures/font/hud_digits.png",
    "assets/servermmo/textures/font/hud_digits_inline.png",
    "assets/servermmo/textures/font/hud_icons.png",
    "assets/servermmo/textures/font/hud_letters.png",
    "assets/minecraft/textures/gui/icons.png",
    "assets/minecraft/textures/gui/sprites/hud/heart/full.png",
    "assets/minecraft/textures/gui/sprites/hud/heart/half.png",
    "assets/minecraft/textures/gui/sprites/hud/heart/container.png",
    "assets/minecraft/textures/gui/sprites/hud/heart/hardcore_full_blinking.png",
    "assets/minecraft/textures/gui/sprites/hud/heart/vehicle_full.png",
    "assets/minecraft/textures/gui/sprites/hud/food_full.png",
    "assets/minecraft/textures/gui/sprites/hud/experience_bar_progress.png",
    # белая полоса босс-бара: панель HUD рисуется строками босс-бара,
    # сама полоса должна быть невидимой
    "assets/minecraft/textures/gui/sprites/boss_bar/white_background.png",
    "assets/minecraft/textures/gui/sprites/boss_bar/white_progress.png",
]


def png_size(data: bytes) -> tuple[int, int]:
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("не PNG")
    return struct.unpack(">II", data[16:24])


def png_is_blank(data: bytes) -> bool:
    """Полностью прозрачный PNG: все IDAT-байты нули после распаковки."""
    import zlib
    pos = 8
    idat = b""
    while pos < len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        kind = data[pos + 4:pos + 8]
        payload = data[pos + 8:pos + 8 + length]
        if kind == b"IDAT":
            idat += payload
        pos += 12 + length
    raw = zlib.decompress(idat)
    return all(byte == 0 for byte in raw)


def check_ink_rows(width: int, height: int, pixels: bytes, cells: int,
                   file: str, problems: list[str]) -> None:
    """Чернила каждого глифа обязаны лежать в строках 0..6 ячейки 8x8.

    Нижняя строка ячейки — базовая линия ванильного шрифта; если чернила
    попадут в неё, глиф окажется на пиксель ниже ванильного текста.
    """
    stride = width * 4
    cell_width = width // cells
    for index in range(cells):
        rows_with_ink = set()
        for y in range(height):
            for x in range(index * cell_width, (index + 1) * cell_width):
                if pixels[y * stride + x * 4 + 3] > 40:
                    rows_with_ink.add(y)
        if not rows_with_ink:
            continue  # пустой глиф-пробел
        if GLYPH_HEIGHT - 1 in rows_with_ink:
            problems.append(f"{file}: у глифа #{index} чернила в строке"
                            f" {GLYPH_HEIGHT - 1} (базовая линия) — он окажется"
                            f" ниже ванильного текста")


LAYOUT_ADVANCES = {
    # пустые глифы, которыми плагин добивает строки панели до одной ширины
    "\ue100": 2, "\ue101": 4, "\ue10a": 6, "\ue10b": 10, "\ue10c": 8,
}
SHIFT_BITS = range(0, 12)
# Самый широкий интерфейс, который надо уметь перекрыть сдвигом: экран 3840 px
# при масштабе 1. Панель при этом шириной ~256 px, поэтому хватает и 3600.
MAX_INTERFACE_WIDTH = 3840
METRICS_FILE = ROOT / "plugins" / "servermmo-hud" / "src" / "main" / "resources" / "font-metrics.properties"


def check_codepoint_grid(font: dict, problems: list[str]) -> None:
    """
    chars — это массив строк, по одной на ряд сетки.

    <p>Клиент читает поле кодеком Codec.STRING.listOf(). Если ряд записать
    массивом отдельных символов, он ответит «Not a string» — и уронит весь
    файл шрифта, а не один провайдер: FontManager.loadResourceStack зовёт
    getOrThrow. Тогда шрифт не загружается вовсе и вместо всех глифов
    рисуются пустые квадратики.</p>
    """
    for provider in font.get("providers", []):
        if provider.get("type") != "bitmap":
            continue
        file = provider.get("file", "")
        rows_chars = provider.get("chars", [])
        for index, row in enumerate(rows_chars):
            if not isinstance(row, str):
                problems.append(f"{file}: ряд chars[{index}] — не строка "
                                f"({type(row).__name__}). Клиент ждёт массив "
                                f"строк, на вложенный массив падает весь "
                                f"файл шрифта целиком")


def check_texty_glyphs(font: dict, problems: list[str]) -> None:
    """Проверяет все 201 варианта панелей, текста и обеих сторон хотбара."""
    total = (INLINE_TOTAL + PANEL_TOTAL + 2 * TEXT_VARIANT_TOTAL
             + HOTBAR_LEFT_TOTAL + HOTBAR_RIGHT_TOTAL + HOTBAR_CENTER_TOTAL)
    expected = {pua_codepoint(index) for index in range(total)}
    found = {
        ord(char)
        for provider in font.get("providers", [])
        if provider.get("type") == "bitmap"
        for row in provider.get("chars", [])
        for char in row
    }
    missing = expected - found
    if missing:
        problems.append(f"варианты HUD-глифов: отсутствуют {len(missing)} из {total} PUA-кодов")
    for provider in font.get("providers", []):
        if provider.get("type") != "bitmap":
            continue
        file = provider.get("file", "")
        if file.endswith("hud_panel_offset.png") and provider.get("height") != 160:
            problems.append(f"{file}: варианты панели должны использовать ячейки 160 px")
        if file.endswith("_offset.png") and any(
                stem in file for stem in ("hud_text", "hud_digits_inline", "hud_money",
                                          "hud_level", "hud_clan", "hud_hotbar",
                                          "hud_fade", "hud_ability")):
            if provider.get("height") != 128:
                problems.append(f"{file}: варианты текста/значков должны использовать height=128")
    space = next((provider.get("advances", {}) for provider in font.get("providers", [])
                  if provider.get("type") == "space"), {})
    if space.get(chr(0xE101)) != 4:
        problems.append("явный пробел текста E101 должен иметь ширину 4 px")


def check_space_glyphs(font: dict, problems: list[str]) -> None:
    """Пустые глифы и сдвиги, из которых плагин собирает строки панели."""
    advances: dict[str, int] = {}
    for provider in font.get("providers", []):
        if provider.get("type") == "space":
            advances.update(provider.get("advances", {}))
    for char, expected in LAYOUT_ADVANCES.items():
        if advances.get(char) != expected:
            problems.append(f"пробел {hex(ord(char))}: {advances.get(char)}, ожидалось {expected}")
    for bit in SHIFT_BITS:
        plus = chr(0xE110 + bit)
        minus = chr(0xE120 + bit)
        if advances.get(plus) != 1 << bit:
            problems.append(f"сдвиг {hex(ord(plus))}: {advances.get(plus)}, ожидалось {1 << bit}")
        if advances.get(minus) != -(1 << bit):
            problems.append(f"сдвиг {hex(ord(minus))}: {advances.get(minus)}, ожидалось {-(1 << bit)}")


def check_shift_range(font: dict, problems: list[str]) -> None:
    """
    Диапазон сдвига должен доставать до левого края на любом экране.

    <p>Сдвиг собирается из глифов-битов ±2^i. Если старшего бита не хватает,
    панель при масштабе интерфейса 1 или на широком экране упирается в предел
    и остаётся посередине экрана: выглядит как «HUD не работает», хотя пак
    applied.</p>
    """
    advances: dict[str, int] = {}
    for provider in font.get("providers", []):
        if provider.get("type") == "space":
            advances.update(provider.get("advances", {}))
    total = sum(1 << bit for bit in SHIFT_BITS if advances.get(chr(0xE120 + bit)) == -(1 << bit))
    needed = MAX_INTERFACE_WIDTH
    if total < needed:
        problems.append(f"сдвиг панели ограничен {total} px, а интерфейс бывает "
                        f"шире {needed} px: добавьте старшие биты в "
                        f"SHIFT_MAX_BIT (tools/gen_resourcepack.py)")


def check_metrics(font: dict, names: set[str], zf: zipfile.ZipFile,
                  problems: list[str]) -> None:
    """Таблица ширин глифов в плагине должна совпадать с атласами пака."""
    measured: dict[str, int] = {}
    image_cache: dict[str, tuple[int, int, bytes]] = {}
    advance_cache: dict[tuple[str, int, int, int], list[list[int]]] = {}
    for provider in font.get("providers", []):
        # Пробелы и сдвиги курсора заданы в space-провайдере, а не в PNG.
        if provider.get("type") == "space":
            for char, advance in provider.get("advances", {}).items():
                measured[char] = int(advance)
            continue
        if provider.get("type") != "bitmap":
            continue
        file = provider.get("file", "").split(":", 1)[-1]
        texture = f"assets/servermmo/textures/{file}"
        if texture not in names:
            continue
        rows_chars = provider.get("chars", [])
        cols = max(1, len(rows_chars[0])) if rows_chars else 1
        rows_count = max(1, len(rows_chars))
        try:
            if texture not in image_cache:
                image_cache[texture] = png_rgba(zf.read(texture))
            width, height, pixels = image_cache[texture]
        except ValueError:
            continue
        # Масштаб провайдера (height / высота ячейки): ширина глифа
        # масштабируется точно так же, как в клиенте Minecraft.
        prov_height = provider.get("height")
        cache_key = (texture, cols, rows_count,
                     prov_height if isinstance(prov_height, int) else 0)
        if cache_key not in advance_cache:
            cell_w, cell_h = width // cols, height // rows_count
            scale = (prov_height / cell_h) if isinstance(prov_height, int) and cell_h else 1.0
            advance_cache[cache_key] = [
                [glyph_advance_grid(width, height, pixels, cell_w, cell_h,
                                    col_index, row_index, scale)
                 for col_index in range(cols)]
                for row_index in range(rows_count)
            ]
        advances = advance_cache[cache_key]
        for row_index, row in enumerate(rows_chars):
            for col_index, char in enumerate(row):
                measured[char] = advances[row_index][col_index]
    expected = ",".join(f"{ord(ch):x}:{adv}" for ch, adv in sorted(measured.items()))
    if not METRICS_FILE.is_file():
        problems.append("нет font-metrics.properties — запусти tools/gen_font_metrics.py")
        return
    text = METRICS_FILE.read_text(encoding="utf-8")
    actual = next((line[len("advances="):] for line in text.splitlines()
                   if line.startswith("advances=")), "")
    if actual != expected:
        problems.append("font-metrics.properties разошёлся с атласами —"
                        " запусти tools/gen_font_metrics.py")


def check_glyph_fits_atlas(font: dict, names: set[str], zf: zipfile.ZipFile,
                           problems: list[str]) -> None:
    """
    Глиф должен влезть в атлас шрифта клиента.

    <p>Клиент запекает bitmap-глифы в FontTexture размером ровно 256x256
    (FontTexture.SIZE = 256), а DynamicAtlasTree делит атлас пополам: глиф
    шире или выше 256 не влезет уже никуда. FontSet подменяет незапечённый
    глиф на SpecialGlyphs.MISSING, то есть на пустой квадратик, — шрифт при
    этом загружается без ошибок, и в логе ничего не видно.</p>
    """
    atlas = 256
    image_sizes: dict[str, tuple[int, int]] = {}
    for provider in font.get("providers", []):
        if provider.get("type") != "bitmap":
            continue
        file = provider.get("file", "").split(":", 1)[-1]
        texture = f"assets/servermmo/textures/{file}"
        if texture not in names:
            continue
        rows_chars = provider.get("chars", [])
        if not rows_chars or not rows_chars[0]:
            continue
        cols, rows_count = len(rows_chars[0]), len(rows_chars)
        try:
            if texture not in image_sizes:
                image_sizes[texture] = png_size(zf.read(texture))
            width, height = image_sizes[texture]
        except ValueError:
            continue
        cell_w, cell_h = width // cols, height // rows_count
        if cell_w > atlas or cell_h > atlas:
            problems.append(
                f"{file}: глиф {cell_w}x{cell_h} не влезет в атлас шрифта "
                f"{atlas}x{atlas} — он останется незапечённым, и вместо него "
                f"будет пустой квадратик. Уменьши ячейку до {atlas} px")


def check_invisible_bar(zf: zipfile.ZipFile, problems: list[str]) -> None:
    """Полоса босс-бара под панелью должна быть полностью прозрачной."""
    for name in ("white_background.png", "white_progress.png"):
        path = f"assets/minecraft/textures/gui/sprites/boss_bar/{name}"
        try:
            width, height, pixels = png_rgba(zf.read(path))
        except (KeyError, ValueError):
            continue
        if any(pixels[i] for i in range(3, len(pixels), 4)):
            problems.append(f"полоса босс-бара видна: {name}")
        if (width, height) != (182, 5):
            problems.append(f"{name}: {width}x{height}, у ванильной полосы 182x5")


def main() -> int:
    archive = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_ZIP
    if not archive.is_file():
        print(f"[ERROR] нет файла {archive}")
        return 1
    problems: list[str] = []
    with zipfile.ZipFile(archive) as zf:
        names = set(zf.namelist())
        for name in REQUIRED:
            if name not in names:
                problems.append(f"в паке нет {name}")

        font_name = "assets/servermmo/font/hud.json"
        if font_name in names:
            font = json.loads(zf.read(font_name))
            bitmaps = 0
            for provider in font.get("providers", []):
                if provider.get("type") != "bitmap":
                    continue
                bitmaps += 1
                height = provider.get("height")
                if not isinstance(height, int) or height <= 0:
                    problems.append(f"нет height в {provider.get('file')}")
                    continue
                ascent = provider.get("ascent")
                if not isinstance(ascent, int) or ascent > height:
                    problems.append(f"ascent={ascent} больше height={height} "
                                    f"в {provider.get('file')} — игра такое не примет")
                rows_chars = provider.get("chars", [])
                cols = len(rows_chars[0]) if rows_chars else 0
                rows_count = len(rows_chars)
                if not cols or any(len(row) != cols for row in rows_chars):
                    problems.append(f"{provider.get('file')}: неровная сетка chars")
                    continue
                file = provider.get("file", "").split(":", 1)[-1]
                texture = f"assets/servermmo/textures/{file}"
                if texture not in names:
                    problems.append(f"нет текстуры {texture}")
                    continue
                width, image_height = png_size(zf.read(texture))
                # Ячейка = (ширина / столбцов) x (высота / строк); height из
                # провайдера её масштабирует. Для цифр и букв это 8x8, для
                # значков 32x32, для панелей классов 256x64.
                if image_height % rows_count != 0 or width % cols != 0:
                    problems.append(f"{texture}: {width}x{image_height} не делится "
                                    f"на сетку {cols}x{rows_count}")
                    continue
                cell_w, cell_h = width // cols, image_height // rows_count
                # height меньше ячейки — значок просто рисуется мельче
                # (равномерный масштаб, без искажения); height больше ячейки
                # растянет глиф по вертикали — такое не годится.
                if height > cell_h:
                    problems.append(f"{texture}: ячейка {cell_w}x{cell_h}, а height="
                                    f"{height} — значок растянется по вертикали")
                if height != GLYPH_HEIGHT and cell_h == GLYPH_HEIGHT:
                    problems.append(f"{texture}: ячейка {cell_w}x{cell_h}, а height="
                                    f"{height} — игра растянет её, нужна ячейка "
                                    f"высотой {height}")
            if bitmaps == 0:
                problems.append("в шрифте нет ни одного bitmap-провайдера")

            # один и тот же символ не должен быть в двух провайдерах:
            # второй молча игнорируется, и текстура «пропадает»
            seen: dict[str, str] = {}
            for provider in font.get("providers", []):
                if provider.get("type") != "bitmap":
                    continue
                for row in provider.get("chars", []):
                    for char in row:
                        if char in seen:
                            problems.append(f"символ {hex(ord(char))} объявлен дважды:"
                                            f" {seen[char]} и {provider.get('file')}")
                        else:
                            seen[char] = provider.get("file", "?")
            if any(ch not in seen for ch in "0123456789/"):
                problems.append("в шрифте нет всех цифр и дроби для опыта")
            if any(ch not in seen for ch in "LVXP"):
                problems.append("в шрифте нет букв LVL/XP для строк HUD")
            check_space_glyphs(font, problems)
            check_texty_glyphs(font, problems)
            check_codepoint_grid(font, problems)
            check_shift_range(font, problems)
            check_glyph_fits_atlas(font, names, zf, problems)
            check_metrics(font, names, zf, problems)

            # чернила на одной базовой линии с ванильным шрифтом
            for provider in font.get("providers", []):
                if provider.get("type") != "bitmap":
                    continue
                file = provider.get("file", "").split(":", 1)[-1]
                texture = f"assets/servermmo/textures/{file}"
                if texture not in names:
                    continue
                # На базовой линии сверяем только 8-пиксельные глифы — те,
                # что стоят в одной строке с ванильным текстом. Крупные значки
                # намеренно выступают ниже строки, это не ошибка.
                if provider.get("height") != GLYPH_HEIGHT:
                    continue
                cells = sum(len(row) for row in provider.get("chars", []))
                try:
                    width, height, pixels = png_rgba(zf.read(texture))
                except ValueError as exc:
                    problems.append(f"{texture}: {exc}")
                    continue
                check_ink_rows(width, height, pixels, cells, texture, problems)

        for name in names:
            if "/heart/" in name and name.endswith(".png"):
                if not png_is_blank(zf.read(name)):
                    problems.append(f"сердце не скрыто: {name}")
            if name.endswith("/icons.png") and not png_is_blank(zf.read(name)):
                problems.append("старые сердца не скрыты: icons.png")

        check_invisible_bar(zf, problems)

        hidden = sum(1 for n in names if "/heart/" in n and n.endswith(".png"))
        # в 1.21.9/26.3 ванильных спрайтов сердец 47; меньше — значит список
        # имён устарел и часть сердец останется на экране
        if hidden < 47:
            problems.append(f"скрыто только {hidden} спрайтов сердец (нужно 47)")

    if problems:
        print("[ERROR] Ресурспак не готов:")
        for problem in problems:
            print("  -", problem)
        return 1
    print(f"[OK] Ресурспак в порядке: {len(names)} файлов, сердец скрыто {hidden}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
