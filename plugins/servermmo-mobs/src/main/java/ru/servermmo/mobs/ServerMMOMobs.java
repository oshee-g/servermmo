package ru.servermmo.mobs;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import ru.servermmo.core.ServerMMOCore;

/**
 * Плагин поведения мобов: агро в радиусе 20 блоков, поворот головы и тела,
 * удары по кастомной системе.
 */
public final class ServerMMOMobs extends JavaPlugin {

    private AggressionTask task;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ServerMMOCore core = ServerMMOCore.get();
        if (core == null) {
            getLogger().severe("ServerMMOCore не загружен - выключаюсь");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        task = new AggressionTask(this, core);
        long interval = Math.max(1L, getConfig().getLong("aggression.check-interval-ticks", 10L));
        task.runTaskTimer(this, 20L, interval);
        getLogger().info("ServerMMOMobs включён: радиус агро "
                + getConfig().getDouble("aggression.radius", 20.0D) + " блоков");
    }

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
