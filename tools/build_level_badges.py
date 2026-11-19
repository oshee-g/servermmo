#!/usr/bin/env python3
"""Собирает 100 значков уровня: ИИ-щит + номер и подпись lvl.

Щит один (tools/pack_assets/icons64/level_shield.png) — рисовать цифры
средствами ИИ на сотню иконок нельзя: они вышли бы разными по стилю и
кривыми. Поэтому щит генерирует ИИ, а цифры и слово lvl рисует код,
подбирая размер так, чтобы текст влез в тёмный центр щита.

Раньше здесь был ромб, но на нём цифры не читались (светло-голубой на
светло-голубом). Щит с тёмным центром даёт контраст: белый номер на
тёмном фоне.

Запуск (нужен Pillow):
    /tmp/venv/bin/python tools/build_level_badges.py
"""
from __future__ import annotations

import sys
from pathlib import Path

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:  # pragma: no cover
    sys.exit("нужен Pillow: /tmp/venv/bin/python tools/build_level_badges.py")

ROOT = Path(__file__).resolve().parent.parent
SHIELD = ROOT / "tools" / "pack_assets" / "icons64" / "level_shield.png"
OUT = ROOT / "tools" / "pack_assets" / "level"

SIZE = 64          # значок в шрифте
NUMBER_RATIO = 0.44  # центр номера по высоте (тёмный центр щита)
LVL_RATIO = 0.70     # центр подписи lvl по высоте
FILL = (255, 255, 255, 255)
STROKE = (12, 28, 38, 255)


def row_widths(img: Image.Image) -> list[int]:
    """Ширина непрозрачной части щита по каждой строке."""
    alpha = img.split()[3].load()
    w, h = img.size
    out = []
    for y in range(h):
        x0, x1 = None, None
        for x in range(w):
            if alpha[x, y] >= 128:
                if x0 is None:
                    x0 = x
                x1 = x
        out.append(0 if x0 is None else x1 - x0 + 1)
    return out


def fit_font(draw: Image.Image, text: str, widths: list[int],
             top: float, bottom: float, max_size: int) -> ImageFont.FreeTypeFont:
    """Самый крупный шрифт, при котором текст влезает в щит."""
    rows = range(max(0, int(top)), min(len(widths), int(bottom) + 1))
    limit = min(widths[y] for y in rows) * 0.86 if rows else SIZE
    for size in range(max_size, 7, -1):
        font = ImageFont.load_default(size=size)
        box = draw.textbbox((0, 0), text, font=font)
        if box[2] - box[0] <= limit and box[3] - box[1] <= (bottom - top) * 1.05:
            return font
    return ImageFont.load_default(size=8)


def main() -> int:
    if not SHIELD.is_file():
        sys.exit(f"нет щита: {SHIELD}")
    base = Image.open(SHIELD).convert("RGBA")
    if base.size != (SIZE, SIZE):
        base = base.resize((SIZE, SIZE), Image.LANCZOS)
    widths = row_widths(base)
    OUT.mkdir(parents=True, exist_ok=True)

    for n in range(1, 101):
        text = str(n)
        badge = base.copy()
        draw = ImageDraw.Draw(badge)
        # Номер: центр чуть выше середины, размер подбираем по ширине щита.
        font = fit_font(draw, text, widths, SIZE * 0.28, SIZE * 0.60, 30)
        box = draw.textbbox((0, 0), text, font=font)
        draw.text(((SIZE - (box[2] - box[0])) / 2 - box[0],
                   SIZE * NUMBER_RATIO - (box[3] - box[1]) / 2 - box[1]),
                  text, font=font, fill=FILL,
                  stroke_width=max(1, round(font.size / 9)),
                  stroke_fill=STROKE)
        # Подпись lvl — ниже центра, мелкая.
        lvl = ImageFont.load_default(size=11)
        box = draw.textbbox((0, 0), "lvl", font=lvl)
        draw.text(((SIZE - (box[2] - box[0])) / 2 - box[0],
                   SIZE * LVL_RATIO - (box[3] - box[1]) / 2 - box[1]),
                  "lvl", font=lvl, fill=(226, 252, 250, 255), stroke_width=2,
                  stroke_fill=STROKE)
        badge.save(OUT / f"level_{n}.png")

    print(f"готово: 100 значков уровня {SIZE}x{SIZE} в {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
