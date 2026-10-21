package dev.otectus.mcamobcompat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Standing tripwire: <b>no compiled class may reference a type from MCA or from any optional mod.</b>
 *
 * <p>This single test is what the entire architecture rests on. This mod claims it cannot throw
 * {@code NoClassDefFoundError} no matter which combination of mods is installed, and the reason it
 * can claim that is that it never names one of their types: MCA villagers are identified by entity
 * registry id, guards are found through a tag and commanded through vanilla {@code Mob.setTarget},
 * SWEM ownership is read through vanilla {@code AbstractHorse}, Genetic Animals and DLO are nothing
 * but tag data, and Villager Recruits and Workers are reached through reflection bound by name. A
 * single stray {@code import} would quietly turn that guarantee into a crash on someone's server, and
 * nothing else in the build would notice.
 *
 * <p>It walks every {@code .class} file under {@code build/classes/java/main} and byte-searches the
 * raw constant pool for the modified-UTF8 encoding of each package, in two forms:
 *
 * <ul>
 *   <li><em>Internal (slash)</em> form, which is what the JVM uses for a real class, method or field
 *       reference. This applies to <b>every</b> class with no exemption: naming one of these types
 *       is what would produce the crash, and nothing in this jar may do it.</li>
 *   <li><em>Dotted</em> form, which is how a reflective {@code Class.forName} name, or a Mixin target
 *       name, is stored. Each root may appear only in the exact class files {@link #DOTTED_ALLOWLIST}
 *       lists for it: the reflective bindings ({@code mca/McaBinding},
 *       {@code integration/recruits/RecruitsBinding}, {@code integration/workers/WorkersBinding}),
 *       which hold every handle as {@code Class}/{@code Method}/{@code Object}, and the optional hooks
 *       ({@code hook/mixin/*}), which name their targets as strings and their parameters as vanilla
 *       types or {@code @Coerce Object}. Every other optional mod's dotted name is forbidden in every
 *       class.</li>
 * </ul>
 *
 * <p>The allowlist is matched on the exact class-file path, not a prefix, so a nested or anonymous
 * class ({@code McaBinding$1.class}) or a moved file is not covered, and it cannot widen without
 * someone editing a literal. A third assertion checks that every allowlisted path still exists, so
 * renaming a binding class cannot silently orphan its exemption and reopen the hole. The
 * {@code checkJarContents} task in {@code build.gradle} runs the same scan on the finished artifact,
 * even when the build skips tests; keep the two in step.
 *
 * <p><b>Honest limit:</b> this catches literals, not a name assembled at runtime from fragments.
 * Constant expressions are safe -- javac folds {@code "forge.net" + ".mca"} into one literal, so
 * that case is still caught -- but a name built from a {@code StringBuilder} or read from a config
 * would slip past. Nothing in this mod does that, and code review is the control for it.
 */
class NoOptionalModStaticLinkTest {

    /**
     * Every package root this mod must never link against, in internal form, plus the dotted names of
     * the optional mods no class may name even reflectively.
     */
    private static final String[] FORBIDDEN = {
            // MCA Reborn, every layout it has ever shipped.
            "forge/net/mca", "net/conczin/mca", "net/mca/",
            // Optional mods.
            "tallestegg/guardvillagers",     // Guard Villagers
            "com/alaharranhonor/swem",       // SWEM
            "mokiyoki/enhancedanimals",      // Genetic Animals (mod id "eanimod")
            "com/dragn0007/dragnlivestock",  // DragN's Livestock Overhaul
            "icy/betterhorses",              // Icy's Better Horses (mod id "icys_better_horses")
            "com/github/alexthe666",         // Alex's Mobs and its Citadel library
            "codx/codxlib",                  // CodxLib, required by Alex's Mobs Continued
            "dev/terminalmc/golemskillcreepers", // Golems Kill Creepers
            "com/talhanation",               // Villager Recruits and Villager Workers
            // Architectury is a runtime dependency of MCA's Forge build, never ours.
            "dev/architectury",
            // Optional mods reached through tags, events and their own member names only: their
            // dotted form has no legitimate use anywhere in this jar.
            "tallestegg.guardvillagers", "com.alaharranhonor.swem", "mokiyoki.enhancedanimals",
            "com.dragn0007", "icy.betterhorses", "com.github.alexthe666", "codx.codxlib",
            "dev.terminalmc.golemskillcreepers", "dev.architectury",
    };

    /**
     * Dotted roots, and the only class files allowed to hold each. {@code net.mca.} keeps its trailing
     * dot so it cannot collide with an unrelated {@code net.mcaSomething} package.
     */
    static final Map<String, Set<String>> DOTTED_ALLOWLIST = dottedAllowlist();

    private static Map<String, Set<String>> dottedAllowlist() {
        Set<String> mca = Set.of("dev/otectus/mcamobcompat/mca/McaBinding.class",
                "dev/otectus/mcamobcompat/hook/mixin/McaEditorSaveHook.class");
        Map<String, Set<String>> map = new LinkedHashMap<>();
        map.put("forge.net.mca", mca);
        map.put("net.conczin.mca", mca);
        map.put("net.mca.", mca);
        map.put("com.talhanation.recruits", Set.of(
                "dev/otectus/mcamobcompat/integration/recruits/RecruitsBinding.class",
                "dev/otectus/mcamobcompat/hook/mixin/RecruitsConversionHook.class"));
        map.put("com.talhanation.workers", Set.of(
                "dev/otectus/mcamobcompat/integration/workers/WorkersBinding.class"));
        return map;
    }

    @Test
    void noCompiledClassReferencesAForeignType() throws IOException {
        List<String> violations = new ArrayList<>();
        forEachClass((relative, bytes) -> {
            for (String root : FORBIDDEN) {
                if (contains(bytes, root.getBytes(StandardCharsets.UTF_8))) {
                    violations.add(relative + " -> " + root);
                }
            }
        });

        assertTrue(violations.isEmpty(),
                "Class(es) statically reference MCA or an optional mod. Every one of those mods must be "
                        + "reached through registry ids, datapack tags, vanilla supertypes or its own member "
                        + "names, so that this mod loads no matter which of them are installed. Offenders: "
                        + violations);
    }

    @Test
    void dottedForeignNamesOnlyInTheirAllowlistedClasses() throws IOException {
        List<String> violations = new ArrayList<>();
        forEachClass((relative, bytes) -> {
            for (Map.Entry<String, Set<String>> root : DOTTED_ALLOWLIST.entrySet()) {
                if (!root.getValue().contains(relative)
                        && contains(bytes, root.getKey().getBytes(StandardCharsets.UTF_8))) {
                    violations.add(relative + " -> " + root.getKey());
                }
            }
        });

        assertTrue(violations.isEmpty(),
                "Class(es) outside the allowlist carry a dotted foreign package name. Reflective access "
                        + "belongs in the binding class of that mod, and a hook target in its hook class, so "
                        + "those files are the whole audit surface. Offenders: " + violations);
    }

    @Test
    void everyAllowlistedClassStillExists() throws IOException {
        Path classesDir = classesDir();
        Set<String> all = new HashSet<>();
        DOTTED_ALLOWLIST.values().forEach(all::addAll);
        for (String allowed : all) {
            assertTrue(Files.isRegularFile(classesDir.resolve(allowed)),
                    "Allowlisted class '" + allowed + "' does not exist. Renaming or moving a binding or "
                            + "hook class without updating DOTTED_ALLOWLIST (and the matching list in "
                            + "build.gradle's checkJarContents) leaves a dead exemption behind and quietly "
                            + "reopens the hole it was carved for.");
        }
    }

    /** Hands every compiled class to the visitor as (path relative to the classes root, bytes). */
    private static void forEachClass(BiConsumer<String, byte[]> visitor) throws IOException {
        Path classesDir = classesDir();
        try (Stream<Path> paths = Files.walk(classesDir)) {
            paths.filter(path -> path.toString().endsWith(".class")).forEach(path -> {
                String relative = classesDir.relativize(path).toString().replace('\\', '/');
                try {
                    visitor.accept(relative, Files.readAllBytes(path));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private static Path classesDir() {
        Path classesDir = Paths.get(System.getProperty("mcamobcompat.projectDir", "."),
                "build", "classes", "java", "main");
        assertTrue(Files.isDirectory(classesDir),
                "build/classes/java/main does not exist; run `gradlew compileJava` (or `test`, which "
                        + "depends on it) before running this test directly.");
        return classesDir;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
