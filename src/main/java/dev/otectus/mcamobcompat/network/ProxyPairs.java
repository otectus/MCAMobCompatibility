package dev.otectus.mcamobcompat.network;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.bridge.GuardBridge;
import dev.otectus.mcamobcompat.bridge.GuardProxy;
import dev.otectus.mcamobcompat.integration.ModIntegrations;
import dev.otectus.mcamobcompat.mca.McaEntities;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * Tells each client exactly which MCA villagers are hidden proxies, and which guard each stands for.
 *
 * <p>The client cannot see the persistent data that makes a proxy a proxy. Before this existed it
 * guessed, from four synced flags and from which bridged guard was nearest -- which hid a real
 * villager that an operator had made invisible, and drew the wrong guard in a portrait whenever two
 * guards stood close together. The server now says so outright: one message per pair, carrying both
 * UUIDs and both runtime entity ids, and the client matches on both, so a reused entity id or a
 * stale entry can never select the wrong entity.
 *
 * <p>Sent when a player starts tracking either half of a pair, when a pair is created or re-linked,
 * and on the pair's one-second heartbeat (which also carries new runtime ids after a dimension
 * change). A removal is sent when the player stops tracking the proxy or the proxy is discarded.
 * The client keeps a small bounded cache per level and drops it on logout.
 *
 * <p>The channel is versioned and required on both sides, as the mod already is. Nothing here names
 * a client-only class: the receiving side is {@code client.ClientProxyPairs}, reached only through
 * {@link DistExecutor}.
 */
@Mod.EventBusSubscriber(modid = McaMobCompat.MOD_ID)
public final class ProxyPairs {

    /** Bumped whenever the message shape changes; mismatched sides refuse to connect. */
    public static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            McaMobCompat.id("proxy_pair"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    /** Upper bound on the client cache per level, so a pathological server cannot grow it forever. */
    private static final int CLIENT_CACHE_LIMIT = 4096;

    /** Client side: proxy UUID to its pair, per client level. */
    private static final Map<Level, LinkedHashMap<UUID, Pair>> CLIENT = new WeakHashMap<>();

    private ProxyPairs() {
    }

    /** One pair, as the server describes it. {@code remove} retracts an earlier message. */
    public record Pair(UUID proxy, int proxyId, UUID guard, int guardId, boolean enabled, boolean remove) {

        public void encode(FriendlyByteBuf buffer) {
            buffer.writeUUID(proxy);
            buffer.writeVarInt(proxyId);
            buffer.writeUUID(guard);
            buffer.writeVarInt(guardId);
            buffer.writeBoolean(enabled);
            buffer.writeBoolean(remove);
        }

        public static Pair decode(FriendlyByteBuf buffer) {
            return new Pair(buffer.readUUID(), buffer.readVarInt(), buffer.readUUID(), buffer.readVarInt(),
                    buffer.readBoolean(), buffer.readBoolean());
        }
    }

    /** Registers the message. Called once from the mod constructor. */
    public static void register() {
        CHANNEL.messageBuilder(Pair.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Pair::encode)
                .decoder(Pair::decode)
                .consumerMainThread(ProxyPairs::receive)
                .add();
    }

    private static void receive(Pair pair, Supplier<NetworkEvent.Context> context) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                dev.otectus.mcamobcompat.client.ClientProxyPairs.accept(pair));
    }

    // ---------------------------------------------------------------- client-side cache (common types)

    /** Stores or retracts a pair for this client level. Called by the client receiver only. */
    public static void store(Level level, Pair pair) {
        if (level == null || pair == null) {
            return;
        }
        LinkedHashMap<UUID, Pair> pairs = CLIENT.computeIfAbsent(level, ignored -> new LinkedHashMap<>());
        if (pair.remove()) {
            Pair existing = pairs.get(pair.proxy());
            if (existing != null && existing.proxyId() == pair.proxyId()) {
                pairs.remove(pair.proxy());
            }
            return;
        }
        pairs.remove(pair.proxy());
        pairs.put(pair.proxy(), pair);
        while (pairs.size() > CLIENT_CACHE_LIMIT) {
            pairs.remove(pairs.keySet().iterator().next());
        }
    }

    /** Drops every client pair. Called on logout. */
    public static void clearClient() {
        CLIENT.clear();
    }

    private static Pair clientPair(Entity proxy) {
        Map<UUID, Pair> pairs = CLIENT.get(proxy.level());
        Pair pair = pairs == null ? null : pairs.get(proxy.getUUID());
        return pair != null && pair.proxyId() == proxy.getId() ? pair : null;
    }

    /** Client side: whether the server has paired this exact MCA villager as a proxy. */
    public static boolean isClientProxy(Entity entity) {
        return entity != null && McaEntities.isMcaVillager(entity) && clientPair(entity) != null;
    }

    /** Client side: the guard the server paired with this proxy, matched by UUID and runtime id. */
    public static Mob clientGuardOf(Entity proxy) {
        Pair pair = proxy == null ? null : clientPair(proxy);
        if (pair == null) {
            return null;
        }
        Entity guard = proxy.level().getEntity(pair.guardId());
        return guard instanceof Mob mob && mob.getUUID().equals(pair.guard()) && !McaEntities.isMcaVillager(mob)
                ? mob : null;
    }

    /** Client side: whether the server says a plain click on this guard is routed to its proxy. */
    public static boolean clientRoutesGuard(Entity guard) {
        Map<UUID, Pair> pairs = guard == null ? null : CLIENT.get(guard.level());
        if (pairs == null) {
            return false;
        }
        for (Pair pair : pairs.values()) {
            if (pair.enabled() && pair.guardId() == guard.getId() && pair.guard().equals(guard.getUUID())) {
                return true;
            }
        }
        return false;
    }

    /** Client side, for diagnostics: how many pairs the client knows in this level. */
    public static int clientPairCount(Level level) {
        Map<UUID, Pair> pairs = CLIENT.get(level);
        return pairs == null ? 0 : pairs.size();
    }

    // ---------------------------------------------------------------- server side

    private static Pair describe(Mob guard, Villager proxy, boolean remove) {
        boolean enabled = GuardBridge.isBridgedGuard(guard) && ModIntegrations.guardVillagers().opensMcaInteraction();
        return new Pair(proxy.getUUID(), proxy.getId(), guard.getUUID(), guard.getId(), enabled, remove);
    }

    /** Sends this pair to every player tracking either half of it. Server side; no-op otherwise. */
    public static void broadcast(Mob guard, Villager proxy) {
        if (guard == null || proxy == null || guard.level().isClientSide || guard.level() != proxy.level()) {
            return;
        }
        Pair pair = describe(guard, proxy, false);
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> guard), pair);
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> proxy), pair);
    }

    /** Retracts this pair from every player tracking the proxy. */
    public static void broadcastRemoval(Mob guard, Villager proxy) {
        if (guard == null || proxy == null || proxy.level().isClientSide) {
            return;
        }
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> proxy), describe(guard, proxy, true));
    }

    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !(event.getTarget() instanceof Mob target)) {
            return;
        }
        Mob guard;
        Villager proxy;
        if (GuardProxy.isProxy(target)) {
            proxy = (Villager) target;
            guard = GuardProxy.guardOf(target);
        } else {
            guard = target;
            proxy = GuardBridge.isBridgedGuard(target) ? GuardProxy.proxyOf(target) : null;
        }
        if (guard != null && proxy != null && guard.level() == proxy.level()) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), describe(guard, proxy, false));
        }
    }

    @SubscribeEvent
    public static void onStopTracking(PlayerEvent.StopTracking event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !(event.getTarget() instanceof Villager target)
                || !GuardProxy.isProxy(target)) {
            return;
        }
        Mob guard = GuardProxy.guardOf(target);
        if (guard != null) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), describe(guard, target, true));
        }
    }
}
