package ru.servermmo.hud;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

/** Тик всех HUD-ов. */
final class HudManager extends BukkitRunnable {

    private final ServerMMOHud plugin;
    private final HudSettings settings;
    private final HudText text;
    private final Map<UUID, PlayerHud> huds = new HashMap<>();

    HudManager(ServerMMOHud plugin, HudSettings settings, HudText text) {
        this.plugin = plugin;
        this.settings = settings;
        this.text = text;
    }

    @Override
    public void run() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            PlayerHud hud = huds.computeIfAbsent(player.getUniqueId(),
                    uuid -> new PlayerHud(settings, text, player));
            try {
                hud.tick();
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("HUD " + player.getName() + " упал: " + ex.getMessage());
                hud.hide();
            }
        }
        huds.entrySet().removeIf(entry -> {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                entry.getValue().hide();
                return true;
            }
            return false;
        });
    }

    /** Показывает панель игроку (вход в игру). */
    void show(Player player) {
        PlayerHud hud = huds.computeIfAbsent(player.getUniqueId(),
                uuid -> new PlayerHud(settings, text, player));
        hud.invalidate();
        hud.tick();
    }

    /** Пересобирает строки: иконки и цифры после смены настроек. */
    void reset(Player player) {
        PlayerHud hud = huds.get(player.getUniqueId());
        if (hud != null) {
            hud.invalidate();
            hud.tick();
        }
    }

    void remove(Player player) {
        PlayerHud hud = huds.remove(player.getUniqueId());
        if (hud != null) {
            hud.hide();
        }
    }

    void removeAll() {
        huds.values().forEach(PlayerHud::hide);
        huds.clear();
    }
}
