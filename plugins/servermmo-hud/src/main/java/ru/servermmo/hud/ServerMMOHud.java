package ru.servermmo.hud;

import java.io.InputStream;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import ru.servermmo.core.ServerMMOCore;

/**
 * Плагин HUD: класс, уровень, клан и валюты в левом верхнем углу экрана.
 *
 * <p>Рисует панель клиент: строки босс-бара + глифы ресурспака. Плагин только
 * подставляет числа, поэтому HUD не зависит от камеры и не висит сущностью в
 * мире.</p>
 *
 * <p>Ресурспак раздаёт сам сервер строкой {@code resource-pack} в
 * {@code server.properties}: плагин про пак ничего не знает, не хранит его и
 * не следит за его загрузкой.</p>
 */
public final class ServerMMOHud extends JavaPlugin {

    private HudSettings settings;
    private HudManager manager;
    private HudText text;
    private HudStore store;

    @Override
    public void onEnable() {
        if (ServerMMOCore.get() == null) {
            getLogger().severe("ServerMMOCore не загружен - выключаюсь");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        settings = HudSettings.load(this);

        try (InputStream metrics = getResource("font-metrics.properties")) {
            text = new HudText(metrics);
        } catch (Exception ex) {
            getLogger().warning("Нет font-metrics.properties, беру ширины по умолчанию: "
                    + ex.getMessage());
            text = new HudText(null);
        }

        store = new HudStore(this);
        store.load();

        manager = new HudManager(this, settings, text, store);
        manager.runTaskTimer(this, 20L, settings.updateIntervalTicks());

        Bukkit.getPluginManager().registerEvents(new HudListener(manager), this);

        PluginCommand hud = Objects.requireNonNull(getCommand("hud"), "команда hud не описана");
        hud.setExecutor(new HudCommand(this, settings));

        // Команды на числовые данные панели: /class, /clan, /coins, /star,
        // /bp, /gems, /armor. Механики за ними пока нет, это заглушки.
        HudDataCommand data = new HudDataCommand(this);
        for (String name : new String[]{"class", "clan", "coins", "star", "bp", "gems", "armor"}) {
            PluginCommand dataCommand = Objects.requireNonNull(getCommand(name),
                    "команда " + name + " не описана");
            dataCommand.setExecutor(data);
        }

        getLogger().info("ServerMMOHud включён: панель строками босс-бара, экран "
                + settings.screenWidth() + "x" + settings.screenHeight()
                + ", масштаб " + (settings.guiScale() == 0 ? "Авто" : settings.guiScale())
                + " (интерфейс " + settings.interfaceWidth()
                + " px), отступ от края " + settings.marginLeft()
                + " px, anchor-x=" + settings.hotbarAnchorX()
                + " px, здоровье, урон и защита — строкой хотбара; "
                + "ресурспак раздаёт сервер (server.properties); "
                + "подстройка: /hud scale <1..8|auto>, /hud screen <ширина>, "
                + "/hud margin <px>, /hud offsetx <px>, /hud anchorx <px>, /hud reset");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.removeAll();
            manager.cancel();
            manager = null;
        }
        if (store != null) {
            store.save();
        }
    }

    public HudSettings settings() {
        return settings;
    }

    /** Числовые данные панели (класс, клан, валюты) — для команд. */
    HudStore store() {
        return store;
    }

    /** Раскладка панели: по ней команды считают отступы под экран игрока. */
    HudText text() {
        return text;
    }

    public HudManager manager() {
        return manager;
    }
}
