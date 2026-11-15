package ru.servermmo.hud;

import org.bukkit.plugin.java.JavaPlugin;

/** Настройки HUD из {@code config.yml}. */
public final class HudSettings {

    /**
     * Заводской сдвиг панели в пикселях интерфейса.
     *
     * <p>Строка босс-бара центрируется клиентом, поэтому «левый верхний угол» —
     * это сдвиг влево примерно на половину ширины интерфейса. Ширина интерфейса
     * зависит от разрешения окна и масштаба GUI ({@code Настройки → Графика →
     * Масштаб интерфейса}), который сервер узнать не может. -384 подобрано под
     * GUI scale 4 на 1920×1080 (ширина интерфейса 480 px, панель ≈ 80 px);
     * если у вас другой масштаб, поправьте командой {@code /hud offsetx}.</p>
     */
    public static final int DEFAULT_OFFSET_X = -384;

    /**
     * Заводской якорь строки хотбара: хотбар шириной 182 px, его левый край
     * на 91 px левее центра экрана.
     */
    public static final int DEFAULT_HOTBAR_ANCHOR_X = -91;

    /**
     * Готовые пары «сдвиг панели + якорь хотбара» под масштаб интерфейса.
     *
     * <p>Сервер не знает ни разрешения окна, ни масштаба GUI, поэтому
     * подобрать сдвиг автоматически нельзя: при чужом масштабе панель
     * уезжает за левый край и кажется, что HUD вообще не работает. Пресеты
     * закрывают три самых ходовых масштаба, а точная подгонка — командами
     * {@code /hud offsetx} и {@code /hud anchorx}.</p>
     */
    private static final int[][] SCALE_PRESETS = {
            {3, -544, -129},   // scale 3: интерфейс шире, сдвиг больше
            {4, -384, -91},    // scale 4 (заводской)
            {5, -288, -68},    // scale 5: интерфейс уже, сдвиг меньше
    };

    private int offsetX;
    private long updateIntervalTicks;
    private int hotbarAnchorX;
    private long damageHoldMillis;
    private long damageFadeMillis;

    private HudSettings() {
    }

    public static HudSettings load(JavaPlugin plugin) {
        var config = plugin.getConfig();
        HudSettings settings = new HudSettings();
        settings.offsetX = config.getInt("hud.offset-x", DEFAULT_OFFSET_X);
        settings.updateIntervalTicks = Math.max(1L, config.getLong("hud.update-interval-ticks", 2L));
        // левый край хотбара: хотбар 182 px wide, его левый край — центр-91
        settings.hotbarAnchorX = config.getInt("hud.hotbar-anchor-x", DEFAULT_HOTBAR_ANCHOR_X);
        settings.damageHoldMillis = Math.max(0L, config.getLong("hud.damage-hold-seconds", 5L)) * 1000L;
        settings.damageFadeMillis = Math.max(200L, config.getLong("hud.damage-fade-seconds", 2L)) * 1000L;
        if (settings.offsetX > 0) {
            plugin.getLogger().warning("hud.offset-x > 0 уводит панель вправо от центра");
        }
        return settings;
    }

    /** Сдвиг панели по горизонтали, в пикселях интерфейса. */
    public int offsetX() {
        return offsetX;
    }

    public void offsetX(int value) {
        this.offsetX = value;
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
     * Подобрать оба отступа под масштаб интерфейса.
     *
     * @return {@code true}, если для такого масштаба есть готовые значения
     */
    public boolean applyScalePreset(int scale) {
        for (int[] preset : SCALE_PRESETS) {
            if (preset[0] == scale) {
                this.offsetX = preset[1];
                this.hotbarAnchorX = preset[2];
                return true;
            }
        }
        return false;
    }

    /** Масштабы интерфейса, под которые есть готовые отступы. */
    public static int[] presetScales() {
        int[] scales = new int[SCALE_PRESETS.length];
        for (int i = 0; i < SCALE_PRESETS.length; i++) {
            scales[i] = SCALE_PRESETS[i][0];
        }
        return scales;
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
        this.offsetX = DEFAULT_OFFSET_X;
        this.hotbarAnchorX = DEFAULT_HOTBAR_ANCHOR_X;
    }

    /** Сдвиг панели из пресета для масштаба интерфейса (для подсказок). */
    public static int presetOffsetX(int scale) {
        for (int[] preset : SCALE_PRESETS) {
            if (preset[0] == scale) {
                return preset[1];
            }
        }
        return DEFAULT_OFFSET_X;
    }
}
