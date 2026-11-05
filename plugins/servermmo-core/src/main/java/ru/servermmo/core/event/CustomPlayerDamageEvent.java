package ru.servermmo.core.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Игрок получил урон по кастомной системе.
 *
 * <p>Срабатывает после списания здоровья, в том числе при смертельном ударе
 * ({@code remainingHealth == 0}). HUD слушает это событие, чтобы показать в
 * хотбаре «−25♥»: ванильное {@code EntityDamageEvent} для этого не годится —
 * оно отменяется, а количество кастомного урона из него не видно.</p>
 */
public final class CustomPlayerDamageEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final long amount;
    private final long remainingHealth;

    public CustomPlayerDamageEvent(Player player, long amount, long remainingHealth) {
        this.player = player;
        this.amount = amount;
        this.remainingHealth = remainingHealth;
    }

    public Player player() {
        return player;
    }

    /** Сколько кастомного урона получил игрок этим ударом. */
    public long amount() {
        return amount;
    }

    /** Кастомное здоровье после удара (0 — удар был смертельным). */
    public long remainingHealth() {
        return remainingHealth;
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
