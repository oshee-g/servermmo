package ru.servermmo.mobs;

import java.util.Objects;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import ru.servermmo.core.ServerMMOCore;

/**
 * Плагин кастомных мобов.
 *
 * <p>Обычные мобы в мире остаются ванильными: их поведение, удары, здоровье,
 * дроп и смерть не трогаются. Кастомные мобы (уровень, здоровье и урон из
 * таблицы, радиус агро, подпись с уровнем) создаются командой
 * {@code /mob spawn} или точками из {@code spawns} в config.yml.</p>
 */
public final class ServerMMOMobs extends JavaPlugin {

    private ServerMMOCore core;
    private AggressionTask aggression;
    private MobTags tags;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        core = ServerMMOCore.get();
        if (core == null) {
            getLogger().severe("ServerMMOCore не загружен - выключаюсь");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        MobSpawner spawner = new MobSpawner(this, core);
        MobCommand mobCommand = new MobCommand(this, spawner);
        PluginCommand command = Objects.requireNonNull(getCommand("mob"), "команда mob не описана");
        command.setExecutor(mobCommand);
        command.setTabCompleter(mobCommand);

        tags = new MobTags(this, core);
        long tagInterval = Math.max(1L, getConfig().getLong("tag.update-interval-ticks", 10L));
        tags.runTaskTimer(this, 20L, tagInterval);

        aggression = new AggressionTask(this, core);
        long interval = Math.max(1L, getConfig().getLong("aggression.check-interval-ticks", 10L));
        aggression.runTaskTimer(this, 20L, interval);

        getLogger().info("ServerMMOMobs включён: кастомные мобы только по команде /mob spawn,"
                + " радиус агро " + getConfig().getDouble("aggression.radius", 20.0D) + " блоков");
    }

    @Override
    public void onDisable() {
        if (aggression != null) {
            aggression.cancel();
            aggression = null;
        }
        if (tags != null) {
            tags.cancel();
            tags = null;
        }
    }

    ServerMMOCore core() {
        return core;
    }

    MobTags tags() {
        return tags;
    }
}
