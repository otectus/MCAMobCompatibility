package dev.otectus.mcamobcompat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
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
 * SWEM ownership is read through vanilla {@code AbstractHorse}, and Genetic Animals and DLO are
 * nothing but tag data. A single stray {@code import} would quietly turn that guarantee into a
 * crash on someone's server, and nothing else in the build would notice.
 *
 * <p>It walks every {@code .class} file under {@code build/classes/java/main} and byte-searches the
 * raw constant pool for the modified-UTF8 encoding of each package, in two forms:
 *
 * <ul>
 *   <li><em>Internal (slash)</em> form, which is what the JVM uses for a real class, method or field
 *       reference. This applies to <b>every</b> class with no exemption: naming one of these types
 *       is what would produce the crash, and nothing in this jar may do it.</li>
 *   <li><em>Dotted</em> form, which is how a reflective {@code Class.forName} name is stored. This
 *       applies to every class <b>except</b> the single entry in {@link #REFLECTION_ALLOWLIST}:
 *       {@code mca/McaBinding}, which binds MCA by name deliberately and holds every handle as
 *       {@code Class}/{@code Method}/{@code Object} so it still names no MCA type.</li>
 * </ul>
 *
 * <p>The allowlist is matched on the exact class-file path, not a prefix, so a nested or anonymous
 * class ({@code McaBinding$1.class}) or a moved file is not covered, and it cannot widen without
 * someone editing a one-line literal. A third assertion checks that every allowlisted path still
 * exists, so renaming the binding class cannot silently orphan the exemption and reopen the hole.
 *
 * <p><b>Honest limit:</b> this catches literals, not a name assembled at runtime from fragments.
 * Constant expressions are safe -- javac folds {@code "forge.net" + ".mca"} into one literal, so
 * that case is still caught -- but a name built from a {@code StringBuilder} or read from a config
 * would slip past. Nothing in this mod does that, and code review is the control for it.
 *
 * <p>A plain byte search is enough -- this only needs to prove a string is absent, not parse
 * bytecode -- which keeps the test dependency-free and runnable on any JDK.
 */
class NoOptionalModStaticLinkTest {

    /**
     * Every package root this mod must never link against, in internal form.
     *
     * <p>The MCA entries cover both the historical layout and the rename: MCA moved from
     * {@code net.mca} to {@code net.conczin.mca}, each behind a Forgix {@code forge.} prefix, and
     * {@code net/mca/} keeps its trailing slash so it cannot collide with an unrelated
     * {@code net/mcaSomething} package. No class in this jar links against any of them, including
     * {@code McaBinding}, which reaches MCA reflectively and therefore names it only as text.
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
            // Architectury is a runtime dependency of MCA's Forge build, never ours.
            "dev/architectury",
            // Optional mods' names have no reflection exemption, not even inside the MCA binding:
            // their dotted form is how a Class.forName name is stored, and nothing may hold one.
            "tallestegg.guardvillagers", "com.alaharranhonor.swem", "mokiyoki.enhancedanimals",
            "com.dragn0007", "icy.betterhorses", "com.github.alexthe666", "codx.codxlib",
            "dev.terminalmc.golemskillcreepers", "dev.architectury",
    };

    /**
     * The MCA roots again, in dotted form -- how a name handed to {@code Class.forName} is stored.
     *
     * <p>Only MCA appears here. The other optional mods are reached through registry ids and tags
     * and are never named reflectively, so their dotted form has no legitimate use anywhere;
     * {@code net.mca.} keeps its trailing dot for the same anti-collision reason as the slash form.
     */
    private static final String[] FORBIDDEN_NAMES = {
            "forge.net.mca", "net.conczin.mca", "net.mca.",
    };

    /**
     * The only class allowed to hold a dotted MCA package name: the reflective binding layer.
     *
     * <p>Exactly one entry, compared for equality rather than as a prefix, so widening it takes a
     * deliberate edit to this literal and no nested, anonymous or relocated class inherits the
     * exemption. Mirrored by the {@code checkJarContents} task in {@code build.gradle}, which runs
     * the same scan on the finished artifact even when the build skips tests.
     */
    private static final Set<String> REFLECTION_ALLOWLIST =
            Set.of("dev/otectus/mcamobcompat/mca/McaBinding.class");

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
                        + "reached through registry ids, datapack tags or vanilla supertypes, so that this "
                        + "mod loads no matter which of them are installed. Offenders: " + violations);
    }

    @Test
    void noClassOutsideTheAllowlistNamesMcaAsText() throws IOException {
        List<String> violations = new ArrayList<>();
        forEachClass((relative, bytes) -> {
            if (REFLECTION_ALLOWLIST.contains(relative)) {
                return;
            }
            for (String name : FORBIDDEN_NAMES) {
                if (contains(bytes, name.getBytes(StandardCharsets.UTF_8))) {
                    violations.add(relative + " -> " + name);
                }
            }
        });

        assertTrue(violations.isEmpty(),
                "Class(es) outside the reflection allowlist carry a dotted MCA package name. Reflective "
                        + "access to MCA belongs in mca/McaBinding and nowhere else, so that one file is "
                        + "the whole audit surface for surviving MCA's next package move. Offenders: "
                        + violations);
    }

    @Test
    void everyAllowlistedClassStillExists() throws IOException {
        Path classesDir = classesDir();
        for (String allowed : REFLECTION_ALLOWLIST) {
            assertTrue(Files.isRegularFile(classesDir.resolve(allowed)),
                    "Allowlisted class '" + allowed + "' does not exist. Renaming or moving the "
                            + "reflective binding class without updating REFLECTION_ALLOWLIST (and the "
                            + "matching list in build.gradle's checkJarContents) leaves a dead exemption "
                            + "behind and quietly reopens the hole it was carved for.");
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
