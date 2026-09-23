package dev.otectus.mcamobcompat.event;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.bridge.GuardProxy;
import dev.otectus.mcamobcompat.integration.ModIntegrations;
import dev.otectus.mcamobcompat.integration.guardvillagers.GuardVillagersModule;
import dev.otectus.mcamobcompat.mca.McaEntities;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

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
 *
 * <p><b>Cancel on both sides, before anything else.</b> Guard Villagers' conversion is not gated
 * to the server: on the client it also spawns a guard and calls {@code discard()} on the villager.
 * Cancelling only on the server leaves the client to run that half, and the recruiting player then
 * sees the villager vanish while the server still has it -- the "invisible villager" a later port
 * of this mod reintroduced by returning early on the client. The cancel below therefore runs on
 * both logical sides and precedes every side check; only the recruit and the message, which change
 * state, are server-only.
 */
@EventBusSubscriber(modid = McaMobCompat.MOD_ID)
public final class ConversionGuardHandler {

    private ConversionGuardHandler() {
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        // A hidden proxy is a guard's stand-in, not a villager to convert or recruit; clicks on it
        // are routed by GuardBridgeHandler at HIGHEST and never arrive here uncancelled.
        if (!McaEntities.isMcaVillager(event.getTarget()) || GuardProxy.isProxy(event.getTarget())) {
            return;
        }

        Player player = event.getEntity();
        // Both flags: isCrouching is the pose, isShiftKeyDown the key, and they disagree while
        // swimming or under a slab. Guard Villagers reads the pose; blocking on either is safe.
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

    /** Why a recruit did or did not happen. Each has a message the player sees. */
    enum Recruitment {
        RECRUITED("message.mcamobcompat.recruited"),
        ALREADY_GUARD("message.mcamobcompat.recruit.already_guard"),
        TOO_YOUNG("message.mcamobcompat.recruit.too_young"),
        HAND_OCCUPIED("message.mcamobcompat.recruit.hand_occupied"),
        UNAVAILABLE("message.mcamobcompat.recruit.unavailable");

        private final String messageKey;

        Recruitment(String messageKey) {
            this.messageKey = messageKey;
        }
    }

    /**
     * Turns an MCA villager into an MCA guard in place, or says why not.
     *
     * <p>Nothing but the profession and the held item changes: {@code setVillagerData} is MCA's own
     * entry point for a profession change, so MCA updates whatever it keeps alongside it, and every
     * other piece of the villager -- family, genetics, memories, inventory -- is simply left alone.
     * That is the whole difference between this and the conversion it replaces.
     *
     * <p>Every precondition is checked before anything changes, so a refused recruit changes
     * nothing: a child is not armed, a villager already holding something keeps it (the weapon
     * would otherwise overwrite it), and an existing guard is not recruited twice or handed a
     * second weapon. The weapon moves as one item with all its data; in creative mode a copy is
     * given and the player's stack is untouched.
     */
    private static void recruit(Player player, Entity target) {
        if (!(target instanceof Villager villager)) {
            return;
        }
        Recruitment outcome = recruitOutcome(player, villager);
        Component name = villager.getName();
        player.displayClientMessage(Component.translatable(outcome.messageKey, name), true);
        McaMobCompat.debug("{} tried to recruit {}: {}", player.getName().getString(), name.getString(), outcome);
    }

    private static Recruitment recruitOutcome(Player player, Villager villager) {
        VillagerProfession profession =
                BuiltInRegistries.VILLAGER_PROFESSION.getOptional(GuardProxy.GUARD_PROFESSION).orElse(null);
        if (profession == null
                || !GuardProxy.GUARD_PROFESSION.equals(BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession))) {
            McaMobCompat.LOGGER.warn("No villager profession '{}' is registered, so {} cannot be "
                    + "recruited as a guard.", GuardProxy.GUARD_PROFESSION, villager.getName().getString());
            return Recruitment.UNAVAILABLE;
        }
        if (villager.getVillagerData().getProfession() == profession) {
            return Recruitment.ALREADY_GUARD;
        }
        if (villager.isBaby()) {
            return Recruitment.TOO_YOUNG;
        }
        if (!villager.getMainHandItem().isEmpty()) {
            return Recruitment.HAND_OCCUPIED;
        }
        ItemStack weapon = player.getMainHandItem();
        if (weapon.isEmpty()) {
            return Recruitment.UNAVAILABLE;
        }

        ItemStack given = player.getAbilities().instabuild ? weapon.copyWithCount(1) : weapon.split(1);
        villager.setVillagerData(villager.getVillagerData().setProfession(profession));
        villager.setItemSlot(EquipmentSlot.MAINHAND, given);
        // MCA builds a villager's behaviours from its profession when the brain is made, and its own
        // profession change rebuilds the brain; a bare data change does not. Without this the new
        // guard would keep a civilian's behaviours until it next reloaded. MCA overrides this vanilla
        // method, and the rebuild keeps every memory.
        if (villager.level() instanceof ServerLevel level) {
            villager.refreshBrain(level);
        }
        return Recruitment.RECRUITED;
    }
}
