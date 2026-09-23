package dev.otectus.mcamobcompat.mca;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.util.ModIds;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.player.Player;

import java.util.Optional;
import java.util.UUID;

/**
 * MCA's brain memories, reached by registry id.
 *
 * <p>MCA registers its memories as ordinary vanilla {@link MemoryModuleType}s under the {@code mca}
 * namespace, so they need no reflection and no MCA class: the registry hands back a vanilla type,
 * and the villager's vanilla {@link Brain} reads and writes it. That makes this the preferred seam
 * into MCA's behaviour wherever one exists -- MCA's own tasks then act on the memory exactly as if
 * MCA had written it.
 *
 * <p>Every access checks that the brain actually registers the memory first. A vanilla
 * {@code Brain.getMemory} on a memory the brain does not know throws, and a memory missing from a
 * future MCA must cost the feature that needs it, not the server.
 *
 * <p>The ids below are identical in MCA 7.6 and 7.7 for 1.20.1 and in MCA 7.7 for 1.21.1.
 */
public final class McaMemories {

    /** The player an MCA villager is following, set by MCA's own Follow command. */
    public static final ResourceLocation PLAYER_FOLLOWING =
            McaMobCompat.location(ModIds.MCA, "player_following_memory");

    /** The enemy an MCA guard or archer is fighting; MCA's guard combat tasks act on it. */
    public static final ResourceLocation NEAREST_GUARD_ENEMY = McaMobCompat.location(ModIds.MCA, "nearest_guard_enemy");

    private McaMemories() {
    }

    /**
     * The registered memory type with this id, or null when MCA has not registered it. The memory
     * registry is a defaulted one, whose plain {@code get} answers an unknown id with vanilla's
     * {@code dummy}; the lookup is therefore the optional one, and the key is checked as well.
     */
    @SuppressWarnings("unchecked")
    public static <U> MemoryModuleType<U> type(ResourceLocation id) {
        MemoryModuleType<?> type = BuiltInRegistries.MEMORY_MODULE_TYPE.getOptional(id).orElse(null);
        return type != null && id.equals(BuiltInRegistries.MEMORY_MODULE_TYPE.getKey(type))
                ? (MemoryModuleType<U>) type : null;
    }

    /** Whether this entity's brain registers that memory at all. */
    public static boolean registers(LivingEntity entity, MemoryModuleType<?> type) {
        return entity != null && type != null && entity.getBrain().checkMemory(type, MemoryStatus.REGISTERED);
    }

    /** The UUID of the player MCA says this villager is following, if any. */
    public static Optional<UUID> followTarget(Entity entity) {
        if (!(entity instanceof LivingEntity living) || !McaEntities.isMcaVillager(entity)) {
            return Optional.empty();
        }
        MemoryModuleType<Object> type = type(PLAYER_FOLLOWING);
        if (!registers(living, type)) {
            return Optional.empty();
        }
        return living.getBrain().getMemory(type)
                .filter(Player.class::isInstance)
                .map(value -> ((Player) value).getUUID());
    }

    /**
     * Hands an MCA guard or archer an enemy, the way MCA's own guard sensor would.
     *
     * @return false when this villager's brain has no such memory (it is not an MCA villager, or
     *         MCA no longer registers it), in which case nothing was written
     */
    public static boolean setGuardEnemy(LivingEntity villager, LivingEntity enemy) {
        if (!McaEntities.isMcaVillager(villager)) {
            return false;
        }
        MemoryModuleType<LivingEntity> type = type(NEAREST_GUARD_ENEMY);
        if (!registers(villager, type)) {
            return false;
        }
        villager.getBrain().setMemory(type, enemy);
        return true;
    }

    /** Forgets that enemy, if it is the one MCA's guard combat is acting on. */
    public static void clearGuardEnemy(LivingEntity villager, LivingEntity enemy) {
        MemoryModuleType<LivingEntity> type = type(NEAREST_GUARD_ENEMY);
        if (registers(villager, type) && villager.getBrain().getMemory(type).orElse(null) == enemy) {
            villager.getBrain().eraseMemory(type);
        }
    }

    /** The enemy MCA's guard combat is currently acting on, if any. */
    public static Optional<LivingEntity> guardEnemy(LivingEntity villager) {
        MemoryModuleType<LivingEntity> type = type(NEAREST_GUARD_ENEMY);
        return registers(villager, type) ? villager.getBrain().getMemory(type) : Optional.empty();
    }
}
