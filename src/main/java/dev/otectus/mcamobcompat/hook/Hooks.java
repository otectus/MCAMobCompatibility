package dev.otectus.mcamobcompat.hook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Which of this mod's optional hooks are actually in place on this installation, and how often each
 * has run.
 *
 * <p>A hook is a narrow Mixin injection into another mod's method, used only where that mod offers no
 * event and data would otherwise be lost before any event could fire (see
 * {@code docs/NATIVE_CHARACTER_COMPATIBILITY.md}, "Hook contracts"). Every injection is optional and
 * targets the other mod by name, so a build that renamed a method simply gets no injection. That must
 * never pass for support: {@link HookPlugin} counts the injected calls in each transformed class and
 * records them here, and a feature that depends on a hook asks {@link #armed} before it offers itself.
 * An unarmed hook is reported, loudly, by {@code /mcamobcompat characters adapters} and in the log.
 *
 * <p>Plain Java, no Minecraft type, because the Mixin plugin writes to it before the game has started.
 */
public final class Hooks {

    /** MCA's editor save, before and after it is applied: validation of bridged characters' edits. */
    public static final String MCA_EDITOR_SAVE = "mca.editor_save";

    /** Villager Recruits turning a villager with a recruit profession into a recruit. */
    public static final String RECRUITS_CREATE_RECRUIT = "recruits.create_recruit";

    /** Villager Recruits turning a jobless villager into a noble. */
    public static final String RECRUITS_CREATE_NOBLE = "recruits.create_noble";

    /** Villager Recruits' (and Workers') hire of a villager picked on a noble's screen. */
    public static final String RECRUITS_HIRE_FROM_VILLAGER = "recruits.hire_from_villager";

    /**
     * One hook's contract: what it hooks, where, and what happens when it is missing. Targets are
     * described, not named in full: the dotted names live only in the mixin classes themselves, the
     * build-time allowlist's exact entries.
     */
    public record Contract(String id, String owner, String target, String descriptor, String artifact,
                           String side, String original, String handlers, String failure) {
    }

    /** The contract table, also printed in the compatibility document. Order is report order. */
    public static final List<Contract> CONTRACTS = List.of(
            new Contract(MCA_EDITOR_SAVE, "mca",
                    "<mca root>.network.c2s.VillagerEditorSyncRequest",
                    "receive(Lnet/minecraft/server/level/ServerPlayer;)V (Forge) / handleServer(...)V (NeoForge)",
                    "MCA Reborn 7.6.20-7.7.1-beta.2 (Forge 1.20.1), 7.7.x (NeoForge 1.21.1)",
                    "server", "runs, with the payload rebuilt; skipped when refused",
                    "head + every return", "bridged characters cannot be opened in MCA's editor"),
            new Contract(RECRUITS_CREATE_RECRUIT, "recruits",
                    "VillagerEvents (Villager Recruits root package)",
                    "createRecruit(Lnet/minecraft/world/entity/npc/Villager;Lnet/minecraft/world/entity/EntityType;)V",
                    "Villager Recruits 1.15.2 (Forge 1.20.1)", "server",
                    "runs for vanilla villagers; skipped for MCA villagers", "head",
                    "MCA villagers are not protected from profession conversion; warned at start"),
            new Contract(RECRUITS_CREATE_NOBLE, "recruits",
                    "VillagerEvents (Villager Recruits root package)",
                    "createNobleVillager(Lnet/minecraft/world/entity/npc/Villager;)V",
                    "Villager Recruits 1.15.2 (Forge 1.20.1)", "server",
                    "runs for vanilla villagers; skipped for MCA villagers", "head",
                    "MCA villagers are not protected from becoming nobles; warned at start"),
            new Contract(RECRUITS_HIRE_FROM_VILLAGER, "recruits",
                    "VillagerEvents (Villager Recruits root package)",
                    "createHiredRecruitFromVillager(Lnet/minecraft/server/level/ServerLevel;"
                            + "Lnet/minecraft/world/entity/npc/Villager;Lnet/minecraft/world/entity/EntityType;"
                            + "Lnet/minecraft/world/entity/player/Player;L...RecruitsGroup;)V",
                    "Villager Recruits 1.15.2 (Forge 1.20.1)", "server",
                    "runs for vanilla villagers; replaced by the enlistment for MCA villagers", "head",
                    "enlistment through a noble is unavailable and MCA villagers are not protected from it; "
                            + "warned at start"));

    /** Injected handler name fragment to the hook it belongs to, and how many of that kind it needs. */
    static final Map<String, String> HANDLERS = Map.of(
            "mcamobcompat$editorHead", MCA_EDITOR_SAVE,
            "mcamobcompat$editorReturn", MCA_EDITOR_SAVE,
            "mcamobcompat$createRecruit", RECRUITS_CREATE_RECRUIT,
            "mcamobcompat$createNoble", RECRUITS_CREATE_NOBLE,
            "mcamobcompat$hireFromVillager", RECRUITS_HIRE_FROM_VILLAGER);

    /** Handler name fragment to how many call sites the plugin found for it. */
    private static final Map<String, Integer> INJECTED = new ConcurrentHashMap<>();

    /** Hook to the class its injections landed in. */
    private static final Map<String, String> TARGETS = new ConcurrentHashMap<>();

    private static final Map<String, AtomicLong> CALLS = new ConcurrentHashMap<>();

    private Hooks() {
    }

    /** Records the call sites found for one handler in one transformed class. Called by the plugin. */
    static void recordInjections(String handler, String targetClass, int count) {
        String hook = HANDLERS.get(handler);
        if (hook == null || count <= 0) {
            return;
        }
        INJECTED.merge(handler, count, Integer::sum);
        TARGETS.put(hook, targetClass);
    }

    /** Whether every handler a hook needs was injected at least once. */
    public static boolean armed(String hook) {
        boolean any = false;
        for (Map.Entry<String, String> handler : HANDLERS.entrySet()) {
            if (handler.getValue().equals(hook)) {
                any = true;
                if (INJECTED.getOrDefault(handler.getKey(), 0) <= 0) {
                    return false;
                }
            }
        }
        return any;
    }

    /** Counts one run of a hook, for diagnostics. */
    public static void called(String hook) {
        CALLS.computeIfAbsent(hook, key -> new AtomicLong()).incrementAndGet();
    }

    public static long calls(String hook) {
        AtomicLong count = CALLS.get(hook);
        return count == null ? 0L : count.get();
    }

    /** The class a hook landed in, or null when it did not land. */
    public static String target(String hook) {
        return TARGETS.get(hook);
    }

    /** One line per hook, for the adapters report and the start-up log. */
    public static List<String> describe() {
        Map<String, Integer> perHook = new LinkedHashMap<>();
        for (Map.Entry<String, String> handler : HANDLERS.entrySet()) {
            perHook.merge(handler.getValue(), INJECTED.getOrDefault(handler.getKey(), 0), Integer::sum);
        }
        List<String> lines = new ArrayList<>();
        for (Contract contract : CONTRACTS) {
            lines.add(contract.id() + ": " + (armed(contract.id()) ? "armed" : "not armed")
                    + " (" + perHook.getOrDefault(contract.id(), 0) + " call site(s)"
                    + (TARGETS.containsKey(contract.id()) ? " in " + TARGETS.get(contract.id()) : "")
                    + ", run " + calls(contract.id()) + " time(s))");
        }
        return lines;
    }

    /** Test-only: forgets every recorded injection. */
    static void resetForTest() {
        INJECTED.clear();
        TARGETS.clear();
        CALLS.clear();
    }
}
