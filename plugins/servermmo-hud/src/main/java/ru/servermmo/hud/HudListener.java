package ru.servermmo.hud;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import ru.servermmo.core.event.CustomPlayerDamageEvent;

/** Показ и снятие панели при входе и выходе игрока. */
public final class HudListener implements Listener {

    private final HudManager manager;

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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        manager.remove(event.getPlayer());
    }
}
