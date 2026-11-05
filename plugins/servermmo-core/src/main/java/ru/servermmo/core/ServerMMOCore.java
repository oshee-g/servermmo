package ru.servermmo.core;

import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import ru.servermmo.core.combat.CombatService;
import ru.servermmo.core.config.CoreConfig;
import ru.servermmo.core.event.PlayerLevelUpEvent;
import ru.servermmo.core.listener.CombatListener;
import ru.servermmo.core.listener.EntityLifecycleListener;
import ru.servermmo.core.player.PlayerProfile;
import ru.servermmo.core.player.PlayerStore;
import ru.servermmo.core.stats.LevelTable;
import ru.servermmo.core.stats.PlayerLevelStats;
import ru.servermmo.core.stats.PlayerStatTable;

/**
 * Ядро ServerMMO: таблицы статов, кастомное здоровье, урон и опыт.
 *
 * <p>Другие плагины набора обращаются к ядру через {@link #get()}.</p>
 */
public final class ServerMMOCore extends JavaPlugin {

    private static ServerMMOCore instance;

    private CoreConfig settings = null;
    private LevelTable levels = LevelTable.empty();
    private PlayerStatTable playerStats = PlayerStatTable.empty();
    private PlayerStore players = null;
    private CombatService combat = null;
    private EntityLifecycleListener lifecycle = null;

    /** Ядро либо {@code null}, если плагин ещё не включён. */
    public static ServerMMOCore get() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        settings = CoreConfig.load(this);

        levels = LevelTable.load(this, "stats.yml");
        playerStats = PlayerStatTable.load(this, "player-stats.yml");
        players = new PlayerStore(this);
        players.loadAll();
        combat = new CombatService(this);

        lifecycle = new EntityLifecycleListener(this);
        Bukkit.getPluginManager().registerEvents(new CombatListener(this), this);
        Bukkit.getPluginManager().registerEvents(lifecycle, this);

        long interval = Math.max(20L, settings.saveIntervalSeconds() * 20L);
        Bukkit.getScheduler().runTaskTimer(this, () -> players.saveAll(), interval, interval);

        getLogger().info("ServerMMOCore включён: уровней " + levels.maxLevel()
                + ", статов игрока " + playerStats.maxLevel());
    }

    @Override
    public void onDisable() {
        if (players != null) {
            players.saveAll();
        }
        instance = null;
    }

    // ------------------------------------------------------------------
    // Доступ к подсистемам
    // ------------------------------------------------------------------

    public CoreConfig settings() {
        return Objects.requireNonNull(settings, "Ядро не инициализировано");
    }

    public LevelTable levels() {
        return levels;
    }

    public PlayerStatTable playerStats() {
        return playerStats;
    }

    public PlayerStore players() {
        return Objects.requireNonNull(players, "Ядро не инициализировано");
    }

    public CombatService combat() {
        return Objects.requireNonNull(combat, "Ядро не инициализировано");
    }

    /** Готовит кастомного моба: снимает ванильные статы, ставит уровень. */
    public void prepareMob(LivingEntity entity) {
        if (lifecycle != null && entity != null) {
            lifecycle.prepareMob(entity);
        }
    }

    // ------------------------------------------------------------------
    // Опыт и уровни
    // ------------------------------------------------------------------

    /**
     * Начисляет игроку опыт. При достижении порога уровень растёт,
     * статы пересчитываются, вызывается {@link PlayerLevelUpEvent}.
     */
    public void awardExperience(Player player, long amount) {
        if (player == null || amount <= 0L) {
            return;
        }
        PlayerProfile profile = players.getOrCreate(player);
        profile.xp(profile.xp() + amount);

        int maxLevel = playerStats.maxLevel();
        boolean leveled = false;
        while (profile.level() < maxLevel) {
            PlayerLevelStats current = playerStats.forLevel((int) profile.level());
            if (current == null) {
                break;
            }
            long need = current.xpToNext();
            if (need <= 0L || profile.xp() < need) {
                break;
            }
            profile.xp(profile.xp() - need);
            long oldLevel = profile.level();
            PlayerLevelStats next = playerStats.forLevel((int) (oldLevel + 1L));
            if (next == null || next.level() <= oldLevel) {
                break;
            }
            profile.applyStats(next, true);
            if (settings.stripVanillaStats()) {
                combat.stripVanillaStats(player);
            }
            leveled = true;
            PlayerLevelUpEvent event = new PlayerLevelUpEvent(player, oldLevel, profile.level());
            Bukkit.getPluginManager().callEvent(event);
        }

        profile.clampHealth();
        if (leveled && settings.debug()) {
            getLogger().info("[debug] " + player.getName() + " теперь уровня " + profile.level());
        }
    }

    /** Полный сброс профиля игрока к 1 уровню. */
    public void resetProfile(Player player) {
        PlayerProfile profile = players.getOrCreate(player);
        PlayerLevelStats stats = playerStats.forLevel(1);
        profile.xp(0L);
        if (stats != null) {
            profile.applyStats(stats, true);
        }
        profile.clampHealth();
        if (settings.stripVanillaStats()) {
            combat.stripVanillaStats(player);
        }
    }
}
