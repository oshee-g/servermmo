package ru.servermmo.core.stats;

/**
 * Статы одного уровня из таблицы мобов.
 *
 * <p>Все числовые значения хранятся в {@code long}: таблица доходит до сотен
 * миллионов, а в будущем числа могут вырасти выше 2 147 483 647.</p>
 */
public final class LevelStats {

    private final int level;
    private final String mobName;
    private final String entityTypeId;
    private final long health;
    private final long damage;
    private final long xp;
    private final long xpToNext;

    public LevelStats(int level, String mobName, String entityTypeId,
                      long health, long damage, long xp, long xpToNext) {
        this.level = level;
        this.mobName = mobName;
        this.entityTypeId = entityTypeId;
        this.health = health;
        this.damage = damage;
        this.xp = xp;
        this.xpToNext = xpToNext;
    }

    /** Уровень (1..90). */
    public int level() {
        return level;
    }

    /** Русское название моба из таблицы. */
    public String mobName() {
        return mobName;
    }

    /** Идентификатор типа сущности Paper, например {@code SALMON}. */
    public String entityTypeId() {
        return entityTypeId;
    }

    /** Здоровье моба на этом уровне. */
    public long health() {
        return health;
    }

    /** Базовый урон моба на этом уровне. */
    public long damage() {
        return damage;
    }

    /** Сколько опыта получает игрок за убийство такого моба. */
    public long xp() {
        return xp;
    }

    /** Сколько опыта нужно игроку, чтобы перейти с этого уровня на следующий. */
    public long xpToNext() {
        return xpToNext;
    }

    @Override
    public String toString() {
        return "LevelStats{level=" + level + ", mob=" + mobName + ", type=" + entityTypeId
                + ", health=" + health + ", damage=" + damage + ", xp=" + xp
                + ", xpToNext=" + xpToNext + '}';
    }
}
