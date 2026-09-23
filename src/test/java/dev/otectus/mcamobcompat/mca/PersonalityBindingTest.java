package dev.otectus.mcamobcompat.mca;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the two personality shapes MCA has shipped: an enum in 7.6 for 1.20.1, and a registry type
 * with {@code getId()} and a static {@code get(ResourceLocation)} in 7.7 for 1.20.1 and 1.21.1. The
 * stand-ins below have exactly those shapes, so no MCA jar is needed.
 */
class PersonalityBindingTest {

    /** MCA 7.6's shape. */
    public enum OldPersonality { FRIENDLY, ODD }

    /** MCA 7.7's shape. */
    public static final class Personality {
        private final ResourceLocation id;

        private Personality(String id) {
            this.id = ResourceLocation.tryParse(id);
        }

        public ResourceLocation getId() {
            return id;
        }

        public static Optional<Personality> get(ResourceLocation id) {
            String text = id.toString();
            return text.equals("custom:reserved") || text.equals("mca:friendly")
                    ? Optional.of(new Personality(text)) : Optional.empty();
        }
    }

    /** A look-alike whose {@code getId()} is not an id: not a personality at all. */
    public static final class NotAPersonality {
        public String getId() {
            return "mca:friendly";
        }
    }

    @Test
    void bothShapesReadBackAsText() throws Exception {
        assertEquals(Optional.of("custom:reserved"), McaBinding.personalityName(new Personality("custom:reserved")));
        assertEquals(Optional.of("odd"), McaBinding.personalityName(OldPersonality.ODD));
        assertEquals(Optional.empty(), McaBinding.personalityName(null));
        assertEquals(Optional.empty(), McaBinding.personalityName(new NotAPersonality()),
                "Only a getId() that returns a ResourceLocation is read.");
    }

    @Test
    void knownNamesResolveAndUnknownOnesAreRefused() throws Exception {
        assertEquals("custom:reserved", McaBinding.personalityName(
                McaBinding.resolvePersonality(Personality.class, "custom:reserved")).orElseThrow());
        assertNotNull(McaBinding.resolvePersonality(Personality.class, "friendly"),
                "A bare name from this mod's roster is looked up in MCA's namespace.");
        assertNull(McaBinding.resolvePersonality(Personality.class, "confident"),
                "A name MCA does not know must be refused, never guessed at.");
        assertNull(McaBinding.resolvePersonality(Personality.class, "invalid spaces"));
        assertNull(McaBinding.resolvePersonality(Personality.class, null));

        assertEquals(OldPersonality.ODD, McaBinding.resolvePersonality(OldPersonality.class, "odd"));
        assertEquals(OldPersonality.FRIENDLY, McaBinding.resolvePersonality(OldPersonality.class, "mca:friendly"),
                "A 7.7 id stored before a downgrade still resolves against the 7.6 enum.");
        assertNull(McaBinding.resolvePersonality(OldPersonality.class, "confident"));
    }
}
