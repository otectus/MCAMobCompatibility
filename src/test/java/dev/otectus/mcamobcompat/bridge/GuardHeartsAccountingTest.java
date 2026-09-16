package dev.otectus.mcamobcompat.bridge;

import dev.otectus.mcamobcompat.api.GuardGender;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class GuardHeartsAccountingTest {
    private static final UUID GUARD = UUID.randomUUID(), PLAYER = UUID.randomUUID();
    @Test void offlineChangesAreCountedOnceAcrossRestart() {
        GuardIdentityData data = new GuardIdentityData();
        var entry = data.create(GUARD, "Rowena", GuardGender.FEMALE, "mca:relaxed", 12);
        var view = new GuardIdentityView(null, data, entry);
        view.modHearts(PLAYER, 5);
        assertEquals(5, view.hearts(PLAYER));
        assertEquals(0, entry.hearts(PLAYER));
        assertEquals(5, entry.pendingHearts(PLAYER));
        view.modHearts(PLAYER, -2);
        view.modHearts(PLAYER, 0);
        var restored = GuardIdentityData.load(data.save(new CompoundTag()));
        var restoredView = new GuardIdentityView(null, restored, restored.find(GUARD));
        assertEquals(3, restoredView.hearts(PLAYER));
        restoredView.modHearts(PLAYER, 7);
        assertEquals(10, restoredView.hearts(PLAYER));
    }
    @Test void saturatedArithmeticDoesNotWrap() {
        assertEquals(Integer.MAX_VALUE, GuardIdentityData.bounded((long) Integer.MAX_VALUE + 5));
        assertEquals(Integer.MIN_VALUE, GuardIdentityData.bounded((long) Integer.MIN_VALUE - 5));
        GuardIdentityData data = new GuardIdentityData();
        var entry = data.create(GUARD, "A", GuardGender.MALE, "odd", 0);
        var view = new GuardIdentityView(null, data, entry);
        view.modHearts(PLAYER, Integer.MAX_VALUE);
        view.modHearts(PLAYER, 8);
        assertEquals(Integer.MAX_VALUE, view.hearts(PLAYER));
    }
    @Test void queuedDeltasCanCrossTheEntireBalanceRange() {
        var data = new GuardIdentityData();
        var entry = data.create(GUARD, "A", GuardGender.MALE, "odd", 0);
        data.snapshotHearts(entry, PLAYER, Integer.MIN_VALUE);
        var view = new GuardIdentityView(null, data, entry);
        view.modHearts(PLAYER, Integer.MAX_VALUE);
        view.modHearts(PLAYER, Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE - 1, view.hearts(PLAYER));
        var restored = GuardIdentityData.load(data.save(new CompoundTag()));
        assertEquals(Integer.MAX_VALUE - 1, new GuardIdentityView(null, restored, restored.find(GUARD)).hearts(PLAYER));
        view.modHearts(PLAYER, Integer.MIN_VALUE);
        assertEquals(-2, view.hearts(PLAYER));
    }
    @Test void versionTwoDuplicateLedgerIsMigratedAndOriginalIsRetained() {
        CompoundTag record = new CompoundTag();
        record.putUUID("UUID", GUARD); record.putString("Name", "A"); record.putString("Personality", "custom:reserved");
        record.putUUID("Proxy", UUID.randomUUID()); record.putBoolean("Seeded", true);
        record.put("Hearts", hearts(15)); record.put("PendingHearts", hearts(5));
        ListTag guards = new ListTag(); guards.add(record);
        CompoundTag old = new CompoundTag(); old.putInt("Version", 2); old.put("Guards", guards);
        var data = GuardIdentityData.load(old);
        var entry = data.find(GUARD);
        assertEquals(10, entry.hearts(PLAYER)); assertEquals(5, entry.pendingHearts(PLAYER));
        assertEquals(15, new GuardIdentityView(null, data, entry).hearts(PLAYER));
        CompoundTag saved = data.save(new CompoundTag());
        assertEquals(record, saved.getList("Guards", 10).getCompound(0).getCompound("LegacyV2"));
        assertEquals("custom:reserved", GuardIdentityData.load(saved).find(GUARD).personality());
    }
    @Test void futureSchemaAndMalformedRecordsAreNeverSilentlyRewritten() {
        CompoundTag future = new CompoundTag(); future.putInt("Version", 999); future.putString("Unknown", "keep me");
        var data = GuardIdentityData.load(future);
        assertTrue(data.isReadOnly());
        assertEquals(future, data.save(new CompoundTag()));
        assertThrows(IllegalStateException.class, () -> data.create(GUARD, "A", GuardGender.MALE, "odd", 0));
        CompoundTag broken = new CompoundTag(); broken.putString("UUID", "malformed");
        CompoundTag old = new CompoundTag(); old.putInt("Version", 3);
        ListTag records = new ListTag(); records.add(broken); old.put("Guards", records);
        assertEquals(old, GuardIdentityData.load(old).save(new CompoundTag()));
    }
    @Test void duplicateProxyOwnersRemainQuarantinedAcrossSave() {
        var data = new GuardIdentityData(); UUID other = UUID.randomUUID(), proxy = UUID.randomUUID();
        data.create(GUARD, "A", GuardGender.MALE, "odd", 0);
        data.create(other, "B", GuardGender.FEMALE, "odd", 0);
        var tag = data.save(new CompoundTag());
        var records = tag.getList("Guards", 10);
        records.getCompound(0).putUUID("Proxy", proxy); records.getCompound(1).putUUID("Proxy", proxy);
        var loaded = GuardIdentityData.load(tag);
        assertEquals(2, loaded.quarantinedCount()); assertNull(loaded.find(GUARD)); assertNull(loaded.findByProxy(proxy));
        assertThrows(IllegalStateException.class, () -> loaded.create(GUARD, "C", GuardGender.MALE, "odd", 0));
        assertEquals(2, GuardIdentityData.load(loaded.save(new CompoundTag())).quarantinedCount());
    }
    private static ListTag hearts(int n) {
        CompoundTag record = new CompoundTag(); record.putUUID("Player", PLAYER); record.putInt("Hearts", n);
        ListTag list = new ListTag(); list.add(record); return list;
    }
}
