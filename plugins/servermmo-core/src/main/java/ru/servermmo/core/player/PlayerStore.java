package ru.servermmo.core.player;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import ru.servermmo.core.ServerMMOCore;
import ru.servermmo.core.stats.PlayerLevelStats;

/**
 * Хранилище профилей игроков: память + {@code playerdata.yml}.
 */
public final class PlayerStore {

    private final ServerMMOCore plugin;
    private final Map<UUID, PlayerProfile> profiles = new ConcurrentHashMap<>();
    private final File file;

    public PlayerStore(ServerMMOCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "playerdata.yml");
    }

    public void loadAll() {
        profiles.clear();
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("players");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("playerdata.yml: некорректный UUID " + key);
                continue;
            }
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            PlayerProfile profile = new PlayerProfile(uuid, section.getString("name", key));
            profile.level(section.getLong("level", 1L));
            profile.xp(section.getLong("xp", 0L));
            profile.health(section.getLong("health", 20L));
            PlayerLevelStats stats = plugin.playerStats().forLevel((int) Math.min(Integer.MAX_VALUE, profile.level()));
            profile.applyStats(stats, false);
            profile.clampHealth();
            profile.clean();
            profiles.put(uuid, profile);
        }
        plugin.getLogger().info("Загружено профилей игроков: " + profiles.size());
    }

    public void saveAll() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (PlayerProfile profile : profiles.values()) {
            if (!profile.dirty()) {
                continue;
            }
            String base = "players." + profile.uuid();
            yaml.set(base + ".name", profile.name());
            yaml.set(base + ".level", profile.level());
            yaml.set(base + ".xp", profile.xp());
            yaml.set(base + ".health", profile.health());
        }
        if (yaml.getKeys(false).isEmpty()) {
            return;
        }
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                plugin.getLogger().warning("Не удалось создать папку плагина для сохранения профилей");
                return;
            }
            yaml.save(file);
            profiles.values().forEach(PlayerProfile::clean);
        } catch (IOException ex) {
            plugin.getLogger().warning("Не удалось сохранить playerdata.yml: " + ex.getMessage());
        }
    }

    /** Профиль игрока либо {@code null}, если его ещё нет. */
    public PlayerProfile get(UUID uuid) {
        return profiles.get(uuid);
    }

    /** Профиль игрока, создаётся при первом обращении. */
    public PlayerProfile getOrCreate(Player player) {
        return profiles.computeIfAbsent(player.getUniqueId(), uuid -> {
            PlayerProfile profile = new PlayerProfile(uuid, player.getName());
            PlayerLevelStats stats = plugin.playerStats().forLevel(1);
            profile.applyStats(stats, true);
            return profile;
        });
    }

    public void removeFromMemory(UUID uuid) {
        profiles.remove(uuid);
    }

    public int size() {
        return profiles.size();
    }
}
