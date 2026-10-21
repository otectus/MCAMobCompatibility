package dev.otectus.mcamobcompat.bridge;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.api.GuardGender;
import dev.otectus.mcamobcompat.api.GuardIdentity;
import dev.otectus.mcamobcompat.api.event.GuardIdentityCreatedEvent;
import dev.otectus.mcamobcompat.character.NativeCharacterAdapter;
import dev.otectus.mcamobcompat.character.NativeCharacterAdapters;
import dev.otectus.mcamobcompat.integration.ModIntegrations;
import dev.otectus.mcamobcompat.integration.guardvillagers.GuardVillagersAdapter;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The server-side door to {@link GuardIdentityData}: everything that needs a live mob or a server.
 *
 * <p>The store hangs off the <b>overworld</b>'s data storage, once per world rather than once per
 * dimension, because an actor's UUID does not change when it walks through a portal and its record
 * must not either.
 *
 * <p>Two faces. {@link #find} and {@link #getOrCreate} are the 0.2 guard API and answer for bridged
 * guards only; {@link #record} and {@link #ensureRecord} serve every adapter's actors. Every method is
 * empty-or-no-op on the client and for an entity that is not bridged, so callers do not have to
 * remember which side they are on.
 */
public final class GuardIdentityStore {

    private GuardIdentityStore() {
    }

    /** The world's identity store, created on first use. */
    public static GuardIdentityData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(GuardIdentityData::new, (tag, registries) -> GuardIdentityData.load(tag)),
                GuardIdentityData.STORAGE_KEY);
    }

    /**
     * This guard's existing identity, or empty.
     *
     * <p><b>Never creates.</b> Diagnostics and read-only questions go through here, so that merely
     * looking at a guard cannot write to the save file.
     */
    public static Optional<GuardIdentity> find(Mob guard) {
        MinecraftServer server = serverFor(guard);
        if (server == null || !GuardBridge.isBridgedGuard(guard)) {
            return Optional.empty();
        }
        GuardIdentityData data = get(server);
        GuardIdentityData.Entry entry = data.find(guard.getUUID());
        return entry == null
                ? Optional.empty()
                : Optional.of(new GuardIdentityView(server, data, entry));
    }

    /**
     * This guard's identity, creating it if it has none. The 0.2 guard API: see {@link #ensureRecord},
     * which does the work for every adapter.
     */
    public static Optional<GuardIdentity> getOrCreate(Mob guard) {
        MinecraftServer server = serverFor(guard);
        if (server == null || !GuardBridge.isBridgedGuard(guard)) {
            return Optional.empty();
        }
        GuardIdentityData.Entry entry = ensureRecord(guard);
        return entry == null ? Optional.empty() : Optional.of(new GuardIdentityView(server, get(server), entry));
    }

    /** This actor's record, whatever adapter it belongs to, or null. Never creates. */
    public static GuardIdentityData.Entry record(Mob actor) {
        MinecraftServer server = serverFor(actor);
        return server == null ? null : get(server).find(actor.getUUID());
    }

    /**
     * This actor's record, creating it if it has none. Idempotent, and the only place a record for
     * an adopted actor is born.
     *
     * <p>A guard's record rolls name, gender and personality from its UUID (see {@link GuardNames}) and
     * persists them, exactly as in 0.2, so a guard keeps the name it already had; those rolls seed the
     * hidden MCA villager once, and MCA owns the live values after that. A recruit or a worker keeps a
     * real custom name if it has one; otherwise MCA names its new person when the person is created,
     * through MCA's own initialisation, once.
     *
     * <p>A guard that already carries a custom name keeps it -- a player's name tag outranks our
     * roll -- and a name changed since last time is adopted on the next pass.
     */
    public static GuardIdentityData.Entry ensureRecord(Mob actor) {
        MinecraftServer server = serverFor(actor);
        NativeCharacterAdapter adapter = NativeCharacterAdapters.bridging(actor);
        if (server == null || adapter == null) {
            return null;
        }
        if (actor.getPersistentData().getBoolean(dev.otectus.mcamobcompat.character.EnlistContext.KEY_ENLISTING)) {
            // An enlistment is creating this actor; its record is the enlisted person's, not a new one.
            return null;
        }
        GuardIdentityData data = get(server);
        UUID uuid = actor.getUUID();
        // A newer schema is preserved untouched, and a quarantined record is never replaced by a
        // fresh roll: both are for an operator to look at, not for this mod to paper over.
        if (data.isReadOnly() || data.isQuarantined(uuid)) {
            return null;
        }
        boolean guard = adapter == GuardVillagersAdapter.INSTANCE;
        String type = EntityType.getKey(actor.getType()).toString();
        GuardIdentityData.Entry existing = data.find(uuid);
        if (existing != null) {
            Optional<String> custom = guard
                    ? (actor.hasCustomName() ? Optional.of(actor.getCustomName().getString()) : Optional.empty())
                    : adapter.meaningfulName(actor);
            custom.filter(name -> !name.isBlank() && !name.equals(existing.name()))
                    .ifPresent(name -> data.rename(existing, name));
            data.assignActor(existing, adapter.id().toString(), type);
            if (guard) {
                showNameTag(actor);
            }
            return existing;
        }

        GuardGender gender = GuardNames.gender(uuid);
        String name;
        String personality;
        if (guard) {
            name = actor.hasCustomName() && !actor.getCustomName().getString().isBlank()
                    ? actor.getCustomName().getString()
                    : GuardNames.name(uuid, gender);
            personality = GuardNames.personality(uuid);
        } else {
            name = adapter.meaningfulName(actor).orElse("");
            personality = "";
        }
        GuardIdentityData.Entry entry = data.create(uuid, name, gender, personality, actor.level().getGameTime());
        data.assignActor(entry, adapter.id().toString(), type);

        if (guard) {
            if (!actor.hasCustomName()) {
                actor.setCustomName(Component.literal(name));
            }
            showNameTag(actor);
            GuardIdentity identity = new GuardIdentityView(server, data, entry);
            McaMobCompat.debug("Gave guard {} the identity {} ({}, {})", uuid, name, gender.id(), entry.personality());
            McaMobCompat.post(new GuardIdentityCreatedEvent(actor, identity));
        } else {
            McaMobCompat.debug("Recorded {} {} for an MCA character", type, uuid);
        }
        return entry;
    }

    /**
     * Makes the guard's name visible without a name tag, the way MCA shows a villager's.
     *
     * <p>Only ever turns visibility on. Clearing it would take away a nameplate a player set with a
     * name tag, and the option is documented as affecting new guards only for that reason.
     */
    private static void showNameTag(Mob guard) {
        if (!guard.isCustomNameVisible() && ModIntegrations.guardVillagers().showsNameTag()) {
            guard.setCustomNameVisible(true);
        }
    }

    /**
     * Points an actor's record at its identity villager, or clears the link when {@code proxy} is
     * null. No-op for an actor with no record, for a read-only store, and when another record already
     * owns that identity -- the link is one-to-one, and a conflict is reported rather than resolved.
     *
     * @return whether the store now records exactly this link
     */
    public static boolean link(MinecraftServer server, UUID guard, UUID proxy) {
        if (server == null || guard == null) {
            return false;
        }
        GuardIdentityData data = get(server);
        GuardIdentityData.Entry entry = data.find(guard);
        if (entry == null || data.isReadOnly()) {
            return false;
        }
        try {
            data.linkProxy(entry, proxy);
            return true;
        } catch (IllegalStateException conflict) {
            McaMobCompat.LOGGER.warn("Not linking actor {} to identity {}: {}", guard, proxy, conflict.getMessage());
            return false;
        }
    }

    /** Forgets an actor's identity. Called when the identity is discarded or the actor dies. */
    public static void unlink(MinecraftServer server, UUID guard) {
        link(server, guard, null);
    }

    /** The identity villager's UUID for this actor, or empty while it has none. */
    public static Optional<UUID> proxyUuid(MinecraftServer server, UUID guard) {
        if (server == null || guard == null) {
            return Optional.empty();
        }
        GuardIdentityData.Entry entry = get(server).find(guard);
        return entry == null ? Optional.empty() : Optional.ofNullable(entry.proxyUuid());
    }

    /** The actor this identity belongs to, or empty when no record claims it. */
    public static Optional<UUID> findByProxy(MinecraftServer server, UUID proxy) {
        if (server == null || proxy == null) {
            return Optional.empty();
        }
        GuardIdentityData.Entry entry = get(server).findByProxy(proxy);
        return entry == null ? Optional.empty() : Optional.of(entry.uuid());
    }

    /** The loaded mob with this UUID, in whichever level holds it, or null if none does. */
    public static Mob resolveLoaded(MinecraftServer server, UUID uuid) {
        if (server == null || uuid == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity found = level.getEntity(uuid);
            if (found instanceof Mob mob) {
                return mob;
            }
        }
        return null;
    }

    /**
     * The pair heartbeat for this actor's record: flushes queued hearts into MCA and records the
     * name, personality and balances MCA reports. No-op without a record or a loaded identity.
     */
    public static void synchronize(Mob guard) {
        MinecraftServer server = serverFor(guard);
        if (server == null) {
            return;
        }
        GuardIdentityData data = get(server);
        GuardIdentityData.Entry entry = data.find(guard.getUUID());
        if (entry != null) {
            new GuardIdentityView(server, data, entry).synchronize();
        }
    }

    /** The server this entity belongs to, or null when it is client-side or not in a level yet. */
    private static MinecraftServer serverFor(Entity entity) {
        if (entity == null || entity.level().isClientSide) {
            return null;
        }
        return entity.getServer();
    }
}
