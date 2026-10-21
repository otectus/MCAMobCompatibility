package dev.otectus.mcamobcompat.bridge;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.api.GuardGender;
import dev.otectus.mcamobcompat.character.CharacterOrigin;
import dev.otectus.mcamobcompat.character.CharacterState;
import dev.otectus.mcamobcompat.character.TransitionKind;
import dev.otectus.mcamobcompat.character.TransitionStage;
import net.minecraft.core.HolderLookup;
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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Every bridged character's record, stored once for the whole world: the native actor, the real MCA
 * villager that carries the person, and the bookkeeping that keeps the two one character.
 *
 * <p>Kept in the <b>overworld's</b> {@code DimensionDataStorage} rather than per-level, because a
 * UUID is global and an actor can walk through a portal; a per-level store would give the same
 * character two records. Nothing here is synced -- this is server data. The storage key and the
 * class name are the 0.2 ones: this is the same store, extended, never a second one beside it.
 *
 * <p>Only NBT and plain Java types appear below, so the store can be unit-tested without
 * bootstrapping Minecraft. Everything that needs a live entity or a server lives in
 * {@link GuardIdentityStore} and the {@code character} package.
 *
 * <p>Records are keyed by the <b>current actor's UUID</b> (the "guard" of 0.2), and indexed by the
 * identity's UUID. The identity's UUID is the canonical character UUID once there is one; the actor's
 * changes when the source mod replaces it (a promotion), which {@link #rekeyActor} handles in one step.
 *
 * <p>Deceased entries are kept, not deleted: a sibling asking about the guard a player killed last
 * week should still get a name back. {@link #purgeDeceased} is the operator's broom.
 *
 * <p><b>Nothing is ever silently thrown away.</b> A record this version cannot read is kept
 * verbatim and written back unchanged; two records that claim the same actor, or the same identity,
 * are quarantined -- preserved, reported by {@code /mcamobcompat characters status}, and never
 * relinked or recreated automatically; and a file written by a newer version of this mod is loaded
 * read-only and never overwritten.
 */
public class GuardIdentityData extends SavedData {

    /** The data file name, under the world's {@code data/} folder. Unchanged since 0.1. */
    public static final String STORAGE_KEY = "mcamobcompat_guard_identities";

    /**
     * Bumped only when the on-disk shape changes; an absent version reads as 1.
     *
     * <p>Version 2 added the hidden MCA proxy villager: the proxy's UUID, whether the stored name
     * and personality have been seeded onto it, which players' stored hearts have been handed to
     * MCA's own memories, and hearts banked while the proxy was not loaded.
     *
     * <p>Version 3 separates the two heart ledgers. The version 2 writer added an offline change to
     * <em>both</em> {@code Hearts} and {@code PendingHearts}, and the reader summed them, so every
     * change made while the proxy was unloaded counted twice. Version 3 keeps {@code Hearts} as the
     * last balance read from MCA (or, before a player is migrated, the pre-proxy base) and
     * {@code PendingHearts} as the unapplied deltas only, stored as longs. Loading a version 2 file
     * subtracts each pending delta from the stored balance once, and keeps the untouched version 2
     * record under {@code LegacyV2} for audit and recovery.
     *
     * <p>Version 4 makes each record a character (0.3.0): which adapter and entity type the actor is,
     * how the character came about ({@link CharacterOrigin}), its lifecycle state, an association
     * generation bumped whenever the actor is replaced, an appearance revision, what an enlisted
     * person's civilian life looked like, and a journal of structural transitions. A record migrated
     * from versions 1 to 3 is a {@link CharacterOrigin#MIGRATED_GUARD_BRIDGE} with its existing proxy
     * UUID as the character's UUID; nothing about its hearts, names or proxy changes.
     */
    public static final int SCHEMA_VERSION = 4;

    /** Adapter id every pre-0.3 record belongs to: they were all Guard Villagers bridges. */
    public static final String LEGACY_ADAPTER = McaMobCompat.MOD_ID + ":guardvillagers";

    /** How many settled transitions a record keeps for diagnostics; open ones are never dropped. */
    static final int JOURNAL_LIMIT = 16;

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
    private static final String KEY_LEGACY_V2 = "LegacyV2";
    private static final String KEY_ADAPTER = "Adapter";
    private static final String KEY_ACTOR_TYPE = "ActorType";
    private static final String KEY_ORIGIN = "Origin";
    private static final String KEY_STATE = "State";
    private static final String KEY_GENERATION = "Generation";
    private static final String KEY_APPEARANCE = "AppearanceRevision";
    private static final String KEY_CIVILIAN_PROFESSION = "CivilianProfession";
    private static final String KEY_CIVILIAN_STATE = "CivilianState";
    private static final String KEY_JOURNAL = "Journal";
    private static final String KEY_MIGRATED_FROM = "MigratedFrom";
    private static final String KEY_LAST_DIMENSION = "LastDimension";
    private static final String KEY_LAST_POS = "LastPos";
    private static final String KEY_LAST_TRANSITION = "LastTransition";

    /** A whole file from a newer schema, preserved verbatim. Non-null means read-only. */
    private CompoundTag futureData;

    /** Records that could not be read, written back exactly as they were found. */
    private final ListTag rejectedRecords = new ListTag();

    /** Actors whose saved records conflict. Preserved, reported, never relinked. */
    private final Set<UUID> quarantinedGuards = new HashSet<>();

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    /**
     * Identity (proxy) UUID to actor UUID, for the hot path: {@code GuardProxy.isProxy} asks it for
     * every MCA villager that ticks, so a linear scan of the entries would cost villagers times
     * actors per tick. Maintained by {@link #linkProxy} and {@link #rekeyActor}, rebuilt on load.
     */
    private final Map<UUID, UUID> guardByProxy = new HashMap<>();

    /** Reads the store back. A malformed record is preserved verbatim, never thrown over. */
    public static GuardIdentityData load(CompoundTag tag) {
        GuardIdentityData data = new GuardIdentityData();
        int version = tag.contains(KEY_VERSION) ? tag.getInt(KEY_VERSION) : 1;
        if (version < 1 || version > SCHEMA_VERSION) {
            data.futureData = tag.copy();
            McaMobCompat.LOGGER.error("Character identities use schema {}, which this version of {} does not "
                    + "understand; preserving the file read-only.", version, McaMobCompat.MOD_ID);
            return data;
        }

        ListTag guards = tag.getList(KEY_GUARDS, Tag.TAG_COMPOUND);
        for (int i = 0; i < guards.size(); i++) {
            CompoundTag raw = guards.getCompound(i);
            try {
                Entry entry = Entry.load(raw, version);
                if (entry == null) {
                    data.rejectedRecords.add(raw.copy());
                    continue;
                }
                if (data.entries.containsKey(entry.uuid())) {
                    data.rejectedRecords.add(raw.copy());
                    data.quarantinedGuards.add(entry.uuid());
                    continue;
                }
                if (version == 2) {
                    // The version 2 writer added every offline delta to both ledgers. Recover the base
                    // once, and keep the original record for anyone auditing an edited save.
                    entry.legacyV2 = raw.copy();
                    for (Map.Entry<UUID, Long> pending : entry.pendingHearts.entrySet()) {
                        entry.hearts.put(pending.getKey(),
                                bounded((long) entry.hearts(pending.getKey()) - pending.getValue()));
                    }
                }
                data.entries.put(entry.uuid(), entry);
            } catch (RuntimeException malformed) {
                data.rejectedRecords.add(raw.copy());
            }
        }

        for (Entry entry : data.entries.values()) {
            if (entry.proxyUuid() == null) {
                continue;
            }
            UUID previous = data.guardByProxy.putIfAbsent(entry.proxyUuid(), entry.uuid());
            if (previous != null) {
                data.quarantinedGuards.add(previous);
                data.quarantinedGuards.add(entry.uuid());
            }
            // An identity that is also some record's actor is the same entity claimed twice over.
            if (data.entries.containsKey(entry.proxyUuid())) {
                data.quarantinedGuards.add(entry.proxyUuid());
                data.quarantinedGuards.add(entry.uuid());
            }
        }
        if (!data.quarantinedGuards.isEmpty()) {
            data.guardByProxy.values().removeIf(data.quarantinedGuards::contains);
            McaMobCompat.LOGGER.error("Conflicting saved actor or identity UUIDs; preserving {} record(s) "
                    + "without relinking or recreating them: {}", data.quarantinedGuards.size(),
                    data.quarantinedGuards);
        }
        if (version < SCHEMA_VERSION) {
            data.setDirty();
        }
        return data;
    }

    private void requireWritable() {
        if (futureData != null) {
            throw new IllegalStateException("Character identities use an unsupported schema; refusing to "
                    + "overwrite them");
        }
    }

    /** True when the file came from a newer schema and is being preserved untouched. */
    public boolean isReadOnly() {
        return futureData != null;
    }

    /** True when this actor's saved record conflicts with another and is preserved as-is. */
    public boolean isQuarantined(UUID guard) {
        return guard != null && quarantinedGuards.contains(guard);
    }

    public int quarantinedCount() {
        return quarantinedGuards.size();
    }

    /** The quarantined actor UUIDs, for the repair report. */
    public Set<UUID> quarantined() {
        return Collections.unmodifiableSet(new HashSet<>(quarantinedGuards));
    }

    /** Records preserved verbatim because they could not be read. */
    public int rejectedCount() {
        return rejectedRecords.size();
    }

    /** Saturating long-to-int, so heart arithmetic can never wrap round. */
    public static int bounded(long value) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
    }

    /** The entry for this actor, or null. Never creates; never returns a quarantined entry. */
    public Entry find(UUID uuid) {
        return uuid == null || isQuarantined(uuid) ? null : entries.get(uuid);
    }

    /** The entry whose character UUID (identity UUID, or actor UUID before there is one) this is. */
    public Entry findByCharacter(UUID character) {
        Entry byIdentity = findByProxy(character);
        return byIdentity != null ? byIdentity : find(character);
    }

    /** Creates and stores an entry for an actor adopted in place, replacing any existing one. */
    public Entry create(UUID uuid, String name, GuardGender gender, String personality, long created) {
        requireWritable();
        if (isQuarantined(uuid)) {
            throw new IllegalStateException("Character record " + uuid + " is quarantined");
        }
        Entry previous = entries.get(uuid);
        if (previous != null && previous.proxyUuid() != null) {
            guardByProxy.remove(previous.proxyUuid());
        }
        Entry entry = new Entry(uuid, name, gender, personality, created);
        entry.origin = CharacterOrigin.ADOPTED_ACTOR;
        entry.state = CharacterState.AWAITING_IDENTITY;
        entries.put(uuid, entry);
        setDirty();
        return entry;
    }

    /**
     * Creates the record of an existing MCA villager taking a native role. The identity is that
     * villager, already alive with its own name and personality, so the record is born seeded: the
     * stored roll must never be pushed onto a real person.
     *
     * @throws IllegalStateException if either UUID already belongs to another record
     */
    public Entry createEnlisted(UUID actor, UUID identity, String name, GuardGender gender, String personality,
                                long created, String adapter, String actorType, String civilianProfession,
                                CompoundTag civilianState) {
        requireWritable();
        if (actor == null || identity == null || actor.equals(identity)) {
            throw new IllegalArgumentException("An enlisted character needs two distinct UUIDs");
        }
        if (entries.containsKey(actor) || isQuarantined(actor)) {
            throw new IllegalStateException("Actor " + actor + " already has a record");
        }
        if (guardByProxy.containsKey(identity) || entries.containsKey(identity)) {
            throw new IllegalStateException("Identity " + identity + " already belongs to a record");
        }
        Entry entry = new Entry(actor, name, gender, personality, created);
        entry.origin = CharacterOrigin.ENLISTED_PERSON;
        entry.state = CharacterState.TRANSITIONING;
        entry.adapter = adapter == null ? "" : adapter;
        entry.actorType = actorType == null ? "" : actorType;
        entry.civilianProfession = civilianProfession == null ? "" : civilianProfession;
        entry.civilianState = civilianState == null ? new CompoundTag() : civilianState.copy();
        entry.seeded = true;
        entry.proxyUuid = identity;
        entries.put(actor, entry);
        guardByProxy.put(identity, actor);
        setDirty();
        return entry;
    }

    /** Forgets a record outright. Only for a transition rolled back before anything was committed. */
    public void remove(Entry entry) {
        requireWritable();
        if (entry == null || entries.get(entry.uuid()) != entry) {
            return;
        }
        entries.remove(entry.uuid());
        if (entry.proxyUuid() != null) {
            guardByProxy.remove(entry.proxyUuid(), entry.uuid());
        }
        setDirty();
    }

    /** Marks an actor dead at this game time. No-op for an actor with no entry. */
    public void markDeceased(UUID uuid, long gameTime) {
        if (isReadOnly()) {
            return;
        }
        Entry entry = find(uuid);
        if (entry == null || entry.deceased()) {
            return;
        }
        entry.deceased = true;
        entry.deceasedAt = gameTime;
        entry.state = CharacterState.DECEASED;
        setDirty();
    }

    /**
     * Removes deceased entries that died at least {@code maxAgeTicks} ago, and returns how many
     * went. Living characters are never touched, whatever their age, and neither are quarantined ones.
     */
    public int purgeDeceased(long now, long maxAgeTicks) {
        requireWritable();
        int removed = 0;
        Iterator<Entry> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (!isQuarantined(entry.uuid()) && entry.deceased() && now - entry.deceasedAt() >= maxAgeTicks) {
                if (entry.proxyUuid() != null) {
                    guardByProxy.remove(entry.proxyUuid());
                }
                iterator.remove();
                removed++;
            }
        }
        if (removed > 0) {
            setDirty();
        }
        return removed;
    }

    /** How many characters are known, alive and dead. */
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

    /** Every entry, in insertion order, quarantined ones included (they report as such). */
    public Collection<Entry> entries() {
        return List.copyOf(entries.values());
    }

    /** Every entry with a transition that has not settled, for recovery and the transitions report. */
    public List<Entry> withOpenTransitions() {
        List<Entry> open = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (entry.openTransition() != null) {
                open.add(entry);
            }
        }
        return open;
    }

    /** Applies a heart change to the stored balance and marks the store dirty. Returns the new total. */
    int addHearts(Entry entry, UUID player, int delta) {
        requireWritable();
        int updated = entry.addHearts(player, delta);
        setDirty();
        return updated;
    }

    /** Adopts a new display name for a character and marks the store dirty. */
    void rename(Entry entry, String newName) {
        requireWritable();
        if (!Objects.equals(entry.name(), newName)) {
            entry.rename(newName);
            setDirty();
        }
    }

    /**
     * Points an entry at its identity villager, or at nothing when {@code proxy} is null.
     *
     * @throws IllegalStateException if another record already owns this identity: the link is
     *                               one-to-one, and a second owner would hand one MCA villager to
     *                               two actors
     */
    void linkProxy(Entry entry, UUID proxy) {
        requireWritable();
        UUID owner = proxy == null ? null : guardByProxy.get(proxy);
        if (owner != null && !owner.equals(entry.uuid())) {
            throw new IllegalStateException("Identity " + proxy + " already belongs to actor " + owner);
        }
        if (entry.proxyUuid != null) {
            guardByProxy.remove(entry.proxyUuid);
        }
        entry.proxyUuid = proxy;
        if (proxy != null) {
            guardByProxy.put(proxy, entry.uuid());
            if (entry.state == CharacterState.AWAITING_IDENTITY) {
                entry.state = CharacterState.ACTIVE;
            }
        } else if (!entry.deceased && entry.state == CharacterState.ACTIVE) {
            entry.state = CharacterState.AWAITING_IDENTITY;
        }
        setDirty();
    }

    /**
     * Moves a record to a new actor: the source mod replaced the entity (a promotion), the character
     * did not change. One step, so no lookup ever sees the record under both keys or under neither.
     * Bumps the association generation, which is what tells clients and stale entities apart.
     *
     * @throws IllegalStateException if the new actor already has a record of its own
     */
    public void rekeyActor(Entry entry, UUID newActor) {
        requireWritable();
        if (entry == null || newActor == null || newActor.equals(entry.uuid())) {
            return;
        }
        if (entries.containsKey(newActor) || isQuarantined(newActor) || isQuarantined(entry.uuid())) {
            throw new IllegalStateException("Actor " + newActor + " already has a record");
        }
        entries.remove(entry.uuid());
        entry.uuid = newActor;
        entries.put(newActor, entry);
        if (entry.proxyUuid != null) {
            guardByProxy.put(entry.proxyUuid, newActor);
        }
        entry.generation++;
        setDirty();
    }

    /** Records which adapter and entity type this record's actor is. Idempotent. */
    public void assignActor(Entry entry, String adapter, String actorType) {
        requireWritable();
        String a = adapter == null ? "" : adapter;
        String t = actorType == null ? "" : actorType;
        if (!a.equals(entry.adapter) || !t.equals(entry.actorType)) {
            entry.adapter = a;
            entry.actorType = t;
            setDirty();
        }
    }

    /** Moves the record to a lifecycle state. A deceased record stays deceased. */
    public void setState(Entry entry, CharacterState state) {
        requireWritable();
        if (state == null || state == CharacterState.QUARANTINED || entry.state == state
                || (entry.deceased && state != CharacterState.DECEASED)) {
            return;
        }
        entry.state = state;
        setDirty();
    }

    /** Bumps the association generation: anything still naming the old one is stale. */
    public int bumpGeneration(Entry entry) {
        requireWritable();
        entry.generation++;
        setDirty();
        return entry.generation;
    }

    /** Bumps the public appearance revision, after an editor save or a detected appearance change. */
    public int bumpAppearance(Entry entry) {
        requireWritable();
        entry.appearanceRevision++;
        setDirty();
        return entry.appearanceRevision;
    }

    /** Records where the actor was last seen, for diagnostics. Cheap: only dirties on a change. */
    public void noteLocation(Entry entry, String dimension, long blockPos) {
        if (isReadOnly() || entry == null) {
            return;
        }
        String dim = dimension == null ? "" : dimension;
        if (!dim.equals(entry.lastDimension) || blockPos != entry.lastPos) {
            entry.lastDimension = dim;
            entry.lastPos = blockPos;
            setDirty();
        }
    }

    /**
     * Opens a transition on this record and returns its id. Refuses while another is open: a
     * character goes through one structural change at a time, and recovery comes first.
     *
     * @throws IllegalStateException if a transition is already open
     */
    public UUID beginTransition(Entry entry, TransitionKind kind, UUID sourceActor, UUID player, long gameTime,
                         CompoundTag receipt) {
        requireWritable();
        if (entry.openTransition() != null) {
            throw new IllegalStateException("Character " + entry.characterUuid() + " is already in a transition");
        }
        Transition transition = new Transition(UUID.randomUUID(), kind, TransitionStage.PREPARED, gameTime,
                sourceActor, null, player, receipt == null ? new CompoundTag() : receipt.copy(), "");
        entry.journal.add(transition);
        entry.trimJournal();
        if (!entry.deceased) {
            entry.stateBeforeTransition = entry.state;
            entry.state = CharacterState.TRANSITIONING;
        }
        setDirty();
        return transition.id;
    }

    /**
     * Advances a transition to {@code stage}. Moving to a terminal stage restores the record's
     * lifecycle state from before the transition, unless {@code settledState} names a new one.
     *
     * @return false when the transition is unknown or already settled
     */
    public boolean advanceTransition(Entry entry, UUID id, TransitionStage stage, UUID targetActor, long gameTime,
                              String note, CharacterState settledState) {
        requireWritable();
        Transition transition = entry.transition(id);
        if (transition == null || transition.stage.isTerminal() || stage == null) {
            return false;
        }
        transition.stage = stage;
        transition.updated = gameTime;
        if (targetActor != null) {
            transition.targetActor = targetActor;
        }
        if (note != null && !note.isBlank()) {
            transition.note = note;
        }
        if (stage.isCommitted()) {
            entry.lastTransition = id;
        }
        if (stage.isTerminal() && !entry.deceased) {
            CharacterState next = settledState != null ? settledState : entry.stateBeforeTransition;
            entry.state = next == null || next == CharacterState.TRANSITIONING ? CharacterState.ACTIVE : next;
            entry.stateBeforeTransition = null;
            entry.trimJournal();
        }
        setDirty();
        return true;
    }

    /**
     * Rebuilds what an enlisted person's record must know from the stamps on the person themselves,
     * when the store's copy was lost to an interrupted save: the origin and the civilian life to
     * return to. Never applied to a record already an enlisted person's.
     */
    public void restoreEnlistment(Entry entry, String profession, CompoundTag state, int generation) {
        requireWritable();
        if (entry.origin == CharacterOrigin.ENLISTED_PERSON) {
            return;
        }
        entry.origin = CharacterOrigin.ENLISTED_PERSON;
        entry.seeded = true;
        entry.civilianProfession = profession == null ? "" : profession;
        entry.civilianState = state == null ? new CompoundTag() : state.copy();
        entry.generation = Math.max(entry.generation, generation);
        setDirty();
    }

    /** Records the civilian profession and the flags proxy mode will change, for a later return. */
    public void recordCivilianLife(Entry entry, String profession, CompoundTag state) {
        requireWritable();
        entry.civilianProfession = profession == null ? "" : profession;
        entry.civilianState = state == null ? new CompoundTag() : state.copy();
        setDirty();
    }

    /** Records that the stored name and personality have been pushed onto the proxy once. */
    public void markSeeded(Entry entry) {
        requireWritable();
        if (!entry.seeded) {
            entry.seeded = true;
            setDirty();
        }
    }

    /** Records that this player's stored hearts have been handed to MCA's own memories. */
    void markHeartsMigrated(Entry entry, UUID player) {
        requireWritable();
        if (player != null && entry.heartsMigrated.add(player)) {
            setDirty();
        }
    }

    /** Banks a heart change for a player until MCA can take it. Returns the new pending total. */
    int addPendingHearts(Entry entry, UUID player, int delta) {
        requireWritable();
        long updated = entry.pendingDelta(player) + delta;
        if (updated == 0L) {
            entry.pendingHearts.remove(player);
        } else {
            entry.pendingHearts.put(player, updated);
        }
        setDirty();
        return bounded(updated);
    }

    /** Clears one player's banked hearts, once MCA has taken them. */
    void clearPendingHearts(Entry entry, UUID player) {
        requireWritable();
        if (player != null && entry.pendingHearts.remove(player) != null) {
            setDirty();
        }
    }

    /** Records the balance MCA itself reports, so an unloaded character still answers correctly. */
    void snapshotHearts(Entry entry, UUID player, int balance) {
        requireWritable();
        Integer previous = entry.hearts.put(player, balance);
        if (previous == null || previous != balance) {
            setDirty();
        }
    }

    /** Records the personality MCA itself reports. */
    void snapshotPersonality(Entry entry, String value) {
        requireWritable();
        if (value != null && !Objects.equals(entry.personality, value)) {
            entry.personality = value;
            setDirty();
        }
    }

    /** The entry whose identity (proxy) this is, or null. The link is one-to-one by construction. */
    public Entry findByProxy(UUID proxy) {
        if (proxy == null) {
            return null;
        }
        UUID guard = guardByProxy.get(proxy);
        return guard == null ? null : find(guard);
    }

    /** SavedData's writer on this version; the registries are not needed for anything stored here. */
    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        return save(tag);
    }

    /** The whole store as NBT, at the current schema; a newer schema is handed back untouched. */
    public CompoundTag save(CompoundTag tag) {
        if (futureData != null) {
            return futureData.copy();
        }
        tag.putInt(KEY_VERSION, SCHEMA_VERSION);
        ListTag guards = new ListTag();
        for (Entry entry : entries.values()) {
            guards.add(entry.save());
        }
        for (Tag rejected : rejectedRecords) {
            guards.add(rejected.copy());
        }
        tag.put(KEY_GUARDS, guards);
        return tag;
    }

    /** One structural transition of a character, as journaled in its record. */
    public static final class Transition {

        private final UUID id;
        private final TransitionKind kind;
        private final long started;
        private final UUID sourceActor;
        private final UUID player;
        private final CompoundTag receipt;
        private TransitionStage stage;
        private long updated;
        private UUID targetActor;
        private String note;

        Transition(UUID id, TransitionKind kind, TransitionStage stage, long started, UUID sourceActor,
                   UUID targetActor, UUID player, CompoundTag receipt, String note) {
            this.id = id;
            this.kind = kind;
            this.stage = stage;
            this.started = started;
            this.updated = started;
            this.sourceActor = sourceActor;
            this.targetActor = targetActor;
            this.player = player;
            this.receipt = receipt;
            this.note = note == null ? "" : note;
        }

        static Transition load(CompoundTag tag) {
            TransitionKind kind = TransitionKind.parse(tag.getString("Kind"));
            TransitionStage stage = TransitionStage.parse(tag.getString("Stage"));
            if (!tag.hasUUID("Id") || kind == null || stage == null) {
                return null;
            }
            Transition transition = new Transition(tag.getUUID("Id"), kind, stage, tag.getLong("Started"),
                    tag.hasUUID("Source") ? tag.getUUID("Source") : null,
                    tag.hasUUID("Target") ? tag.getUUID("Target") : null,
                    tag.hasUUID("Player") ? tag.getUUID("Player") : null,
                    tag.getCompound("Receipt").copy(), tag.getString("Note"));
            transition.updated = tag.getLong("Updated");
            return transition;
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("Id", id);
            tag.putString("Kind", kind.id());
            tag.putString("Stage", stage.id());
            tag.putLong("Started", started);
            tag.putLong("Updated", updated);
            if (sourceActor != null) {
                tag.putUUID("Source", sourceActor);
            }
            if (targetActor != null) {
                tag.putUUID("Target", targetActor);
            }
            if (player != null) {
                tag.putUUID("Player", player);
            }
            tag.put("Receipt", receipt.copy());
            if (!note.isEmpty()) {
                tag.putString("Note", note);
            }
            return tag;
        }

        public UUID id() {
            return id;
        }

        public TransitionKind kind() {
            return kind;
        }

        public TransitionStage stage() {
            return stage;
        }

        public long started() {
            return started;
        }

        public long updated() {
            return updated;
        }

        public UUID sourceActor() {
            return sourceActor;
        }

        public UUID targetActor() {
            return targetActor;
        }

        public UUID player() {
            return player;
        }

        /** What the transition took or must give back: a copy, so a reader cannot rewrite the evidence. */
        public CompoundTag receipt() {
            return receipt.copy();
        }

        public String note() {
            return note;
        }
    }

    /**
     * One character's stored record.
     *
     * <p>Its mutators are package-private and routed through {@link GuardIdentityData}, so that
     * every change dirties the store: an entry that changed without {@code setDirty} would look
     * right until the server restarted and then quietly lose the change.
     */
    public static final class Entry {

        private UUID uuid;
        private final GuardGender gender;
        private final long created;

        /** The last MCA balance per player, or the pre-proxy base until that player is migrated. */
        private final Map<UUID, Integer> hearts = new HashMap<>();

        private final Set<UUID> heartsMigrated = new HashSet<>();

        /** Deltas MCA has not taken yet. A long, because a delta can span the whole int range. */
        private final Map<UUID, Long> pendingHearts = new HashMap<>();

        private final List<Transition> journal = new ArrayList<>();

        private String name;
        private String personality;
        private boolean deceased;
        private long deceasedAt;
        private UUID proxyUuid;
        private boolean seeded;
        private CompoundTag legacyV2;

        private String adapter = "";
        private String actorType = "";
        private CharacterOrigin origin = CharacterOrigin.ADOPTED_ACTOR;
        private CharacterState state = CharacterState.AWAITING_IDENTITY;
        private CharacterState stateBeforeTransition;
        private int generation = 1;
        private int appearanceRevision;
        private String civilianProfession = "";
        private CompoundTag civilianState = new CompoundTag();
        private int migratedFrom;
        private String lastDimension = "";
        private long lastPos;
        private UUID lastTransition;

        Entry(UUID uuid, String name, GuardGender gender, String personality, long created) {
            this.uuid = uuid;
            this.name = name;
            this.gender = gender;
            this.personality = personality;
            this.created = created;
        }

        static Entry load(CompoundTag tag, int version) {
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

            ListTag stored = tag.getList(KEY_HEARTS, Tag.TAG_COMPOUND);
            for (int i = 0; i < stored.size(); i++) {
                CompoundTag record = stored.getCompound(i);
                if (record.hasUUID(KEY_PLAYER) && record.contains(KEY_HEARTS, Tag.TAG_ANY_NUMERIC)) {
                    entry.hearts.put(record.getUUID(KEY_PLAYER), record.getInt(KEY_HEARTS));
                }
            }
            // Version 2 wrote pending hearts as ints; version 3 writes longs. getLong reads both.
            ListTag pending = tag.getList(KEY_PENDING_HEARTS, Tag.TAG_COMPOUND);
            for (int i = 0; i < pending.size(); i++) {
                CompoundTag record = pending.getCompound(i);
                if (record.hasUUID(KEY_PLAYER) && record.contains(KEY_HEARTS, Tag.TAG_ANY_NUMERIC)) {
                    long delta = record.getLong(KEY_HEARTS);
                    if (delta != 0L) {
                        entry.pendingHearts.put(record.getUUID(KEY_PLAYER), delta);
                    }
                }
            }

            // Absent in a version 1 file, and absent-means-default is that whole migration: an old
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
            if (tag.contains(KEY_LEGACY_V2, Tag.TAG_COMPOUND)) {
                entry.legacyV2 = tag.getCompound(KEY_LEGACY_V2).copy();
            }

            CharacterState derived = entry.deceased ? CharacterState.DECEASED
                    : entry.proxyUuid != null ? CharacterState.ACTIVE : CharacterState.AWAITING_IDENTITY;
            if (version < 4) {
                // Every record before 0.3.0 is a Guard Villagers bridge whose proxy, when it has one,
                // is the character's identity. Nothing else is invented: the actor's type is learned
                // the next time it is seen, and the generation starts at one.
                entry.origin = CharacterOrigin.MIGRATED_GUARD_BRIDGE;
                entry.adapter = LEGACY_ADAPTER;
                entry.state = derived;
                entry.migratedFrom = version;
                return entry;
            }
            entry.adapter = tag.getString(KEY_ADAPTER);
            entry.actorType = tag.getString(KEY_ACTOR_TYPE);
            entry.origin = CharacterOrigin.parse(tag.getString(KEY_ORIGIN), CharacterOrigin.ADOPTED_ACTOR);
            entry.state = entry.deceased ? CharacterState.DECEASED : CharacterState.parse(tag.getString(KEY_STATE), derived);
            entry.generation = Math.max(1, tag.getInt(KEY_GENERATION));
            entry.appearanceRevision = Math.max(0, tag.getInt(KEY_APPEARANCE));
            entry.civilianProfession = tag.getString(KEY_CIVILIAN_PROFESSION);
            entry.civilianState = tag.getCompound(KEY_CIVILIAN_STATE).copy();
            entry.migratedFrom = tag.getInt(KEY_MIGRATED_FROM);
            entry.lastDimension = tag.getString(KEY_LAST_DIMENSION);
            entry.lastPos = tag.getLong(KEY_LAST_POS);
            if (tag.hasUUID(KEY_LAST_TRANSITION)) {
                entry.lastTransition = tag.getUUID(KEY_LAST_TRANSITION);
            }
            ListTag journal = tag.getList(KEY_JOURNAL, Tag.TAG_COMPOUND);
            for (int i = 0; i < journal.size(); i++) {
                Transition transition = Transition.load(journal.getCompound(i));
                if (transition != null) {
                    entry.journal.add(transition);
                }
            }
            if (entry.state == CharacterState.TRANSITIONING && entry.openTransition() == null) {
                // A transitioning record whose journal settled is simply in its derived state.
                entry.state = derived;
            }
            return entry;
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            if (legacyV2 != null) {
                tag.put(KEY_LEGACY_V2, legacyV2.copy());
            }
            tag.putUUID(KEY_UUID, uuid);
            tag.putString(KEY_NAME, name == null ? "" : name);
            tag.putString(KEY_GENDER, gender.id());
            tag.putString(KEY_PERSONALITY, personality == null ? "" : personality);
            tag.putLong(KEY_CREATED, created);
            tag.putBoolean(KEY_DECEASED, deceased);
            tag.putLong(KEY_DECEASED_AT, deceasedAt);

            ListTag stored = new ListTag();
            for (Map.Entry<UUID, Integer> heart : hearts.entrySet()) {
                CompoundTag record = new CompoundTag();
                record.putUUID(KEY_PLAYER, heart.getKey());
                record.putInt(KEY_HEARTS, heart.getValue());
                stored.add(record);
            }
            tag.put(KEY_HEARTS, stored);

            ListTag pending = new ListTag();
            for (Map.Entry<UUID, Long> delta : pendingHearts.entrySet()) {
                CompoundTag record = new CompoundTag();
                record.putUUID(KEY_PLAYER, delta.getKey());
                record.putLong(KEY_HEARTS, delta.getValue());
                pending.add(record);
            }
            tag.put(KEY_PENDING_HEARTS, pending);

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

            tag.putString(KEY_ADAPTER, adapter);
            tag.putString(KEY_ACTOR_TYPE, actorType);
            tag.putString(KEY_ORIGIN, origin.id());
            tag.putString(KEY_STATE, state.id());
            tag.putInt(KEY_GENERATION, generation);
            tag.putInt(KEY_APPEARANCE, appearanceRevision);
            if (!civilianProfession.isEmpty()) {
                tag.putString(KEY_CIVILIAN_PROFESSION, civilianProfession);
            }
            if (!civilianState.isEmpty()) {
                tag.put(KEY_CIVILIAN_STATE, civilianState.copy());
            }
            if (migratedFrom > 0) {
                tag.putInt(KEY_MIGRATED_FROM, migratedFrom);
            }
            if (!lastDimension.isEmpty()) {
                tag.putString(KEY_LAST_DIMENSION, lastDimension);
                tag.putLong(KEY_LAST_POS, lastPos);
            }
            if (lastTransition != null) {
                tag.putUUID(KEY_LAST_TRANSITION, lastTransition);
            }
            if (!journal.isEmpty()) {
                ListTag list = new ListTag();
                for (Transition transition : journal) {
                    list.add(transition.save());
                }
                tag.put(KEY_JOURNAL, list);
            }
            return tag;
        }

        /** Keeps every open transition and the newest settled ones, up to {@link #JOURNAL_LIMIT}. */
        private void trimJournal() {
            while (journal.size() > JOURNAL_LIMIT) {
                boolean removed = false;
                for (Iterator<Transition> it = journal.iterator(); it.hasNext(); ) {
                    if (it.next().stage.isTerminal()) {
                        it.remove();
                        removed = true;
                        break;
                    }
                }
                if (!removed) {
                    return;
                }
            }
        }

        Transition transition(UUID id) {
            if (id == null) {
                return null;
            }
            for (Transition transition : journal) {
                if (transition.id.equals(id)) {
                    return transition;
                }
            }
            return null;
        }

        /** The actor's UUID: the record's key, and the "guard" UUID of 0.2. */
        public UUID uuid() {
            return uuid;
        }

        /** The canonical character UUID: the identity's once there is one, the actor's before that. */
        public UUID characterUuid() {
            return proxyUuid != null ? proxyUuid : uuid;
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

        /**
         * This player's stored balance, zero if they have never met: the last value read from MCA
         * once the player is migrated, the pre-proxy base before that. Pending deltas are separate.
         */
        public int hearts(UUID player) {
            Integer value = player == null ? null : hearts.get(player);
            return value == null ? 0 : value;
        }

        /** Every player this character has a stored balance with. */
        public Collection<UUID> knownPlayers() {
            return new ArrayList<>(hearts.keySet());
        }

        /** The MCA identity villager's UUID, or null while the character has none. */
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

        /** Hearts banked for a player until MCA takes them, saturated to an int. */
        public int pendingHearts(UUID player) {
            return bounded(pendingDelta(player));
        }

        /** The exact banked delta. A long: a delta can span the entire signed-int balance range. */
        public long pendingDelta(UUID player) {
            Long value = player == null ? null : pendingHearts.get(player);
            return value == null ? 0L : value;
        }

        /** Every player with banked hearts waiting for MCA. */
        public Set<UUID> pendingHeartPlayers() {
            return Collections.unmodifiableSet(new HashSet<>(pendingHearts.keySet()));
        }

        /** The adapter id that owns this record's actor; empty until the actor is first seen. */
        public String adapter() {
            return adapter;
        }

        /** The actor's entity type id; empty until the actor is first seen. */
        public String actorType() {
            return actorType;
        }

        public CharacterOrigin origin() {
            return origin;
        }

        public CharacterState state() {
            return state;
        }

        /** The association generation: bumped whenever the actor is replaced. Starts at one. */
        public int generation() {
            return generation;
        }

        public int appearanceRevision() {
            return appearanceRevision;
        }

        /** An enlisted person's civilian profession id, to restore on return; empty otherwise. */
        public String civilianProfession() {
            return civilianProfession;
        }

        /** The flags proxy mode changed on an enlisted person, as they were before. A copy. */
        public CompoundTag civilianState() {
            return civilianState.copy();
        }

        /** The schema this record was migrated from; zero for a record written by 0.3.0 or later. */
        public int migratedFrom() {
            return migratedFrom;
        }

        public String lastDimension() {
            return lastDimension;
        }

        public long lastPos() {
            return lastPos;
        }

        /** The last committed transition's id, or null. */
        public UUID lastTransition() {
            return lastTransition;
        }

        /** The journal, oldest first. */
        public List<Transition> journal() {
            return List.copyOf(journal);
        }

        /** The transition that has not settled yet, or null. There is at most one. */
        public Transition openTransition() {
            for (Transition transition : journal) {
                if (!transition.stage.isTerminal()) {
                    return transition;
                }
            }
            return null;
        }

        int addHearts(UUID player, int delta) {
            int updated = bounded((long) hearts(player) + delta);
            hearts.put(player, updated);
            return updated;
        }

        void rename(String newName) {
            this.name = newName;
        }
    }
}
