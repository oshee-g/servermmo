package ru.servermmo.core.stats;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Таблица статов мобов по уровням (по умолчанию 90 уровней из {@code stats.yml}).
 */
public final class LevelTable {

    private final Map<Integer, LevelStats> byLevel;
    private final Map<EntityType, LevelStats> byType;
    private final int maxLevel;

    private LevelTable(Map<Integer, LevelStats> byLevel, Map<EntityType, LevelStats> byType) {
        this.byLevel = Collections.unmodifiableMap(byLevel);
        this.byType = Collections.unmodifiableMap(byType);
        this.maxLevel = byLevel.isEmpty() ? 0 : Collections.max(byLevel.keySet());
    }

    public static LevelTable empty() {
        return new LevelTable(new TreeMap<>(), new HashMap<>());
    }

    /**
     * Загружает таблицу из файла, лежащего внутри jar-файла плагина.
     *
     * @param plugin плагин-владелец
     * @param name   имя ресурса, например {@code stats.yml}
     */
    public static LevelTable load(JavaPlugin plugin, String name) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                new java.io.InputStreamReader(
                        java.util.Objects.requireNonNull(plugin.getResource(name), "Нет ресурса " + name),
                        java.nio.charset.StandardCharsets.UTF_8));

        ConfigurationSection root = yaml.getConfigurationSection("levels");
        if (root == null) {
            plugin.getLogger().warning("В " + name + " нет секции levels - таблица статов пуста");
            return empty();
        }

        Map<Integer, LevelStats> byLevel = new TreeMap<>();
        Map<EntityType, LevelStats> byType = new HashMap<>();

        for (String rawLevel : root.getKeys(false)) {
            int level;
            try {
                level = Integer.parseInt(rawLevel.trim());
            } catch (NumberFormatException ex) {
                plugin.getLogger().warning("Пропущен уровень с некорректным номером: " + rawLevel);
                continue;
            }
            ConfigurationSection section = root.getConfigurationSection(rawLevel);
            if (section == null) {
                continue;
            }
            String mobName = section.getString("mob", rawLevel);
            String typeId = section.getString("type", "");
            long health = section.getLong("health", 1L);
            long damage = section.getLong("damage", 0L);
            long xp = section.getLong("xp", 0L);
            long xpToNext = section.getLong("xp-to-next", 0L);

            if (health <= 0L) {
                plugin.getLogger().warning("Уровень " + level + ": health <= 0, ставлю 1");
                health = 1L;
            }
            if (damage < 0L) {
                plugin.getLogger().warning("Уровень " + level + ": damage < 0, ставлю 0");
                damage = 0L;
            }

            LevelStats stats = new LevelStats(level, mobName, typeId, health, damage, xp, xpToNext);
            byLevel.put(level, stats);

            EntityType type = resolveType(typeId);
            if (type == null) {
                plugin.getLogger().warning("Уровень " + level + ": неизвестный EntityType '" + typeId + "'");
            } else if (byType.put(type, stats) != null) {
                plugin.getLogger().warning("Уровень " + level + ": тип " + typeId
                        + " уже был в таблице - оставлен первый ("
                        + byType.get(type).level() + ")");
            }
        }

        if (byLevel.isEmpty()) {
            plugin.getLogger().warning("Таблица статов " + name + " пуста");
        }
        return new LevelTable(byLevel, byType);
    }

    private static EntityType resolveType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toUpperCase(java.util.Locale.ROOT);
        try {
            // Paper 26.3: EntityType - не enum, а поле реестра; valueOf оставлен для совместимости.
            return EntityType.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            // ничего не делаем, ниже пробуем через реестр
        }
        NamespacedKey key = NamespacedKey.minecraft(value.toLowerCase(java.util.Locale.ROOT));
        EntityType type = Registry.ENTITY_TYPE.get(key);
        return type;
    }

    /** Статы по номеру уровня либо {@code null}. */
    public LevelStats byLevel(int level) {
        return byLevel.get(level);
    }

    /** Статы по типу сущности либо {@code null}, если типа нет в таблице. */
    public LevelStats byType(EntityType type) {
        return byType.get(type);
    }

    /** Все уровни в порядке возрастания. */
    public Collection<LevelStats> all() {
        return byLevel.values();
    }

    /** Максимальный уровень таблицы (0, если таблица пуста). */
    public int maxLevel() {
        return maxLevel;
    }

    public boolean isEmpty() {
        return byLevel.isEmpty();
    }

    /** Проверка, что реестр типов вообще доступен (используется в тестах). */
    public static boolean registryAvailable() {
        return Bukkit.getServer() != null;
    }
}
