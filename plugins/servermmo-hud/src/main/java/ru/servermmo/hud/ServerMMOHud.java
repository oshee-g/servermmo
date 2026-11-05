package ru.servermmo.hud;

import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import ru.servermmo.core.ServerMMOCore;

/** Плагин HUD: рисует здоровье, урон, уровень и опыт в левом верхнем углу. */
public final class ServerMMOHud extends JavaPlugin {

    private HudSettings settings;
    private HudManager manager;

    @Override
    public void onEnable() {
        if (ServerMMOCore.get() == null) {
            getLogger().severe("ServerMMOCore не загружен - выключаюсь");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        settings = HudSettings.load(this);
        manager = new HudManager(this, settings);
        manager.runTaskTimer(this, 20L, Math.max(1L, settings.updateIntervalTicks()));

        Bukkit.getPluginManager().registerEvents(new HudListener(this, manager, settings), this);

        PluginCommand hud = Objects.requireNonNull(getCommand("hud"), "команда hud не описана");
        hud.setExecutor(new HudCommand(this, settings));

        getLogger().info("ServerMMOHud включён: distance=" + settings.distance()
                + " offsetX=" + settings.offsetX()
                + " offsetY=" + settings.offsetY()
                + " scale=" + settings.scale()
                + ", подстройка: /hud scale|distance|offsetx|offsety, /hud reset");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.removeAll();
            manager.cancel();
            manager = null;
        }
    }

    public HudSettings settings() {
        return settings;
    }

    public HudManager manager() {
        return manager;
    }
}
