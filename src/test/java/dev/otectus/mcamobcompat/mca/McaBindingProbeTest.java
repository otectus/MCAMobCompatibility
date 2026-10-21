package dev.otectus.mcamobcompat.mca;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replays {@link McaBinding}'s members against every real MCA jar in the family's probe fleet.
 *
 * <p>The sibling add-ons (Reputation, Quests, Crime, Conversations) already probe their bindings this
 * way. Without it this mod's binding was checked only against the one MCA build a GameTest run
 * launches, so a member MCA renamed in another release inside the supported range would reach
 * players as a silently disabled feature: no proxy personality, no hearts, no home village.
 *
 * <p>Each jar is opened in its own {@link URLClassLoader} behind a parent that hides every MCA
 * package root, because the test classpath already carries a development MCA and a
 * {@code URLClassLoader} asks its parent first; without the hiding parent every jar would bind
 * against that one build and pass vacuously. Minecraft still resolves through the parent, so the
 * parameter types the binding asks for ({@code Player}, {@code ServerLevel}, {@code ResourceLocation})
 * are the very classes MCA's signatures name.
 *
 * <p>Jar paths arrive in {@code mcamobcompat.probe.jars} (path-separated), resolved from
 * {@code mca_probe_versions} in gradle.properties by the {@code test} task. Without the property the
 * test is skipped rather than failed, so an IDE run does not turn red for lack of Gradle.
 *
 * <p>This file is loader-neutral: the Forge build probes the 1.20.1 fleet and the NeoForge build
 * probes its own 1.21.1 fleet through the same assertions.
 */
class McaBindingProbeTest {

    private static final String JARS_PROPERTY = "mcamobcompat.probe.jars";

    /** The package roots {@link McaBinding} probes, hidden from the parent loader. */
    private static final List<String> MCA_ROOTS = List.of(
            "forge.net.conczin.mca.", "forge.net.mca.", "net.conczin.mca.", "net.mca.");

    /** Whether the game had bound a real MCA before this test ran (the NeoForge unit-test runtime does). */
    private boolean boundBefore;

    @BeforeEach
    void rememberBinding() {
        boundBefore = McaBinding.packageRoot().isPresent();
    }

    /** Leaves the binding as the rest of the suite found it: unresolved, or bound to the runtime MCA. */
    @AfterEach
    void restoreBinding() {
        McaBinding.resetForProbe();
        if (boundBefore) {
            McaBinding.resolve();
        }
    }

    @Test
    void everyMemberBindsAgainstEveryProbedMcaJar() throws Exception {
        List<Path> jars = probeJars();
        Assumptions.assumeFalse(jars.isEmpty(),
                "No MCA jar to probe (" + JARS_PROPERTY + "); run via Gradle to exercise this.");

        // One loader and one fresh binding per jar: probeRoot stops at the first root that resolves,
        // so two builds in one loader would exercise whichever root won and leave the other unchecked.
        for (Path jar : jars) {
            McaBinding.resetForProbe();
            try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()},
                    new McaHidingLoader(McaBindingProbeTest.class.getClassLoader()))) {
                McaBinding.bindAgainst(loader);
                String name = jar.getFileName().toString();

                Optional<String> root = McaBinding.packageRoot();
                assertTrue(root.isPresent(), "No package root matched " + name + ". If MCA has moved "
                        + "again, add the new root to McaBinding.PACKAGE_ROOTS.");
                assertTrue(McaBinding.isAvailable(), name + ": VillagerEntityMCA or getVillagerBrain did "
                        + "not bind, so every MCA feature of this mod would be off.");
                assertEquals(Map.of(), McaBinding.failures(), name + " is missing member(s) this mod "
                        + "binds. Either MCA renamed them (update McaBinding) or removed them (give the "
                        + "feature a fallback and say so in its javadoc).");

                List<String> missing = new ArrayList<>();
                for (String line : McaBinding.describeBindings()) {
                    if (line.endsWith(": missing") || line.endsWith(": unsupported")) {
                        missing.add(line);
                    }
                }
                assertEquals(List.of(), missing, name + " leaves part of the binding unbound.");

                String nodeReport = familyNodeReport(loader, root.get(), name);
                String editorReport = editorHookReport(loader, root.get(), name);
                System.out.println("[probe] " + name + " -> " + root.get() + "; " + nodeReport + "; " + editorReport);
            }
        }
    }

    /**
     * The members {@code McaBinding.rollbackSpawn} reflects on a family-tree node at call time. They
     * are not bound up front, so nothing else would notice a rename: {@code father} and
     * {@code mother} missing would silently skip the generated-parent cleanup. The rollback tolerates a
     * missing {@code probablyGenerated} (it then removes only the proxy's own node), so that one is
     * reported rather than required.
     */
    private static String familyNodeReport(ClassLoader loader, String root, String jar) throws Exception {
        Class<?> tree = Class.forName(root + ".server.world.data.FamilyTree", false, loader);
        Method lookup = tree.getMethod("getOrEmpty", java.util.UUID.class);
        Type returned = lookup.getGenericReturnType();
        assertTrue(returned instanceof ParameterizedType, jar + ": FamilyTree#getOrEmpty is not a generic Optional");
        Type node = ((ParameterizedType) returned).getActualTypeArguments()[0];
        assertTrue(node instanceof Class<?>, jar + ": FamilyTree#getOrEmpty names no concrete node type");
        Class<?> nodeClass = (Class<?>) node;
        for (String accessor : List.of("father", "mother", "children")) {
            assertNotNull(nodeClass.getMethod(accessor), jar + ": " + nodeClass.getName() + "#" + accessor);
        }
        boolean generated;
        try {
            nodeClass.getMethod("probablyGenerated");
            generated = true;
        } catch (NoSuchMethodException absent) {
            generated = false;
        }
        return "family node " + nodeClass.getSimpleName() + (generated
                ? " (generated parents are cleaned up on rollback)"
                : " (no probablyGenerated: rollback removes the proxy's own node only)");
    }

    /**
     * The editor-save hook's target, as the hook contract names it: MCA's editor save request with the
     * method the hook brackets -- {@code receive(ServerPlayer)} on the Forge builds, the payload's
     * {@code handleServer(ServerPlayer)} on NeoForge -- and the members {@code McaBinding.editorRequest}
     * reads at call time: the command, the target UUID and the payload tag.
     */
    private static String editorHookReport(ClassLoader loader, String root, String jar) throws Exception {
        Class<?> request = Class.forName(root + ".network.c2s.VillagerEditorSyncRequest", false, loader);
        Class<?> player = Class.forName("net.minecraft.server.level.ServerPlayer", false, loader);
        Method bracketed;
        try {
            bracketed = request.getDeclaredMethod("receive", player);
        } catch (NoSuchMethodException forgeShapeAbsent) {
            bracketed = request.getDeclaredMethod("handleServer", player);
        }
        assertEquals(void.class, bracketed.getReturnType(), jar + ": the hooked save method changed shape");
        for (String member : List.of("command", "uuid")) {
            boolean accessor = Arrays.stream(request.getMethods()).anyMatch(m -> m.getName().equals(member)
                    && m.getParameterCount() == 0);
            boolean field = Arrays.stream(request.getDeclaredFields()).anyMatch(f -> f.getName().equals(member));
            assertTrue(accessor || field, jar + ": VillagerEditorSyncRequest has no '" + member + "'");
        }
        boolean data = Arrays.stream(request.getMethods()).anyMatch(m -> (m.getName().equals("getData")
                || m.getName().equals("data")) && m.getParameterCount() == 0
                && m.getReturnType().getName().equals("net.minecraft.nbt.CompoundTag"));
        assertTrue(data, jar + ": VillagerEditorSyncRequest has no payload accessor");
        return "editor hook target " + bracketed.getName() + "(ServerPlayer)";
    }

    /**
     * The test classpath deliberately carries a development MCA, and a {@link URLClassLoader} asks
     * its parent first, so this parent refuses every MCA root: the jar under test is then the only
     * place an MCA class can come from, while Minecraft still resolves through the parent.
     */
    private static final class McaHidingLoader extends ClassLoader {
        private McaHidingLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            for (String root : MCA_ROOTS) {
                if (name.startsWith(root)) {
                    throw new ClassNotFoundException(name + " is hidden from the probe parent loader");
                }
            }
            return super.loadClass(name, resolve);
        }
    }

    private static List<Path> probeJars() {
        List<Path> jars = new ArrayList<>();
        for (String entry : System.getProperty(JARS_PROPERTY, "").split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                Path path = Paths.get(entry.trim());
                if (Files.isRegularFile(path)) {
                    jars.add(path);
                }
            }
        }
        return jars;
    }
}
