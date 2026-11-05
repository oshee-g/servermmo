package ru.servermmo.hud;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/** Пересоздание HUD при смене мира и входе. */
public final class HudListener implements Listener {

    private final ServerMMOHud plugin;
    private final HudManager manager;
    private final HudSettings settings;

    HudListener(ServerMMOHud plugin, HudManager manager, HudSettings settings) {
        this.plugin = plugin;
        this.manager = manager;
        this.settings = settings;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (settings.resourcePackEnabled()) {
            event.getPlayer().setResourcePack(settings.resourcePackUrl());
        }
        manager.reset(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        manager.reset(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        manager.reset(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        manager.remove(event.getPlayer());
    }
}
