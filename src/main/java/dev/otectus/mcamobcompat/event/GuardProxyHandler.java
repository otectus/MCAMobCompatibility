package dev.otectus.mcamobcompat.event;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.bridge.GuardBridge;
import dev.otectus.mcamobcompat.bridge.GuardInteractions;
import dev.otectus.mcamobcompat.bridge.GuardProxy;
import dev.otectus.mcamobcompat.bridge.GuardProxySync;
import dev.otectus.mcamobcompat.integration.ModIntegrations;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
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
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Set;

/**
 * The lifecycle of the hidden MCA proxy: creating it, keeping it, hiding it from everything that
 * could hit it, and removing it when its guard is really gone.
 *
 * <p>Creation happens on <b>tick</b>, never on join. A guard and its proxy are added to a loading
 * chunk in list order, and a proxy created from the join event would be created before the existing
 * one had been added -- giving every reload a second proxy. Waiting two seconds and then sweeping
 * the guard's own bounding box for an already-linked villager makes the reload case idempotent
 * without any join-order assumption.
 *
 * <p>The proxy shares the guard's hitbox and {@code isPickable} cannot be overridden without a
 * mixin, so everything that can select an entity has to be taught to pick the guard instead: a
 * crouch click, a melee swing, a mob's target choice and an arrow all land on the guard. What is
 * left -- the plain click -- is exactly the one gesture the proxy exists to receive.
 */
@Mod.EventBusSubscriber(modid = McaMobCompat.MOD_ID)
public final class GuardProxyHandler {

    /**
     * How long a guard waits before it may create a proxy.
     *
     * <p>Two seconds, purely to let a chunk finish loading: the proxy that already exists is added
     * in the same tick batch, and creating another before it arrives is how a world grows a crowd
     * of invisible villagers.
     */
    private static final int CREATE_GRACE_TICKS = 40;

    /** How long an orphaned proxy is given to find its guard again before it is discarded. */
    private static final int ORPHAN_LIMIT_TICKS = 200;

    /** Persistent-data key counting the ticks a proxy has spent unable to find its guard. */
    private static final String KEY_ORPHANED = McaMobCompat.MOD_ID + ":orphaned";

    /** How far around a guard to look for a proxy that is already its own. */
    private static final double ADOPT_RANGE = 2.0D;

    private GuardProxyHandler() {
    }

    /**
     * The pair's heartbeat: sync a linked pair, create a missing proxy, retire an orphaned one.
     *
     * <p>Gated hard and cheaply at the top, because this runs for every living entity in the world.
     */
    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || !(mob.level() instanceof ServerLevel level)) {
            return;
        }
        if (GuardProxy.isProxy(mob)) {
            tickProxy(mob);
            return;
        }
        if (!GuardBridge.isBridgedGuard(mob)
                || !ModIntegrations.guardVillagers().opensMcaInteraction()) {
            return;
        }

        Villager proxy = GuardProxy.proxyOf(mob);
        if (proxy != null) {
            GuardProxySync.tick(level, mob, proxy);
            return;
        }
        if (mob.tickCount <= CREATE_GRACE_TICKS) {
            return;
        }

        Villager existing = findOwnProxy(level, mob);
        if (existing != null) {
            GuardProxy.link(mob, existing);
            return;
        }
        GuardProxy.create(level, mob);
    }

    /**
     * A proxy whose guard has gone counts down and then discards itself.
     *
     * <p>Not immediately: "the guard is not loaded" and "the guard no longer exists" look identical
     * from here, and a proxy that vanished every time its guard's chunk unloaded first would lose
     * the family-tree node and the memories MCA keeps on it.
     */
    private static void tickProxy(Mob proxy) {
        if (proxy.tickCount <= CREATE_GRACE_TICKS) {
            return;
        }
        CompoundTag persistent = proxy.getPersistentData();
        if (GuardProxy.guardOf(proxy) != null) {
            if (persistent.contains(KEY_ORPHANED)) {
                persistent.remove(KEY_ORPHANED);
            }
            return;
        }
        int orphaned = persistent.getInt(KEY_ORPHANED) + 1;
        if (orphaned >= ORPHAN_LIMIT_TICKS) {
            McaMobCompat.debug("Discarding orphaned MCA proxy {}", proxy.getUUID());
            GuardProxy.discard(proxy);
            return;
        }
        persistent.putInt(KEY_ORPHANED, orphaned);
    }

    /** A proxy inside the guard's own bounding box that already names this guard. */
    private static Villager findOwnProxy(ServerLevel level, Mob guard) {
        List<Villager> nearby = level.getEntitiesOfClass(Villager.class,
                guard.getBoundingBox().inflate(ADOPT_RANGE),
                candidate -> guard.getUUID().equals(GuardProxy.linkedGuardUuid(candidate)));
        return nearby.isEmpty() ? null : nearby.get(0);
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
        if (!(event.getEntity() instanceof Mob guard) || !GuardBridge.isBridgedGuard(guard)) {
            return;
        }
        Villager proxy = GuardProxy.proxyOf(guard);
        if (proxy == null || proxy.level() == level) {
            return;
        }
        proxy.teleportTo(level, guard.getX(), guard.getY(), guard.getZ(), Set.of(),
                guard.getYRot(), guard.getXRot());
    }

    /**
     * A guard that is really gone takes its proxy with it -- and only then.
     *
     * <p>{@code KILLED} and {@code DISCARDED} are the two reasons that mean the entity will not be
     * back; unloading and changing dimension use different ones, and discarding on those would
     * destroy MCA state every time a player walked away. A killed guard has normally already
     * unlinked in the damage relay, which is what leaves MCA free to finish its own death handling
     * on the proxy: the lookup below then finds nothing and this does nothing.
     */
    @SubscribeEvent
    public static void onGuardLeaveLevel(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel)) {
            return;
        }
        if (!(event.getEntity() instanceof Mob guard) || !GuardBridge.isBridgedGuard(guard)) {
            return;
        }
        Entity.RemovalReason reason = guard.getRemovalReason();
        if (reason != Entity.RemovalReason.KILLED && reason != Entity.RemovalReason.DISCARDED) {
            return;
        }
        Villager proxy = GuardProxy.proxyOf(guard);
        if (proxy != null) {
            GuardProxy.discard(proxy);
        }
        GuardProxySync.forget(guard);
    }

    /**
     * The crouch gesture, when the click landed on the proxy rather than on the guard.
     *
     * <p>They share a hitbox, so which of the two a ray hits is arbitrary. {@code HIGHEST} because
     * {@link ConversionGuardHandler} runs at {@code HIGH} and would otherwise see a crouch-clicked
     * MCA villager and block the gesture that is meant to open the guard's inventory.
     *
     * <p>The client asks the heuristic question: persistent data is not synced, so
     * {@code isProxy} is only answerable on the server.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onProxyInteract(PlayerInteractEvent.EntityInteract event) {
        Entity target = event.getTarget();
        boolean proxy = event.getLevel().isClientSide
                ? GuardProxy.looksLikeProxy(target)
                : GuardProxy.isProxy(target);
        if (!proxy) {
            return;
        }
        Player player = event.getEntity();
        if (!player.isCrouching() && !player.isShiftKeyDown()) {
            // Everything else on a proxy is MCA's own business. Leave it alone.
            return;
        }

        Mob guard = event.getLevel().isClientSide ? null : GuardProxy.guardOf(target);
        event.setCanceled(true);
        event.setCancellationResult(guard != null && player instanceof ServerPlayer serverPlayer
                ? GuardInteractions.delegateToGuardVillagers(serverPlayer, guard, event.getHand())
                : InteractionResult.SUCCESS);
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
     * An arrow that would hit the proxy passes through it and carries on to the guard behind, which
     * occupies the same space.
     */
    @SubscribeEvent
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        if (event.getRayTraceResult() instanceof EntityHitResult hit
                && GuardProxy.isProxy(hit.getEntity())) {
            event.setImpactResult(ProjectileImpactEvent.ImpactResult.SKIP_ENTITY);
        }
    }
}
