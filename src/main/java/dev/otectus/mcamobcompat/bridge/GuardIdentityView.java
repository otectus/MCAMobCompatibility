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
import net.minecraftforge.common.MinecraftForge;

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
        Mob proxy = loadedProxy();
        if (proxy != null) {
            OptionalInt live = McaBinding.hearts(proxy, player);
            if (live.isPresent()) {
                return live.getAsInt();
            }
        }
        // Pending hearts are part of the answer: they were earned, they are simply waiting for a
        // moment when both the proxy and the player are loaded.
        return entry.hearts(player) + entry.pendingHearts(player);
    }

    /**
     * Moves a player's hearts, on MCA's side when that is reachable and in our store otherwise.
     *
     * <p>MCA's per-player memories need a live {@code Player}, so an offline player's change is
     * banked in {@code pendingHearts} and folded in the next time they and the proxy are both
     * there. The same pass migrates whatever the store held for that player from before the proxy
     * existed, exactly once -- that is what {@code heartsMigrated} records -- so hearts a player
     * earned under the old emulation are not lost and not counted twice.
     */
    @Override
    public void modHearts(UUID player, int delta) {
        if (player == null || delta == 0) {
            return;
        }
        int before = hearts(player);

        Mob proxy = loadedProxy();
        ServerPlayer target = server == null ? null : server.getPlayerList().getPlayer(player);
        boolean applied = false;
        if (proxy != null && target != null) {
            int migrating = entry.heartsMigrated(player) ? 0 : entry.hearts(player);
            int pending = entry.pendingHearts(player);
            applied = McaBinding.modHearts(proxy, target, delta + migrating + pending);
            if (applied) {
                data.markHeartsMigrated(entry, player);
                data.clearPendingHearts(entry, player);
            }
        }

        // The store's own copy is kept current either way: it is what answers for an unloaded
        // guard, and what a version of this world without MCA's binding would fall back to.
        data.addHearts(entry, player, delta);
        if (!applied) {
            data.addPendingHearts(entry, player, delta);
        }

        // Nullable on purpose: hearts are keyed on a UUID and can move while the guard is in an
        // unloaded chunk or a level nobody is in. Resolving the mob is a convenience for listeners,
        // never a precondition for the change.
        Mob guard = GuardIdentityStore.resolveLoaded(server, entry.uuid());
        MinecraftForge.EVENT_BUS.post(
                new GuardHeartsChangedEvent(this, guard, player, before, hearts(player)));
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
