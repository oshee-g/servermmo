package ru.servermmo.hud;

import java.util.Set;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import org.bukkit.entity.Player;
import ru.servermmo.core.ServerMMOCore;
import ru.servermmo.core.player.PlayerProfile;

/**
 * Панель HUD одного игрока: две строки, нарисованные клиентом.
 *
 * <p>Панель — это не сущность в мире, а две строки босс-бара. Клиент рисует
 * их сам в верхней части экрана, поэтому панель не привязана к камере, не
 * уезжает при повороте и не может «застрять» в блоке. Значки, цифры и буквы —
 * глифы шрифта {@code servermmo:hud} из ресурспака, то есть рисует их тоже
 * клиент.</p>
 *
 * <p>Полоса босс-бара гасится прозрачными спрайтами {@code white_*} в пакете:
 * видны только строки. Белый цвет в ванили не используется, поэтому полосы
 * Хранителя, Дракона и рейда остаются как есть.</p>
 *
 * <p>Строки центрируются клиентом, поэтому положение панели задаётся сдвигом
 * в пикселях ({@code /hud offsetx}). Сдвиг пересчитывается после смены
 * настроек — см. {@link HudManager#reset(Player)}.</p>
 */
final class PlayerHud {

    /** Шрифт ресурспака: значки, цифры и буквы панели. */
    private static final Style HUD_STYLE = Style.style()
            .font(Key.key("servermmo", "hud"))
            .color(NamedTextColor.WHITE)
            .build();

    private final HudSettings settings;
    private final HudText text;
    private final Player player;
    private final BossBar first;
    private final BossBar second;

    private boolean visible;
    private String lastFirst;
    private String lastSecond;

    /**
     * Поставил ли клиент серверный ресурспак. Пока пак не загружен, игрок не
     * должен видеть ничего нашего: без пака полосы босс-баров белые, а вместо
     * глифов — пустые квадратики. Поэтому и босс-бары, и строка хотбара
     * показываются только после SUCCESSFULLY_LOADED.
     */
    private boolean packLoaded;
    private boolean actionBarCleared;

    /** Последний полученный урон и когда он случился (мс). */
    private long lastDamage;
    private long lastDamageAt;
    private String lastActionBar;
    private long ticksSinceActionBar;

    PlayerHud(HudSettings settings, HudText text, Player player) {
        this.settings = settings;
        this.text = text;
        this.player = player;
        this.first = newBar();
        this.second = newBar();
    }

    private static BossBar newBar() {
        return BossBar.bossBar(Component.empty(), 0.0F, BossBar.Color.WHITE,
                BossBar.Overlay.PROGRESS, Set.of());
    }

    Player player() {
        return player;
    }

    /** Статус ресурспака у этого игрока: загружен — можно показывать HUD. */
    void packLoaded(boolean loaded) {
        if (this.packLoaded == loaded) {
            return;
        }
        this.packLoaded = loaded;
        if (!loaded) {
            hide();
            clearActionBar();
        } else {
            invalidate();
            lastActionBar = null; // строку хотбара перешлём в ближайшем тике
        }
    }

    boolean packLoaded() {
        return packLoaded;
    }

    /** Игрок получил урон: индикатор «−N♥» вспыхивает заново. */
    void recordDamage(long amount) {
        if (amount <= 0L) {
            return;
        }
        this.lastDamage = amount;
        this.lastDamageAt = System.currentTimeMillis();
    }

    /** Показать панель (только если ресурспак загружен). */
    void show() {
        if (!visible && packLoaded) {
            player.showBossBar(first);
            player.showBossBar(second);
            visible = true;
        }
    }

    /** Убрать панель (выход, выключение плагина, нет ресурспака). */
    void hide() {
        if (visible) {
            player.hideBossBar(first);
            player.hideBossBar(second);
            visible = false;
        }
    }

    private void clearActionBar() {
        if (!actionBarCleared && player.isOnline()) {
            player.sendActionBar(Component.empty());
            actionBarCleared = true;
        }
    }

    /** Сбросить кэш текста: после смены настроек строки пересоберутся. */
    void invalidate() {
        lastFirst = null;
        lastSecond = null;
    }

    void tick() {
        if (!player.isOnline()) {
            hide();
            return;
        }
        if (!packLoaded) {
            // Без ресурспака не показываем ничего: ни полосы босс-баров, ни
            // строку хотбара — иначе игрок увидит белые полосы и квадратики.
            hide();
            clearActionBar();
            return;
        }
        show();

        long health = 0L;
        long damage = 0L;
        long level = 1L;
        long xp = 0L;
        long xpToNext = 1L;
        ServerMMOCore core = ServerMMOCore.get();
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

        String[] lines = text.panel(damage, level, xp, xpToNext, settings.offsetX());
        String firstLine = lines[0];
        String secondLine = lines[1];
        if (!firstLine.equals(lastFirst)) {
            first.name(Component.text(firstLine, HUD_STYLE));
            lastFirst = firstLine;
        }
        if (!secondLine.equals(lastSecond)) {
            second.name(Component.text(secondLine, HUD_STYLE));
            lastSecond = secondLine;
        }

        tickActionBar(health);
    }

    /**
     * Строка хотбара: сердце и здоровье над хотбаром (там, где были ванильные
     * сердца), справа — индикатор полученного урона. Он висит в полной
     * яркости {@code damage-hold-seconds}, затем ступенями затухает и
     * пропадает; новый урон вспыхивает его заново.
     */
    private void tickActionBar(long health) {
        int fadeStep = -1;
        if (lastDamageAt > 0L) {
            long elapsed = System.currentTimeMillis() - lastDamageAt;
            long hold = settings.damageHoldMillis();
            long fade = settings.damageFadeMillis();
            if (elapsed < hold) {
                fadeStep = 0;
            } else if (elapsed < hold + fade) {
                long into = elapsed - hold;
                fadeStep = 1 + (int) (into * (HudText.FADE_STEPS - 1) / fade);
                if (fadeStep >= HudText.FADE_STEPS) {
                    fadeStep = -1;
                }
            }
        }
        String line = text.hotbar(health, lastDamage, fadeStep, settings.hotbarAnchorX());
        ticksSinceActionBar++;
        // Экшн-бар сам гаснет примерно за 3 секунды: пересылаем раз в секунду,
        // даже если строка не менялась.
        if (!line.equals(lastActionBar) || ticksSinceActionBar >= 10L) {
            player.sendActionBar(Component.text(line, HUD_STYLE));
            lastActionBar = line;
            ticksSinceActionBar = 0L;
            actionBarCleared = false;
        }
    }
}
