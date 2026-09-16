package dev.otectus.mcamobcompat.event;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.bridge.*;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Combat health belongs to the guard; scoped synthetic notifications reach its MCA identity. */
@EventBusSubscriber(modid = McaMobCompat.MOD_ID)
public final class GuardDamageRelayHandler {
    private record Relay(Mob guard, Villager proxy, float amount, boolean lethal) {}
    private record Hit(Mob guard, Villager proxy, DamageSource source, float amount) {}
    private static final java.util.List<Hit> SURVIVAL_CHECKS = new java.util.ArrayList<>();
    private record Death(Mob guard, DamageSource source, LivingDeathEvent event) {}
    private static final ThreadLocal<Relay> ACTIVE = new ThreadLocal<>();
    private static final Map<UUID, Death> DEATHS = new LinkedHashMap<>();
    private GuardDamageRelayHandler() {}

    public static boolean isRelay(Entity entity) { Relay relay = ACTIVE.get(); return relay != null && relay.proxy == entity; }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onGuardHurt(LivingDamageEvent.Post event) {
        if (event.getEntity().level().isClientSide || isRelay(event.getEntity()) || event.getNewDamage() <= 0
                || !(event.getEntity() instanceof Mob guard)) return;
        Villager proxy = GuardProxy.proxyOf(guard);
        if (proxy == null || !proxy.isAlive()) return;
        // A lethal hit is handled only after the guard's death/totem outcome is known.
        if (guard.getHealth() <= 0) {
            SURVIVAL_CHECKS.add(new Hit(guard, proxy, event.getSource(), event.getNewDamage()));
            return;
        }
        relay(guard, proxy, event.getSource(), Math.min(event.getNewDamage(), proxy.getMaxHealth() - 1), false);
        GuardIdentityStore.synchronize(guard);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onGuardDeath(LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide || isRelay(event.getEntity())
                || !(event.getEntity() instanceof Mob guard) || GuardProxy.isProxy(guard)) return;
        if (GuardIdentityStore.get(guard.getServer()).find(guard.getUUID()) != null)
            DEATHS.put(guard.getUUID(), new Death(guard, event.getSource(), event));
    }

    @SubscribeEvent
    public static void afterServerTick(ServerTickEvent.Post event) {
        for (Death death : java.util.List.copyOf(DEATHS.values())) {
            Mob guard = death.guard;
            if (death.event.isCanceled() || guard.isAlive()) { DEATHS.remove(guard.getUUID()); continue; }
            if (guard.getPose() == Pose.DYING || guard.getRemovalReason() == Entity.RemovalReason.KILLED) finishDeath(death);
        }
        for (Hit hit : java.util.List.copyOf(SURVIVAL_CHECKS)) {
            if (hit.guard.isAlive() && hit.proxy.isAlive()) {
                relay(hit.guard, hit.proxy, hit.source, Math.min(hit.amount, hit.proxy.getMaxHealth() - 1), false);
                GuardIdentityStore.synchronize(hit.guard);
            }
        }
        SURVIVAL_CHECKS.clear();
    }

    public static void confirmRemoval(Mob guard) {
        Death death = DEATHS.get(guard.getUUID());
        if (death != null) finishDeath(death);
    }

    private static void finishDeath(Death death) {
        Mob guard = death.guard;
        DEATHS.remove(guard.getUUID());
        Villager proxy = GuardProxy.proxyOf(guard);
        GuardIdentityStore.get(guard.getServer()).markDeceased(guard.getUUID(), guard.level().getGameTime());
        if (proxy == null) return;
        if (proxy.isAlive()) relay(guard, proxy, death.source, proxy.getMaxHealth() + 1, true);
        if (proxy.getPose() == Pose.DYING || proxy.isRemoved()) {
            GuardIdentityStore.synchronize(guard);
            GuardProxy.unlink(guard);
        } else {
            // Never retry a canceled synthetic death: MCA's override can already have run its
            // relationship callbacks. Preserve the UUID link for diagnosis/recovery.
            McaMobCompat.LOGGER.warn("Guard {} died but MCA proxy {} rejected its death; preserving the identity link.",
                    guard.getUUID(), proxy.getUUID());
        }
        GuardProxySync.forget(guard);
    }

    private static void relay(Mob guard, Villager proxy, DamageSource source, float amount, boolean lethal) {
        if (amount <= 0) return;
        Relay previous = ACTIVE.get();
        boolean invulnerable = proxy.isInvulnerable();
        ACTIVE.set(new Relay(guard, proxy, amount, lethal));
        try {
            proxy.setInvulnerable(false);
            proxy.invulnerableTime = 0;
            proxy.setHealth(proxy.getMaxHealth());
            proxy.hurt(source, amount);
        } finally {
            // A callback may have legitimately killed/removed the proxy. Never revive it here.
            if (!lethal && proxy.isAlive() && proxy.getPose() != Pose.DYING) proxy.setHealth(proxy.getMaxHealth());
            proxy.setInvulnerable(invulnerable);
            if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onProxyAttacked(LivingIncomingDamageEvent event) {
        if (GuardProxy.isProxy(event.getEntity()) && !isRelay(event.getEntity())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void relayFinalDamage(LivingDamageEvent.Pre event) {
        Relay relay = ACTIVE.get();
        if (relay == null || relay.proxy != event.getEntity()) return;
        event.getEntity().setAbsorptionAmount(0);
        event.setNewDamage(relay.amount);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void proxyExperience(LivingExperienceDropEvent event) {
        if (GuardProxy.isProxy(event.getEntity())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void proxyLoot(LivingDropsEvent event) {
        // The visible guard supplies combat loot. MCA's separately stored gifts are dropped
        // once by MCA after vanilla die() returns and are not part of this loot-table event.
        if (GuardProxy.isProxy(event.getEntity())) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void stop(ServerStoppedEvent event) {
        DEATHS.clear(); SURVIVAL_CHECKS.clear(); ACTIVE.remove(); GuardProxySync.clear();
        dev.otectus.mcamobcompat.classification.ClassificationCache.invalidate();
        ChoreSafetyHandler.invalidateConfig();
    }
}
