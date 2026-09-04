package dev.otectus.mcamobcompat.classification;

import dev.otectus.mcamobcompat.api.MobRole;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A memo table from some key to a role set, with bulk invalidation.
 *
 * <p>Generic in the key purely so the caching semantics can be unit-tested: the real cache is keyed
 * by {@code EntityType}, and an {@code EntityType} cannot be constructed without a bootstrapped
 * Minecraft registry, which a plain JUnit test does not have. {@link ClassificationCache} is the
 * one instance that matters.
 *
 * @param <K> the key type; must have stable equality, which registry singletons do
 */
public final class RoleCache<K> {

    private final Map<K, Set<MobRole>> entries = new ConcurrentHashMap<>();

    /** The cached roles for this key, computing and storing them on first use. */
    public Set<MobRole> get(K key, Function<K, Set<MobRole>> compute) {
        return entries.computeIfAbsent(key, compute);
    }

    /** Drops every cached answer. */
    public void invalidate() {
        entries.clear();
    }

    /** How many keys are currently memoised. */
    public int size() {
        return entries.size();
    }
}
