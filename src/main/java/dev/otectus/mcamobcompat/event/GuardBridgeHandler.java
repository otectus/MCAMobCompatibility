package dev.otectus.mcamobcompat.event;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.api.event.GuardInteractEvent;
import dev.otectus.mcamobcompat.bridge.GuardIdentityStore;
import dev.otectus.mcamobcompat.bridge.GuardInteractions;
import dev.otectus.mcamobcompat.bridge.GuardProxy;
import dev.otectus.mcamobcompat.bridge.GuardProxySync;
import dev.otectus.mcamobcompat.character.CharacterBridge;
import dev.otectus.mcamobcompat.character.McaEditorBridge;
import dev.otectus.mcamobcompat.character.NativeCharacterAdapter;
import dev.otectus.mcamobcompat.character.NativeCharacterAdapters;
import dev.otectus.mcamobcompat.integration.guardvillagers.GuardVillagersAdapter;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps every bridged actor's record up to date, and routes every click on an actor or its identity.
 *
 * <p><b>One router for both halves of the pair, and for every adapter.</b> A click can select the
 * actor or, where the two overlap, its identity; and it can arrive as {@code EntityInteractSpecific}
 * (the positional packet the client sends first) or {@code EntityInteract}. All four combinations go
 * through {@link #route}, which resolves the visible actor and decides the gesture once, in this
 * order:
 * <ol>
 *   <li><b>MCA's editor item</b>, in either hand: MCA's own editor for the person, if the server's
 *       editor policy allows this player (see {@link McaEditorBridge}).</li>
 *   <li><b>A native tool</b> -- one of the actor's own mod's items, a lead or a name tag: the click is
 *       left to the native mod and to vanilla, untouched.</li>
 *   <li><b>A crouching click</b>: the native mod's own screen -- Guard Villagers' inventory,
 *       Recruits' and Workers' orders and inventory for the owner, Recruits' hire screen for an unhired
 *       recruit -- through the adapter, which knows the gesture its mod expects.</li>
 *   <li><b>Any other click</b>, empty-handed or holding a gift, a ring or any other MCA item: forwarded
 *       to the hidden person, so <b>MCA's own</b> screen and items work exactly as on any villager --
 *       unless the actor is hostile to this player right now.</li>
 * </ol>
 * With MCA interaction switched off for the actor's mod, a click does what it did before the bridge
 * existed, and a click that lands on an existing identity runs the actor's stock gesture instead.
 *
 * <p>{@link GuardInteractEvent} is posted for a guard's plain click only, as 0.2 promised; a recruit's
 * or a worker's click is not a guard's.
 *
 * <p>Server authority: the client only cancels the click (it cannot open MCA's screen itself, and must
 * not let vanilla run the click locally); every decision with an effect is the server's, after
 * checking the actor is alive, in the player's level and within the player's own entity reach. Each
 * click is handled at most once per player per tick, whichever of the two packets carries it.
 */
@EventBusSubscriber(modid = McaMobCompat.MOD_ID)
public final class GuardBridgeHandler {

    /** Slack added to the player's entity reach, as vanilla allows for a click that lands late. */
    private static final double REACH_PADDING = 1.0D;

    /** Set while a click is being replayed, so the replay's own events are left alone. */
    private static final ThreadLocal<Boolean> FORWARDING = ThreadLocal.withInitial(() -> false);

    /** The game time of each player's last routed click, so the two packets of one click route once. */
    private static final Map<UUID, Long> LAST_ROUTED = new ConcurrentHashMap<>();

    /** Players already shown the native-controls hint this session. */
    private static final Set<UUID> HINTED = ConcurrentHashMap.newKeySet();

    private GuardBridgeHandler() {
    }

    /**
     * A guard entering a server level gets a record if it has none: eagerly, so it has a name the
     * moment a player can see it, as in 0.2. Other adapters' actors get theirs from their first
     * heartbeat instead: a native mod that replaces an actor (a Recruits promotion) adds the new one
     * inside the very call the character moves over in, and a record made at its join would compete.
     */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel && event.getEntity() instanceof Mob actor
                && dev.otectus.mcamobcompat.bridge.GuardBridge.isBridgedGuard(actor)) {
            GuardIdentityStore.ensureRecord(actor);
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
        Mob actor = selectedProxy
                ? GuardProxy.nearbyGuardOf(selected)
                : selected instanceof Mob mob && !McaEntities.isMcaVillager(mob) ? mob : null;
        if (selectedProxy && actor == null) {
            // An identity whose actor is not here: nothing should open MCA for an invisible villager.
            cancel(event, client ? InteractionResult.SUCCESS : InteractionResult.FAIL);
            return;
        }
        if (actor == null) {
            return;
        }

        Player player = event.getEntity();
        boolean routed = client
                ? ProxyPairs.clientRoutesGuard(actor) || CharacterBridge.routesToMca(actor)
                : CharacterBridge.routesToMca(actor);
        boolean crouch = player.isCrouching() || player.isShiftKeyDown();
        NativeCharacterAdapter adapter = NativeCharacterAdapters.claimant(actor);
        boolean guard = adapter == GuardVillagersAdapter.INSTANCE;

        if (!routed) {
            if (selectedProxy) {
                // An existing identity stays inert with the bridge off: its click is the actor's.
                if (client) {
                    cancel(event, InteractionResult.SUCCESS);
                } else if (player instanceof ServerPlayer serverPlayer && reachable(serverPlayer, actor)
                        && firstRouteThisTick(serverPlayer)) {
                    cancel(event, forward(serverPlayer, actor, event.getHand()));
                } else {
                    cancel(event, InteractionResult.FAIL);
                }
                return;
            }
            // The pre-proxy behaviour: offer a guard's plain click to the addon suite, nothing more.
            if (event instanceof PlayerInteractEvent.EntityInteract && !crouch && guard
                    && CharacterBridge.isBridgedActor(actor)) {
                if (!client) {
                    GuardIdentityStore.getOrCreate(actor);
                }
                GuardInteractEvent offered = new GuardInteractEvent(player, actor, event.getHand());
                if (McaMobCompat.post(offered)) {
                    cancel(event, offered.getCancellationResult());
                }
            }
            return;
        }

        InteractionHand editorHand = McaEditorBridge.isEditor(player.getMainHandItem()) ? InteractionHand.MAIN_HAND
                : McaEditorBridge.isEditor(player.getOffhandItem()) ? InteractionHand.OFF_HAND : null;
        if (editorHand == null && nativeTool(adapter, player.getMainHandItem())) {
            if (!selectedProxy) {
                return; // the native mod's and vanilla's own click, untouched
            }
            if (client) {
                cancel(event, InteractionResult.SUCCESS);
            } else if (player instanceof ServerPlayer serverPlayer && reachable(serverPlayer, actor)
                    && firstRouteThisTick(serverPlayer)) {
                cancel(event, forward(serverPlayer, actor, event.getHand()));
            } else {
                cancel(event, InteractionResult.FAIL);
            }
            return;
        }

        if (client) {
            if (editorHand != null || crouch || !guard) {
                cancel(event, InteractionResult.SUCCESS);
                return;
            }
            GuardInteractEvent offered = new GuardInteractEvent(player, actor, event.getHand());
            cancel(event, McaMobCompat.post(offered)
                    ? offered.getCancellationResult() : InteractionResult.SUCCESS);
            return;
        }

        if (!(player instanceof ServerPlayer serverPlayer) || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!reachable(serverPlayer, actor)) {
            cancel(event, InteractionResult.FAIL);
            return;
        }
        if (!firstRouteThisTick(serverPlayer)) {
            cancel(event, InteractionResult.SUCCESS);
            return;
        }
        if (editorHand != null) {
            GuardIdentityStore.ensureRecord(actor);
            Villager proxy = GuardProxy.create(level, actor);
            if (proxy == null || !proxy.isAlive() || proxy.level() != level) {
                serverPlayer.displayClientMessage(Component.translatable("message.mcamobcompat.proxy_pending"), true);
                cancel(event, InteractionResult.SUCCESS);
                return;
            }
            cancel(event, forwarding(() -> McaEditorBridge.open(serverPlayer, actor, proxy, editorHand)));
            return;
        }
        if (crouch) {
            cancel(event, forwarding(() -> adapter == null
                    ? GuardInteractions.delegateToGuardVillagers(serverPlayer, actor, event.getHand())
                    : adapter.openNativeInterface(serverPlayer, actor, event.getHand())));
            return;
        }
        if (adapter != null && adapter.isHostileTo(actor, serverPlayer)) {
            serverPlayer.displayClientMessage(Component.translatable("message.mcamobcompat.character.hostile",
                    actor.getName()), true);
            cancel(event, InteractionResult.FAIL);
            return;
        }

        if (guard) {
            GuardIdentityStore.getOrCreate(actor);
            GuardInteractEvent offered = new GuardInteractEvent(player, actor, event.getHand());
            if (McaMobCompat.post(offered)) {
                // An addon has taken this click. Honour its result and stay out of the way.
                cancel(event, offered.getCancellationResult());
                return;
            }
        } else {
            GuardIdentityStore.ensureRecord(actor);
        }

        // Resolves the existing identity first; creates one only when that is safe.
        Villager proxy = GuardProxy.create(level, actor);
        if (proxy == null || !proxy.isAlive() || proxy.level() != level) {
            serverPlayer.displayClientMessage(Component.translatable("message.mcamobcompat.proxy_pending"), true);
            cancel(event, InteractionResult.SUCCESS);
            return;
        }
        GuardProxySync.noteInteraction(actor, player.getUUID());
        // Queued hearts reach MCA before its screen reads them, rather than on the next heartbeat.
        GuardIdentityStore.synchronize(actor);
        hintOnce(serverPlayer, actor);
        cancel(event, forward(serverPlayer, proxy, event.getHand()));
    }

    /** Whether the held item is one the actor's own mod, or vanilla, handles on the actor itself. */
    private static boolean nativeTool(NativeCharacterAdapter adapter, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.is(Items.LEAD) || stack.is(Items.NAME_TAG)) {
            return true;
        }
        return adapter != null && adapter.isNativeTool(stack);
    }

    /** Tells a player once per session how to reach the native controls and the role actions. */
    private static void hintOnce(ServerPlayer player, Mob actor) {
        if (HINTED.add(player.getUUID())) {
            player.displayClientMessage(Component.translatable("message.mcamobcompat.character.hint",
                    actor.getType().getDescription()), false);
        }
    }

    private static boolean reachable(ServerPlayer player, Mob guard) {
        return guard.isAlive() && guard.level() == player.level() && player.canInteractWithEntity(guard, REACH_PADDING);
    }

    private static boolean firstRouteThisTick(ServerPlayer player) {
        long now = player.level().getGameTime();
        Long previous = LAST_ROUTED.put(player.getUUID(), now);
        return previous == null || previous != now;
    }

    /** Runs one replayed interaction with the router stood down, so its own events are left alone. */
    private static InteractionResult forwarding(java.util.function.Supplier<InteractionResult> interaction) {
        boolean previous = FORWARDING.get();
        FORWARDING.set(true);
        try {
            return interaction.get();
        } finally {
            FORWARDING.set(previous);
        }
    }

    /**
     * Replays the click onto this entity exactly as though the player had clicked it.
     *
     * <p>Both NeoForge hooks are fired by hand first, because an addon listening for them on an MCA
     * villager -- which is how MCAQuests notices a conversation -- would otherwise never see this
     * click: nothing else raises them for the identity. A non-null result means a listener took the
     * click. {@code interactAt} next, because that is where MCA opens its screen; vanilla's item
     * fallback only if that passed, without posting the hook a second time.
     */
    private static InteractionResult forward(ServerPlayer player, Mob target, InteractionHand hand) {
        return forwarding(() -> {
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
        });
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
        HINTED.clear();
    }
}
