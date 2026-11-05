package ru.servermmo.hud;

import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** {@code /hud} - подстройка положения и размера HUD на месте. */
public final class HudCommand implements CommandExecutor {

    private final ServerMMOHud plugin;
    private final HudSettings settings;

    HudCommand(ServerMMOHud plugin, HudSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reset")) {
            settings.reset();
            plugin.getConfig().set("hud.scale", settings.scale());
            plugin.getConfig().set("hud.distance", settings.distance());
            plugin.getConfig().set("hud.offset-x", settings.offsetX());
            plugin.getConfig().set("hud.offset-y", settings.offsetY());
            plugin.saveConfig();
            if (sender instanceof Player player) {
                plugin.manager().reset(player);
            }
            sender.sendMessage(Component.text(
                    "HUD вернул заводские значения: scale=" + settings.scale()
                            + " distance=" + settings.distance()
                            + " offsetX=" + settings.offsetX()
                            + " offsetY=" + settings.offsetY(), NamedTextColor.GREEN));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text(
                    "/hud <scale|distance|offsetx|offsety> <значение>  |  /hud reset",
                    NamedTextColor.YELLOW));
            sender.sendMessage(Component.text(
                    "сейчас: scale=" + settings.scale()
                            + " distance=" + settings.distance()
                            + " offsetX=" + settings.offsetX()
                            + " offsetY=" + settings.offsetY(), NamedTextColor.GRAY));
            return true;
        }
        double value;
        try {
            value = Double.parseDouble(args[1].replace(',', '.'));
        } catch (NumberFormatException ex) {
            sender.sendMessage(Component.text("Нужно число", NamedTextColor.RED));
            return true;
        }
        String key = args[0].toLowerCase(Locale.ROOT);
        switch (key) {
            case "scale" -> {
                if (value <= 0.0D || value > 1.0D) {
                    sender.sendMessage(Component.text("scale должен быть (0; 1]", NamedTextColor.RED));
                    return true;
                }
                settings.scale((float) value);
                plugin.getConfig().set("hud.scale", value);
            }
            case "distance" -> {
                if (value <= 0.05D || value > 5.0D) {
                    sender.sendMessage(Component.text("distance должен быть (0.05; 5]", NamedTextColor.RED));
                    return true;
                }
                settings.distance(value);
                plugin.getConfig().set("hud.distance", value);
            }
            case "offsetx" -> {
                settings.offsetX(value);
                plugin.getConfig().set("hud.offset-x", value);
            }
            case "offsety" -> {
                settings.offsetY(value);
                plugin.getConfig().set("hud.offset-y", value);
            }
            default -> {
                sender.sendMessage(Component.text(
                        "/hud <scale|distance|offsetx|offsety> <значение>  |  /hud reset",
                        NamedTextColor.YELLOW));
                return true;
            }
        }
        plugin.saveConfig();
        if (sender instanceof Player player) {
            plugin.manager().reset(player);
        }
        sender.sendMessage(Component.text("Готово: " + key + " = " + value, NamedTextColor.GREEN));
        return true;
    }

    /** Подсказка аргументов (используется в plugin.yml как tab-complete по умолчанию). */
    public static List<String> keys() {
        return List.of("scale", "distance", "offsetx", "offsety", "reset");
    }
}
