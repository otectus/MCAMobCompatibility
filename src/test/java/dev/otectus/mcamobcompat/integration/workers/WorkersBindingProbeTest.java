package dev.otectus.mcamobcompat.integration.workers;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Method;
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
 * Replays {@link WorkersBinding} against the audited Villager Workers jar (with the Recruits jar it
 * extends), each in a loader that hides any development copy. Skipped without the jars, as on NeoForge.
 */
class WorkersBindingProbeTest {

    @AfterEach
    void reset() {
        WorkersBinding.resetForProbe();
    }

    private static List<URL> jars(String... properties) throws Exception {
        List<URL> urls = new ArrayList<>();
        for (String property : properties) {
            for (String entry : System.getProperty(property, "").split(File.pathSeparator)) {
                if (!entry.isBlank()) {
                    Path path = Paths.get(entry.trim());
                    if (Files.isRegularFile(path)) {
                        urls.add(path.toUri().toURL());
                    }
                }
            }
        }
        return urls;
    }

    private static final class HidingLoader extends ClassLoader {
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
    void everyMemberBinds() throws Exception {
        List<URL> workers = jars("mcamobcompat.probe.workers");
        Assumptions.assumeFalse(workers.isEmpty(), "No Villager Workers jar to probe; run via Gradle on Forge.");
        List<URL> all = new ArrayList<>(workers);
        all.addAll(jars("mcamobcompat.probe.recruits"));
        try (URLClassLoader loader = new URLClassLoader(all.toArray(URL[]::new),
                new HidingLoader(WorkersBindingProbeTest.class.getClassLoader()))) {
            WorkersBinding.bindAgainst(loader);
            assertEquals(Map.of(), WorkersBinding.failures(), "Villager Workers renamed or removed a bound member.");
            assertTrue(WorkersBinding.isAvailable());
            for (String line : WorkersBinding.describeBindings()) {
                assertTrue(line.endsWith(": ok"), "Unbound: " + line);
            }
            // Workers' working state is read through isWorking(); its follow-state number is Workers'
            // own, kept in WorkersAdapter.WORKING and checked here against the shipped bytecode's intent.
            Class<?> worker = Class.forName("com.talhanation.workers.entities.AbstractWorkerEntity", false, loader);
            Method working = worker.getMethod("isWorking");
            assertEquals(boolean.class, working.getReturnType());
            assertEquals(6, WorkersAdapter.WORKING, "The adapter's working follow state drifted from Workers 2.0.3.");
        }
    }
}
