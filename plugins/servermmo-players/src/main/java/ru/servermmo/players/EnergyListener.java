package ru.servermmo.players;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ru.servermmo.core.ServerMMOCore;
import ru.servermmo.core.player.PlayerProfile;

/** Расход и восстановление энергии при быстром беге. */
final class EnergyListener implements Listener {

    private static final long REGEN_DELAY_MILLIS = 5_000L;
    private static final long REGEN_INTERVAL_MILLIS = 100L;
    private final JavaPlugin plugin;
    private final Map<UUID, Double> sprintDistance = new ConcurrentHashMap<>();
    private final Map<UUID, Long> stoppedAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRegenAt = new ConcurrentHashMap<>();
    private final BukkitTask regenTask;

    EnergyListener(JavaPlugin plugin) {
        this.plugin = plugin;
        regenTask = Bukkit.getScheduler().runTaskTimer(plugin, this::regenerate, 2L, 2L);
    }

    void stop() {
        regenTask.cancel();
        sprintDistance.clear();
        stoppedAt.clear();
        lastRegenAt.clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void preventSprintWithoutEnergy(PlayerToggleSprintEvent event) {
        if (event.isSprinting() && profile(event.getPlayer()).energy() <= 0L) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSprint(PlayerToggleSprintEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (event.isSprinting()) {
            stoppedAt.remove(uuid);
            lastRegenAt.remove(uuid);
        } else {
            long now = System.currentTimeMillis();
            stoppedAt.put(uuid, now);
            lastRegenAt.put(uuid, now + REGEN_DELAY_MILLIS);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location from = event.getFrom();
        Location to = event.getTo();
        if (event instanceof PlayerTeleportEvent || !player.isSprinting()
                || to == null || from.getWorld() != to.getWorld()) {
            return;
        }
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance <= 0.0) {
            return;
        }
        UUID uuid = player.getUniqueId();
        double accumulated = sprintDistance.getOrDefault(uuid, 0.0) + distance;
        long blocks = (long) accumulated;
        sprintDistance.put(uuid, accumulated - blocks);
        if (blocks > 0L) {
            PlayerProfile profile = profile(player);
            profile.energy(profile.energy() - blocks);
            if (profile.energy() <= 0L) {
                // Израсходовав последний пункт, теряем спринт сразу; дальнейшие
                // попытки включить его отменяются preventSprintWithoutEnergy.
                player.setSprinting(false);
                long now = System.currentTimeMillis();
                stoppedAt.put(uuid, now);
                lastRegenAt.put(uuid, now + REGEN_DELAY_MILLIS);
                return;
            }
        }
        stoppedAt.remove(uuid);
        lastRegenAt.remove(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        long now = System.currentTimeMillis();
        UUID uuid = event.getPlayer().getUniqueId();
        stoppedAt.put(uuid, now);
        lastRegenAt.put(uuid, now + REGEN_DELAY_MILLIS);
        sprintDistance.put(uuid, 0.0);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        sprintDistance.remove(uuid);
        stoppedAt.remove(uuid);
        lastRegenAt.remove(uuid);
    }

    private void regenerate() {
        long now = System.currentTimeMillis();
        ServerMMOCore core = ServerMMOCore.get();
        if (core == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isSprinting()) {
                stoppedAt.remove(player.getUniqueId());
                lastRegenAt.remove(player.getUniqueId());
                continue;
            }
            UUID uuid = player.getUniqueId();
            long stop = stoppedAt.computeIfAbsent(uuid, ignored -> now);
            if (now - stop < REGEN_DELAY_MILLIS) {
                continue;
            }
            long previous = lastRegenAt.computeIfAbsent(uuid,
                    ignored -> stop + REGEN_DELAY_MILLIS);
            if (now < previous + REGEN_INTERVAL_MILLIS) {
                continue;
            }
            long points = (now - previous) / REGEN_INTERVAL_MILLIS;
            lastRegenAt.put(uuid, previous + points * REGEN_INTERVAL_MILLIS);
            PlayerProfile profile = profile(player);
            profile.energy(profile.energy() + points);
        }
    }

    private static PlayerProfile profile(Player player) {
        return ServerMMOCore.get().players().getOrCreate(player);
    }
}
