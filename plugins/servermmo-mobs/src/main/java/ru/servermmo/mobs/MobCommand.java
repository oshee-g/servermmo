package ru.servermmo.mobs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.servermmo.core.stats.LevelStats;

/**
 * {@code /mob spawn <уровень> <тип> [x y z]} — создать кастомного моба.
 *
 * <p>Без координат моб появляется там, где стоит игрок. Обычные мобы в мире
 * при этом остаются ванильными: кастомные существуют только после этой
 * команды.</p>
 */
public final class MobCommand implements CommandExecutor, TabCompleter {

    private final ServerMMOMobs plugin;
    private final MobSpawner spawner;

    MobCommand(ServerMMOMobs plugin, MobSpawner spawner) {
        this.plugin = plugin;
        this.spawner = spawner;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("spawn")) {
            sender.sendMessage(Component.text(
                    "/mob spawn <уровень> <тип> [x y z] — например: /mob spawn 2 Курица",
                    NamedTextColor.YELLOW));
            sender.sendMessage(Component.text(
                    "/mob spawn 40 zombie 100 65 -20 — моб 40 уровня в точке",
                    NamedTextColor.GRAY));
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(Component.text("/mob spawn <уровень> <тип> [x y z]",
                    NamedTextColor.RED));
            return true;
        }

        int level;
        try {
            level = Integer.parseInt(args[1]);
        } catch (NumberFormatException ex) {
            sender.sendMessage(Component.text("Уровень должен быть числом: 1.."
                    + plugin.core().levels().maxLevel(), NamedTextColor.RED));
            return true;
        }
        LevelStats stats = spawner.stats(level);
        if (stats == null) {
            sender.sendMessage(Component.text("Нет уровня " + level + " (таблица 1.."
                    + plugin.core().levels().maxLevel() + ")", NamedTextColor.RED));
            return true;
        }

        EntityType type = spawner.resolveType(args[2]);
        if (type == null) {
            sender.sendMessage(Component.text("Не знаю моба «" + args[2]
                    + "». Можно по-русски (Курица) или по имени игры (chicken).", NamedTextColor.RED));
            return true;
        }

        Location base = sender instanceof Player p ? p.getLocation()
                : plugin.getServer().getWorlds().get(0).getSpawnLocation();
        Location location;
        if (args.length >= 6) {
            try {
                double x = Double.parseDouble(args[3]);
                double y = Double.parseDouble(args[4]);
                double z = Double.parseDouble(args[5]);
                location = new Location(base.getWorld(), x, y, z);
            } catch (NumberFormatException ex) {
                sender.sendMessage(Component.text("Координаты должны быть числами: x y z",
                        NamedTextColor.RED));
                return true;
            }
        } else if (sender instanceof Player) {
            location = base;
        } else {
            sender.sendMessage(Component.text(
                    "Из консоли нужны координаты: /mob spawn <уровень> <тип> <x> <y> <z>",
                    NamedTextColor.RED));
            return true;
        }

        LivingEntity mob = spawner.spawn(location, type, level);
        if (mob == null) {
            sender.sendMessage(Component.text("Не удалось создать «" + type.getKey().getKey()
                    + "» — это не моб.", NamedTextColor.RED));
            return true;
        }
        plugin.tags().apply(mob);
        sender.sendMessage(Component.text("Создан " + stats.mobName() + " " + level
                + " уровня: " + stats.health() + " HP, " + stats.damage() + " урона",
                NamedTextColor.GREEN));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            result.add("spawn");
        } else if (args.length == 2) {
            for (int level = 1; level <= Math.min(10, plugin.core().levels().maxLevel()); level++) {
                result.add(Integer.toString(level));
            }
        } else if (args.length == 3) {
            String probe = args[2].toLowerCase(Locale.ROOT);
            for (EntityType type : org.bukkit.Registry.ENTITY_TYPE) {
                String key = type.getKey().getKey();
                if (key.startsWith(probe)) {
                    result.add(key);
                }
            }
        }
        return result;
    }
}
