package dev.otectus.mcamobcompat.bridge;

import dev.otectus.mcamobcompat.api.GuardGender;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the two properties the identity roll has to have.
 *
 * <p><b>Determinism</b>, because a guard whose stored identity is missing must regenerate the same
 * name it had before, and <b>spread</b>, because a village where every guard is a gloomy man called
 * Alden is a bug that no crash would ever report.
 */
class GuardNamesTest {

    @Test
    void everyRollIsStableForTheSameUuid() {
        UUID uuid = UUID.fromString("11111111-2222-4333-8444-555555555555");
        GuardGender gender = GuardNames.gender(uuid);

        for (int i = 0; i < 5; i++) {
            assertEquals(gender, GuardNames.gender(uuid));
            assertEquals(GuardNames.name(uuid, gender), GuardNames.name(uuid, gender));
            assertEquals(GuardNames.personality(uuid), GuardNames.personality(uuid));
        }
    }

    @Test
    void bothGendersAndAVarietyOfNamesAppearAcrossUuids() {
        Set<GuardGender> genders = new HashSet<>();
        Set<String> names = new HashSet<>();
        Set<String> personalities = new HashSet<>();

        for (int i = 0; i < 200; i++) {
            UUID uuid = new UUID(0x5EEDL * i, 0x9E3779B9L * i + 17L);
            GuardGender gender = GuardNames.gender(uuid);
            genders.add(gender);
            names.add(GuardNames.name(uuid, gender));
            personalities.add(GuardNames.personality(uuid));
        }

        assertEquals(2, genders.size(), "Both genders must be reachable.");
        assertTrue(names.size() > 10, "Names clustered on too few entries: " + names.size());
        assertTrue(personalities.size() > 5,
                "Personalities clustered on too few entries: " + personalities.size());
    }

    @Test
    void personalitiesAreLowercaseAndFromMcaRoster() {
        for (String personality : GuardNames.personalities()) {
            assertEquals(personality.toLowerCase(java.util.Locale.ROOT), personality,
                    "Personality ids are persisted and matched by the sibling addons; they are "
                            + "always MCA's own lowercase path form.");
        }
        assertTrue(GuardNames.personalities().contains("confident"));
        assertTrue(GuardNames.personalities().contains("upbeat"));
    }
}
