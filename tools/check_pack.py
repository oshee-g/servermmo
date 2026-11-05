#!/usr/bin/env python3
"""Проверка собранного ресурспака перед публикацией.

Запускается в CI после ``tools/gen_resourcepack.py``. Проверяет то, из-за
чего HUD раньше не отображался:

* шрифт HUD на месте и все bitmap-провайдеры — квадратные ячейки 8x8;
* текстуры глифов действительно 8x8 (или кратны 8 по ширине);
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

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_ZIP = ROOT / "server" / "resourcepack" / "servermmo-hud.zip"
GLYPH_HEIGHT = 8

REQUIRED = [
    "assets/servermmo/font/hud.json",
    "assets/servermmo/textures/font/hud_digits.png",
    "assets/servermmo/textures/font/hud_icons.png",
    "assets/servermmo/textures/font/hud_heart.png",
    "assets/minecraft/textures/gui/icons.png",
    "assets/minecraft/textures/gui/sprites/hud/heart/full.png",
    "assets/minecraft/textures/gui/sprites/hud/heart/half.png",
    "assets/minecraft/textures/gui/sprites/hud/heart/container.png",
    "assets/minecraft/textures/gui/sprites/hud/heart/hardcore_full_blinking.png",
    "assets/minecraft/textures/gui/sprites/hud/heart/vehicle_full.png",
    "assets/minecraft/textures/gui/sprites/hud/food_full.png",
    "assets/minecraft/textures/gui/sprites/hud/experience_bar_progress.png",
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
                if provider.get("height") != GLYPH_HEIGHT:
                    problems.append(f"height={provider.get('height')} в {provider.get('file')},"
                                    f" нужен {GLYPH_HEIGHT}")
                file = provider.get("file", "").split(":", 1)[-1]
                texture = f"assets/servermmo/textures/{file}"
                if texture not in names:
                    problems.append(f"нет текстуры {texture}")
                    continue
                width, height = png_size(zf.read(texture))
                if height != GLYPH_HEIGHT or width % GLYPH_HEIGHT != 0:
                    problems.append(f"{texture}: {width}x{height},"
                                    f" нужна ячейка {GLYPH_HEIGHT}x{GLYPH_HEIGHT}")
            if bitmaps == 0:
                problems.append("в шрифте нет ни одного bitmap-провайдера")

        for name in names:
            if "/heart/" in name and name.endswith(".png"):
                if not png_is_blank(zf.read(name)):
                    problems.append(f"сердце не скрыто: {name}")
            if name.endswith("/icons.png") and not png_is_blank(zf.read(name)):
                problems.append("старые сердца не скрыты: icons.png")

        hidden = sum(1 for n in names if "/heart/" in n and n.endswith(".png"))
        if hidden < 80:
            problems.append(f"скрыто только {hidden} спрайтов сердец (ожидалось ~90)")

    if problems:
        print("[ERROR] Ресурспак не готов:")
        for problem in problems:
            print("  -", problem)
        return 1
    print(f"[OK] Ресурспак в порядке: {len(names)} файлов, сердец скрыто {hidden}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
