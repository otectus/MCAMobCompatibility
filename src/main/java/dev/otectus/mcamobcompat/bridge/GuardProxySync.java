package dev.otectus.mcamobcompat.bridge;

import dev.otectus.mcamobcompat.character.CharacterCommandRouter;
import dev.otectus.mcamobcompat.character.CharacterSync;
import dev.otectus.mcamobcompat.character.NativeCharacterAdapter;
import dev.otectus.mcamobcompat.character.NativeCharacterAdapters;
import dev.otectus.mcamobcompat.integration.ModIntegrations;
import dev.otectus.mcamobcompat.mca.McaBinding;
import dev.otectus.mcamobcompat.mca.McaEntities;
import dev.otectus.mcamobcompat.network.ProxyPairs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.npc.Villager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps a native actor and its hidden MCA identity the same character, once per tick.
 *
 * <p>Position, rotation and the hiding flags go down every tick, because anything less is visible:
 * an identity one tick behind opens MCA's screen out of reach, and a flag MCA's own tick cleared shows
 * a floating villager. The expensive half -- profession, orders, names, hearts, the client
 * association -- runs once a second, which is far faster than a player can notice and cheap enough
 * for a village full of NPCs.
 *
 * <p>The interesting direction is upward. MCA drives an NPC by setting a movement state and by pathing
 * it, and the identity has no AI and cannot walk; so MCA's orders are carried out by the actor's
 * adapter through the native mod's own controls ({@link CharacterCommandRouter}), and -- for Guard
 * Villagers' guards only -- any path MCA gives the identity is replayed onto the guard's navigation and
 * then cancelled. That is what makes MCA's Follow button, and MCA: Crime's escort, move a guard. A
 * recruit's or a worker's path is dropped instead: it would fight the formation or the job the owner set.
 * Targets travel both ways where the adapter allows it, for the same reason: MCA: Crime orders a guard
 * to close on a wanted player through {@code setTarget} on the villager it can see, which is the
 * identity -- see {@link #mirrorTargets}.
 *
 * <p>An existing pair keeps being hidden and pinned even when the bridge options are turned off:
 * switching an option must never expose a hidden villager. Only the upward mirroring stops.
 */
public final class GuardProxySync {

    /** How often the slow half runs. One second: unnoticeable to a player, cheap for a village. */
    private static final int SLOW_INTERVAL = 20;

    /** How often a homeless proxy asks MCA for a village again: a village search, so not every second. */
    private static final int HOMING_INTERVAL = SLOW_INTERVAL * 10;

    /** Speed the guard is given when it is replaying a path MCA laid for the proxy. */
    private static final double RELAY_SPEED = 1.0D;

    /** The name last agreed between the pair, to tell which side changed it. */
    private static final Map<UUID, String> LAST_NAME = new ConcurrentHashMap<>();

    /** The association last sent to clients, so it is only re-sent when something in it changed. */
    private static final Map<UUID, String> LAST_PAIRING = new ConcurrentHashMap<>();

    /** The target (entity id) last agreed between the pair, so the side that changed it can be told. */
    private static final Map<UUID, Integer> LAST_TARGET = new ConcurrentHashMap<>();

    /** The agreed value for "no target". */
    private static final int NO_TARGET = -1;

    private GuardProxySync() {
    }

    /** Records who opened an actor's MCA screen. Called from the interaction router. */
    public static void noteInteraction(Mob guard, UUID player) {
        CharacterCommandRouter.noteInteraction(guard, player);
    }

    /** Forgets an actor's transient state, so the maps cannot outlive the world's NPCs. */
    public static void forget(Mob guard) {
        if (guard != null) {
            ProxyEffects.forget(guard);
            CharacterCommandRouter.forget(guard);
            CharacterSync.forget(guard.getUUID());
            UUID id = guard.getUUID();
            LAST_NAME.remove(id);
            LAST_PAIRING.remove(id);
            LAST_TARGET.remove(id);
        }
    }

    /** Logs, once per server start, which MCA: Crime effects will be mirrored onto bridged actors. */
    public static void logMirroredEffects() {
        ProxyEffects.logResolution();
    }

    /** Drops all transient state. Called when the server stops. */
    public static void clear() {
        ProxyEffects.clear();
        CharacterCommandRouter.clear();
        CharacterSync.clear();
        LAST_NAME.clear();
        LAST_PAIRING.clear();
        LAST_TARGET.clear();
    }

    /** One tick of the pair. Server side; both entities are alive, linked and in the same level. */
    public static void tick(ServerLevel level, Mob guard, Villager proxy) {
        // MCA's 'ride horse' order can put the proxy on a mount. It has no business riding anything,
        // and dismounting it is also what neutralises that option.
        if (proxy.isPassenger()) {
            proxy.stopRiding();
        }
        pin(guard, proxy);

        NativeCharacterAdapter adapter = NativeCharacterAdapters.bridging(guard);
        boolean mirroring = adapter != null && adapter.opensMcaInteraction();
        mirrorTargets(guard, proxy, mirroring, adapter);
        if (mirroring && adapter.relaysNavigation()) {
            relayNavigation(guard, proxy);
        } else if (!proxy.getNavigation().isDone()) {
            // Nobody may walk an identity, and its own path must not be left to look like an order.
            proxy.getNavigation().stop();
        }
        ProxyEffects.tick(guard, proxy, proxy.tickCount % SLOW_INTERVAL == 0);
        CharacterCommandRouter.tickPause(guard);

        if (proxy.tickCount % HOMING_INTERVAL == 0 && !McaBinding.hasHomeVillage(proxy)) {
            // The actor may have been made outside any village and walked into one since.
            McaBinding.seekHome(proxy);
        }

        if (proxy.tickCount % SLOW_INTERVAL == 0) {
            reassertProfession(guard, proxy);
            if (mirroring) {
                CharacterCommandRouter.mirrorMoveState(guard, proxy, adapter);
            }
            reconcileNames(guard, proxy, adapter);
            GuardIdentityStore.synchronize(guard);
            CharacterSync.observe(level, guard, proxy);
            sendPairingIfChanged(guard, proxy);
        }
    }

    /**
     * Keeps one target across the pair, in both directions.
     *
     * <p>Downward is the older rule: MCA and its addons read the proxy's target to decide whether a
     * villager was defending itself (MCACrime does), so the actor's enemy becomes the proxy's.
     * Upward is what lets an addon's order reach the entity that can act on it. MCACrime makes a guard
     * pursue a wanted player by calling {@code setTarget} on the villager it selected as a responder,
     * and for a bridged guard that villager is the proxy: NoAI, unable to move, and until 0.2.2 wiped
     * back to the guard's own target on the very next tick, so Guard Villagers guards never enforced
     * Crime's law while Crime believed a responder was assigned.
     *
     * <p>Whichever side changed since the last agreed target wins, exactly as {@link #reconcileNames}
     * decides names. The actor wins a tie, because it is the one in combat. An order is pushed up only
     * while the bridge is mirroring at all, and only when the actor's adapter accepts it: a guard takes
     * a non-player target, and a player target only under {@code followAddonTargets}; a recruit takes
     * one only when the server made recruits law enforcement; a worker never does.
     */
    static void mirrorTargets(Mob guard, Villager proxy, boolean mirroring, NativeCharacterAdapter adapter) {
        LivingEntity guardTarget = guard.getTarget();
        LivingEntity proxyTarget = proxy.getTarget();
        UUID key = guard.getUUID();
        if (guardTarget == proxyTarget) {
            LAST_TARGET.put(key, targetId(guardTarget));
            return;
        }
        int agreed = LAST_TARGET.getOrDefault(key, NO_TARGET);
        boolean guardChanged = targetId(guardTarget) != agreed;
        boolean proxyChanged = targetId(proxyTarget) != agreed;
        if (proxyChanged && !guardChanged && mirroring
                && (proxyTarget == null || adapter.acceptsAddonTarget(guard, proxyTarget))) {
            guard.setTarget(proxyTarget);
        } else {
            // Only on change: every setTarget posts an event.
            proxy.setTarget(guardTarget);
        }
        LAST_TARGET.put(key, targetId(guard.getTarget()));
    }

    /** The pure half of the guards' upward rule, pinned by GuardProxySyncTargetTest. */
    static boolean acceptsOrder(boolean playerTarget, boolean mcaVillagerTarget,
                                boolean followAddonTargets, boolean preventsFriendlyFire) {
        if (playerTarget) {
            return followAddonTargets;
        }
        return !(mcaVillagerTarget && preventsFriendlyFire);
    }

    /** Whether a target an add-on set on a guard's identity may be pushed up to the guard. */
    public static boolean acceptsAddonTarget(LivingEntity target) {
        if (target == null) {
            return true; // clearing an order is always allowed
        }
        return acceptsOrder(target instanceof Player, McaEntities.isMcaVillager(target),
                ModIntegrations.guardVillagers().followsAddonTargets(),
                ModIntegrations.guardVillagers().preventsFriendlyFire());
    }

    private static int targetId(LivingEntity target) {
        return target == null ? NO_TARGET : target.getId();
    }

    /** Puts the proxy exactly where the actor is, facing where it faces, and keeps it hidden. */
    public static void pin(Mob guard, Villager proxy) {
        proxy.moveTo(guard.getX(), guard.getY(), guard.getZ(), guard.getYRot(), guard.getXRot());
        proxy.setYHeadRot(guard.getYHeadRot());
        proxy.setYBodyRot(guard.yBodyRot);
        GuardProxy.hide(proxy);
    }

    private static void sendPairingIfChanged(Mob guard, Villager proxy) {
        String state = ProxyPairs.fingerprint(guard, proxy);
        if (!state.equals(LAST_PAIRING.put(guard.getUUID(), state))) {
            ProxyPairs.broadcast(guard, proxy);
        }
    }

    /** Re-sends this pair's association on the next heartbeat, after something clients cache changed. */
    public static void invalidatePairing(UUID actor) {
        if (actor != null) {
            LAST_PAIRING.remove(actor);
        }
    }

    /**
     * Replays a path MCA gave the proxy onto the guard, then cancels it.
     *
     * <p>This is the whole mechanism behind MCACrime's escort and anything else that calls
     * {@code getNavigation().moveTo} on a villager: the proxy is brainless and would stand still
     * forever, so the destination is handed to the entity that can actually walk there. A guard in
     * combat keeps fighting; the path is dropped rather than queued.
     */
    private static void relayNavigation(Mob guard, Villager proxy) {
        PathNavigation navigation = proxy.getNavigation();
        if (navigation.isDone() || navigation.getPath() == null) {
            return;
        }
        BlockPos target = navigation.getTargetPos();
        if (target != null && guard.getTarget() == null) {
            guard.getNavigation().moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, RELAY_SPEED);
        }
        navigation.stop();
    }

    /**
     * Puts the identity's profession back if anything moved it.
     *
     * <p>MCA's own profession-change option operates on the identity like any other villager, and a
     * guard's identity that became a farmer would stop being a guard to every addon that keys off the
     * profession -- including MCA's own guard quota. An adapter that names no profession (an enlisted
     * recruit's person keeps their own) is left alone.
     */
    private static void reassertProfession(Mob guard, Villager proxy) {
        ResourceLocation wanted = GuardProxy.wantedProfession(guard);
        if (wanted == null) {
            return;
        }
        ResourceLocation current =
                BuiltInRegistries.VILLAGER_PROFESSION.getKey(proxy.getVillagerData().getProfession());
        if (!wanted.equals(current)) {
            GuardProxy.applyVillagerData(proxy, guard);
        }
    }

    /**
     * Keeps one name across the pair, in both directions.
     *
     * <p>Whichever side changed since the last agreed name wins: a name tag used on the actor goes up
     * into MCA, and a rename made in MCA's own screen or editor comes down to the actor. If both changed
     * in the same second, the actor's wins, because it is the one a player can see being renamed. A
     * label the native mod gives every one of its NPCs ("Recruit", "Farmer") is not a name and never
     * goes up: the person's MCA name replaces it.
     */
    private static void reconcileNames(Mob guard, Villager proxy, NativeCharacterAdapter adapter) {
        String proxyName = proxy.hasCustomName() ? proxy.getCustomName().getString() : "";
        String guardName = guard.hasCustomName() ? guard.getCustomName().getString() : "";
        boolean meaningful = adapter == null || adapter.meaningfulName(guard).isPresent();
        String previous = LAST_NAME.get(guard.getUUID());
        if (previous == null) {
            GuardIdentityData.Entry entry = guard.getServer() == null ? null
                    : GuardIdentityStore.get(guard.getServer()).find(guard.getUUID());
            previous = entry == null ? proxyName : entry.name();
        }
        if (meaningful && !guardName.isBlank() && !guardName.equals(previous) && !guardName.equals(proxyName)) {
            proxy.setCustomName(guard.getCustomName());
        } else if (!proxyName.isBlank() && !proxyName.equals(guardName)) {
            GuardProxy.copyNameToGuard(guard, proxy);
        }
        LAST_NAME.put(guard.getUUID(), proxy.hasCustomName() ? proxy.getCustomName().getString() : guardName);
    }
}
