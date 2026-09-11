package dev.otectus.mcamobcompat.bridge;

import dev.otectus.mcamobcompat.api.GuardGender;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the two properties the identity roll has to have.
 *
 * <p><b>Determinism</b>, because a guard whose stored identity is missing must regenerate the same
 * name it had before, and <b>spread</b>, because a village where every guard is a gloomy man called
 * Alden is a bug that no crash would ever report.
 *
 * <p>The weighted draw is pinned here too, on crafted weights rather than on real data: a walk
 * that is off by one entry still produces plausible names forever.
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
        assertTrue(GuardNames.personalities().contains("grumpy"));
        assertFalse(GuardNames.personalities().contains("unassigned"),
                "'unassigned' is MCA's parse-failure sentinel and is never rolled onto anybody.");
    }

    @Test
    void theWeightedPickIsStableForTheSameUuid() {
        UUID uuid = UUID.fromString("66666666-7777-4888-8999-aaaaaaaaaaaa");
        List<String> names = List.of("Aaron", "Abe", "Adam", "Alan");
        List<Integer> weights = List.of(3600, 100, 900, 40);

        String first = GuardNames.weightedName(uuid, names, weights).orElseThrow();
        for (int i = 0; i < 5; i++) {
            assertEquals(first, GuardNames.weightedName(uuid, names, weights).orElseThrow());
        }
    }

    @Test
    void weightZeroIsNeverDrawnAndASingleEntryAlwaysIs() {
        List<String> names = List.of("Never", "Always");
        List<Integer> weights = List.of(0, 5);

        for (int i = 0; i < 100; i++) {
            UUID uuid = new UUID(0xBEEFL * i, 0x1234L * i + 7L);
            assertEquals("Always", GuardNames.weightedName(uuid, names, weights).orElseThrow(),
                    "A name weighted 0 must be outside the cumulative walk entirely.");
        }
    }

    @Test
    void heavyWeightsDominateTheDraw() {
        List<String> names = List.of("Rare", "Common");
        List<Integer> weights = List.of(1, 999);
        int common = 0;

        for (int i = 0; i < 200; i++) {
            UUID uuid = new UUID(0x5EEDL * i, 0x9E3779B9L * i + 17L);
            if ("Common".equals(GuardNames.weightedName(uuid, names, weights).orElseThrow())) {
                common++;
            }
        }
        assertTrue(common > 180, "A 999:1 weighting barely showed: " + common + "/200.");
    }

    @Test
    void anUnusablePoolFallsBackRatherThanGuessing() {
        UUID uuid = UUID.fromString("11111111-2222-4333-8444-555555555555");

        assertTrue(GuardNames.weightedName(uuid, List.of(), List.of()).isEmpty());
        assertTrue(GuardNames.weightedName(uuid, List.of("A"), List.of(0)).isEmpty(),
                "A pool whose every weight is zero has nothing to draw.");
        assertTrue(GuardNames.weightedName(uuid, List.of("A", "B"), List.of(1)).isEmpty(),
                "Mismatched lists are a bug in the caller, not a name.");
        assertTrue(GuardNames.weightedName(uuid, null, null).isEmpty());
    }
}
