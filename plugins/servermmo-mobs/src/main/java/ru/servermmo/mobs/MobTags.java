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
 * Подпись над кастомным мобом: {@code <LVL> 1 Лосось 3}.
 *
 * <p>Значок {@code LVL} и числа — глифы шрифта {@code servermmo:hud} из
 * ресурспака, имя моба — обычный ванильный шрифт. Глифы пака лежат в тех же
 * строках ячейки, что ванильные заглавные буквы, поэтому строка не «пляшет»:
 * значок, цифры и имя стоят на одной базовой линии.</p>
 *
 * <p>Без ресурспака вместо {@code LVL} останется текст, а цифры будут
 * ванильными — подпись не сломается.</p>
 *
 * <p>Подпись обновляется при изменении здоровья. Перебираются только
 * кастомные мобы (они регистрируются при спавне и загрузке), а не все
 * сущности мира — иначе на большом сервере это был бы лишний скан.</p>
 */
final class MobTags extends BukkitRunnable {

    /** Значок уровня: буквы LVL из шрифта пака. */
    private static final String GLYPH_LEVEL = "LVL";
    /** Пробел шрифта пака шириной 4 пикселя: в паке обычный пробел 1 px. */
    private static final String GLYPH_SPACE = "\uE101";

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

        // Пробелы внутри шрифта пака шириной 1 px, поэтому между значком и
        // числом ставим глиф-пробел \uE101 (4 px), а вокруг имени — обычные
        // ванильные пробелы: имя рисуется ванильным шрифтом.
        return Component.text()
                .append(Component.text(GLYPH_LEVEL, HUD_STYLE))
                .append(Component.text(GLYPH_SPACE, HUD_STYLE))
                .append(Component.text(Integer.toString(level), HUD_STYLE))
                .append(Component.text(" " + name + " ", NamedTextColor.WHITE))
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
