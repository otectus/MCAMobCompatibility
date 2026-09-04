package dev.otectus.mcamobcompat.bridge;

import dev.otectus.mcamobcompat.api.GuardGender;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trips the guard identity store through NBT.
 *
 * <p>This is the file that holds player-visible state nobody can rebuild by hand -- names players
 * have learned and hearts they have earned -- so the save/load shape is pinned here rather than
 * discovered after somebody's world drops it. The store deliberately touches no registry type, so
 * all of this runs without bootstrapping Minecraft.
 */
class GuardIdentityDataTest {

    private static final UUID GUARD_A = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID GUARD_B = UUID.fromString("00000000-0000-4000-8000-00000000000b");
    private static final UUID PLAYER_ONE = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID PLAYER_TWO = UUID.fromString("00000000-0000-4000-8000-000000000002");

    @Test
    void savesAndLoadsEveryFieldOfEveryEntry() {
        GuardIdentityData data = new GuardIdentityData();
        GuardIdentityData.Entry first =
                data.create(GUARD_A, "Alden", GuardGender.MALE, "confident", 100L);
        GuardIdentityData.Entry second =
                data.create(GUARD_B, "Rowena", GuardGender.FEMALE, "gloomy", 200L);
        data.addHearts(first, PLAYER_ONE, -5);
        data.addHearts(first, PLAYER_TWO, 12);
        data.addHearts(second, PLAYER_ONE, 3);
        data.rename(second, "Rowena the Watchful");
        data.markDeceased(GUARD_B, 500L);

        GuardIdentityData reloaded = GuardIdentityData.load(data.save(new CompoundTag()));

        assertEquals(2, reloaded.size());
        GuardIdentityData.Entry loadedFirst = reloaded.find(GUARD_A);
        assertNotNull(loadedFirst);
        assertEquals("Alden", loadedFirst.name());
        assertEquals(GuardGender.MALE, loadedFirst.gender());
        assertEquals("confident", loadedFirst.personality());
        assertEquals(100L, loadedFirst.created());
        assertEquals(-5, loadedFirst.hearts(PLAYER_ONE));
        assertEquals(12, loadedFirst.hearts(PLAYER_TWO));
        assertFalse(loadedFirst.deceased());

        GuardIdentityData.Entry loadedSecond = reloaded.find(GUARD_B);
        assertNotNull(loadedSecond);
        assertEquals("Rowena the Watchful", loadedSecond.name(), "A rename must survive the save.");
        assertEquals(GuardGender.FEMALE, loadedSecond.gender());
        assertEquals(3, loadedSecond.hearts(PLAYER_ONE));
        assertTrue(loadedSecond.deceased());
        assertEquals(500L, loadedSecond.deceasedAt());
    }

    @Test
    void heartsForAnUnknownPlayerAreZero() {
        GuardIdentityData data = new GuardIdentityData();
        GuardIdentityData.Entry entry =
                data.create(GUARD_A, "Alden", GuardGender.MALE, "odd", 0L);

        assertEquals(0, entry.hearts(PLAYER_ONE));
        assertEquals(0, entry.hearts(null));
    }

    @Test
    void purgeRemovesOnlyDeceasedEntriesPastTheAge() {
        GuardIdentityData data = new GuardIdentityData();
        data.create(GUARD_A, "Alden", GuardGender.MALE, "confident", 0L);
        data.create(GUARD_B, "Rowena", GuardGender.FEMALE, "gloomy", 0L);
        data.markDeceased(GUARD_B, 1000L);

        assertEquals(1, data.deceasedCount());
        assertEquals(0, data.purgeDeceased(2000L, 24000L),
                "A guard dead for under a day must survive a one-day purge.");
        assertEquals(2, data.size());

        assertEquals(1, data.purgeDeceased(30000L, 24000L));
        assertEquals(1, data.size());
        assertNotNull(data.find(GUARD_A), "A living guard is never purged, whatever its age.");
        assertNull(data.find(GUARD_B));
    }

    @Test
    void anEntryWithoutAVersionStillLoads() {
        GuardIdentityData data = new GuardIdentityData();
        data.create(GUARD_A, "Alden", GuardGender.MALE, "confident", 7L);

        // What a file written before the version field existed looks like.
        CompoundTag saved = data.save(new CompoundTag());
        saved.remove("Version");

        GuardIdentityData reloaded = GuardIdentityData.load(saved);
        assertEquals(1, reloaded.size());
        assertEquals("Alden", reloaded.find(GUARD_A).name());
    }
}
