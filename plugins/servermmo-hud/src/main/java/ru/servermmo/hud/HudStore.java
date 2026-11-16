package ru.servermmo.hud;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Хранилище числовых данных панели: по профилю на игрока в {@code players.yml}.
 *
 * <p>Это заглушки: реальной экономики и кланов пока нет, значения меняет
 * оператор командами. Лежат отдельным файлом, чтобы не затирать
 * {@code config.yml} при его перегенерации.</p>
 */
final class HudStore {

    private final JavaPlugin plugin;
    private final Map<UUID, HudProfile> profiles = new HashMap<>();
    private File file;
    private FileConfiguration data;

    HudStore(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    void load() {
        this.file = new File(plugin.getDataFolder(), "players.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        profiles.clear();
        String root = "players";
        if (!data.isConfigurationSection(root)) {
            return;
        }
        for (String key : data.getConfigurationSection(root).getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            String path = root + "." + key + ".";
            HudProfile profile = new HudProfile();
            profile.classIndex(data.getInt(path + "class", 0));
            profile.clanName(data.getString(path + "clan", ""));
            profile.clanLevel(data.getLong(path + "clan-level", 1L));
            profile.clanXp(data.getLong(path + "clan-xp", 0L));
            profile.coins(data.getLong(path + "coins", 0L));
            profile.starCoins(data.getLong(path + "star-coins", 0L));
            profile.battlePoints(data.getLong(path + "battle-points", 0L));
            profile.nanogems(data.getLong(path + "nanogems", 0L));
            profile.armor(data.getLong(path + "armor", 0L));
            // Масштаб интерфейса и разрешение окна: чем считать отступ панели
            profile.guiScale(data.getInt(path + "gui-scale", 0));
            profile.screenWidth(data.getInt(path + "screen-width", 0));
            profile.screenHeight(data.getInt(path + "screen-height", 0));
            profiles.put(uuid, profile);
        }
        plugin.getLogger().info("ServerMMOHud: профилей панели загружено " + profiles.size());
    }

    /** Профиль игрока, с созданием пустого при первом обращении. */
    HudProfile get(UUID uuid) {
        return profiles.computeIfAbsent(uuid, key -> new HudProfile());
    }

    void save() {
        for (Map.Entry<UUID, HudProfile> entry : profiles.entrySet()) {
            HudProfile profile = entry.getValue();
            String path = "players." + entry.getKey() + ".";
            data.set(path + "class", profile.classIndex());
            data.set(path + "clan", profile.clanName());
            data.set(path + "clan-level", profile.clanLevel());
            data.set(path + "clan-xp", profile.clanXp());
            data.set(path + "coins", profile.coins());
            data.set(path + "star-coins", profile.starCoins());
            data.set(path + "battle-points", profile.battlePoints());
            data.set(path + "nanogems", profile.nanogems());
            data.set(path + "armor", profile.armor());
            data.set(path + "gui-scale", profile.guiScale());
            data.set(path + "screen-width", profile.screenWidth());
            data.set(path + "screen-height", profile.screenHeight());
        }
        try {
            data.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Не смог сохранить players.yml: " + ex.getMessage());
        }
    }
}
