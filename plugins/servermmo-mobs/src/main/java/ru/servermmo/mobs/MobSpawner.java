package ru.servermmo.mobs;

import java.util.Locale;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import ru.servermmo.core.ServerMMOCore;
import ru.servermmo.core.stats.LevelStats;

/**
 * Создаёт кастомных мобов.
 *
 * <p>Обычные мобы, которых спавнит игра, остаются ванильными. Кастомный моб
 * появляется только здесь: по команде {@code /mob spawn} или из списка
 * {@code spawns} в config.yml (когда там заданы координаты).</p>
 */
final class MobSpawner {

    private final JavaPlugin plugin;
    private final ServerMMOCore core;
    private final MobTags tags;
    private final NamespacedKey levelKey;

    MobSpawner(JavaPlugin plugin, ServerMMOCore core, MobTags tags) {
        this.plugin = plugin;
        this.core = core;
        this.tags = tags;
        this.levelKey = new NamespacedKey(core, "mob-level");
    }

    /** Тип моба по русскому названию из таблицы либо по имени EntityType. */
    EntityType resolveType(String raw) {
        String probe = raw.trim();
        if (probe.isEmpty()) {
            return null;
        }
        for (LevelStats level : core.levels().all()) {
            if (level.mobName().equalsIgnoreCase(probe)) {
                EntityType type = byId(level.entityTypeId());
                if (type != null) {
                    return type;
                }
            }
        }
        EntityType direct = byId(probe);
        if (direct != null) {
            return direct;
        }
        for (EntityType type : Registry.ENTITY_TYPE) {
            String key = type.getKey().getKey();
            String name = type.getKey().getNamespace().equals("minecraft") ? key : type.getKey().toString();
            if (key.equalsIgnoreCase(probe.replace(' ', '_').replace('-', '_'))
                    || name.equalsIgnoreCase(probe)) {
                return type;
            }
        }
        return null;
    }

    /** Тип по названию из таблицы ("CHICKEN") или по ключу ("chicken"). */
    private EntityType byId(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String value = id.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        return Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(value));
    }

    /** Спавнит кастомного моба и сразу выдаёт ему статы и подпись. */
    LivingEntity spawn(Location location, EntityType type, int level) {
        if (location.getWorld() == null || type == null) {
            return null;
        }
        Entity created = location.getWorld().spawnEntity(location, type);
        if (!(created instanceof Mob mob)) {
            created.remove();
            return null;
        }
        mob.getPersistentDataContainer().set(levelKey, PersistentDataType.INTEGER, level);
        core.combat().markCustom(mob);
        core.prepareMob(mob);
        core.combat().setMobLevel(mob, level);
        mob.setPersistent(true);
        tags.apply(mob);
        return mob;
    }

    /** Статы уровня (для сообщений и подписей). */
    LevelStats stats(int level) {
        return core.levels().byLevel(level);
    }

    /**
     * Спавнит мобов из секции {@code spawns} в config.yml.
     *
     * <p>Формат записи:</p>
     * <pre>
     * spawns:
     *   - world: world
     *     type: Курица
     *     level: 2
     *     x: 100
     *     y: 65
     *     z: -20
     * </pre>
     * <p>Координаты можно дописать заранее — мобы появятся при старте
     * сервера. Битые записи пропускаются с предупреждением в лог.</p>
     */
    int spawnConfigured() {
        var section = plugin.getConfig().getMapList("spawns");
        int spawned = 0;
        for (java.util.Map<?, ?> entry : section) {
            Object rawType = entry.get("type");
            String typeName = rawType == null ? "" : String.valueOf(rawType).trim();
            EntityType type = resolveType(typeName);
            if (type == null) {
                plugin.getLogger().warning("spawns: неизвестный моб \"" + typeName + "\" - пропускаю");
                continue;
            }
            int level = parseInt(entry.get("level"), 1);
            if (stats(level) == null) {
                plugin.getLogger().warning("spawns: нет уровня " + level + " - пропускаю " + typeName);
                continue;
            }
            Object rawWorld = entry.get("world");
            String worldName = rawWorld == null ? "world" : String.valueOf(rawWorld).trim();
            var world = plugin.getServer().getWorld(worldName);
            if (world == null) {
                plugin.getLogger().warning("spawns: нет мира " + worldName + " - пропускаю " + typeName);
                continue;
            }
            Location location = new Location(world,
                    parseDouble(entry.get("x")), parseDouble(entry.get("y")), parseDouble(entry.get("z")));
            if (spawn(location, type, level) != null) {
                spawned++;
            }
        }
        return spawned;
    }

    private static int parseInt(Object raw, int fallback) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static double parseDouble(Object raw) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(raw).trim().replace(',', '.'));
        } catch (NumberFormatException ex) {
            return 0.0D;
        }
    }

    JavaPlugin plugin() {
        return plugin;
    }
}
