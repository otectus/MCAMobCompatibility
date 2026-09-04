package dev.otectus.mcamobcompat.mca;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the root probe of the reflective MCA binding, the one piece of {@code McaBinding} that can be
 * exercised without MCA -- or Minecraft -- on the classpath.
 *
 * <p>What it protects is the ordering decision. MCA has moved its package once already, and this mod
 * must bind to whichever layout is installed while preferring the newer one when a build somehow
 * ships both. It also pins the failure behaviour: no MCA means an empty result, never a throw, since
 * this probe runs during common setup and a throw there takes the whole game down.
 */
class McaBindingTest {

    @Test
    void theNewerLayoutWinsWhenBothResolve() {
        Optional<String> root = McaBinding.probeRoot(name ->
                name.equals("forge.net.conczin.mca") || name.equals("forge.net.mca")
                        ? Optional.of(String.class)
                        : Optional.empty());

        assertEquals(Optional.of("forge.net.conczin.mca"), root,
                "The probe must prefer the newer net.conczin.mca layout over the older net.mca one, so "
                        + "that a build carrying both binds against the current package.");
    }

    @Test
    void theOlderLayoutBindsWhenItIsTheOnlyOne() {
        Optional<String> root = McaBinding.probeRoot(name ->
                name.equals("forge.net.mca") ? Optional.of(String.class) : Optional.empty());

        assertEquals(Optional.of("forge.net.mca"), root,
                "The pinned dev jar ships the old forge.net.mca layout; it must still bind.");
    }

    @Test
    void noMcaMeansNoRoot() {
        assertTrue(McaBinding.probeRoot(name -> Optional.empty()).isEmpty(),
                "With no MCA on the classpath the binding must simply be unavailable.");
    }

    @Test
    void aThrowingProbeYieldsEmptyRatherThanPropagating() {
        Optional<String> root = McaBinding.probeRoot(name -> {
            throw new NoClassDefFoundError(name);
        });

        assertTrue(root.isEmpty(),
                "A class loader that throws -- a half-installed or shaded MCA, say -- must degrade to "
                        + "'binding unavailable'. This runs inside FMLCommonSetupEvent, where a throw is "
                        + "a crash on startup.");
    }
}
