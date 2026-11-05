package ru.servermmo.core.combat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import ru.servermmo.core.ServerMMOCore;
import ru.servermmo.core.event.CustomPlayerDamageEvent;
import ru.servermmo.core.player.PlayerProfile;
import ru.servermmo.core.stats.LevelStats;

/**
 * Кастомная боевая система.
 *
 * <p>Здоровье каждого моба и игрока хранится отдельно от ванильного и считается
 * в {@code long}. Ванильные атрибуты (максимальное здоровье, урон, броня)
 * обнуляются, чтобы не мешать расчётам.</p>
 */
public final class CombatService {

    /** Результат нанесения урона. */
    public enum DamageResult {
        /** Урон нанесён, цель жива. */
        DAMAGED,
        /** Цель погибла. */
        KILLED,
        /** Урон проигнорирован (нулевой либо цель уже мертва). */
        IGNORED
    }

    private final ServerMMOCore plugin;
    private final NamespacedKey healthKey;
    private final NamespacedKey levelKey;
    private final NamespacedKey customKey;
    private final Map<UUID, Long> mobHealth = new ConcurrentHashMap<>();

    public CombatService(ServerMMOCore plugin) {
        this.plugin = plugin;
        this.healthKey = new NamespacedKey(plugin, "combat-health");
        this.levelKey = new NamespacedKey(plugin, "mob-level");
        this.customKey = new NamespacedKey(plugin, "custom-mob");
    }

    // ------------------------------------------------------------------
    // Кастомные и ванильные мобы
    // ------------------------------------------------------------------

    /**
     * Кастомный ли моб: только такие живут по кастомной системе (здоровье из
     * таблицы, урон, имя с уровнем). Обычные, заспавненные игрой мобы
     * остаются полностью ванильными.
     */
    public boolean isCustom(LivingEntity entity) {
        if (entity instanceof Player) {
            return true;
        }
        Byte flag = entity.getPersistentDataContainer().get(customKey, PersistentDataType.BYTE);
        return flag != null && flag == (byte) 1;
    }

    /** Помечает моба кастомным (вызывается только командой спавна). */
    public void markCustom(LivingEntity entity) {
        entity.getPersistentDataContainer().set(customKey, PersistentDataType.BYTE, (byte) 1);
    }

    // ------------------------------------------------------------------
    // Здоровье
    // ------------------------------------------------------------------

    /** Максимальное кастомное здоровье сущности. */
    public long maxHealth(LivingEntity entity) {
        if (entity instanceof Player player) {
            PlayerProfile profile = plugin.players().get(player.getUniqueId());
            return profile != null ? profile.maxHealth() : 10L;
        }
        LevelStats stats = levelStats(entity);
        return stats != null ? stats.health() : 1L;
    }

    /** Текущее кастомное здоровье сущности. */
    public long health(LivingEntity entity) {
        if (entity instanceof Player player) {
            PlayerProfile profile = plugin.players().get(player.getUniqueId());
            return profile != null ? profile.health() : 0L;
        }
        UUID uuid = entity.getUniqueId();
        Long cached = mobHealth.get(uuid);
        if (cached != null) {
            return cached;
        }
        Long stored = entity.getPersistentDataContainer().get(healthKey, PersistentDataType.LONG);
        long value = stored != null ? stored : maxHealth(entity);
        mobHealth.put(uuid, value);
        return value;
    }

    /** Сохраняет текущее здоровье сущности. */
    public void setHealth(LivingEntity entity, long health) {
        long max = maxHealth(entity);
        long clamped = Math.max(0L, Math.min(health, max));
        if (entity instanceof Player player) {
            PlayerProfile profile = plugin.players().get(player.getUniqueId());
            if (profile != null) {
                profile.health(clamped);
            }
            return;
        }
        UUID uuid = entity.getUniqueId();
        mobHealth.put(uuid, clamped);
        if (!entity.isDead() && entity.isValid()) {
            entity.getPersistentDataContainer().set(healthKey, PersistentDataType.LONG, clamped);
        }
    }

    /** Сбрасывает кэш здоровья моба (например, после его удаления). */
    public void forget(UUID uuid) {
        mobHealth.remove(uuid);
    }

    // ------------------------------------------------------------------
    // Базовый урон
    // ------------------------------------------------------------------

    /** Базовый урон сущности по кастомной системе. */
    public long baseDamage(LivingEntity entity) {
        if (entity instanceof Player player) {
            PlayerProfile profile = plugin.players().get(player.getUniqueId());
            return profile != null ? profile.damage() : 1L;
        }
        LevelStats stats = levelStats(entity);
        return stats != null ? stats.damage() : 0L;
    }

    /** Опыт, который даёт моб за убийство. */
    public long experienceReward(LivingEntity entity) {
        LevelStats stats = levelStats(entity);
        return stats != null ? stats.xp() : 0L;
    }

    private LevelStats levelStats(LivingEntity entity) {
        // У кастомного моба уровень задан при спавне и важнее типа: курица
        // 40 уровня должна быть сильнее курицы 2 уровня.
        Integer level = entity.getPersistentDataContainer().get(levelKey, PersistentDataType.INTEGER);
        if (level != null) {
            LevelStats byLevel = plugin.levels().byLevel(level);
            if (byLevel != null) {
                return byLevel;
            }
        }
        return plugin.levels().byType(entity.getType());
    }

    // ------------------------------------------------------------------
    // Урон и смерть
    // ------------------------------------------------------------------

    /**
     * Наносит кастомный урон.
     *
     * @param target цель
     * @param source источник (может быть {@code null} - падение, лава и т.п.)
     * @param amount количество урона в кастомных единицах
     * @return результат
     */
    public DamageResult damage(LivingEntity target, LivingEntity source, long amount) {
        if (target == null || target.isDead() || !target.isValid() || amount <= 0L) {
            return DamageResult.IGNORED;
        }
        long current = health(target);
        if (current <= 0L) {
            return DamageResult.IGNORED;
        }
        long remaining = current - amount;
        if (remaining > 0L) {
            setHealth(target, remaining);
            if (target instanceof Player victim) {
                plugin.getServer().getPluginManager().callEvent(
                        new CustomPlayerDamageEvent(victim, amount, remaining));
            }
            playHurt(target);
            if (plugin.settings().debug()) {
                plugin.getLogger().info("[debug] " + name(target) + " получил " + amount
                        + " урона, осталось " + remaining + "/" + maxHealth(target));
            }
            return DamageResult.DAMAGED;
        }
        setHealth(target, 0L);
        if (target instanceof Player victim) {
            plugin.getServer().getPluginManager().callEvent(
                    new CustomPlayerDamageEvent(victim, amount, 0L));
        }
        kill(target, source);
        return DamageResult.KILLED;
    }

    /** Убивает сущность по кастомным правилам. */
    public void kill(LivingEntity target, LivingEntity source) {
        if (target instanceof Player player) {
            killPlayer(player);
            return;
        }
        playDeathSound(target);
        Player killer = source instanceof Player p ? p : null;
        if (killer != null) {
            long xp = experienceReward(target);
            if (xp > 0L) {
                plugin.awardExperience(killer, xp);
            }
        }
        forget(target.getUniqueId());
        // Без анимации смерти и без дропа: сущность просто исчезает.
        target.remove();
    }

    private void killPlayer(Player player) {
        PlayerProfile profile = plugin.players().get(player.getUniqueId());
        if (profile != null) {
            profile.health(0L);
        }
        player.setHealth(0.0D);
    }

    /** Покраснение и звук урона, если это разрешено в конфиге. */
    public void playHurt(LivingEntity entity) {
        if (!plugin.settings().hurtAnimation()) {
            return;
        }
        try {
            entity.playHurtAnimation(0.0F);
        } catch (RuntimeException ex) {
            if (plugin.settings().debug()) {
                plugin.getLogger().warning("playHurtAnimation упал для " + name(entity) + ": " + ex.getMessage());
            }
        }
        Sound hurt = entity.getHurtSound();
        if (hurt != null) {
            Location location = entity.getLocation();
            entity.getWorld().playSound(location, hurt, 1.0F, 1.0F);
        }
    }

    private void playDeathSound(LivingEntity entity) {
        if (!plugin.settings().mobDeathSound()) {
            return;
        }
        Sound death = entity.getDeathSound();
        if (death != null) {
            Location location = entity.getLocation();
            entity.getWorld().playSound(location, death, 1.0F, 1.0F);
        }
    }

    // ------------------------------------------------------------------
    // Ванильные статы
    // ------------------------------------------------------------------

    /**
     * Убирает ванильные атрибуты, чтобы здоровье и урон считались только
     * кастомной системой.
     *
     * <p>Игроку ванильный урон не обнуляется, а выставляется равным его
     * кастомному урону: так он обычным ударом убивает ванильных мобов.</p>
     */
    public void stripVanillaStats(LivingEntity entity) {
        AttributeInstance maxHealth = entity.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(1.0D);
        }
        AttributeInstance attackDamage = entity.getAttribute(Attribute.ATTACK_DAMAGE);
        if (attackDamage != null) {
            attackDamage.setBaseValue(entity instanceof Player ? (double) baseDamage(entity) : 0.0D);
        }
        AttributeInstance armor = entity.getAttribute(Attribute.ARMOR);
        if (armor != null) {
            armor.setBaseValue(0.0D);
        }
        AttributeInstance armorToughness = entity.getAttribute(Attribute.ARMOR_TOUGHNESS);
        if (armorToughness != null) {
            armorToughness.setBaseValue(0.0D);
        }
        try {
            entity.setHealth(1.0D);
        } catch (IllegalArgumentException ignored) {
            // у сущности нет ванильного здоровья - не страшно
        }
    }

    /** Проверяет, что у сущности стоит ванильный «заглушечный» максимум здоровья. */
    public boolean hasVanillaStatsStripped(LivingEntity entity) {
        AttributeInstance maxHealth = entity.getAttribute(Attribute.MAX_HEALTH);
        return maxHealth != null && Math.abs(maxHealth.getBaseValue() - 1.0D) < 1.0E-6D;
    }

    // ------------------------------------------------------------------
    // Уровень моба
    // ------------------------------------------------------------------

    /** Назначает мобу уровень (сохраняется в данных сущности). */
    public void setMobLevel(LivingEntity entity, int level) {
        entity.getPersistentDataContainer().set(levelKey, PersistentDataType.INTEGER, level);
        LevelStats stats = plugin.levels().byLevel(level);
        if (stats != null) {
            mobHealth.put(entity.getUniqueId(), stats.health());
            entity.getPersistentDataContainer().set(healthKey, PersistentDataType.LONG, stats.health());
        }
    }

    /** Уровень моба: сохранённый при спавне либо по типу из таблицы. */
    public int mobLevel(LivingEntity entity) {
        Integer stored = entity.getPersistentDataContainer().get(levelKey, PersistentDataType.INTEGER);
        if (stored != null) {
            return stored;
        }
        LevelStats byType = plugin.levels().byType(entity.getType());
        return byType != null ? byType.level() : 1;
    }

    private String name(LivingEntity entity) {
        return entity instanceof Mob mob && mob.getCustomName() != null
                ? mob.getCustomName()
                : entity.getType().name();
    }

    /** Возвращает экземпляр плагина (для удобства слушателей). */
    public JavaPlugin plugin() {
        return plugin;
    }
}
