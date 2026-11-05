package ru.servermmo.core.stats;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Таблица статов игрока по уровням (файл {@code player-stats.yml}).
 *
 * <p>1 уровень задан по макету HUD: 20 HP, 5 урона, 200 опыта до 2 уровня.
 * Со 2 уровня здоровье и урон совпадают с мобом того же уровня.</p>
 */
public final class PlayerStatTable {

    private final Map<Integer, PlayerLevelStats> byLevel;
    private final int maxLevel;

    private PlayerStatTable(Map<Integer, PlayerLevelStats> byLevel) {
        this.byLevel = Collections.unmodifiableMap(byLevel);
        this.maxLevel = byLevel.isEmpty() ? 0 : Collections.max(byLevel.keySet());
    }

    public static PlayerStatTable empty() {
        return new PlayerStatTable(new TreeMap<>());
    }

    public static PlayerStatTable load(JavaPlugin plugin, String name) {
        java.io.InputStream resource = plugin.getResource(name);
        if (resource == null) {
            plugin.getLogger().severe("В jar плагина нет ресурса " + name + " - таблица не загружена");
            return empty();
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                new java.io.InputStreamReader(resource, java.nio.charset.StandardCharsets.UTF_8));

        ConfigurationSection root = yaml.getConfigurationSection("levels");
        if (root == null) {
            plugin.getLogger().warning("В " + name + " нет секции levels - статы игрока пусты");
            return empty();
        }

        Map<Integer, PlayerLevelStats> byLevel = new TreeMap<>();
        for (String rawLevel : root.getKeys(false)) {
            int level;
            try {
                level = Integer.parseInt(rawLevel.trim());
            } catch (NumberFormatException ex) {
                plugin.getLogger().warning("Пропущен уровень игрока с некорректным номером: " + rawLevel);
                continue;
            }
            ConfigurationSection section = root.getConfigurationSection(rawLevel);
            if (section == null) {
                continue;
            }
            String displayName = section.getString("name", rawLevel);
            long health = section.getLong("health", 20L);
            long damage = section.getLong("damage", 1L);
            long xpToNext = section.getLong("xp-to-next", 200L);
            if (health <= 0L) {
                plugin.getLogger().warning("Игрок, уровень " + level + ": health <= 0, ставлю 1");
                health = 1L;
            }
            if (damage < 0L) {
                damage = 0L;
            }
            if (xpToNext <= 0L) {
                xpToNext = 1L;
            }
            byLevel.put(level, new PlayerLevelStats(level, displayName, health, damage, xpToNext));
        }

        if (byLevel.isEmpty()) {
            plugin.getLogger().warning("Таблица статов игрока " + name + " пуста");
        }
        return new PlayerStatTable(byLevel);
    }

    /** Статы уровня либо {@code null}. */
    public PlayerLevelStats byLevel(int level) {
        return byLevel.get(level);
    }

    /** Статы ближайшего подходящего уровня: если уровня нет, берётся последний не выше запрошенного. */
    public PlayerLevelStats forLevel(int level) {
        PlayerLevelStats exact = byLevel.get(level);
        if (exact != null) {
            return exact;
        }
        PlayerLevelStats best = null;
        for (Map.Entry<Integer, PlayerLevelStats> entry : byLevel.entrySet()) {
            if (entry.getKey() <= level) {
                best = entry.getValue();
            }
        }
        return best != null ? best : (byLevel.isEmpty() ? null : byLevel.values().iterator().next());
    }

    public Collection<PlayerLevelStats> all() {
        return byLevel.values();
    }

    public int maxLevel() {
        return maxLevel;
    }

    public boolean isEmpty() {
        return byLevel.isEmpty();
    }
}
