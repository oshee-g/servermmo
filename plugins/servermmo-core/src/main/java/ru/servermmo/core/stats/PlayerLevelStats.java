package ru.servermmo.core.stats;

/** Статы игрока на конкретном уровне. */
public final class PlayerLevelStats {

    private final int level;
    private final String name;
    private final long health;
    private final long damage;
    private final long xpToNext;

    public PlayerLevelStats(int level, String name, long health, long damage, long xpToNext) {
        this.level = level;
        this.name = name;
        this.health = health;
        this.damage = damage;
        this.xpToNext = xpToNext;
    }

    public int level() {
        return level;
    }

    public String name() {
        return name;
    }

    public long health() {
        return health;
    }

    public long damage() {
        return damage;
    }

    public long xpToNext() {
        return xpToNext;
    }

    @Override
    public String toString() {
        return "PlayerLevelStats{level=" + level + ", health=" + health
                + ", damage=" + damage + ", xpToNext=" + xpToNext + '}';
    }
}
