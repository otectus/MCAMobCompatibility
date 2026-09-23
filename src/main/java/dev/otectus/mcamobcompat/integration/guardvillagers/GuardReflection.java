package dev.otectus.mcamobcompat.integration.guardvillagers;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.util.ModIds;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads and writes a Guard Villagers guard's own state, by method name, on its runtime class.
 *
 * <p><b>Why reflection at all.</b> The proxy carries MCA's movement intent -- Follow, Stay, Move --
 * and something has to put that intent onto the entity that actually walks. Guard Villagers has no
 * API and no events, so the only way is to call its own methods; and naming its class anywhere in
 * this jar is the one thing the architecture forbids.
 *
 * <p><b>Why by name is safe here.</b> These five are Guard Villagers' own members, not names
 * inherited from Minecraft, so they are never SRG-mangled: {@code setFollowing} is spelled the same
 * in a dev run and on a production server. A Minecraft-inherited name ({@code hurt}, {@code tick})
 * could not be bound this way at all. Each member is bound by its whole signature -- name,
 * parameter types and return type -- so a same-named method that means something else is refused.
 * The variant getter is {@code getGuardVariant()} on Guard Villagers for 1.20.1 and
 * {@code getVariant()} on 1.21.1; both are tried.
 *
 * <p>Nothing here holds a Guard Villagers type: the guard arrives as a vanilla {@link Mob} and
 * every handle is a {@link Method}. Every member is best-effort and warns exactly once, so a Guard
 * Villagers update that renames one costs that one behaviour and nothing else.
 */
public final class GuardReflection {

    /** Resolved per runtime class, because a pack may tag a defender from some other mod entirely. */
    private static final Map<Class<?>, Handles> CACHE = new ConcurrentHashMap<>();

    /** Members already warned about, so a missing method cannot spam the log every tick. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    /** The NBT key Guard Villagers writes its variant under; the fallback when the getter is gone. */
    private static final String VARIANT_TAG = "Variant";

    private GuardReflection() {
    }

    /** Puts the guard into, or out of, follow mode. Silent no-op when the member is unavailable. */
    public static void setFollowing(Mob guard, boolean following) {
        Handles resolved = handles(guard);
        invoke(guard, resolved == null ? null : resolved.setFollowing, "setFollowing", following);
    }

    /** Puts the guard into, or out of, patrol mode. */
    public static void setPatrolling(Mob guard, boolean patrolling) {
        Handles resolved = handles(guard);
        invoke(guard, resolved == null ? null : resolved.setPatrolling, "setPatrolling", patrolling);
    }

    /** Pins the guard's patrol to one block, which is how MCA's Stay is expressed. */
    public static void setPatrolPos(Mob guard, BlockPos pos) {
        Handles resolved = handles(guard);
        invoke(guard, resolved == null ? null : resolved.setPatrolPos, "setPatrolPos", pos);
    }

    /** Names the player the guard follows. Guard Villagers refuses to follow without one. */
    public static void setOwnerId(Mob guard, UUID owner) {
        Handles resolved = handles(guard);
        invoke(guard, resolved == null ? null : resolved.setOwnerId, "setOwnerId", owner);
    }

    /**
     * The guard's appearance variant -- {@code desert}, {@code taiga}, ... -- for
     * {@link GuardVariants}.
     *
     * <p>Falls back to reading the saved tag when the getter is unavailable, because the variant is
     * what makes the proxy resemble the guard and getting it wrong is visible to a player the
     * moment they open MCA's screen.
     */
    public static Optional<String> variant(Mob guard) {
        if (guard == null) {
            return Optional.empty();
        }
        Handles resolved = handles(guard);
        if (resolved != null && resolved.getVariant != null) {
            try {
                Object value = resolved.getVariant.invoke(guard);
                if (value instanceof String text && !text.isBlank()) {
                    return Optional.of(text);
                }
            } catch (Throwable t) {
                warnOnce(resolved.getVariant.getName(), t);
            }
        }
        try {
            String saved = guard.saveWithoutId(new CompoundTag()).getString(VARIANT_TAG);
            return saved.isBlank() ? Optional.empty() : Optional.of(saved);
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    private static void invoke(Mob guard, Method method, String name, Object argument) {
        if (guard == null || method == null || argument == null) {
            return;
        }
        try {
            method.invoke(guard, argument);
        } catch (Throwable t) {
            warnOnce(name, t);
        }
    }

    /**
     * The handles for this guard's runtime class -- but only for Guard Villagers' own entities. A
     * pack can tag another mod's defender as a guard, and a same-named method on that class means
     * whatever that mod says it means; this mod only knows what Guard Villagers' members do.
     */
    private static Handles handles(Mob guard) {
        if (guard == null) {
            return null;
        }
        ResourceLocation type = EntityType.getKey(guard.getType());
        if (type == null || !ModIds.GUARD_VILLAGERS.equals(type.getNamespace())) {
            return null;
        }
        return CACHE.computeIfAbsent(guard.getClass(), Handles::resolve);
    }

    private static void warnOnce(String member, Throwable t) {
        if (WARNED.add(member)) {
            McaMobCompat.LOGGER.warn("Guard Villagers member '{}' is unavailable ({}); the proxy's "
                    + "movement mirroring skips it and everything else keeps working.", member, t.toString());
        }
    }

    /** One runtime class's resolved members. Null fields are simply unavailable. */
    private static final class Handles {

        private final Method setFollowing;
        private final Method setPatrolling;
        private final Method setPatrolPos;
        private final Method setOwnerId;
        private final Method getVariant;

        private Handles(Class<?> owner) {
            setFollowing = find(owner, "setFollowing", void.class, boolean.class);
            setPatrolling = find(owner, "setPatrolling", void.class, boolean.class);
            setPatrolPos = find(owner, "setPatrolPos", void.class, BlockPos.class);
            setOwnerId = find(owner, "setOwnerId", void.class, UUID.class);
            Method variant = lookup(owner, "getGuardVariant", String.class);
            getVariant = variant != null ? variant : find(owner, "getVariant", String.class);
        }

        static Handles resolve(Class<?> owner) {
            return new Handles(owner);
        }

        /**
         * By exact signature: the parameter and return types are vanilla or JDK types, so they can be
         * named even though the owner class cannot. Warns once when the member is not there.
         */
        private static Method find(Class<?> owner, String name, Class<?> result, Class<?>... parameters) {
            Method method = lookup(owner, name, result, parameters);
            if (method == null) {
                warnOnce(name, new NoSuchMethodException(name + " with the expected signature"));
            }
            return method;
        }

        /** The public method with exactly this signature, or null. Never warns. */
        private static Method lookup(Class<?> owner, String name, Class<?> result, Class<?>... parameters) {
            try {
                Method method = owner.getMethod(name, parameters);
                return method.getReturnType() == result ? method : null;
            } catch (NoSuchMethodException | LinkageError | SecurityException e) {
                return null;
            }
        }
    }
}
