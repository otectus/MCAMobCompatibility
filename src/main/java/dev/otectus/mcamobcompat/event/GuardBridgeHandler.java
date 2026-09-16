package dev.otectus.mcamobcompat.event;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.api.event.GuardInteractEvent;
import dev.otectus.mcamobcompat.bridge.*;
import dev.otectus.mcamobcompat.integration.ModIntegrations;
import dev.otectus.mcamobcompat.network.ProxyPairs;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** One server-authoritative dispatch route for both guard and proxy selections. */
@EventBusSubscriber(modid = McaMobCompat.MOD_ID)
public final class GuardBridgeHandler {
    private static final ThreadLocal<Boolean> FORWARDING = ThreadLocal.withInitial(() -> false);
    private GuardBridgeHandler() {}

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel && event.getEntity() instanceof Mob guard && GuardBridge.isBridgedGuard(guard))
            GuardIdentityStore.getOrCreate(guard);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        route(event, event.getTarget());
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        route(event, event.getTarget());
    }

    private static void route(PlayerInteractEvent event, net.minecraft.world.entity.Entity selected) {
        if (FORWARDING.get() || event.getHand() != InteractionHand.MAIN_HAND) return;
        boolean client = event.getLevel().isClientSide;
        boolean selectedProxy = GuardProxy.isProxy(selected);
        Mob guard = selectedProxy ? GuardProxy.nearbyGuardOf(selected) : selected instanceof Mob mob ? mob : null;
        if (selectedProxy && guard == null) { cancel(event, InteractionResult.FAIL); return; }
        if (guard == null) return;
        boolean enabled = client ? ProxyPairs.routesGuard(guard)
                : GuardBridge.isBridgedGuard(guard) && ModIntegrations.guardVillagers().opensMcaInteraction();
        if (!enabled) {
            // Existing proxies remain inert when the bridge is disabled; selecting one must
            // execute the visible guard's stock gesture instead of opening MCA accidentally.
            if (selectedProxy) {
                if (client) { cancel(event, InteractionResult.SUCCESS); return; }
                if (event.getEntity() instanceof ServerPlayer player && guard.isAlive() && guard.level() == player.level()
                        && player.canInteractWithEntity(guard, 1.0)) {
                    boolean previous = FORWARDING.get(); FORWARDING.set(true);
                    try { cancel(event, forwardToEntity(player, guard, event.getHand())); }
                    finally { FORWARDING.set(previous); }
                } else cancel(event, InteractionResult.FAIL);
                return;
            }
            // Preserve the public click event with the proxy interaction option disabled.
            if (event instanceof PlayerInteractEvent.EntityInteract && GuardBridge.isBridgedGuard(guard)
                    && !event.getEntity().isShiftKeyDown()) {
                GuardInteractEvent offered = NeoForge.EVENT_BUS.post(new GuardInteractEvent(event.getEntity(), guard, event.getHand()));
                if (offered.isCanceled()) cancel(event, offered.getCancellationResult());
            }
            return;
        }
        boolean crouch = event.getEntity().isCrouching() || event.getEntity().isShiftKeyDown();
        if (client) {
            GuardInteractEvent offered = crouch ? null : NeoForge.EVENT_BUS.post(new GuardInteractEvent(event.getEntity(), guard, event.getHand()));
            cancel(event, offered != null && offered.isCanceled() ? offered.getCancellationResult() : InteractionResult.SUCCESS);
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player) || !(event.getLevel() instanceof ServerLevel level)) return;
        if (!guard.isAlive() || guard.level() != player.level() || !player.canInteractWithEntity(guard, 1.0)) {
            cancel(event, InteractionResult.FAIL);
            return;
        }
        if (crouch) {
            cancel(event, GuardInteractions.delegateToGuardVillagers(player, guard, event.getHand()));
            return;
        }
        GuardInteractEvent offered = NeoForge.EVENT_BUS.post(new GuardInteractEvent(player, guard, event.getHand()));
        if (offered.isCanceled()) { cancel(event, offered.getCancellationResult()); return; }
        Villager proxy = GuardProxy.create(level, guard);
        if (proxy == null || !proxy.isAlive() || proxy.level() != level) {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.mcamobcompat.proxy_pending"), true);
            cancel(event, InteractionResult.SUCCESS);
            return;
        }
        GuardProxySync.noteInteraction(guard, player.getUUID());
        GuardIdentityStore.synchronize(guard);
        boolean previous = FORWARDING.get();
        FORWARDING.set(true);
        try { cancel(event, forwardToProxy(player, proxy, event.getHand())); }
        finally { FORWARDING.set(previous); }
    }

    /** Both addon hooks run once; PASS executes the vanilla fallback without posting either hook again. */
    static InteractionResult forwardToProxy(ServerPlayer player, Villager proxy, InteractionHand hand) {
        return forwardToEntity(player, proxy, hand);
    }
    private static InteractionResult forwardToEntity(ServerPlayer player, Mob proxy, InteractionHand hand) {
        InteractionResult hooked = CommonHooks.onInteractEntityAt(player, proxy, Vec3.ZERO, hand);
        if (hooked != null) return hooked;
        hooked = CommonHooks.onInteractEntity(player, proxy, hand);
        if (hooked != null) return hooked;
        InteractionResult result = proxy.interactAt(player, Vec3.ZERO, hand);
        return result == InteractionResult.PASS ? GuardInteractions.interactAfterHook(player, proxy, hand) : result;
    }
    private static void cancel(PlayerInteractEvent event, InteractionResult result) {
        if (event instanceof PlayerInteractEvent.EntityInteract specific) {
            specific.setCanceled(true); specific.setCancellationResult(result == InteractionResult.PASS ? InteractionResult.SUCCESS : result);
        } else if (event instanceof PlayerInteractEvent.EntityInteractSpecific specific) {
            specific.setCanceled(true); specific.setCancellationResult(result == InteractionResult.PASS ? InteractionResult.SUCCESS : result);
        }
    }
}
