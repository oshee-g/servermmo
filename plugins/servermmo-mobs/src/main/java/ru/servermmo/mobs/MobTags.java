package ru.servermmo.mobs;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.scheduler.BukkitRunnable;
import ru.servermmo.core.ServerMMOCore;
import ru.servermmo.core.stats.LevelStats;

/**
 * Подпись над кастомным мобом: {@code Ур <щит> 1 Курица <сердце> 11}.
 *
 * <p>Иконки щита и сердца и сами числа — глифы шрифта {@code servermmo:hud}
 * из ресурспака, поэтому подпись выглядит как единая пиксельная полоска, а
 * не как ванильный текст с картинками. Без пака вместо иконок будут пустые
 * квадратики, текст останется читаемым.</p>
 *
 * <p>Подпись обновляется при изменении здоровья. Перебираются только
 * кастомные мобы (они регистрируются при спавне и загрузке), а не все
 * сущности мира — иначе на большом сервере это был бы лишний скан.</p>
 */
final class MobTags extends BukkitRunnable {

    /** Щит: обозначает уровень. */
    private static final String GLYPH_LEVEL = "\uE012";
    /** Сердце: обозначает здоровье. */
    private static final String GLYPH_HEART = "\uE010";

    private static final Style HUD_STYLE = Style.style()
            .font(Key.key("servermmo", "hud"))
            .color(NamedTextColor.WHITE)
            .build();

    private final ServerMMOMobs plugin;
    private final ServerMMOCore core;
    private final Set<UUID> tracked = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> lastTag = new ConcurrentHashMap<>();

    MobTags(ServerMMOMobs plugin, ServerMMOCore core) {
        this.plugin = plugin;
        this.core = core;
    }

    /** Берёт моба под наблюдение и сразу ставит подпись. */
    void apply(LivingEntity entity) {
        if (!(entity instanceof Mob mob) || !core.combat().isCustom(mob)) {
            return;
        }
        tracked.add(mob.getUniqueId());
        update(mob, true);
    }

    /** Сколько кастомных мобов сейчас под наблюдением. */
    int trackedCount() {
        return tracked.size();
    }

    @Override
    public void run() {
        for (UUID id : tracked) {
            Entity entity = Bukkit.getEntity(id);
            if (!(entity instanceof Mob mob) || !mob.isValid() || mob.isDead()) {
                tracked.remove(id);
                lastTag.remove(id);
                continue;
            }
            update(mob, false);
        }
    }

    private void update(Mob mob, boolean force) {
        Component tag = build(mob);
        long health = core.combat().health(mob);
        String key = mob.getUniqueId() + "|" + plain(tag) + "|" + health;
        String previous = lastTag.put(mob.getUniqueId(), key);
        if (!force && key.equals(previous)) {
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
                .append(Component.text(GLYPH_LEVEL, HUD_STYLE))
                .append(Component.text(" ", NamedTextColor.YELLOW))
                .append(Component.text(Integer.toString(level), HUD_STYLE))
                .append(Component.text(" " + name + " ", NamedTextColor.WHITE))
                .append(Component.text(GLYPH_HEART, HUD_STYLE))
                .append(Component.text(" ", NamedTextColor.WHITE))
                .append(Component.text(Long.toString(health), HUD_STYLE))
                .build();
    }

    /** Имя по типу, если уровня нет в таблице. */
    private static String niceName(Mob mob) {
        String key = mob.getType().getKey().getKey().replace('_', ' ');
        return Character.toUpperCase(key.charAt(0)) + key.substring(1);
    }

    private static String plain(Component component) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(component);
    }
}
