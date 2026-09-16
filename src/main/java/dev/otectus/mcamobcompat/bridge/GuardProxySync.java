package dev.otectus.mcamobcompat.bridge;

import net.minecraft.core.registries.BuiltInRegistries;
import dev.otectus.mcamobcompat.integration.guardvillagers.GuardReflection;
import dev.otectus.mcamobcompat.mca.McaBinding;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
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
 * a floating villager. The expensive half -- profession, movement intent, names -- runs once a
 * second, which is far faster than a player can notice and cheap enough for a village full of
 * guards.
 *
 * <p>The interesting direction is upward. MCA drives an NPC by setting a movement state and by
 * pathing it, and the proxy has no AI and cannot walk; so the state is translated into Guard
 * Villagers' own follow and patrol flags, and any path MCA gives the proxy is replayed onto the
 * guard's navigation and then cancelled. That is what makes MCA's Follow button, and MCACrime's
 * escort, actually move the guard.
 */
public final class GuardProxySync {

    /** How often the slow half runs. One second: unnoticeable to a player, cheap for a village. */
    private static final int SLOW_INTERVAL = 20;

    /** Speed the guard is given when it is replaying a path MCA laid for the proxy. */
    private static final double RELAY_SPEED = 1.0D;
    private static final String FOLLOW_TARGET = "mcamobcompat:follow_target";

    /**
     * The last player to interact with each guard, by guard UUID.
     *
     * <p>Guard Villagers will not follow without an owner, and MCA's Follow state does not say whom
     * it is following -- the screen is open in front of exactly one player, so the player who
     * opened it is the answer. Transient on purpose: losing it on restart costs a follow order
     * nobody is still standing there waiting on.
     */
    private static final Map<UUID, String> LAST_NAME = new ConcurrentHashMap<>();
    private static final Map<UUID, String> LAST_MOVE = new ConcurrentHashMap<>();

    private static final Map<UUID, UUID> LAST_INTERACTION = new ConcurrentHashMap<>();

    private GuardProxySync() {
    }

    /** Records who a guard is talking to, for the follow order. Called from the interaction router. */
    public static void noteInteraction(Mob guard, UUID player) {
        if (guard != null && player != null) {
            LAST_INTERACTION.put(guard.getUUID(), player);
        }
    }

    /** Forgets a guard's last interaction, so the map cannot outlive the world's guards. */
    public static void forget(Mob guard) {
        if (guard != null) {
            LAST_INTERACTION.remove(guard.getUUID());
            LAST_NAME.remove(guard.getUUID());
            LAST_MOVE.remove(guard.getUUID());
        }
    }

    public static void clear() { LAST_INTERACTION.clear(); LAST_NAME.clear(); LAST_MOVE.clear(); }

    /** One tick of the pair. Server side; both entities are assumed alive and linked. */
    public static void tick(ServerLevel level, Mob guard, Villager proxy) {
        // MCA's 'ride horse' order can put the proxy on a mount. It has no business riding anything,
        // and dismounting it is also what neutralises that option.
        if (proxy.isPassenger()) {
            proxy.stopRiding();
        }

        proxy.moveTo(guard.getX(), guard.getY(), guard.getZ(), guard.getYRot(), guard.getXRot());
        proxy.setYHeadRot(guard.getYHeadRot());
        proxy.setYBodyRot(guard.yBodyRot);
        GuardProxy.hide(proxy);

        // So MCA and its addons see the same enemy the guard does -- MCACrime reads getTarget() to
        // decide whether a villager was defending itself.
        proxy.setTarget(guard.getTarget());

        boolean enabled = dev.otectus.mcamobcompat.integration.ModIntegrations.guardVillagers().opensMcaInteraction()
                && GuardBridge.isBridgedGuard(guard);
        if (enabled) relayNavigation(guard, proxy);

        if (proxy.tickCount % SLOW_INTERVAL == 0) {
            reassertProfession(guard, proxy);
            if (enabled) mirrorMoveState(guard, proxy);
            reconcileNames(guard, proxy);
            GuardIdentityStore.synchronize(guard);
            dev.otectus.mcamobcompat.network.ProxyPairs.broadcast(guard, proxy);
        }
    }

    /**
     * Replays a path MCA gave the proxy onto the guard, then cancels it.
     *
     * <p>This is the whole mechanism behind MCACrime's escort and anything else that calls
     * {@code getNavigation().moveTo} on a villager: the proxy is brainless and would stand still
     * forever, so the destination is handed to the entity that can actually walk there.
     */
    private static void relayNavigation(Mob guard, Villager proxy) {
        PathNavigation navigation = proxy.getNavigation();
        if (guard.getTarget() != null || navigation.isDone() || navigation.getPath() == null) {
            return;
        }
        BlockPos target = navigation.getTargetPos();
        if (target != null) {
            guard.getNavigation().moveTo(target.getX() + 0.5D, target.getY(),
                    target.getZ() + 0.5D, RELAY_SPEED);
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

    /** Translates MCA's movement intent into Guard Villagers' follow and patrol flags. */
    private static void mirrorMoveState(Mob guard, Villager proxy) {
        Optional<String> state = McaBinding.moveState(proxy);
        if (state.isEmpty()) {
            return;
        }
        String movement = state.get();
        UUID followTarget = null;
        if ("FOLLOW".equals(movement)) {
            followTarget = McaBinding.followTarget(proxy).orElse(null);
            if (followTarget != null) proxy.getPersistentData().putUUID(FOLLOW_TARGET, followTarget);
            else if (proxy.getPersistentData().hasUUID(FOLLOW_TARGET)) followTarget = proxy.getPersistentData().getUUID(FOLLOW_TARGET);
        } else proxy.getPersistentData().remove(FOLLOW_TARGET);
        if ("FOLLOW".equals(movement) && (followTarget == null || guard.getServer().getPlayerList().getPlayer(followTarget) == null)) {
            GuardReflection.setFollowing(guard, false);
            LAST_MOVE.remove(guard.getUUID());
            return;
        }
        String intent = movement + ":" + followTarget;
        if (intent.equals(LAST_MOVE.put(guard.getUUID(), intent))) return;
        switch (movement) {
            case "FOLLOW" -> {
                UUID owner = followTarget;
                if (owner != null) {
                    GuardReflection.setOwnerId(guard, owner);
                }
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
     * Keeps one name across the pair.
     *
     * <p>A guard-side name that differs from the proxy's can only have come from a player's name
     * tag -- the crouch gesture runs vanilla's name-tag handling on the guard -- so it wins and is
     * pushed up into MCA. Otherwise the proxy's name, which is MCA's own, comes down.
     */
    private static void reconcileNames(Mob guard, Villager proxy) {
        String proxyName = proxy.hasCustomName() ? proxy.getCustomName().getString() : "";
        String guardName = guard.hasCustomName() ? guard.getCustomName().getString() : "";
        String previous = LAST_NAME.get(guard.getUUID());
        if (previous == null) {
            var entry = GuardIdentityStore.get(guard.getServer()).find(guard.getUUID());
            previous = entry == null ? proxyName : entry.name();
        }
        // A changed guard name wins simultaneous edits; otherwise preserve the MCA-side rename.
        if (!guardName.isBlank() && !guardName.equals(previous)) proxy.setCustomName(guard.getCustomName());
        else if (!proxyName.isBlank() && !proxyName.equals(guardName)) GuardProxy.copyNameToGuard(guard, proxy);
        LAST_NAME.put(guard.getUUID(), proxy.hasCustomName() ? proxy.getCustomName().getString() : guardName);
    }
}
