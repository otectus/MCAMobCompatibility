package dev.otectus.mcamobcompat.classification;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.api.ClassificationProvider;
import dev.otectus.mcamobcompat.api.MobRole;
import dev.otectus.mcamobcompat.integration.ModIntegrations;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Turns an entity (or an entity type) into the set of {@link MobRole}s it belongs to.
 *
 * <p>Two layers, in order:
 * <ol>
 *   <li><b>Datapack tags</b> -- the primary mechanism, cached per entity type by
 *       {@link ClassificationCache}.</li>
 *   <li><b>{@link ClassificationProvider}s</b> -- per-entity additions that no tag could know,
 *       applied fresh on every call and never cached.</li>
 * </ol>
 */
public final class TagClassifier {

    private static final Set<Class<?>> WARNED_PROVIDERS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private TagClassifier() {
    }

    /**
     * Roles for an entity type, from tags alone.
     *
     * <p>Safe to call before tags are bound: membership simply reads as false, and
     * {@code TagsUpdatedEvent} drops the cache once real tags arrive, so the empty answer cannot
     * become stale.
     */
    public static Set<MobRole> rolesFor(EntityType<?> type) {
        if (type == null) {
            return Set.of();
        }
        return ClassificationCache.get(type, TagClassifier::computeTypeRoles);
    }

    /** Roles for a live entity: its type's tags, plus anything the providers add. */
    public static Set<MobRole> rolesFor(Entity entity) {
        if (entity == null || ModIntegrations.isSuppressed(entity.getType())) {
            return Set.of();
        }
        Set<MobRole> fromTags = rolesFor(entity.getType());
        if (ProviderRegistry.count() == 0) {
            return fromTags;
        }

        EnumSet<MobRole> combined = null;
        for (ClassificationProvider provider : ProviderRegistry.providers()) {
            Set<MobRole> extra;
            try {
                extra = provider.rolesFor(entity);
            } catch (Throwable t) {
                // A misbehaving third-party provider degrades its own contribution and nothing else.
                if (WARNED_PROVIDERS.add(provider.getClass())) McaMobCompat.LOGGER.warn("Classification provider {} threw; skipping it for this entity",
                        provider.getClass().getName(), t);
                continue;
            }
            if (extra == null || extra.isEmpty()) {
                continue;
            }
            if (combined == null) {
                combined = EnumSet.noneOf(MobRole.class);
                combined.addAll(fromTags);
            }
            combined.addAll(extra);
        }
        // No provider had anything to say, which is the common case: hand back the shared cached set
        // rather than a fresh copy of it.
        return combined == null ? fromTags : Collections.unmodifiableSet(combined);
    }

    /** True if this entity holds the given role. */
    public static boolean hasRole(Entity entity, MobRole role) {
        return rolesFor(entity).contains(role);
    }

    private static Set<MobRole> computeTypeRoles(EntityType<?> type) {
        // A disabled integration module makes this mod blind to its mod's entities entirely, which is
        // what "the module is off" has to mean -- not "off except the classification still fires".
        // The check is here rather than at query time so it is cached with the rest of the answer;
        // a config reload drops the cache, so the decision cannot go stale.
        if (ModIntegrations.isSuppressed(type)) {
            return Set.of();
        }

        EnumSet<MobRole> roles = EnumSet.noneOf(MobRole.class);
        for (MobRole role : MobRole.VALUES) {
            try {
                if (type.is(role.tag())) {
                    roles.add(role);
                }
            } catch (Throwable t) {
                // Tag lookup before the registry is bound, or a broken tag: treat as "not a member".
                // Never let classification take a server down (spec section 25: never crash).
                McaMobCompat.LOGGER.debug("Tag lookup for {} on {} failed; treating as not a member",
                        role.tagId(), EntityType.getKey(type), t);
            }
        }
        return roles.isEmpty() ? Set.of() : Collections.unmodifiableSet(roles);
    }
}
