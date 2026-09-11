package dev.otectus.mcamobcompat.mca;

import dev.otectus.mcamobcompat.McaMobCompat;
import net.minecraft.world.entity.Entity;

import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Function;

/**
 * The one place in this mod that knows MCA's <em>class</em> names, and it knows them only as
 * strings. {@link McaEntities} knows MCA's entity ids; this knows its Java package layout.
 *
 * <p><b>Nothing here holds an MCA type.</b> Every handle is a {@link Class}, a {@link Method} or a
 * plain {@link Object}. Naming an MCA type in a field or a method signature -- even in a method that
 * is never called -- makes the JVM fail this class at verification time with a
 * {@code NoClassDefFoundError} on any install without MCA, and the byte-scan tripwires catch string
 * literals rather than types, so they would not warn about it.
 *
 * <p>This is the single class exempted from the dot-form half of
 * {@code NoOptionalModStaticLinkTest} and of {@code checkJarContents}, by exact class-file name. The
 * slash-form scan still applies here as it does everywhere: these are strings, not references.
 *
 * <p><b>Binding rule: only MCA's own member names may be reflected.</b> A name MCA inherits from
 * Minecraft is SRG-named ({@code m_..._}) in production and official-named in a dev run, so it
 * cannot be bound by name at all. {@code getVillagerBrain}, {@code getCurrentJob} and
 * {@code abandonJob} are MCA's own and are safe; anything like {@code tick} or {@code hurt} is not.
 *
 * <p>Resolution is best-effort and never fatal. A missing root leaves the whole binding unavailable;
 * a missing member disables only the feature that needs it, and any throw from a reflective call
 * logs one warning, retires that member for the session, and returns empty.
 */
public final class McaBinding {

    /**
     * Candidate package roots, newest layout first. MCA moved from {@code net.mca} to
     * {@code net.conczin.mca}, and its Forge build carries a Forgix {@code forge.} prefix on top of
     * either. First hit wins, so a dev run on the older pinned jar and a production server on the
     * newer one both bind.
     */
    private static final String[] PACKAGE_ROOTS = {
            "forge.net.conczin.mca", "forge.net.mca", "net.conczin.mca", "net.mca",
    };

    /** The probe class. Present in every MCA layout, and the anchor for every other member. */
    private static final String VILLAGER_CLASS = ".entity.VillagerEntityMCA";

    private static volatile boolean resolved;
    private static volatile String packageRoot;
    private static volatile Class<?> villagerClass;

    /** {@code VillagerEntityMCA#getVillagerBrain()}. Null means unavailable. */
    private static volatile Method getVillagerBrain;

    /** {@code VillagerBrain#getCurrentJob()}, off the return type above. Null means unavailable. */
    private static volatile Method getCurrentJob;

    /**
     * {@code VillagerBrain#abandonJob()}. Public and no-arg, so clearing a chore needs neither the
     * {@code Chore} enum nor a player to hand to {@code assignJob}.
     */
    private static volatile Method abandonJob;

    /**
     * {@code VillagerBrain#getPersonality()} and {@code #setPersonality(Personality)}.
     *
     * <p>Read for the bridged guard's identity, written only to migrate a personality this mod
     * rolled before the guard had a proxy. MCA rolls its own on {@code finalizeSpawn}, so an
     * unbound setter costs a migrated guard its old personality and nothing else.
     */
    private static volatile Method getPersonality;
    private static volatile Method setPersonality;

    /** {@code VillagerBrain#getMemories()} -- a map keyed by player UUID -- and the per-player one. */
    private static volatile Method getMemories;
    private static volatile Method getMemoriesForPlayer;

    /** {@code VillagerBrain#getMoveState()}, whose enum constants are MOVE, STAY and FOLLOW. */
    private static volatile Method getMoveState;

    /** {@code Memories#getHearts()} and {@code #modHearts(int)}, off the per-player memories type. */
    private static volatile Method getHearts;
    private static volatile Method modHearts;

    private McaBinding() {
    }

    /**
     * Binds against whatever MCA is installed. Called once from {@code FMLCommonSetupEvent}, where
     * every mod has finished loading; repeat calls are no-ops.
     */
    public static synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;

        Optional<String> root = probeRoot(McaBinding::loadVillagerClass);
        if (root.isEmpty()) {
            return;
        }
        packageRoot = root.get();
        villagerClass = loadVillagerClass(packageRoot).orElse(null);
        if (villagerClass == null) {
            return;
        }

        // One catch per member, so one MCA rename disables one feature rather than all of them.
        try {
            getVillagerBrain = villagerClass.getMethod("getVillagerBrain");
        } catch (ReflectiveOperationException | LinkageError t) {
            warnMissing("getVillagerBrain", t);
        }
        if (getVillagerBrain == null) {
            return;
        }

        // The brain type comes from that method's return type rather than a second class-name
        // literal: one name to keep in step with MCA instead of two.
        Class<?> brainClass = getVillagerBrain.getReturnType();
        try {
            getCurrentJob = brainClass.getMethod("getCurrentJob");
        } catch (ReflectiveOperationException | LinkageError t) {
            warnMissing("getCurrentJob", t);
        }
        try {
            abandonJob = brainClass.getMethod("abandonJob");
        } catch (ReflectiveOperationException | LinkageError t) {
            warnMissing("abandonJob", t);
        }

        // These five are looked up by name and arity rather than by signature: their parameter and
        // return types are MCA's own, and naming one here -- even as a Class literal -- would link
        // this class against MCA and defeat the whole design.
        getPersonality = findMethod(brainClass, "getPersonality", 0);
        setPersonality = findMethod(brainClass, "setPersonality", 1);
        getMemories = findMethod(brainClass, "getMemories", 0);
        getMemoriesForPlayer = findMethod(brainClass, "getMemoriesForPlayer", 1);
        getMoveState = findMethod(brainClass, "getMoveState", 0);

        // Same trick as the brain class itself: the memories type is the return type of the
        // accessor that hands one out, so there is one MCA name to keep in step instead of two.
        Method perPlayer = getMemoriesForPlayer;
        if (perPlayer != null) {
            Class<?> memoriesClass = perPlayer.getReturnType();
            getHearts = findMethod(memoriesClass, "getHearts", 0);
            modHearts = findMethod(memoriesClass, "modHearts", 1);
        }
    }

    /**
     * The one public method of this name and arity, or null with a single warning.
     *
     * <p>By name and arity because every signature involved names an MCA type. MCA has no
     * overloads among these members, so arity is enough to identify them; an ambiguous match is
     * treated as unbound rather than guessed at.
     */
    private static Method findMethod(Class<?> owner, String name, int arity) {
        Method found = null;
        try {
            for (Method candidate : owner.getMethods()) {
                if (candidate.getName().equals(name) && candidate.getParameterCount() == arity) {
                    if (found != null) {
                        found = null;
                        break;
                    }
                    found = candidate;
                }
            }
        } catch (LinkageError t) {
            warnMissing(name, t);
            return null;
        }
        if (found == null) {
            warnMissing(name, new NoSuchMethodException(name + "/" + arity));
        }
        return found;
    }

    /** True when the root and the brain accessor both bound. */
    public static boolean isAvailable() {
        return villagerClass != null && getVillagerBrain != null;
    }

    /** The bound package root, for logs and the {@code /mcamobcompat mca} command. */
    public static Optional<String> packageRoot() {
        return Optional.ofNullable(packageRoot);
    }

    /**
     * The chore this villager is currently running, by enum constant name ({@code NONE},
     * {@code HUNT}, ...), or empty if it is not an MCA villager or the binding is unavailable.
     */
    public static Optional<String> currentChore(Entity entity) {
        Method job = getCurrentJob;
        if (job == null || !isBoundVillager(entity)) {
            return Optional.empty();
        }
        Object brain = brainOf(entity);
        if (brain == null) {
            return Optional.empty();
        }
        try {
            Object current = job.invoke(brain);
            return current instanceof Enum<?> ? Optional.of(((Enum<?>) current).name()) : Optional.empty();
        } catch (Throwable t) {
            getCurrentJob = null;
            retire("getCurrentJob", t);
            return Optional.empty();
        }
    }

    /** Clears this villager's chore. False if it could not be done, for any reason. */
    public static boolean abandonChore(Entity entity) {
        Method abandon = abandonJob;
        if (abandon == null || !isBoundVillager(entity)) {
            return false;
        }
        Object brain = brainOf(entity);
        if (brain == null) {
            return false;
        }
        try {
            abandon.invoke(brain);
            return true;
        } catch (Throwable t) {
            abandonJob = null;
            retire("abandonJob", t);
            return false;
        }
    }

    /**
     * This villager's personality, by enum constant name ({@code CONFIDENT}, {@code SHY}, ...), or
     * empty when it is not an MCA villager or the member is unbound.
     */
    public static Optional<String> personality(Entity entity) {
        Method accessor = getPersonality;
        Object brain = boundBrain(entity, accessor);
        if (brain == null) {
            return Optional.empty();
        }
        try {
            Object value = accessor.invoke(brain);
            return value instanceof Enum<?> ? Optional.of(((Enum<?>) value).name()) : Optional.empty();
        } catch (Throwable t) {
            getPersonality = null;
            retire("getPersonality", t);
            return Optional.empty();
        }
    }

    /**
     * Sets this villager's personality by constant name, case-insensitively. False when the name is
     * not one MCA knows, or the member is unbound.
     */
    public static boolean setPersonality(Entity entity, String personality) {
        Method setter = setPersonality;
        Object brain = boundBrain(entity, setter);
        if (brain == null || personality == null || personality.isBlank()) {
            return false;
        }
        try {
            Class<?> type = setter.getParameterTypes()[0];
            if (!type.isEnum()) {
                return false;
            }
            Object constant = null;
            for (Object candidate : type.getEnumConstants()) {
                if (((Enum<?>) candidate).name().equalsIgnoreCase(personality)) {
                    constant = candidate;
                    break;
                }
            }
            if (constant == null) {
                return false;
            }
            setter.invoke(brain, constant);
            return true;
        } catch (Throwable t) {
            setPersonality = null;
            retire("setPersonality", t);
            return false;
        }
    }

    /**
     * This player's hearts with the villager, read from MCA's own memories.
     *
     * <p>Empty rather than zero when there is nothing to read: a player MCA has no memory of and a
     * player on zero hearts are different facts, and the bridge falls back to its own store for the
     * first one.
     */
    public static OptionalInt hearts(Entity entity, UUID player) {
        Method accessor = getMemories;
        Method reader = getHearts;
        Object brain = boundBrain(entity, accessor);
        if (brain == null || player == null || reader == null) {
            return OptionalInt.empty();
        }
        try {
            Object memories = accessor.invoke(brain);
            if (!(memories instanceof Map<?, ?> map)) {
                return OptionalInt.empty();
            }
            Object forPlayer = map.get(player);
            if (forPlayer == null) {
                return OptionalInt.empty();
            }
            Object value = reader.invoke(forPlayer);
            return value instanceof Number number
                    ? OptionalInt.of(number.intValue())
                    : OptionalInt.empty();
        } catch (Throwable t) {
            getMemories = null;
            retire("getMemories", t);
            return OptionalInt.empty();
        }
    }

    /** Moves this player's hearts with the villager. False if it could not be done, for any reason. */
    public static boolean modHearts(Entity entity, Player player, int delta) {
        Method accessor = getMemoriesForPlayer;
        Method mutator = modHearts;
        Object brain = boundBrain(entity, accessor);
        if (brain == null || player == null || mutator == null) {
            return false;
        }
        try {
            Object memories = accessor.invoke(brain, player);
            if (memories == null) {
                return false;
            }
            mutator.invoke(memories, delta);
            return true;
        } catch (Throwable t) {
            getMemoriesForPlayer = null;
            retire("getMemoriesForPlayer", t);
            return false;
        }
    }

    /**
     * The villager's movement intent, by enum constant name ({@code MOVE}, {@code STAY},
     * {@code FOLLOW}), or empty when unbound. This is what the proxy mirrors onto its guard.
     */
    public static Optional<String> moveState(Entity entity) {
        Method accessor = getMoveState;
        Object brain = boundBrain(entity, accessor);
        if (brain == null) {
            return Optional.empty();
        }
        try {
            Object value = accessor.invoke(brain);
            return value instanceof Enum<?> ? Optional.of(((Enum<?>) value).name()) : Optional.empty();
        } catch (Throwable t) {
            getMoveState = null;
            retire("getMoveState", t);
            return Optional.empty();
        }
    }

    /** The brain of a bound MCA villager, but only when {@code member} bound too. Null otherwise. */
    private static Object boundBrain(Entity entity, Method member) {
        return member == null || !isBoundVillager(entity) ? null : brainOf(entity);
    }

    /** One line per member, ok or missing, for the diagnostic command and the setup log line. */
    public static List<String> describeBindings() {
        List<String> lines = new ArrayList<>(11);
        lines.add("VillagerEntityMCA: " + state(villagerClass != null));
        lines.add("getVillagerBrain: " + state(getVillagerBrain != null));
        lines.add("getCurrentJob: " + state(getCurrentJob != null));
        lines.add("abandonJob: " + state(abandonJob != null));
        lines.add("getPersonality: " + state(getPersonality != null));
        lines.add("setPersonality: " + state(setPersonality != null));
        lines.add("getMemories: " + state(getMemories != null));
        lines.add("getMemoriesForPlayer: " + state(getMemoriesForPlayer != null));
        lines.add("getMoveState: " + state(getMoveState != null));
        lines.add("getHearts: " + state(getHearts != null));
        lines.add("modHearts: " + state(modHearts != null));
        return lines;
    }

    /**
     * The root probe, with the class lookup injected so it can be driven in a unit test on a
     * classpath that has neither MCA nor Minecraft on it. A probe that throws counts as a miss.
     */
    static Optional<String> probeRoot(Function<String, Optional<Class<?>>> probe) {
        for (String root : PACKAGE_ROOTS) {
            Optional<Class<?>> found;
            try {
                found = probe.apply(root);
            } catch (Throwable t) {
                continue;
            }
            if (found != null && found.isPresent()) {
                return Optional.of(root);
            }
        }
        return Optional.empty();
    }

    private static Optional<Class<?>> loadVillagerClass(String root) {
        try {
            return Optional.of(Class.forName(root + VILLAGER_CLASS, false,
                    McaBinding.class.getClassLoader()));
        } catch (ClassNotFoundException | LinkageError t) {
            return Optional.empty();
        }
    }

    /** Registry-id gate first -- cheap, and authoritative -- then the bound class. */
    private static boolean isBoundVillager(Entity entity) {
        Class<?> villager = villagerClass;
        return entity != null && villager != null && McaEntities.isMcaVillager(entity)
                && villager.isInstance(entity);
    }

    private static Object brainOf(Entity entity) {
        Method accessor = getVillagerBrain;
        if (accessor == null) {
            return null;
        }
        try {
            return accessor.invoke(entity);
        } catch (Throwable t) {
            getVillagerBrain = null;
            retire("getVillagerBrain", t);
            return null;
        }
    }

    private static void warnMissing(String member, Throwable t) {
        McaMobCompat.LOGGER.warn("MCA binding: '{}' not found under {} ({}). The feature that needs "
                + "it is disabled; everything else keeps working.", member, packageRoot, t.toString());
    }

    /** One warning, then the member is gone for the session, so a broken call cannot spam the log. */
    private static void retire(String member, Throwable t) {
        McaMobCompat.LOGGER.warn("MCA binding: '{}' threw and has been disabled for this session.",
                member, t);
    }

    private static String state(boolean ok) {
        return ok ? "ok" : "missing";
    }
}
