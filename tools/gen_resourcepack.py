#!/usr/bin/env python3
"""Генератор ресурспака ServerMMO HUD.

Рисует пиксель-арт рамку HUD (4 полосы по строкам), иконки, шрифт цифр,
прозрачные заглушки ванильных сердец/полосы опыта и собирает всё в zip.

Запуск:  python3 tools/gen_resourcepack.py
Результат: resourcepack/ (распакованный) + server/resourcepack/servermmo-hud.zip
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
ZIP_OUT = ROOT / "server" / "resourcepack" / "servermmo-hud.zip"

ROW_W, ROW_H = 160, 14
TEXT_X = 24

# --- палитра ---------------------------------------------------------------
OUTLINE = (24, 16, 9, 255)
WOOD_DARK = (66, 43, 25, 255)
WOOD = (104, 69, 38, 255)
WOOD_LIGHT = (146, 101, 58, 255)
WOOD_HI = (176, 129, 79, 255)
PANEL = (24, 20, 17, 200)
PANEL_EDGE = (12, 10, 8, 160)
WHITE = (240, 240, 240, 255)
SHADOW = (60, 60, 60, 255)
RED = (214, 59, 59, 255)
RED_DARK = (122, 20, 20, 255)
GREEN = (89, 214, 90, 255)
GREEN_DARK = (30, 92, 32, 255)
CYAN = (79, 214, 224, 255)
CYAN_DARK = (24, 96, 110, 255)
STEEL = (206, 206, 214, 255)
STEEL_DARK = (96, 96, 108, 255)
HANDLE = (110, 72, 36, 255)


class Canvas:
    def __init__(self, w: int, h: int, fill=(0, 0, 0, 0)):
        self.w, self.h = w, h
        self.px = [[fill for _ in range(w)] for _ in range(h)]

    def set(self, x: int, y: int, color) -> None:
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[y][x] = color

    def rect(self, x0: int, y0: int, x1: int, y1: int, color) -> None:
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.set(x, y, color)

    def frame(self, x0: int, y0: int, x1: int, y1: int, color) -> None:
        for x in range(x0, x1 + 1):
            self.set(x, y0, color)
            self.set(x, y1, color)
        for y in range(y0, y1 + 1):
            self.set(x0, y, color)
            self.set(x1, y, color)

    def disc(self, cx: float, cy: float, r: float, color) -> None:
        for y in range(self.h):
            for x in range(self.w):
                if (x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2 <= r * r:
                    self.set(x, y, color)

    def outline(self, color) -> None:
        """Обводка вокруг непрозрачных пикселей."""
        src = [row[:] for row in self.px]
        for y in range(self.h):
            for x in range(self.w):
                if src[y][x][3] != 0:
                    continue
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < self.w and 0 <= ny < self.h and src[ny][nx][3] != 0:
                        self.px[y][x] = color
                        break

    def blit_pattern(self, x0: int, y0: int, pattern: list[str], color, scale: int = 1) -> None:
        for ry, line in enumerate(pattern):
            for rx, ch in enumerate(line):
                if ch in "1#X" and ch != " ":
                    if ch != ".":
                        for sy in range(scale):
                            for sx in range(scale):
                                self.set(x0 + rx * scale + sx, y0 + ry * scale + sy, color)

    def copy_into(self, dst: "Canvas", x0: int, y0: int) -> None:
        for y in range(self.h):
            for x in range(self.w):
                if self.px[y][x][3] != 0:
                    dst.set(x0 + x, y0 + y, self.px[y][x])

    def rgba(self) -> bytes:
        out = bytearray()
        for row in self.px:
            for (r, g, b, a) in row:
                out += bytes((r, g, b, a))
        return bytes(out)


def write_png(path: Path, canvas: Canvas) -> None:
    raw = bytearray()
    stride = canvas.w * 4
    data = canvas.rgba()
    for y in range(canvas.h):
        raw.append(0)
        raw += data[y * stride:(y + 1) * stride]

    def chunk(typ: bytes, payload: bytes) -> bytes:
        return (struct.pack(">I", len(payload)) + typ + payload
                + struct.pack(">I", zlib.crc32(typ + payload) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", canvas.w, canvas.h, 8, 6, 0, 0, 0)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
                     + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))


# --- иконки ----------------------------------------------------------------
HEART = [
    ".11...11.",
    "11111.111",
    "111111111",
    "111111111",
    ".1111111.",
    "..11111..",
    "...111...",
    "....1....",
]

SWORD = [
    "........11",
    ".......111",
    "......111.",
    ".....111..",
    "..1.111...",
    "..1111....",
    ".1111.....",
    "11.1......",
    "1.........",
]

LETTERS_3x5 = {
    "L": ["100", "100", "100", "100", "111"],
    "V": ["101", "101", "101", "101", "010"],
}

DIGITS_5x7 = {
    "0": ["01110", "10001", "10011", "10101", "11001", "10001", "01110"],
    "1": ["00100", "01100", "00100", "00100", "00100", "00100", "01110"],
    "2": ["01110", "10001", "00001", "00010", "00100", "01000", "11111"],
    "3": ["11111", "00010", "00100", "00010", "00001", "10001", "01110"],
    "4": ["00010", "00110", "01010", "10010", "11111", "00010", "00010"],
    "5": ["11111", "10000", "11110", "00001", "00001", "10001", "01110"],
    "6": ["00110", "01000", "10000", "11110", "10001", "10001", "01110"],
    "7": ["11111", "00001", "00010", "00100", "01000", "01000", "01000"],
    "8": ["01110", "10001", "10001", "01110", "10001", "10001", "01110"],
    "9": ["01110", "10001", "10001", "01111", "00001", "00010", "01100"],
    "/": ["00001", "00001", "00010", "00100", "01000", "10000", "10000"],
    " ": ["00000", "00000", "00000", "00000", "00000", "00000", "00000"],
}


def row_strip(kind: str) -> Canvas:
    """Полоса HUD 160x14. kind: top | heart | sword | lvl | orb | bottom."""
    c = Canvas(ROW_W, ROW_H, PANEL)
    # затемнение панели к низу для объёма
    c.rect(2, 3, ROW_W - 3, ROW_H - 3, PANEL)
    # боковые стенки: тёмный контур + дерево + светлая кромка внутри
    c.rect(0, 0, 0, ROW_H - 1, OUTLINE)
    c.rect(1, 0, 1, ROW_H - 1, WOOD)
    c.rect(2, 0, 2, ROW_H - 1, WOOD_LIGHT)
    c.rect(3, 0, 3, ROW_H - 1, PANEL_EDGE)
    c.rect(ROW_W - 4, 0, ROW_W - 4, ROW_H - 1, PANEL_EDGE)
    c.rect(ROW_W - 3, 0, ROW_W - 3, ROW_H - 1, WOOD_LIGHT)
    c.rect(ROW_W - 2, 0, ROW_W - 2, ROW_H - 1, WOOD)
    c.rect(ROW_W - 1, 0, ROW_W - 1, ROW_H - 1, OUTLINE)
    # прожилки дерева на боковинах
    for y in range(0, ROW_H, 3):
        c.set(1, y, WOOD_DARK)
        c.set(ROW_W - 2, y + 1, WOOD_DARK)

    if kind == "top":
        c.rect(0, 0, ROW_W - 1, 0, OUTLINE)
        c.rect(0, 1, ROW_W - 1, 2, WOOD)
        c.rect(0, 3, ROW_W - 1, 3, WOOD_HI)
        for x in range(4, ROW_W - 4, 7):
            c.set(x, 1, WOOD_LIGHT)
            c.set(x + 3, 2, WOOD_DARK)
        for x0 in (0, ROW_W - 7):
            c.rect(x0, 0, x0 + 6, 3, WOOD_LIGHT)
            c.frame(x0, 0, x0 + 6, 3, OUTLINE)
            c.set(x0 + 3, 1, WOOD_HI)
    elif kind == "bottom":
        c.rect(0, ROW_H - 4, ROW_W - 1, ROW_H - 4, WOOD_DARK)
        c.rect(0, ROW_H - 3, ROW_W - 1, ROW_H - 2, WOOD)
        c.rect(0, ROW_H - 1, ROW_W - 1, ROW_H - 1, OUTLINE)
        for x in range(6, ROW_W - 6, 9):
            c.set(x, ROW_H - 3, WOOD_LIGHT)
            c.set(x + 4, ROW_H - 2, WOOD_DARK)
        for x0 in (0, ROW_W - 7):
            c.rect(x0, ROW_H - 4, x0 + 6, ROW_H - 1, WOOD_LIGHT)
            c.frame(x0, ROW_H - 4, x0 + 6, ROW_H - 1, OUTLINE)
    return c
def draw_icon_row(kind: str) -> Canvas:
    c = row_strip(kind)
    if kind == "heart":
        icon = Canvas(9, 8)
        icon.blit_pattern(0, 0, HEART, RED)
        icon.outline(RED_DARK)
        icon.copy_into(c, 8, 3)
    elif kind == "sword":
        icon = Canvas(10, 9)
        icon.blit_pattern(0, 0, SWORD, STEEL)
        icon.outline(STEEL_DARK)
        icon.rect(0, 7, 3, 8, HANDLE)
        icon.copy_into(c, 7, 2)
    elif kind == "lvl":
        x = 8
        for ch in "LVL":
            icon = Canvas(3, 5)
            icon.blit_pattern(0, 0, LETTERS_3x5[ch], GREEN)
            icon.outline(GREEN_DARK)
            icon.copy_into(c, x, 5)
            x += 5
    elif kind == "orb":
        icon = Canvas(10, 10)
        icon.disc(5.0, 5.0, 3.6, CYAN)
        icon.outline(CYAN_DARK)
        icon.set(3, 3, (216, 252, 255, 255))
        icon.set(4, 3, (216, 252, 255, 255))
        icon.copy_into(c, 7, 2)
    return c


def digits_strip() -> Canvas:
    """Полоса цифр 6x14: глифы 5x7 стоят по центру полосы (y=3..9), ascent 11."""
    chars = "0123456789/ "
    cell_w, cell_h = 6, ROW_H
    c = Canvas(cell_w * len(chars), cell_h)
    for index, ch in enumerate(chars):
        x0 = index * cell_w
        for ry, line in enumerate(DIGITS_5x7[ch]):
            for rx, bit in enumerate(line):
                if bit == "1":
                    c.set(x0 + rx + 1, ry + 3, WHITE)
    return c


def transparent(path: Path, w: int, h: int) -> None:
    write_png(path, Canvas(w, h))


def build_font_json() -> dict:
    return {
        "providers": [
            {"type": "bitmap", "file": "servermmo:font/hud_row_top.png",
             "ascent": 11, "height": 14, "chars": ["\ue001"]},
            {"type": "bitmap", "file": "servermmo:font/hud_row_heart.png",
             "ascent": 11, "height": 14, "chars": ["\ue002"]},
            {"type": "bitmap", "file": "servermmo:font/hud_row_sword.png",
             "ascent": 11, "height": 14, "chars": ["\ue003"]},
            {"type": "bitmap", "file": "servermmo:font/hud_row_lvl.png",
             "ascent": 11, "height": 14, "chars": ["\ue004"]},
            {"type": "bitmap", "file": "servermmo:font/hud_row_orb.png",
             "ascent": 11, "height": 14, "chars": ["\ue005"]},
            {"type": "bitmap", "file": "servermmo:font/hud_row_bottom.png",
             "ascent": 11, "height": 14, "chars": ["\ue006"]},
            {"type": "bitmap", "file": "servermmo:font/hud_digits.png",
             "ascent": 11, "height": ROW_H, "chars": list("0123456789/ ")},
            {"type": "space", "advances": {
                "\ue100": -ROW_W + TEXT_X,
                "\ue101": -10, "\ue102": -1, "\ue103": -4, "\ue104": 4,
            }},
        ]
    }


def build_pack_meta() -> dict:
    return {
        "pack": {
            "pack_format": 75,
            "supported_formats": {"min_inclusive": 1, "max_inclusive": 999},
            "description": "ServerMMO HUD: рамка, иконки и пиксельный шрифт",
        }
    }


HEART_SPRITES = [
    "heart_container", "heart_full", "heart_half",
    "heart_poisoned_container", "heart_poisoned_full", "heart_poisoned_half",
    "heart_withered_container", "heart_withered_full", "heart_withered_half",
    "heart_frozen_container", "heart_frozen_full", "heart_frozen_half",
    "heart_absorbing_container", "heart_absorbing_full", "heart_absorbing_half",
    "heart_blinking_container", "heart_blinking_full", "heart_blinking_half",
]
EXTRA_HUD_SPRITES = [
    "experience_bar_background", "experience_bar_progress",
    "jump_bar_background", "jump_bar_progress", "jump_bar_cooldown",
    "armor_empty", "armor_half", "armor_full",
    "food_empty", "food_half", "food_full",
    "food_empty_hunger", "food_half_hunger", "food_full_hunger",
    "hotbar_attack_indicator_background", "hotbar_attack_indicator_progress",
]


def main() -> int:
    if PACK.exists():
        shutil.rmtree(PACK)
    (PACK / "assets" / "minecraft" / "textures" / "gui" / "sprites" / "hud" / "heart").mkdir(parents=True, exist_ok=True)
    fonts_dir = PACK / "assets" / "servermmo" / "font"
    fonts_dir.mkdir(parents=True, exist_ok=True)
    tex_dir = PACK / "assets" / "servermmo" / "textures" / "font"
    tex_dir.mkdir(parents=True, exist_ok=True)

    (PACK / "pack.mcmeta").write_text(json.dumps(build_pack_meta(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (fonts_dir / "hud.json").write_text(json.dumps(build_font_json(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    write_png(tex_dir / "hud_row_top.png", draw_icon_row("top"))
    write_png(tex_dir / "hud_row_heart.png", draw_icon_row("heart"))
    write_png(tex_dir / "hud_row_sword.png", draw_icon_row("sword"))
    write_png(tex_dir / "hud_row_lvl.png", draw_icon_row("lvl"))
    write_png(tex_dir / "hud_row_orb.png", draw_icon_row("orb"))
    write_png(tex_dir / "hud_row_bottom.png", draw_icon_row("bottom"))
    write_png(tex_dir / "hud_digits.png", digits_strip())

    # прозрачные заглушки ванильного HUD
    gui = PACK / "assets" / "minecraft" / "textures" / "gui"
    transparent(gui / "icons.png", 256, 256)
    heart_dir = gui / "sprites" / "hud" / "heart"
    for name in HEART_SPRITES:
        transparent(heart_dir / f"{name}.png", 9, 9)
    for name in EXTRA_HUD_SPRITES:
        transparent(gui / "sprites" / "hud" / f"{name}.png", 182, 10 if "bar" in name else 9)

    # иконка пака
    icon = Canvas(64, 64, WOOD)
    icon.frame(0, 0, 63, 63, OUTLINE)
    icon.rect(4, 4, 59, 59, PANEL)
    heart64 = Canvas(9, 8)
    heart64.blit_pattern(0, 0, HEART, RED)
    heart64.outline(RED_DARK)
    for scale in (2,):
        big = Canvas(9 * scale, 8 * scale)
        for y in range(8):
            for x in range(9):
                color = heart64.px[y][x]
                if color[3] != 0:
                    big.rect(x * scale, y * scale, x * scale + scale - 1, y * scale + scale - 1, color)
        big.copy_into(icon, 23, 24)
    write_png(PACK / "pack.png", icon)

    # zip для раздачи клиентам
    ZIP_OUT.parent.mkdir(parents=True, exist_ok=True)
    if ZIP_OUT.exists():
        ZIP_OUT.unlink()
    with zipfile.ZipFile(ZIP_OUT, "w", zipfile.ZIP_DEFLATED) as zf:
        for path in sorted(PACK.rglob("*")):
            if path.is_file():
                zf.write(path, path.relative_to(PACK).as_posix())

    files = sum(1 for p in PACK.rglob("*") if p.is_file())
    print(f"Ресурспак: {files} файлов -> {ZIP_OUT.relative_to(ROOT)} ({ZIP_OUT.stat().st_size} байт)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
