package ru.servermmo.hud;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
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

    /**
     * Пока пак не загружен клиентом, HUD полностью скрыт: босс-бары без пака —
     * белые полосы, глифы — пустые квадратики, а строка хотбара — те же
     * квадратики над хотбаром. Панель и хотбар появляются только после
     * SUCCESSFULLY_LOADED и пропадают при отказе/сбое загрузки.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        boolean loaded = event.getStatus() == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED;
        // Статус пишем в лог всегда: это единственный способ понять, почему
        // HUD не виден — скрыт гейтом (пак не загружен) или уехал за край
        // экрана из-за чужого масштаба интерфейса.
        manager.plugin().getLogger().info("Ресурспак " + event.getPlayer().getName()
                + ": " + event.getStatus() + (loaded ? " — HUD показан" : " — HUD скрыт"));
        manager.packLoaded(event.getPlayer(), loaded);
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
