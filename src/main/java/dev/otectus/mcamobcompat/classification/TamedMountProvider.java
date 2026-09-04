package dev.otectus.mcamobcompat.classification;

import dev.otectus.mcamobcompat.api.ClassificationProvider;
import dev.otectus.mcamobcompat.api.MobRole;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;

import java.util.Set;

/**
 * Classifies any tamed, saddled equine as a {@link MobRole#MOUNT}, whichever mod it came from.
 *
 * <p>This is the case a tag cannot express. Whether a horse is a mount is a fact about the
 * individual animal, not about its entity type: a wild horse on a plain and the one in somebody's
 * stable are the same {@code EntityType}. So the shipped tags name the equines we know about, and
 * this fills in the rest -- a horse from a mod nobody has written a tag for still reads as a mount
 * once a player has tamed it.
 *
 * <p>It is built on vanilla {@code AbstractHorse} alone, which is why it covers SWEM horses,
 * DragN's equines and any other mod extending the vanilla horse without naming one of their classes.
 */
public final class TamedMountProvider implements ClassificationProvider {

    public static final TamedMountProvider INSTANCE = new TamedMountProvider();

    private static final Set<MobRole> MOUNT = Set.of(MobRole.MOUNT);

    private TamedMountProvider() {
    }

    @Override
    public Set<MobRole> rolesFor(Entity entity) {
        if (entity instanceof AbstractHorse horse && horse.isTamed() && horse.getOwnerUUID() != null) {
            return MOUNT;
        }
        return Set.of();
    }
}
