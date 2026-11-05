#!/usr/bin/env python3
"""Проверка собранного ресурспака перед публикацией.

Запускается в CI после ``tools/gen_resourcepack.py``. Проверяет то, из-за
чего HUD раньше не отображался:

* шрифт HUD на месте и все bitmap-провайдеры — квадратные ячейки 8x8;
* текстуры глифов действительно 8x8 (или кратны 8 по ширине);
* чернила глифов лежат в строках 1..7 ячейки — как заглавные буквы и цифры
  ванильного ``ascii.png``; иначе в подписи моба значки «прыгают» относительно
  имени, которое рисуется ванильным шрифтом;
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
    "assets/servermmo/textures/font/hud_letters.png",
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


def png_rgba(data: bytes) -> tuple[int, int, bytes]:
    """Раскодировать 8-битный RGBA PNG (color type 6) в сырые пиксели.

    Pillow в CI нет, а проверить строки, в которых лежат чернила глифов,
    нужно именно по пикселям.
    """
    import zlib
    width, height = struct.unpack(">II", data[16:24])
    depth, color_type = data[24], data[25]
    if (depth, color_type) != (8, 6):
        raise ValueError(f"ожидался 8-битный RGBA, а тут depth={depth} type={color_type}")
    pos = 8
    idat = b""
    while pos < len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        kind = data[pos + 4:pos + 8]
        if kind == b"IDAT":
            idat += data[pos + 8:pos + 8 + length]
        pos += 12 + length
    raw = zlib.decompress(idat)
    stride = width * 4
    pixels = bytearray(stride * height)
    previous = bytearray(stride)
    offset = 0
    for row in range(height):
        filter_type = raw[offset]
        offset += 1
        line = bytearray(raw[offset:offset + stride])
        offset += stride
        for i in range(stride):
            left = line[i - 4] if i >= 4 else 0
            up = previous[i]
            up_left = previous[i - 4] if i >= 4 else 0
            if filter_type == 1:
                line[i] = (line[i] + left) & 0xFF
            elif filter_type == 2:
                line[i] = (line[i] + up) & 0xFF
            elif filter_type == 3:
                line[i] = (line[i] + (left + up) // 2) & 0xFF
            elif filter_type == 4:
                p = left + up - up_left
                pa, pb, pc = abs(p - left), abs(p - up), abs(p - up_left)
                predictor = left if pa <= pb and pa <= pc else (up if pb <= pc else up_left)
                line[i] = (line[i] + predictor) & 0xFF
        pixels[row * stride:(row + 1) * stride] = line
        previous = line
    return width, height, bytes(pixels)


def check_ink_rows(width: int, height: int, pixels: bytes, cells: int,
                   file: str, problems: list[str]) -> None:
    """Чернила каждого глифа обязаны лежать в строках 1..7 ячейки 8x8."""
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
        if 0 in rows_with_ink:
            problems.append(f"{file}: у глифа #{index} чернила в строке 0 —"
                            f" он выше ванильных заглавных и сдвинет строку")
        for y in rows_with_ink:
            if y > GLYPH_HEIGHT - 1:
                problems.append(f"{file}: глиф #{index} вылезает ниже ячейки")


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

            # чернила на одной базовой линии с ванильным шрифтом
            for provider in font.get("providers", []):
                if provider.get("type") != "bitmap":
                    continue
                file = provider.get("file", "").split(":", 1)[-1]
                texture = f"assets/servermmo/textures/{file}"
                if texture not in names:
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
