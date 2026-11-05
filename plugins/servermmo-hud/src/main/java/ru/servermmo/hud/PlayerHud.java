package ru.servermmo.hud;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Vector;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import ru.servermmo.core.ServerMMOCore;
import ru.servermmo.core.player.PlayerProfile;

/**
 * HUD одного игрока: табличка перед камерой с сердцем, мечом, щитом и
 * кристаллом из ресурспака и числами рядом.
 *
 * <p>Иконки — глифы шрифта {@code servermmo:hud} (ячейка 8x8), числа — тоже
 * из пака, поэтому строка выглядит как единая пиксельная панель. Без пака
 * вместо иконок будут пустые квадратики, числа останутся ванильными.</p>
 */
final class PlayerHud {

    /** Сердце (здоровье). */
    private static final String GLYPH_HEART = "\uE010";
    /** Меч (базовый урон). */
    private static final String GLYPH_SWORD = "\uE011";
    /** Щит (уровень). */
    private static final String GLYPH_LEVEL = "\uE012";
    /** Кристалл (опыт). */
    private static final String GLYPH_XP = "\uE013";
    /** Пустой глиф-разделитель шириной 2 пикселя. */
    private static final String GLYPH_SPACER = "\uE100";

    /** Шрифт ресурспака с иконками и цифрами. */
    private static final Style HUD_STYLE = Style.style()
            .font(Key.key("servermmo", "hud"))
            .build();

    private static final Style VALUE_STYLE = Style.style()
            .font(Key.key("servermmo", "hud"))
            .color(NamedTextColor.WHITE)
            .build();

    private static final Style LABEL_STYLE = Style.style()
            .font(Key.key("servermmo", "hud"))
            .color(NamedTextColor.WHITE)
            .build();

    private final ServerMMOHud plugin;
    private final HudSettings settings;
    private final Player player;

    private TextDisplay display;
    private String lastText;

    PlayerHud(ServerMMOHud plugin, HudSettings settings, Player player) {
        this.plugin = plugin;
        this.settings = settings;
        this.player = player;
    }

    Player player() {
        return player;
    }

    /** Пересоздаёт сущность (например, после смены мира). */
    void reset() {
        if (display != null && display.isValid()) {
            display.remove();
        }
        display = null;
        lastText = null;
    }

    void remove() {
        reset();
    }

    void tick() {
        if (!player.isOnline()) {
            remove();
            return;
        }
        TextDisplay current = ensureDisplay();
        if (current == null) {
            return;
        }
        current.teleport(computeLocation());
        Component text = buildText();
        String flat = plain(text);
        if (!flat.equals(lastText)) {
            current.text(text);
            lastText = flat;
        }
    }

    private TextDisplay ensureDisplay() {
        if (display != null && display.isValid() && display.getWorld().equals(player.getWorld())) {
            return display;
        }
        reset();
        Location spawn = computeLocation();
        TextDisplay created = player.getWorld().spawn(spawn, TextDisplay.class, entity -> {
            entity.setVisibleByDefault(false);
            entity.setBillboard(Display.Billboard.FIXED);
            entity.setSeeThrough(settings.seeThrough());
            entity.setShadowed(false);
            entity.setDefaultBackground(false);
            entity.setLineWidth(1000);
            entity.setViewRange(1.0F);
            entity.setPersistent(false);
            entity.setInvulnerable(true);
            entity.setGravity(false);
            entity.setSilent(true);
            entity.setInterpolationDuration(0);
            entity.setTeleportDuration(0);
            entity.setTransformation(new Transformation(
                    new Vector3f(0.0F, 0.0F, 0.0F),
                    new Quaternionf(),
                    new Vector3f(settings.scale(), settings.scale(), settings.scale()),
                    new Quaternionf()));
        });
        player.showEntity(plugin, created);
        display = created;
        return created;
    }

    private Location computeLocation() {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        Vector up = new Vector(0.0D, 1.0D, 0.0D);
        Vector right = up.clone().crossProduct(direction);
        if (right.lengthSquared() < 1.0E-6D) {
            right = new Vector(1.0D, 0.0D, 0.0D);
        }
        right.normalize();
        Vector cameraUp = direction.clone().crossProduct(right).normalize();
        Location location = eye.clone()
                .add(direction.clone().multiply(settings.distance()))
                .add(right.multiply(settings.offsetX()))
                .add(cameraUp.multiply(settings.offsetY()));
        location.setDirection(direction);
        return location;
    }

    /** Четыре строки: здоровье, урон, уровень, опыт. */
    private Component buildText() {
        ServerMMOCore core = ServerMMOCore.get();
        long health = 0L;
        long damage = 0L;
        long level = 1L;
        long xp = 0L;
        long xpToNext = 1L;
        if (core != null) {
            PlayerProfile profile = core.players().get(player.getUniqueId());
            if (profile != null) {
                health = profile.health();
                damage = profile.damage();
                level = profile.level();
                xp = profile.xp();
                xpToNext = Math.max(1L, profile.xpToNext());
            }
        }
        return Component.text()
                .append(row(GLYPH_HEART, Long.toString(health)))
                .append(Component.newline())
                .append(row(GLYPH_SWORD, Long.toString(damage)))
                .append(Component.newline())
                .append(row(GLYPH_LEVEL, Long.toString(level)))
                .append(Component.newline())
                .append(row(GLYPH_XP, xp + "/" + xpToNext))
                .build();
    }

    private Component row(String icon, String value) {
        return Component.text()
                .append(Component.text(icon, HUD_STYLE))
                .append(Component.text(GLYPH_SPACER, LABEL_STYLE))
                .append(Component.text(value, VALUE_STYLE))
                .build();
    }

    private static String plain(Component component) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(component);
    }
}
