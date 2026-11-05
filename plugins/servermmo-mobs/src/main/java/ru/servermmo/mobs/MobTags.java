package ru.servermmo.mobs;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.scheduler.BukkitRunnable;
import ru.servermmo.core.ServerMMOCore;
import ru.servermmo.core.stats.LevelStats;

/**
 * Подпись над кастомным мобом: {@code Ур 1 Курица 20 <сердце>}.
 *
 * <p>Иконка щита (уровень) и сердечко — глифы шрифта {@code servermmo:hud}
 * из ресурспака, поэтому подпись выглядит как в макете. Если пак не
 * установлен, вместо иконок будут пустые квадратики — текст останется
 * читаемым.</p>
 *
 * <p>Подпись обновляется при изменении здоровья, чтобы цифры не врали.</p>
 */
final class MobTags extends BukkitRunnable {

    /** Щит: обозначает уровень. */
    private static final String GLYPH_LEVEL = "\uE012";
    /** Сердце: обозначает здоровье. */
    private static final String GLYPH_HEART = "\uE010";

    private static final Style ICON_STYLE = Style.style()
            .font(Key.key("servermmo", "hud"))
            .color(NamedTextColor.WHITE)
            .build();

    private final ServerMMOMobs plugin;
    private final ServerMMOCore core;
    private final Map<UUID, String> lastTag = new HashMap<>();

    MobTags(ServerMMOMobs plugin, ServerMMOCore core) {
        this.plugin = plugin;
        this.core = core;
    }

    /** Ставит подпись сразу после спавна. */
    void apply(LivingEntity entity) {
        if (!(entity instanceof Mob mob) || !core.combat().isCustom(mob)) {
            return;
        }
        mob.setCustomNameVisible(true);
        update(mob, true);
    }

    @Override
    public void run() {
        for (var world : plugin.getServer().getWorlds()) {
            for (LivingEntity living : world.getLivingEntities()) {
                if (!(living instanceof Mob mob) || !core.combat().isCustom(mob)) {
                    continue;
                }
                if (!mob.isValid()) {
                    lastTag.remove(mob.getUniqueId());
                    continue;
                }
                update(mob, false);
            }
        }
    }

    private void update(Mob mob, boolean force) {
        Component tag = build(mob);
        String flat = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(tag)
                + "|" + core.combat().health(mob);
        String previous = lastTag.put(mob.getUniqueId(), flat);
        if (!force && flat.equals(previous)) {
            return;
        }
        mob.customName(tag);
        if (!mob.isCustomNameVisible()) {
            mob.setCustomNameVisible(true);
        }
    }

    private Component build(Mob mob) {
        int level = core.combat().mobLevel(mob);
        LevelStats stats = core.levels().byLevel(level);
        String name = stats != null ? stats.mobName() : niceName(mob);
        long health = core.combat().health(mob);

        return Component.text()
                .append(Component.text("Ур ", NamedTextColor.YELLOW))
                .append(Component.text(GLYPH_LEVEL, ICON_STYLE))
                .append(Component.text(" " + level + " ", NamedTextColor.YELLOW))
                .append(Component.text(name, NamedTextColor.WHITE))
                .append(Component.text(" ", NamedTextColor.WHITE))
                .append(Component.text(GLYPH_HEART, ICON_STYLE))
                .append(Component.text(" " + health, NamedTextColor.RED))
                .build();
    }

    /** Имя по типу, если уровня нет в таблице. */
    private static String niceName(Mob mob) {
        String key = mob.getType().getKey().getKey().replace('_', ' ');
        return Character.toUpperCase(key.charAt(0)) + key.substring(1);
    }
}
