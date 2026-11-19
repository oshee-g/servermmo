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
ICON_HEIGHT = 24
# ascent — сколько пикселей значка выше базовой линии. Текст 8 px с ascent 7
# занимает строки -7..0, его середина на -3.5; значит середина значка 24 px
# должна быть там же: ascent - 12 = -3.5 -> 15 (с округлением). Значки 24 px,
# а не 32: 32-пиксельные задевали декоративную рамку панели — игрок просил
# поменьше, чтобы всё сидело на пустом поле.
ICON_ASCENT = 15
# Значки хотбара — ячейки 20x20, как ячейки самого хотбара при масштабе
# интерфейса 2 (слот 16 px + рамка 2 px с каждой стороны). Все значки
# хотбара одного размера: сердце, меч, щит и способности.
HOTBAR_HEIGHT = 20
# Ascent 0: значки опущены на 13 px ниже, вплотную к ячейкам хотбара
# (игрок просил прижать строку к ячейкам). Цифры хотбара идут отдельными
# глифами с тем же центром (HOTBAR_DIGIT_ASCENT), чтобы не отрывались от
# значков: строка экшн-бара у клиента на фиксированной высоте, и опустить
# можно только содержимое.
HOTBAR_ASCENT = 0
# Цифры хотбара: тот же атлас hud_digits.png, но ascent -6 — центр цифры
# на той же высоте, что и центр значка 20 px (ascent 0): -(-6) + 4 = 10.
HOTBAR_DIGIT_ASCENT = -6
# Панель класса — подложка под все четыре строки HUD, ячейка 256x96 (высота
# 96, а не исходные 64: расчёт блока строк см. в PANEL в
# tools/build_icon_atlases.py — нужно накрыть 77 px и дать панели запас по
# высоте). Ширина 256 — предел атласа шрифта клиента 256x256: глиф шире не
# влезет, поэтому панель не расширяем, а делаем выше.
# Её горизонталь задаёт PANEL_PAD_LEFT и расчёт сдвига в HudText.java.
# Её вертикаль задаёт ascent: глиф рисуется от -ascent до -ascent + height.
# Панель живёт в первой строке босс-бара, а текст трёх строк ложится на
# неё: строка 1 занимает -15..9 (значки 24 px с ascent 15), строка 3
# уходит до 47. Сама пластина в арте начинается на 22-й строке ячейки
# (у воина — самая низкая верхняя кромка), поэтому, чтобы кромка была
# не ниже верха первой строки, нужен ascent не меньше 22 + 15 = 37:
# PANEL_ASCENT = 37 ставит кромку пластины ровно на верх строки 1, а
# низ пластины (89) остаётся на 5 px ниже низа строки 3 (47).
PANEL_HEIGHT = 96
PANEL_ASCENT = 37

# Кодовые точки. Занято: 0xE010/11/13 (старые значки), 0xE100-0xE10C
# (пробелы), 0xE110+ (сдвиги вправо), 0xE120+ (сдвиги влево),
# 0xE200-0xE24B (ступени затухания урона). Новое — с 0xE300.
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
ABILITY_CHARS = chars(0xE320, 8)    # восемь слотов способностей
LEVEL_CHARS = chars(0xE400, 100)    # ромбы уровней игрока 1..100
CLAN_CHARS = chars(0xE470, 100)     # щиты уровней клана 1..100
PANEL_CHARS = chars(0xE500, 8)      # панели классов
LEVEL_COLS = 10                     # сетка 10x10
PANEL_COLS = 2                      # сетка 2x4


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
FADE_STEPS = 5


def fade_chars(step: int) -> str:
    base = 0xE200 + step * 16
    return "".join(chr(base + i) for i in range(12))


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
            {
                "type": "bitmap",
                "file": "servermmo:font/hud_panel.png",
                "ascent": PANEL_ASCENT,
                "height": PANEL_HEIGHT,
                "chars": grid(PANEL_CHARS, PANEL_COLS),
            },
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
                 "hud_level.png", "hud_clan.png", "hud_panel.png"):
        copy_asset(name, textures / name)
    copy_asset("pack_icon.png", PACK / "pack.png")

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
