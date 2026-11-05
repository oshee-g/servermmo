package ru.servermmo.players;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import java.time.Duration;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import ru.servermmo.core.event.PlayerLevelUpEvent;
import ru.servermmo.core.player.PlayerProfile;

/** Оповещение о новом уровне. */
public final class LevelUpListener implements Listener {

    private final ServerMMOPlayers plugin;

    LevelUpListener(ServerMMOPlayers plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onLevelUp(PlayerLevelUpEvent event) {
        Player player = event.player();
        PlayerProfile profile = ru.servermmo.core.ServerMMOCore.get().players().get(player.getUniqueId());
        long maxHealth = profile != null ? profile.maxHealth() : 0L;
        long damage = profile != null ? profile.damage() : 0L;

        Component subtitle = Component.text("HP " + maxHealth + "  |  урон " + damage, NamedTextColor.GRAY);
        player.showTitle(Title.title(
                Component.text("Уровень " + event.newLevel(), NamedTextColor.GREEN),
                subtitle,
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1500), Duration.ofMillis(400))));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7F, 1.0F);
    }
}
