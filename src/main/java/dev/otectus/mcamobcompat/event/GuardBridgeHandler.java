package dev.otectus.mcamobcompat.event;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.api.event.GuardInteractEvent;
import dev.otectus.mcamobcompat.bridge.GuardBridge;
import dev.otectus.mcamobcompat.bridge.GuardIdentityStore;
import dev.otectus.mcamobcompat.bridge.GuardInteractions;
import dev.otectus.mcamobcompat.bridge.GuardProxy;
import dev.otectus.mcamobcompat.bridge.GuardProxySync;
import dev.otectus.mcamobcompat.integration.ModIntegrations;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Keeps every bridged guard's identity up to date, and routes the guard's clicks.
 *
 * <p>Three small jobs, all server-authoritative except the interact event, which is offered on both
 * sides so that a listener cancelling a click can stop the client swinging as well:
 * <ul>
 *   <li><b>join</b> -- a guard entering a server level gets an identity if it has none. Eagerly,
 *       rather than lazily on first question, so a guard has a name the moment a player can see it,
 *       and so guards that were already in the world when the option was switched on get caught.</li>
 *   <li><b>interact</b> -- decides which gesture a click was: the plain one is forwarded to the
 *       guard's hidden MCA proxy, the crouch one is handed to Guard Villagers, and {@link
 *       GuardInteractEvent} is still offered to the addon suite first. Runs ahead of that mod's own
 *       handler through the {@code HIGH} priority {@link ConversionGuardHandler} also uses.</li>
 *   <li><b>death</b> -- records the guard as deceased. The entry stays until purged.</li>
 * </ul>
 *
 * <p>There is no hurt handler any more. Hitting a guard is relayed onto the proxy by
 * {@link GuardDamageRelayHandler}, and MCA's own {@code hurt} applies the memory penalty -- one
 * reaction, MCA's, instead of two scales that disagree.
 *
 * <p>Disjoint from {@link ConversionGuardHandler} by construction: that one only acts on MCA
 * villagers, and {@link GuardBridge#isBridgedGuard} excludes them.
 */
@Mod.EventBusSubscriber(modid = McaMobCompat.MOD_ID)
public final class GuardBridgeHandler {

    private GuardBridgeHandler() {
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel)) {
            return;
        }
        if (!(event.getEntity() instanceof Mob guard) || !GuardBridge.isBridgedGuard(guard)) {
            return;
        }
        GuardIdentityStore.getOrCreate(guard);
    }

    /**
     * Routes a right-click on a bridged guard: which gesture was it, and who should answer it.
     *
     * <p>With {@code guardMcaInteraction} on, the two gestures Guard Villagers ships are swapped.
     * A plain click is replayed onto the guard's hidden MCA villager, so <b>MCA's own</b> screen
     * opens with every MCA option working; a crouch click is replayed into Guard Villagers so its
     * inventory GUI still has a gesture of its own. With the option off, this does exactly what it
     * did before: offer the click to the addon suite and otherwise leave it alone.
     *
     * <p>Main hand only. A click offers both hands in turn, and cancelling the main-hand one with a
     * consuming result is what stops the off-hand pass -- so gating here removes the double fire the
     * event used to have.
     *
     * <p>{@link GuardInteractEvent} is posted for the plain click only. Its documented contract is
     * the MCA-style interaction, and an addon that cancels it takes MCA's place; the crouch gesture
     * belongs to Guard Villagers and is not offered around. "Not cancelled" now means MCA's screen
     * opens for the proxy.
     *
     * <p><b>Side effect worth knowing:</b> a name tag applies on the crouch gesture rather than the
     * plain one, because {@code Mob#interact} runs vanilla's name-tag handling first and that is
     * the path the crouch click takes. The sync pushes the new name up into MCA a second later.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof Mob guard) || !GuardBridge.isBridgedGuard(guard)) {
            return;
        }
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }

        Player player = event.getEntity();
        if (!ModIntegrations.guardVillagers().opensMcaInteraction()) {
            offerClick(event, guard, player);
            return;
        }

        // Both flags: isCrouching is the pose, isShiftKeyDown the key, and they disagree while
        // swimming or under a slab -- either one means the player meant the other gesture.
        if (player.isCrouching() || player.isShiftKeyDown()) {
            event.setCanceled(true);
            event.setCancellationResult(player instanceof ServerPlayer serverPlayer
                    ? GuardInteractions.delegateToGuardVillagers(serverPlayer, guard, event.getHand())
                    : InteractionResult.SUCCESS);
            return;
        }

        // The client cancels blind: it cannot open MCA's screen itself and must not let vanilla run
        // the click locally. The screen arrives from the server a moment later. Cancelling here is
        // safe because the interact packet has already been sent by the time Forge fires this.
        if (event.getLevel().isClientSide) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }
        if (!(player instanceof ServerPlayer serverPlayer)
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        // Created on demand as well as on tick: a player who clicks a guard in the first two
        // seconds after a chunk load must not get a dead click.
        Villager proxy = GuardProxy.proxyOf(guard);
        if (proxy == null) {
            proxy = GuardProxy.create(level, guard);
        }
        if (proxy == null) {
            // No MCA, or no villager type to build one from. Fall back to the stock behaviour
            // rather than eating the click.
            offerClick(event, guard, player);
            return;
        }
        GuardProxySync.noteInteraction(guard, player.getUUID());

        GuardIdentityStore.getOrCreate(guard);
        GuardInteractEvent offered = new GuardInteractEvent(player, guard, event.getHand());
        if (MinecraftForge.EVENT_BUS.post(offered)) {
            // An addon has taken this click. Honour its result and stay out of the way.
            event.setCanceled(true);
            event.setCancellationResult(offered.getCancellationResult());
            return;
        }

        InteractionResult result = forwardToProxy(serverPlayer, proxy, event.getHand());
        event.setCanceled(true);
        event.setCancellationResult(
                result == InteractionResult.PASS ? InteractionResult.SUCCESS : result);
    }

    /**
     * Replays the click onto the proxy exactly as though the player had clicked the villager.
     *
     * <p>Both Forge hooks are fired by hand first, because an addon listening for
     * {@code EntityInteractSpecific} or {@code EntityInteract} on an MCA villager -- which is how
     * MCAQuests notices a conversation -- would otherwise never see this click at all: nothing else
     * raises those events for the proxy. A non-null result from either means a listener cancelled,
     * and it is passed straight back.
     *
     * <p>{@code interactAt} first, because that is the packet MCA opens its screen from;
     * {@code interactOn} only if MCA passed, so a click that MCA did not want still gets vanilla's
     * villager behaviour.
     */
    private static InteractionResult forwardToProxy(ServerPlayer player, Villager proxy,
                                                    InteractionHand hand) {
        InteractionResult hooked = ForgeHooks.onInteractEntityAt(player, proxy, Vec3.ZERO, hand);
        if (hooked != null) {
            return hooked;
        }
        hooked = ForgeHooks.onInteractEntity(player, proxy, hand);
        if (hooked != null) {
            return hooked;
        }
        InteractionResult result = proxy.interactAt(player, Vec3.ZERO, hand);
        return result == InteractionResult.PASS ? player.interactOn(proxy, hand) : result;
    }

    /**
     * The pre-proxy behaviour, kept intact for {@code guardMcaInteraction=false}: offer the click
     * to the addon suite and otherwise let Guard Villagers have it.
     */
    private static void offerClick(PlayerInteractEvent.EntityInteract event, Mob guard, Player player) {
        // Crouching is Guard Villagers' own gesture and MCA's; the bridge stays out of it.
        if (player.isCrouching()) {
            return;
        }

        // Server side only, so the identity is created once, by the authoritative side.
        if (!event.getLevel().isClientSide) {
            GuardIdentityStore.getOrCreate(guard);
        }

        GuardInteractEvent offered = new GuardInteractEvent(player, guard, event.getHand());
        if (!MinecraftForge.EVENT_BUS.post(offered)) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(offered.getCancellationResult());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)) {
            return;
        }
        if (!(event.getEntity() instanceof Mob guard) || !GuardBridge.isBridgedGuard(guard)) {
            return;
        }
        // Never creates an identity for a guard that died without one -- a record of a nobody is
        // just a leak.
        if (GuardIdentityStore.find(guard).isEmpty()) {
            return;
        }
        GuardIdentityStore.get(level.getServer()).markDeceased(guard.getUUID(), level.getGameTime());
        McaMobCompat.debug("Recorded the death of bridged guard {}", guard.getUUID());
    }
}
