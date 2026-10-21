package dev.otectus.mcamobcompat.mca;

import dev.otectus.mcamobcompat.McaMobCompat;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 *
 * <p><b>Two MCA lines are supported, and they differ.</b> 7.6 ({@code forge.net.mca}) has an enum
 * {@code Personality}; 7.7 ({@code forge.net.conczin.mca}) turned it into a registry type with
 * {@code getId()} and a static {@code get(ResourceLocation)}. Both shapes are handled here, and an
 * identifier MCA does not know is refused rather than guessed at, so a live personality is never
 * overwritten with something MCA would not have picked. Spawn rollback removes the proxy's own
 * family-tree node, and cleans up generated parents only where MCA can say which ones those are
 * ({@code FamilyTreeNode#probablyGenerated()}, present on every build in the probe fleet, 7.6.20
 * included); on a build without it only the proxy's own node goes.
 *
 * <p>Brain <em>memories</em> are not reflected at all: they are vanilla {@code MemoryModuleType}s
 * registered under MCA's namespace, so {@link McaMemories} reaches them by registry id. That is the
 * preferred seam wherever MCA offers one.
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

    /** The world family tree, used only to roll back a proxy whose spawn was rejected. */
    private static final String FAMILY_TREE_CLASS = ".server.world.data.FamilyTree";

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

    /**
     * {@code VillagerBrain#setMoveState(MoveState, Player)}: puts MCA's own order back to Move when the
     * native mod refused the one it recorded, so MCA's screen never claims an order nobody carries out.
     */
    private static volatile Method setMoveState;

    /** {@code VillagerLike#getGenetics()} and {@code Genetics#getGender()}: an identity's real gender. */
    private static volatile Method getGenetics;
    private static volatile Method getGender;

    /** Residency: how a proxy gets a home village, so the addons that key a deed to one find it. */
    private static volatile Method getResidency;
    private static volatile Method seekHome;
    private static volatile Method getHomeVillage;

    /** {@code Memories#getHearts()}, {@code #modHearts(int)} and {@code #setHearts(int)}. */
    private static volatile Method getHearts;
    private static volatile Method modHearts;
    private static volatile Method setHearts;

    /** 7.7 registry personalities: {@code Personality#getId()} and static {@code #get(ResourceLocation)}. */
    private static volatile Method personalityId;
    private static volatile Method personalityLookup;

    /** {@code FamilyTree#get(ServerLevel)}, {@code #getOrEmpty(UUID)}, {@code #remove(UUID)}. */
    private static volatile Method familyGet;
    private static volatile Method familyLookup;
    private static volatile Method familyRemove;

    /** Members that failed to bind or were retired, with the reason, for {@code /mcamobcompat mca}. */
    private static final Map<String, String> FAILURES = new ConcurrentHashMap<>();

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
        bindAgainst(McaBinding.class.getClassLoader());
    }

    /**
     * Binds every member against the MCA that {@code loader} can see. {@link #resolve} hands it this
     * class's own loader; {@code McaBindingProbeTest} hands it one loader per build in the family's
     * MCA probe fleet, after {@link #resetForProbe()}, so the members bound here are exactly the ones
     * that test checks against every supported MCA release.
     */
    static synchronized void bindAgainst(ClassLoader loader) {
        Optional<String> root = probeRoot(candidate -> loadVillagerClass(candidate, loader));
        if (root.isEmpty()) {
            return;
        }
        packageRoot = root.get();
        villagerClass = loadVillagerClass(packageRoot, loader).orElse(null);
        if (villagerClass == null) {
            return;
        }

        // The family tree is needed only to undo a proxy spawn that another mod rejected, and it
        // lives beside the villager rather than under it, so it is the one second class name here.
        try {
            Class<?> family = Class.forName(packageRoot + FAMILY_TREE_CLASS, false, loader);
            familyGet = family.getMethod("get", ServerLevel.class);
            familyLookup = family.getMethod("getOrEmpty", UUID.class);
            familyRemove = family.getMethod("remove", UUID.class);
            if (!Modifier.isStatic(familyGet.getModifiers())) {
                familyGet = null;
                warnMissing("FamilyTree.get", new NoSuchMethodException("get is not static"));
            }
        } catch (ReflectiveOperationException | LinkageError t) {
            warnMissing("FamilyTree", t);
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

        // These are looked up by name and arity rather than by signature: their parameter and
        // return types are MCA's own, and naming one here -- even as a Class literal -- would link
        // this class against MCA and defeat the whole design. The types are then read back off the
        // bound methods and checked, so a same-named member with a different shape is refused.
        getPersonality = findMethod(brainClass, "getPersonality", 0);
        if (getPersonality != null) {
            Class<?> type = getPersonality.getReturnType();
            setPersonality = findMethod(brainClass, "setPersonality", 1);
            if (setPersonality != null && setPersonality.getParameterTypes()[0] != type) {
                warnMissing("setPersonality", new NoSuchMethodException("setPersonality(" + type.getName() + ")"));
                setPersonality = null;
            }
            if (!type.isEnum()) {
                personalityId = typed(type, "getId", ResourceLocation.class);
                personalityLookup = typed(type, "get", Optional.class, ResourceLocation.class);
                if (personalityLookup != null && !Modifier.isStatic(personalityLookup.getModifiers())) {
                    warnMissing("Personality.get", new NoSuchMethodException("get is not static"));
                    personalityLookup = null;
                }
            }
        }
        getMemories = findMethod(brainClass, "getMemories", 0);
        if (getMemories != null && !Map.class.isAssignableFrom(getMemories.getReturnType())) {
            warnMissing("getMemories", new NoSuchMethodException("getMemories does not return a Map"));
            getMemories = null;
        }
        getMemoriesForPlayer = typed(brainClass, "getMemoriesForPlayer", null, Player.class);
        getMoveState = findMethod(brainClass, "getMoveState", 0);
        if (getMoveState != null && !getMoveState.getReturnType().isEnum()) {
            warnMissing("getMoveState", new NoSuchMethodException("getMoveState is not an enum"));
            getMoveState = null;
        }
        if (getMoveState != null) {
            setMoveState = typed(brainClass, "setMoveState", void.class, getMoveState.getReturnType(), Player.class);
        }

        // Gender lives in the genetics, not in the entity type: MCA's editor can change it on a
        // male_villager, so the type alone is not the person's gender.
        getGenetics = findMethod(villagerClass, "getGenetics", 0);
        if (getGenetics != null) {
            getGender = findMethod(getGenetics.getReturnType(), "getGender", 0);
            if (getGender != null && !getGender.getReturnType().isEnum()) {
                warnMissing("getGender", new NoSuchMethodException("getGender is not an enum"));
                getGender = null;
            }
        }

        // Residency, the same way: its type is the accessor's return type, so one MCA name to keep in
        // step. seekHome() is how MCA itself gives a villager a home village (0.2.2); without it a
        // proxy had none, MCA: Crime charged an assault on it to no community, and MCA: Reputation had
        // to fall back to the nearest village by position.
        getResidency = findMethod(villagerClass, "getResidency", 0);
        if (getResidency != null) {
            Class<?> residencyClass = getResidency.getReturnType();
            seekHome = typed(residencyClass, "seekHome", void.class);
            getHomeVillage = typed(residencyClass, "getHomeVillage", Optional.class);
        }

        // Same trick as the brain class itself: the memories type is the return type of the
        // accessor that hands one out, so there is one MCA name to keep in step instead of two.
        Method perPlayer = getMemoriesForPlayer;
        if (perPlayer != null) {
            Class<?> memoriesClass = perPlayer.getReturnType();
            getHearts = typed(memoriesClass, "getHearts", int.class);
            modHearts = typed(memoriesClass, "modHearts", void.class, int.class);
            setHearts = typed(memoriesClass, "setHearts", void.class, int.class);
        }
    }

    /** A public method with exactly these parameter types and, when given, this return type. */
    private static Method typed(Class<?> owner, String name, Class<?> result, Class<?>... parameters) {
        try {
            Method method = owner.getMethod(name, parameters);
            if (result != null && !result.isAssignableFrom(method.getReturnType())) {
                throw new NoSuchMethodException(name + " returns " + method.getReturnType().getName());
            }
            return method;
        } catch (ReflectiveOperationException | LinkageError t) {
            warnMissing(name, t);
            return null;
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
     * This villager's personality: the lower-case constant name on MCA 7.6 ({@code confident}), the
     * full registry id on 7.7 ({@code mca:confident}). Empty when it is not an MCA villager or the
     * member is unbound.
     */
    public static Optional<String> personality(Entity entity) {
        Method accessor = getPersonality;
        Object brain = boundBrain(entity, accessor);
        if (brain == null) {
            return Optional.empty();
        }
        try {
            return personalityName(accessor.invoke(brain));
        } catch (Throwable t) {
            getPersonality = null;
            retire("getPersonality", t);
            return Optional.empty();
        }
    }

    /** Either personality shape to its string form. Package-private for the unit test. */
    static Optional<String> personalityName(Object value) throws ReflectiveOperationException {
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof Enum<?> constant) {
            return Optional.of(constant.name().toLowerCase(Locale.ROOT));
        }
        Method reader = value.getClass().getMethod("getId");
        if (reader.getReturnType() != ResourceLocation.class) {
            return Optional.empty();
        }
        Object id = reader.invoke(value);
        return id instanceof ResourceLocation location ? Optional.of(location.toString()) : Optional.empty();
    }

    /**
     * The MCA personality value for this name, or null if MCA does not know it.
     *
     * <p>Accepts a bare name ({@code friendly}), which is what this mod's own roster rolls, or a
     * full id ({@code mca:friendly}, {@code custom:reserved}). A bare name is looked up in MCA's
     * namespace on 7.7 and case-insensitively among the constants on 7.6. Package-private for the
     * unit test.
     */
    static Object resolvePersonality(Class<?> type, String name) throws ReflectiveOperationException {
        if (name == null || name.isBlank()) {
            return null;
        }
        String trimmed = name.trim();
        if (type.isEnum()) {
            String bare = trimmed.contains(":") ? trimmed.substring(trimmed.indexOf(':') + 1) : trimmed;
            for (Object candidate : type.getEnumConstants()) {
                if (((Enum<?>) candidate).name().equalsIgnoreCase(bare)) {
                    return candidate;
                }
            }
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(trimmed.contains(":")
                ? trimmed : "mca:" + trimmed.toLowerCase(Locale.ROOT));
        if (id == null) {
            return null;
        }
        Method lookup = type.getMethod("get", ResourceLocation.class);
        if (!Modifier.isStatic(lookup.getModifiers()) || lookup.getReturnType() != Optional.class) {
            return null;
        }
        Object found = lookup.invoke(null, id);
        Object resolved = found instanceof Optional<?> option ? option.orElse(null) : null;
        return type.isInstance(resolved) ? resolved : null;
    }

    /**
     * Sets this villager's personality. False when the name is not one MCA knows, or the member is
     * unbound -- and in both cases MCA's existing value is left exactly as it was.
     */
    public static boolean setPersonality(Entity entity, String personality) {
        Method setter = setPersonality;
        Object brain = boundBrain(entity, setter);
        if (brain == null || personality == null || personality.isBlank()) {
            return false;
        }
        try {
            Object value = resolvePersonality(setter.getParameterTypes()[0], personality);
            if (value == null) {
                return false;
            }
            setter.invoke(brain, value);
            return true;
        } catch (Throwable t) {
            setPersonality = null;
            retire("setPersonality", t);
            return false;
        }
    }

    /** Whether a rejected proxy spawn can be undone in MCA's family tree. */
    public static boolean canRollbackSpawn() {
        return familyGet != null && familyLookup != null && familyRemove != null;
    }

    /**
     * Removes the family-tree node MCA created for a proxy whose spawn was then rejected.
     *
     * <p>The proxy's own node always goes. On 7.7, whose {@code finalizeSpawn} can also generate a
     * pair of parents for a new villager, a parent is removed too -- but only when MCA marks it
     * {@code probablyGenerated()} and its only child is this proxy, so no real villager's family
     * is ever touched. 7.6 has no such marker and generates no parents, so nothing else is done.
     */
    public static void rollbackSpawn(Entity proxy) {
        if (!canRollbackSpawn() || proxy == null || !(proxy.level() instanceof ServerLevel level)) {
            return;
        }
        UUID self = proxy.getUUID();
        try {
            Object tree = familyGet.invoke(null, level);
            Object node = ((Optional<?>) familyLookup.invoke(tree, self)).orElse(null);
            if (node == null) {
                return;
            }
            List<Object> parents = new ArrayList<>(2);
            for (String accessor : List.of("father", "mother")) {
                try {
                    parents.add(node.getClass().getMethod(accessor).invoke(node));
                } catch (NoSuchMethodException absent) {
                    // Nothing to clean up on a layout without parent links.
                }
            }
            familyRemove.invoke(tree, self);
            for (Object parentId : parents) {
                if (!(parentId instanceof UUID id)) {
                    continue;
                }
                Object parent = ((Optional<?>) familyLookup.invoke(tree, id)).orElse(null);
                if (parent == null) {
                    continue;
                }
                Method generated;
                try {
                    generated = parent.getClass().getMethod("probablyGenerated");
                } catch (NoSuchMethodException absent) {
                    continue;
                }
                Object children = parent.getClass().getMethod("children").invoke(parent);
                boolean onlyThisProxy = children instanceof Set<?> set
                        && (set.isEmpty() || (set.size() == 1 && set.contains(self)));
                if (Boolean.TRUE.equals(generated.invoke(parent)) && onlyThisProxy) {
                    familyRemove.invoke(tree, id);
                }
            }
        } catch (Throwable t) {
            retire("FamilyTree rollback", t);
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

    /** Every player MCA has a memory of, with their current hearts. Empty when unbound. */
    public static Map<UUID, Integer> heartSnapshot(Entity entity) {
        Method accessor = getMemories;
        Method reader = getHearts;
        Object brain = boundBrain(entity, accessor);
        if (brain == null || reader == null) {
            return Map.of();
        }
        try {
            Map<UUID, Integer> result = new HashMap<>();
            if (accessor.invoke(brain) instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> memory : map.entrySet()) {
                    if (memory.getKey() instanceof UUID player && memory.getValue() != null
                            && reader.invoke(memory.getValue()) instanceof Number hearts) {
                        result.put(player, hearts.intValue());
                    }
                }
            }
            return Map.copyOf(result);
        } catch (Throwable t) {
            getMemories = null;
            retire("getMemories", t);
            return Map.of();
        }
    }

    /** Moves this player's hearts with the villager. False if it could not be done, for any reason. */
    public static boolean modHearts(Entity entity, Player player, int delta) {
        return modHearts(entity, player, (long) delta);
    }

    /**
     * Moves this player's hearts by a delta that may exceed the int range, saturating rather than
     * wrapping. Uses {@code setHearts} when bound so the result is exact; falls back to
     * {@code modHearts} for a delta that fits.
     */
    public static boolean modHearts(Entity entity, Player player, long delta) {
        Method accessor = getMemoriesForPlayer;
        Object brain = boundBrain(entity, accessor);
        if (brain == null || player == null) {
            return false;
        }
        try {
            Object memories = accessor.invoke(brain, player);
            if (memories == null) {
                return false;
            }
            Method reader = getHearts;
            Method setter = setHearts;
            if (reader != null && setter != null) {
                long previous = ((Number) reader.invoke(memories)).longValue();
                long next = Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, previous + delta));
                setter.invoke(memories, (int) next);
                return true;
            }
            Method mutator = modHearts;
            if (mutator == null || delta < Integer.MIN_VALUE || delta > Integer.MAX_VALUE) {
                return false;
            }
            mutator.invoke(memories, (int) delta);
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

    /**
     * Sets MCA's own movement order on this villager -- {@code MOVE}, {@code STAY} or {@code FOLLOW} --
     * with no player recorded as the leader. False when unbound or the name is not one of MCA's.
     */
    public static boolean setMoveState(Entity entity, String state) {
        Method setter = setMoveState;
        Object brain = boundBrain(entity, setter);
        if (brain == null || state == null) {
            return false;
        }
        try {
            Class<?> type = setter.getParameterTypes()[0];
            for (Object constant : type.getEnumConstants()) {
                if (((Enum<?>) constant).name().equals(state)) {
                    setter.invoke(brain, constant, null);
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            setMoveState = null;
            retire("setMoveState", t);
            return false;
        }
    }

    /**
     * This villager's gender as MCA's genetics record it: {@code MALE}, {@code FEMALE},
     * {@code NEUTRAL} or {@code UNASSIGNED}. Empty when unbound.
     */
    public static Optional<String> gender(Entity entity) {
        Method genetics = getGenetics;
        Method reader = getGender;
        if (genetics == null || reader == null || !isBoundVillager(entity)) {
            return Optional.empty();
        }
        try {
            Object value = reader.invoke(genetics.invoke(entity));
            return value instanceof Enum<?> constant ? Optional.of(constant.name()) : Optional.empty();
        } catch (Throwable t) {
            getGender = null;
            retire("getGender", t);
            return Optional.empty();
        }
    }

    /**
     * The fields of one of MCA's editor save requests, read from the request object MCA's network
     * handed to the hook: the command, the target's UUID and the payload. Works on both request shapes
     * -- the class with private fields and {@code getData()} of the Forge builds, and the record of the
     * NeoForge builds. The payload is the live tag inside the request, so a sanitised edit of it is
     * what MCA then applies. Empty for anything that is not such a request.
     */
    public static Optional<EditorRequest> editorRequest(Object request) {
        if (request == null) {
            return Optional.empty();
        }
        Class<?> type = request.getClass();
        try {
            Object command = readMember(type, request, "command");
            Object uuid = readMember(type, request, "uuid");
            Object data;
            try {
                data = type.getMethod("getData").invoke(request);
            } catch (NoSuchMethodException absent) {
                data = type.getMethod("data").invoke(request);
            }
            if (command instanceof String text && uuid instanceof UUID id
                    && data instanceof net.minecraft.nbt.CompoundTag tag) {
                return Optional.of(new EditorRequest(text, id, tag));
            }
            return Optional.empty();
        } catch (Throwable t) {
            FAILURES.putIfAbsent("VillagerEditorSyncRequest", t.toString());
            return Optional.empty();
        }
    }

    /**
     * Loads MCA's editor save request class without initialising it, so the editor-save hook -- which
     * Mixin applies as a class is loaded -- has landed or not by the time this mod reports its hooks.
     * NeoForge loads MCA's payload classes only when the first one is used, long after setup. False when
     * the binding found no MCA, or this MCA has no such class.
     */
    public static boolean loadEditorRequestClass() {
        String root = packageRoot;
        if (root == null) {
            return false;
        }
        try {
            Class.forName(root + ".network.c2s.VillagerEditorSyncRequest", false, McaBinding.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }

    /** A record accessor where there is one, the private field of that name where there is not. */
    private static Object readMember(Class<?> type, Object target, String name) throws ReflectiveOperationException {
        try {
            return type.getMethod(name).invoke(target);
        } catch (NoSuchMethodException absent) {
            java.lang.reflect.Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        }
    }

    /** One editor save request: what MCA's editor asked for, and for whom. */
    public record EditorRequest(String command, UUID target, net.minecraft.nbt.CompoundTag data) {
    }

    /**
     * Asks MCA to give this villager a home village the way it would any other -- the nearest one
     * whose border reaches it -- and says whether the call could be made. Residency is what every
     * sibling addon keys a deed to: MCA: Reputation and MCA: Crime both resolve a victim's community
     * from it, so a proxy without one was a villager nobody's ledger could charge for.
     */
    public static boolean seekHome(Entity entity) {
        Method seek = seekHome;
        Method accessor = getResidency;
        if (seek == null || accessor == null || !isBoundVillager(entity)) {
            return false;
        }
        try {
            Object residency = accessor.invoke(entity);
            if (residency == null) {
                return false;
            }
            seek.invoke(residency);
            return true;
        } catch (Throwable t) {
            seekHome = null;
            retire("seekHome", t);
            return false;
        }
    }

    /** Whether MCA records a home village for this villager; false when unbound or unknown. */
    public static boolean hasHomeVillage(Entity entity) {
        Method home = getHomeVillage;
        Method accessor = getResidency;
        if (home == null || accessor == null || !isBoundVillager(entity)) {
            return false;
        }
        try {
            Object residency = accessor.invoke(entity);
            if (residency == null) {
                return false;
            }
            Object value = home.invoke(residency);
            return value instanceof Optional<?> optional && optional.isPresent();
        } catch (Throwable t) {
            getHomeVillage = null;
            retire("getHomeVillage", t);
            return false;
        }
    }

    /** The brain of a bound MCA villager, but only when {@code member} bound too. Null otherwise. */
    private static Object boundBrain(Entity entity, Method member) {
        return member == null || !isBoundVillager(entity) ? null : brainOf(entity);
    }

    /** One line per member, ok or missing, for the diagnostic command and the setup log line. */
    public static List<String> describeBindings() {
        List<String> lines = new ArrayList<>(BINDING_LINES);
        lines.add("VillagerEntityMCA: " + state(villagerClass != null));
        lines.add("getVillagerBrain: " + state(getVillagerBrain != null));
        lines.add("getCurrentJob: " + state(getCurrentJob != null));
        lines.add("abandonJob: " + state(abandonJob != null));
        lines.add("getPersonality: " + state(getPersonality != null));
        lines.add("setPersonality: " + state(setPersonality != null));
        lines.add("getMemories: " + state(getMemories != null));
        lines.add("getMemoriesForPlayer: " + state(getMemoriesForPlayer != null));
        lines.add("getMoveState: " + state(getMoveState != null));
        lines.add("setMoveState: " + state(setMoveState != null));
        lines.add("getGenetics/getGender: " + state(getGenetics != null && getGender != null));
        lines.add("getHearts: " + state(getHearts != null));
        lines.add("modHearts: " + state(modHearts != null));
        lines.add("setHearts: " + state(setHearts != null));
        lines.add("getResidency: " + state(getResidency != null));
        lines.add("seekHome: " + state(seekHome != null));
        lines.add("getHomeVillage: " + state(getHomeVillage != null));
        lines.add("personality shape: " + personalityShape());
        lines.add("FamilyTree rollback: " + state(canRollbackSpawn()));
        return lines;
    }

    /** How many lines {@link #describeBindings} always returns, bound or not. */
    public static final int BINDING_LINES = 19;

    /** Members that failed to bind or were retired this session, with the reason. */
    public static Map<String, String> failures() {
        return Map.copyOf(FAILURES);
    }

    private static String personalityShape() {
        Method accessor = getPersonality;
        if (accessor == null) {
            return "missing";
        }
        if (accessor.getReturnType().isEnum()) {
            return "enum (MCA 7.6)";
        }
        return personalityId != null && personalityLookup != null
                ? "registry (MCA 7.7)" : "unsupported";
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

    private static Optional<Class<?>> loadVillagerClass(String root, ClassLoader loader) {
        try {
            return Optional.of(Class.forName(root + VILLAGER_CLASS, false, loader));
        } catch (ClassNotFoundException | LinkageError t) {
            return Optional.empty();
        }
    }

    /**
     * Forgets every binding, so {@code McaBindingProbeTest} can bind one MCA build after another in
     * one JVM and then leave the unresolved state the rest of the unit suite expects. Test-only: the
     * game binds once, from common setup, and never unbinds.
     */
    static synchronized void resetForProbe() {
        resolved = false;
        packageRoot = null;
        villagerClass = null;
        getVillagerBrain = null;
        getCurrentJob = null;
        abandonJob = null;
        getPersonality = null;
        setPersonality = null;
        getMemories = null;
        getMemoriesForPlayer = null;
        getMoveState = null;
        setMoveState = null;
        getGenetics = null;
        getGender = null;
        getResidency = null;
        seekHome = null;
        getHomeVillage = null;
        getHearts = null;
        modHearts = null;
        setHearts = null;
        personalityId = null;
        personalityLookup = null;
        familyGet = null;
        familyLookup = null;
        familyRemove = null;
        FAILURES.clear();
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
        FAILURES.putIfAbsent(member, t.toString());
        McaMobCompat.LOGGER.warn("MCA binding: '{}' not found under {} ({}). The feature that needs "
                + "it is disabled; everything else keeps working.", member, packageRoot, t.toString());
    }

    /** One warning, then the member is gone for the session, so a broken call cannot spam the log. */
    private static void retire(String member, Throwable t) {
        FAILURES.putIfAbsent(member, "retired: " + t);
        McaMobCompat.LOGGER.warn("MCA binding: '{}' threw and has been disabled for this session.",
                member, t);
    }

    private static String state(boolean ok) {
        return ok ? "ok" : "missing";
    }
}
