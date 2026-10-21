package dev.otectus.mcamobcompat.bridge;

import dev.otectus.mcamobcompat.api.GuardGender;
import dev.otectus.mcamobcompat.character.CharacterOrigin;
import dev.otectus.mcamobcompat.character.CharacterState;
import dev.otectus.mcamobcompat.character.TransitionKind;
import dev.otectus.mcamobcompat.character.TransitionStage;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins schema 4: every older record becomes a character without changing a single thing it had, the
 * migration is idempotent, and the character fields -- adapter, origin, state, generation, revision,
 * civilian life, journal -- survive a save. The old shapes are built by hand, as the version 1 test
 * does, because a round trip through today's writer would prove nothing about yesterday's file.
 */
class CharacterSchemaMigrationTest {

    private static final UUID GUARD = UUID.fromString("00000000-0000-4000-8000-0000000000d1");
    private static final UUID PROXY = UUID.fromString("00000000-0000-4000-8000-0000000000d2");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-4000-8000-0000000000d3");
    private static final UUID NEW_ACTOR = UUID.fromString("00000000-0000-4000-8000-0000000000d4");
    private static final UUID PERSON = UUID.fromString("00000000-0000-4000-8000-0000000000d5");

    /** A version 3 file: one linked guard with hearts and a pending delta, one guard with no proxy yet. */
    private static CompoundTag versionThreeFile() {
        CompoundTag linked = new CompoundTag();
        linked.putUUID("UUID", GUARD);
        linked.putString("Name", "Maren");
        linked.putString("Gender", "female");
        linked.putString("Personality", "friendly");
        linked.putLong("Created", 40L);
        linked.putBoolean("Deceased", false);
        linked.putUUID("Proxy", PROXY);
        linked.putBoolean("Seeded", true);
        ListTag hearts = new ListTag();
        CompoundTag heart = new CompoundTag();
        heart.putUUID("Player", PLAYER);
        heart.putInt("Hearts", 30);
        hearts.add(heart);
        linked.put("Hearts", hearts);
        ListTag pending = new ListTag();
        CompoundTag delta = new CompoundTag();
        delta.putUUID("Player", PLAYER);
        delta.putLong("Hearts", 4L);
        pending.add(delta);
        linked.put("PendingHearts", pending);
        ListTag migrated = new ListTag();
        CompoundTag record = new CompoundTag();
        record.putUUID("Player", PLAYER);
        migrated.add(record);
        linked.put("HeartsMigrated", migrated);

        CompoundTag unlinked = new CompoundTag();
        unlinked.putUUID("UUID", UUID.fromString("00000000-0000-4000-8000-0000000000d9"));
        unlinked.putString("Name", "Oswin");
        unlinked.putString("Gender", "male");

        ListTag guards = new ListTag();
        guards.add(linked);
        guards.add(unlinked);
        CompoundTag file = new CompoundTag();
        file.putInt("Version", 3);
        file.put("Guards", guards);
        return file;
    }

    @Test
    void aVersionThreeGuardBecomesAMigratedCharacterWithItsProxyAsIdentity() {
        GuardIdentityData data = GuardIdentityData.load(versionThreeFile());
        GuardIdentityData.Entry entry = data.find(GUARD);
        assertNotNull(entry);
        assertEquals(CharacterOrigin.MIGRATED_GUARD_BRIDGE, entry.origin());
        assertEquals(GuardIdentityData.LEGACY_ADAPTER, entry.adapter());
        assertEquals(CharacterState.ACTIVE, entry.state());
        assertEquals(PROXY, entry.characterUuid(), "The existing proxy UUID is the character's UUID.");
        assertEquals(1, entry.generation());
        assertEquals(3, entry.migratedFrom());
        assertEquals(30, entry.hearts(PLAYER), "The MCA snapshot ledger was touched.");
        assertEquals(4L, entry.pendingDelta(PLAYER), "The pending ledger was touched.");
        assertTrue(entry.heartsMigrated(PLAYER));
        assertTrue(entry.seeded(), "A seeded proxy must never be re-seeded.");
        assertEquals("Maren", entry.name());
        assertEquals(GuardGender.FEMALE, entry.gender());
        assertSame(entry, data.findByProxy(PROXY));

        GuardIdentityData.Entry waiting = data.find(UUID.fromString("00000000-0000-4000-8000-0000000000d9"));
        assertEquals(CharacterState.AWAITING_IDENTITY, waiting.state(), "A guard that never got a proxy waits for one.");
        assertNull(waiting.proxyUuid());
    }

    @Test
    void theMigrationIsIdempotent() {
        GuardIdentityData once = GuardIdentityData.load(versionThreeFile());
        CompoundTag first = once.save(new CompoundTag());
        CompoundTag second = GuardIdentityData.load(first.copy()).save(new CompoundTag());
        assertEquals(first, second, "Loading a schema 4 file and saving it again changed it.");
        GuardIdentityData.Entry entry = GuardIdentityData.load(first).find(GUARD);
        assertEquals(3, entry.migratedFrom(), "The provenance was lost in a second round trip.");
        assertEquals(4L, entry.pendingDelta(PLAYER), "A pending delta was added to both ledgers.");
        assertEquals(30, entry.hearts(PLAYER));
    }

    @Test
    void aVersionTwoFileStillKeepsItsLegacyRecordAndIsCountedOnce() {
        CompoundTag file = versionThreeFile();
        file.putInt("Version", 2);
        GuardIdentityData.Entry entry = GuardIdentityData.load(file).find(GUARD);
        assertEquals(26, entry.hearts(PLAYER), "Version 2's double count is undone once, as before.");
        assertEquals(CharacterOrigin.MIGRATED_GUARD_BRIDGE, entry.origin());
        assertEquals(2, entry.migratedFrom());
        assertTrue(GuardIdentityData.load(file).save(new CompoundTag()).getList("Guards", 10).getCompound(0)
                .contains("LegacyV2"), "The version 2 record is no longer preserved.");
    }

    @Test
    void aNewerSchemaIsStillReadOnly() {
        CompoundTag file = versionThreeFile();
        file.putInt("Version", GuardIdentityData.SCHEMA_VERSION + 1);
        GuardIdentityData data = GuardIdentityData.load(file);
        assertTrue(data.isReadOnly());
        assertEquals(file, data.save(new CompoundTag()));
    }

    @Test
    void characterFieldsSurviveASave() {
        GuardIdentityData data = new GuardIdentityData();
        CompoundTag flags = new CompoundTag();
        flags.putBoolean("NoAI", false);
        GuardIdentityData.Entry entry = data.createEnlisted(NEW_ACTOR, PERSON, "Ada", GuardGender.FEMALE, "mca:friendly",
                10L, "mcamobcompat:recruits", "recruits:bowman", "minecraft:farmer", flags);
        UUID tx = data.beginTransition(entry, TransitionKind.ENLIST, PERSON, PLAYER, 10L, new CompoundTag());
        data.advanceTransition(entry, tx, TransitionStage.ACTOR_READY, NEW_ACTOR, 10L, "", null);
        data.advanceTransition(entry, tx, TransitionStage.ASSOCIATION_COMMITTED, null, 10L, "", null);
        data.advanceTransition(entry, tx, TransitionStage.CLEANED, null, 10L, "", CharacterState.ACTIVE);
        data.bumpAppearance(entry);

        GuardIdentityData.Entry loaded = GuardIdentityData.load(data.save(new CompoundTag())).find(NEW_ACTOR);
        assertEquals(CharacterOrigin.ENLISTED_PERSON, loaded.origin());
        assertEquals(CharacterState.ACTIVE, loaded.state());
        assertEquals(PERSON, loaded.characterUuid());
        assertEquals("minecraft:farmer", loaded.civilianProfession());
        assertFalse(loaded.civilianState().getBoolean("NoAI"));
        assertEquals("recruits:bowman", loaded.actorType());
        assertEquals(1, loaded.appearanceRevision());
        assertEquals(tx, loaded.lastTransition());
        assertNull(loaded.openTransition());
        assertEquals(1, loaded.journal().size());
        assertTrue(loaded.seeded(), "A real person's record must never seed the stored roll onto them.");
    }

    @Test
    void oneTransitionAtATime() {
        GuardIdentityData data = new GuardIdentityData();
        GuardIdentityData.Entry entry = data.create(GUARD, "B", GuardGender.MALE, "odd", 0L);
        data.beginTransition(entry, TransitionKind.HOST_REPLACEMENT, GUARD, null, 0L, new CompoundTag());
        assertEquals(CharacterState.TRANSITIONING, entry.state());
        assertThrows(IllegalStateException.class,
                () -> data.beginTransition(entry, TransitionKind.ENLIST, GUARD, null, 0L, new CompoundTag()));
    }

    @Test
    void aRekeyMovesTheRecordToTheNewActorAndBumpsTheGeneration() {
        GuardIdentityData data = new GuardIdentityData();
        GuardIdentityData.Entry entry = data.create(GUARD, "C", GuardGender.MALE, "odd", 0L);
        data.linkProxy(entry, PROXY);
        data.rekeyActor(entry, NEW_ACTOR);
        assertNull(data.find(GUARD), "The old actor still holds the record.");
        assertSame(entry, data.find(NEW_ACTOR));
        assertSame(entry, data.findByProxy(PROXY), "The identity index still names the old actor.");
        assertEquals(2, entry.generation());
        assertEquals(PROXY, entry.characterUuid(), "The character changed with its body.");
        GuardIdentityData reloaded = GuardIdentityData.load(data.save(new CompoundTag()));
        assertEquals(NEW_ACTOR, reloaded.findByProxy(PROXY).uuid());
    }

    @Test
    void anEnlistedPersonCannotBeClaimedTwice() {
        GuardIdentityData data = new GuardIdentityData();
        data.createEnlisted(NEW_ACTOR, PERSON, "Ada", GuardGender.FEMALE, "", 0L, "a", "t", "", new CompoundTag());
        assertThrows(IllegalStateException.class, () -> data.createEnlisted(GUARD, PERSON, "Ada", GuardGender.FEMALE, "",
                0L, "a", "t", "", new CompoundTag()));
    }

    @Test
    void aJournalKeepsOpenTransitionsAndTheNewestSettledOnes() {
        GuardIdentityData data = new GuardIdentityData();
        GuardIdentityData.Entry entry = data.create(GUARD, "D", GuardGender.MALE, "odd", 0L);
        for (int i = 0; i < GuardIdentityData.JOURNAL_LIMIT + 5; i++) {
            UUID tx = data.beginTransition(entry, TransitionKind.HOST_REPLACEMENT, GUARD, null, i, new CompoundTag());
            data.advanceTransition(entry, tx, TransitionStage.ROLLED_BACK, null, i, "", null);
        }
        assertEquals(GuardIdentityData.JOURNAL_LIMIT, entry.journal().size());
        assertEquals(GuardIdentityData.JOURNAL_LIMIT + 4L, entry.journal().get(entry.journal().size() - 1).started());
    }
}
