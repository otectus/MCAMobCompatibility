package dev.otectus.mcamobcompat.event;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.integration.ModIntegrations;
import dev.otectus.mcamobcompat.integration.guardvillagers.GuardVillagersModule;
import dev.otectus.mcamobcompat.mca.McaEntities;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Stops Guard Villagers converting an MCA villager into a guard, which would destroy the villager.
 *
 * <p><b>The reason this mod exists.</b> Guard Villagers converts on crouch-and-right-click while
 * holding a weapon, and its only type check is {@code instanceof net.minecraft.world.entity.npc
 * .Villager}. MCA villagers extend vanilla {@code Villager}, so they pass it. Its default list of
 * convertible professions is {@code ["nitwit", "none"]}, and an unemployed MCA villager reports
 * {@code "none"} -- so this is reachable in a stock install, by accident, by any player holding a
 * sword. The conversion copies across position, custom name, invulnerability and the held stack,
 * then calls {@code discard()} on the villager. Family tree, marriage, children, genetics,
 * personality and MCA inventory are gone, and no restoration is possible because none of it is
 * recoverable from the guard that replaced them. Blocking is the only correct policy.
 *
 * <p><b>Why HIGH priority works.</b> Guard Villagers subscribes with a bare {@code @SubscribeEvent},
 * so it runs at {@code NORMAL} and does not receive cancelled events. Cancelling at {@code HIGH}
 * therefore reliably pre-empts it, with no mixin and no reference to any of its classes.
 *
 * <p><b>Why the block is deliberately wider than the conversion.</b> The last gate on the Guard
 * Villagers side is a profession check against its own config, which cannot be read without naming
 * one of its classes -- the thing this mod does not do. So the block covers every case the
 * conversion could fire in and some it could not: crouch-right-clicking an MCA villager <em>while
 * holding a sword or crossbow</em> does nothing, rather than opening MCA's interaction screen. Every
 * other gesture is untouched, and blocking too much here costs one awkward interaction while
 * blocking too little costs somebody their family.
 */
@Mod.EventBusSubscriber(modid = McaMobCompat.MOD_ID)
public final class ConversionGuardHandler {

    private ConversionGuardHandler() {
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!McaEntities.isMcaVillager(event.getTarget())) {
            return;
        }

        Player player = event.getEntity();
        if (!player.isCrouching()) {
            return;
        }

        // Main hand regardless of which hand raised the event, because that is what Guard Villagers
        // reads: a right-click with an empty off-hand still converts if a sword is in the main hand.
        ItemStack weapon = player.getMainHandItem();
        if (weapon.isEmpty() || !weapon.is(GuardVillagersModule.CONVERTIBLE_GUARD_ITEMS)) {
            return;
        }

        if (!ModIntegrations.guardVillagers().blocksConversion()) {
            return;
        }

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.FAIL);

        // One message, server side, on the main-hand pass only -- the off-hand pass would otherwise
        // repeat it for the same click.
        if (!event.getLevel().isClientSide && event.getHand() == InteractionHand.MAIN_HAND) {
            player.displayClientMessage(Component.translatable("message.mcamobcompat.conversion_blocked"), true);
            McaMobCompat.debug("Blocked a guard conversion of {} by {}",
                    event.getTarget().getName().getString(), player.getName().getString());
        }
    }
}
