package dev.otectus.mcamobcompat.api;

import dev.otectus.mcamobcompat.bridge.GuardBridge;
import dev.otectus.mcamobcompat.bridge.GuardIdentityStore;
import dev.otectus.mcamobcompat.classification.ProviderRegistry;
import dev.otectus.mcamobcompat.classification.TagClassifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The public, read-only face of MCA: Mob Compatibility.
 *
 * <p>Deliberately tiny. Everything here is a query over the tag-and-provider classifier, so it can
 * stay source-compatible while the internals move; a bigger API in 0.1.0 would only be a bigger
 * thing to break later. Other mods should depend on this class and nothing else in this jar.
 *
 * <p>All methods are null-safe and none of them ever throw: a failed classification is an empty
 * answer, not an exception in somebody else's event handler.
 */
public final class MobCompatibilityAPI {

    private MobCompatibilityAPI() {
    }

    /** A farmed animal -- a cow, sheep, pig, chicken, or any mod's replacement for one. */
    public static boolean isLivestock(Entity entity) {
        return TagClassifier.hasRole(entity, MobRole.LIVESTOCK);
    }

    /** Something a villager could ride. */
    public static boolean isMount(Entity entity) {
        return TagClassifier.hasRole(entity, MobRole.MOUNT);
    }

    /** A village defender that is an NPC, such as {@code guardvillagers:guard}. */
    public static boolean isGuard(Entity entity) {
        return TagClassifier.hasRole(entity, MobRole.GUARD);
    }

    /** An owned companion animal. */
    public static boolean isPet(Entity entity) {
        return TagClassifier.hasRole(entity, MobRole.PET);
    }

    /**
     * Whether this entity is a guard that this mod gives an MCA-style identity to.
     *
     * <p>Narrower than {@link #isGuard}: it also requires Guard Villagers to be installed, the
     * bridge option to be on, and the entity not to be an MCA villager (which has all of this from
     * MCA already).
     */
    public static boolean isBridgedGuard(Entity entity) {
        return GuardBridge.isBridgedGuard(entity);
    }

    /**
     * This guard's MCA-style identity -- name, gender, personality, hearts.
     *
     * <p><b>This call may create the identity</b> if the guard does not have one yet, which stores
     * it, may set the guard's custom name, and fires
     * {@link dev.otectus.mcamobcompat.api.event.GuardIdentityCreatedEvent} on the Forge bus. Call it
     * from a listener for that event only with that re-entry in mind.
     *
     * <p>Always empty on the client, and always empty for anything
     * {@link #isBridgedGuard} rejects.
     */
    public static Optional<GuardIdentity> getGuardIdentity(Entity entity) {
        if (!(entity instanceof Mob guard)) {
            return Optional.empty();
        }
        return GuardIdentityStore.getOrCreate(guard);
    }

    /** Every role this entity holds: its type's tags plus anything the providers add. */
    public static Set<MobRole> getRoles(Entity entity) {
        return TagClassifier.rolesFor(entity);
    }

    /**
     * Every role this entity <em>type</em> holds, from tags alone.
     *
     * <p>The cheap overload: it answers straight from the per-type cache and needs no live entity,
     * so it suits menus, tooltips and bulk checks. Per-entity providers are not consulted, so a
     * tamed-only role such as {@link MobRole#MOUNT} on a modded horse will not appear here.
     */
    public static Set<MobRole> getRoles(EntityType<?> type) {
        return TagClassifier.rolesFor(type);
    }

    /**
     * The owning player's UUID, if this entity has one and reports it through vanilla.
     *
     * <p>Strictly read-only, and deliberately vanilla-only. It works for SWEM horses because
     * {@code SWEMHorseEntityBase extends AbstractHorse} and overrides the vanilla owner accessors,
     * and for the same reason it works for DragN's equines, vanilla horses, and tamed cats and
     * wolves -- without this jar naming a single class from any of those mods. Ownership is the
     * source mod's data; this reads it and never writes it.
     */
    public static Optional<UUID> getOwner(Entity entity) {
        if (entity instanceof AbstractHorse horse) {
            return Optional.ofNullable(horse.getOwnerUUID());
        }
        if (entity instanceof OwnableEntity ownable) {
            return Optional.ofNullable(ownable.getOwnerUUID());
        }
        return Optional.empty();
    }

    /**
     * Registers a provider that contributes per-entity roles. Call during
     * {@code FMLCommonSetupEvent}; registering later still works but earlier entities will already
     * have been classified without it.
     */
    public static void registerProvider(ClassificationProvider provider) {
        ProviderRegistry.register(provider);
    }
}
