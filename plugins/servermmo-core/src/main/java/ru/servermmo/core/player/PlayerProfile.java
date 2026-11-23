package ru.servermmo.core.player;

import java.util.UUID;
import ru.servermmo.core.stats.PlayerLevelStats;

/**
 * Профиль игрока: уровень, опыт и текущее кастомное здоровье.
 * Все числа - {@code long}.
 */
public final class PlayerProfile {

    private final UUID uuid;
    private String name;
    private long level;
    private long xp;
    private long health;
    private long maxHealth;
    private long energy = 100L;
    private long maxEnergy = 100L;
    private long damage;
    private long xpToNext;
    private boolean dirty = true;

    public PlayerProfile(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
        this.level = 1L;
        this.xp = 0L;
        this.maxHealth = 10L;
        this.health = 10L;
        this.damage = 1L;
        this.xpToNext = 200L;
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public void name(String name) {
        this.name = name;
        this.dirty = true;
    }

    public long level() {
        return level;
    }

    public void level(long level) {
        this.level = level;
        this.dirty = true;
    }

    public long xp() {
        return xp;
    }

    public void xp(long xp) {
        this.xp = xp;
        this.dirty = true;
    }

    public long health() {
        return health;
    }

    public void health(long health) {
        this.health = health;
        this.dirty = true;
    }

    public long maxHealth() {
        return maxHealth;
    }

    public long energy() {
        return energy;
    }

    public long maxEnergy() {
        return maxEnergy;
    }

    public void energy(long energy) {
        long clamped = Math.max(0L, Math.min(maxEnergy, energy));
        if (this.energy != clamped) {
            this.energy = clamped;
            this.dirty = true;
        }
    }

    public long damage() {
        return damage;
    }

    public long xpToNext() {
        return xpToNext;
    }

    public boolean dirty() {
        return dirty;
    }

    public void clean() {
        this.dirty = false;
    }

    /** Применяет статы уровня из таблицы, сохраняя процент здоровья. */
    public void applyStats(PlayerLevelStats stats, boolean refill) {
        if (stats == null) {
            return;
        }
        long previousMax = this.maxHealth;
        this.level = stats.level();
        this.maxHealth = stats.health();
        this.damage = stats.damage();
        this.xpToNext = stats.xpToNext();
        if (refill || previousMax <= 0L) {
            this.health = this.maxHealth;
        } else if (this.health > this.maxHealth) {
            this.health = this.maxHealth;
        }
        this.dirty = true;
    }

    /** Ограничивает текущее здоровье максимумом. */
    public void clampHealth() {
        if (this.health > this.maxHealth) {
            this.health = this.maxHealth;
        }
        if (this.health <= 0L) {
            this.health = 0L;
        }
    }

    public double healthPercent() {
        return this.maxHealth <= 0L ? 0.0D : Math.min(1.0D, (double) this.health / (double) this.maxHealth);
    }
}
