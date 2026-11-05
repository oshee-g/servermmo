package ru.servermmo.mobs;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import ru.servermmo.core.ServerMMOCore;
import ru.servermmo.core.combat.CombatService;

/**
 * Заставляет всех мобов (включая мирных и нейтральных) замечать игрока
 * в радиусе {@code aggression.radius} блоков, поворачиваться к нему,
 * подходить и бить по кастомной системе урона.
 */
final class AggressionTask extends BukkitRunnable {

    private final ServerMMOMobs plugin;
    private final ServerMMOCore core;
    private final Map<UUID, Long> nextAttackTick = new HashMap<>();

    AggressionTask(ServerMMOMobs plugin, ServerMMOCore core) {
        this.plugin = plugin;
        this.core = core;
    }

    @Override
    public void run() {
        double radius = plugin.getConfig().getDouble("aggression.radius", 20.0D);
        double radiusSquared = radius * radius;
        boolean lookAtTarget = plugin.getConfig().getBoolean("aggression.look-at-target", true);
        boolean peacefulAggro = plugin.getConfig().getBoolean("aggression.peaceful-mobs-attack", true);
        long attackCooldown = Math.max(1L, plugin.getConfig().getLong("aggression.attack-cooldown-ticks", 20L));
        double reach = plugin.getConfig().getDouble("aggression.attack-reach", 2.6D);
        double speed = plugin.getConfig().getDouble("aggression.follow-speed", 1.0D);

        for (World world : plugin.getServer().getWorlds()) {
            for (Player player : world.getPlayers()) {
                if (player.getGameMode() == GameMode.CREATIVE
                        || player.getGameMode() == GameMode.SPECTATOR
                        || player.isDead()) {
                    continue;
                }
                for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
                    if (!(entity instanceof LivingEntity living) || entity instanceof Player) {
                        continue;
                    }
                    if (living.isDead() || !living.isValid()) {
                        continue;
                    }
                    if (living.getLocation().distanceSquared(player.getLocation()) > radiusSquared) {
                        continue;
                    }
                    if (!(living instanceof Mob mob)) {
                        continue;
                    }
                    try {
                        handleMob(mob, player, lookAtTarget, peacefulAggro, attackCooldown, reach, speed);
                    } catch (RuntimeException ex) {
                        if (core.settings().debug()) {
                            plugin.getLogger().warning("Ошибка агро для "
                                    + mob.getType() + ": " + ex.getMessage());
                        }
                    }
                }
            }
        }
    }

    private void handleMob(Mob mob, Player player, boolean lookAtTarget, boolean peacefulAggro,
                           long attackCooldown, double reach, double speed) {
        boolean hostile = isHostile(mob);
        if (!hostile && !peacefulAggro) {
            return;
        }

        // Ванильный моб-агрессор сам преследует цель.
        if (hostile) {
            LivingEntity currentTarget = mob.getTarget();
            if (currentTarget == null || currentTarget.getUniqueId().equals(player.getUniqueId())) {
                mob.setTarget(player);
            } else if (!(currentTarget instanceof Player)) {
                mob.setTarget(player);
            }
        } else {
            // Мирные и нейтральные: идём к игроку вручную.
            mob.getPathfinder().moveTo(player, speed);
        }

        if (lookAtTarget) {
            faceTarget(mob, player);
        }

        Location mobLocation = mob.getLocation();
        if (mobLocation.getWorld() != player.getWorld()) {
            return;
        }
        double distance = mobLocation.distance(player.getLocation());
        double effectiveReach = Math.max(reach, mob.getWidth() / 2.0D + 1.8D);
        if (distance > effectiveReach) {
            return;
        }

        long now = plugin.getServer().getCurrentTick();
        Long next = nextAttackTick.get(mob.getUniqueId());
        if (next != null && now < next) {
            return;
        }
        nextAttackTick.put(mob.getUniqueId(), now + attackCooldown);

        long damage = core.combat().baseDamage(mob);
        if (damage <= 0L) {
            return;
        }
        mob.swingMainHand();
        CombatService.DamageResult result = core.combat().damage(player, mob, damage);
        if (core.settings().debug()) {
            plugin.getLogger().info("[debug] " + mob.getType() + " ударил " + player.getName()
                    + " на " + damage + " (" + result + ")");
        }
    }

    /** Поворот тела по направлению к игроку и поворот головы. */
    private void faceTarget(Mob mob, Player player) {
        Location from = mob.getLocation();
        Location to = player.getLocation();
        Vector direction = to.toVector().subtract(from.toVector());
        if (direction.lengthSquared() < 1.0E-6D) {
            return;
        }
        direction.normalize();
        float yaw = (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
        float pitch = (float) Math.toDegrees(-Math.asin(Math.max(-1.0D, Math.min(1.0D, direction.getY()))));
        mob.setRotation(yaw, pitch);
        mob.lookAt(player, 30.0F, 60.0F);
    }

    private boolean isHostile(Mob mob) {
        return mob instanceof org.bukkit.entity.Monster
                || mob instanceof org.bukkit.entity.Slime
                || mob instanceof org.bukkit.entity.EnderDragon;
    }
}
