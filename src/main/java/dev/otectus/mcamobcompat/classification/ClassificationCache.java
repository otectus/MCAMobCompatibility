package dev.otectus.mcamobcompat.classification;

import dev.otectus.mcamobcompat.api.MobRole;
import net.minecraft.world.entity.EntityType;

import java.util.Set;
import java.util.function.Function;

/**
 * Memoises the role set of each {@link EntityType}.
 *
 * <p>The performance promise of this mod (never scan the world, never do work per tick) rests on
 * this: a classification is a hash lookup, and the tag membership tests behind it run once per
 * entity type per reload rather than once per event. Entity types are registry singletons, so
 * identity equality is exactly right and the table is bounded by the number of registered types.
 *
 * <p><b>Only type-level results are cached.</b> A {@code ClassificationProvider} answers about an
 * individual entity -- a tamed horse versus a wild one of the same type -- so caching those would be
 * a correctness bug rather than an optimisation. {@link TagClassifier} applies them fresh each call.
 */
public final class ClassificationCache {

    private static final RoleCache<EntityType<?>> CACHE = new RoleCache<>();

    private ClassificationCache() {
    }

    /** The cached roles for this type, computing and storing them on first use. */
    public static Set<MobRole> get(EntityType<?> type, Function<EntityType<?>, Set<MobRole>> compute) {
        return CACHE.get(type, compute);
    }

    /**
     * Drops every cached answer.
     *
     * <p>Called when the tags themselves change ({@code TagsUpdatedEvent} -- world load and every
     * {@code /reload}) and when the config changes, since a disabled integration module suppresses
     * the roles of its whole namespace and that decision is baked into the cached value.
     */
    public static void invalidate() {
        CACHE.invalidate();
    }

    /** How many entity types have been classified since the last invalidation. For diagnostics. */
    public static int size() {
        return CACHE.size();
    }
}
