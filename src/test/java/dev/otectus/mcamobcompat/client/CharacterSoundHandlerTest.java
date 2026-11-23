package dev.otectus.mcamobcompat.client;

import dev.otectus.mcamobcompat.McaMobCompat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CharacterSoundHandlerTest {

    @Test
    void classifiesGuardAndVillagerVoiceEvents() {
        assertEquals(CharacterSoundHandler.SoundKind.AMBIENT,
                CharacterSoundHandler.classify(McaMobCompat.location("guardvillagers", "entity.guard.ambient")));
        assertEquals(CharacterSoundHandler.SoundKind.HURT,
                CharacterSoundHandler.classify(McaMobCompat.location("guardvillagers", "entity.guard.hurt")));
        assertEquals(CharacterSoundHandler.SoundKind.DEATH,
                CharacterSoundHandler.classify(McaMobCompat.location("guardvillagers", "entity.guard.death")));
        assertEquals(CharacterSoundHandler.SoundKind.YES,
                CharacterSoundHandler.classify(McaMobCompat.location("guardvillagers", "entity.guard.yes")));
        assertEquals(CharacterSoundHandler.SoundKind.NO,
                CharacterSoundHandler.classify(McaMobCompat.location("guardvillagers", "entity.guard.no")));
        assertEquals(CharacterSoundHandler.SoundKind.AMBIENT,
                CharacterSoundHandler.classify(McaMobCompat.location("minecraft", "entity.villager.ambient")));
        assertNull(CharacterSoundHandler.classify(McaMobCompat.location("minecraft", "entity.player.hurt")));
    }

    @Test
    void mapsSoundKindAndGenderToMcaSoundIds() {
        assertNull(CharacterSoundHandler.mcaSoundLocation(CharacterSoundHandler.SoundKind.AMBIENT, false, false));
        assertEquals("mca:villager.male.snore",
                CharacterSoundHandler.mcaSoundLocation(CharacterSoundHandler.SoundKind.AMBIENT, false, true).toString());
        assertEquals("mca:villager.female.snore",
                CharacterSoundHandler.mcaSoundLocation(CharacterSoundHandler.SoundKind.AMBIENT, true, true).toString());
        assertEquals("mca:villager.male.hurt",
                CharacterSoundHandler.mcaSoundLocation(CharacterSoundHandler.SoundKind.HURT, false, false).toString());
        assertEquals("mca:villager.female.hurt",
                CharacterSoundHandler.mcaSoundLocation(CharacterSoundHandler.SoundKind.HURT, true, false).toString());
        assertEquals("mca:villager.male.scream",
                CharacterSoundHandler.mcaSoundLocation(CharacterSoundHandler.SoundKind.DEATH, false, false).toString());
        assertEquals("mca:villager.female.scream",
                CharacterSoundHandler.mcaSoundLocation(CharacterSoundHandler.SoundKind.DEATH, true, false).toString());
        assertEquals("mca:villager.male.yes",
                CharacterSoundHandler.mcaSoundLocation(CharacterSoundHandler.SoundKind.YES, false, false).toString());
        assertEquals("mca:villager.female.no",
                CharacterSoundHandler.mcaSoundLocation(CharacterSoundHandler.SoundKind.NO, true, false).toString());
    }
}
