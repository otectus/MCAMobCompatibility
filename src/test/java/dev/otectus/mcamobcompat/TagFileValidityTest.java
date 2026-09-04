package dev.otectus.mcamobcompat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.otectus.mcamobcompat.api.MobRole;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Checks the shipped classification tags, which are the mod's actual content.
 *
 * <p>These files are the whole reason the mod works without hardcoding mob ids in Java, and a
 * mistake in one of them is invisible at runtime: a wrong entity id does not error, it simply never
 * matches, and the mob silently goes unclassified forever. So the things that can only be caught by
 * reading them are caught here.
 */
class TagFileValidityTest {

    private static final Path TAGS =
            Paths.get("src", "main", "resources", "data", "mcamobcompat", "tags", "entity_types");

    /**
     * Namespaces whose entries may be listed as a bare string.
     *
     * <p>{@code minecraft} is always present, and {@code mcamobcompat} is us. Everything else is an
     * optional mod that may well not be installed, and a bare entry for an absent mod makes Minecraft
     * log a datapack error on every world load.
     */
    private static final Set<String> ALWAYS_PRESENT = Set.of("minecraft", "mcamobcompat");

    /**
     * Ids that look real and are not.
     *
     * <p>Both have language keys and loot tables inside the DLO jar but neither is a registered
     * entity type: undead is a body-layer variant of {@code o_horse}, and the freight wagon no
     * longer exists. Listing either would be a silently dead tag entry, so they are banned outright.
     */
    private static final Set<String> PHANTOM_IDS = Set.of(
            "dragnlivestock:freight_wagon", "dragnlivestock:o_undead_horse");

    @Test
    void everyRoleShipsATagFile() {
        assertTrue(Files.isDirectory(TAGS), "Shipped tag directory is missing: " + TAGS.toAbsolutePath());
        for (MobRole role : MobRole.VALUES) {
            Path file = TAGS.resolve(role.path() + ".json");
            assertTrue(Files.isRegularFile(file),
                    "MobRole." + role.name() + " has no shipped tag file. Every role needs one, or the "
                            + "role can never match anything on a plain install: " + file);
        }
    }

    @Test
    void mcaVillagerExtensionTagIsShipped() {
        assertTrue(Files.isRegularFile(TAGS.resolve("mca_villagers.json")),
                "mca_villagers.json is missing. It is the additive extension point for packs running an "
                        + "MCA fork; the Java constant set in McaEntities stays authoritative either way.");
    }

    @Test
    void everyTagFileIsWellFormedAndOptionalWhereItMustBe() throws IOException {
        List<String> problems = new ArrayList<>();
        List<Path> files;
        try (var stream = Files.list(TAGS)) {
            files = stream.filter(path -> path.toString().endsWith(".json")).sorted().toList();
        }
        assertTrue(!files.isEmpty(), "No tag files found in " + TAGS.toAbsolutePath());

        for (Path file : files) {
            String name = file.getFileName().toString();
            JsonObject root;
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                root = JsonParser.parseReader(reader).getAsJsonObject();
            } catch (Exception e) {
                problems.add(name + ": not parseable as a JSON object (" + e + ")");
                continue;
            }

            if (!root.has("replace") || root.get("replace").getAsBoolean()) {
                problems.add(name + ": must set \"replace\": false, so a pack that adds to this tag is "
                        + "merged with the shipped defaults instead of replacing them");
            }
            if (!root.has("values") || !root.get("values").isJsonArray()) {
                problems.add(name + ": has no \"values\" array");
                continue;
            }

            for (JsonElement entry : root.getAsJsonArray("values")) {
                checkEntry(name, entry, problems);
            }
        }

        if (!problems.isEmpty()) {
            fail("Shipped tag files have " + problems.size() + " problem(s):\n  "
                    + String.join("\n  ", problems));
        }
    }

    @Test
    void nestedRoleReferencesPointAtShippedFiles() throws IOException {
        List<String> problems = new ArrayList<>();
        List<Path> files;
        try (var stream = Files.list(TAGS)) {
            files = stream.filter(path -> path.toString().endsWith(".json")).sorted().toList();
        }
        for (Path file : files) {
            JsonObject root;
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                root = JsonParser.parseReader(reader).getAsJsonObject();
            }
            JsonArray values = root.getAsJsonArray("values");
            if (values == null) {
                continue;
            }
            for (JsonElement element : values) {
                String id = idOf(element);
                if (id == null || !id.startsWith("#mcamobcompat:")) {
                    continue;
                }
                String referenced = id.substring("#mcamobcompat:".length());
                if (!Files.isRegularFile(TAGS.resolve(referenced + ".json"))) {
                    problems.add(file.getFileName() + " references " + id + ", which ships no file");
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("; ", problems));
    }

    private static void checkEntry(String fileName, JsonElement entry, List<String> problems) {
        String id = idOf(entry);
        if (id == null) {
            problems.add(fileName + ": entry " + entry + " has no usable id");
            return;
        }

        String bare = id.startsWith("#") ? id.substring(1) : id;
        if (PHANTOM_IDS.contains(bare)) {
            problems.add(fileName + ": " + bare + " is not a registered entity type, only a leftover "
                    + "language key and loot table. A tag entry for it would never match anything.");
            return;
        }

        int colon = bare.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : bare.substring(0, colon);
        if (ALWAYS_PRESENT.contains(namespace)) {
            return;
        }

        // Anything from a mod that might not be installed has to be declared optional, or Minecraft
        // logs a datapack error on every load for players who do not have that mod.
        if (!entry.isJsonObject()
                || !entry.getAsJsonObject().has("required")
                || entry.getAsJsonObject().get("required").getAsBoolean()) {
            problems.add(fileName + ": " + id + " is from an optional mod and must be written as "
                    + "{\"id\": \"" + id + "\", \"required\": false}");
        }
    }

    private static String idOf(JsonElement entry) {
        if (entry.isJsonPrimitive()) {
            return entry.getAsString();
        }
        if (entry.isJsonObject() && entry.getAsJsonObject().has("id")) {
            return entry.getAsJsonObject().get("id").getAsString();
        }
        return null;
    }
}
