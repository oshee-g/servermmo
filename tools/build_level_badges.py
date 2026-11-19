#!/usr/bin/env python3
"""Собирает 100 значков уровня: ИИ-ромб + номер уровня.

Ромб один (tools/pack_assets/icons64/level_diamond_new.png) — рисовать цифры
средствами ИИ на сотню иконок нельзя: они вышли бы разными по стилю и
кривыми. Поэтому ромб генерирует ИИ, а номер рисует код, подбирая размер
так, чтобы текст влез в тёмный центр ромба. Слово «lvl» не пишется —
номер стоит один в центре.

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
DIAMOND = ROOT / "tools" / "pack_assets" / "icons64" / "level_diamond_new.png"
SHIELD = ROOT / "tools" / "pack_assets" / "icons64" / "level_shield.png"
OUT = ROOT / "tools" / "pack_assets" / "level"
CLAN_OUT = ROOT / "tools" / "pack_assets" / "clan"

SIZE = 64          # значок в шрифте
NUMBER_RATIO = 0.50  # центр номера по высоте (тёмный центр ромба)
SHIELD_NUMBER_RATIO = 0.44  # центр номера на щите клана (тёмный центр щита)
FILL = (255, 255, 255, 255)
STROKE = (12, 28, 38, 255)


def row_widths(img: Image.Image) -> list[int]:
    """Ширина непрозрачной части ромба по каждой строке."""
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


def build(base_path: Path, out_dir: Path, top: float, bottom: float,
          ratio: float, factor: float) -> None:
    """100 значков: основа + номер по центру, без подписи lvl."""
    if not base_path.is_file():
        sys.exit(f"нет основы: {base_path}")
    base = Image.open(base_path).convert("RGBA")
    if base.size != (SIZE, SIZE):
        base = base.resize((SIZE, SIZE), Image.LANCZOS)
    widths = row_widths(base)
    out_dir.mkdir(parents=True, exist_ok=True)

    for n in range(1, 101):
        text = str(n)
        badge = base.copy()
        draw = ImageDraw.Draw(badge)
        # Номер: центр основы, размер подбираем по ширине тёмного центра.
        rows = range(max(0, int(top)), min(len(widths), int(bottom) + 1))
        limit = min(widths[y] for y in rows) * factor if rows else SIZE
        font = None
        for size in range(30, 7, -1):
            candidate = ImageFont.load_default(size=size)
            box = draw.textbbox((0, 0), text, font=candidate)
            if box[2] - box[0] <= limit and box[3] - box[1] <= (bottom - top) * 1.05:
                font = candidate
                break
        if font is None:
            font = ImageFont.load_default(size=8)
        box = draw.textbbox((0, 0), text, font=font)
        draw.text(((SIZE - (box[2] - box[0])) / 2 - box[0],
                   SIZE * ratio - (box[3] - box[1]) / 2 - box[1]),
                  text, font=font, fill=FILL,
                  stroke_width=max(1, round(font.size / 9)),
                  stroke_fill=STROKE)
        badge.save(out_dir / f"level_{n}.png")


def main() -> int:
    # Ромбы уровня игрока: номер в тёмном центре ромба.
    build(DIAMOND, OUT, SIZE * 0.36, SIZE * 0.64, NUMBER_RATIO, 0.50)
    # Щиты уровня клана: номер в тёмном центре щита.
    build(SHIELD, CLAN_OUT, SIZE * 0.28, SIZE * 0.60, SHIELD_NUMBER_RATIO, 0.86)

    print(f"готово: 100 ромбов уровня {SIZE}x{SIZE} в {OUT}, "
          f"100 щитов клана в {CLAN_OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
