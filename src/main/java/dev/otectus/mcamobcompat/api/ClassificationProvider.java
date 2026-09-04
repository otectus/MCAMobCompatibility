package dev.otectus.mcamobcompat.api;

import net.minecraft.world.entity.Entity;

import java.util.Set;

/**
 * Contributes roles that a static tag cannot express, because they depend on the individual entity
 * rather than on its type.
 *
 * <p>Tags are the primary mechanism and should be preferred: they are cached per entity type, they
 * are editable by pack authors, and they cost nothing. A provider is for the cases where the answer
 * genuinely varies between two entities of the same type -- for example "this horse is a mount
 * because somebody has tamed it", which no tag can know.
 *
 * <p>Register one with {@link MobCompatibilityAPI#registerProvider(ClassificationProvider)} during
 * {@code FMLCommonSetupEvent}. Results are <b>not</b> cached, so implementations must be cheap and
 * side-effect free: they are called on every classification of a live entity. Return
 * {@link Set#of()} -- not null -- when there is nothing to add, which should be the common case.
 */
@FunctionalInterface
public interface ClassificationProvider {

    /**
     * Extra roles for this entity, on top of whatever its type's tags already gave it. Never null;
     * return {@link Set#of()} for "nothing to add". Must not throw -- an exception here is caught
     * and logged, and the provider is skipped for that call.
     */
    Set<MobRole> rolesFor(Entity entity);
}
