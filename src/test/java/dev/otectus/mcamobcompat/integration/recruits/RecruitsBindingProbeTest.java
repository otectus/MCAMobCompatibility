package dev.otectus.mcamobcompat.integration.recruits;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replays {@link RecruitsBinding} against the audited Villager Recruits jar, and checks that every
 * method the conversion hooks inject into exists with the exact descriptor the hook contract names.
 *
 * <p>The jar is opened in its own loader behind a parent that hides Recruits' package, so a
 * development copy on the test classpath cannot answer instead. Minecraft and Forge still resolve
 * through the parent, so the parameter types checked are the very classes Recruits' signatures name.
 * Jar paths arrive in {@code mcamobcompat.probe.recruits} from the {@code test} task; without it the
 * test is skipped, as on the NeoForge build, where no Recruits release exists.
 */
class RecruitsBindingProbeTest {

    static final String RECRUITS = "com.talhanation.recruits.";

    @AfterEach
    void reset() {
        RecruitsBinding.resetForProbe();
    }

    static List<URL> jars(String property) throws Exception {
        List<URL> urls = new ArrayList<>();
        for (String entry : System.getProperty(property, "").split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                Path path = Paths.get(entry.trim());
                if (Files.isRegularFile(path)) {
                    urls.add(path.toUri().toURL());
                }
            }
        }
        return urls;
    }

    /** Hides the talhanation packages from the parent, so only the jars under test can supply them. */
    static final class HidingLoader extends ClassLoader {
        HidingLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("com.talhanation.")) {
                throw new ClassNotFoundException(name + " is hidden from the probe parent loader");
            }
            return super.loadClass(name, resolve);
        }
    }

    @Test
    void everyMemberBindsAndEveryHookTargetExists() throws Exception {
        List<URL> jars = jars("mcamobcompat.probe.recruits");
        Assumptions.assumeFalse(jars.isEmpty(), "No Villager Recruits jar to probe; run via Gradle on Forge.");
        try (URLClassLoader loader = new URLClassLoader(jars.toArray(URL[]::new),
                new HidingLoader(RecruitsBindingProbeTest.class.getClassLoader()))) {
            RecruitsBinding.bindAgainst(loader);
            assertEquals(Map.of(), RecruitsBinding.failures(), "Villager Recruits renamed or removed a bound member.");
            assertTrue(RecruitsBinding.isAvailable() && RecruitsBinding.canHire() && RecruitsBinding.canDisband());
            for (String line : RecruitsBinding.describeBindings()) {
                assertTrue(line.endsWith(": ok"), "Unbound: " + line);
            }

            Class<?> events = Class.forName(RECRUITS + "VillagerEvents", false, loader);
            Class<?> group = Class.forName(RECRUITS + "world.RecruitsGroup", false, loader);
            assertStatic(events.getDeclaredMethod("createRecruit", Villager.class, EntityType.class));
            assertStatic(events.getDeclaredMethod("createNobleVillager", Villager.class));
            assertStatic(events.getDeclaredMethod("createHiredRecruitFromVillager", ServerLevel.class, Villager.class,
                    EntityType.class, Player.class, group));
        }
    }

    private static void assertStatic(Method method) {
        assertTrue(Modifier.isStatic(method.getModifiers()) && method.getReturnType() == void.class,
                method + " is no longer the static void method the hook injects into.");
    }
}
