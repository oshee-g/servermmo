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
    private final HudStore store;
    private final Map<UUID, PlayerHud> huds = new HashMap<>();

    HudManager(ServerMMOHud plugin, HudSettings settings, HudText text, HudStore store) {
        this.plugin = plugin;
        this.settings = settings;
        this.text = text;
        this.store = store;
    }

    @Override
    public void run() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            PlayerHud hud = huds.computeIfAbsent(player.getUniqueId(),
                    uuid -> new PlayerHud(settings, text, store, player));
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
                uuid -> new PlayerHud(settings, text, store, player));
        hud.invalidate();
        hud.tick();
    }

    /** Двойной тап Shift активирует 30-секундный откат способности агрессии. */
    void activateAggression(Player player) {
        PlayerHud hud = huds.computeIfAbsent(player.getUniqueId(),
                uuid -> new PlayerHud(settings, text, store, player));
        hud.activateAggression();
    }

    /** Игрок получил кастомный урон: вспыхнуть индикатору «−N♥» в хотбаре. */
    void recordDamage(Player player, long amount) {
        PlayerHud hud = huds.computeIfAbsent(player.getUniqueId(),
                uuid -> new PlayerHud(settings, text, store, player));
        hud.recordDamage(amount);
    }

    /** Пересобирает строки: иконки и цифры после смены настроек. */
    void reset(Player player) {
        PlayerHud hud = huds.get(player.getUniqueId());
        if (hud != null) {
            hud.invalidate();
            hud.tick();
        }
    }

    /** Состояние HUD игрока — для /hud debug. */
    String debugLine(Player player) {
        PlayerHud hud = huds.get(player.getUniqueId());
        return hud == null ? "HUD игрока ещё не создан" : hud.debugLine();
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
