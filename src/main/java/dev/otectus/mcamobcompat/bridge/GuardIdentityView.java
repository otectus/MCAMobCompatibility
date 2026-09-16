package dev.otectus.mcamobcompat.bridge;

import dev.otectus.mcamobcompat.api.GuardGender;
import dev.otectus.mcamobcompat.api.GuardIdentity;
import dev.otectus.mcamobcompat.api.event.GuardHeartsChangedEvent;
import dev.otectus.mcamobcompat.mca.McaBinding;
import dev.otectus.mcamobcompat.mca.McaEntities;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.common.NeoForge;

import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
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
 * ours -- so every read prefers the live proxy and falls back to the stored roll only when the
 * proxy is unloaded, absent, or the reflective binding is unavailable. The store keeps its own copy
 * up to date regardless, so an identity is never nameless and a sibling addon asking about a guard
 * in an unloaded chunk still gets an answer.
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
        // MCA fixes gender by entity type, which is why the proxy is spawned as one or the other
        // and never has its genetics touched.
        Mob proxy = loadedProxy();
        if (proxy != null) {
            if (McaEntities.MALE_VILLAGER.equals(EntityType.getKey(proxy.getType()))) {
                return GuardGender.MALE;
            }
            if (McaEntities.FEMALE_VILLAGER.equals(EntityType.getKey(proxy.getType()))) {
                return GuardGender.FEMALE;
            }
        }
        return entry.gender();
    }

    @Override
    public String personality() {
        Mob proxy = loadedProxy();
        if (proxy != null) {
            Optional<String> live = McaBinding.personality(proxy);
            if (live.isPresent()) {
                return live.get().toLowerCase(Locale.ROOT);
            }
        }
        return entry.personality();
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
        if (player == null) return 0;
        Mob proxy = loadedProxy();
        if (proxy != null) {
            OptionalInt live = McaBinding.hearts(proxy, player);
            if (live.isPresent()) {
                int legacy = entry.heartsMigrated(player) ? 0 : entry.hearts(player);
                if (entry.heartsMigrated(player)) data.snapshotHearts(entry, player, live.getAsInt());
                return GuardIdentityData.bounded((long) live.getAsInt() + legacy + entry.pendingDelta(player));
            }
        }
        return GuardIdentityData.bounded((long) entry.hearts(player) + entry.pendingDelta(player));
    }

    @Override
    public void modHearts(UUID player, int delta) {
        if (player == null || delta == 0 || data.isReadOnly()) return;
        if (server != null && !server.isSameThread()) throw new IllegalStateException("Hearts must change on the server thread");
        int before = hearts(player);
        int after = GuardIdentityData.bounded((long) before + delta);
        long actual = (long) after - before;
        data.addPendingHearts(entry, player, GuardIdentityData.bounded(actual));
        flush(player);
        Mob guard = GuardIdentityStore.resolveLoaded(server, entry.uuid());
        NeoForge.EVENT_BUS.post(new GuardHeartsChangedEvent(this, guard, player, before, hearts(player)));
    }

    /** Runs on the pair heartbeat, so reconnect does not require another earned delta. */
    void synchronize() {
        Mob proxy = loadedProxy();
        if (proxy == null || data.isReadOnly()) return;
        java.util.Set<UUID> players = new java.util.HashSet<>(entry.knownPlayers());
        players.addAll(entry.pendingHeartPlayers());
        players.addAll(McaBinding.heartSnapshot(proxy).keySet());
        for (UUID player : players) flush(player);
        if (proxy.hasCustomName()) data.rename(entry, proxy.getCustomName().getString());
        McaBinding.personality(proxy).ifPresent(value -> data.snapshotPersonality(entry, value));
    }

    private void flush(UUID player) {
        Mob proxy = loadedProxy();
        ServerPlayer target = server == null ? null : server.getPlayerList().getPlayer(player);
        if (proxy == null) return;
        long outstanding = (long) (entry.heartsMigrated(player) ? 0 : entry.hearts(player)) + entry.pendingDelta(player);
        if (target != null && McaBinding.modHearts(proxy, target, outstanding)) {
            data.markHeartsMigrated(entry, player);
            data.clearPendingHearts(entry, player);
        }
        if (entry.heartsMigrated(player)) McaBinding.hearts(proxy, player).ifPresent(value -> data.snapshotHearts(entry, player, value));
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
