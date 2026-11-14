#!/usr/bin/env python3
"""Доводка нарезанных иконок: наклон оружия и перекрас валют.

Зачем отдельный скрипт: правки по мелочи (наклони меч как трезубец,
сделай монеты жёлтыми) проще применять кодом к уже готовому арту, чем
перегенерацией — так иконки остаются одного стиля и правка повторяема.

Что умеет:
  * ``lean()`` — наклон иконки в градусах: считается по главной оси
    непрозрачных пикселей (моменты второго порядка), 0° — ровно
    вертикально, положительный угол — верхушка ушла вправо;
  * ``tilt_like()`` — поворачивает иконку так, чтобы её наклон стал таким
    же, как у образца (у нас образец — трезубец);
  * ``recolor()`` — перекрашивает иконку в нужный тон HSV, сохраняя
    светлоту и тени: меняются только тон и насыщенность.

Запуск (нужен Pillow):
    /tmp/venv/bin/python tools/tune_icons.py            # все правки
    /tmp/venv/bin/python tools/tune_icons.py --report    # только замеры
"""
from __future__ import annotations

import math
import sys
from pathlib import Path

try:
    from PIL import Image
except ImportError:  # pragma: no cover
    sys.exit("нужен Pillow: /tmp/venv/bin/python tools/tune_icons.py")

ROOT = Path(__file__).resolve().parent.parent
ICONS = ROOT / "tools" / "pack_assets" / "icons64"

# Оружие, которое просили наклонить «чуть вправо, как трезубец».
TILT_REF = "trident"
TILT_ICONS = ["sword", "axe", "staff", "hammer"]

# Перекрас валют: монеты (горсть) — жёлтые, коины (со звездой) — оранжевые,
# нанагемы — зелёные. Тон в градусах, порог насыщенности — чтобы серый
# металл стал цветным, а не остался грязно-серым.
# (имя, тон, порог насыщенности, множитель яркости). Множитель нужен там,
# где принудительная насыщенность съедает яркость: чем выше S, тем темнее
# второстепенные каналы, и монета становится грязно-коричневой.
RECOLORS = [
    ("coin_pile", 48, 120, 1.0),   # жёлтые монеты
    ("coin_star", 26, 150, 1.25),  # оранжевые коины
    ("nanogem", 132, 0, 1.0),      # зелёный гем (насыщенность не трогаем)
]


def _moments(alpha) -> tuple[float, float, float, float, float]:
    """Вес, центр и центральные моменты второго порядка маски."""
    w, h = alpha.size
    px = alpha.load()
    s = cx = cy = 0.0
    for y in range(h):
        for x in range(w):
            v = px[x, y] / 255.0
            if v <= 0.05:
                continue
            s += v
            cx += v * x
            cy += v * y
    if s == 0:
        return 0.0, 0.0, 0.0, 0.0, 0.0
    cx, cy = cx / s, cy / s
    mu20 = mu11 = mu02 = 0.0
    for y in range(h):
        for x in range(w):
            v = px[x, y] / 255.0
            if v <= 0.05:
                continue
            dx, dy = x - cx, y - cy
            mu20 += v * dx * dx
            mu11 += v * dx * dy
            mu02 += v * dy * dy
    return s, cx, cy, mu20 / s, mu11 / s, mu02 / s


def lean(img: Image.Image) -> float:
    """Наклон в градусах: 0 — вертикально, >0 — верхушка справа."""
    alpha = img.split()[3]
    _, cx, cy, mu20, mu11, mu02 = _moments(alpha)
    if mu20 == 0 and mu02 == 0:
        return 0.0
    theta = 0.5 * math.atan2(2 * mu11, mu20 - mu02)  # ось в системе (y вниз)
    # Два конца оси; «верхушка» — тот, у которого y меньше.
    dx, dy = math.cos(theta), math.sin(theta)
    top_dx, top_dy = (dx, dy) if dy < 0 else (-dx, -dy)
    return math.degrees(math.atan2(top_dx, -top_dy))


def fit(img: Image.Image, size: tuple[int, int]) -> Image.Image:
    """Вписать в квадрат с сохранением пропорций (как в slice_sheet.py)."""
    iw, ih = img.size
    tw, th = size
    scale = min(tw / iw, th / ih)
    nw, nh = max(1, round(iw * scale)), max(1, round(ih * scale))
    small = img.resize((nw, nh), Image.LANCZOS)
    canvas = Image.new("RGBA", (tw, th), (0, 0, 0, 0))
    canvas.paste(small, ((tw - nw) // 2, (th - nh) // 2), small)
    return canvas


def tilt_like(img: Image.Image, target: float) -> Image.Image:
    """Повернуть так, чтобы наклон стал как target (знак подбираем сам)."""
    cur = lean(img)
    delta = target - cur
    best, best_err = img, abs(cur - target)
    for sign in (1.0, -1.0):
        cand = img.rotate(sign * delta, resample=Image.BICUBIC, expand=True,
                          fillcolor=(0, 0, 0, 0))
        err = abs(lean(cand) - target)
        if err < best_err - 0.01:
            best, best_err = cand, err
    return best


def recolor(img: Image.Image, hue_deg: int, min_sat: int,
            value_scale: float = 1.0) -> Image.Image:
    """Перекрасить в тон hue_deg, сохранив светотень (V) и блики."""
    rgb = img.convert("RGBA")
    alpha = rgb.split()[3]
    hsv = rgb.convert("HSV")
    h, s, v = hsv.split()
    h = h.point(lambda _: int(hue_deg / 360 * 255))
    if min_sat:
        s = s.point(lambda p: max(p, min_sat))
    if value_scale != 1.0:
        v = v.point(lambda p: min(255, int(p * value_scale)))
    out = Image.merge("HSV", (h, s, v)).convert("RGBA")
    out.putalpha(alpha)
    return out


def load(name: str) -> Image.Image:
    path = ICONS / f"{name}.png"
    if not path.is_file():
        sys.exit(f"нет иконки: {path}")
    return Image.open(path).convert("RGBA")


def lean_rows(img: Image.Image) -> float:
    """Наклон вторым способом: для каждой строки берём центр тяжести
    непрозрачных пикселей и проводим через них прямую. Перекрёстная проверка
    к lean(): у симметричных форм обе оценки должны совпадать."""
    alpha = img.split()[3].load()
    w, h = img.size
    pts = []
    for y in range(h):
        sx = n = 0
        for x in range(w):
            a = alpha[x, y]
            if a >= 32:
                sx += a * x
                n += a
        if n > 255 * 3:
            pts.append((y, sx / n, n))
    if len(pts) < 4:
        return 0.0
    sw = sum(p[2] for p in pts)
    my = sum(p[0] * p[2] for p in pts) / sw
    mx = sum(p[1] * p[2] for p in pts) / sw
    num = sum(p[2] * (p[0] - my) * (p[1] - mx) for p in pts)
    den = sum(p[2] * (p[0] - my) ** 2 for p in pts)
    if den == 0:
        return 0.0
    # dy/dx -> наклон относительно вертикали (y растёт вниз)
    return -math.degrees(math.atan(num / den * -1)) if False else math.degrees(math.atan(-num / den))


def report() -> None:
    print(f"{'иконка':18s} {'по оси':>8s} {'по строкам':>11s}")
    for p in sorted(ICONS.glob("*.png")):
        if p.name.startswith("panel_") or p.name.startswith("ability_"):
            continue
        img = Image.open(p).convert("RGBA")
        print(f"{p.stem:18s} {lean(img):7.1f}° {lean_rows(img):10.1f}°")


def main() -> int:
    if "--report" in sys.argv:
        report()
        return 0

    ref = load(TILT_REF)
    target = lean(ref)
    print(f"образец наклона — {TILT_REF}: {target:.1f}°")
    for name in TILT_ICONS:
        img = load(name)
        before = lean(img)
        out = fit(tilt_like(img, target), img.size)
        out.save(ICONS / f"{name}.png")
        print(f"  {name:10s} {before:6.1f}° -> {lean(out):6.1f}°")

    for name, hue, min_sat, value_scale in RECOLORS:
        img = load(name)
        out = recolor(img, hue, min_sat, value_scale)
        out.save(ICONS / f"{name}.png")
        px = out.convert("RGBA").load()
        w, h = out.size
        r = g = b = n = 0
        for y in range(h):
            for x in range(w):
                pr, pg, pb, pa = px[x, y]
                if pa > 200:
                    r += pr
                    g += pg
                    b += pb
                    n += 1
        print(f"  {name:10s} тон {hue}° -> средний цвет "
              f"#{r//max(n,1):02x}{g//max(n,1):02x}{b//max(n,1):02x}")
    print("доводка иконок готова")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
