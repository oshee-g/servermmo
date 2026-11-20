package ru.servermmo.hud;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Настройки HUD из {@code config.yml}.
 *
 * <p>Главное здесь — не «сдвиг панели», а три величины, из которых он
 * считается: <b>разрешение окна</b>, <b>масштаб интерфейса</b> и
 * <b>отступ панели от левого края экрана</b>. Два последних сервер узнать не
 * может — клиент их не присылает (проверено по исходникам 26.3: в
 * {@code ClientInformation} только язык, обзор, чат, руки и частицы) —
 * поэтому их вводит игрок один раз командой {@code /hud scale}, а плагин
 * пересчитывает отступы под любой масштаб.</p>
 *
 * <p>Отступ от края экрана ({@code margin-left}) от масштаба не зависит:
 * панель стоит в углу одинаково и при масштабе 2, и при масштабе 5. Сам же
 * сдвиг строки ({@code offset-x}) в пикселях интерфейса при смене масштаба
 * меняется в разы — поэтому его и нельзя хранить: на чужом масштабе панель
 * уезжает за край и кажется, что HUD совсем не работает.</p>
 */
public final class HudSettings {

    /** Заводское разрешение окна игры, в реальных пикселях. */
    public static final int DEFAULT_SCREEN_WIDTH = 1920;
    public static final int DEFAULT_SCREEN_HEIGHT = 1080;

    /**
     * Заводской масштаб интерфейса.
     *
     * <p>0 — «Авто»: тогда масштаб считается по правилу клиента
     * (см. {@link #effectiveScale}) и для 1920×1080 получается 4.</p>
     */
    public static final int DEFAULT_GUI_SCALE = 0;

    /** Заводской отступ панели от левого края экрана, в пикселях интерфейса. */
    public static final int DEFAULT_MARGIN_LEFT = 8;

    /**
     * Заводской якорь строки хотбара: хотбар шириной 182 px, его левый край
     * на 91 px левее центра экрана. Величина от масштаба не зависит — хотбар
     * всегда по центру и всегда одной ширины.
     */
    public static final int DEFAULT_HOTBAR_ANCHOR_X = -91;

    /**
     * Минимальный размер интерфейса, до которого клиент крутит «Авто».
     *
     * <p>Ровно константы из {@code Window.calculateScale}: масштаб растёт,
     * пока интерфейс не станет меньше 320×240. Меньше клиент не даст, даже
     * если в настройках стоит 8.</p>
     */
    private static final int MIN_SCALED_WIDTH = 320;
    private static final int MIN_SCALED_HEIGHT = 240;

    private int screenWidth;
    private int screenHeight;
    private int guiScale;
    private int marginLeft;
    private int panelOffsetY;
    private int textOffsetY;
    private long updateIntervalTicks;
    private int hotbarAnchorX;
    private long damageHoldMillis;
    private long damageFadeMillis;
    private int abilitySlots;

    private HudSettings() {
    }

    public static HudSettings load(JavaPlugin plugin) {
        var config = plugin.getConfig();
        HudSettings settings = new HudSettings();
        settings.screenWidth = Math.max(MIN_SCALED_WIDTH,
                config.getInt("hud.screen-width", DEFAULT_SCREEN_WIDTH));
        settings.screenHeight = Math.max(MIN_SCALED_HEIGHT,
                config.getInt("hud.screen-height", DEFAULT_SCREEN_HEIGHT));
        settings.guiScale = Math.max(0, config.getInt("hud.gui-scale", DEFAULT_GUI_SCALE));
        settings.marginLeft = clampMargin(config.getInt("hud.margin-left", DEFAULT_MARGIN_LEFT));
        settings.panelOffsetY = clampOffsetY(config.getInt("hud.panel-offset-y", 0));
        settings.textOffsetY = clampTextY(config.getInt("hud.text-offset-y", 0));
        settings.updateIntervalTicks = Math.max(1L, config.getLong("hud.update-interval-ticks", 2L));
        settings.hotbarAnchorX = config.getInt("hud.hotbar-anchor-x", DEFAULT_HOTBAR_ANCHOR_X);
        settings.damageHoldMillis = Math.max(0L, config.getLong("hud.damage-hold-seconds", 5L)) * 1000L;
        settings.damageFadeMillis = Math.max(200L, config.getLong("hud.damage-fade-seconds", 2L)) * 1000L;
        settings.abilitySlots = Math.max(0, Math.min(8, config.getInt("hud.ability-slots", 4)));
        return settings;
    }

    /** Разрешение окна игры по горизонтали, в реальных пикселях. */
    public int screenWidth() {
        return screenWidth;
    }

    /** Разрешение окна игры по вертикали, в реальных пикселях. */
    public int screenHeight() {
        return screenHeight;
    }

    /**
     * Масштаб интерфейса: что стоит в {@code Настройки → Видео → Масштаб
     * интерфейса}. 0 — «Авто».
     */
    public int guiScale() {
        return guiScale;
    }

    /** Отступ левого края панели от левого края экрана, в пикселях интерфейса. */
    public int marginLeft() {
        return marginLeft;
    }

    /**
     * Сдвиг текста и значков панели вниз относительно её картинки,
     * в пикселях интерфейса (-15..15, команда {@code /hud offsety}).
     */
    public int panelOffsetY() {
        return panelOffsetY;
    }

    /**
     * Сдвиг текста панели вниз относительно значков, в пикселях
     * интерфейса (-5..5, команда {@code /hud texty}; двигаются значки).
     */
    public int textOffsetY() {
        return textOffsetY;
    }

    public void screenWidth(int value) {
        this.screenWidth = Math.max(MIN_SCALED_WIDTH, value);
    }

    public void screenHeight(int value) {
        this.screenHeight = Math.max(MIN_SCALED_HEIGHT, value);
    }

    public void guiScale(int value) {
        this.guiScale = Math.max(0, value);
    }

    public void marginLeft(int value) {
        this.marginLeft = clampMargin(value);
    }

    public void panelOffsetY(int value) {
        this.panelOffsetY = clampOffsetY(value);
    }

    public void textOffsetY(int value) {
        this.textOffsetY = clampTextY(value);
    }

    public long updateIntervalTicks() {
        return updateIntervalTicks;
    }

    /** Левый край строки хотбара относительно центра экрана, px. */
    public int hotbarAnchorX() {
        return hotbarAnchorX;
    }

    public void anchorX(int value) {
        this.hotbarAnchorX = value;
    }

    /**
     * Масштаб интерфейса, который реально применит клиент.
     *
     * <p>Повторяет {@code Window.calculateScale}: масштаб растёт от 1, пока
     * интерфейс остаётся не меньше 320×240, но не больше запрошенного.
     * Запрошенный 0 означает «Авто» — тогда предел только по размеру. Если
     * игрок выставил масштаб 8 на маленьком окне, клиент всё равно нарисует
     * так, что интерфейс влезет в 320×240, — здесь считается так же, иначе
     * панель уедет.</p>
     */
    public static int effectiveScale(int requested, int width, int height) {
        int scale = 1;
        while (scale != requested
                && scale < width && scale < height
                && width / (scale + 1) >= MIN_SCALED_WIDTH
                && height / (scale + 1) >= MIN_SCALED_HEIGHT) {
            scale++;
        }
        return Math.max(1, scale);
    }

    /**
     * Ширина интерфейса в пикселях GUI — ровно то, что клиент называет
     * {@code Window.getGuiScaledWidth()} и от чего центрует строки.
     */
    public static int interfaceWidth(int requestedScale, int width, int height) {
        int scale = effectiveScale(requestedScale, width, height);
        return (int) Math.ceil((double) width / (double) scale);
    }

    /** Ширина интерфейса для общих настроек сервера. */
    public int interfaceWidth() {
        return interfaceWidth(guiScale, screenWidth, screenHeight);
    }

    /**
     * Геометрия панели под экран игрока.
     *
     * <p>У игрока могут быть свои масштаб и разрешение (в профиле, командами
     * {@code /hud scale} и {@code /hud screen}); чего в профиле нет — берётся
     * из общих настроек сервера.</p>
     */
    public HudLayout layout(HudProfile profile) {
        int width = profile != null && profile.screenWidth() > 0 ? profile.screenWidth() : screenWidth;
        int height = profile != null && profile.screenHeight() > 0 ? profile.screenHeight() : screenHeight;
        int scale = profile != null && profile.guiScale() > 0 ? profile.guiScale() : guiScale;
        return new HudLayout(interfaceWidth(scale, width, height), marginLeft);
    }

    /** Сколько квадратов способностей рисовать справа от хотбара. */
    public int abilitySlots() {
        return abilitySlots;
    }

    /** Сколько индикатор урона висит в полной яркости, мс. */
    public long damageHoldMillis() {
        return damageHoldMillis;
    }

    /** За сколько индикатор урона затухает до невидимости, мс. */
    public long damageFadeMillis() {
        return damageFadeMillis;
    }

    /** Вернуть заводские значения. */
    public void reset() {
        this.screenWidth = DEFAULT_SCREEN_WIDTH;
        this.screenHeight = DEFAULT_SCREEN_HEIGHT;
        this.guiScale = DEFAULT_GUI_SCALE;
        this.marginLeft = DEFAULT_MARGIN_LEFT;
        this.panelOffsetY = 0;
        this.textOffsetY = 0;
        this.hotbarAnchorX = DEFAULT_HOTBAR_ANCHOR_X;
    }

    /**
     * Отступ от края экрана ограничиваем разумно: половины экрана влево
     * хватит за глаза, а упёршаяся в предел панель всё равно не влезет.
     */
    private static int clampMargin(int value) {
        return Math.max(-HudLayout.MAX_SHIFT, Math.min(HudLayout.MAX_SHIFT, value));
    }

    /** Сдвиг контента панели ограничиваем диапазоном вариантов глифа в паке. */
    private static int clampOffsetY(int value) {
        return Math.max(-HudText.PANEL_OFFSET_RANGE,
                Math.min(HudText.PANEL_OFFSET_RANGE, value));
    }

    /** Сдвиг текста ограничиваем диапазоном вариантов значков в паке. */
    private static int clampTextY(int value) {
        return Math.max(-HudText.ICON_OFFSET_RANGE,
                Math.min(HudText.ICON_OFFSET_RANGE, value));
    }
}
