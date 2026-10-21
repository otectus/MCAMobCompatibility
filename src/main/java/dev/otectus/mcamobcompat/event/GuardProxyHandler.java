package dev.otectus.mcamobcompat.event;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.bridge.GuardIdentityStore;
import dev.otectus.mcamobcompat.bridge.GuardProxy;
import dev.otectus.mcamobcompat.bridge.GuardProxySync;
import dev.otectus.mcamobcompat.character.CharacterBridge;
import dev.otectus.mcamobcompat.character.CharacterTransitions;
import dev.otectus.mcamobcompat.character.NativeCharacterAdapter;
import dev.otectus.mcamobcompat.mca.McaEntities;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.Set;

/**
 * The lifecycle of a character's hidden MCA identity: creating it, keeping it with its actor, and
 * letting it go only when one half is really gone -- for every adapter's actors.
 *
 * <p>Creation happens on <b>tick</b>, never on join, and only after {@link GuardProxy#CREATE_GRACE_TICKS}:
 * an actor and its identity are added to a loading chunk in list order, and an identity created from
 * the join event would be created before the existing one had been added -- giving every reload a
 * second one. All creation goes through {@link GuardProxy#create}, which resolves, adopts or creates,
 * and never replaces a recorded identity that is merely unloaded.
 *
 * <p><b>Nothing is deleted on a timer.</b> "The actor is not loaded" and "the actor no longer exists"
 * look identical from a ticking identity, and an identity discarded on the first of those would take a
 * family-tree node and every heart a player earned with it. An identity is let go only when its actor
 * is discarded (and it was made for that actor), when the actor dies (see
 * {@link GuardDamageRelayHandler}), or by an operator. An enlisted person is never let go with their
 * body: a discarded actor sends them home ({@link CharacterTransitions#onActorDiscarded}), and a
 * promotion's discard is a replacement, not an abandonment.
 *
 * <p>The identity shares the actor's position, so everything that can select an entity is taught to
 * select the actor: its narrow box ({@code ProxyShapeHandler}) makes the actor the nearest hit for
 * picks and projectiles, and the handlers below re-aim a melee swing and a mob's target choice as a
 * fallback. What is left -- the plain click -- is exactly the one gesture the identity exists to
 * receive, and {@link GuardBridgeHandler} routes even that through the actor.
 */
@EventBusSubscriber(modid = McaMobCompat.MOD_ID)
public final class GuardProxyHandler {

    /** How often an actor without an identity tries to get one, after its grace period. */
    private static final int CREATE_INTERVAL = 20;

    /** Persistent-data key the orphan timer of builds before 0.2.1 left on proxies; removed on sight. */
    private static final String LEGACY_ORPHAN_KEY = McaMobCompat.MOD_ID + ":orphaned";

    private GuardProxyHandler() {
    }

    /**
     * The pair's heartbeat, from either side.
     *
     * <p>Gated hard and cheaply at the top, because this runs for every living entity in the world:
     * a registry-id check, then one persistent-data key or the cached adapter claim.
     */
    @SubscribeEvent
    public static void onLivingTick(EntityTickEvent.Pre event) {
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
        NativeCharacterAdapter adapter = CharacterBridge.adapterOf(mob);
        if (!linked && adapter == null) {
            return;
        }

        Villager proxy = GuardProxy.proxyOf(mob);
        if (proxy != null) {
            if (proxy.level() != level && proxy.isAlive() && mob.isAlive()) {
                // The actor crossed a dimension first. Moved, never recreated: see onGuardJoinLevel.
                proxy.teleportTo(level, mob.getX(), mob.getY(), mob.getZ(), Set.of(), mob.getYRot(), mob.getXRot());
                proxy = GuardProxy.proxyOf(mob);
            }
            if (proxy != null && proxy.level() == level && proxy.isAlive() && mob.isAlive()) {
                if (proxy.tickCount % CREATE_INTERVAL == 0 && !GuardIdentityStore.proxyUuid(level.getServer(),
                        mob.getUUID()).map(proxy.getUUID()::equals).orElse(false)) {
                    // The entities name each other and the store does not: an unclean stop saved them
                    // after a commit the store never saw. Record the pair again, never re-roll it.
                    CharacterTransitions.repairLink(mob, proxy);
                }
                GuardProxySync.tick(level, mob, proxy);
            }
            return;
        }
        if (mob.tickCount % CREATE_INTERVAL == 0 && mob.isAlive() && adapter != null && adapter.opensMcaInteraction()) {
            GuardProxy.create(level, mob);
        }
    }

    /**
     * An identity's own tick: keep it hidden, and pinned to its actor if the actor is here.
     *
     * <p>Pinning from both ticks means whichever of the two ticks later has the actor's latest
     * position, which keeps the narrow identity box inside the actor's between ticks.
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
     * An actor that changed level takes its identity with it.
     *
     * <p>Moved, never recreated: a new identity would be a different villager to MCA, losing the
     * family-tree node, the memories and every heart the character had earned.
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
     * A native mod replacing an actor -- a Recruits promotion -- adds the new actor inside the same
     * call that discarded the old one; the transition reads it against its open bracket. Last, and
     * cancelled joins included, so the bracket sees exactly what Recruits got.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onReplacementJoin(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel && event.getEntity() instanceof Mob mob
                && !McaEntities.isMcaVillager(mob)) {
            CharacterTransitions.onActorJoined(mob, event.isCanceled());
        }
    }

    /**
     * Lets a pair go when one half is really gone -- and only then.
     *
     * <p>{@code KILLED} and {@code DISCARDED} are the two reasons that mean an entity will not be
     * back; unloading and changing dimension use different ones, and acting on those would destroy
     * MCA state every time a player walked away.
     *
     * <ul>
     *   <li>An <b>actor discarded</b> by one of this mod's own transitions is a role change. One
     *       discarded inside a native replacement is handed on to its replacement. An enlisted
     *       person's actor discarded any other way sends the person home. Any other discarded actor
     *       takes the identity made for it along, as in 0.2.</li>
     *   <li>An <b>actor killed</b> has its death confirmed by the damage relay, which relays it onto
     *       the identity so MCA's own death handling runs.</li>
     *   <li>An <b>identity</b> that is really gone -- an operator's {@code /kill}, or the end of a
     *       relayed death -- is unlinked on both sides, so its actor can be given a new one later
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
                McaMobCompat.debug("Identity {} left the world ({}); its link is released", mob.getUUID(), reason);
            }
            return;
        }
        if (!mob.getPersistentData().hasUUID(GuardProxy.KEY_PROXY) && !CharacterBridge.isBridgedActor(mob)) {
            return;
        }
        if (reason == Entity.RemovalReason.KILLED) {
            GuardDamageRelayHandler.confirmRemoval(mob);
        } else if (!CharacterTransitions.onActorDiscarded(mob)) {
            Villager proxy = GuardProxy.proxyOf(mob);
            if (proxy != null) {
                GuardProxy.discard(proxy);
            }
        }
        GuardProxySync.forget(mob);
    }

    /**
     * A swing that landed on the identity is re-aimed at the actor.
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

    /** A mob that picked the identity picks the actor instead -- the actor is the one that fights. */
    @SubscribeEvent
    public static void onChangeTarget(LivingChangeTargetEvent event) {
        LivingEntity target = event.getNewAboutToBeSetTarget();
        if (target == null || target.level().isClientSide || !GuardProxy.isProxy(target)) {
            return;
        }
        // Null is a fine answer: no actor means nothing worth attacking stood there.
        event.setNewAboutToBeSetTarget(GuardProxy.guardOf(target));
    }

    /**
     * The fallback for a projectile that still selects the identity -- one fired from exactly where
     * the narrow box pokes out between two ticks. It passes through, and cannot hurt the person.
     */
    @SubscribeEvent
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        if (event.getRayTraceResult() instanceof EntityHitResult hit && GuardProxy.isProxy(hit.getEntity())) {
            event.setCanceled(true);
        }
    }
}
