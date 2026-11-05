package ru.servermmo.core.listener;

import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.projectiles.ProjectileSource;
import ru.servermmo.core.ServerMMOCore;
import ru.servermmo.core.combat.CombatService;

/**
 * Перехватывает ванильный урон и переводит его в кастомную систему.
 *
 * <p>Кастомная система работает только для игроков и кастомных мобов (тех,
 * что созданы командой спавна). Обычные мобы остаются полностью ванильными:
 * их удары, здоровье, дроп и смерть не трогаются.</p>
 *
 * <p>Урон по игроку всегда отменяется: здоровье списывает только
 * {@link CombatService}. Благодаря этому игрок с «техническим» 1 HP не умирает
 * по-настоящему, а покраснение и звук остаются ванильными.</p>
 */
public final class CombatListener implements Listener {

    private final ServerMMOCore plugin;

    public CombatListener(ServerMMOCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof LivingEntity target)) {
            return;
        }
        // Ванильных мобов не трогаем совсем: за них играет сама игра.
        if (target instanceof Mob && !plugin.combat().isCustom(target)) {
            return;
        }
        // Стойки для брони, картины и прочее остаются ванильными.
        if (!(target instanceof Player) && !(target instanceof Mob)) {
            return;
        }
        // Ванильный урон не должен проходить никогда.
        event.setCancelled(true);

        if (target.isDead() || target.getHealth() <= 0.0D || !target.isValid()) {
            return;
        }
        if (target instanceof Player player
                && (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR)) {
            return;
        }

        LivingEntity source = null;
        long amount;

        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Entity damager = byEntity.getDamager();
            if (damager instanceof LivingEntity living) {
                source = living;
                amount = damageOf(living, event.getDamage());
            } else if (damager instanceof Projectile projectile) {
                ProjectileSource shooter = projectile.getShooter();
                if (shooter instanceof LivingEntity living) {
                    source = living;
                    amount = damageOf(living, event.getDamage());
                } else {
                    amount = scaleEnvironment(event.getDamage());
                }
            } else {
                amount = scaleEnvironment(event.getDamage());
            }
        } else {
            amount = scaleEnvironment(event.getDamage());
        }

        if (amount <= 0L) {
            return;
        }
        // Не бьём себя же (стрела, вернувшаяся к стрелку).
        if (source != null && source.getUniqueId().equals(target.getUniqueId())) {
            return;
        }
        plugin.combat().damage(target, source, amount);
    }

    /**
     * Урон источника. Игрок и кастомный моб бьют по таблице статов, обычный
     * моб — своим ванильным уроном.
     */
    private long damageOf(LivingEntity source, double vanillaDamage) {
        if (source instanceof Mob && !plugin.combat().isCustom(source)) {
            return Math.max(1L, Math.round(vanillaDamage));
        }
        long custom = plugin.combat().baseDamage(source);
        return custom > 0L ? custom : scaleEnvironment(vanillaDamage);
    }

    /** Ванильный опыт и дроп отключаются только у кастомных мобов. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Player || !plugin.combat().isCustom(event.getEntity())) {
            return;
        }
        if (!event.getDrops().isEmpty()) {
            event.getDrops().clear();
        }
        event.setDroppedExp(0);
    }

    private long scaleEnvironment(double vanillaDamage) {
        if (vanillaDamage <= 0.0D) {
            return 0L;
        }
        double scaled = vanillaDamage * plugin.settings().environmentDamageScale();
        long result = Math.round(scaled);
        return Math.max(1L, result);
    }
}
