package dev.otectus.mcamobcompat.bridge;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.api.GuardGender;
import dev.otectus.mcamobcompat.api.GuardIdentity;
import dev.otectus.mcamobcompat.api.event.GuardHeartsChangedEvent;
import dev.otectus.mcamobcompat.mca.McaBinding;
import dev.otectus.mcamobcompat.mca.McaEntities;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * A live view of one stored {@link GuardIdentityData.Entry}, handed out through the public API.
 *
 * <p>A view rather than a snapshot: two siblings holding the same guard's identity must see each
 * other's heart changes, and a copy would let one of them write over the other. All state stays in
 * the store; this only knows how to reach it and how to announce a change.
 *
 * <p><b>Proxy first, store second.</b> Once a guard has its hidden MCA proxy villager, MCA owns the
 * real name, personality and hearts -- a player who gifted the proxy changed MCA's memories, not
 * ours -- so every read prefers the live proxy and falls back to the stored values only when the
 * proxy is unloaded, absent, or the reflective binding is unavailable.
 *
 * <p><b>Hearts are counted exactly once.</b> The store keeps two ledgers per player: the balance
 * (MCA's last reported value once the player is migrated, the pre-proxy base before) and the
 * pending delta that MCA has not taken yet. A change made through {@link #modHearts} is queued as
 * one pending delta and flushed straight away when both the proxy and the player are loaded, or on
 * the pair's one-second heartbeat later. A delta is cleared only after MCA accepted it, so a
 * failed write is retried rather than lost, and never applied twice.
 */
final class GuardIdentityView implements GuardIdentity {

    private final MinecraftServer server;
    private final GuardIdentityData data;
    private final GuardIdentityData.Entry entry;

    GuardIdentityView(MinecraftServer server, GuardIdentityData data, GuardIdentityData.Entry entry) {
        this.server = server;
        this.data = data;
        this.entry = entry;
    }

    @Override
    public UUID uuid() {
        return entry.uuid();
    }

    @Override
    public String name() {
        Mob proxy = loadedProxy();
        if (proxy != null && proxy.hasCustomName()) {
            String live = proxy.getCustomName().getString();
            if (!live.isBlank()) {
                return live;
            }
        }
        return entry.name();
    }

    @Override
    public GuardGender gender() {
        // MCA's genetics carry the person's gender -- MCA's editor can change it on either entity
        // type -- so the genetics win; the entity type answers only when they cannot be read.
        Mob proxy = loadedProxy();
        if (proxy != null) {
            Optional<String> genetic = McaBinding.gender(proxy);
            if (genetic.isPresent() && ("MALE".equals(genetic.get()) || "FEMALE".equals(genetic.get()))) {
                return "FEMALE".equals(genetic.get()) ? GuardGender.FEMALE : GuardGender.MALE;
            }
            if (McaEntities.MALE_VILLAGER.equals(EntityType.getKey(proxy.getType()))) {
                return GuardGender.MALE;
            }
            if (McaEntities.FEMALE_VILLAGER.equals(EntityType.getKey(proxy.getType()))) {
                return GuardGender.FEMALE;
            }
        }
        return entry.gender();
    }

    /**
     * The personality id, lower-case and unnamespaced, as the {@link GuardIdentity} contract says.
     * MCA 7.7 reports {@code mca:confident}; the namespace is dropped for MCA's own personalities
     * and kept for any other, so a custom one is still distinguishable.
     */
    @Override
    public String personality() {
        Mob proxy = loadedProxy();
        if (proxy != null) {
            Optional<String> live = McaBinding.personality(proxy);
            if (live.isPresent()) {
                return plain(live.get());
            }
        }
        return plain(entry.personality());
    }

    private static String plain(String personality) {
        if (personality == null) {
            return "";
        }
        String lower = personality.toLowerCase(Locale.ROOT);
        return lower.startsWith("mca:") ? lower.substring(4) : lower;
    }

    @Override
    public long created() {
        return entry.created();
    }

    @Override
    public Optional<UUID> proxyUuid() {
        return Optional.ofNullable(entry.proxyUuid());
    }

    @Override
    public int hearts(UUID player) {
        if (player == null) {
            return 0;
        }
        Mob proxy = loadedProxy();
        if (proxy != null) {
            OptionalInt live = McaBinding.hearts(proxy, player);
            if (live.isPresent()) {
                int legacy = entry.heartsMigrated(player) ? 0 : entry.hearts(player);
                return GuardIdentityData.bounded((long) live.getAsInt() + legacy + entry.pendingDelta(player));
            }
        }
        return GuardIdentityData.bounded((long) entry.hearts(player) + entry.pendingDelta(player));
    }

    @Override
    public void modHearts(UUID player, int delta) {
        if (player == null || delta == 0 || data.isReadOnly()) {
            return;
        }
        if (server != null && !server.isSameThread()) {
            throw new IllegalStateException("Guard hearts must change on the server thread");
        }
        int before = hearts(player);
        int after = GuardIdentityData.bounded((long) before + delta);
        if (after == before) {
            return;
        }
        data.addPendingHearts(entry, player, GuardIdentityData.bounded((long) after - before));
        flush(player);

        // Nullable on purpose: hearts are keyed on a UUID and can move while the guard is in an
        // unloaded chunk or a level nobody is in. Resolving the mob is a convenience for listeners,
        // never a precondition for the change.
        // Only with a live server: the event is for listeners in a running game, and a store
        // driven without one (a unit test, an offline tool) has nobody to tell.
        if (server != null) {
            Mob guard = GuardIdentityStore.resolveLoaded(server, entry.uuid());
            McaMobCompat.post(new GuardHeartsChangedEvent(this, guard, player, before, hearts(player)));
        }
    }

    /**
     * The heartbeat: hands MCA every queued delta it can take now, and records what MCA reports.
     *
     * <p>Runs once a second for every loaded pair, so a player who reconnects gets their queued
     * hearts applied without having to earn another change first. MCA-originated changes (a gift,
     * a chat) update the snapshot here without a {@link GuardHeartsChangedEvent}: that event is the
     * bridge API's, and MCA announces its own.
     */
    void synchronize() {
        Mob proxy = loadedProxy();
        if (proxy == null || data.isReadOnly()) {
            return;
        }
        Set<UUID> players = new HashSet<>(entry.knownPlayers());
        players.addAll(entry.pendingHeartPlayers());
        players.addAll(McaBinding.heartSnapshot(proxy).keySet());
        for (UUID player : players) {
            flush(player);
        }
        if (proxy.hasCustomName()) {
            data.rename(entry, proxy.getCustomName().getString());
        }
        McaBinding.personality(proxy).ifPresent(value -> data.snapshotPersonality(entry, value));
    }

    /** Applies whatever this player has outstanding, if MCA can take it now. */
    private void flush(UUID player) {
        Mob proxy = loadedProxy();
        if (proxy == null || player == null || data.isReadOnly()) {
            return;
        }
        long legacy = entry.heartsMigrated(player) ? 0L : entry.hearts(player);
        long outstanding = legacy + entry.pendingDelta(player);
        if (outstanding == 0L) {
            // Nothing to hand over: the player is migrated by definition, with no write to MCA.
            data.markHeartsMigrated(entry, player);
        } else {
            ServerPlayer target = server == null ? null : server.getPlayerList().getPlayer(player);
            if (target != null && McaBinding.modHearts(proxy, target, outstanding)) {
                data.markHeartsMigrated(entry, player);
                data.clearPendingHearts(entry, player);
            }
        }
        if (entry.heartsMigrated(player)) {
            McaBinding.hearts(proxy, player).ifPresent(value -> data.snapshotHearts(entry, player, value));
        }
    }

    @Override
    public boolean deceased() {
        return entry.deceased();
    }

    /** The proxy villager, if it is loaded in some level right now. Null otherwise. */
    private Mob loadedProxy() {
        return GuardIdentityStore.resolveLoaded(server, entry.proxyUuid());
    }
}
