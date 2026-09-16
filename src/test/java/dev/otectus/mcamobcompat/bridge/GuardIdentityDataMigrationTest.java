package dev.otectus.mcamobcompat.bridge;

import dev.otectus.mcamobcompat.api.GuardGender;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the version 1 to version 2 migration of the guard identity store.
 *
 * <p>Version 2 added the hidden MCA proxy, and the migration is supposed to be nothing at all: the
 * new keys are absent in an old file, absent reads as the default, and every old field survives
 * untouched. That claim is easy to make and easy to break with one {@code getCompound} that assumes
 * a key exists, so the old shape is built by hand here rather than produced by the current writer --
 * a round-trip through today's code would prove nothing about yesterday's file.
 *
 * <p>Pure NBT, like the rest of the store's tests: no Minecraft bootstrap.
 */
class GuardIdentityDataMigrationTest {

    private static final UUID GUARD = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID PROXY = UUID.fromString("00000000-0000-4000-8000-0000000000ff");

    /** Exactly what a file written before the proxy existed looks like. */
    private static CompoundTag versionOneFile() {
        CompoundTag guard = new CompoundTag();
        guard.putUUID("UUID", GUARD);
        guard.putString("Name", "Alden");
        guard.putString("Gender", "male");
        guard.putString("Personality", "confident");
        guard.putLong("Created", 100L);
        guard.putBoolean("Deceased", false);
        guard.putLong("DeceasedAt", 0L);

        CompoundTag heart = new CompoundTag();
        heart.putUUID("Player", PLAYER);
        heart.putInt("Hearts", 25);
        ListTag hearts = new ListTag();
        hearts.add(heart);
        guard.put("Hearts", hearts);

        ListTag guards = new ListTag();
        guards.add(guard);
        CompoundTag file = new CompoundTag();
        file.putInt("Version", 1);
        file.put("Guards", guards);
        return file;
    }

    @Test
    void aVersionOneFileKeepsEveryOldFieldAndDefaultsTheNewOnes() {
        GuardIdentityData data = GuardIdentityData.load(versionOneFile());

        assertEquals(1, data.size());
        GuardIdentityData.Entry entry = data.find(GUARD);
        assertNotNull(entry);
        assertEquals("Alden", entry.name());
        assertEquals(GuardGender.MALE, entry.gender());
        assertEquals("confident", entry.personality());
        assertEquals(100L, entry.created());
        assertFalse(entry.deceased());
        assertEquals(25, entry.hearts(PLAYER),
                "Hearts earned before the proxy existed are the one thing that cannot be rebuilt.");

        assertNull(entry.proxyUuid(), "An old guard has no proxy until one is made for it.");
        assertFalse(entry.seeded(), "Nothing has been pushed onto a proxy yet.");
        assertFalse(entry.heartsMigrated(PLAYER),
                "Stored hearts must still be waiting to be handed to MCA, or they are lost.");
        assertEquals(0, entry.pendingHearts(PLAYER));
        assertTrue(entry.pendingHeartPlayers().isEmpty());
    }

    @Test
    void theNewFieldsSurviveASaveAndLoadAtVersionTwo() {
        GuardIdentityData data = GuardIdentityData.load(versionOneFile());
        GuardIdentityData.Entry entry = data.find(GUARD);
        data.linkProxy(entry, PROXY);
        data.markSeeded(entry);
        data.markHeartsMigrated(entry, PLAYER);
        data.addPendingHearts(entry, PLAYER, 7);

        CompoundTag saved = data.save(new CompoundTag());
        assertEquals(GuardIdentityData.SCHEMA_VERSION, saved.getInt("Version"));
        assertEquals(3, GuardIdentityData.SCHEMA_VERSION,
                "Schema 3 separates snapshot balances from queued changes.");

        GuardIdentityData.Entry reloaded = GuardIdentityData.load(saved).find(GUARD);
        assertNotNull(reloaded);
        assertEquals(PROXY, reloaded.proxyUuid());
        assertTrue(reloaded.seeded());
        assertTrue(reloaded.heartsMigrated(PLAYER));
        assertEquals(7, reloaded.pendingHearts(PLAYER));
        assertEquals(25, reloaded.hearts(PLAYER), "The old hearts are still the fallback answer.");
    }

    @Test
    void anEntryIsFoundByItsProxy() {
        GuardIdentityData data = GuardIdentityData.load(versionOneFile());
        assertNull(data.findByProxy(PROXY), "Nothing claims a proxy that was never linked.");
        assertNull(data.findByProxy(null));

        data.linkProxy(data.find(GUARD), PROXY);
        GuardIdentityData.Entry found = data.findByProxy(PROXY);
        assertNotNull(found);
        assertEquals(GUARD, found.uuid());

        data.linkProxy(found, null);
        assertNull(data.findByProxy(PROXY), "Unlinking must really break the lookup.");
    }
}
