package dev.otectus.mcamobcompat.classification;

import dev.otectus.mcamobcompat.api.ClassificationProvider;
import dev.otectus.mcamobcompat.api.MobRole;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;

import java.util.Set;

/**
 * Classifies any animal a player owns as a {@link MobRole#PET}, whichever mod it came from.
 *
 * <p>Like {@link TamedMountProvider}, a fact about the individual animal rather than its type: a
 * wild raccoon and somebody's tamed one are the same {@code EntityType}. Built on vanilla
 * {@link OwnableEntity} alone, so it covers vanilla's tamables and every mod's that extends them --
 * Alex's Mobs alone has twenty -- without naming a class. Equines are left to
 * {@code TamedMountProvider}: a tamed horse is a mount, not a pet.
 */
public final class OwnedAnimalProvider implements ClassificationProvider {

    public static final OwnedAnimalProvider INSTANCE = new OwnedAnimalProvider();

    private static final Set<MobRole> PET = Set.of(MobRole.PET);

    private OwnedAnimalProvider() {
    }

    @Override
    public Set<MobRole> rolesFor(Entity entity) {
        if (entity instanceof OwnableEntity ownable && !(entity instanceof AbstractHorse)
                && ownable.getOwnerUUID() != null) {
            return PET;
        }
        return Set.of();
    }
}
