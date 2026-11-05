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
    private final Map<UUID, PlayerHud> huds = new HashMap<>();

    HudManager(ServerMMOHud plugin, HudSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    @Override
    public void run() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            PlayerHud hud = huds.computeIfAbsent(player.getUniqueId(),
                    uuid -> new PlayerHud(plugin, settings, player));
            try {
                hud.tick();
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("HUD " + player.getName() + " упал: " + ex.getMessage());
                hud.reset();
            }
        }
        huds.entrySet().removeIf(entry -> {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                entry.getValue().remove();
                return true;
            }
            return false;
        });
    }

    void reset(Player player) {
        PlayerHud hud = huds.get(player.getUniqueId());
        if (hud != null) {
            hud.reset();
        }
    }

    void remove(Player player) {
        PlayerHud hud = huds.remove(player.getUniqueId());
        if (hud != null) {
            hud.remove();
        }
    }

    void removeAll() {
        huds.values().forEach(PlayerHud::remove);
        huds.clear();
    }
}
