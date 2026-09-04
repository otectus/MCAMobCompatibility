package dev.otectus.mcamobcompat;

import dev.otectus.mcamobcompat.api.MobRole;
import dev.otectus.mcamobcompat.classification.RoleCache;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The caching semantics classification depends on.
 *
 * <p>Exercised through the generic {@link RoleCache} rather than {@code ClassificationCache}, which
 * is keyed by {@code EntityType} -- and an {@code EntityType} cannot be constructed without a
 * bootstrapped Minecraft registry that a plain JUnit test does not have. The behaviour under test is
 * the same object either way.
 */
class RoleCacheTest {

    @Test
    void computesOncePerKey() {
        RoleCache<String> cache = new RoleCache<>();
        AtomicInteger computations = new AtomicInteger();

        Set<MobRole> first = cache.get("cow", key -> {
            computations.incrementAndGet();
            return Set.of(MobRole.CATTLE, MobRole.LIVESTOCK);
        });
        Set<MobRole> second = cache.get("cow", key -> {
            computations.incrementAndGet();
            return Set.of(MobRole.GUARD);
        });

        assertEquals(1, computations.get(),
                "The second lookup recomputed. Classification runs from event handlers, so a cache miss "
                        + "per event is the difference between a hash lookup and a full tag scan.");
        assertSame(first, second, "The cached set should be handed back, not a copy of it.");
        assertEquals(Set.of(MobRole.CATTLE, MobRole.LIVESTOCK), second);
    }

    @Test
    void keysAreIndependent() {
        RoleCache<String> cache = new RoleCache<>();
        cache.get("cow", key -> Set.of(MobRole.CATTLE));
        cache.get("horse", key -> Set.of(MobRole.HORSE));

        assertEquals(2, cache.size());
        assertEquals(Set.of(MobRole.CATTLE), cache.get("cow", key -> Set.of()));
        assertEquals(Set.of(MobRole.HORSE), cache.get("horse", key -> Set.of()));
    }

    @Test
    void invalidateForcesRecomputation() {
        RoleCache<String> cache = new RoleCache<>();
        cache.get("cow", key -> Set.of(MobRole.CATTLE));
        assertEquals(1, cache.size());

        cache.invalidate();
        assertEquals(0, cache.size());

        // A datapack reload can genuinely change the answer, which is the entire reason invalidation
        // exists: a stale cache would keep a mob classified by tags that no longer say so.
        Set<MobRole> afterReload = cache.get("cow", key -> Set.of(MobRole.PET));
        assertEquals(Set.of(MobRole.PET), afterReload);
    }

    @Test
    void emptyResultsAreCachedToo() {
        RoleCache<String> cache = new RoleCache<>();
        AtomicInteger computations = new AtomicInteger();

        for (int i = 0; i < 3; i++) {
            cache.get("unclassified_mob", key -> {
                computations.incrementAndGet();
                return Set.of();
            });
        }

        // Most entities in a modpack match no role at all, so this is the hot path, not the edge case.
        assertEquals(1, computations.get(), "An empty role set must be cached like any other.");
    }
}
