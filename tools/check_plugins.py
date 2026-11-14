#!/usr/bin/env python3
"""Проверка собранных артефактов ServerMMO без запуска сервера.

Проверяет:
  * plugin.yml внутри каждого jar (name, main, api-version 26.3);
  * наличие main-класса в jar;
  * таблицу stats.yml (90 уровней, long-диапазон, известные EntityType);
  * player-stats.yml (1 уровень = 10/1/200, 90 уровней);
  * ресурспак: pack.mcmeta, шрифт, текстуры, zip.

Запуск: python3 tools/check_plugins.py
"""
from __future__ import annotations

import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PLUGINS_DIR = ROOT / "server" / "plugins"
RESOURCEPACK = ROOT / "resourcepack"
RESOURCEPACK_ZIP = ROOT / "server" / "resourcepack" / "servermmo-hud.zip"

LONG_MIN, LONG_MAX = -(2 ** 63), 2 ** 63 - 1

errors: list[str] = []
warnings: list[str] = []


def fail(message: str) -> None:
    errors.append(message)


def warn(message: str) -> None:
    warnings.append(message)


def parse_simple_yaml(text: str) -> dict[str, str]:
    """Минимальный парсер «ключ: значение» для plugin.yml."""
    result: dict[str, str] = {}
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if line.startswith((" ", "\t")):
            continue
        if ":" in line:
            key, value = line.split(":", 1)
            result[key.strip()] = value.strip().strip("'\"")
    return result


def check_jar(path: Path, expected_name: str) -> None:
    if not path.is_file():
        fail(f"нет файла {path.relative_to(ROOT)}")
        return
    with zipfile.ZipFile(path) as jar:
        names = jar.namelist()
        if "plugin.yml" not in names:
            fail(f"{path.name}: внутри нет plugin.yml")
            return
        meta = parse_simple_yaml(jar.read("plugin.yml").decode("utf-8"))
        name = meta.get("name", "")
        main = meta.get("main", "")
        api = meta.get("api-version", "")
        if name != expected_name:
            fail(f"{path.name}: name={name!r}, ожидалось {expected_name!r}")
        if api != "26.3":
            fail(f"{path.name}: api-version={api!r}, ожидалось '26.3'")
        class_path = main.replace(".", "/") + ".class"
        if class_path not in names:
            fail(f"{path.name}: нет главного класса {class_path}")
        if not any(n.startswith("ru/servermmo/") for n in names):
            fail(f"{path.name}: нет классов ru.servermmo")
        if expected_name == "ServerMMOHud":
            # ширины глифов: без них строки панели разъедутся
            if "font-metrics.properties" not in names:
                fail(f"{path.name}: нет font-metrics.properties (ширины глифов)")
            else:
                metrics = jar.read("font-metrics.properties").decode("utf-8")
                if "advances=" not in metrics:
                    fail(f"{path.name}: font-metrics.properties без строки advances=")
        print(f"  ok {path.name}: {name} (api {api}), классов "
              f"{sum(1 for n in names if n.endswith('.class'))}")


def check_stats_yaml() -> None:
    stats = ROOT / "plugins" / "servermmo-core" / "src" / "main" / "resources" / "stats.yml"
    player_stats = ROOT / "plugins" / "servermmo-core" / "src" / "main" / "resources" / "player-stats.yml"
    for path in (stats, player_stats):
        if not path.is_file():
            fail(f"нет {path.relative_to(ROOT)}")
            return

    text = stats.read_text(encoding="utf-8")
    levels = re.findall(r"^  (\d+):$", text, re.MULTILINE)
    if len(levels) != 90:
        fail(f"stats.yml: уровней {len(levels)}, ожидалось 90")
    numbers = [int(v) for v in re.findall(r":\s*(\d+)\s*$", text, re.MULTILINE)]
    bad = [v for v in numbers if not LONG_MIN <= v <= LONG_MAX]
    if bad:
        fail(f"stats.yml: значения вне long: {bad[:5]}")
    if "type: WARDEN" not in text:
        fail("stats.yml: не найден моб 90 уровня (WARDEN)")
    if "health: 229645" not in text:
        fail("stats.yml: здоровье Хранителя должно быть 229645")

    ptext = player_stats.read_text(encoding="utf-8")
    plevels = re.findall(r"^  (\d+):$", ptext, re.MULTILINE)
    if len(plevels) != 90:
        fail(f"player-stats.yml: уровней {len(plevels)}, ожидалось 90")
    first = re.search(r"^  1:\n(?:    .*\n)*?    health: (\d+)\n(?:    .*\n)*?    damage: (\d+)\n(?:    .*\n)*?    xp-to-next: (\d+)",
                      ptext, re.MULTILINE)
    if not first:
        fail("player-stats.yml: не разобрать 1 уровень")
    else:
        hp, dmg, need = (int(v) for v in first.groups())
        if (hp, dmg, need) != (10, 1, 200):
            fail(f"player-stats.yml: 1 уровень = {hp}/{dmg}/{need}, ожидалось 10/1/200")
    print(f"  ok stats.yml: {len(levels)} уровней, player-stats.yml: {len(plevels)} уровней")


def check_resourcepack() -> None:
    meta_path = RESOURCEPACK / "pack.mcmeta"
    if not meta_path.is_file():
        fail("resourcepack/pack.mcmeta отсутствует")
        return
    meta = json.loads(meta_path.read_text(encoding="utf-8"))
    pack = meta.get("pack", {})
    if "min_format" not in pack or "max_format" not in pack:
        fail("pack.mcmeta: для 26.3 нужны min_format/max_format")
    if "description" not in pack:
        fail("pack.mcmeta: нет description - игра не покажет пак")
    if pack.get("min_format") != [97, 1] or pack.get("max_format") != 97:
        fail(f"pack.mcmeta: формат {pack.get('min_format')}-{pack.get('max_format')}, ожидалось [97,1]-97")

    font_path = RESOURCEPACK / "assets" / "servermmo" / "font" / "hud.json"
    if not font_path.is_file():
        fail("нет assets/servermmo/font/hud.json")
        return
    font = json.loads(font_path.read_text(encoding="utf-8"))
    chars: set[str] = set()
    advances: set[str] = set()
    for provider in font.get("providers", []):
        if provider.get("type") == "space":
            advances.update(provider.get("advances", {}).keys())
        if provider.get("type") == "bitmap":
            file_name = provider.get("file", "")
            namespace, path = file_name.split(":", 1)
            texture = RESOURCEPACK / "assets" / namespace / "textures" / path
            if not texture.is_file():
                fail(f"шрифт ссылается на отсутствующую текстуру {file_name}")
            # в JSON строка символов может быть одной строкой ("0123456789")
            for row in provider.get("chars", []):
                chars.update(row)
    # иконки HUD: сердце, меч, кристалл; цифры, "/" и буквы LVL/XP
    needed = {"\ue010", "\ue011", "\ue013", *"0123456789/", *"LVXP"}
    missing = sorted(needed - chars)
    if missing:
        fail(f"в шрифте нет глифов: {[hex(ord(c)) for c in missing]}")
    if "\ue100" not in advances:
        fail("в шрифте нет служебного сдвига \ue100 (разделитель иконка-число)")

    if not RESOURCEPACK_ZIP.is_file():
        fail("нет server/resourcepack/servermmo-hud.zip")
    else:
        with zipfile.ZipFile(RESOURCEPACK_ZIP) as zf:
            names = zf.namelist()
            if "pack.mcmeta" not in names:
                fail("в zip ресурспака pack.mcmeta не в корне")
            if not any(n.endswith("hud.json") for n in names):
                fail("в zip нет шрифта hud.json")
    print(f"  ok ресурспак: формат 97.1, глифов {len(chars)}, сдвигов {len(advances)}")


def check_server_properties() -> None:
    """Если ресурспак уже раздаётся, SHA-1 в server.properties должен совпадать с файлом."""
    props = ROOT / "server" / "server.properties"
    if not props.is_file():
        fail("нет server/server.properties")
        return
    # server.properties традиционно читается как ISO-8859-1 (latin-1)
    values: dict[str, str] = {}
    for line in props.read_text(encoding="latin-1").splitlines():
        if not line or line.lstrip().startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()

    for key in ("resource-pack", "resource-pack-sha1", "require-resource-pack", "resource-pack-prompt"):
        if key not in values:
            fail(f"server.properties: нет ключа {key}")

    url = values.get("resource-pack", "")
    sha1 = values.get("resource-pack-sha1", "").lower()
    pack = ROOT / "server" / "resourcepack" / "servermmo-hud.zip"

    if not url:
        print("  ok server.properties: ресурспак пока не раздаётся (resource-pack пуст)")
        return
    if not pack.is_file():
        fail("server.properties: resource-pack задан, а zip ресурспака отсутствует")
        return
    # Клиент качает пак через HttpURLConnection и сверяет SHA-1, поэтому
    # ссылка обязана вести прямо на zip. Канал ровно один — файл
    # servermmo-hud.zip в релизе GitHub: /releases/latest/download/ нельзя,
    # он плавающий, и клиент может получить пак, не совпадающий с SHA-1.
    if not url.startswith("https://github.com/oshee-g/servermmo/releases/download/"):
        fail("server.properties: resource-pack должен вести на файл релиза "
             "GitHub: https://github.com/oshee-g/servermmo/releases/download/"
             "<тег>/servermmo-hud.zip")
        return
    if "/releases/latest/" in url or not url.endswith(".zip"):
        fail("server.properties: ссылка должна быть на конкретный тег и "
             "оканчиваться на .zip (плавающий /latest/ ломает сверку SHA-1)")
        return

    real = hashlib.sha1(pack.read_bytes()).hexdigest()
    if sha1 != real:
        fail(f"server.properties: resource-pack-sha1={sha1 or '(пусто)'} не совпадает "
             f"с файлом ({real}). Обнови resource-pack-sha1 под новый zip")
        return
    print(f"  ok server.properties: ресурспак из релиза GitHub, SHA-1 совпадает ({url})")


def main() -> int:
    print("Проверка ServerMMO:")
    jars = [
        ("servermmo-core-1.0.0.jar", "ServerMMOCore"),
        ("servermmo-mobs-1.0.0.jar", "ServerMMOMobs"),
        ("servermmo-players-1.0.0.jar", "ServerMMOPlayers"),
        ("servermmo-hud-1.0.0.jar", "ServerMMOHud"),
    ]
    for file_name, plugin_name in jars:
        check_jar(PLUGINS_DIR / file_name, plugin_name)
    check_stats_yaml()
    check_resourcepack()
    check_server_properties()

    if warnings:
        print("\nПредупреждения:")
        for message in warnings:
            print("  ! " + message)
    if errors:
        print("\nОшибки:")
        for message in errors:
            print("  x " + message)
        return 1
    print("\nВСЕ ПРОВЕРКИ ПРОЙДЕНЫ")
    return 0


if __name__ == "__main__":
    sys.exit(main())
