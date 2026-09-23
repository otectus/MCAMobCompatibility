package dev.otectus.mcamobcompat.event;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.bridge.GuardBridge;
import dev.otectus.mcamobcompat.bridge.GuardIdentityStore;
import dev.otectus.mcamobcompat.bridge.GuardProxy;
import dev.otectus.mcamobcompat.bridge.GuardProxySync;
import dev.otectus.mcamobcompat.integration.ModIntegrations;
import dev.otectus.mcamobcompat.mca.McaEntities;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Set;

/**
 * The lifecycle of the hidden MCA proxy: creating it, keeping it with its guard, and letting it go
 * only when one half is really gone.
 *
 * <p>Creation happens on <b>tick</b>, never on join, and only after {@link GuardProxy#CREATE_GRACE_TICKS}:
 * a guard and its proxy are added to a loading chunk in list order, and a proxy created from the
 * join event would be created before the existing one had been added -- giving every reload a second
 * proxy. All creation goes through {@link GuardProxy#create}, which resolves, adopts or creates, and
 * never replaces a recorded proxy that is merely unloaded.
 *
 * <p><b>Nothing is deleted on a timer.</b> "The guard is not loaded" and "the guard no longer
 * exists" look identical from a ticking proxy, and a proxy discarded on the first of those would
 * take a family-tree node and every heart a player earned with it. A proxy is let go only when its
 * guard is discarded, when the guard dies (see {@link GuardDamageRelayHandler}), or by an operator.
 *
 * <p>The proxy shares the guard's position, so everything that can select an entity is taught to
 * select the guard: its narrow box ({@code ProxyShapeHandler}) makes the guard the nearest hit for
 * picks and projectiles, and the handlers below re-aim a melee swing and a mob's target choice as a
 * fallback. What is left -- the plain click -- is exactly the one gesture the proxy exists to
 * receive, and {@link GuardBridgeHandler} routes even that through the guard.
 */
@Mod.EventBusSubscriber(modid = McaMobCompat.MOD_ID)
public final class GuardProxyHandler {

    /** How often a guard without a proxy tries to get one, after its grace period. */
    private static final int CREATE_INTERVAL = 20;

    /** Persistent-data key the orphan timer of builds before 0.2.1 left on proxies; removed on sight. */
    private static final String LEGACY_ORPHAN_KEY = McaMobCompat.MOD_ID + ":orphaned";

    private GuardProxyHandler() {
    }

    /**
     * The pair's heartbeat, from either side.
     *
     * <p>Gated hard and cheaply at the top, because this runs for every living entity in the world:
     * a registry-id check, then one persistent-data key or the cached guard role.
     */
    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || !(mob.level() instanceof ServerLevel level)) {
            return;
        }
        if (McaEntities.isMcaVillager(mob)) {
            if (mob instanceof Villager villager && GuardProxy.isProxy(villager)) {
                tickProxy(villager);
            }
            return;
        }
        boolean linked = mob.getPersistentData().hasUUID(GuardProxy.KEY_PROXY);
        if (!linked && !GuardBridge.isBridgedGuard(mob)) {
            return;
        }

        Villager proxy = GuardProxy.proxyOf(mob);
        if (proxy != null) {
            if (proxy.level() != level && proxy.isAlive() && mob.isAlive()) {
                // The guard crossed a dimension first. Moved, never recreated: see onGuardJoinLevel.
                proxy.teleportTo(level, mob.getX(), mob.getY(), mob.getZ(), Set.of(), mob.getYRot(), mob.getXRot());
                proxy = GuardProxy.proxyOf(mob);
            }
            if (proxy != null && proxy.level() == level && proxy.isAlive() && mob.isAlive()) {
                GuardProxySync.tick(level, mob, proxy);
            }
            return;
        }
        if (mob.tickCount % CREATE_INTERVAL == 0 && mob.isAlive() && GuardBridge.isBridgedGuard(mob)
                && ModIntegrations.guardVillagers().opensMcaInteraction()) {
            GuardProxy.create(level, mob);
        }
    }

    /**
     * A proxy's own tick: keep it hidden, and pinned to its guard if the guard is here.
     *
     * <p>Pinning from both ticks means whichever of the two ticks later has the guard's latest
     * position, which keeps the narrow proxy box inside the guard's between ticks.
     */
    private static void tickProxy(Villager proxy) {
        if (!proxy.isAlive()) {
            return;
        }
        GuardProxy.hide(proxy);
        if (proxy.getPersistentData().contains(LEGACY_ORPHAN_KEY)) {
            proxy.getPersistentData().remove(LEGACY_ORPHAN_KEY);
        }
        Mob guard = GuardProxy.guardOf(proxy);
        if (guard != null && guard.level() == proxy.level() && guard.isAlive()) {
            if (proxy.isPassenger()) {
                proxy.stopRiding();
            }
            GuardProxySync.pin(guard, proxy);
        }
    }

    /**
     * A guard that changed level takes its proxy with it.
     *
     * <p>Moved, never recreated: a new proxy would be a different villager to MCA, losing the
     * family-tree node, the memories and every heart the guard had earned.
     */
    @SubscribeEvent
    public static void onGuardJoinLevel(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!(event.getEntity() instanceof Mob guard) || McaEntities.isMcaVillager(guard)
                || !guard.getPersistentData().hasUUID(GuardProxy.KEY_PROXY)) {
            return;
        }
        Villager proxy = GuardProxy.proxyOf(guard);
        if (proxy == null || proxy.level() == level || !proxy.isAlive()) {
            return;
        }
        proxy.teleportTo(level, guard.getX(), guard.getY(), guard.getZ(), Set.of(),
                guard.getYRot(), guard.getXRot());
    }

    /**
     * Lets a pair go when one half is really gone -- and only then.
     *
     * <p>{@code KILLED} and {@code DISCARDED} are the two reasons that mean an entity will not be
     * back; unloading and changing dimension use different ones, and acting on those would destroy
     * MCA state every time a player walked away.
     *
     * <ul>
     *   <li>A <b>guard discarded</b> takes its proxy with it.</li>
     *   <li>A <b>guard killed</b> has its death confirmed by the damage relay, which relays it onto
     *       the proxy so MCA's own death handling runs.</li>
     *   <li>A <b>proxy</b> that is really gone -- an operator's {@code /kill}, or the end of a
     *       relayed death -- is unlinked on both sides, so its guard can be given a new one later
     *       instead of waiting forever for a villager that no longer exists.</li>
     * </ul>
     */
    @SubscribeEvent
    public static void onLeaveLevel(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel) || !(event.getEntity() instanceof Mob mob)) {
            return;
        }
        Entity.RemovalReason reason = mob.getRemovalReason();
        if (reason != Entity.RemovalReason.KILLED && reason != Entity.RemovalReason.DISCARDED) {
            return;
        }
        if (McaEntities.isMcaVillager(mob)) {
            if (GuardProxy.isProxy(mob)) {
                Mob guard = GuardProxy.guardOf(mob);
                if (guard != null) {
                    GuardProxy.unlink(guard);
                } else if (mob.getServer() != null) {
                    GuardIdentityStore.findByProxy(mob.getServer(), mob.getUUID())
                            .ifPresent(owner -> GuardIdentityStore.unlink(mob.getServer(), owner));
                }
                McaMobCompat.debug("Proxy {} left the world ({}); its link is released", mob.getUUID(), reason);
            }
            return;
        }
        if (!mob.getPersistentData().hasUUID(GuardProxy.KEY_PROXY) && !GuardBridge.isBridgedGuard(mob)) {
            return;
        }
        if (reason == Entity.RemovalReason.KILLED) {
            GuardDamageRelayHandler.confirmRemoval(mob);
        } else {
            Villager proxy = GuardProxy.proxyOf(mob);
            if (proxy != null) {
                GuardProxy.discard(proxy);
            }
        }
        GuardProxySync.forget(mob);
    }

    /**
     * A swing that landed on the proxy is re-aimed at the guard.
     *
     * <p>Server side only, deliberately: cancelling on the client swallows the attack packet, so
     * the hit would never reach the server at all.
     */
    @SubscribeEvent
    public static void onAttackProxy(AttackEntityEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide || !GuardProxy.isProxy(event.getTarget())) {
            return;
        }
        Mob guard = GuardProxy.guardOf(event.getTarget());
        event.setCanceled(true);
        if (guard != null) {
            player.attack(guard);
        }
    }

    /** A mob that picked the proxy picks the guard instead -- the guard is the one that fights. */
    @SubscribeEvent
    public static void onChangeTarget(LivingChangeTargetEvent event) {
        LivingEntity target = event.getNewTarget();
        if (target == null || target.level().isClientSide || !GuardProxy.isProxy(target)) {
            return;
        }
        // Null is a fine answer: no guard means nothing worth attacking stood there.
        event.setNewTarget(GuardProxy.guardOf(target));
    }

    /**
     * The fallback for a projectile that still selects the proxy -- one fired from exactly where
     * the narrow proxy box pokes out between two ticks. It passes through, and cannot hurt the
     * identity the guard's MCA state lives in.
     */
    @SubscribeEvent
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        if (event.getRayTraceResult() instanceof EntityHitResult hit && GuardProxy.isProxy(hit.getEntity())) {
            event.setImpactResult(ProjectileImpactEvent.ImpactResult.SKIP_ENTITY);
        }
    }
}
