package ru.servermmo.core.listener;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import ru.servermmo.core.ServerMMOCore;
import ru.servermmo.core.combat.CombatService;
import ru.servermmo.core.player.PlayerProfile;
import ru.servermmo.core.stats.PlayerLevelStats;

/**
 * Ставит кастомные статы новым сущностям, восстанавливает профиль игрока
 * при входе и респавне.
 */
public final class EntityLifecycleListener implements Listener {

    private final ServerMMOCore plugin;

    public EntityLifecycleListener(ServerMMOCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        prepareMob(event.getEntity());
    }

    /** Мобы, которые уже были в мире до установки плагина. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof LivingEntity living && !(entity instanceof Player)) {
                prepareMob(living);
            }
        }
    }

    private void prepareMob(LivingEntity living) {
        if (!(living instanceof Mob mob) || living instanceof Player) {
            return;
        }
        CombatService combat = plugin.combat();
        if (plugin.settings().stripVanillaStats()) {
            combat.stripVanillaStats(living);
        }
        mob.clearLootTable();
        int level = combat.mobLevel(living);
        combat.setMobLevel(living, level);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerProfile profile = plugin.players().getOrCreate(player);
        profile.name(player.getName());
        PlayerLevelStats stats = plugin.playerStats().forLevel((int) Math.min(Integer.MAX_VALUE, profile.level()));
        profile.applyStats(stats, false);
        if (profile.health() <= 0L) {
            profile.health(profile.maxHealth());
        }
        profile.clampHealth();

        if (plugin.settings().stripVanillaStats()) {
            plugin.combat().stripVanillaStats(player);
        }
        // Ванильный опыт и уровень не используем - их рисует HUD из ресурспака.
        player.setLevel(0);
        player.setExp(0.0F);
        player.setTotalExperience(0);
        player.setFoodLevel(20);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        PlayerProfile profile = plugin.players().getOrCreate(player);
        profile.health(profile.maxHealth());
        profile.clampHealth();
        plugin.combat().stripVanillaStats(player);
        player.setLevel(0);
        player.setExp(0.0F);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.players().saveAll();
    }
}
