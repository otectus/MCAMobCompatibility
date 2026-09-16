package dev.otectus.mcamobcompat.event;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.bridge.GuardProxy;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Excludes inert proxies only for the duration of native projectile collision selection. */
@EventBusSubscriber(modid = McaMobCompat.MOD_ID)
public final class ProxyProjectileHandler {
    private static final Map<Entity, Map<Villager, AABB>> ACTIVE = new IdentityHashMap<>();
    private ProxyProjectileHandler() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void beforeProjectile(EntityTickEvent.Pre event) {
        if (!(event.getEntity() instanceof Projectile projectile) || projectile.level().isClientSide) return;
        AABB swept = projectile.getBoundingBox().expandTowards(projectile.getDeltaMovement()).inflate(2);
        Map<Villager, AABB> bounds = new LinkedHashMap<>();
        for (Villager proxy : projectile.level().getEntitiesOfClass(Villager.class, swept, GuardProxy::isProxy)) {
            bounds.put(proxy, proxy.getBoundingBox());
            double y = proxy.level().getMinBuildHeight() - 1024.0;
            proxy.setBoundingBox(new AABB(proxy.getX(), y, proxy.getZ(), proxy.getX(), y, proxy.getZ()));
        }
        if (!bounds.isEmpty()) ACTIVE.put(projectile, bounds);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void afterProjectile(EntityTickEvent.Post event) { restore(event.getEntity()); }
    private static void restore(Entity projectile) {
        Map<Villager, AABB> bounds = ACTIVE.remove(projectile);
        if (bounds != null) bounds.forEach((proxy, box) -> { if (!proxy.isRemoved()) proxy.setBoundingBox(box); });
    }
    @SubscribeEvent public static void endTick(ServerTickEvent.Post event) { restoreAll(); }
    @SubscribeEvent public static void stop(ServerStoppedEvent event) { restoreAll(); }
    private static void restoreAll() { for (Entity entity : java.util.List.copyOf(ACTIVE.keySet())) restore(entity); }
}
