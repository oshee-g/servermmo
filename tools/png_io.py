#!/usr/bin/env python3
"""Минимальное чтение PNG без сторонних библиотек.

Нужно проверкам пака: они смотрят, в каких строках ячейки лежат чернила
глифов, и считают ширину символов. Pillow в CI не ставится, а разбирать
8-битный RGBA PNG руками — два десятка строк.
"""
from __future__ import annotations

import struct
import zlib


def png_rgba(data: bytes) -> tuple[int, int, bytes]:
    """Раскодировать 8-битный RGBA PNG (color type 6) в сырые пиксели."""
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("не PNG")
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


def glyph_advance(width: int, height: int, pixels: bytes, cell: int, index: int) -> int:
    """Ширина глифа в ячейке: последний непрозрачный пиксель + 1.

    Ровно так считает ширину сама игра для bitmap-провайдера шрифта.
    """
    stride = width * 4
    rightmost = -1
    for y in range(height):
        for x in range(index * cell, (index + 1) * cell):
            if pixels[y * stride + x * 4 + 3] > 0:
                rightmost = max(rightmost, x)
    if rightmost < 0:
        return 1  # пустой глиф всё равно занимает пиксель
    return rightmost - index * cell + 1


def glyph_advance_grid(width: int, height: int, pixels: bytes,
                       cell_w: int, cell_h: int, col: int, row: int,
                       scale: float = 1.0) -> int:
    """Ширина глифа в сетке из ячеек cell_w x cell_h.

    Обобщение glyph_advance(): атласы значков уровня и панелей классов
    многстрочные (10x10 и 2x4), а ширина всё так же считается по последнему
    непрозрачному столбцу внутри своей ячейки — ровно как это делает игра.
    scale — pixelScale провайдера (height провайдера / высота ячейки):
    значки панели рисуются 16 px из ячеек 24 px, поэтому ширина тоже
    масштабируется.
    """
    stride = width * 4
    rightmost = -1
    x0, y0 = col * cell_w, row * cell_h
    for y in range(y0, min(y0 + cell_h, height)):
        for x in range(x0, min(x0 + cell_w, width)):
            if pixels[y * stride + x * 4 + 3] > 0:
                rightmost = max(rightmost, x)
    if rightmost < 0:
        # Пустой глиф: видимая ширина 0, и игра всё равно добавляет 1.
        return 1
    # Формула игры (BitmapProvider.Definition.load):
    # advance = round(видимая_ширина * масштаб) + 1. Без этого +1 плагин
    # считал строки на пиксель уже, чем их рисует клиент, и панель ехала.
    visible = rightmost - x0 + 1
    return int(0.5 + visible * scale) + 1
