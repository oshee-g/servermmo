package ru.servermmo.hud;

import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Команды на числовые данные панели: класс, клан, валюты, защита.
 *
 * <p>Смысловой логики за ними пока нет — это заглушки, чтобы панель чем-то
 * заполнялась и чтобы цифры можно было проверить вживую. Значения хранятся в
 * {@code players.yml} и переживают рестарт.</p>
 *
 * <p>Все команды операторские. Цель по умолчанию — тот, кто ввёл команду;
 * оператор может указать игрока вторым аргументом.</p>
 */
public final class HudDataCommand implements CommandExecutor {

    private final ServerMMOHud plugin;

    HudDataCommand(ServerMMOHud plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String key = command.getName().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "class" -> setClass(sender, args);
            case "clan" -> setClan(sender, args);
            default -> setNumber(sender, key, args);
        };
    }

    // --- класс ----------------------------------------------------------

    private boolean setClass(CommandSender sender, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("список")) {
            sender.sendMessage(Component.text("Классы: " + String.join(", ",
                    HudProfile.CLASSES), NamedTextColor.YELLOW));
            return true;
        }
        if (args.length < 1) {
            Player self = self(sender);
            if (self != null) {
                sender.sendMessage(Component.text("Ваш класс: "
                        + plugin.store().get(self.getUniqueId()).className(),
                        NamedTextColor.YELLOW));
            }
            sender.sendMessage(Component.text("Классы: " + String.join(", ",
                    HudProfile.CLASSES), NamedTextColor.GRAY));
            return true;
        }
        int index = classIndex(args[0]);
        if (index < 0) {
            sender.sendMessage(Component.text("Нет такого класса. Доступные: "
                    + String.join(", ", HudProfile.CLASSES), NamedTextColor.RED));
            return true;
        }
        Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : self(sender);
        if (target == null) {
            sender.sendMessage(Component.text("Игрок не найден (или команду "
                    + "ввёл не игрок)", NamedTextColor.RED));
            return true;
        }
        HudProfile profile = plugin.store().get(target.getUniqueId());
        profile.classIndex(index);
        plugin.store().save();
        plugin.manager().reset(target);
        sender.sendMessage(Component.text("Класс игрока " + target.getName() + ": "
                + profile.className(), NamedTextColor.GREEN));
        return true;
    }

    private static int classIndex(String raw) {
        String name = raw.toLowerCase(Locale.ROOT);
        for (int i = 0; i < HudProfile.CLASSES.length; i++) {
            if (HudProfile.CLASSES[i].equals(name)) {
                return i;
            }
        }
        try {
            int index = Integer.parseInt(raw) - 1;
            return index >= 0 && index < HudProfile.CLASSES.length ? index : -1;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    // --- клан -----------------------------------------------------------

    private boolean setClan(CommandSender sender, String[] args) {
        Player target = args.length >= 3 ? Bukkit.getPlayerExact(args[2])
                : (args.length == 2 && !isNumber(args[1]) ? Bukkit.getPlayerExact(args[0]) : self(sender));
        if (target == null) {
            sender.sendMessage(Component.text("Игрок не найден (или команду "
                    + "ввёл не игрок)", NamedTextColor.RED));
            return true;
        }
        if (args.length < 1) {
            HudProfile current = plugin.store().get(target.getUniqueId());
            sender.sendMessage(Component.text("Клан: " + current.clanName()
                    + ", уровень " + current.clanLevel() + ", опыт " + current.clanXp(),
                    NamedTextColor.YELLOW));
            return true;
        }
        HudProfile profile = plugin.store().get(target.getUniqueId());
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("level") || sub.equals("уровень")) {
            Long value = parse(sender, args.length >= 2 ? args[1] : null);
            if (value == null) {
                return true;
            }
            profile.clanLevel(value);
        } else if (sub.equals("xp") || sub.equals("опыт")) {
            Long value = parse(sender, args.length >= 2 ? args[1] : null);
            if (value == null) {
                return true;
            }
            profile.clanXp(value);
        } else {
            // /clan <название> — всё остальное считаем названием
            StringBuilder name = new StringBuilder(args[0]);
            for (int i = 1; i < args.length; i++) {
                if (Bukkit.getPlayerExact(args[i]) != null && i == args.length - 1) {
                    Player last = Bukkit.getPlayerExact(args[i]);
                    if (last != null) {
                        target = last;
                        profile = plugin.store().get(target.getUniqueId());
                        continue;
                    }
                }
                name.append(' ').append(args[i]);
            }
            profile.clanName(name.toString());
        }
        plugin.store().save();
        plugin.manager().reset(target);
        sender.sendMessage(Component.text("Клан игрока " + target.getName() + ": "
                + profile.clanName() + ", уровень " + profile.clanLevel()
                + ", опыт " + profile.clanXp(), NamedTextColor.GREEN));
        return true;
    }

    // --- числа: монеты, коины, боевые очки, нанагемы, защита -------------

    private boolean setNumber(CommandSender sender, String key, String[] args) {
        if (args.length < 1) {
            Player self = self(sender);
            if (self != null) {
                sender.sendMessage(Component.text(labelOf(key) + ": "
                        + read(plugin.store().get(self.getUniqueId()), key),
                        NamedTextColor.YELLOW));
            }
            return true;
        }
        Long value = parse(sender, args[0]);
        if (value == null) {
            return true;
        }
        Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : self(sender);
        if (target == null) {
            sender.sendMessage(Component.text("Игрок не найден (или команду "
                    + "ввёл не игрок)", NamedTextColor.RED));
            return true;
        }
        HudProfile profile = plugin.store().get(target.getUniqueId());
        write(profile, key, value);
        plugin.store().save();
        plugin.manager().reset(target);
        sender.sendMessage(Component.text(labelOf(key) + " игрока " + target.getName()
                + ": " + read(profile, key), NamedTextColor.GREEN));
        return true;
    }

    private static void write(HudProfile profile, String key, long value) {
        switch (key) {
            case "coins" -> profile.coins(value);
            case "star" -> profile.starCoins(value);
            case "bp" -> profile.battlePoints(value);
            case "gems" -> profile.nanogems(value);
            case "armor" -> profile.armor(value);
            default -> { }
        }
    }

    private static long read(HudProfile profile, String key) {
        return switch (key) {
            case "coins" -> profile.coins();
            case "star" -> profile.starCoins();
            case "bp" -> profile.battlePoints();
            case "gems" -> profile.nanogems();
            case "armor" -> profile.armor();
            default -> 0L;
        };
    }

    private static String labelOf(String key) {
        return switch (key) {
            case "coins" -> "Монеты";
            case "star" -> "Коины";
            case "bp" -> "Боевые очки";
            case "gems" -> "Нанагемы";
            case "armor" -> "Защита";
            default -> key;
        };
    }

    // --- helpers --------------------------------------------------------

    private static Player self(CommandSender sender) {
        return sender instanceof Player player ? player : null;
    }

    private static boolean isNumber(String raw) {
        try {
            Long.parseLong(raw);
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static Long parse(CommandSender sender, String raw) {
        if (raw == null) {
            sender.sendMessage(Component.text("Нужно число", NamedTextColor.RED));
            return null;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ex) {
            sender.sendMessage(Component.text("Нужно целое число, а не «"
                    + raw + "»", NamedTextColor.RED));
            return null;
        }
    }
}
