package ru.servermmo.players;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.servermmo.core.ServerMMOCore;
import ru.servermmo.core.player.PlayerProfile;

/** {@code /stats} - просмотр кастомных статов игрока. */
public final class StatsCommand implements CommandExecutor, TabCompleter {

    private final ServerMMOPlayers plugin;

    StatsCommand(ServerMMOPlayers plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("Укажите игрока: /stats <ник>", NamedTextColor.RED));
                return true;
            }
            printStats(sender, player);
            return true;
        }

        String first = args[0].toLowerCase(Locale.ROOT);
        if (first.equals("reset") || first.equals("сброс")) {
            if (!sender.hasPermission("servermmo.stats.reset")) {
                sender.sendMessage(Component.text("Недостаточно прав", NamedTextColor.RED));
                return true;
            }
            Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1])
                    : (sender instanceof Player p ? p : null);
            if (target == null) {
                sender.sendMessage(Component.text("Игрок не найден", NamedTextColor.RED));
                return true;
            }
            ServerMMOCore.get().resetProfile(target);
            sender.sendMessage(Component.text("Статы " + target.getName() + " сброшены к 1 уровню",
                    NamedTextColor.YELLOW));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(Component.text("Игрок не найден", NamedTextColor.RED));
            return true;
        }
        printStats(sender, target);
        return true;
    }

    private void printStats(CommandSender sender, Player target) {
        PlayerProfile profile = ServerMMOCore.get().players().getOrCreate(target);
        long need = Math.max(1L, profile.xpToNext());
        sender.sendMessage(Component.text("--- " + target.getName() + " ---", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Уровень: " + profile.level(), NamedTextColor.GREEN));
        sender.sendMessage(Component.text("Здоровье: " + profile.health() + "/" + profile.maxHealth(),
                NamedTextColor.RED));
        sender.sendMessage(Component.text("Базовый урон: " + profile.damage(), NamedTextColor.AQUA));
        sender.sendMessage(Component.text("Опыт: " + profile.xp() + "/" + need, NamedTextColor.YELLOW));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            if (sender.hasPermission("servermmo.stats.reset")) {
                options.add("reset");
            }
            options.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()));
            return options.stream()
                    .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("reset")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
        }
        return List.of();
    }
}
