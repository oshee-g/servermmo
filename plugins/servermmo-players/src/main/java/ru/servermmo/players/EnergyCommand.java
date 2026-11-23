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

/** Просмотр и выдача энергии игрокам. */
public final class EnergyCommand implements CommandExecutor, TabCompleter {

    private final EnergyListener energyListener;

    EnergyCommand(EnergyListener energyListener) {
        this.energyListener = energyListener;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                showEnergy(sender, player);
            } else {
                sender.sendMessage(Component.text("Использование: /energy <игрок>",
                        NamedTextColor.YELLOW));
            }
            return true;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("add") || action.equals("give") || action.equals("добавить")) {
            addEnergy(sender, args);
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(Component.text(
                    "Игрок не найден. Использование: /energy add <количество> [игрок]",
                    NamedTextColor.RED));
            return true;
        }
        showEnergy(sender, target);
        return true;
    }

    private void addEnergy(CommandSender sender, String[] args) {
        if (!sender.hasPermission("servermmo.energy.give")) {
            sender.sendMessage(Component.text("Недостаточно прав", NamedTextColor.RED));
            return;
        }
        if (args.length < 2 || args.length > 3) {
            sender.sendMessage(Component.text(
                    "Использование: /energy add <количество> [игрок]", NamedTextColor.YELLOW));
            return;
        }

        long amount;
        try {
            amount = Long.parseLong(args[1]);
        } catch (NumberFormatException ex) {
            sender.sendMessage(Component.text("Количество должно быть целым числом",
                    NamedTextColor.RED));
            return;
        }
        if (amount <= 0L) {
            sender.sendMessage(Component.text("Количество должно быть больше нуля",
                    NamedTextColor.RED));
            return;
        }

        Player target;
        if (args.length == 3) {
            target = Bukkit.getPlayerExact(args[2]);
        } else {
            target = sender instanceof Player player ? player : null;
        }
        if (target == null) {
            sender.sendMessage(Component.text(args.length == 3
                    ? "Игрок не найден" : "Из консоли укажите игрока: /energy add <количество> <игрок>",
                    NamedTextColor.RED));
            return;
        }

        PlayerProfile profile = ServerMMOCore.get().players().getOrCreate(target);
        long previous = profile.energy();
        long added = Math.min(amount, profile.maxEnergy() - previous);
        profile.energy(previous + added);
        energyListener.refreshSprint(target);
        sender.sendMessage(Component.text("Энергия игрока " + target.getName() + ": "
                + profile.energy() + "/" + profile.maxEnergy()
                + (added > 0L ? " (добавлено " + added + ")" : " (уже максимум)"),
                NamedTextColor.GREEN));
    }

    private void showEnergy(CommandSender sender, Player target) {
        PlayerProfile profile = ServerMMOCore.get().players().getOrCreate(target);
        sender.sendMessage(Component.text("Энергия " + target.getName() + ": "
                + profile.energy() + "/" + profile.maxEnergy(), NamedTextColor.AQUA));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            options.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .collect(Collectors.toList()));
            if (sender.hasPermission("servermmo.energy.give")) {
                options.add("add");
                options.add("give");
            }
            return matching(args[0], options);
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("add")
                || args[0].equalsIgnoreCase("give")
                || args[0].equalsIgnoreCase("добавить"))
                && sender.hasPermission("servermmo.energy.give")) {
            return matching(args[2], Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName).collect(Collectors.toList()));
        }
        return List.of();
    }

    private static List<String> matching(String prefix, List<String> options) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower))
                .collect(Collectors.toList());
    }
}
