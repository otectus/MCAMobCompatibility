package dev.otectus.mcamobcompat.bridge;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the MCA: Crime effect ids {@link ProxyEffects} mirrors onto a bridged guard.
 *
 * <p>The list is a soft contract by string with another repository: this mod never names a Crime
 * class, so nothing at compile time can tell it that an id is not registered. 0.2.2 shipped
 * {@code mcacrime:wounded}, which no MCA: Crime build registers, and documented it as mirrored. The
 * contract is recorded in {@code MCAReputation/docs/FAMILY_COMPATIBILITY.md}; changing it means
 * changing that record, this test, and (for a new id) confirming the Crime build registers it.
 */
class ProxyEffectsContractTest {

    /** Exactly the effects MCA: Crime 0.7.5 registers ({@code effect/CrimeEffects}). */
    private static final List<String> CONTRACT = List.of("mcacrime:restrained", "mcacrime:sand_blinded");

    private static final Pattern CRIME_ID = Pattern.compile("mcacrime:([a-z0-9_./-]+)");

    @Test
    void theMirroredListIsTheRecordedContract() {
        List<String> mirrored = ProxyEffects.MIRRORED.stream().map(ResourceLocation::toString).toList();
        assertEquals(CONTRACT, mirrored, "ProxyEffects.MIRRORED drifted from the effect-id contract in "
                + "MCAReputation/docs/FAMILY_COMPATIBILITY.md. Mirror only effects the targeted MCA: Crime "
                + "build registers, and update the contract with the list.");
    }

    /**
     * Current-state documents may name only contracted ids. This mod names no other Crime id at all, so
     * any {@code mcacrime:} id in them is a mirroring claim. {@code CHANGELOG.md} is history and exempt:
     * an entry that records a removal has to be able to name what was removed.
     */
    @Test
    void noCurrentDocumentNamesAnUncontractedCrimeId() throws IOException {
        Path root = Paths.get(System.getProperty("mcamobcompat.projectDir", "."));
        List<Path> documents = new ArrayList<>();
        try (Stream<Path> top = Files.list(root)) {
            top.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().equals("CHANGELOG.md"))
                    .forEach(documents::add);
        }
        Path docs = root.resolve("docs");
        if (Files.isDirectory(docs)) {
            try (Stream<Path> tree = Files.walk(docs)) {
                tree.filter(p -> p.getFileName().toString().endsWith(".md")).forEach(documents::add);
            }
        }
        assertTrue(documents.stream().anyMatch(p -> p.getFileName().toString().equals("README.md")),
                "Expected to scan README.md under " + root.toAbsolutePath() + "; is mcamobcompat.projectDir set?");

        List<String> strays = new ArrayList<>();
        for (Path document : documents) {
            Matcher matcher = CRIME_ID.matcher(Files.readString(document, StandardCharsets.UTF_8));
            while (matcher.find()) {
                if (!CONTRACT.contains(matcher.group())) {
                    strays.add(root.relativize(document) + ": " + matcher.group());
                }
            }
        }
        assertEquals(List.of(), strays, "These documents name MCA: Crime ids the guard bridge does not mirror.");
    }
}
