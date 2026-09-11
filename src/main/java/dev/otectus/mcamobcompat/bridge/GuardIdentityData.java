package dev.otectus.mcamobcompat.bridge;

import dev.otectus.mcamobcompat.api.GuardGender;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Every bridged guard's identity, stored once for the whole world.
 *
 * <p>Kept in the <b>overworld's</b> {@code DimensionDataStorage} rather than per-level, because a
 * guard UUID is global and a guard can walk through a portal; a per-level store would give the same
 * guard two identities. Nothing here is synced -- this is server data.
 *
 * <p>Only NBT and plain Java types appear below, so the store can be unit-tested without
 * bootstrapping Minecraft. Everything that needs a live entity or a server lives in
 * {@link GuardIdentityStore}.
 *
 * <p>Deceased entries are kept, not deleted: a sibling asking about the guard a player killed last
 * week should still get a name back. {@link #purgeDeceased} is the operator's broom.
 */
public class GuardIdentityData extends SavedData {

    /** The data file name, under the world's {@code data/} folder. */
    public static final String STORAGE_KEY = "mcamobcompat_guard_identities";

    /**
     * Bumped only when the on-disk shape changes; an absent version reads as 1.
     *
     * <p>Version 2 added the hidden MCA proxy villager: the proxy's UUID, whether the stored name
     * and personality have been seeded onto it, which players' stored hearts have already been
     * migrated into MCA's own memories, and hearts banked while the proxy was not loaded. Every one
     * of those keys reads as absent-means-default, so a version 1 file loads unchanged and nothing
     * a player earned before the proxy existed is dropped.
     */
    public static final int SCHEMA_VERSION = 2;

    private static final String KEY_VERSION = "Version";
    private static final String KEY_GUARDS = "Guards";
    private static final String KEY_UUID = "UUID";
    private static final String KEY_NAME = "Name";
    private static final String KEY_GENDER = "Gender";
    private static final String KEY_PERSONALITY = "Personality";
    private static final String KEY_CREATED = "Created";
    private static final String KEY_DECEASED = "Deceased";
    private static final String KEY_DECEASED_AT = "DeceasedAt";
    private static final String KEY_HEARTS = "Hearts";
    private static final String KEY_PLAYER = "Player";
    private static final String KEY_PROXY = "Proxy";
    private static final String KEY_SEEDED = "Seeded";
    private static final String KEY_HEARTS_MIGRATED = "HeartsMigrated";
    private static final String KEY_PENDING_HEARTS = "PendingHearts";

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    /** Reads the store back. A malformed entry is dropped, never thrown over. */
    public static GuardIdentityData load(CompoundTag tag) {
        GuardIdentityData data = new GuardIdentityData();
        ListTag guards = tag.getList(KEY_GUARDS, Tag.TAG_COMPOUND);
        for (int i = 0; i < guards.size(); i++) {
            Entry entry = Entry.load(guards.getCompound(i));
            if (entry != null) {
                data.entries.put(entry.uuid(), entry);
            }
        }
        return data;
    }

    /** The entry for this guard, or null. Never creates. */
    public Entry find(UUID uuid) {
        return uuid == null ? null : entries.get(uuid);
    }

    /** Creates and stores an entry, replacing any existing one for the same guard. */
    public Entry create(UUID uuid, String name, GuardGender gender, String personality, long created) {
        Entry entry = new Entry(uuid, name, gender, personality, created);
        entries.put(uuid, entry);
        setDirty();
        return entry;
    }

    /** Marks a guard dead at this game time. No-op for a guard with no entry. */
    public void markDeceased(UUID uuid, long gameTime) {
        Entry entry = find(uuid);
        if (entry == null || entry.deceased()) {
            return;
        }
        entry.deceased = true;
        entry.deceasedAt = gameTime;
        setDirty();
    }

    /**
     * Removes deceased entries that died at least {@code maxAgeTicks} ago, and returns how many
     * went. Living guards are never touched, whatever their age.
     */
    public int purgeDeceased(long now, long maxAgeTicks) {
        int removed = 0;
        Iterator<Entry> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.deceased() && now - entry.deceasedAt() >= maxAgeTicks) {
                iterator.remove();
                removed++;
            }
        }
        if (removed > 0) {
            setDirty();
        }
        return removed;
    }

    /** How many guards are known, alive and dead. */
    public int size() {
        return entries.size();
    }

    /** How many of them are dead. */
    public int deceasedCount() {
        int count = 0;
        for (Entry entry : entries.values()) {
            if (entry.deceased()) {
                count++;
            }
        }
        return count;
    }

    /** Every entry, in insertion order. */
    public Collection<Entry> entries() {
        return List.copyOf(entries.values());
    }

    /** Applies a heart change and marks the store dirty. Returns the new total. */
    int addHearts(Entry entry, UUID player, int delta) {
        int updated = entry.addHearts(player, delta);
        setDirty();
        return updated;
    }

    /** Adopts a new display name for a guard and marks the store dirty. */
    void rename(Entry entry, String newName) {
        entry.rename(newName);
        setDirty();
    }

    /** Points an entry at its proxy villager, or at nothing when {@code proxy} is null. */
    void linkProxy(Entry entry, UUID proxy) {
        entry.proxyUuid = proxy;
        setDirty();
    }

    /** Records that the stored name and personality have been pushed onto the proxy once. */
    void markSeeded(Entry entry) {
        entry.seeded = true;
        setDirty();
    }

    /** Records that this player's stored hearts have been handed to MCA's own memories. */
    void markHeartsMigrated(Entry entry, UUID player) {
        if (player != null && entry.heartsMigrated.add(player)) {
            setDirty();
        }
    }

    /** Banks a heart change for a player while the proxy is not loaded. Returns the new total. */
    int addPendingHearts(Entry entry, UUID player, int delta) {
        int updated = entry.pendingHearts(player) + delta;
        entry.pendingHearts.put(player, updated);
        setDirty();
        return updated;
    }

    /** Clears one player's banked hearts, once they have been applied to the proxy. */
    void clearPendingHearts(Entry entry, UUID player) {
        if (player != null && entry.pendingHearts.remove(player) != null) {
            setDirty();
        }
    }

    /** The entry whose proxy this is, or null. The proxy link is one-to-one by construction. */
    public Entry findByProxy(UUID proxy) {
        if (proxy == null) {
            return null;
        }
        for (Entry entry : entries.values()) {
            if (proxy.equals(entry.proxyUuid())) {
                return entry;
            }
        }
        return null;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt(KEY_VERSION, SCHEMA_VERSION);
        ListTag guards = new ListTag();
        for (Entry entry : entries.values()) {
            guards.add(entry.save());
        }
        tag.put(KEY_GUARDS, guards);
        return tag;
    }

    /**
     * One guard's stored identity.
     *
     * <p>Its mutators are package-private and routed through {@link GuardIdentityData}, so that
     * every change dirties the store: an entry that changed without {@code setDirty} would look
     * right until the server restarted and then quietly lose the change.
     */
    public static final class Entry {

        private final UUID uuid;
        private final GuardGender gender;
        private final String personality;
        private final long created;
        private final Map<UUID, Integer> hearts = new HashMap<>();

        private final Set<UUID> heartsMigrated = new HashSet<>();
        private final Map<UUID, Integer> pendingHearts = new HashMap<>();

        private String name;
        private boolean deceased;
        private long deceasedAt;
        private UUID proxyUuid;
        private boolean seeded;

        Entry(UUID uuid, String name, GuardGender gender, String personality, long created) {
            this.uuid = uuid;
            this.name = name;
            this.gender = gender;
            this.personality = personality;
            this.created = created;
        }

        static Entry load(CompoundTag tag) {
            if (!tag.hasUUID(KEY_UUID)) {
                return null;
            }
            GuardGender gender;
            try {
                gender = GuardGender.valueOf(tag.getString(KEY_GENDER).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                gender = GuardGender.MALE;
            }
            Entry entry = new Entry(tag.getUUID(KEY_UUID), tag.getString(KEY_NAME), gender,
                    tag.getString(KEY_PERSONALITY), tag.getLong(KEY_CREATED));
            entry.deceased = tag.getBoolean(KEY_DECEASED);
            entry.deceasedAt = tag.getLong(KEY_DECEASED_AT);
            readHearts(tag.getList(KEY_HEARTS, Tag.TAG_COMPOUND), entry.hearts);
            readHearts(tag.getList(KEY_PENDING_HEARTS, Tag.TAG_COMPOUND), entry.pendingHearts);
            // Absent in a version 1 file, and absent-means-default is the whole migration: an old
            // guard has no proxy yet, has never been seeded, and has migrated nobody's hearts.
            if (tag.hasUUID(KEY_PROXY)) {
                entry.proxyUuid = tag.getUUID(KEY_PROXY);
            }
            entry.seeded = tag.getBoolean(KEY_SEEDED);
            ListTag migrated = tag.getList(KEY_HEARTS_MIGRATED, Tag.TAG_COMPOUND);
            for (int i = 0; i < migrated.size(); i++) {
                CompoundTag record = migrated.getCompound(i);
                if (record.hasUUID(KEY_PLAYER)) {
                    entry.heartsMigrated.add(record.getUUID(KEY_PLAYER));
                }
            }
            return entry;
        }

        private static void readHearts(ListTag stored, Map<UUID, Integer> into) {
            for (int i = 0; i < stored.size(); i++) {
                CompoundTag record = stored.getCompound(i);
                if (record.hasUUID(KEY_PLAYER)) {
                    into.put(record.getUUID(KEY_PLAYER), record.getInt(KEY_HEARTS));
                }
            }
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID(KEY_UUID, uuid);
            tag.putString(KEY_NAME, name);
            tag.putString(KEY_GENDER, gender.id());
            tag.putString(KEY_PERSONALITY, personality);
            tag.putLong(KEY_CREATED, created);
            tag.putBoolean(KEY_DECEASED, deceased);
            tag.putLong(KEY_DECEASED_AT, deceasedAt);
            tag.put(KEY_HEARTS, writeHearts(hearts));
            tag.put(KEY_PENDING_HEARTS, writeHearts(pendingHearts));
            if (proxyUuid != null) {
                tag.putUUID(KEY_PROXY, proxyUuid);
            }
            tag.putBoolean(KEY_SEEDED, seeded);
            ListTag migrated = new ListTag();
            for (UUID player : heartsMigrated) {
                CompoundTag record = new CompoundTag();
                record.putUUID(KEY_PLAYER, player);
                migrated.add(record);
            }
            tag.put(KEY_HEARTS_MIGRATED, migrated);
            return tag;
        }

        private static ListTag writeHearts(Map<UUID, Integer> source) {
            ListTag stored = new ListTag();
            for (Map.Entry<UUID, Integer> heart : source.entrySet()) {
                CompoundTag record = new CompoundTag();
                record.putUUID(KEY_PLAYER, heart.getKey());
                record.putInt(KEY_HEARTS, heart.getValue());
                stored.add(record);
            }
            return stored;
        }

        public UUID uuid() {
            return uuid;
        }

        public String name() {
            return name;
        }

        public GuardGender gender() {
            return gender;
        }

        public String personality() {
            return personality;
        }

        public long created() {
            return created;
        }

        public boolean deceased() {
            return deceased;
        }

        public long deceasedAt() {
            return deceasedAt;
        }

        /** This player's hearts, zero if they have never met. */
        public int hearts(UUID player) {
            Integer value = player == null ? null : hearts.get(player);
            return value == null ? 0 : value;
        }

        /** Every player this guard has hearts with. */
        public Collection<UUID> knownPlayers() {
            return new ArrayList<>(hearts.keySet());
        }

        /** The hidden MCA proxy villager's UUID, or null while the guard has none. */
        public UUID proxyUuid() {
            return proxyUuid;
        }

        /** Whether the stored name and personality have been pushed onto a proxy already. */
        public boolean seeded() {
            return seeded;
        }

        /** Whether this player's stored hearts have already been handed to MCA. */
        public boolean heartsMigrated(UUID player) {
            return player != null && heartsMigrated.contains(player);
        }

        /** Hearts banked for a player while the proxy was not loaded. Zero when there are none. */
        public int pendingHearts(UUID player) {
            Integer value = player == null ? null : pendingHearts.get(player);
            return value == null ? 0 : value;
        }

        /** Every player with banked hearts waiting for the proxy. */
        public Set<UUID> pendingHeartPlayers() {
            return Collections.unmodifiableSet(new HashSet<>(pendingHearts.keySet()));
        }

        int addHearts(UUID player, int delta) {
            int updated = hearts(player) + delta;
            hearts.put(player, updated);
            return updated;
        }

        void rename(String newName) {
            this.name = newName;
        }
    }
}
