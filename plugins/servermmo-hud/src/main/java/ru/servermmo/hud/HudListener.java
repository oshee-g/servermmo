package ru.servermmo.hud;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import ru.servermmo.core.event.CustomPlayerDamageEvent;

/** Показ и снятие панели при входе и выходе игрока. */
public final class HudListener implements Listener {

    private static final long DOUBLE_SHIFT_WINDOW_MILLIS = 500L;
    private static final long MIN_SHIFT_TAP_GAP_MILLIS = 70L;
    private final HudManager manager;
    private final Map<UUID, Long> lastSneakPress = new HashMap<>();

    HudListener(HudManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        manager.show(event.getPlayer());
    }

    /** Кастомный урон по игроку — индикатор «−N♥» в хотбаре. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCustomDamage(CustomPlayerDamageEvent event) {
        manager.recordDamage(event.player(), event.amount());
    }

    /** Двойное нажатие Shift затемняет центральную иконку на 30 секунд. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSneakToggle(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) {
            return;
        }
        UUID uuid = event.getPlayer().getUniqueId();
        long now = System.currentTimeMillis();
        Long previous = lastSneakPress.put(uuid, now);
        if (previous != null && now - previous >= MIN_SHIFT_TAP_GAP_MILLIS
                && now - previous <= DOUBLE_SHIFT_WINDOW_MILLIS) {
            lastSneakPress.remove(uuid);
            manager.activateAggression(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lastSneakPress.remove(event.getPlayer().getUniqueId());
        manager.remove(event.getPlayer());
    }
}
