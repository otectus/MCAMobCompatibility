package dev.otectus.mcamobcompat.event;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.McaMobCompatConfig;
import dev.otectus.mcamobcompat.api.MobCompatibilityAPI;
import dev.otectus.mcamobcompat.bridge.GuardProxy;
import dev.otectus.mcamobcompat.integration.ModIntegrations;
import dev.otectus.mcamobcompat.integration.guardvillagers.GuardVillagersModule;
import dev.otectus.mcamobcompat.mca.McaEntities;
import dev.otectus.mcamobcompat.util.ConfigAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Makes guards defend MCA villagers, and stops them attacking MCA villagers.
 *
 * <p>Both halves close the same gap. Guard Villagers decides who to protect and who not to attack by
 * matching {@code Entity.getEncodeId()} against string lists in its own config, and both lists ship
 * naming {@code minecraft:villager}. An MCA villager encodes as {@code mca:male_villager}, so out of
 * the box it is on neither: guards will not come to its defence, and nothing stops them choosing it
 * as a target. Guard Villagers reads no entity-type tags at all, so there is no tag to add it to --
 * the behaviour has to be mirrored here.
 *
 * <p>Neither half references a Guard Villagers class. A guard is found through the
 * {@code mcamobcompat:guard} tag and commanded through vanilla {@code Mob.setTarget}, so this works
 * for any mod's guard NPC that a pack has tagged, not only that one.
 *
 * <p>Friendly fire is stopped at both ends: a guard is stopped from <em>choosing</em> an MCA
 * villager as a target, and any damage a guard deals to one -- a stray arrow aimed at a zombie behind
 * it, a sweeping blow -- is cancelled before it lands. The second half is what the target veto alone
 * could not reach, and it replaces the old "guard arrows hurt villagers is out of reach" limitation.
 *
 * <p>Deliberately not mirrored: appending the MCA villager ids to the Guard Villagers config lists
 * at runtime would be the smaller change, but writing to another mod's config file is exactly the
 * kind of reaching-in this mod refuses to do.
 */
@Mod.EventBusSubscriber(modid = McaMobCompat.MOD_ID)
public final class GuardDefenseHandler {

    private GuardDefenseHandler() {
    }

    @SubscribeEvent
    public static void onChangeTarget(LivingChangeTargetEvent event) {
        LivingEntity changer = event.getEntity();
        if (changer.level().isClientSide) {
            return;
        }
        LivingEntity newTarget = event.getNewTarget();
        if (!McaEntities.isMcaVillager(newTarget)) {
            return;
        }

        GuardVillagersModule guards = ModIntegrations.guardVillagers();

        // A guard has just decided to attack an MCA villager. Stop it.
        if (MobCompatibilityAPI.isGuard(changer)) {
            if (guards.preventsFriendlyFire()) {
                event.setCanceled(true);
                McaMobCompat.debug("Stopped {} from targeting the MCA villager {}",
                        changer.getName().getString(), newTarget.getName().getString());
            }
            return;
        }

        // Something else has decided to attack an MCA villager. Call for help.
        alertGuards(newTarget, changer);
    }

    /**
     * A guard's damage never lands on an MCA villager. Runs early so the villager takes no damage,
     * knockback or hurt reaction at all.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onAttack(LivingAttackEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide || !McaEntities.isMcaVillager(victim) || GuardProxy.isProxy(victim)) {
            return;
        }
        Entity source = event.getSource().getEntity();
        if (source instanceof LivingEntity attacker && attacker != victim && !McaEntities.isMcaVillager(attacker)
                && MobCompatibilityAPI.isGuard(attacker) && ModIntegrations.guardVillagers().preventsFriendlyFire()) {
            event.setCanceled(true);
            McaMobCompat.debug("Cancelled friendly fire from {} on the MCA villager {}",
                    attacker.getName().getString(), victim.getName().getString());
        }
    }

    @SubscribeEvent
    public static void onHurt(LivingHurtEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide || !McaEntities.isMcaVillager(victim)) {
            return;
        }
        if (event.getSource().getEntity() instanceof LivingEntity attacker) {
            alertGuards(victim, attacker);
        }
    }

    /**
     * Points nearby guards at whoever is attacking this villager, within the configured range and
     * cap. See {@link GuardAlerts} for the rules every alert obeys.
     */
    private static void alertGuards(LivingEntity victim, LivingEntity attacker) {
        if (!ModIntegrations.guardVillagers().protectsVillagers()) {
            return;
        }
        if (attacker == null || attacker == victim || !attacker.isAlive()) {
            return;
        }
        // A squabble between villagers, or a guard already doing its job, is not an attack to answer.
        if (McaEntities.isMcaVillager(attacker) || MobCompatibilityAPI.isGuard(attacker)) {
            return;
        }
        if (attacker instanceof Player
                && !ConfigAccess.bool(McaMobCompatConfig.SERVER.retaliateAgainstPlayers, false)) {
            return;
        }
        if (!(victim.level() instanceof ServerLevel level)) {
            return;
        }
        int alerted = GuardAlerts.alert(level, victim, attacker,
                ConfigAccess.integer(McaMobCompatConfig.SERVER.guardHelpRange, 32),
                ConfigAccess.integer(McaMobCompatConfig.SERVER.maxGuardsAlerted, 8));
        if (alerted > 0) {
            McaMobCompat.debug("Alerted {} guard(s) to {} attacking the MCA villager {}",
                    alerted, describe(attacker), victim.getName().getString());
        }
    }

    private static String describe(Entity entity) {
        return entity.getName().getString();
    }
}
