package dev.otectus.mcamobcompat.integration.icysbetterhorses;

import dev.otectus.mcamobcompat.McaMobCompat;
import net.minecraft.world.entity.animal.horse.AbstractHorse;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads an Icy's Better Horses horse's own state, by method name, on its runtime class.
 *
 * <p>Icy's Better Horses mixes an interface into vanilla {@code AbstractHorse}, so every horse in a
 * world with it installed -- vanilla ones, and the breeds release 2.0 registers, which extend
 * vanilla {@code Horse} -- carries its {@code bh_getOwner}, {@code bh_isOwned} and
 * {@code bh_getCommand}; release 1.1.6 also has {@code bh_isHitched}, while 2.0 has no hitching
 * posts. Those are Icy's own member names, never SRG-mangled, so they can be bound by name exactly
 * as {@code GuardReflection} binds Guard Villagers' -- and no Icy class is named anywhere in this
 * jar. The command comes back as the name of Icy's enum constant ({@code WANDER}, {@code STAY}, ...).
 *
 * <p>Read-only by design: nothing here writes an owner, a command or anything else back.
 */
public final class IcyReflection {

    private static final Map<Class<?>, Handles> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private IcyReflection() {
    }

    /** The player Icy says owns this horse, if any. */
    public static Optional<UUID> owner(AbstractHorse horse) {
        Object value = call(horse, handles(horse).getOwner, "bh_getOwner");
        return value instanceof UUID uuid ? Optional.of(uuid) : Optional.empty();
    }

    /** Whether Icy says this horse belongs to a player. */
    public static boolean isOwned(AbstractHorse horse) {
        Object value = call(horse, handles(horse).isOwned, "bh_isOwned");
        return value instanceof Boolean owned ? owned : owner(horse).isPresent();
    }

    /** Whether the horse is tied to one of Icy's hitching posts. */
    public static boolean isHitched(AbstractHorse horse) {
        return call(horse, handles(horse).isHitched, "bh_isHitched") instanceof Boolean hitched && hitched;
    }

    /** Icy's command for this horse, by constant name, if it has one. */
    public static Optional<String> command(AbstractHorse horse) {
        Object value = call(horse, handles(horse).getCommand, "bh_getCommand");
        return value instanceof Enum<?> constant ? Optional.of(constant.name()) : Optional.empty();
    }

    private static Object call(AbstractHorse horse, Method method, String name) {
        if (horse == null || method == null) {
            return null;
        }
        try {
            return method.invoke(horse);
        } catch (Throwable t) {
            warnOnce(name, t);
            return null;
        }
    }

    private static Handles handles(AbstractHorse horse) {
        return horse == null ? Handles.NONE : CACHE.computeIfAbsent(horse.getClass(), Handles::new);
    }

    private static void warnOnce(String member, Throwable t) {
        if (WARNED.add(member)) {
            McaMobCompat.LOGGER.warn("Icy's Better Horses member '{}' is unavailable ({}); that part of the "
                    + "integration is skipped and everything else keeps working.", member, t.toString());
        }
    }

    private static final class Handles {

        private static final Handles NONE = new Handles(null);

        private final Method getOwner;
        private final Method isOwned;
        private final Method isHitched;
        private final Method getCommand;

        private Handles(Class<?> owner) {
            getOwner = find(owner, "bh_getOwner", true);
            isOwned = find(owner, "bh_isOwned", true);
            // Release 2.0 dropped hitching posts, so its absence is expected rather than a fault.
            isHitched = find(owner, "bh_isHitched", false);
            getCommand = find(owner, "bh_getCommand", true);
        }

        private static Method find(Class<?> owner, String name, boolean expected) {
            if (owner == null) {
                return null;
            }
            try {
                return owner.getMethod(name);
            } catch (NoSuchMethodException | LinkageError e) {
                if (expected) {
                    warnOnce(name, e);
                } else {
                    McaMobCompat.debug("Icy's Better Horses has no '{}' in this release; skipping it.", name);
                }
                return null;
            }
        }
    }
}
