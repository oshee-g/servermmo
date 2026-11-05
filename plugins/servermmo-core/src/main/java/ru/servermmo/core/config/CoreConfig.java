package ru.servermmo.core.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Настройки ядра из {@code config.yml}. */
public final class CoreConfig {

    private final double environmentDamageScale;
    private final boolean stripVanillaStats;
    private final boolean mobDeathSound;
    private final boolean mobDeathAnimation;
    private final boolean hurtAnimation;
    private final long saveIntervalSeconds;
    private final boolean debug;
    private final boolean packServe;
    private final int packPort;
    private final String packFile;
    private final String packBind;
    private final String packPublicUrl;

    private CoreConfig(double environmentDamageScale, boolean stripVanillaStats, boolean mobDeathSound,
                       boolean mobDeathAnimation, boolean hurtAnimation, long saveIntervalSeconds,
                       boolean debug, boolean packServe, int packPort, String packFile, String packBind,
                       String packPublicUrl) {
        this.environmentDamageScale = environmentDamageScale;
        this.stripVanillaStats = stripVanillaStats;
        this.mobDeathSound = mobDeathSound;
        this.mobDeathAnimation = mobDeathAnimation;
        this.hurtAnimation = hurtAnimation;
        this.saveIntervalSeconds = saveIntervalSeconds;
        this.debug = debug;
        this.packServe = packServe;
        this.packPort = packPort;
        this.packFile = packFile;
        this.packBind = packBind;
        this.packPublicUrl = packPublicUrl;
    }

    public static CoreConfig load(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        double scale = config.getDouble("combat.environment-damage-scale", 1.0D);
        if (scale <= 0.0D) {
            plugin.getLogger().warning("combat.environment-damage-scale <= 0, ставлю 1.0");
            scale = 1.0D;
        }
        long saveSeconds = config.getLong("storage.save-interval-seconds", 300L);
        if (saveSeconds < 10L) {
            plugin.getLogger().warning("storage.save-interval-seconds < 10, ставлю 60");
            saveSeconds = 60L;
        }
        String packFile = config.getString("resourcepack.file", "resourcepack/servermmo-hud.zip");
        if (packFile == null || packFile.isBlank()) {
            packFile = "resourcepack/servermmo-hud.zip";
        }
        String packBind = config.getString("resourcepack.bind", "0.0.0.0");
        if (packBind == null || packBind.isBlank()) {
            packBind = "0.0.0.0";
        }
        String packUrl = config.getString("resourcepack.public-url", "");
        if (packUrl == null) {
            packUrl = "";
        }
        int packPort = config.getInt("resourcepack.port", 8080);
        if (packPort < 1 || packPort > 65535) {
            plugin.getLogger().warning("resourcepack.port=" + packPort + " вне диапазона 1-65535, ставлю 8080");
            packPort = 8080;
        }
        return new CoreConfig(
                scale,
                config.getBoolean("combat.strip-vanilla-stats", true),
                config.getBoolean("combat.mob-death-sound", true),
                config.getBoolean("combat.mob-death-animation", false),
                config.getBoolean("combat.hurt-animation", true),
                saveSeconds,
                config.getBoolean("debug", false),
                config.getBoolean("resourcepack.serve", true),
                packPort,
                packFile,
                packBind,
                packUrl);
    }

    /** Во сколько кастомных единиц превращается 1.0 ванильного урона (падение, лава и т.п.). */
    public double environmentDamageScale() {
        return environmentDamageScale;
    }

    /** Убирать ли ванильные атрибуты здоровья/урона/брони. */
    public boolean stripVanillaStats() {
        return stripVanillaStats;
    }

    /** Проигрывать ли звук смерти моба. */
    public boolean mobDeathSound() {
        return mobDeathSound;
    }

    /** Проигрывать ли ванильную анимацию смерти (по умолчанию выключено). */
    public boolean mobDeathAnimation() {
        return mobDeathAnimation;
    }

    /** Проигрывать ли покраснение при получении урона. */
    public boolean hurtAnimation() {
        return hurtAnimation;
    }

    public long saveIntervalSeconds() {
        return saveIntervalSeconds;
    }

    public boolean debug() {
        return debug;
    }

    /** Отдавать ли ресурспак самим сервером (второе окно не нужно). */
    public boolean packServe() {
        return packServe;
    }

    /** Порт раздачи ресурспака. */
    public int packPort() {
        return packPort;
    }

    /** Путь к zip относительно папки сервера. */
    public String packFile() {
        return packFile;
    }

    /** Адрес, на котором слушать ({@code 0.0.0.0} — все интерфейсы). */
    public String packBind() {
        return packBind;
    }

    /** Внешний адрес пака, если сервер стоит за NAT/прокси. Пусто — определить самому. */
    public String packPublicUrl() {
        return packPublicUrl;
    }
}
