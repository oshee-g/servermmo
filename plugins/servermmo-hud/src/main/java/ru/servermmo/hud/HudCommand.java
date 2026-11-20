package ru.servermmo.hud;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Команда {@code /hud}: подгонка панели под экран игрока.
 *
 * <p>Главное, что здесь настраивается, — <b>масштаб интерфейса</b>
 * ({@code Настройки → Видео → Масштаб интерфейса}) и <b>разрешение окна</b>.
 * Клиент их серверу не сообщает: в пакете {@code ClientInformation} (26.3)
 * только язык, обзор, чат, руки и частицы — ни масштаба, ни размера окна.
 * Поэтому автоматически, без мода на клиенте, их узнать нельзя, и игрок
 * называет их сам один раз.</p>
 *
 * <p>Зато дальше всё считается само: плагин помнит не сдвиг панели (он в
 * пикселях интерфейса и при смене масштаба меняется в разы), а <b>отступ от
 * края экрана</b>, который от масштаба не зависит. Сменили масштаб — сказали
 * {@code /hud scale 3} — панель осталась в том же углу.</p>
 */
final class HudCommand implements CommandExecutor {

    /** Масштабы интерфейса, под которые считаем отступ (0 — «Авто»). */
    private static final int[] SCALES = {0, 1, 2, 3, 4, 5, 6, 7, 8};

    private final ServerMMOHud plugin;
    private final HudSettings settings;

    HudCommand(ServerMMOHud plugin, HudSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }
        String key = args[0].toLowerCase(Locale.ROOT);
        return switch (key) {
            case "reset", "сброс" -> reset(sender);
            case "debug" -> debug(sender);
            case "scale", "масштаб" -> setScale(sender, args);
            case "screen", "экран" -> setScreen(sender, args);
            case "margin", "отступ" -> setMargin(sender, args);
            case "offsetx", "offset", "сдвиг" -> setOffset(sender, args);
            case "offsety" -> setOffsetY(sender, args);
            case "anchorx", "anchor", "якорь" -> setAnchor(sender, args);
            default -> {
                help(sender);
                yield true;
            }
        };
    }

    // --- масштаб интерфейса -----------------------------------------------

    /**
     * {@code /hud scale <1..8|auto> [игрок]} — какой масштаб интерфейса стоит
     * у игрока в настройках игры.
     */
    private boolean setScale(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text(
                    "Укажите масштаб интерфейса: /hud scale <1..8|auto> [игрок]",
                    NamedTextColor.YELLOW));
            return true;
        }
        int scale;
        String raw = args[1].toLowerCase(Locale.ROOT);
        if (raw.equals("auto") || raw.equals("авто")) {
            scale = 0;
        } else {
            Integer parsed = parseInt(args[1]);
            if (parsed == null || parsed < 1 || parsed > 8) {
                sender.sendMessage(Component.text(
                        "Масштаб интерфейса — целое число 1..8 или auto",
                        NamedTextColor.RED));
                return true;
            }
            scale = parsed;
        }
        Player target = target(sender, args, 2);
        if (target == null || !allowed(sender, target)) {
            return true;
        }
        HudProfile profile = plugin.store().get(target.getUniqueId());
        profile.guiScale(scale);
        plugin.store().save();
        plugin.manager().reset(target);
        HudLayout layout = settings.layout(profile);
        sender.sendMessage(Component.text("Масштаб интерфейса игрока "
                + target.getName() + ": " + (scale == 0 ? "Авто" : scale)
                + " (интерфейс " + layout.interfaceWidth() + " px, сдвиг панели "
                + layout.panelShift(plugin.text().panelWidth(profile.classIndex()))
                + " px, отступ от края " + layout.marginLeft() + " px)",
                NamedTextColor.GREEN));
        if (sender instanceof Player player && player.equals(target)) {
            sender.sendMessage(Component.text(
                    "Запомнили. Смените масштаб в настройках — скажите /hud scale "
                            + "с новым числом, панель встанет в тот же угол.",
                    NamedTextColor.GRAY));
        }
        return true;
    }

    /**
     * {@code /hud screen <ширина> [высота] [игрок]} — разрешение окна игры в
     * реальных пикселях. По нему и по масштабу считается ширина интерфейса.
     */
    private boolean setScreen(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text(
                    "Укажите разрешение окна: /hud screen <ширина> [высота] [игрок]",
                    NamedTextColor.YELLOW));
            return true;
        }
        List<Integer> numbers = new ArrayList<>();
        String playerName = null;
        for (int i = 1; i < args.length; i++) {
            Integer parsed = parseInt(args[i]);
            if (parsed == null) {
                playerName = args[i];
            } else {
                numbers.add(parsed);
            }
        }
        if (numbers.isEmpty()) {
            sender.sendMessage(Component.text("Нужна ширина окна в пикселях",
                    NamedTextColor.RED));
            return true;
        }
        Player target;
        if (playerName != null) {
            target = Bukkit.getPlayerExact(playerName);
            if (target == null) {
                sender.sendMessage(Component.text("Игрок не найден: " + playerName,
                        NamedTextColor.RED));
                return true;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage(Component.text(
                    "Из консоли укажите игрока: /hud screen <ширина> [высота] <ник>",
                    NamedTextColor.RED));
            return true;
        }
        if (!allowed(sender, target)) {
            return true;
        }
        HudProfile profile = plugin.store().get(target.getUniqueId());
        profile.screenWidth(numbers.get(0));
        profile.screenHeight(numbers.size() > 1 ? numbers.get(1) : numbers.get(0) * 9 / 16);
        plugin.store().save();
        plugin.manager().reset(target);
        HudLayout layout = settings.layout(profile);
        sender.sendMessage(Component.text("Разрешение окна игрока " + target.getName() + ": "
                + profile.screenWidth() + "×" + profile.screenHeight()
                + " (интерфейс " + layout.interfaceWidth() + " px, сдвиг панели "
                + layout.panelShift(plugin.text().panelWidth(profile.classIndex())) + " px)",
                NamedTextColor.GREEN));
        return true;
    }

    /**
     * {@code /hud margin <px>} — отступ левого края панели от края экрана, в
     * пикселях интерфейса. Общая настройка сервера, от масштаба не зависит.
     */
    private boolean setMargin(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text(
                    "Укажите отступ от края экрана: /hud margin <пиксели>",
                    NamedTextColor.YELLOW));
            return true;
        }
        Integer value = parseInt(args[1]);
        if (value == null) {
            sender.sendMessage(Component.text("Нужно целое число пикселей",
                    NamedTextColor.RED));
            return true;
        }
        if (!sender.hasPermission("servermmo.hud")) {
            sender.sendMessage(Component.text("Недостаточно прав", NamedTextColor.RED));
            return true;
        }
        settings.marginLeft(value);
        applyAll(sender, "Готово: отступ панели от левого края экрана = " + value
                + " px (при любом масштабе интерфейса)");
        return true;
    }

    // --- ручная подгонка ---------------------------------------------------

    /**
     * {@code /hud offsetx <пиксели>} — ручная подгонка в старых единицах.
     *
     * <p>Сдвиг в пикселях интерфейса сам по себе масштабозависим, поэтому
     * команда не сохраняет его, а пересчитывает в отступ от края экрана при
     * текущем масштабе: подобранный на глаз сдвиг переживёт смену масштаба.
     * </p>
     */
    private boolean setOffset(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text(
                    "Укажите сдвиг: /hud offsetx <пиксели> (например -770)",
                    NamedTextColor.YELLOW));
            return true;
        }
        Integer value = parseInt(args[1]);
        if (value == null || value > 0 || value < -HudLayout.MAX_SHIFT) {
            sender.sendMessage(Component.text("Сдвиг — целое число от "
                    + (-HudLayout.MAX_SHIFT) + " до 0: панель ставится от центра "
                    + "экрана влево", NamedTextColor.RED));
            return true;
        }
        if (!sender.hasPermission("servermmo.hud")) {
            sender.sendMessage(Component.text("Недостаточно прав: отступ от края "
                    + "экрана — общая настройка сервера", NamedTextColor.RED));
            return true;
        }
        Player target = target(sender, args, 2);
        if (target == null) {
            return true;
        }
        HudProfile profile = plugin.store().get(target.getUniqueId());
        HudLayout layout = settings.layout(profile);
        int panel = plugin.text().panelWidth(profile.classIndex());
        int margin = (value + layout.interfaceWidth() - panel) / 2;
        settings.marginLeft(margin);
        applyAll(sender, "Готово: сдвиг " + value + " px при интерфейсе "
                + layout.interfaceWidth() + " px — это отступ от края экрана "
                + margin + " px. Он сохранится и при другом масштабе.");
        return true;
    }

    /**
     * {@code /hud offsety <px>} — сдвиг текста и значков панели вниз
     * относительно её картинки, в пикселях интерфейса (-15..15).
     *
     * <p>Базовая линия босс-бара фиксирована клиентом, поэтому на самом
     * деле двигается картинка панели в противоположную сторону: визуально
     * контент сдвигается относительно панели. Положительное значение —
     * контент ниже, отрицательное — выше, 0 — как было.</p>
     */
    private boolean setOffsetY(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text(
                    "Укажите сдвиг: /hud offsety <пиксели> (от -15 до 15, "
                            + "положительное — контент ниже)",
                    NamedTextColor.YELLOW));
            return true;
        }
        Integer value = parseInt(args[1]);
        if (value == null) {
            sender.sendMessage(Component.text("Нужно целое число пикселей",
                    NamedTextColor.RED));
            return true;
        }
        if (!sender.hasPermission("servermmo.hud")) {
            sender.sendMessage(Component.text("Недостаточно прав", NamedTextColor.RED));
            return true;
        }
        settings.panelOffsetY(value);
        applyAll(sender, "Готово: текст и значки панели сдвинуты на "
                + settings.panelOffsetY() + " px вниз относительно картинки"
                + " (0 — как было)");
        return true;
    }

    /** {@code /hud anchorx <пиксели>} — левый край строки хотбара. */
    private boolean setAnchor(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text(
                    "Укажите якорь: /hud anchorx <пиксели> (-91 — левый край хотбара)",
                    NamedTextColor.YELLOW));
            return true;
        }
        Integer value = parseInt(args[1]);
        if (value == null || value > 0 || value < -HudLayout.MAX_SHIFT) {
            sender.sendMessage(Component.text("Якорь — целое число от "
                    + (-HudLayout.MAX_SHIFT) + " до 0: строка хотбара ставится "
                    + "от центра экрана влево", NamedTextColor.RED));
            return true;
        }
        if (!sender.hasPermission("servermmo.hud")) {
            sender.sendMessage(Component.text("Недостаточно прав", NamedTextColor.RED));
            return true;
        }
        settings.anchorX(value);
        applyAll(sender, "Готово: anchor-x = " + value
                + " (левый край строки хотбара относительно центра экрана)");
        return true;
    }

    private boolean reset(CommandSender sender) {
        if (!sender.hasPermission("servermmo.hud")) {
            sender.sendMessage(Component.text("Недостаточно прав", NamedTextColor.RED));
            return true;
        }
        settings.reset();
        applyAll(sender, "HUD вернул заводские настройки: экран "
                + settings.screenWidth() + "×" + settings.screenHeight()
                + ", масштаб " + (settings.guiScale() == 0 ? "Авто" : settings.guiScale())
                + ", отступ от края " + settings.marginLeft() + " px");
        return true;
    }

    // --- диагностика -------------------------------------------------------

    /** Показать, почему HUD может стоять не там: масштаб, интерфейс, сдвиг. */
    private boolean debug(CommandSender sender) {
        HudProfile profile = null;
        if (sender instanceof Player player) {
            profile = plugin.store().get(player.getUniqueId());
            sender.sendMessage(Component.text(
                    "HUD: " + plugin.manager().debugLine(player), NamedTextColor.YELLOW));
        }
        HudLayout layout = settings.layout(profile);
        int panel = plugin.text().panelWidth(profile == null ? 0 : profile.classIndex());
        sender.sendMessage(Component.text("экран " + settings.screenWidth() + "×"
                + settings.screenHeight() + ", масштаб "
                + (settings.guiScale() == 0 ? "Авто" : settings.guiScale())
                + " → интерфейс " + layout.interfaceWidth() + " px, отступ от края "
                + layout.marginLeft() + " px, сдвиг панели "
                + layout.panelShift(panel) + " px (панель " + panel + " px)",
                NamedTextColor.YELLOW));
        int width = profile != null && profile.screenWidth() > 0
                ? profile.screenWidth() : settings.screenWidth();
        int height = profile != null && profile.screenHeight() > 0
                ? profile.screenHeight() : settings.screenHeight();
        int current = HudSettings.effectiveScale(
                profile != null && profile.guiScale() > 0 ? profile.guiScale() : settings.guiScale(),
                width, height);
        for (int scale : SCALES) {
            if (scale == 0) {
                continue;
            }
            int width2 = HudSettings.interfaceWidth(scale, width, height);
            int shift = new HudLayout(width2, layout.marginLeft()).panelShift(panel);
            sender.sendMessage(Component.text("  масштаб " + scale + ": интерфейс "
                    + width2 + " px, сдвиг " + shift + " px"
                    + (Math.abs(shift) >= HudLayout.MAX_SHIFT
                            ? " — упёрлись в предел сдвига!" : ""),
                    scale == current ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        }
        sender.sendMessage(Component.text(
                "Сменили масштаб интерфейса в настройках игры — скажите /hud scale "
                        + "<число>: отступ от края сохранится, панель останется в углу.",
                NamedTextColor.GRAY));
        return true;
    }

    private void help(CommandSender sender) {
        sender.sendMessage(Component.text(
                "/hud scale <1..8|auto> [игрок] — масштаб интерфейса игрока; "
                        + "отступ панели считается под него", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text(
                "/hud screen <ширина> [высота] [игрок] — разрешение окна игры",
                NamedTextColor.YELLOW));
        sender.sendMessage(Component.text(
                "/hud margin <пиксели> — отступ панели от левого края экрана "
                        + "(общая настройка)", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text(
                "/hud offsetx <пиксели> — ручная подгонка: пересчитывает отступ "
                        + "от края по текущему масштабу", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text(
                "/hud offsety <пиксели> — сдвиг текста и значков вниз "
                        + "относительно картинки панели (-15..15)",
                NamedTextColor.YELLOW));
        sender.sendMessage(Component.text(
                "/hud anchorx <пиксели> — левый край строки хотбара (-91)",
                NamedTextColor.YELLOW));
        sender.sendMessage(Component.text(
                "/hud debug — расчёт отступов по всем масштабам; /hud reset — сброс",
                NamedTextColor.YELLOW));
        HudProfile profile = sender instanceof Player player
                ? plugin.store().get(player.getUniqueId()) : null;
        HudLayout layout = settings.layout(profile);
        sender.sendMessage(Component.text("сейчас: экран " + settings.screenWidth()
                + "×" + settings.screenHeight() + ", масштаб "
                + (settings.guiScale() == 0 ? "Авто" : settings.guiScale())
                + " → интерфейс " + layout.interfaceWidth() + " px, отступ от края "
                + layout.marginLeft() + " px", NamedTextColor.GRAY));
        sender.sendMessage(Component.text(
                        "Масштаб интерфейса — кнопкой: ", NamedTextColor.GRAY)
                .append(scaleButtons()));
    }

    /** Кликабельный ряд масштабов: один щелчок вместо команды. */
    private Component scaleButtons() {
        Component line = Component.empty();
        for (int scale : SCALES) {
            if (scale == 0) {
                continue;
            }
            String label = " " + scale + " ";
            line = line.append(Component.text(label, NamedTextColor.GOLD,
                            TextDecoration.BOLD)
                    .clickEvent(ClickEvent.runCommand("/hud scale " + scale))
                    .hoverEvent(HoverEvent.showText(Component.text(
                            "Масштаб интерфейса " + scale + ": интерфейс "
                                    + HudSettings.interfaceWidth(scale,
                                            settings.screenWidth(), settings.screenHeight())
                                    + " px", NamedTextColor.YELLOW))));
        }
        return line;
    }

    // --- помощники ---------------------------------------------------------

    /** Цель команды: из последнего аргумента, если это ник, иначе сам игрок. */
    private Player target(CommandSender sender, String[] args, int valueArgs) {
        if (args.length > valueArgs) {
            Player found = Bukkit.getPlayerExact(args[args.length - 1]);
            if (found == null) {
                sender.sendMessage(Component.text("Игрок не найден: " + args[args.length - 1],
                        NamedTextColor.RED));
                return null;
            }
            return found;
        }
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage(Component.text("Из консоли укажите игрока последним аргументом",
                NamedTextColor.RED));
        return null;
    }

    /** Менять чужое может только оператор, своё — любой игрок. */
    private boolean allowed(CommandSender sender, Player target) {
        if (sender.hasPermission("servermmo.hud")) {
            return true;
        }
        if (sender instanceof Player player && player.equals(target)
                && sender.hasPermission("servermmo.hud.self")) {
            return true;
        }
        sender.sendMessage(Component.text("Недостаточно прав", NamedTextColor.RED));
        return false;
    }

    private void applyAll(CommandSender sender, String message) {
        plugin.getConfig().set("hud.screen-width", settings.screenWidth());
        plugin.getConfig().set("hud.screen-height", settings.screenHeight());
        plugin.getConfig().set("hud.gui-scale", settings.guiScale());
        plugin.getConfig().set("hud.margin-left", settings.marginLeft());
        plugin.getConfig().set("hud.panel-offset-y", settings.panelOffsetY());
        plugin.getConfig().set("hud.hotbar-anchor-x", settings.hotbarAnchorX());
        plugin.saveConfig();
        for (Player player : Bukkit.getOnlinePlayers()) {
            plugin.manager().reset(player);
        }
        sender.sendMessage(Component.text(message, NamedTextColor.GREEN));
    }

    private static Integer parseInt(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Подсказка аргументов для tab-complete. */
    public static List<String> keys() {
        return List.of("scale", "screen", "margin", "offsetx", "offsety", "anchorx", "debug", "reset");
    }
}
