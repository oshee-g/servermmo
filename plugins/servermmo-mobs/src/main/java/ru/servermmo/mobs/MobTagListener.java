package ru.servermmo.mobs;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import ru.servermmo.core.ServerMMOCore;

/**
 * Возвращает подписи кастомным мобам после перезапуска сервера или загрузки
 * чанка: они уже помечены в своих данных, достаточно снова взять их под
 * наблюдение.
 */
final class MobTagListener implements Listener {

    private final ServerMMOCore core;
    private final MobTags tags;

    MobTagListener(ServerMMOCore core, MobTags tags) {
        this.core = core;
        this.tags = tags;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof LivingEntity living && core.combat().isCustom(living)) {
                tags.apply(living);
            }
        }
    }
}
