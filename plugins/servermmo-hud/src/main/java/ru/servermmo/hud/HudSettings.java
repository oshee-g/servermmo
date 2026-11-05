package ru.servermmo.hud;

import org.bukkit.plugin.java.JavaPlugin;

/** Настройки HUD из {@code config.yml}. */
public final class HudSettings {

    private double distance;
    private double offsetX;
    private double offsetY;
    private float scale;
    private long updateIntervalTicks;
    private boolean seeThrough;
    private boolean resourcePackEnabled;
    private String resourcePackUrl;

    private HudSettings() {
    }

    public static HudSettings load(JavaPlugin plugin) {
        var config = plugin.getConfig();
        HudSettings settings = new HudSettings();
        settings.distance = config.getDouble("hud.distance", 1.0D);
        settings.offsetX = config.getDouble("hud.offset-x", -0.52D);
        settings.offsetY = config.getDouble("hud.offset-y", 0.32D);
        settings.scale = (float) config.getDouble("hud.scale", 0.27D);
        settings.updateIntervalTicks = Math.max(1L, config.getLong("hud.update-interval-ticks", 2L));
        settings.seeThrough = config.getBoolean("hud.see-through", true);
        settings.resourcePackEnabled = config.getBoolean("resource-pack.enabled", false);
        settings.resourcePackUrl = config.getString("resource-pack.url", "");
        if (settings.distance <= 0.0D) {
            plugin.getLogger().warning("hud.distance <= 0, ставлю 0.9");
            settings.distance = 0.9D;
        }
        if (settings.scale <= 0.0F) {
            plugin.getLogger().warning("hud.scale <= 0, ставлю 0.27");
            settings.scale = 0.27F;
        }
        return settings;
    }

    public double distance() {
        return distance;
    }

    public double offsetX() {
        return offsetX;
    }

    public double offsetY() {
        return offsetY;
    }

    public float scale() {
        return scale;
    }

    public long updateIntervalTicks() {
        return updateIntervalTicks;
    }

    public boolean seeThrough() {
        return seeThrough;
    }

    public boolean resourcePackEnabled() {
        return resourcePackEnabled && !resourcePackUrl.isBlank();
    }

    public String resourcePackUrl() {
        return resourcePackUrl;
    }

    public void scale(float newScale) {
        this.scale = newScale;
    }

    public void distance(double newDistance) {
        this.distance = newDistance;
    }

    public void offsetX(double value) {
        this.offsetX = value;
    }

    public void offsetY(double value) {
        this.offsetY = value;
    }
}
