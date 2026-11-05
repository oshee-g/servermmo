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
 * HUD одного игрока: табличка перед камерой со значками сердца, меча,
 * «LVL» и «XP» из ресурспака и числами рядом.
 *
 * <p>Почему так, а не иначе:</p>
 * <ul>
 *   <li>{@link Display.Billboard#CENTER} — табличка всегда разворачивается к
 *       камере. С {@code FIXED} текст смотрел в ту же сторону, что и игрок,
 *       то есть к игроку был повёрнут обратной стороной, и панель не была
 *       видна вообще;</li>
 *   <li>положение считается векторами камеры (вперёд / вправо / вверх), а не
 *       через {@code Transformation}: порядок применения translate и поворота
 *       billboard в клиенте не документирован, и смещение могло уйти в мировые
 *       оси. Векторы дают предсказуемый угол экрана при любом взгляде;</li>
 *   <li>яркость выставляется максимальной, иначе ночью HUD темнеет вместе
 *       с миром;</li>
 *   <li>выравнивание по левому краю — цифры разной длины не «прыгают».</li>
 * </ul>
 */
final class PlayerHud {

    /** Сердце (здоровье). */
    private static final String GLYPH_HEART = "\uE010";
    /** Меч (базовый урон). */
    private static final String GLYPH_SWORD = "\uE011";
    /** Пустой глиф-разделитель шириной 4 пикселя (обычный пробел пака — 1 px). */
    private static final String GLYPH_SPACER = "\uE101";

    /** Мировая ось «вверх» — нужна только для вычисления осей экрана. */
    private static final Vector WORLD_UP = new Vector(0.0D, 1.0D, 0.0D);

    /** Шрифт ресурспака с иконками и цифрами. */
    private static final Style HUD_STYLE = Style.style()
            .font(Key.key("servermmo", "hud"))
            .color(NamedTextColor.WHITE)
            .build();

    private final ServerMMOHud plugin;
    private final HudSettings settings;
    private final Player player;

    private TextDisplay display;
    private String lastText;
    private Location lastLocation;

    PlayerHud(ServerMMOHud plugin, HudSettings settings, Player player) {
        this.plugin = plugin;
        this.settings = settings;
        this.player = player;
    }

    Player player() {
        return player;
    }

    /** Текстовая сущность (для {@code /hud debug}). */
    TextDisplay display() {
        return display;
    }

    /** Пересоздаёт сущность (например, после смены мира). */
    void reset() {
        if (display != null && display.isValid()) {
            display.remove();
        }
        display = null;
        lastText = null;
        lastLocation = null;
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
        Location target = computeLocation();
        // Телепорт каждый тик — это пакет каждый тик. Если игрок стоит на
        // месте, панель и так не двигается, поэтому шлём только изменения.
        if (lastLocation == null || !samePlace(lastLocation, target)) {
            current.teleport(target);
            lastLocation = target;
        }
        Component text = buildText();
        String flat = plain(text);
        if (!flat.equals(lastText)) {
            current.text(text);
            lastText = flat;
        }
    }

    /** Позиция и направление взгляда не изменились заметно. */
    private static boolean samePlace(Location a, Location b) {
        return a.getWorld() == b.getWorld()
                && a.distanceSquared(b) < 1.0E-6D
                && a.getDirection().distanceSquared(b.getDirection()) < 1.0E-8D;
    }

    private TextDisplay ensureDisplay() {
        if (display != null && display.isValid() && display.getWorld().equals(player.getWorld())) {
            return display;
        }
        reset();
        Location spawn = computeLocation();
        TextDisplay created = player.getWorld().spawn(spawn, TextDisplay.class, entity -> {
            entity.setVisibleByDefault(false);
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setAlignment(TextDisplay.TextAlignment.LEFT);
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
            // HUD не должен темнеть ночью вместе с миром.
            entity.setBrightness(new Display.Brightness(15, 15));
            // Смещение уже зашито в позицию сущности, поэтому здесь только
            // масштаб: текст растягивается вокруг своего левого верхнего угла.
            entity.setTransformation(new Transformation(
                    new Vector3f(),
                    new Quaternionf(),
                    new Vector3f(settings.scale(), settings.scale(), settings.scale()),
                    new Quaternionf()));
        });
        player.showEntity(plugin, created);
        display = created;
        lastLocation = spawn;
        return created;
    }

    /**
     * Левый верхний угол панели: {@code distance} блоков вперёд по взгляду,
     * плюс смещение по осям «вправо» и «вверх» экрана.
     *
     * <p>Оси берутся из вектора взгляда: {@code right = forward x up},
     * {@code up = right x forward}. Так смещение всегда читается как «на
     * экране», как бы игрок ни повернулся.</p>
     */
    private Location computeLocation() {
        Location eye = player.getEyeLocation();
        Vector forward = eye.getDirection().normalize();
        Vector right = forward.clone().crossProduct(WORLD_UP);
        if (right.lengthSquared() < 1.0E-6D) {
            // Смотрим строго вниз или вверх: ось «вправо» вырождается.
            right = new Vector(1.0D, 0.0D, 0.0D);
            if (Math.abs(forward.getX()) > 0.9D) {
                right = new Vector(0.0D, 0.0D, -1.0D);
            }
        }
        right.normalize();
        Vector up = right.clone().crossProduct(forward).normalize();
        Vector position = eye.toVector()
                .add(forward.clone().multiply(settings.distance()))
                .add(right.multiply(settings.offsetX()))
                .add(up.multiply(settings.offsetY()));
        Location location = position.toLocation(eye.getWorld());
        location.setDirection(forward);
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
                .append(row("LVL", Long.toString(level)))
                .append(Component.newline())
                .append(row("XP", xp + "/" + xpToNext))
                .build();
    }

    private Component row(String icon, String value) {
        return Component.text()
                .append(Component.text(icon, HUD_STYLE))
                .append(Component.text(GLYPH_SPACER, HUD_STYLE))
                .append(Component.text(value, HUD_STYLE))
                .build();
    }

    private static String plain(Component component) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(component);
    }
}
