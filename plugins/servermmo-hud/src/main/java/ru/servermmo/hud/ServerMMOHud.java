package ru.servermmo.hud;

import java.io.InputStream;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import ru.servermmo.core.ServerMMOCore;

/**
 * Плагин HUD: здоровье, урон, уровень и опыт в левом верхнем углу экрана.
 *
 * <p>Рисует панель клиент: строки босс-бара + глифы ресурспака. Плагин только
 * подставляет числа, поэтому HUD не зависит от камеры и не висит сущностью в
 * мире.</p>
 */
public final class ServerMMOHud extends JavaPlugin {

    private HudSettings settings;
    private HudManager manager;
    private HudText text;

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

        manager = new HudManager(this, settings, text);
        manager.runTaskTimer(this, 20L, settings.updateIntervalTicks());

        Bukkit.getPluginManager().registerEvents(new HudListener(manager), this);

        PluginCommand hud = Objects.requireNonNull(getCommand("hud"), "команда hud не описана");
        hud.setExecutor(new HudCommand(this, settings));

        getLogger().info("ServerMMOHud включён: панель строками босс-бара, offset-x="
                + settings.offsetX() + " px, anchor-x=" + settings.hotbarAnchorX()
                + " px, здоровье и индикатор урона — строкой хотбара; "
                + "без загруженного ресурспака HUD полностью скрыт; "
                + "подстройка: /hud offsetx <px>, /hud anchorx <px>, /hud scale <3|4|5>, /hud reset");
        getLogger().info("Ссылка на ресурспак в server.properties: " + resourcePackSummary());
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

    /**
     * Ссылка на ресурспак, которую сервер отдаёт клиенту — чтобы в
     * {@code /hud debug} было видно, ту ли ссылку и тот ли SHA-1 ждёт HUD.
     */
    String resourcePackSummary() {
        String url = getServer().getResourcePack();
        String hash = getServer().getResourcePackHash();
        if (url == null || url.isEmpty()) {
            return "НЕ ЗАДАНА (server.properties: resource-pack пуст) — клиент не "
                    + "загрузит пак, HUD останется скрыт";
        }
        return url + (hash == null || hash.isEmpty()
                ? " (SHA-1 не задан)" : " (SHA-1 " + hash + ")");
    }
}
