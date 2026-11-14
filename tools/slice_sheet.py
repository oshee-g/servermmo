#!/usr/bin/env python3
"""Нарезает ИИ-лист с иконками на отдельные PNG.

ИИ-модели не умеют отдавать прозрачность, поэтому листы генерируются на
ровном маджентовом фоне (#FF00FF): скрипт вырезает его хромакеем, находит
связные области (иконка = одна область), сортирует их по строкам и
столбцам (порядок чтения) и раскладывает по именам из --names.

Если число областей не совпадает с числом имён — скрипт падает и печатает
все найденные области (размер, площадь, средний цвет): так видно, что
модель нарисовала лишнее или склеила иконки.

Запуск (нужен Pillow):
    /tmp/venv/bin/python tools/slice_sheet.py tools/assets_src/sheet_eq.png \
        --out tools/pack_assets/icons64 --size 64 \
        --names heart,sword,axe,staff,hammer,scythe,trident,bow,spear,shield
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

try:
    from PIL import Image
except ImportError:  # pragma: no cover - только для локального запуска
    sys.exit("нужен Pillow: /tmp/venv/bin/python tools/slice_sheet.py ...")

# Порог «маджентовости»: насколько R и B ярче G.
LOW, HIGH = 40.0, 100.0
ALPHA_MIN = 40  # ниже этот уровень пиксель считается фоном


def key_out_magenta(img: Image.Image) -> Image.Image:
    """Маджентовый фон -> прозрачность, края мягкие."""
    img = img.convert("RGBA")
    px = img.load()
    w, h = img.size
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            d = min(r - g, b - g)  # у мадженты большое, у серого/белого ~0
            if d <= LOW:
                continue
            k = 255.0 * min(1.0, (d - LOW) / (HIGH - LOW))
            px[x, y] = (r, g, b, int(a * (1.0 - k / 255.0)))
    return img


def components(img: Image.Image, alpha_min: int = ALPHA_MIN):
    """Связные области непрозрачных пикселей (union-find по строкам)."""
    w, h = img.size
    alpha = img.split()[3].load()
    parent: dict[int, int] = {}

    def find(i: int) -> int:
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    def union(a: int, b: int) -> None:
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[max(ra, rb)] = min(ra, rb)

    boxes: dict[int, list[int]] = {}  # root -> [x0, y0, x1, y1, area]
    prev: list[tuple[int, int, int]] = []  # (x0, x1, root) предыдущей строки
    nxt = 1
    for y in range(h):
        row: list[tuple[int, int, int]] = []
        x = 0
        while x < w:
            if alpha[x, y] < alpha_min:
                x += 1
                continue
            x0 = x
            while x < w and alpha[x, y] >= alpha_min:
                x += 1
            root = nxt
            nxt += 1
            parent[root] = root
            boxes[root] = [x0, y, x - 1, y, x - x0]
            for (px0, px1, proot) in prev:
                if px0 <= x - 1 and x0 <= px1:  # интервалы пересекаются
                    union(root, proot)
            row.append((x0, x - 1, root))
        prev = row

    merged: dict[int, list[int]] = {}
    for root, box in boxes.items():
        r = find(root)
        if r not in merged:
            merged[r] = list(box)
        else:
            m = merged[r]
            m[0] = min(m[0], box[0])
            m[1] = min(m[1], box[1])
            m[2] = max(m[2], box[2])
            m[3] = max(m[3], box[3])
            m[4] += box[4]
    return list(merged.values())


def mean_color(img: Image.Image) -> tuple[int, int, int]:
    """Средний цвет непрозрачных пикселей — чтобы глазами сверить иконку."""
    px = img.load()
    w, h = img.size
    rs = gs = bs = n = 0
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a < 200:
                continue
            rs += r
            gs += g
            bs += b
            n += 1
    if not n:
        return (0, 0, 0)
    return (rs // n, gs // n, bs // n)


def fit(img: Image.Image, size: tuple[int, int]) -> Image.Image:
    """Вписать иконку в size с сохранением пропорций, без масштаба вверх
    больше чем нужно: просто центрируем на прозрачном холсте."""
    iw, ih = img.size
    tw, th = size
    scale = min(tw / iw, th / ih)
    nw, nh = max(1, round(iw * scale)), max(1, round(ih * scale))
    small = img.resize((nw, nh), Image.LANCZOS)
    canvas = Image.new("RGBA", (tw, th), (0, 0, 0, 0))
    canvas.paste(small, ((tw - nw) // 2, (th - nh) // 2), small)
    return canvas


def merge_small(comps: list[list[int]], ratio: float = 0.2) -> list[list[int]]:
    """Мелкие осколки (блики, отлетевшие языки пламени) приклеиваем к той
    иконке, внутрь габаритов которой они попали, иначе к ближайшей."""
    if not comps:
        return comps
    areas = sorted(c[4] for c in comps)
    median = areas[len(areas) // 2]
    big = [c for c in comps if c[4] >= median * ratio]
    small = [c for c in comps if c[4] < median * ratio]
    if not big:
        return comps
    for s in small:
        sx, sy = (s[0] + s[2]) / 2, (s[1] + s[3]) / 2
        target = None
        for b in big:  # сначала ищем, внутрь чего попал осколок
            if b[0] <= sx <= b[2] and b[1] <= sy <= b[3]:
                target = b
                break
        if target is None:  # иначе — ближайший по центру
            target = min(big, key=lambda b: ((b[0] + b[2]) / 2 - sx) ** 2
                         + ((b[1] + b[3]) / 2 - sy) ** 2)
        target[0] = min(target[0], s[0])
        target[1] = min(target[1], s[1])
        target[2] = max(target[2], s[2])
        target[3] = max(target[3], s[3])
        target[4] += s[4]
    return big


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("sheet", type=Path)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--size", default="64", help="64 или 256x64")
    ap.add_argument("--names", required=True, help="имена файлов через запятую")
    ap.add_argument("--min-area", type=float, default=0.0004,
                    help="минимальная доля площади листа на область")
    ap.add_argument("--take", type=int, default=0,
                    help="взять только первые N иконок (порядок чтения)")
    args = ap.parse_args()

    if "x" in args.size:
        size = tuple(int(p) for p in args.size.split("x"))
    else:
        size = (int(args.size), int(args.size))
    names = [n.strip() for n in args.names.split(",") if n.strip()]

    img = key_out_magenta(Image.open(args.sheet))
    w, h = img.size
    comps = [c for c in components(img)
             if c[4] >= args.min_area * w * h
             and c[0] > 1 and c[1] > 1 and c[2] < w - 2 and c[3] < h - 2]
    comps = merge_small(comps)

    # Строки: группируем по центру Y с допуском в половину медианной высоты.
    comps.sort(key=lambda c: (c[1], c[0]))
    heights = sorted(c[3] - c[1] for c in comps)
    med_h = heights[len(heights) // 2] if heights else 1
    rows: list[list[list[int]]] = []
    for c in comps:
        cy = (c[1] + c[3]) / 2
        if rows and abs(cy - (rows[-1][0][1] + rows[-1][0][3]) / 2) <= med_h * 0.6:
            rows[-1].append(c)
        else:
            rows.append([c])
    ordered: list[list[int]] = []
    for row in rows:
        ordered.extend(sorted(row, key=lambda c: c[0]))

    if args.take and len(ordered) > args.take:
        print(f"ВНИМАНИЕ: найдено {len(ordered)}, берём первые {args.take} "
              f"(модель нарисовала несколько копий набора)")
        ordered = ordered[:args.take]

    print(f"лист {args.sheet.name} {w}x{h}: областей {len(ordered)} (строк {len(rows)})")
    if len(ordered) != len(names):
        print(f"ОШИБКА: ожидалось иконок {len(names)}, найдено {len(ordered)}")
        for i, c in enumerate(ordered):
            print(f"  [{i}] x{c[0]}..{c[2]} y{c[1]}..{c[3]} "
                  f"размер {c[2]-c[0]+1}x{c[3]-c[1]+1} площадь {c[4]}")
        return 1

    args.out.mkdir(parents=True, exist_ok=True)
    for name, c in zip(names, ordered):
        icon = fit(img.crop((c[0], c[1], c[2] + 1, c[3] + 1)), size)
        path = args.out / f"{name}.png"
        icon.save(path)
        r, g, b = mean_color(icon)
        print(f"  {path.name:22s} {c[2]-c[0]+1:4d}x{c[3]-c[1]+1:<4d} "
              f"средний цвет #{r:02x}{g:02x}{b:02x}")
    print(f"готово: {len(names)} иконок в {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
