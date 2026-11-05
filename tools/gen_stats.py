#!/usr/bin/env python3
"""Генератор YAML-таблиц статов для плагинов ServerMMO.

Читает tools/mobs_stats.txt (таблица из 90 уровней) и пишет:
  plugins/servermmo-core/src/main/resources/stats.yml         - статы мобов по уровням
  plugins/servermmo-core/src/main/resources/player-stats.yml    - статы игрока по уровням

Запуск:  python3 tools/gen_stats.py
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "tools" / "mobs_stats.txt"
CORE_OUT = ROOT / "plugins" / "servermmo-core" / "src" / "main" / "resources" / "stats.yml"
PLAYER_OUT = ROOT / "plugins" / "servermmo-core" / "src" / "main" / "resources" / "player-stats.yml"

# Русское название из таблицы -> EntityType Bukkit/Paper (26.3)
MOB_TYPES: dict[str, str] = {
    "Лосось": "SALMON",
    "Курица": "CHICKEN",
    "Головастик": "TADPOLE",
    "Летучая мышь": "BAT",
    "Треска": "COD",
    "Тропическая рыба": "TROPICAL_FISH",
    "Попугай": "PARROT",
    "Овца": "SHEEP",
    "Корова": "COW",
    "Чешуйница": "SILVERFISH",
    "Муушрум": "MOOSHROOM",
    "Светящийся спрут": "GLOW_SQUID",
    "Свинья": "PIG",
    "Спрут": "SQUID",
    "Броненосец": "ARMADILLO",
    "Снежный голем": "SNOW_GOLEM",
    "Медный голем": "COPPER_GOLEM",
    "Коза": "GOAT",
    "Иглобрюх": "PUFFERFISH",
    "Кошка": "CAT",
    "Нюхач": "SNIFFER",
    "Эндермит": "ENDERMITE",
    "Лиса": "FOX",
    "Скрипун": "CREAKING",
    "Лошадь-скелет": "SKELETON_HORSE",
    "Оцелот": "OCELOT",
    "Аксолотль": "AXOLOTL",
    "Дельфин": "DOLPHIN",
    "Паук": "SPIDER",
    "Страйдер": "STRIDER",
    "Странствующий торговец": "WANDERING_TRADER",
    "Наутилус": "NAUTILUS",
    "Счастливый гаст": "HAPPY_GHAST",
    "Наутилус-зомби": "ZOMBIE_NAUTILUS",
    "Тихоня": "ALLAY",
    "Фантом": "PHANTOM",
    "Слизень": "SLIME",
    "Лошадь-зомби": "ZOMBIE_HORSE",
    "Лягушка": "FROG",
    "Болотник": "BOGGED",
    "Кролик": "RABBIT",
    "Пустынник": "PARCHED",
    "Зимогор": "STRAY",
    "Лошадь": "HORSE",
    "Магмовый куб": "MAGMA_CUBE",
    "Мул": "MULE",
    "Скелет": "SKELETON",
    "Осёл": "DONKEY",
    "Пещерный паук": "CAVE_SPIDER",
    "Ифрит": "BLAZE",
    "Черепаха": "TURTLE",
    "Панда": "PANDA",
    "Вихрь": "BREEZE",
    "Лама": "LLAMA",
    "Разбойник": "PILLAGER",
    "Пиглин": "PIGLIN",
    "Лама торговца": "TRADER_LLAMA",
    "Пчела": "BEE",
    "Верблюд": "CAMEL",
    "Досаждатель": "VEX",
    "Верблюд-кадавр": "CAMEL_HUSK",
    "Шалкер": "SHULKER",
    "Иллюзор": "ILLUSIONER",
    "Деревенский житель": "VILLAGER",
    "Белый медведь": "POLAR_BEAR",
    "Скелет-иссушитель": "WITHER_SKELETON",
    "Волк": "WOLF",
    "Страж": "GUARDIAN",
    "Вызыватель": "EVOKER",
    "Эндермен": "ENDERMAN",
    "Гаст": "GHAST",
    "Зоглин": "ZOGLIN",
    "Поборник": "VINDICATOR",
    "Хоглин": "HOGLIN",
    "Зомби": "ZOMBIE",
    "Древний страж": "ELDER_GUARDIAN",
    "Зомби-житель": "ZOMBIE_VILLAGER",
    "Брутальный пиглин": "PIGLIN_BRUTE",
    "Ведьма": "WITCH",
    "Кадавр": "HUSK",
    "Серный куб": "SULFUR_CUBE",
    "Утопленник": "DROWNED",
    "Зомбифицированный пиглин": "ZOMBIFIED_PIGLIN",
    "Разоритель": "RAVAGER",
    "Железный голем": "IRON_GOLEM",
    "Дракон Края": "ENDER_DRAGON",
    "Гигант": "GIANT",
    "Крипер": "CREEPER",
    "Иссушитель": "WITHER",
    "Хранитель": "WARDEN",
}

ROW_RE = re.compile(r"^\s*(\d+)\s+(.+?)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s*$")

# Переопределение стартовых статов игрока (как в макете HUD: 20 HP, 5 урона, 0/200 XP)
PLAYER_START_HEALTH = 20
PLAYER_START_DAMAGE = 5
PLAYER_START_XP_NEXT = 200


def read_rows() -> list[tuple[int, str, int, int, int, int]]:
    rows = []
    for line in SRC.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.strip().startswith("-") or line.strip().startswith("Уровень"):
            continue
        m = ROW_RE.match(line)
        if not m:
            raise SystemExit(f"Не удалось разобрать строку: {line!r}")
        rows.append((int(m.group(1)), m.group(2).strip(), int(m.group(3)),
                     int(m.group(4)), int(m.group(5)), int(m.group(6))))
    if len(rows) != 90:
        raise SystemExit(f"Ожидалось 90 уровней, найдено {len(rows)}")
    return rows


def q(value: str) -> str:
    return '"' + value.replace('"', '\\"') + '"'


def write_core(rows) -> None:
    out = []
    out.append("# ==========================================================================")
    out.append("# ServerMMOCore - таблица статов мобов (90 уровней)")
    out.append("# ==========================================================================")
    out.append("# Все числа - целые long (64 бита), ограничение ~9.2e18 на значение.")
    out.append("# health  - здоровье моба этого уровня")
    out.append("# damage  - базовый урон моба этого уровня")
    out.append("# xp      - сколько опыта получает игрок за убийство моба")
    out.append("# xp-to-next - сколько опыта нужно игроку, чтобы перейти с этого уровня на следующий")
    out.append("# type    - EntityType Paper (https://jd.papermc.io/paper/26.3/org/bukkit/entity/EntityType.html)")
    out.append("# Файл сгенерирован tools/gen_stats.py из tools/mobs_stats.txt - правки вручную допустимы.")
    out.append("levels:")
    for level, name, health, damage, xp, xp_next in rows:
        out.append(f"  {level}:")
        out.append(f"    mob: {q(name)}")
        out.append(f"    type: {MOB_TYPES[name]}")
        out.append(f"    health: {health}")
        out.append(f"    damage: {damage}")
        out.append(f"    xp: {xp}")
        out.append(f"    xp-to-next: {xp_next}")
    CORE_OUT.parent.mkdir(parents=True, exist_ok=True)
    CORE_OUT.write_text("\n".join(out) + "\n", encoding="utf-8")
    print(f"  {CORE_OUT.relative_to(ROOT)}: {len(rows)} уровней")


def write_players(rows) -> None:
    out = []
    out.append("# ==========================================================================")
    out.append("# ServerMMOPlayers - статы игрока по уровням")
    out.append("# ==========================================================================")
    out.append("# health     - максимальное здоровье игрока данного уровня")
    out.append("# damage     - базовый урон игрока (по мобам) данного уровня")
    out.append("# xp-to-next - сколько опыта нужно набрать, чтобы получить следующий уровень")
    out.append("#")
    out.append("# 1 уровень задан по макету HUD: 20 HP / 5 урона / 200 XP.")
    out.append("# Со 2 уровня статы совпадают с таблицей мобов (здоровье и урон моба того же уровня).")
    out.append("# Файл сгенерирован tools/gen_stats.py из tools/mobs_stats.txt - правки вручную допустимы.")
    out.append("start-level: 1")
    out.append("max-level: 90")
    out.append("levels:")
    for level, name, health, damage, _xp, xp_next in rows:
        hp = PLAYER_START_HEALTH if level == 1 else health
        dmg = PLAYER_START_DAMAGE if level == 1 else damage
        need = PLAYER_START_XP_NEXT if level == 1 else xp_next
        out.append(f"  {level}:")
        out.append(f"    name: {q(name)}")
        out.append(f"    health: {hp}")
        out.append(f"    damage: {dmg}")
        out.append(f"    xp-to-next: {need}")
    PLAYER_OUT.parent.mkdir(parents=True, exist_ok=True)
    PLAYER_OUT.write_text("\n".join(out) + "\n", encoding="utf-8")
    print(f"  {PLAYER_OUT.relative_to(ROOT)}: {len(rows)} уровней")


def main() -> int:
    rows = read_rows()
    missing = [r[1] for r in rows if r[1] not in MOB_TYPES]
    if missing:
        print("Нет маппинга EntityType для: " + ", ".join(missing), file=sys.stderr)
        return 1
    print("Пишу статы:")
    write_core(rows)
    write_players(rows)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
