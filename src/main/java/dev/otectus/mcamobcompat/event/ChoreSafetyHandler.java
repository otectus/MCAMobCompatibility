package dev.otectus.mcamobcompat.event;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.McaMobCompatConfig;
import dev.otectus.mcamobcompat.api.MobCompatibilityAPI;
import dev.otectus.mcamobcompat.api.MobRole;
import dev.otectus.mcamobcompat.mca.McaBinding;
import dev.otectus.mcamobcompat.mca.McaEntities;
import dev.otectus.mcamobcompat.util.ConfigAccess;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Set;

/**
 * Keeps an MCA villager's chores off the animals a player cares about.
 *
 * <p>This is restraint, not enablement. MCA's hunting task types its target as vanilla
 * {@code Animal} and excludes only {@code TamableAnimal}; Genetic Animals entities extend
 * {@code Animal}, and SWEM and DragN's equines extend {@code AbstractHorse}, which in 1.20.1 extends
 * {@code Animal} without being a {@code TamableAnimal}. A tamed, player-owned modded horse is
 * therefore already a legal hunting target, and this handler is what takes it back off the menu.
 *
 * <p><b>{@link LivingAttackEvent} is the primary hook, and it must stay that way.</b> The obvious
 * choice, {@link LivingChangeTargetEvent}, does not cover the case: MCA's hunting task holds its
 * quarry in a {@code private Animal target} field and drives {@code MemoryModuleType.ATTACK_TARGET}
 * itself. It never calls {@code Mob.setTarget}, which is the one thing Forge fires
 * {@code LivingChangeTargetEvent} from -- so a target-change handler alone would never see an MCA
 * hunt at all. Cancelling {@code LivingAttackEvent} returns from {@code hurt()} before damage,
 * knockback, sound and hurt animation. The target-change handler below is kept as belt and braces
 * for other mods and for a future MCA that does use {@code setTarget}; it is not the mechanism.
 *
 * <p>{@code LivingHurtEvent} is deliberately not handled: it is strictly later on the same call
 * path, so it would add cost and no coverage.
 *
 * <p>Gate order is a performance contract. {@code LivingAttackEvent} fires for every server-side
 * {@code hurt()} in the world, so the cheap checks come first -- server side, then one registry-id
 * lookup for "is the attacker an MCA villager" -- and roles, ownership and config are only touched
 * once that has passed.
 */
@Mod.EventBusSubscriber(modid = McaMobCompat.MOD_ID)
public final class ChoreSafetyHandler {

    /**
     * The parsed {@code chores.protectedRoles} option, and the raw list it came from.
     *
     * <p>Cached so that an unknown role name is reported once per config load rather than once per
     * attack, and so that the parse itself is not repeated on a hot path. A race between two threads
     * reparsing the same list is benign: both compute the same answer.
     */
    private static volatile Set<MobRole> cachedRoles;
    private static volatile List<? extends String> cachedNames;

    private ChoreSafetyHandler() {
    }

    @SubscribeEvent
    public static void onAttack(LivingAttackEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide) {
            return;
        }
        LivingEntity villager = mcaAttacker(event.getSource().getEntity(), event.getSource().getDirectEntity());
        if (villager == null) {
            return;
        }
        if (!isProtectedFrom(victim, villager)) {
            return;
        }

        event.setCanceled(true);
        McaMobCompat.debug("Blocked the MCA villager {} from hitting the protected animal {}",
                villager.getName().getString(), victim.getName().getString());
        abandonHuntIfConfigured(villager, victim);
    }

    /**
     * Belt and braces. It does not catch the hunting chore today -- see the class javadoc -- but it
     * costs one registry-id lookup and covers anything else that points an MCA villager at a
     * protected animal through vanilla targeting.
     */
    @SubscribeEvent
    public static void onChangeTarget(LivingChangeTargetEvent event) {
        LivingEntity villager = event.getEntity();
        if (villager.level().isClientSide || !McaEntities.isMcaVillager(villager)) {
            return;
        }
        LivingEntity newTarget = event.getNewTarget();
        if (newTarget == null || !isProtectedFrom(newTarget, villager)) {
            return;
        }

        event.setCanceled(true);
        McaMobCompat.debug("Stopped the MCA villager {} from targeting the protected animal {}",
                villager.getName().getString(), newTarget.getName().getString());
        abandonHuntIfConfigured(villager, newTarget);
    }

    /**
     * The MCA villager behind this damage, or null.
     *
     * <p>Both the source entity and the direct entity are checked: a villager swinging a sword is
     * the same entity in both, but a projectile it fired is the direct entity with the villager as
     * the source.
     */
    private static LivingEntity mcaAttacker(Entity source, Entity direct) {
        if (source instanceof LivingEntity living && McaEntities.isMcaVillager(living)) {
            return living;
        }
        if (direct instanceof LivingEntity living && McaEntities.isMcaVillager(living)) {
            return living;
        }
        return null;
    }

    /** Gathers the facts {@link ChoreProtectionPolicy} decides on. */
    private static boolean isProtectedFrom(LivingEntity victim, LivingEntity villager) {
        boolean alreadyTargeting = victim instanceof Mob mob && mob.getTarget() == villager;
        return ChoreProtectionPolicy.shouldProtect(
                MobCompatibilityAPI.getRoles(victim),
                MobCompatibilityAPI.getOwner(victim).isPresent(),
                victim.hasCustomName(),
                victim instanceof Mob mob && mob.isLeashed(),
                alreadyTargeting,
                protectedRoles(),
                ConfigAccess.bool(McaMobCompatConfig.SERVER.protectOwnedAnimals, true),
                ConfigAccess.bool(McaMobCompatConfig.SERVER.protectNamedAnimals, true),
                ConfigAccess.bool(McaMobCompatConfig.SERVER.protectLeashedAnimals, true));
    }

    /**
     * Tier 2: end the chore, not just this one swing.
     *
     * <p>Blocking the attack alone leaves MCA's hunting task holding a target it can never kill, so
     * the villager stands there swinging forever. Needs the reflective binding, and degrades to
     * plain blocking without a word when MCA has moved and the binding is dark.
     */
    private static void abandonHuntIfConfigured(LivingEntity villager, LivingEntity victim) {
        boolean hunting = McaBinding.currentChore(villager).filter("HUNT"::equals).isPresent();
        if (!ChoreProtectionPolicy.shouldAbandonHunt(true, hunting,
                ConfigAccess.bool(McaMobCompatConfig.SERVER.abandonHuntOnProtectedTarget, true))) {
            return;
        }
        if (McaBinding.abandonChore(villager)) {
            McaMobCompat.debug("Abandoned the hunting chore of the MCA villager {}; its quarry {} is protected",
                    villager.getName().getString(), victim.getName().getString());
        }
    }

    /** The configured protected roles, parsed once per config change rather than once per attack. */
    private static Set<MobRole> protectedRoles() {
        List<? extends String> names = ConfigAccess.strings(
                McaMobCompatConfig.SERVER.protectedRoles, List.of("mount", "pet"));
        Set<MobRole> roles = cachedRoles;
        if (roles != null && names.equals(cachedNames)) {
            return roles;
        }
        Set<MobRole> parsed = ChoreProtectionPolicy.parseRoles(names, unknown ->
                McaMobCompat.LOGGER.warn("Ignoring '{}' in chores.protectedRoles: it is not a role. "
                        + "Valid names are the tag paths under data/mcamobcompat/tags/entity_types/.", unknown));
        cachedRoles = parsed;
        cachedNames = List.copyOf(names);
        return parsed;
    }
}
