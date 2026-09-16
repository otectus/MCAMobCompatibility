package dev.otectus.mcamobcompat.event;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.McaMobCompatConfig;
import dev.otectus.mcamobcompat.api.MobCompatibilityAPI;
import dev.otectus.mcamobcompat.integration.ModIntegrations;
import dev.otectus.mcamobcompat.integration.guardvillagers.GuardVillagersModule;
import dev.otectus.mcamobcompat.mca.McaEntities;
import dev.otectus.mcamobcompat.util.ConfigAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.List;

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
 * <p>Deliberately not mirrored: appending the MCA villager ids to the Guard Villagers config lists
 * at runtime would be the smaller change, but writing to another mod's config file is exactly the
 * kind of reaching-in this mod refuses to do.
 */
@EventBusSubscriber(modid = McaMobCompat.MOD_ID)
public final class GuardDefenseHandler {

    @SubscribeEvent
    public static void onFriendlyFire(LivingIncomingDamageEvent event) {
        if (!event.getEntity().level().isClientSide && McaEntities.isMcaVillager(event.getEntity())
                && ModIntegrations.guardVillagers().preventsFriendlyFire()
                && MobCompatibilityAPI.isGuard(event.getSource().getEntity())) event.setCanceled(true);
    }

    private GuardDefenseHandler() {
    }

    @SubscribeEvent
    public static void onChangeTarget(LivingChangeTargetEvent event) {
        LivingEntity changer = event.getEntity();
        if (changer.level().isClientSide) {
            return;
        }
        LivingEntity newTarget = event.getNewAboutToBeSetTarget();
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

    @SubscribeEvent
    public static void onHurt(LivingDamageEvent.Post event) {
        if (event.getNewDamage() <= 0) return;
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide || !McaEntities.isMcaVillager(victim)) {
            return;
        }
        if (event.getSource().getEntity() instanceof LivingEntity attacker) {
            alertGuards(victim, attacker);
        }
    }

    /**
     * Points nearby guards at whoever is attacking this villager.
     *
     * <p>Only guards with no target of their own are redirected, so an ongoing fight is never
     * interrupted, and the number alerted is capped so one zombie in a large village cannot start a
     * village-wide pathfinding storm.
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

        int range = ConfigAccess.integer(McaMobCompatConfig.SERVER.guardHelpRange, 32);
        int max = ConfigAccess.integer(McaMobCompatConfig.SERVER.maxGuardsAlerted, 8);

        AABB search = victim.getBoundingBox().inflate(range);
        List<Mob> candidates = level.getEntitiesOfClass(Mob.class, search, mob ->
                mob != attacker
                        && mob.isAlive()
                        && mob.getTarget() == null
                        && MobCompatibilityAPI.isGuard(mob));

        int alerted = 0;
        for (Mob guard : candidates) {
            if (alerted >= max) {
                break;
            }
            if (!guard.canAttack(attacker)) {
                continue;
            }
            guard.setTarget(attacker);
            alerted++;
        }

        if (alerted > 0) {
            McaMobCompat.debug("Alerted {} guard(s) to {} attacking the MCA villager {}",
                    alerted, describe(attacker), victim.getName().getString());
        }
    }

    private static String describe(Entity entity) {
        return entity.getName().getString();
    }
}
