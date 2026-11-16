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
    /** Когда игрока впервые увидели без статуса пака, мс. */
    private final Map<UUID, Long> awaitingStatus = new HashMap<>();

    HudManager(ServerMMOHud plugin, HudSettings settings, HudText text, HudStore store) {
        this.plugin = plugin;
        this.settings = settings;
        this.text = text;
        this.store = store;
    }

    ServerMMOHud plugin() {
        return plugin;
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
                awaitingStatus.remove(entry.getKey());
                return true;
            }
            return false;
        });
        warnWithoutStatus();
    }

    /**
     * Предупредить про игроков, от которых давно нет статуса пака.
     *
     * <p>Без статуса HUD скрыт гейтом, и со стороны это выглядит как «панель
     * не работает». Причина обычно в том, что клиент вообще не скачивал пак
     * (старая ссылка, кэш) или плагин не получает событие. Раз в минуту
     * напоминаем об этом в лог, чтобы диагноз был по одной строке.</p>
     */
    private void warnWithoutStatus() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, PlayerHud> entry : huds.entrySet()) {
            PlayerHud hud = entry.getValue();
            if (hud.packLoaded() || hud.statusSeen()) {
                awaitingStatus.remove(entry.getKey());
                continue;
            }
            Long since = awaitingStatus.get(entry.getKey());
            if (since == null) {
                awaitingStatus.put(entry.getKey(), now);
                continue;
            }
            if (now - since < 60_000L) {
                continue;
            }
            awaitingStatus.put(entry.getKey(), now);
            Player player = plugin.getServer().getPlayer(entry.getKey());
            plugin.getLogger().warning("HUD скрыт у "
                    + (player == null ? entry.getKey() : player.getName())
                    + ": клиент не прислал статус ресурспака. Почти всегда это значит, "
                    + "что в списке серверов у игрока отключены серверные пакеты "
                    + "ресурсов: Изменить сервер -> «Пакеты ресурсов сервера» -> "
                    + "«Включены», либо удалить сервер из списка и добавить заново. "
                    + "Если пак включён, проверьте, что клиент вообще качает его по "
                    + "ссылке из server.properties (resource-pack); принудительно "
                    + "спросить игрока помогает require-resource-pack=true.");
        }
    }

    /** Показывает панель игроку (вход в игру). */
    void show(Player player) {
        PlayerHud hud = huds.computeIfAbsent(player.getUniqueId(),
                uuid -> new PlayerHud(settings, text, store, player));
        hud.invalidate();
        hud.tick();
    }

    /** Статус серверного ресурспака: загружен — HUD виден, нет — скрыт весь. */
    void packLoaded(Player player, boolean loaded) {
        PlayerHud hud = huds.computeIfAbsent(player.getUniqueId(),
                uuid -> new PlayerHud(settings, text, store, player));
        awaitingStatus.remove(player.getUniqueId());
        hud.packLoaded(loaded);
        if (loaded) {
            hud.tick();
        }
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
        awaitingStatus.remove(player.getUniqueId());
        PlayerHud hud = huds.remove(player.getUniqueId());
        if (hud != null) {
            hud.hide();
        }
    }

    void removeAll() {
        huds.values().forEach(PlayerHud::hide);
        huds.clear();
        awaitingStatus.clear();
    }
}
