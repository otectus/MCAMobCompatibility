package dev.otectus.mcamobcompat.mount;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.Optional;

/**
 * An integration's reason to refuse an MCA villager a particular mount, as a translation key.
 * Registered with {@link MountPolicy}; consulted only for MCA villagers.
 */
@FunctionalInterface
public interface MountRefusal {

    Optional<String> refusalFor(LivingEntity rider, Entity mount);
}
