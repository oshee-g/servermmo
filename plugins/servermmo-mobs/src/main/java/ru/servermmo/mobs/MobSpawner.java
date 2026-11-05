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
    private final NamespacedKey levelKey;

    MobSpawner(JavaPlugin plugin, ServerMMOCore core) {
        this.plugin = plugin;
        this.core = core;
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
        return mob;
    }

    /** Статы уровня (для сообщений и подписей). */
    LevelStats stats(int level) {
        return core.levels().byLevel(level);
    }

    JavaPlugin plugin() {
        return plugin;
    }
}
