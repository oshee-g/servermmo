package ru.servermmo.hud;

import java.util.Arrays;
import java.util.Set;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
 * видны только строки. Ресурспак раздаёт сам сервер строкой
 * {@code resource-pack} в {@code server.properties}; плагин про пак ничего не
 * знает и за его загрузкой не следит.</p> Белый цвет в ванили не используется, поэтому полосы
 * Хранителя, Дракона и рейда остаются как есть.</p>
 *
 * <p>Строки центрируются клиентом, поэтому положение панели зависит от
 * ширины интерфейса, а та — от разрешения окна и масштаба GUI. Плагин
 * считает сдвиг сам: игрок один раз называет свой масштаб интерфейса
 * ({@code /hud scale}), а отступ панели от края экрана остаётся тем же при
 * любом масштабе — см. {@link HudLayout}.</p>
 */
final class PlayerHud {

    private final HudSettings settings;
    private final HudText text;
    private final HudStore store;
    private final Player player;
    /** Три строки панели: подложка+ник+уровень, клан, валюты. */
    private final BossBar[] bars = new BossBar[3];

    private boolean visible;
    private final Component[] lastLines = new Component[3];

    private boolean actionBarCleared;

    /** Последний полученный урон и когда он случился (мс). */
    private long lastDamage;
    private long lastDamageAt;
    private Component lastActionBar2;
    private long ticksSinceActionBar;
    /** Время окончания визуального 30-секундного отката способности агрессии. */
    private long aggressionCooldownUntil;

    PlayerHud(HudSettings settings, HudText text, HudStore store, Player player) {
        this.settings = settings;
        this.text = text;
        this.store = store;
        this.player = player;
        for (int i = 0; i < bars.length; i++) {
            this.bars[i] = newBar();
        }
    }

    private static BossBar newBar() {
        return BossBar.bossBar(Component.empty(), 0.0F, BossBar.Color.WHITE,
                BossBar.Overlay.PROGRESS, Set.of());
    }

    Player player() {
        return player;
    }

    /** Краткая сводка состояния HUD для /hud debug. */
    String debugLine() {
        HudLayout layout = settings.layout(store.get(player.getUniqueId()));
        return "панель " + (visible ? "показана" : "скрыта")
                + ", интерфейс " + layout.interfaceWidth() + " px"
                + ", отступ от края " + layout.marginLeft() + " px"
                + ", сдвиг " + layout.panelShift(text.panelWidth(
                        store.get(player.getUniqueId()).classIndex())) + " px"
                + ", anchor-x=" + settings.hotbarAnchorX();
    }

    /** Игрок получил урон: индикатор «−N♥» вспыхивает заново. */
    void recordDamage(long amount) {
        if (amount <= 0L) {
            return;
        }
        this.lastDamage = amount;
        this.lastDamageAt = System.currentTimeMillis();
    }

    /** Запускает визуальный откат способности, если предыдущий уже завершился. */
    void activateAggression() {
        long now = System.currentTimeMillis();
        if (aggressionCooldownUntil <= now) {
            aggressionCooldownUntil = now + 30_000L;
            lastActionBar2 = null;
        }
    }

    private int aggressionSecondsRemaining() {
        long remaining = aggressionCooldownUntil - System.currentTimeMillis();
        return remaining <= 0L ? 0 : (int) ((remaining + 999L) / 1_000L);
    }

    /** Показать панель (только если ресурспак загружен). */
    void show() {
        if (!visible) {
            for (BossBar bar : bars) {
                player.showBossBar(bar);
            }
            visible = true;
        }
    }

    /** Убрать панель (выход игрока, выключение плагина). */
    void hide() {
        if (visible) {
            for (BossBar bar : bars) {
                player.hideBossBar(bar);
            }
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
        java.util.Arrays.fill(lastLines, null);
    }

    void tick() {
        if (!player.isOnline()) {
            hide();
            return;
        }
        show();

        long health = 0L;
        long maxHealth = 10L;
        long energy = 100L;
        long maxEnergy = 100L;
        long damage = 0L;
        long level = 1L;
        long xp = 0L;
        long xpToNext = 1L;
        ServerMMOCore core = ServerMMOCore.get();
        if (core != null) {
            PlayerProfile profile = core.players().get(player.getUniqueId());
            if (profile != null) {
                health = profile.health();
                maxHealth = profile.maxHealth();
                energy = profile.energy();
                maxEnergy = profile.maxEnergy();
                damage = profile.damage();
                level = profile.level();
                xp = profile.xp();
                xpToNext = Math.max(1L, profile.xpToNext());
            }
        }

        HudProfile profile = store.get(player.getUniqueId());
        Component[] lines = text.panel(profile, player.getName(), level, xp, xpToNext,
                settings.layout(profile), settings.panelOffsetY(), settings.textOffsetY(),
                settings.textOffsetX());
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].equals(lastLines[i])) {
                bars[i].name(lines[i]);
                lastLines[i] = lines[i];
            }
        }

        tickActionBar(health, maxHealth, energy, maxEnergy, damage, profile.armor());
    }

    /**
     * Строка хотбара: сердце и здоровье над хотбаром (там, где были ванильные
     * сердца), справа — индикатор полученного урона. Он висит в полной
     * яркости {@code damage-hold-seconds}, затем ступенями затухает и
     * пропадает; новый урон вспыхивает его заново.
     */
    private void tickActionBar(long health, long maxHealth, long energy, long maxEnergy,
                               long damage, long armor) {
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
        int aggressionTimer = aggressionSecondsRemaining();
        Component line = text.hotbar(health, maxHealth, energy, maxEnergy, damage, armor,
                lastDamage, fadeStep, aggressionTimer,
                settings.hotbarAnchorX(), settings.abilitySlots(),
                settings.layout(store.get(player.getUniqueId())),
                settings.hotbarLeftOffsetX(), settings.hotbarLeftOffsetY(),
                settings.hotbarRightOffsetX(), settings.hotbarRightOffsetY(),
                settings.hotbarCenterOffsetX(), settings.hotbarCenterOffsetY());
        ticksSinceActionBar++;
        // Экшн-бар сам гаснет примерно за 3 секунды: пересылаем раз в секунду,
        // даже если строка не менялась.
        if (!line.equals(lastActionBar2) || ticksSinceActionBar >= 10L) {
            player.sendActionBar(line);
            lastActionBar2 = line;
            ticksSinceActionBar = 0L;
            actionBarCleared = false;
        }
    }
}
