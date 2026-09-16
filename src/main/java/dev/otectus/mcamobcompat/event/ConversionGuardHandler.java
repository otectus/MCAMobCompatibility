package dev.otectus.mcamobcompat.event;

import net.minecraft.core.registries.BuiltInRegistries;
import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.bridge.GuardProxy;
import dev.otectus.mcamobcompat.integration.ModIntegrations;
import dev.otectus.mcamobcompat.integration.guardvillagers.GuardVillagersModule;
import dev.otectus.mcamobcompat.mca.McaEntities;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

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
 * <p><b>Recruiting instead of blocking.</b> With {@code recruitMcaVillagersAsGuards} on, the same
 * gesture does the thing the player was clearly trying to do -- it makes the villager a guard --
 * without destroying anything: the profession becomes {@code mca:guard} <em>in place</em>, so the
 * name, family tree, marriage, children, genetics and inventory are all still there afterwards, and
 * the weapon moves from the player's hand into the villager's. The cancel happens first either way,
 * so Guard Villagers' own {@code NORMAL} handler never sees the click.
 *
 * <p><b>Why the block is deliberately wider than the conversion.</b> The last gate on the Guard
 * Villagers side is a profession check against its own config, which cannot be read without naming
 * one of its classes -- the thing this mod does not do. So the block covers every case the
 * conversion could fire in and some it could not: crouch-right-clicking an MCA villager <em>while
 * holding a sword or crossbow</em> does nothing, rather than opening MCA's interaction screen. Every
 * other gesture is untouched, and blocking too much here costs one awkward interaction while
 * blocking too little costs somebody their family.
 */
@EventBusSubscriber(modid = McaMobCompat.MOD_ID)
public final class ConversionGuardHandler {

    private ConversionGuardHandler() {
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide || GuardProxy.isProxy(event.getTarget()) || !McaEntities.isMcaVillager(event.getTarget())) {
            return;
        }

        Player player = event.getEntity();
        if (!player.isCrouching() && !player.isShiftKeyDown()) {
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

        boolean recruiting = ModIntegrations.guardVillagers().recruitsAsGuards();
        event.setCanceled(true);
        event.setCancellationResult(recruiting ? InteractionResult.SUCCESS : InteractionResult.FAIL);

        // One message, server side, on the main-hand pass only -- the off-hand pass would otherwise
        // repeat it for the same click.
        if (event.getLevel().isClientSide || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        if (recruiting) {
            recruit(player, event.getTarget());
            return;
        }
        player.displayClientMessage(Component.translatable("message.mcamobcompat.conversion_blocked"), true);
        McaMobCompat.debug("Blocked a guard conversion of {} by {}",
                event.getTarget().getName().getString(), player.getName().getString());
    }

    /**
     * Turns an MCA villager into an MCA guard in place.
     *
     * <p>Nothing but the profession and the held item changes: {@code setVillagerData} is MCA's own
     * entry point for a profession change, so MCA updates whatever it keeps alongside it, and every
     * other piece of the villager -- family, genetics, memories, inventory -- is simply left alone.
     * That is the whole difference between this and the conversion it replaces.
     */
    private static void recruit(Player player, Entity target) {
        if (!(target instanceof Villager villager) || villager.isBaby() || !villager.getMainHandItem().isEmpty()) {
            return;
        }
        VillagerProfession profession =
                BuiltInRegistries.VILLAGER_PROFESSION.getOptional(GuardProxy.GUARD_PROFESSION).orElse(null);
        if (profession == null) {
            McaMobCompat.LOGGER.warn("No villager profession '{}' is registered, so {} cannot be "
                            + "recruited as a guard.", GuardProxy.GUARD_PROFESSION,
                    villager.getName().getString());
            return;
        }

        if (villager.getVillagerData().getProfession() == profession) return;
        villager.setVillagerData(villager.getVillagerData().setProfession(profession));
        villager.setItemSlot(EquipmentSlot.MAINHAND, player.getAbilities().instabuild ? player.getMainHandItem().copyWithCount(1) : player.getMainHandItem().split(1));
        player.displayClientMessage(
                Component.translatable("message.mcamobcompat.recruited", villager.getName()), true);
        McaMobCompat.debug("{} recruited {} as an MCA guard",
                player.getName().getString(), villager.getName().getString());
    }
}
