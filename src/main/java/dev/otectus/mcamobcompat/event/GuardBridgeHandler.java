package dev.otectus.mcamobcompat.event;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.api.event.GuardInteractEvent;
import dev.otectus.mcamobcompat.bridge.GuardBridge;
import dev.otectus.mcamobcompat.bridge.GuardIdentityStore;
import dev.otectus.mcamobcompat.bridge.GuardInteractions;
import dev.otectus.mcamobcompat.bridge.GuardProxy;
import dev.otectus.mcamobcompat.bridge.GuardProxySync;
import dev.otectus.mcamobcompat.integration.ModIntegrations;
import dev.otectus.mcamobcompat.mca.McaEntities;
import dev.otectus.mcamobcompat.network.ProxyPairs;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps every bridged guard's identity up to date, and routes every click on a guard or its proxy.
 *
 * <p><b>One router for both halves of the pair.</b> A click can select the guard or, where the two
 * overlap, its proxy; and it can arrive as {@code EntityInteractSpecific} (the positional packet the
 * client sends first) or {@code EntityInteract}. All four combinations go through {@link #route},
 * which resolves the visible guard and decides the gesture once:
 * <ul>
 *   <li>with {@code guardMcaInteraction} on, a plain click is forwarded to the guard's hidden MCA
 *       villager, so <b>MCA's own</b> screen opens with every MCA option working, and a crouch click
 *       is replayed into Guard Villagers so its inventory GUI keeps a gesture of its own;</li>
 *   <li>with it off, a click on the guard does what it did before the proxy existed -- offer
 *       {@link GuardInteractEvent} to the addon suite, then leave the click alone -- and a click that
 *       lands on an already existing proxy runs the guard's stock gesture instead of opening MCA.</li>
 * </ul>
 *
 * <p>{@link GuardInteractEvent} is posted for the plain click only; its documented contract is the
 * MCA-style interaction, and an addon that cancels it takes MCA's place for that click.
 *
 * <p>Server authority: the client only cancels the click (it cannot open MCA's screen itself, and
 * must not let vanilla run the click locally); every decision with an effect is the server's,
 * after checking the guard is alive, in the player's level and within the player's own entity
 * reach. Each click is handled at most once per player per tick, whichever of the two packets
 * carries it.
 */
@EventBusSubscriber(modid = McaMobCompat.MOD_ID)
public final class GuardBridgeHandler {

    /** Slack added to the player's entity reach, as vanilla allows for a click that lands late. */
    private static final double REACH_PADDING = 1.0D;

    /** Set while a click is being replayed, so the replay's own events are left alone. */
    private static final ThreadLocal<Boolean> FORWARDING = ThreadLocal.withInitial(() -> false);

    /** The game time of each player's last routed click, so the two packets of one click route once. */
    private static final Map<UUID, Long> LAST_ROUTED = new ConcurrentHashMap<>();

    private GuardBridgeHandler() {
    }

    /**
     * A guard entering a server level gets an identity if it has none: eagerly, so a guard has a name
     * the moment a player can see it, and so guards already in the world when the option was turned
     * on are caught.
     */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel && event.getEntity() instanceof Mob guard
                && GuardBridge.isBridgedGuard(guard)) {
            GuardIdentityStore.getOrCreate(guard);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        route(event, event.getTarget());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        route(event, event.getTarget());
    }

    private static void route(PlayerInteractEvent event, Entity selected) {
        if (FORWARDING.get() || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        boolean client = event.getLevel().isClientSide;
        boolean selectedProxy = GuardProxy.isProxy(selected);
        Mob guard = selectedProxy
                ? GuardProxy.nearbyGuardOf(selected)
                : selected instanceof Mob mob && !McaEntities.isMcaVillager(mob) ? mob : null;
        if (selectedProxy && guard == null) {
            // A proxy whose guard is not here: nothing should open MCA for an invisible villager.
            cancel(event, client ? InteractionResult.SUCCESS : InteractionResult.FAIL);
            return;
        }
        if (guard == null) {
            return;
        }

        Player player = event.getEntity();
        boolean routed = client
                ? ProxyPairs.clientRoutesGuard(guard) || (GuardBridge.isBridgedGuard(guard)
                        && ModIntegrations.guardVillagers().opensMcaInteraction())
                : GuardBridge.isBridgedGuard(guard) && ModIntegrations.guardVillagers().opensMcaInteraction();
        boolean crouch = player.isCrouching() || player.isShiftKeyDown();

        if (!routed) {
            if (selectedProxy) {
                // An existing proxy stays inert with the bridge off: its click is the guard's.
                if (client) {
                    cancel(event, InteractionResult.SUCCESS);
                } else if (player instanceof ServerPlayer serverPlayer && reachable(serverPlayer, guard)
                        && firstRouteThisTick(serverPlayer)) {
                    cancel(event, forward(serverPlayer, guard, event.getHand()));
                } else {
                    cancel(event, InteractionResult.FAIL);
                }
                return;
            }
            // The pre-proxy behaviour: offer the plain click to the addon suite, nothing more.
            if (event instanceof PlayerInteractEvent.EntityInteract && !crouch && GuardBridge.isBridgedGuard(guard)) {
                if (!client) {
                    GuardIdentityStore.getOrCreate(guard);
                }
                GuardInteractEvent offered = new GuardInteractEvent(player, guard, event.getHand());
                if (McaMobCompat.post(offered)) {
                    cancel(event, offered.getCancellationResult());
                }
            }
            return;
        }

        if (client) {
            if (crouch) {
                cancel(event, InteractionResult.SUCCESS);
                return;
            }
            GuardInteractEvent offered = new GuardInteractEvent(player, guard, event.getHand());
            cancel(event, McaMobCompat.post(offered)
                    ? offered.getCancellationResult() : InteractionResult.SUCCESS);
            return;
        }

        if (!(player instanceof ServerPlayer serverPlayer) || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!reachable(serverPlayer, guard)) {
            cancel(event, InteractionResult.FAIL);
            return;
        }
        if (!firstRouteThisTick(serverPlayer)) {
            cancel(event, InteractionResult.SUCCESS);
            return;
        }
        if (crouch) {
            cancel(event, GuardInteractions.delegateToGuardVillagers(serverPlayer, guard, event.getHand()));
            return;
        }

        GuardIdentityStore.getOrCreate(guard);
        GuardInteractEvent offered = new GuardInteractEvent(player, guard, event.getHand());
        if (McaMobCompat.post(offered)) {
            // An addon has taken this click. Honour its result and stay out of the way.
            cancel(event, offered.getCancellationResult());
            return;
        }

        // Resolves the existing proxy first; creates one only when that is safe.
        Villager proxy = GuardProxy.create(level, guard);
        if (proxy == null || !proxy.isAlive() || proxy.level() != level) {
            serverPlayer.displayClientMessage(Component.translatable("message.mcamobcompat.proxy_pending"), true);
            cancel(event, InteractionResult.SUCCESS);
            return;
        }
        GuardProxySync.noteInteraction(guard, player.getUUID());
        // Queued hearts reach MCA before its screen reads them, rather than on the next heartbeat.
        GuardIdentityStore.synchronize(guard);
        cancel(event, forward(serverPlayer, proxy, event.getHand()));
    }

    private static boolean reachable(ServerPlayer player, Mob guard) {
        return guard.isAlive() && guard.level() == player.level() && player.canInteractWithEntity(guard, REACH_PADDING);
    }

    private static boolean firstRouteThisTick(ServerPlayer player) {
        long now = player.level().getGameTime();
        Long previous = LAST_ROUTED.put(player.getUUID(), now);
        return previous == null || previous != now;
    }

    /**
     * Replays the click onto this entity exactly as though the player had clicked it.
     *
     * <p>Both NeoForge hooks are fired by hand first, because an addon listening for them on an MCA
     * villager -- which is how MCAQuests notices a conversation -- would otherwise never see this
     * click: nothing else raises them for the proxy. A non-null result means a listener took the
     * click. {@code interactAt} next, because that is where MCA opens its screen; vanilla's item
     * fallback only if that passed, without posting the hook a second time.
     */
    private static InteractionResult forward(ServerPlayer player, Mob target, InteractionHand hand) {
        boolean previous = FORWARDING.get();
        FORWARDING.set(true);
        try {
            InteractionResult hooked = CommonHooks.onInteractEntityAt(player, target, Vec3.ZERO, hand);
            if (hooked != null) {
                return hooked;
            }
            hooked = CommonHooks.onInteractEntity(player, target, hand);
            if (hooked != null) {
                return hooked;
            }
            InteractionResult result = target.interactAt(player, Vec3.ZERO, hand);
            return result == InteractionResult.PASS
                    ? GuardInteractions.interactAfterHook(player, target, hand) : result;
        } finally {
            FORWARDING.set(previous);
        }
    }

    /** Cancels either click event with a consuming result, so the client sends no second packet. */
    private static void cancel(PlayerInteractEvent event, InteractionResult result) {
        InteractionResult consuming = result == InteractionResult.PASS ? InteractionResult.SUCCESS : result;
        if (event instanceof PlayerInteractEvent.EntityInteract interact) {
            interact.setCanceled(true);
            interact.setCancellationResult(consuming);
        } else if (event instanceof PlayerInteractEvent.EntityInteractSpecific specific) {
            specific.setCanceled(true);
            specific.setCancellationResult(consuming);
        }
    }

    /** Drops per-player click bookkeeping; called when the server stops. */
    public static void clear() {
        LAST_ROUTED.clear();
    }
}
