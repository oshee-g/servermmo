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

/**
 * {@code /hud} — подстройка панели на месте.
 *
 * <p>Положение панели зависит от масштаба интерфейса в настройках клиента,
 * который сервер узнать не может, поэтому сдвиг подбирается вручную и
 * запоминается в {@code config.yml}.</p>
 */
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
            apply(sender, "HUD вернул заводской сдвиг");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text(
                    "/hud offsetx <пиксели>  — сдвиг панели влево (минус) или вправо (плюс)",
                    NamedTextColor.YELLOW));
            sender.sendMessage(Component.text(
                    "сейчас offset-x=" + settings.offsetX()
                            + "   (заводское " + HudSettings.DEFAULT_OFFSET_X + ")",
                    NamedTextColor.GRAY));
            sender.sendMessage(Component.text(
                    "Если панель уехала за левый край — увеличьте минус, "
                            + "если стоит слишком близко к центру — уменьшите.",
                    NamedTextColor.GRAY));
            return true;
        }
        String key = args[0].toLowerCase(Locale.ROOT);
        if (!key.equals("offsetx") && !key.equals("offset")) {
            sender.sendMessage(Component.text("/hud offsetx <пиксели> | /hud reset",
                    NamedTextColor.YELLOW));
            return true;
        }
        int value;
        try {
            value = Integer.parseInt(args[1]);
        } catch (NumberFormatException ex) {
            sender.sendMessage(Component.text("Нужно целое число пикселей", NamedTextColor.RED));
            return true;
        }
        if (value > 0 || value < -1023) {
            sender.sendMessage(Component.text(
                    "offsetx должен быть от -1023 до 0: панель ставится от центра влево",
                    NamedTextColor.RED));
            return true;
        }
        settings.offsetX(value);
        apply(sender, "Готово: offset-x = " + value);
        return true;
    }

    private void apply(CommandSender sender, String message) {
        plugin.getConfig().set("hud.offset-x", settings.offsetX());
        plugin.saveConfig();
        if (sender instanceof Player player) {
            plugin.manager().reset(player);
        }
        sender.sendMessage(Component.text(message, NamedTextColor.GREEN));
    }

    /** Подсказка аргументов для tab-complete. */
    public static List<String> keys() {
        return List.of("offsetx", "reset");
    }
}
