package dev.otectus.mcamobcompat.event;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.api.GuardIdentity;
import dev.otectus.mcamobcompat.api.event.GuardInteractEvent;
import dev.otectus.mcamobcompat.bridge.GuardBridge;
import dev.otectus.mcamobcompat.bridge.GuardIdentityStore;
import dev.otectus.mcamobcompat.integration.ModIntegrations;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Optional;

/**
 * Keeps every bridged guard's identity up to date, and offers the guard's clicks to the addon suite.
 *
 * <p>Four small jobs, all server-authoritative except the interact event, which is offered on both
 * sides so that a listener cancelling a click can stop the client swinging as well:
 * <ul>
 *   <li><b>join</b> -- a guard entering a server level gets an identity if it has none. Eagerly,
 *       rather than lazily on first question, so a guard has a name the moment a player can see it,
 *       and so guards that were already in the world when the option was switched on get caught.</li>
 *   <li><b>interact</b> -- posts {@link GuardInteractEvent} ahead of Guard Villagers' own handler,
 *       the same {@code HIGH}-priority trick {@link ConversionGuardHandler} uses.</li>
 *   <li><b>hurt</b> -- a player hitting a guard moves that player's hearts.</li>
 *   <li><b>death</b> -- records the guard as deceased. The entry stays until purged.</li>
 * </ul>
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

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof Mob guard) || !GuardBridge.isBridgedGuard(guard)) {
            return;
        }

        // Crouching is Guard Villagers' own gesture and MCA's; the bridge stays out of it.
        Player player = event.getEntity();
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

    @SubscribeEvent
    public static void onHurt(LivingHurtEvent event) {
        if (event.getEntity().level().isClientSide) {
            return;
        }
        if (!(event.getEntity() instanceof Mob guard) || !GuardBridge.isBridgedGuard(guard)) {
            return;
        }
        if (!(event.getSource().getEntity() instanceof ServerPlayer attacker)) {
            return;
        }

        int delta = ModIntegrations.guardVillagers().guardHeartsLostPerHit();
        if (delta == 0) {
            return;
        }

        Optional<GuardIdentity> identity = GuardIdentityStore.getOrCreate(guard);
        identity.ifPresent(guardIdentity -> guardIdentity.modHearts(attacker.getUUID(), delta));
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
