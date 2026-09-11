package dev.otectus.mcamobcompat.integration.guardvillagers;

import dev.otectus.mcamobcompat.McaMobCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
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
 * could not be bound this way at all.
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
        if (resolved != null && resolved.getGuardVariant != null) {
            try {
                Object value = resolved.getGuardVariant.invoke(guard);
                if (value instanceof String text && !text.isBlank()) {
                    return Optional.of(text);
                }
            } catch (Throwable t) {
                warnOnce("getGuardVariant", t);
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

    private static Handles handles(Mob guard) {
        return guard == null ? null : CACHE.computeIfAbsent(guard.getClass(), Handles::resolve);
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
        private final Method getGuardVariant;

        private Handles(Class<?> owner) {
            setFollowing = find(owner, "setFollowing", 1);
            setPatrolling = find(owner, "setPatrolling", 1);
            setPatrolPos = find(owner, "setPatrolPos", 1);
            setOwnerId = find(owner, "setOwnerId", 1);
            getGuardVariant = find(owner, "getGuardVariant", 0);
        }

        static Handles resolve(Class<?> owner) {
            return new Handles(owner);
        }

        /** By name and arity: the parameter types are vanilla, but the owner class is not nameable. */
        private static Method find(Class<?> owner, String name, int arity) {
            try {
                for (Method candidate : owner.getMethods()) {
                    if (candidate.getName().equals(name) && candidate.getParameterCount() == arity) {
                        return candidate;
                    }
                }
            } catch (LinkageError t) {
                warnOnce(name, t);
                return null;
            }
            warnOnce(name, new NoSuchMethodException(name + "/" + arity));
            return null;
        }
    }
}
