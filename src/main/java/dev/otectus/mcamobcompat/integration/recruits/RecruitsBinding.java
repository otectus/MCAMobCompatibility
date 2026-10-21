package dev.otectus.mcamobcompat.integration.recruits;

import dev.otectus.mcamobcompat.McaMobCompat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one place in this mod that knows Villager Recruits' class names, and it knows them only as
 * strings -- the same rule, and the same build-time allowlist, as {@code mca/McaBinding}.
 *
 * <p><b>Nothing here holds a Recruits type.</b> Every handle is a {@link Class}, a {@link Method} or a
 * {@link Field}; every signature this class declares uses vanilla and JDK types only. Naming a
 * Recruits type in a field or a method signature would fail this class at verification time on every
 * install without Recruits, which is most of them.
 *
 * <p><b>Only Recruits' own member names are bound.</b> {@code getFollowState}, {@code handleRecruiting}
 * and the rest are spelled the same in a dev run and in production; a name Recruits inherits from
 * Minecraft is SRG-named in production and is never bound here. Each member is bound by its whole
 * signature where the types are vanilla, and by name and arity, then checked, where they are Recruits'.
 *
 * <p>Pinned to the audited artifact, Villager Recruits 1.15.2 for Forge 1.20.1 (see
 * {@code docs/NATIVE_CHARACTER_COMPATIBILITY.md}). A member a later build renames disables only the
 * feature that needs it, and says so in {@code /mcamobcompat characters adapters}.
 */
public final class RecruitsBinding {

    private static final String ROOT = "com.talhanation.recruits.";

    private static volatile boolean resolved;
    private static volatile Class<?> recruitClass;
    private static volatile Class<?> nobleClass;
    private static volatile Class<?> companionClass;
    private static volatile Class<?> groupClass;
    private static volatile Class<?> promotedEvent;
    private static volatile Class<?> hiredEvent;
    private static volatile Class<?> dismissedEvent;

    private static volatile Method getOwnerUuid;
    private static volatile Method isOwned;
    private static volatile Method getFollowState;
    private static volatile Method setFollowState;
    private static volatile Method getGroup;
    private static volatile Method setGroupUuid;
    private static volatile Method getInventory;
    private static volatile Method canBeHired;
    private static volatile Method initSpawn;
    private static volatile Method disband;
    private static volatile Method isEffectedByCommand;
    private static volatile Method applyVariantFromVillager;
    private static volatile Method handleRecruiting;
    private static volatile Method eventRecruit;
    private static volatile Method promotingPlayer;
    private static volatile Method hiredPlayer;
    private static volatile Field groupsManager;
    private static volatile Method managerGetGroup;
    private static volatile Method managerPlayerGroups;
    private static volatile Method managerAddMember;
    private static volatile Method managerRemoveMember;
    private static volatile Method groupUuid;
    private static volatile Method addXp;
    private static volatile Method checkLevel;
    private static volatile Method applyRecruitValues;
    private static volatile Field maxXpForLevelUp;

    /** Members that failed to bind or were retired, with the reason, for the adapters report. */
    private static final Map<String, String> FAILURES = new ConcurrentHashMap<>();

    private RecruitsBinding() {
    }

    /** Binds against the installed Recruits. Call once, from common setup, only when it is loaded. */
    public static synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        bindAgainst(RecruitsBinding.class.getClassLoader());
    }

    static synchronized void bindAgainst(ClassLoader loader) {
        recruitClass = load(loader, "entities.AbstractRecruitEntity");
        if (recruitClass == null) {
            return;
        }
        nobleClass = load(loader, "entities.VillagerNobleEntity");
        companionClass = load(loader, "entities.ICompanion");
        groupClass = load(loader, "world.RecruitsGroup");
        promotedEvent = load(loader, "RecruitEvent$Promoted");
        hiredEvent = load(loader, "RecruitEvent$Hired");
        dismissedEvent = load(loader, "RecruitEvent$Dismissed");

        getOwnerUuid = typed(recruitClass, "getOwnerUUID", UUID.class);
        isOwned = typed(recruitClass, "isOwned", boolean.class);
        getFollowState = typed(recruitClass, "getFollowState", int.class);
        setFollowState = typed(recruitClass, "setFollowState", void.class, int.class);
        getGroup = typed(recruitClass, "getGroup", UUID.class);
        setGroupUuid = typed(recruitClass, "setGroupUUID", void.class, UUID.class);
        getInventory = typed(recruitClass, "getInventory", SimpleContainer.class);
        canBeHired = typed(recruitClass, "canBeHired", boolean.class);
        initSpawn = typed(recruitClass, "initSpawn", void.class);
        disband = typed(recruitClass, "disband", void.class, Player.class, boolean.class, boolean.class);
        isEffectedByCommand = typed(recruitClass, "isEffectedByCommand", boolean.class, UUID.class);
        applyVariantFromVillager = typed(recruitClass, "applyVariantFromVillager", void.class, recruitClass, Villager.class);
        if (applyVariantFromVillager != null && !Modifier.isStatic(applyVariantFromVillager.getModifiers())) {
            applyVariantFromVillager = null;
            warnMissing("applyVariantFromVillager", new NoSuchMethodException("applyVariantFromVillager is not static"));
        }

        Class<?> commands = load(loader, "CommandEvents");
        if (commands != null && groupClass != null) {
            handleRecruiting = typed(commands, "handleRecruiting", boolean.class, Player.class, groupClass,
                    recruitClass, boolean.class);
            if (handleRecruiting != null && !Modifier.isStatic(handleRecruiting.getModifiers())) {
                handleRecruiting = null;
                warnMissing("handleRecruiting", new NoSuchMethodException("handleRecruiting is not static"));
            }
        }
        Class<?> recruitEvent = load(loader, "RecruitEvent");
        if (recruitEvent != null) {
            eventRecruit = typed(recruitEvent, "getRecruit", recruitClass);
        }
        if (promotedEvent != null) {
            promotingPlayer = typed(promotedEvent, "getPromotingPlayer", Player.class);
        }
        if (hiredEvent != null) {
            hiredPlayer = typed(hiredEvent, "getPlayer", Player.class);
        }

        Class<?> events = load(loader, "RecruitEvents");
        if (events != null) {
            try {
                Field field = events.getField("recruitsGroupsManager");
                if (Modifier.isStatic(field.getModifiers())) {
                    groupsManager = field;
                    Class<?> manager = field.getType();
                    managerGetGroup = typed(manager, "getGroup", groupClass, UUID.class);
                    managerPlayerGroups = typed(manager, "getPlayerGroups", List.class, Player.class);
                    managerAddMember = typed(manager, "addMember", void.class, UUID.class, UUID.class, ServerLevel.class);
                    managerRemoveMember = typed(manager, "removeMember", void.class, UUID.class, UUID.class, ServerLevel.class);
                } else {
                    warnMissing("recruitsGroupsManager", new NoSuchFieldException("not static"));
                }
            } catch (ReflectiveOperationException | LinkageError t) {
                warnMissing("recruitsGroupsManager", t);
            }
        }
        if (groupClass != null) {
            groupUuid = typed(groupClass, "getUUID", UUID.class);
        }

        // Companion setup, exactly as Recruits' own hire-from-villager does it for a leader or a
        // specialist: four level-ups at the configured threshold, then the companion's own values.
        addXp = typed(recruitClass, "addXp", void.class, int.class);
        checkLevel = typed(recruitClass, "checkLevel", void.class);
        if (companionClass != null) {
            applyRecruitValues = typed(companionClass, "applyRecruitValues", void.class, recruitClass);
        }
        Class<?> config = load(loader, "config.RecruitsServerConfig");
        if (config != null) {
            try {
                Field field = config.getField("RecruitsMaxXpForLevelUp");
                maxXpForLevelUp = Modifier.isStatic(field.getModifiers()) ? field : null;
            } catch (ReflectiveOperationException | LinkageError t) {
                warnMissing("RecruitsMaxXpForLevelUp", t);
            }
        }
    }

    /**
     * Gives a newly hired companion -- captain, messenger, scout, patrol leader, siege engineer -- the
     * levels and values Recruits' own hire gives one. No-op for a plain recruit. False when a companion
     * could not be set up because a member is missing.
     */
    public static boolean setUpCompanion(Entity recruit) {
        if (!isCompanion(recruit)) {
            return true;
        }
        Method xp = addXp;
        Method level = checkLevel;
        Method values = applyRecruitValues;
        Field threshold = maxXpForLevelUp;
        if (xp == null || level == null || values == null || threshold == null) {
            return false;
        }
        try {
            Object configured = threshold.get(null);
            Object amount = configured == null ? null : configured.getClass().getMethod("get").invoke(configured);
            int perLevel = amount instanceof Integer number ? number : 250;
            for (int i = 0; i < 4; i++) {
                xp.invoke(recruit, perLevel);
                level.invoke(recruit);
            }
            values.invoke(recruit, recruit);
            return true;
        } catch (Throwable t) {
            applyRecruitValues = null;
            retire("companion setup", t);
            return false;
        }
    }

    /** Forgets every binding, so a probe test can bind a jar in its own loader. Test-only. */
    static synchronized void resetForProbe() {
        resolved = false;
        recruitClass = null;
        nobleClass = null;
        companionClass = null;
        groupClass = null;
        promotedEvent = null;
        hiredEvent = null;
        dismissedEvent = null;
        getOwnerUuid = null;
        isOwned = null;
        getFollowState = null;
        setFollowState = null;
        getGroup = null;
        setGroupUuid = null;
        getInventory = null;
        canBeHired = null;
        initSpawn = null;
        disband = null;
        isEffectedByCommand = null;
        applyVariantFromVillager = null;
        handleRecruiting = null;
        eventRecruit = null;
        promotingPlayer = null;
        hiredPlayer = null;
        groupsManager = null;
        managerGetGroup = null;
        managerPlayerGroups = null;
        managerAddMember = null;
        managerRemoveMember = null;
        groupUuid = null;
        addXp = null;
        checkLevel = null;
        applyRecruitValues = null;
        maxXpForLevelUp = null;
        FAILURES.clear();
    }

    /** Whether the actor-recognition core bound: the class and the owner and follow accessors. */
    public static boolean isAvailable() {
        return recruitClass != null && getOwnerUuid != null && getFollowState != null && setFollowState != null;
    }

    /** What is missing for the core to bind, for the adapters report; empty when nothing is. */
    public static Optional<String> problem() {
        if (recruitClass == null) {
            return Optional.of("AbstractRecruitEntity not found");
        }
        if (!isAvailable()) {
            return Optional.of("owner or follow-state accessors missing: " + FAILURES.keySet());
        }
        return Optional.empty();
    }

    /** Whether hiring an existing MCA villager can run through Recruits' own payment and ownership. */
    public static boolean canHire() {
        return isAvailable() && handleRecruiting != null && initSpawn != null && groupClass != null
                && setGroupUuid != null;
    }

    /** Whether a civilian return can release the recruit through Recruits' own {@code disband}. */
    public static boolean canDisband() {
        return isAvailable() && disband != null;
    }

    /** The event classes, for the loader's event bus; null when the build does not have them. */
    public static Class<?> promotedEventClass() {
        return promotedEvent;
    }

    public static Class<?> hiredEventClass() {
        return hiredEvent;
    }

    public static Class<?> dismissedEventClass() {
        return dismissedEvent;
    }

    /** Whether this entity is a Recruits recruit of any kind, workers included. */
    public static boolean isRecruit(Entity entity) {
        Class<?> type = recruitClass;
        return type != null && type.isInstance(entity);
    }

    /** Whether this class is a Recruits recruit class. */
    public static boolean isRecruitClass(Class<?> type) {
        Class<?> recruit = recruitClass;
        return recruit != null && type != null && recruit.isAssignableFrom(type);
    }

    public static boolean isNoble(Entity entity) {
        Class<?> type = nobleClass;
        return type != null && type.isInstance(entity);
    }

    /** Leaders and specialists: the promoted kinds, which carry companion state of their own. */
    public static boolean isCompanion(Entity entity) {
        Class<?> type = companionClass;
        return type != null && type.isInstance(entity);
    }

    public static Optional<UUID> owner(Entity recruit) {
        Object value = call(getOwnerUuid, "getOwnerUUID", recruit);
        return value instanceof UUID uuid ? Optional.of(uuid) : Optional.empty();
    }

    public static boolean owned(Entity recruit) {
        return Boolean.TRUE.equals(call(isOwned, "isOwned", recruit));
    }

    public static OptionalInt followState(Entity recruit) {
        Object value = call(getFollowState, "getFollowState", recruit);
        return value instanceof Integer state ? OptionalInt.of(state) : OptionalInt.empty();
    }

    public static boolean setFollowState(Entity recruit, int state) {
        return callVoid(setFollowState, "setFollowState", recruit, state);
    }

    public static Optional<UUID> group(Entity recruit) {
        Object value = call(getGroup, "getGroup", recruit);
        return value instanceof UUID uuid ? Optional.of(uuid) : Optional.empty();
    }

    public static Optional<SimpleContainer> inventory(Entity recruit) {
        Object value = call(getInventory, "getInventory", recruit);
        return value instanceof SimpleContainer container ? Optional.of(container) : Optional.empty();
    }

    public static boolean canBeHired(Entity recruit) {
        return Boolean.TRUE.equals(call(canBeHired, "canBeHired", recruit));
    }

    /** Whether Recruits would accept a command from this player for this recruit. */
    public static boolean acceptsCommandFrom(Entity recruit, UUID player) {
        Method method = isEffectedByCommand;
        if (method == null) {
            return owner(recruit).map(player::equals).orElse(false);
        }
        return Boolean.TRUE.equals(call(method, "isEffectedByCommand", recruit, player));
    }

    /** Recruits' own spawn initialisation: equipment, cost, spawn values. */
    public static boolean initSpawn(Entity recruit) {
        return callVoid(initSpawn, "initSpawn", recruit);
    }

    /** Recruits' own variant choice from the villager's biome type. */
    public static boolean applyVariantFromVillager(Entity recruit, Villager villager) {
        Method method = applyVariantFromVillager;
        if (method == null || !isRecruit(recruit)) {
            return false;
        }
        try {
            method.invoke(null, recruit, villager);
            return true;
        } catch (Throwable t) {
            applyVariantFromVillager = null;
            retire("applyVariantFromVillager", t);
            return false;
        }
    }

    /**
     * Recruits' own hiring: checks and takes the player's payment, sets the owner, the group and the
     * unit count, and posts its {@code Hired} event. True only when Recruits accepted the hire.
     */
    public static boolean handleRecruiting(Player player, Object group, Entity recruit) {
        Method method = handleRecruiting;
        Class<?> groupType = groupClass;
        if (method == null || groupType == null || !groupType.isInstance(group) || !isRecruit(recruit)) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(method.invoke(null, player, group, recruit, false));
        } catch (Throwable t) {
            handleRecruiting = null;
            retire("handleRecruiting", t);
            return false;
        }
    }

    /** Recruits' own dismissal: owner, team, group and unit count, without killing anything. */
    public static boolean disband(Entity recruit, Player player) {
        return callVoid(disband, "disband", recruit, player, false, false);
    }

    /** Sets the recruit's group by UUID, as Recruits' own conversion does after hiring. */
    public static boolean setGroup(Entity recruit, UUID group) {
        return callVoid(setGroupUuid, "setGroupUUID", recruit, group);
    }

    /** Whether this object is one of Recruits' groups. */
    public static boolean isGroup(Object group) {
        Class<?> type = groupClass;
        return type != null && type.isInstance(group);
    }

    public static Optional<UUID> groupUuid(Object group) {
        Method method = groupUuid;
        if (method == null || !isGroup(group)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable((UUID) method.invoke(group));
        } catch (Throwable t) {
            groupUuid = null;
            retire("RecruitsGroup.getUUID", t);
            return Optional.empty();
        }
    }

    /** The group with this UUID, from Recruits' own manager; empty when there is none. */
    public static Optional<Object> groupById(UUID id) {
        Object manager = manager();
        Method method = managerGetGroup;
        if (manager == null || method == null || id == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(method.invoke(manager, id));
        } catch (Throwable t) {
            managerGetGroup = null;
            retire("RecruitsGroupsManager.getGroup", t);
            return Optional.empty();
        }
    }

    /**
     * The player's groups, from Recruits' own manager, which creates the base groups for a player who
     * has none -- exactly what Recruits' own hire screen relies on.
     */
    public static List<Object> playerGroups(Player player) {
        Object manager = manager();
        Method method = managerPlayerGroups;
        if (manager == null || method == null || player == null) {
            return List.of();
        }
        try {
            Object value = method.invoke(manager, player);
            return value instanceof List<?> list ? new ArrayList<>(list) : List.of();
        } catch (Throwable t) {
            managerPlayerGroups = null;
            retire("RecruitsGroupsManager.getPlayerGroups", t);
            return List.of();
        }
    }

    /** Adds and removes group members by UUID, through Recruits' own manager. */
    public static boolean moveGroupMember(UUID group, UUID from, UUID to, ServerLevel level) {
        Object manager = manager();
        Method add = managerAddMember;
        Method remove = managerRemoveMember;
        if (manager == null || add == null || remove == null || group == null) {
            return false;
        }
        try {
            if (to != null) {
                add.invoke(manager, group, to, level);
            }
            if (from != null) {
                remove.invoke(manager, group, from, level);
            }
            return true;
        } catch (Throwable t) {
            managerAddMember = null;
            retire("RecruitsGroupsManager.addMember/removeMember", t);
            return false;
        }
    }

    /** The recruit an event is about, or null. */
    public static Entity eventRecruit(Object event) {
        Object value = call(eventRecruit, "RecruitEvent.getRecruit", event);
        return value instanceof Entity entity ? entity : null;
    }

    /** The player promoting a recruit, from a {@code Promoted} event, or null. */
    public static Player promotingPlayer(Object event) {
        Object value = call(promotingPlayer, "Promoted.getPromotingPlayer", event);
        return value instanceof Player player ? player : null;
    }

    /** The hiring player, from a {@code Hired} event, or null. */
    public static Player hiringPlayer(Object event) {
        Object value = call(hiredPlayer, "Hired.getPlayer", event);
        return value instanceof Player player ? player : null;
    }

    /** One line per member, ok or missing, for the adapters report. */
    public static List<String> describeBindings() {
        List<String> lines = new ArrayList<>();
        lines.add("AbstractRecruitEntity: " + state(recruitClass != null));
        lines.add("owner/follow accessors: " + state(isAvailable()));
        lines.add("handleRecruiting (native hire): " + state(handleRecruiting != null));
        lines.add("initSpawn: " + state(initSpawn != null));
        lines.add("applyVariantFromVillager: " + state(applyVariantFromVillager != null));
        lines.add("disband (native dismissal): " + state(disband != null));
        lines.add("groups manager: " + state(groupsManager != null && managerGetGroup != null));
        lines.add("Promoted event: " + state(promotedEvent != null && eventRecruit != null));
        lines.add("Hired event: " + state(hiredEvent != null));
        lines.add("companion setup: " + state(addXp != null && checkLevel != null && applyRecruitValues != null
                && maxXpForLevelUp != null));
        return lines;
    }

    public static Map<String, String> failures() {
        return Map.copyOf(FAILURES);
    }

    private static Object manager() {
        Field field = groupsManager;
        if (field == null) {
            return null;
        }
        try {
            return field.get(null);
        } catch (Throwable t) {
            groupsManager = null;
            retire("recruitsGroupsManager", t);
            return null;
        }
    }

    private static Class<?> load(ClassLoader loader, String name) {
        try {
            return Class.forName(ROOT + name, false, loader);
        } catch (ClassNotFoundException | LinkageError t) {
            warnMissing(name, t);
            return null;
        }
    }

    /** A public method with exactly these parameter types and a compatible return type, or null. */
    private static Method typed(Class<?> owner, String name, Class<?> result, Class<?>... parameters) {
        if (owner == null) {
            return null;
        }
        for (Class<?> parameter : parameters) {
            if (parameter == null) {
                warnMissing(name, new NoSuchMethodException(name + ": a parameter type is missing"));
                return null;
            }
        }
        try {
            Method method = owner.getMethod(name, parameters);
            if (result != null && result != void.class && !result.isAssignableFrom(method.getReturnType())) {
                throw new NoSuchMethodException(name + " returns " + method.getReturnType().getName());
            }
            if (result == void.class && method.getReturnType() != void.class) {
                throw new NoSuchMethodException(name + " returns " + method.getReturnType().getName());
            }
            return method;
        } catch (ReflectiveOperationException | LinkageError t) {
            warnMissing(name, t);
            return null;
        }
    }

    private static Object call(Method method, String name, Object target, Object... args) {
        if (method == null || target == null || !method.getDeclaringClass().isInstance(target)) {
            return null;
        }
        try {
            return method.invoke(target, args);
        } catch (Throwable t) {
            retire(name, t);
            return null;
        }
    }

    private static boolean callVoid(Method method, String name, Object target, Object... args) {
        if (method == null || target == null || !method.getDeclaringClass().isInstance(target)) {
            return false;
        }
        try {
            method.invoke(target, args);
            return true;
        } catch (Throwable t) {
            retire(name, t);
            return false;
        }
    }

    private static void warnMissing(String member, Throwable t) {
        if (FAILURES.putIfAbsent(member, t.toString()) == null) {
            McaMobCompat.LOGGER.warn("Villager Recruits binding: '{}' is unavailable ({}). The feature that "
                    + "needs it is disabled; everything else keeps working.", member, t.toString());
        }
    }

    private static void retire(String member, Throwable t) {
        if (FAILURES.putIfAbsent(member, "retired: " + t) == null) {
            McaMobCompat.LOGGER.warn("Villager Recruits binding: '{}' threw and is not used again this session.",
                    member, t);
        }
    }

    private static String state(boolean ok) {
        return ok ? "ok" : "missing";
    }
}
