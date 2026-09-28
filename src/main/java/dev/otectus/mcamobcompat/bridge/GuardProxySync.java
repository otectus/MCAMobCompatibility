package dev.otectus.mcamobcompat.bridge;

import dev.otectus.mcamobcompat.integration.ModIntegrations;
import dev.otectus.mcamobcompat.integration.guardvillagers.GuardReflection;
import dev.otectus.mcamobcompat.mca.McaBinding;
import dev.otectus.mcamobcompat.mca.McaEntities;
import dev.otectus.mcamobcompat.mca.McaMemories;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps a guard and its hidden MCA proxy the same NPC, once per tick.
 *
 * <p>Position, rotation and the hiding flags go down every tick, because anything less is visible:
 * a proxy one tick behind opens MCA's screen out of reach, and a flag MCA's own tick cleared shows
 * a floating villager. The expensive half -- profession, movement intent, names, hearts, the client
 * pairing -- runs once a second, which is far faster than a player can notice and cheap enough for
 * a village full of guards.
 *
 * <p>The interesting direction is upward. MCA drives an NPC by setting a movement state and by
 * pathing it, and the proxy has no AI and cannot walk; so the state is translated into Guard
 * Villagers' own follow and patrol flags, and any path MCA gives the proxy is replayed onto the
 * guard's navigation and then cancelled. That is what makes MCA's Follow button, and MCACrime's
 * escort, actually move the guard. Targets travel both ways for the same reason: MCACrime orders a
 * guard to close on a wanted player through {@code setTarget} on the villager it can see, which is the
 * proxy, and a NoAI proxy cannot act on it -- see {@link #mirrorTargets}.
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

    /** Persistent-data key on the proxy: whom it follows, so a restart keeps the order. */
    private static final String KEY_FOLLOW_TARGET = "mcamobcompat:follow_target";

    /**
     * The last player to open a guard's screen, by guard UUID -- only a fallback for MCA builds
     * whose following memory cannot be read. MCA's own memory says whom it was told to follow,
     * and merely opening another player's screen must not steal an existing follow order.
     */
    private static final Map<UUID, UUID> LAST_INTERACTION = new ConcurrentHashMap<>();

    /** The name last agreed between the pair, to tell which side changed it. */
    private static final Map<UUID, String> LAST_NAME = new ConcurrentHashMap<>();

    /** The movement intent last applied to the guard, so it is only re-applied on change. */
    private static final Map<UUID, String> LAST_MOVE = new ConcurrentHashMap<>();

    /** The pairing last sent to clients, so it is only re-sent when something in it changed. */
    private static final Map<UUID, String> LAST_PAIRING = new ConcurrentHashMap<>();

    /** The target (entity id) last agreed between the pair, so the side that changed it can be told. */
    private static final Map<UUID, Integer> LAST_TARGET = new ConcurrentHashMap<>();

    /** The agreed value for "no target". */
    private static final int NO_TARGET = -1;

    private GuardProxySync() {
    }

    /** Records who opened a guard's screen. Called from the interaction router. */
    public static void noteInteraction(Mob guard, UUID player) {
        if (guard != null && player != null) {
            LAST_INTERACTION.put(guard.getUUID(), player);
        }
    }

    /** Forgets a guard's transient state, so the maps cannot outlive the world's guards. */
    public static void forget(Mob guard) {
        if (guard != null) {
            ProxyEffects.forget(guard);
            UUID id = guard.getUUID();
            LAST_INTERACTION.remove(id);
            LAST_NAME.remove(id);
            LAST_MOVE.remove(id);
            LAST_PAIRING.remove(id);
            LAST_TARGET.remove(id);
        }
    }

    /** Logs, once per server start, which MCA: Crime effects will be mirrored onto bridged guards. */
    public static void logMirroredEffects() {
        ProxyEffects.logResolution();
    }

    /** Drops all transient state. Called when the server stops. */
    public static void clear() {
        ProxyEffects.clear();
        LAST_INTERACTION.clear();
        LAST_NAME.clear();
        LAST_MOVE.clear();
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

        boolean mirroring = GuardBridge.isBridgedGuard(guard)
                && ModIntegrations.guardVillagers().opensMcaInteraction();
        mirrorTargets(guard, proxy, mirroring);
        if (mirroring) {
            relayNavigation(guard, proxy);
        }
        ProxyEffects.tick(guard, proxy, proxy.tickCount % SLOW_INTERVAL == 0);

        if (proxy.tickCount % HOMING_INTERVAL == 0 && !McaBinding.hasHomeVillage(proxy)) {
            // The guard may have been made outside any village and walked into one since.
            McaBinding.seekHome(proxy);
        }

        if (proxy.tickCount % SLOW_INTERVAL == 0) {
            reassertProfession(guard, proxy);
            if (mirroring) {
                mirrorMoveState(guard, proxy);
            }
            reconcileNames(guard, proxy);
            GuardIdentityStore.synchronize(guard);
            sendPairingIfChanged(guard, proxy);
        }
    }

    /**
     * Keeps one target across the pair, in both directions.
     *
     * <p>Downward is the older rule: MCA and its addons read the proxy's target to decide whether a
     * villager was defending itself (MCACrime does), so the guard's enemy becomes the proxy's.
     * Upward is what lets an addon's order reach the entity that can act on it. MCACrime makes a guard
     * pursue a wanted player by calling {@code setTarget} on the villager it selected as a responder,
     * and for a bridged guard that villager is the proxy: NoAI, unable to move, and until 0.2.2 wiped
     * back to the guard's own target on the very next tick, so Guard Villagers guards never enforced
     * Crime's law while Crime believed a responder was assigned.
     *
     * <p>Whichever side changed since the last agreed target wins, exactly as {@link #reconcileNames}
     * decides names. The guard wins a tie, because it is the one in combat. An order is pushed up only
     * while the bridge is mirroring at all, and a <em>player</em> target only under
     * {@code followAddonTargets}, because a guard turning on a player is a consequence Guard Villagers'
     * own player policy exists to control. An MCA villager is never pushed up while friendly-fire
     * prevention is on: that handler would cancel it again next tick and the pair would churn events.
     */
    static void mirrorTargets(Mob guard, Villager proxy, boolean mirroring) {
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
        if (proxyChanged && !guardChanged && mirroring && acceptsOrder(proxyTarget)) {
            guard.setTarget(proxyTarget);
        } else {
            // Only on change: every setTarget posts an event.
            proxy.setTarget(guardTarget);
        }
        LAST_TARGET.put(key, targetId(guard.getTarget()));
    }

    /** The pure half of the upward rule, pinned by GuardProxySyncTargetTest. */
    static boolean acceptsOrder(boolean playerTarget, boolean mcaVillagerTarget,
                                boolean followAddonTargets, boolean preventsFriendlyFire) {
        if (playerTarget) {
            return followAddonTargets;
        }
        return !(mcaVillagerTarget && preventsFriendlyFire);
    }

    private static boolean acceptsOrder(LivingEntity target) {
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

    /** Puts the proxy exactly where the guard is, facing where it faces, and keeps it hidden. */
    public static void pin(Mob guard, Villager proxy) {
        proxy.moveTo(guard.getX(), guard.getY(), guard.getZ(), guard.getYRot(), guard.getXRot());
        proxy.setYHeadRot(guard.getYHeadRot());
        proxy.setYBodyRot(guard.yBodyRot);
        GuardProxy.hide(proxy);
    }

    private static void sendPairingIfChanged(Mob guard, Villager proxy) {
        String state = proxy.getUUID() + "/" + proxy.getId() + "/" + guard.getId() + "/"
                + (GuardBridge.isBridgedGuard(guard) && ModIntegrations.guardVillagers().opensMcaInteraction());
        if (!state.equals(LAST_PAIRING.put(guard.getUUID(), state))) {
            ProxyPairs.broadcast(guard, proxy);
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
     * Puts the profession back to {@code mca:guard} if anything moved it.
     *
     * <p>MCA's own profession-change option operates on the proxy like any other villager, and a
     * proxy that became a farmer would stop being a guard to every addon that keys off the
     * profession -- including MCA's own guard quota.
     */
    private static void reassertProfession(Mob guard, Villager proxy) {
        ResourceLocation current =
                BuiltInRegistries.VILLAGER_PROFESSION.getKey(proxy.getVillagerData().getProfession());
        if (!GuardProxy.GUARD_PROFESSION.equals(current)) {
            GuardProxy.applyVillagerData(proxy, guard);
        }
    }

    /**
     * Translates MCA's movement intent into Guard Villagers' follow and patrol flags.
     *
     * <p>Follow uses the player MCA itself recorded in its following memory, saved on the proxy so a
     * restart keeps the order. A Follow order whose player is offline stops the guard following
     * rather than following nobody. Re-applied only when the intent changes, so a guard an
     * operator moved by hand is not fought every second.
     */
    private static void mirrorMoveState(Mob guard, Villager proxy) {
        Optional<String> state = McaBinding.moveState(proxy);
        if (state.isEmpty()) {
            return;
        }
        String movement = state.get();
        UUID followTarget = null;
        if ("FOLLOW".equals(movement)) {
            followTarget = McaMemories.followTarget(proxy).orElse(null);
            if (followTarget != null) {
                proxy.getPersistentData().putUUID(KEY_FOLLOW_TARGET, followTarget);
            } else if (proxy.getPersistentData().hasUUID(KEY_FOLLOW_TARGET)) {
                followTarget = proxy.getPersistentData().getUUID(KEY_FOLLOW_TARGET);
            } else {
                followTarget = LAST_INTERACTION.get(guard.getUUID());
            }
            if (followTarget == null || guard.getServer() == null
                    || guard.getServer().getPlayerList().getPlayer(followTarget) == null) {
                GuardReflection.setFollowing(guard, false);
                LAST_MOVE.remove(guard.getUUID());
                return;
            }
        } else {
            proxy.getPersistentData().remove(KEY_FOLLOW_TARGET);
        }

        String intent = movement + ":" + followTarget;
        if (intent.equals(LAST_MOVE.put(guard.getUUID(), intent))) {
            return;
        }
        switch (movement) {
            case "FOLLOW" -> {
                GuardReflection.setOwnerId(guard, followTarget);
                GuardReflection.setPatrolling(guard, false);
                GuardReflection.setFollowing(guard, true);
            }
            case "STAY" -> {
                GuardReflection.setFollowing(guard, false);
                GuardReflection.setPatrolPos(guard, guard.blockPosition());
                GuardReflection.setPatrolling(guard, true);
            }
            default -> {
                GuardReflection.setFollowing(guard, false);
                GuardReflection.setPatrolling(guard, false);
            }
        }
    }

    /**
     * Keeps one name across the pair, in both directions.
     *
     * <p>Whichever side changed since the last agreed name wins: a name tag used on the guard (the
     * crouch gesture runs vanilla's name-tag handling on it) goes up into MCA, and a rename made in
     * MCA's own screen comes down to the guard. If both changed in the same second, the guard's
     * wins, because it is the one a player can see being renamed.
     */
    private static void reconcileNames(Mob guard, Villager proxy) {
        String proxyName = proxy.hasCustomName() ? proxy.getCustomName().getString() : "";
        String guardName = guard.hasCustomName() ? guard.getCustomName().getString() : "";
        String previous = LAST_NAME.get(guard.getUUID());
        if (previous == null) {
            GuardIdentityData.Entry entry = guard.getServer() == null ? null
                    : GuardIdentityStore.get(guard.getServer()).find(guard.getUUID());
            previous = entry == null ? proxyName : entry.name();
        }
        if (!guardName.isBlank() && !guardName.equals(previous) && !guardName.equals(proxyName)) {
            proxy.setCustomName(guard.getCustomName());
        } else if (!proxyName.isBlank() && !proxyName.equals(guardName)) {
            GuardProxy.copyNameToGuard(guard, proxy);
        }
        LAST_NAME.put(guard.getUUID(), proxy.hasCustomName() ? proxy.getCustomName().getString() : guardName);
    }
}
