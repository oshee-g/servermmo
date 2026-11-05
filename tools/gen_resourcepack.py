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

GLYPH_HEIGHT = 8  # ячейка 8x8, как у ванильного шрифта
DIGIT_CHARS = "0123456789/ "
ICON_CHARS = "\ue010\ue011\ue012\ue013"  # сердце, меч, щит, кристалл
HEART_CHAR = "\ue010"

# Ванильные спрайты HUD, которые надо спрятать. Список снят с настоящих
# ресурсов 1.21.9: имена с суффиксом _blinking появились в 1.21.2, а
# vehicle_* рисуются, когда игрок едет на мобе.
HEART_STATES = ["full", "half", "container",
                "poisoned_full", "poisoned_half", "poisoned_container",
                "withered_full", "withered_half", "withered_container",
                "frozen_full", "frozen_half", "frozen_container",
                "absorbing_full", "absorbing_half", "absorbing_container"]
HEART_TYPES = ["", "hardcore_", "vehicle_"]
HEART_VARIANTS = ["", "_blinking"]

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


def heart_sprites() -> list[str]:
    names = []
    for kind in HEART_TYPES:
        for state in HEART_STATES:
            for variant in HEART_VARIANTS:
                names.append(f"{kind}{state}{variant}")
    return sorted(set(names))


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
                "file": "servermmo:font/hud_icons.png",
                "ascent": 7,
                "height": GLYPH_HEIGHT,
                "chars": [ICON_CHARS],
            },
            {
                "type": "bitmap",
                "file": "servermmo:font/hud_heart.png",
                "ascent": 7,
                "height": GLYPH_HEIGHT,
                "chars": [HEART_CHAR],
            },
            {
                "type": "space",
                "advances": {
                    # служебные символы: тонкая подгонка отступов
                    "\ue100": 2,
                    "\ue101": 4,
                    "\ue102": -1,
                    "\ue103": -2,
                },
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

    for name in ("hud_digits.png", "hud_icons.png", "hud_heart.png"):
        copy_asset(name, textures / name)
    copy_asset("pack_icon.png", PACK / "pack.png")

    # --- ванильные сердца и полосы -----------------------------------------
    gui = PACK / "assets" / "minecraft" / "textures" / "gui"
    heart_dir = gui / "sprites" / "hud" / "heart"
    heart_dir.mkdir(parents=True, exist_ok=True)
    hearts = heart_sprites()
    for name in hearts:
        write_blank_png(heart_dir / f"{name}.png", 9, 9)
    for name, (width, height) in EXTRA_HUD_SPRITES.items():
        write_blank_png(gui / "sprites" / "hud" / name, width, height)
    # Старые клиенты рисуют сердца из одного атласа: пустая текстура 256x256
    # гасит и его. Новые спрайты берутся выше.
    write_blank_png(gui / "icons.png", 256, 256)

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
