package ru.servermmo.hud;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.Style;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import ru.servermmo.core.ServerMMOCore;
import ru.servermmo.core.player.PlayerProfile;
import org.bukkit.util.Transformation;

/** HUD одного игрока: сущность TextDisplay перед камерой. */
final class PlayerHud {

    /** Символы рамки из ресурспака. */
    private static final String GLYPH_TOP = "\uE001";
    private static final String GLYPH_HEART = "\uE002";
    private static final String GLYPH_SWORD = "\uE003";
    private static final String GLYPH_LVL = "\uE004";
    private static final String GLYPH_ORB = "\uE005";
    private static final String GLYPH_BOTTOM = "\uE006";
    /** Сдвиг каретки на начало текстовой колонки внутри полосы. */
    private static final String GLYPH_REWIND = "\uE100";

    /** Шрифт ресурспака с рамкой, иконками и цифрами. */
    private static final Style HUD_STYLE = Style.style(Key.key("servermmo", "hud"));

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
        String text = buildText();
        if (!text.equals(lastText)) {
            current.text(Component.text(text).style(HUD_STYLE));
            lastText = text;
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

    private String buildText() {
        ServerMMOCore core = ServerMMOCore.get();
        long health = 0L;
        long maxHealth = 0L;
        long damage = 0L;
        long level = 1L;
        long xp = 0L;
        long xpToNext = 1L;
        if (core != null) {
            PlayerProfile profile = core.players().get(player.getUniqueId());
            if (profile != null) {
                health = profile.health();
                maxHealth = profile.maxHealth();
                damage = profile.damage();
                level = profile.level();
                xp = profile.xp();
                xpToNext = Math.max(1L, profile.xpToNext());
            }
        }
        return GLYPH_TOP + "\n"
                + GLYPH_HEART + GLYPH_REWIND + health + "\n"
                + GLYPH_SWORD + GLYPH_REWIND + damage + "\n"
                + GLYPH_LVL + GLYPH_REWIND + level + "\n"
                + GLYPH_ORB + GLYPH_REWIND + xp + "/" + xpToNext + "\n"
                + GLYPH_BOTTOM;
    }
}
