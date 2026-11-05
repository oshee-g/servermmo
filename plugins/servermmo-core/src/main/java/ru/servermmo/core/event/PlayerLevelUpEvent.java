package ru.servermmo.core.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Игрок получил новый уровень. */
public final class PlayerLevelUpEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final long oldLevel;
    private final long newLevel;

    public PlayerLevelUpEvent(Player player, long oldLevel, long newLevel) {
        this.player = player;
        this.oldLevel = oldLevel;
        this.newLevel = newLevel;
    }

    public Player player() {
        return player;
    }

    public long oldLevel() {
        return oldLevel;
    }

    public long newLevel() {
        return newLevel;
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
