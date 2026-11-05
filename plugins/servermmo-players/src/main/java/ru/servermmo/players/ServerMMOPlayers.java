package ru.servermmo.players;

import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import ru.servermmo.core.ServerMMOCore;

/** Плагин игроков: уровни, опыт и команды просмотра статов. */
public final class ServerMMOPlayers extends JavaPlugin {

    @Override
    public void onEnable() {
        if (ServerMMOCore.get() == null) {
            getLogger().severe("ServerMMOCore не загружен - выключаюсь");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        Bukkit.getPluginManager().registerEvents(new LevelUpListener(this), this);

        PluginCommand stats = Objects.requireNonNull(getCommand("stats"), "команда stats не описана");
        StatsCommand executor = new StatsCommand(this);
        stats.setExecutor(executor);
        stats.setTabCompleter(executor);

        getLogger().info("ServerMMOPlayers включён");
    }
}
