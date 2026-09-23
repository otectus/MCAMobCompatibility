package dev.otectus.mcamobcompat.bridge;

import dev.otectus.mcamobcompat.api.GuardGender;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the schema 3 heart ledgers: a change is counted exactly once, across restarts and across the
 * whole int range, a version 2 file's double count is undone once, and nothing unreadable or
 * conflicting is ever silently rewritten.
 *
 * <p>Driven with no server, which is the proxy-unloaded case: every change stays queued.
 */
class GuardHeartsAccountingTest {

    private static final UUID GUARD = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID OTHER = UUID.fromString("00000000-0000-4000-8000-0000000000a2");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-4000-8000-0000000000b1");
    private static final UUID PROXY = UUID.fromString("00000000-0000-4000-8000-0000000000c1");

    @Test
    void offlineChangesAreCountedOnceAcrossARestart() {
        GuardIdentityData data = new GuardIdentityData();
        GuardIdentityData.Entry entry = data.create(GUARD, "Rowena", GuardGender.FEMALE, "relaxed", 12L);
        GuardIdentityView view = new GuardIdentityView(null, data, entry);

        view.modHearts(PLAYER, 5);
        assertEquals(5, view.hearts(PLAYER));
        assertEquals(0, entry.hearts(PLAYER), "A queued change must not also land in the balance.");
        assertEquals(5, entry.pendingHearts(PLAYER));

        view.modHearts(PLAYER, -2);
        view.modHearts(PLAYER, 0);
        GuardIdentityData restored = GuardIdentityData.load(data.save(new CompoundTag()));
        GuardIdentityView restoredView = new GuardIdentityView(null, restored, restored.find(GUARD));
        assertEquals(3, restoredView.hearts(PLAYER));

        restoredView.modHearts(PLAYER, 7);
        assertEquals(10, restoredView.hearts(PLAYER));
    }

    @Test
    void arithmeticSaturatesInsteadOfWrapping() {
        assertEquals(Integer.MAX_VALUE, GuardIdentityData.bounded((long) Integer.MAX_VALUE + 5));
        assertEquals(Integer.MIN_VALUE, GuardIdentityData.bounded((long) Integer.MIN_VALUE - 5));

        GuardIdentityData data = new GuardIdentityData();
        GuardIdentityView view = new GuardIdentityView(null, data,
                data.create(GUARD, "A", GuardGender.MALE, "odd", 0L));
        view.modHearts(PLAYER, Integer.MAX_VALUE);
        view.modHearts(PLAYER, 8);
        assertEquals(Integer.MAX_VALUE, view.hearts(PLAYER));
    }

    @Test
    void aQueuedDeltaCanCrossTheWholeBalanceRange() {
        GuardIdentityData data = new GuardIdentityData();
        GuardIdentityData.Entry entry = data.create(GUARD, "A", GuardGender.MALE, "odd", 0L);
        data.snapshotHearts(entry, PLAYER, Integer.MIN_VALUE);
        GuardIdentityView view = new GuardIdentityView(null, data, entry);

        view.modHearts(PLAYER, Integer.MAX_VALUE);
        view.modHearts(PLAYER, Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE - 1, view.hearts(PLAYER),
                "The pending delta exceeds an int and must be kept as a long.");

        GuardIdentityData restored = GuardIdentityData.load(data.save(new CompoundTag()));
        assertEquals(Integer.MAX_VALUE - 1, new GuardIdentityView(null, restored, restored.find(GUARD)).hearts(PLAYER));

        view.modHearts(PLAYER, Integer.MIN_VALUE);
        assertEquals(-2, view.hearts(PLAYER));
    }

    @Test
    void aVersionTwoDoubleCountIsUndoneOnceAndTheOriginalIsKept() {
        CompoundTag record = new CompoundTag();
        record.putUUID("UUID", GUARD);
        record.putString("Name", "A");
        record.putString("Gender", "male");
        record.putString("Personality", "custom:reserved");
        record.putUUID("Proxy", PROXY);
        record.putBoolean("Seeded", true);
        record.put("Hearts", hearts(15));
        record.put("PendingHearts", hearts(5));
        ListTag guards = new ListTag();
        guards.add(record);
        CompoundTag file = new CompoundTag();
        file.putInt("Version", 2);
        file.put("Guards", guards);

        GuardIdentityData data = GuardIdentityData.load(file);
        GuardIdentityData.Entry entry = data.find(GUARD);
        assertEquals(10, entry.hearts(PLAYER), "The version 2 writer added the pending 5 into Hearts too.");
        assertEquals(5, entry.pendingHearts(PLAYER));
        assertEquals(15, new GuardIdentityView(null, data, entry).hearts(PLAYER),
                "What a player had before the upgrade is exactly what they have after it.");
        assertTrue(data.isDirty(), "A migrated file must be written back at the new schema.");

        CompoundTag saved = data.save(new CompoundTag());
        assertEquals(record, saved.getList("Guards", Tag.TAG_COMPOUND).getCompound(0).getCompound("LegacyV2"));
        GuardIdentityData reloaded = GuardIdentityData.load(saved);
        assertEquals(10, reloaded.find(GUARD).hearts(PLAYER), "Loading a version 3 file must not subtract again.");
        assertEquals("custom:reserved", reloaded.find(GUARD).personality());
    }

    @Test
    void aNewerSchemaIsPreservedAndNeverOverwritten() {
        CompoundTag future = new CompoundTag();
        future.putInt("Version", 999);
        future.putString("Unknown", "keep me");
        GuardIdentityData data = GuardIdentityData.load(future);
        assertTrue(data.isReadOnly());
        assertEquals(future, data.save(new CompoundTag()));
        assertThrows(IllegalStateException.class, () -> data.create(GUARD, "A", GuardGender.MALE, "odd", 0L));
    }

    @Test
    void anUnreadableRecordIsWrittenBackVerbatim() {
        CompoundTag broken = new CompoundTag();
        broken.putString("UUID", "not a uuid");
        ListTag records = new ListTag();
        records.add(broken);
        CompoundTag file = new CompoundTag();
        file.putInt("Version", 3);
        file.put("Guards", records);
        GuardIdentityData data = GuardIdentityData.load(file);
        assertEquals(1, data.rejectedCount());
        assertEquals(file, data.save(new CompoundTag()));
    }

    @Test
    void twoGuardsClaimingOneProxyAreQuarantinedAcrossASave() {
        GuardIdentityData data = new GuardIdentityData();
        data.create(GUARD, "A", GuardGender.MALE, "odd", 0L);
        data.create(OTHER, "B", GuardGender.FEMALE, "odd", 0L);
        CompoundTag file = data.save(new CompoundTag());
        ListTag records = file.getList("Guards", Tag.TAG_COMPOUND);
        records.getCompound(0).putUUID("Proxy", PROXY);
        records.getCompound(1).putUUID("Proxy", PROXY);

        GuardIdentityData loaded = GuardIdentityData.load(file);
        assertEquals(2, loaded.quarantinedCount());
        assertNull(loaded.find(GUARD));
        assertNull(loaded.findByProxy(PROXY), "Nobody may claim a contested proxy.");
        assertThrows(IllegalStateException.class, () -> loaded.create(GUARD, "C", GuardGender.MALE, "odd", 0L));
        assertEquals(2, GuardIdentityData.load(loaded.save(new CompoundTag())).quarantinedCount());
    }

    @Test
    void aProxyCannotBeLinkedToTwoGuards() {
        GuardIdentityData data = new GuardIdentityData();
        GuardIdentityData.Entry first = data.create(GUARD, "A", GuardGender.MALE, "odd", 0L);
        GuardIdentityData.Entry second = data.create(OTHER, "B", GuardGender.MALE, "odd", 0L);
        data.linkProxy(first, PROXY);
        assertThrows(IllegalStateException.class, () -> data.linkProxy(second, PROXY));
        assertNotNull(data.findByProxy(PROXY));
        assertEquals(GUARD, data.findByProxy(PROXY).uuid());

        data.linkProxy(first, null);
        data.linkProxy(second, PROXY);
        assertEquals(OTHER, data.findByProxy(PROXY).uuid(), "A released proxy can be linked again.");
    }

    private static ListTag hearts(int amount) {
        CompoundTag record = new CompoundTag();
        record.putUUID("Player", PLAYER);
        record.putInt("Hearts", amount);
        ListTag list = new ListTag();
        list.add(record);
        return list;
    }
}
