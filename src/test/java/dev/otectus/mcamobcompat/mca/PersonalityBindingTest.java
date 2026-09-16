package dev.otectus.mcamobcompat.mca;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
class PersonalityBindingTest {
    public enum OldPersonality { FRIENDLY, ODD }
    public static class Personality {
        final ResourceLocation id;
        Personality(String id) { this.id = ResourceLocation.parse(id); }
        public ResourceLocation getId() { return id; }
        public static Optional<Personality> get(ResourceLocation id) {
            return id.toString().equals("custom:reserved") || id.toString().equals("mca:friendly")
                    ? Optional.of(new Personality(id.toString())) : Optional.empty();
        }
    }
    @Test void readsExtensibleAndEnumShapes() throws Exception {
        assertEquals(Optional.of("custom:reserved"), McaBinding.personalityName(new Personality("custom:reserved")));
        assertEquals(Optional.of("odd"), McaBinding.personalityName(OldPersonality.ODD));
    }
    @Test void preservesCustomNamespacesAndDoesNotInventRemovedValues() throws Exception {
        assertEquals("custom:reserved", McaBinding.personalityName(McaBinding.resolvePersonality(Personality.class, "custom:reserved")).orElseThrow());
        assertNotNull(McaBinding.resolvePersonality(Personality.class, "friendly"));
        assertNull(McaBinding.resolvePersonality(Personality.class, "confident"));
        assertNull(McaBinding.resolvePersonality(Personality.class, "invalid spaces"));
        assertEquals(OldPersonality.ODD, McaBinding.resolvePersonality(OldPersonality.class, "odd"));
    }
}
