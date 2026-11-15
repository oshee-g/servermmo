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
 * запоминается в {@code config.yml}. Если панель не видна вообще — почти
 * всегда дело в этом: при чужом масштабе она уезжает за левый край экрана.
 * Быстрый порядок: {@code /hud offsetx 0} (панель встанет по центру верха,
 * так сразу видно, работает ли она), затем {@code /hud scale <3|4|5>} и
 * точная доводка {@code /hud offsetx} / {@code /hud anchorx}.</p>
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
            apply(sender, "HUD вернул заводские отступы: offset-x="
                    + settings.offsetX() + ", anchor-x=" + settings.hotbarAnchorX());
            return true;
        }
        if (args.length == 1 && (args[0].equalsIgnoreCase("scale")
                || args[0].equalsIgnoreCase("масштаб"))) {
            sender.sendMessage(Component.text("Укажите масштаб интерфейса: "
                    + "/hud scale 3 | 4 | 5", NamedTextColor.YELLOW));
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("scale")) {
            int scale;
            try {
                scale = Integer.parseInt(args[1]);
            } catch (NumberFormatException ex) {
                sender.sendMessage(Component.text("Масштаб интерфейса — целое число: "
                        + "/hud scale 3 | 4 | 5", NamedTextColor.RED));
                return true;
            }
            if (!settings.applyScalePreset(scale)) {
                sender.sendMessage(Component.text("Готовых отступов для масштаба "
                        + scale + " нет, подберите вручную: /hud offsetx <px> и "
                        + "/hud anchorx <px>", NamedTextColor.RED));
                return true;
            }
            apply(sender, "Готово: отступы под масштаб интерфейса " + scale
                    + " (offset-x=" + settings.offsetX()
                    + ", anchor-x=" + settings.hotbarAnchorX() + ")");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("debug")) {
            debug(sender);
            return true;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("offsetx")
                || args[0].equalsIgnoreCase("offset"))) {
            return setOffset(sender, args[1]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("anchorx")
                || args[0].equalsIgnoreCase("anchor"))) {
            return setAnchor(sender, args[1]);
        }
        help(sender);
        return true;
    }

    /**
     * Показать, почему HUD может быть не виден: скрыт гейтом (пак не загружен)
     * или просто уехал за край экрана из-за масштаба интерфейса.
     */
    private void debug(CommandSender sender) {
        if (sender instanceof Player player) {
            sender.sendMessage(Component.text(
                    "HUD: " + plugin.manager().debugLine(player), NamedTextColor.YELLOW));
        }
        sender.sendMessage(Component.text(
                "Пак на сервере: " + plugin.resourcePackSummary(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text(
                "Если «пак загружен» — панель работает, а отступ не под ваш "
                        + "масштаб интерфейса: /hud offsetx 0 покажет её по центру верха.",
                NamedTextColor.GRAY));
        sender.sendMessage(Component.text(
                "Если «статус не получен» — клиент молча не берёт серверный пак: "
                        + "в списке серверов «Изменить» -> «Пакеты ресурсов сервера» "
                        + "-> «Включены» (или удалите сервер из списка и добавьте заново).",
                NamedTextColor.GRAY));
    }

    private boolean setOffset(CommandSender sender, String raw) {
        Integer value = parseNegative(sender, raw, -1023);
        if (value == null) {
            return true;
        }
        settings.offsetX(value);
        apply(sender, "Готово: offset-x = " + value);
        return true;
    }

    private boolean setAnchor(CommandSender sender, String raw) {
        Integer value = parseNegative(sender, raw, -1023);
        if (value == null) {
            return true;
        }
        settings.anchorX(value);
        apply(sender, "Готово: anchor-x = " + value
                + " (левый край строки хотбара относительно центра)");
        return true;
    }

    /** Разобрать сдвиг влево: от -limit до 0, но 0 тоже допустим для проверки. */
    private static Integer parseNegative(CommandSender sender, String raw, int limit) {
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            sender.sendMessage(Component.text("Нужно целое число пикселей", NamedTextColor.RED));
            return null;
        }
        if (value > 0 || value < limit) {
            sender.sendMessage(Component.text(
                    "Значение должно быть от " + limit + " до 0: панель ставится "
                            + "от центра экрана влево", NamedTextColor.RED));
            return null;
        }
        return value;
    }

    private void help(CommandSender sender) {
        sender.sendMessage(Component.text(
                "/hud offsetx <пиксели>  — сдвиг панели влево от центра экрана",
                NamedTextColor.YELLOW));
        sender.sendMessage(Component.text(
                "/hud anchorx <пиксели>  — левый край строки хотбара",
                NamedTextColor.YELLOW));
        sender.sendMessage(Component.text(
                "/hud scale <3|4|5>      — готовые отступы под масштаб интерфейса",
                NamedTextColor.YELLOW));
        sender.sendMessage(Component.text(
                "/hud debug              — почему HUD не виден",
                NamedTextColor.YELLOW));
        sender.sendMessage(Component.text(
                "сейчас offset-x=" + settings.offsetX()
                        + " (заводское " + HudSettings.DEFAULT_OFFSET_X + "), anchor-x="
                        + settings.hotbarAnchorX()
                        + " (заводское " + HudSettings.DEFAULT_HOTBAR_ANCHOR_X + ")",
                NamedTextColor.GRAY));
        sender.sendMessage(Component.text(
                "Если HUD не виден вообще: /hud offsetx 0 — панель встанет по центру "
                        + "верха экрана. Значит панель работает, а отступ подобран не под "
                        + "ваш масштаб интерфейса (Настройки → Видео → Масштаб интерфейса).",
                NamedTextColor.GRAY));
    }

    private void apply(CommandSender sender, String message) {
        plugin.getConfig().set("hud.offset-x", settings.offsetX());
        plugin.getConfig().set("hud.hotbar-anchor-x", settings.hotbarAnchorX());
        plugin.saveConfig();
        if (sender instanceof Player player) {
            plugin.manager().reset(player);
        }
        sender.sendMessage(Component.text(message, NamedTextColor.GREEN));
    }

    /** Подсказка аргументов для tab-complete. */
    public static List<String> keys() {
        return List.of("offsetx", "anchorx", "scale", "debug", "reset");
    }
}
