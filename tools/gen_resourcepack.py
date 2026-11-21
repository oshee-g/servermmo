#!/usr/bin/env python3
"""Собирает ресурспак ServerMMO HUD.

Арт (панель, иконки, цифры) готовится скриптом
``tools/build_hud_assets.py`` (там используется Pillow и ИИ-исходники из
``tools/assets_src/``), а сюда попадают уже готовые PNG из
``tools/pack_assets/``. Поэтому этот скрипт работает без сторонних
библиотек и запускается в CI.

Что делает:
  * раскладывает панель, иконки и цифры в ``assets/servermmo/textures/font``;
  * пишет ``assets/servermmo/font/hud.json`` — шрифт HUD;
  * скрывает ванильные сердца, броню, голод и полосу опыта:
    пустые спрайты в ``assets/minecraft/textures/gui/sprites/hud`` и
    пустая текстура ``assets/minecraft/textures/gui/icons.png``;
  * собирает ``server/resourcepack/servermmo-hud.zip`` детерминированно
    (фиксированные таймстампы → стабильный SHA-1).

Запуск:  python3 tools/gen_resourcepack.py
"""
from __future__ import annotations

import json
import shutil
import struct
import zipfile
import zlib
from pathlib import Path

from png_io import png_rgba

ROOT = Path(__file__).resolve().parent.parent
PACK = ROOT / "resourcepack"
ASSETS = ROOT / "tools" / "pack_assets"
ZIP_OUT = ROOT / "server" / "resourcepack" / "servermmo-hud.zip"

GLYPH_HEIGHT = 8  # ячейка 8x8, как у ванильного шрифта: цифры и буквы
DIGIT_CHARS = "0123456789/ "
# Значки: сердце, меч, кристалл и три буквы LVL / XP — всё одной сеткой.
ICON_CHARS = "\ue010\ue011\ue013"
LETTER_CHARS = "LVXP"

# --- Значки 32x32 ------------------------------------------------------
# Игрок решил: шрифт (цифры, буквы) остаётся 8 px, а значки рисуются
# ячейкой 32x32 — в 4 раза крупнее, чтобы оружие, монеты и ромб уровня
# читались. height задаёт и размер ячейки, и масштаб: ячейка 32x32 при
# height=32 рисуется 1:1 (pixelScale = height / высота_ячейки).
ICON_HEIGHT = 14
# ascent — сколько пикселей значка выше базовой линии. Текст 8 px с ascent 7
# занимает строки -7..0, его середина на -3.5; значит середина значка 14 px
# должна быть там же: ascent - 7 = -3.5 -> 9 (с округлением). Значки 14 px:
# при 16 px верхняя строка всё равно выступала над серой плиткой и свиток
# уходил за край экрана (базовая линия босс-бара у самого верха) — игрок
# просил, чтобы всё было только на серой плитке.
ICON_ASCENT = 9
# Значки групп по краям — ячейки 20x20; центральная пустая рамка — 24x24.
HOTBAR_HEIGHT = 20
HOTBAR_CENTER_HEIGHT = 24
HOTBAR_CENTER_ASCENT = -34  # опускаем из строки actionbar к центру слота
# Ascent 0: значки опущены на 13 px ниже, вплотную к ячейкам хотбара
# (игрок просил прижать строку к ячейкам). Цифры хотбара идут отдельными
# глифами с тем же центром (HOTBAR_DIGIT_ASCENT), чтобы не отрывались от
# значков: строка экшн-бара у клиента на фиксированной высоте, и опустить
# можно только содержимое.
HOTBAR_ASCENT = 0
# Цифры хотбара: тот же атлас hud_digits.png, но ascent -6 — центр цифры
# на той же высоте, что и центр значка 20 px (ascent 0): -(-6) + 4 = 10.
HOTBAR_DIGIT_ASCENT = -6
FADE_STEPS = 5
HOTBAR_VARIANT_HEIGHT = 128
# Панель класса — подложка под все четыре строки HUD, ячейка 256x96 (высота
# 96, а не исходные 64: расчёт блока строк см. в PANEL в
# tools/build_icon_atlases.py — нужно накрыть 77 px и дать панели запас по
# высоте). Ширина 256 — предел атласа шрифта клиента 256x256: глиф шире не
# влезет, поэтому панель не расширяем, а делаем выше.
# Её горизонталь задаёт PANEL_PAD_LEFT и расчёт сдвига в HudText.java.
# Её вертикаль задаёт ascent: глиф рисуется от -ascent до -ascent + height.
# Панель живёт в первой строке босс-бара, а текст трёх строк ложится на
# неё. Значки теперь 16 px (ascent 11): строка 1 занимает -11..5, строка 3
# уходит до 43, весь контент 54 px. Серая плитка в арте — строки 24..85
# ячейки (у воина), поэтому, чтобы она накрывала контент, нужен ascent
# не меньше 24 + 11 = 35. Но большой подъём уводит верх панели за край
# экрана. Компромисс — ascent 33: серая плитка на 2 px ниже верха значков
# строки 1 (их верх чуть выступает, но контент на плитке), а верх панели
# обрезан минимально (только рога декора).
PANEL_HEIGHT = 96
PANEL_ASCENT = 33

# Старые диапазоны: 0xE010/11/13 (значки), 0xE100-0xE10C (пробелы),
# 0xE110+ и 0xE120+ (сдвиги), 0xE200-0xE24B (затухание урона),
# 0xE300+ (значки панели/хотбара). Варианты HUD занимают supplementary PUA.
def chars(base: int, count: int) -> str:
    return "".join(chr(base + i) for i in range(count))


# Цифры хотбара: те же PNG, что и цифры панели, но отдельные кодовые точки
# и ниже (ascent -1) — чтобы цифры строки экшн-бара стояли по центру
# опущенных значков, а не выше них. Панель цифры не трогаем.
HOTBAR_DIGIT_CHARS = chars(0xE600, len(DIGIT_CHARS))   # 0-9, /, пробел
# Ступени затухания урона для хотбара: те же атласы hud_fade*.png, но ниже.
def hotbar_fade_chars(step: int) -> str:
    base = 0xE610 + step * 16
    return "".join(chr(base + i) for i in range(12))


GEAR_CHARS = chars(0xE300, 10)      # сердце, меч, топор, посох, молот,
                                    # коса, вилы, лук, копьё, щит
MONEY_CHARS = chars(0xE310, 6)      # монеты, коины, нанагемы, клан, свиток,
                                    # боевые очки
ABILITY_CHARS = chars(0xE320, 8)    # восемь квадратных иконок справа от хотбара
CENTER_DIAMOND_CHAR = "\ue328"    # одна ромб-рамка в центральном слоте хотбара
LEVEL_CHARS = chars(0xE400, 100)    # ромбы уровней игрока 1..100
CLAN_CHARS = chars(0xE470, 100)     # щиты уровней клана 1..100
PANEL_OFFSET_RANGE = 100           # /hud offsety: панель -100..+100 px
ICON_OFFSET_RANGE = 100             # /hud texty: текст -100..+100 px
PANEL_CLASS_COUNT = 10
TEXT_COLS = 19
TEXT_HEIGHT = 16
TEXT_ASCENT = 6
TEXT_VARIANT_HEIGHT = 128
INLINE_VARIANT_HEIGHT = 128
PANEL_VARIANT_HEIGHT = 160
PUA_START = 0xF0000
PUA_A_COUNT = 0xFFFFE - PUA_START

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

# База: глифы, столбцы, строки исходного атласа, высота рисования, ascent.
INLINE_GLYPHS = (
    ("hud_digits_inline", DIGIT_CHARS, 12, 1, 16, 7),
    ("hud_money", MONEY_CHARS, 6, 1, ICON_HEIGHT, ICON_ASCENT),
    ("hud_level", LEVEL_CHARS, 10, 10, ICON_HEIGHT, ICON_ASCENT),
    ("hud_clan", CLAN_CHARS, 10, 10, ICON_HEIGHT, ICON_ASCENT),
)
INLINE_GLYPH_COUNT = sum(len(glyphs) for _, glyphs, *_ in INLINE_GLYPHS)
VARIANT_COUNT = 2 * ICON_OFFSET_RANGE + 1
INLINE_START_INDEX = 0
INLINE_TOTAL = VARIANT_COUNT * INLINE_GLYPH_COUNT
PANEL_START_INDEX = INLINE_TOTAL
PANEL_VARIANT_GLYPH_COUNT = PANEL_CLASS_COUNT
PANEL_TOTAL = VARIANT_COUNT * PANEL_VARIANT_GLYPH_COUNT
TEXT_START_INDEX = PANEL_START_INDEX + PANEL_TOTAL
TEXT_VARIANT_TOTAL = VARIANT_COUNT * len(TEXT_CHARS)
TEXT_SMALL_START_INDEX = TEXT_START_INDEX + TEXT_VARIANT_TOTAL
TEXT_VARIANT_END_INDEX = TEXT_SMALL_START_INDEX + TEXT_VARIANT_TOTAL
HOTBAR_LEFT_GEAR_COUNT = 3
HOTBAR_LEFT_DIGIT_COUNT = 10
HOTBAR_LEFT_GEAR_OFFSET = 0
HOTBAR_LEFT_DIGIT_OFFSET = HOTBAR_LEFT_GEAR_COUNT
HOTBAR_LEFT_FADE_OFFSET = HOTBAR_LEFT_GEAR_COUNT + HOTBAR_LEFT_DIGIT_COUNT
HOTBAR_LEFT_VARIANT_GLYPH_COUNT = HOTBAR_LEFT_FADE_OFFSET + FADE_STEPS * 12
HOTBAR_LEFT_DIGIT_FADE_COUNT = HOTBAR_LEFT_VARIANT_GLYPH_COUNT - HOTBAR_LEFT_DIGIT_OFFSET
HOTBAR_LEFT_START_INDEX = TEXT_VARIANT_END_INDEX
HOTBAR_LEFT_TOTAL = VARIANT_COUNT * HOTBAR_LEFT_VARIANT_GLYPH_COUNT
HOTBAR_RIGHT_START_INDEX = HOTBAR_LEFT_START_INDEX + HOTBAR_LEFT_TOTAL
HOTBAR_RIGHT_VARIANT_GLYPH_COUNT = len(ABILITY_CHARS)
HOTBAR_RIGHT_TOTAL = VARIANT_COUNT * HOTBAR_RIGHT_VARIANT_GLYPH_COUNT
PUA_TOTAL_USED = HOTBAR_RIGHT_START_INDEX + HOTBAR_RIGHT_TOTAL
if PUA_TOTAL_USED > 2 * PUA_A_COUNT:
    raise ValueError(f"HUD-глифы требуют {PUA_TOTAL_USED} PUA-кодов, доступно "
                     f"{2 * PUA_A_COUNT}")


def pua_codepoint(index: int) -> int:
    if index < 0 or index >= 2 * PUA_A_COUNT:
        raise ValueError(f"PUA-индекс вне диапазона: {index}")
    if index < PUA_A_COUNT:
        return PUA_START + index
    return 0x100000 + index - PUA_A_COUNT


def pua_chars(start: int, count: int) -> str:
    return "".join(chr(pua_codepoint(start + i)) for i in range(count))


def variant_index(offset: int) -> int:
    return max(-ICON_OFFSET_RANGE, min(ICON_OFFSET_RANGE, offset)) + ICON_OFFSET_RANGE


def panel_chars(variant: int) -> str:
    start = PANEL_START_INDEX + variant * PANEL_VARIANT_GLYPH_COUNT
    return pua_chars(start, PANEL_CLASS_COUNT)


LEVEL_COLS = 10
PANEL_COLS = 2


def grid(chars_row: str, cols: int) -> list[str]:
    """
    Режет строку символов на ряды по cols столбцов (как в атласе).

    <p>Важно: каждый ряд — одна строка, а не список отдельных символов.
    Кодек клиента читает поле chars как Codec.STRING.listOf(), то есть
    ждёт массив строк; на вложенный массив он отвечает
    "Not a string: [...]", и тогда падает весь файл шрифта целиком
    (FontManager.loadResourceStack вызывает getOrThrow), а не один
    провайдер. Так пак и приехал с одними пустыми квадратиками.</p>
    """
    return [chars_row[i:i + cols] for i in range(0, len(chars_row), cols)]

# Ванильные спрайты HUD, которые надо спрятать. Список снят с настоящих
# ресурсов 1.21.9: имена с суффиксом _blinking появились в 1.21.2, а
# vehicle_* рисуются, когда игрок едет на мобе.
# Точные имена спрайтов сердец из ванильных ассетов 1.21.9 (формат пака
# тот же, что у 26.3). Обрати внимание: у полных сердец префикс
# hardcore_, а у контейнеров - суффикс _hardcore, отсюда список целиком,
# а не генерация по шаблону.
HEART_SPRITES = [
    "container", "container_blinking",
    "container_hardcore", "container_hardcore_blinking",
    "full", "full_blinking",
    "half", "half_blinking",
    "hardcore_full", "hardcore_full_blinking",
    "hardcore_half", "hardcore_half_blinking",
    "absorbing_full", "absorbing_full_blinking",
    "absorbing_half", "absorbing_half_blinking",
    "absorbing_hardcore_full", "absorbing_hardcore_full_blinking",
    "absorbing_hardcore_half", "absorbing_hardcore_half_blinking",
    "frozen_full", "frozen_full_blinking",
    "frozen_half", "frozen_half_blinking",
    "frozen_hardcore_full", "frozen_hardcore_full_blinking",
    "frozen_hardcore_half", "frozen_hardcore_half_blinking",
    "poisoned_full", "poisoned_full_blinking",
    "poisoned_half", "poisoned_half_blinking",
    "poisoned_hardcore_full", "poisoned_hardcore_full_blinking",
    "poisoned_hardcore_half", "poisoned_hardcore_half_blinking",
    "withered_full", "withered_full_blinking",
    "withered_half", "withered_half_blinking",
    "withered_hardcore_full", "withered_hardcore_full_blinking",
    "withered_hardcore_half", "withered_hardcore_half_blinking",
    "vehicle_container", "vehicle_full", "vehicle_half",
]

EXTRA_HUD_SPRITES = {
    "armor_empty.png": (9, 9),
    "armor_full.png": (9, 9),
    "armor_half.png": (9, 9),
    "food_empty.png": (9, 9),
    "food_full.png": (9, 9),
    "food_half.png": (9, 9),
    "food_empty_hunger.png": (9, 9),
    "food_full_hunger.png": (9, 9),
    "food_half_hunger.png": (9, 9),
    "experience_bar_background.png": (182, 5),
    "experience_bar_progress.png": (182, 5),
}


def padded_digit_atlas(data: bytes) -> tuple[int, int, bytes]:
    """Помещает исходные 8x8 цифры в прозрачные ячейки 16x16.

    Это сохраняет пиксельный размер цифр и позволяет ascent-вариантам
    /hud texty иметь диапазон выше исходной высоты глифа.
    """
    width, height, source = png_rgba(data)
    cols = len(DIGIT_CHARS)
    cell = width // cols
    if width % cols or height != cell:
        raise ValueError("hud_digits.png должен быть сеткой из 12 квадратных глифов")
    out_cell = 16
    out_width, out_height = cols * out_cell, out_cell
    target = bytearray(out_width * out_height * 4)
    for index in range(cols):
        for y in range(cell):
            src_start = (y * width + index * cell) * 4
            dst_start = (y * out_width + index * out_cell) * 4
            target[dst_start:dst_start + cell * 4] = source[src_start:src_start + cell * 4]
    return out_width, out_height, bytes(target)


def select_atlas_cells(data: bytes, cols: int, indices: tuple[int, ...]) -> tuple[int, int, bytes]:
    """Copy selected cells into a one-row atlas, preserving their pixel art."""
    width, height, source = png_rgba(data)
    if width % cols:
        raise ValueError(f"атлас {width}x{height} не делится на {cols} столбцов")
    cell_width, cell_height = width // cols, height
    if any(index < 0 or index >= cols for index in indices):
        raise ValueError(f"ячейка вне диапазона 0..{cols - 1}: {indices}")
    output_width = cell_width * len(indices)
    target = bytearray(output_width * cell_height * 4)
    for out_col, source_col in enumerate(indices):
        for y in range(cell_height):
            src = (y * width + source_col * cell_width) * 4
            dst = (y * output_width + out_col * cell_width) * 4
            target[dst:dst + cell_width * 4] = source[src:src + cell_width * 4]
    return output_width, cell_height, bytes(target)


def hotbar_icon_atlas_data() -> bytes:
    """One 11-glyph atlas for left stats icons and the square ability icons."""
    gear_cols = (0, 1, 9)
    gear_w, gear_h, gear = select_atlas_cells(
        (ASSETS / "hud_gear.png").read_bytes(), len(GEAR_CHARS), gear_cols)
    ability_w, ability_h, abilities = select_atlas_cells(
        (ASSETS / "hud_ability.png").read_bytes(), len(ABILITY_CHARS),
        tuple(range(len(ABILITY_CHARS))))
    if gear_h != ability_h:
        raise ValueError("иконки хотбара должны иметь одинаковую высоту ячейки")
    width = gear_w + ability_w
    pixels = bytearray(width * gear_h * 4)
    for y in range(gear_h):
        row_bytes = gear_w * 4
        pixels[y * width * 4:y * width * 4 + row_bytes] = \
            gear[y * gear_w * 4:(y + 1) * gear_w * 4]
        start = (y * width + gear_w) * 4
        pixels[start:start + ability_w * 4] = \
            abilities[y * ability_w * 4:(y + 1) * ability_w * 4]
    return encode_rgba_png(width, gear_h, bytes(pixels))


def hotbar_digits_fade_atlas_data() -> bytes:
    """Pack 10 digits and five 12-glyph damage sets into one bitmap atlas."""
    cols = 14
    cell_width = GLYPH_HEIGHT
    cell_height = HOTBAR_VARIANT_HEIGHT
    count = HOTBAR_LEFT_DIGIT_FADE_COUNT
    rows = (count + cols - 1) // cols
    width, height = cols * cell_width, rows * cell_height
    pixels = bytearray(width * height * 4)
    sources = [((ASSETS / "hud_digits.png").read_bytes(), tuple(range(10)))]
    sources.extend(((ASSETS / f"hud_fade{step}.png").read_bytes(), tuple(range(12)))
                   for step in range(FADE_STEPS))
    out_index = 0
    for data, indices in sources:
        source_width, source_height, source = png_rgba(data)
        source_cols = source_width // cell_width
        if source_height != cell_width or source_width % cell_width:
            raise ValueError(f"цифровой атлас имеет неожиданную сетку {source_width}x{source_height}")
        for source_index in indices:
            col, row = out_index % cols, out_index // cols
            for y in range(source_height):
                src = (y * source_width + source_index % source_cols * cell_width) * 4
                dst = ((row * cell_height + 6 + y) * width + col * cell_width) * 4
                pixels[dst:dst + cell_width * 4] = source[src:src + cell_width * 4]
            out_index += 1
    return encode_rgba_png(width, height, bytes(pixels))


def padded_atlas(data: bytes, cols: int, rows: int, target_cell_height: int,
                 draw_height: int | None = None) -> tuple[int, int, bytes]:
    """Pad cells for large ascents, preserving the provider's original scale."""
    width, height, source = png_rgba(data)
    if width % cols or height % rows:
        raise ValueError(f"атлас {width}x{height} не делится на сетку {cols}x{rows}")
    cell_width, cell_height = width // cols, height // rows
    draw_height = cell_height if draw_height is None else draw_height
    if target_cell_height < draw_height:
        raise ValueError(f"высота ячейки {target_cell_height} меньше глифа {draw_height}")
    if draw_height > cell_height:
        raise ValueError(f"нельзя растянуть ячейку {cell_height} до {draw_height}")
    draw_width = max(1, round(cell_width * draw_height / cell_height))
    target_height = rows * target_cell_height
    target = bytearray(width * target_height * 4)
    for row in range(rows):
        for col in range(cols):
            for y in range(draw_height):
                source_y = min(cell_height - 1, int(y * cell_height / draw_height))
                for x in range(draw_width):
                    source_x = min(cell_width - 1, int(x * cell_width / draw_width))
                    src = ((row * cell_height + source_y) * width
                           + col * cell_width + source_x) * 4
                    dst = ((row * target_cell_height + y) * width
                           + col * cell_width + x) * 4
                    target[dst:dst + 4] = source[src:src + 4]
    return width, target_height, bytes(target)


def encode_rgba_png(width: int, height: int, pixels: bytes) -> bytes:
    raw = bytearray()
    stride = width * 4
    for y in range(height):
        raw.append(0)
        raw.extend(pixels[y * stride:(y + 1) * stride])

    def chunk(kind: bytes, payload: bytes) -> bytes:
        return (struct.pack(">I", len(payload)) + kind + payload
                + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF))

    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
            + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))


def write_rgba_png(path: Path, width: int, height: int, pixels: bytes) -> None:
    path.write_bytes(encode_rgba_png(width, height, pixels))


def write_blank_png(path: Path, width: int, height: int) -> None:
    """Полностью прозрачный PNG без зависимостей."""
    raw = bytearray()
    stride = width * 4
    for _y in range(height):
        raw.append(0)
        raw += b"\x00" * stride

    def chunk(kind: bytes, payload: bytes) -> bytes:
        return (struct.pack(">I", len(payload)) + kind + payload
                + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF))

    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
                     + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))


# Пустые глифы для раскладки панели (ширина в пикселях шрифта).
BLANK_ADVANCES = {
    " ": 4,          # обычный пробел в тексте панели (глифы hud_text)
    "\ue100": 2,    # щель между иконкой и числом
    "\ue101": 4,
    "\ue10a": 6,    # ровно ширина цифры
    "\ue10b": 10,   # дотягивает иконку 8 px до ширины "LVL" (18 px)
    "\ue10c": 8,    # щель между блоками панели
    "\ue102": -1,   # микроподгонка
    "\ue103": -2,
}
# Сдвиги: \ue110+i = +2^i, \ue120+i = -2^i. Из них собирается любой сдвиг
# панели по горизонтали — так панель ставится в левый верхний угол.
#
# Старший бит 11 даёт ±2048, то есть любой сдвиг в пределах ±4095 px.
# Десяти бит (±1023) не хватало: чтобы дотянуть панель до левого края при
# масштабе интерфейса 1 на экране 1920 px, нужен сдвиг около -1700 px, а на
# широком (3440 px) — и того больше. Не дотянув, панель остаётся посередине
# экрана, и кажется, что HUD не работает.
SHIFT_MIN_BIT = 0
SHIFT_MAX_BIT = 11


def build_space_advances() -> dict:
    """Таблица пробелов: пустые глифы и сдвиги для раскладки HUD."""
    advances = dict(BLANK_ADVANCES)
    for bit in range(SHIFT_MIN_BIT, SHIFT_MAX_BIT + 1):
        advances[chr(0xE110 + bit)] = 1 << bit
        advances[chr(0xE120 + bit)] = -(1 << bit)
    return dict(sorted(advances.items()))


# Ступени затухания индикатора урона: ступень L занимает кодовые точки
# 0xE200 + L*16 .. +11 (цифры, минус, сердце). Синхронно с build_hud_assets.py.
def fade_chars(step: int) -> str:
    base = 0xE200 + step * 16
    return "".join(chr(base + i) for i in range(12))


def inline_glyph_groups():
    offset = 0
    for group in INLINE_GLYPHS:
        stem, glyphs, cols, rows, draw_h, ascent = group
        yield stem, glyphs, cols, rows, draw_h, ascent, offset
        offset += len(glyphs)


def build_font_json() -> dict:
    """Шрифт HUD.

    Важно: в bitmap-провайдере ячейка глифа квадратная (height x height), а
    ширина берётся по последнему непрозрачному пикселю + 1. Поэтому все
    текстуры — сетки 8x8, а не длинные полосы.
    """
    return {
        "providers": [
            {
                "type": "bitmap",
                "file": "servermmo:font/hud_digits.png",
                "ascent": 7,
                "height": GLYPH_HEIGHT,
                "chars": [DIGIT_CHARS],
            },
            {
                "type": "bitmap",
                "file": "servermmo:font/hud_digits.png",
                "ascent": HOTBAR_DIGIT_ASCENT,
                "height": GLYPH_HEIGHT,
                "chars": [HOTBAR_DIGIT_CHARS],
            },
            {
                "type": "bitmap",
                "file": "servermmo:font/hud_icons.png",
                "ascent": 7,
                "height": GLYPH_HEIGHT,
                "chars": [ICON_CHARS],
            },
            {
                "type": "bitmap",
                "file": "servermmo:font/hud_letters.png",
                "ascent": 7,
                "height": GLYPH_HEIGHT,
                "chars": [LETTER_CHARS],
            },
            {
                "type": "bitmap",
                "file": "servermmo:font/hud_gear.png",
                "ascent": HOTBAR_ASCENT,
                "height": HOTBAR_HEIGHT,
                "chars": [GEAR_CHARS],
            },
            {
                "type": "bitmap",
                "file": "servermmo:font/hud_money.png",
                "ascent": ICON_ASCENT,
                "height": ICON_HEIGHT,
                "chars": [MONEY_CHARS],
            },
            {
                "type": "bitmap",
                "file": "servermmo:font/hud_ability.png",
                "ascent": HOTBAR_ASCENT,
                "height": HOTBAR_HEIGHT,
                "chars": [ABILITY_CHARS],
            },
            {
                "type": "bitmap",
                "file": "servermmo:font/hud_hotbar_center_offset.png",
                "ascent": HOTBAR_CENTER_ASCENT,
                "height": HOTBAR_VARIANT_HEIGHT,
                "chars": [CENTER_DIAMOND_CHAR],
            },
            {
                "type": "bitmap",
                "file": "servermmo:font/hud_level.png",
                "ascent": ICON_ASCENT,
                "height": ICON_HEIGHT,
                "chars": grid(LEVEL_CHARS, LEVEL_COLS),
            },
            {
                "type": "bitmap",
                "file": "servermmo:font/hud_clan.png",
                "ascent": ICON_ASCENT,
                "height": ICON_HEIGHT,
                "chars": grid(CLAN_CHARS, LEVEL_COLS),
            },
            *[
                {
                    "type": "bitmap",
                    "file": "servermmo:font/hud_panel_offset.png",
                    "ascent": PANEL_ASCENT + offset,
                    "height": PANEL_VARIANT_HEIGHT,
                    "chars": grid(panel_chars(variant_index(offset)), PANEL_COLS),
                }
                for offset in range(-PANEL_OFFSET_RANGE, PANEL_OFFSET_RANGE + 1)
            ],
            *[
                {
                    "type": "bitmap",
                    "file": f"servermmo:font/hud_fade{step}.png",
                    "ascent": 7,
                    "height": GLYPH_HEIGHT,
                    "chars": [fade_chars(step)],
                }
                for step in range(FADE_STEPS)
            ],
            *[
                {
                    "type": "bitmap",
                    "file": f"servermmo:font/hud_fade{step}.png",
                    "ascent": HOTBAR_DIGIT_ASCENT,
                    "height": GLYPH_HEIGHT,
                    "chars": [hotbar_fade_chars(step)],
                }
                for step in range(FADE_STEPS)
            ],
            *[
                {
                    "type": "bitmap",
                    "file": "servermmo:font/hud_hotbar_icons_offset.png",
                    "ascent": HOTBAR_ASCENT - offset,
                    "height": HOTBAR_VARIANT_HEIGHT,
                    "chars": [pua_chars(HOTBAR_LEFT_START_INDEX
                                         + variant_index(offset) * HOTBAR_LEFT_VARIANT_GLYPH_COUNT
                                         + HOTBAR_LEFT_GEAR_OFFSET,
                                         HOTBAR_LEFT_GEAR_COUNT)
                              + pua_chars(HOTBAR_RIGHT_START_INDEX
                                          + variant_index(offset) * HOTBAR_RIGHT_VARIANT_GLYPH_COUNT,
                                          HOTBAR_RIGHT_VARIANT_GLYPH_COUNT)],
                }
                for offset in range(-ICON_OFFSET_RANGE, ICON_OFFSET_RANGE + 1)
            ],
            *[
                {
                    "type": "bitmap",
                    "file": "servermmo:font/hud_hotbar_digits_fade_offset.png",
                    "ascent": HOTBAR_ASCENT - offset,
                    "height": HOTBAR_VARIANT_HEIGHT,
                    "chars": grid(pua_chars(HOTBAR_LEFT_START_INDEX
                                             + variant_index(offset) * HOTBAR_LEFT_VARIANT_GLYPH_COUNT
                                             + HOTBAR_LEFT_DIGIT_OFFSET,
                                             HOTBAR_LEFT_DIGIT_FADE_COUNT), 14),
                }
                for offset in range(-ICON_OFFSET_RANGE, ICON_OFFSET_RANGE + 1)
            ],
            *[
                {
                    "type": "bitmap",
                    "file": f"servermmo:font/{stem}_offset.png",
                    "ascent": ascent - texty,
                    "height": INLINE_VARIANT_HEIGHT,
                    "chars": grid(pua_chars(INLINE_START_INDEX
                                             + variant_index(texty) * INLINE_GLYPH_COUNT
                                             + group_offset, len(source_chars)), cols),
                }
                for texty in range(-ICON_OFFSET_RANGE, ICON_OFFSET_RANGE + 1)
                for stem, source_chars, cols, rows, draw_h, ascent, group_offset
                in inline_glyph_groups()
            ],
            *[
                {
                    "type": "bitmap",
                    "file": "servermmo:font/hud_text_offset.png",
                    "ascent": TEXT_ASCENT - texty,
                    "height": TEXT_VARIANT_HEIGHT,
                    "chars": grid(pua_chars(TEXT_START_INDEX
                                             + variant_index(texty) * len(TEXT_CHARS),
                                             len(TEXT_CHARS)), TEXT_COLS),
                }
                for texty in range(-ICON_OFFSET_RANGE, ICON_OFFSET_RANGE + 1)
            ],
            *[
                {
                    "type": "bitmap",
                    "file": "servermmo:font/hud_text_small_offset.png",
                    "ascent": TEXT_ASCENT - texty,
                    "height": TEXT_VARIANT_HEIGHT,
                    "chars": grid(pua_chars(TEXT_SMALL_START_INDEX
                                             + variant_index(texty) * len(TEXT_CHARS),
                                             len(TEXT_CHARS)), TEXT_COLS),
                }
                for texty in range(-ICON_OFFSET_RANGE, ICON_OFFSET_RANGE + 1)
            ],
            {
                "type": "space",
                # пустые глифы и сдвиги для раскладки панели HUD
                "advances": build_space_advances(),
            },
        ]
    }


def build_pack_meta() -> dict:
    """pack.mcmeta для Minecraft 26.3: формат ресурспака 97.1.

    Начиная с 1.21.9 игра читает min_format/max_format вместо pack_format.
    """
    return {
        "pack": {
            "description": "ServerMMO HUD: \u0440\u0430\u043c\u043a\u0430, "
                           "\u0438\u043a\u043e\u043d\u043a\u0438 \u0438 "
                           "\u0446\u0438\u0444\u0440\u044b",
            "min_format": [97, 1],
            "max_format": 97,
        }
    }


def copy_asset(name: str, target: Path) -> None:
    source = ASSETS / name
    if not source.is_file():
        raise SystemExit(f"Нет {source}. Сначала запусти tools/build_hud_assets.py")
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)


def main() -> int:
    if PACK.exists():
        shutil.rmtree(PACK)

    fonts = PACK / "assets" / "servermmo" / "font"
    textures = PACK / "assets" / "servermmo" / "textures" / "font"
    fonts.mkdir(parents=True, exist_ok=True)
    textures.mkdir(parents=True, exist_ok=True)

    (PACK / "pack.mcmeta").write_text(
        json.dumps(build_pack_meta(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (fonts / "hud.json").write_text(
        json.dumps(build_font_json(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    for name in ("hud_digits.png", "hud_icons.png", "hud_letters.png",
                 *(f"hud_fade{step}.png" for step in range(FADE_STEPS)),
                 "hud_gear.png", "hud_money.png", "hud_ability.png",
                 "hud_hotbar_center.png", "hud_level.png", "hud_clan.png", "hud_panel.png",
                 "hud_text.png", "hud_text_small.png"):
        copy_asset(name, textures / name)
    copy_asset("pack_icon.png", PACK / "pack.png")
    padded_width, padded_height, padded_pixels = padded_digit_atlas(
        (ASSETS / "hud_digits.png").read_bytes())
    write_rgba_png(textures / "hud_digits_inline.png",
                   padded_width, padded_height, padded_pixels)

    # У bitmap-провайдера ascent не может быть больше height. Расширяем
    # прозрачные ячейки вверх по запасу высоты, сохраняя исходный арт 1:1.
    def write_offset_data(data: bytes, target: Path, cols: int,
                          rows: int, cell_height: int, draw_height: int) -> None:
        width, height, pixels = padded_atlas(
            data, cols, rows, cell_height, draw_height)
        write_rgba_png(target, width, height, pixels)

    def write_offset_atlas(source: Path, target: Path, cols: int,
                           rows: int, cell_height: int, draw_height: int) -> None:
        write_offset_data(source.read_bytes(), target, cols, rows,
                          cell_height, draw_height)

    for stem, _glyphs, cols, rows, draw_h, _ascent in INLINE_GLYPHS:
        write_offset_atlas(textures / f"{stem}.png",
                           textures / f"{stem}_offset.png",
                           cols, rows, INLINE_VARIANT_HEIGHT, draw_h)
    text_rows = (len(TEXT_CHARS) + TEXT_COLS - 1) // TEXT_COLS
    for stem in ("hud_text", "hud_text_small"):
        write_offset_atlas(textures / f"{stem}.png",
                           textures / f"{stem}_offset.png",
                           TEXT_COLS, text_rows, TEXT_VARIANT_HEIGHT, TEXT_HEIGHT)
    write_offset_atlas(textures / "hud_panel.png",
                       textures / "hud_panel_offset.png",
                       PANEL_COLS, 5, PANEL_VARIANT_HEIGHT, PANEL_HEIGHT)
    write_offset_atlas(textures / "hud_hotbar_center.png",
                       textures / "hud_hotbar_center_offset.png",
                       1, 1, HOTBAR_VARIANT_HEIGHT, HOTBAR_CENTER_HEIGHT)

    write_offset_data(hotbar_icon_atlas_data(),
                      textures / "hud_hotbar_icons_offset.png",
                      HOTBAR_LEFT_GEAR_COUNT + HOTBAR_RIGHT_VARIANT_GLYPH_COUNT,
                      1, HOTBAR_VARIANT_HEIGHT, HOTBAR_HEIGHT)
    # Одинаковая базовая линия для этих глифов; цифры и индикатор физически
    # сдвинуты на 6 px вниз внутри ячейки, чтобы сохранить прежнее выравнивание.
    digit_fade_path = textures / "hud_hotbar_digits_fade_offset.png"
    digit_fade_path.write_bytes(hotbar_digits_fade_atlas_data())
    for stale_name in (
            "hud_hotbar_left_icons_offset.png", "hud_hotbar_digits_offset.png",
            "hud_ability_offset.png",
            *(f"hud_fade{step}_hotbar_offset.png" for step in range(FADE_STEPS))):
        (textures / stale_name).unlink(missing_ok=True)

    # --- ванильные сердца и полосы -----------------------------------------
    gui = PACK / "assets" / "minecraft" / "textures" / "gui"
    heart_dir = gui / "sprites" / "hud" / "heart"
    heart_dir.mkdir(parents=True, exist_ok=True)
    hearts = HEART_SPRITES
    for name in hearts:
        write_blank_png(heart_dir / f"{name}.png", 9, 9)
    for name, (width, height) in EXTRA_HUD_SPRITES.items():
        write_blank_png(gui / "sprites" / "hud" / name, width, height)
    # Старые клиенты рисуют сердца из одного атласа: пустая текстура 256x256
    # гасит и его. Новые спрайты берутся выше.
    write_blank_png(gui / "icons.png", 256, 256)

    # --- полоса босс-бара ------------------------------------------------
    # Панель HUD рисуется строками босс-бара (это единственный элемент
    # интерфейса, который клиент рисует вверху экрана). Сама полоса нам не
    # нужна: гасим только белый вариант, который использует наш плагин, —
    # полосы Хранителя, Дракона и рейда остаются на месте.
    boss_bar = gui / "sprites" / "boss_bar"
    boss_bar.mkdir(parents=True, exist_ok=True)
    for name in ("white_background.png", "white_progress.png"):
        write_blank_png(boss_bar / name, 182, 5)

    # --- zip для раздачи ----------------------------------------------------
    ZIP_OUT.parent.mkdir(parents=True, exist_ok=True)
    if ZIP_OUT.exists():
        ZIP_OUT.unlink()
    # Фиксированные таймстампы: одинаковый вход → одинаковый zip → стабильный SHA-1.
    with zipfile.ZipFile(ZIP_OUT, "w", zipfile.ZIP_DEFLATED) as zf:
        for path in sorted(PACK.rglob("*")):
            if not path.is_file():
                continue
            info = zipfile.ZipInfo(path.relative_to(PACK).as_posix(), date_time=(2026, 11, 5, 12, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            zf.writestr(info, path.read_bytes())

    files = sum(1 for p in PACK.rglob("*") if p.is_file())
    print(f"Ресурспак: {files} файлов, сердец скрыто: {len(hearts)}")
    print(f"архив: {ZIP_OUT.relative_to(ROOT)} ({ZIP_OUT.stat().st_size} байт)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
