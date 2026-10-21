package dev.otectus.mcamobcompat.network;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.bridge.GuardIdentityData;
import dev.otectus.mcamobcompat.bridge.GuardIdentityStore;
import dev.otectus.mcamobcompat.bridge.GuardProxy;
import dev.otectus.mcamobcompat.character.AppearanceMode;
import dev.otectus.mcamobcompat.character.Capability;
import dev.otectus.mcamobcompat.character.CharacterBridge;
import dev.otectus.mcamobcompat.character.NativeCharacterAdapter;
import dev.otectus.mcamobcompat.character.NativeCharacterAdapters;
import dev.otectus.mcamobcompat.mca.McaEntities;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * Tells each client exactly which MCA villagers are hidden identities, which native actor each stands
 * behind, and how that actor is to be drawn and clicked.
 *
 * <p>The client cannot see the persistent data that makes an identity one. Before 0.2.1 it guessed,
 * from synced flags and from which bridged guard was nearest -- which hid a real villager an operator
 * had made invisible, and drew the wrong guard whenever two stood close together. The server says so
 * outright instead: one association per character, carrying both UUIDs and both runtime entity ids,
 * matched on both, so a reused entity id or a stale entry can never select the wrong entity.
 *
 * <p><b>Protocol 2</b> (0.3.0) carries the whole association a client needs and nothing private: the
 * character UUID, the actor's and the identity's UUIDs and runtime ids, the dimension, the association
 * generation, the public appearance revision, the adapter's capabilities, and whether a plain click
 * routes to MCA and whether the actor is drawn as its MCA person. No family tree, hearts, contract or
 * native NBT is ever sent. The generation orders messages: an association from an older generation
 * never replaces a newer one, so a promotion's messages arriving out of order cannot pair a client with
 * the old actor again. Protocol 1 clients are refused at login, as the channel is required on both
 * sides; a client without the mod sees no floating proxies because it cannot join at all.
 *
 * <p>Sent when a player starts tracking either half, when a pair is created, committed or replaced,
 * after an editor save, and on the pair's heartbeat whenever anything in it changed (new runtime ids
 * after a dimension change included). A removal is sent when the player stops tracking the identity,
 * when the identity is discarded and when the character goes back to civilian life. The client keeps a
 * small bounded cache per level and drops it on logout. The receiving side is
 * {@code client.ClientProxyPairs}, reached only through {@link DistExecutor}.
 */
@Mod.EventBusSubscriber(modid = McaMobCompat.MOD_ID)
public final class ProxyPairs {

    /** Bumped whenever the message shape changes; mismatched sides refuse to connect. */
    public static final String PROTOCOL = "2";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            McaMobCompat.id("proxy_pair"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    /** Upper bound on the client cache per level, so a pathological server cannot grow it forever. */
    private static final int CLIENT_CACHE_LIMIT = 4096;

    /** Client side: identity UUID to its association, per client level. */
    private static final Map<Level, LinkedHashMap<UUID, Pair>> CLIENT = new WeakHashMap<>();

    /** Client side: actor UUID to identity UUID, per client level, for the renderer's hot path. */
    private static final Map<Level, Map<UUID, UUID>> CLIENT_ACTORS = new WeakHashMap<>();

    private ProxyPairs() {
    }

    /**
     * One association, as the server describes it. {@code remove} retracts an earlier one. Named
     * {@code Pair} since protocol 1; {@code guard} is the actor, of whatever adapter.
     */
    public record Pair(UUID character, UUID proxy, int proxyId, UUID guard, int guardId, ResourceLocation dimension,
                       int generation, int appearanceRevision, int capabilities, boolean enabled,
                       boolean mcaAppearance, boolean remove) {

        public void encode(FriendlyByteBuf buffer) {
            buffer.writeUUID(character);
            buffer.writeUUID(proxy);
            buffer.writeVarInt(proxyId);
            buffer.writeUUID(guard);
            buffer.writeVarInt(guardId);
            buffer.writeResourceLocation(dimension);
            buffer.writeVarInt(generation);
            buffer.writeVarInt(appearanceRevision);
            buffer.writeVarInt(capabilities);
            buffer.writeBoolean(enabled);
            buffer.writeBoolean(mcaAppearance);
            buffer.writeBoolean(remove);
        }

        public static Pair decode(FriendlyByteBuf buffer) {
            return new Pair(buffer.readUUID(), buffer.readUUID(), buffer.readVarInt(), buffer.readUUID(),
                    buffer.readVarInt(), buffer.readResourceLocation(), buffer.readVarInt(), buffer.readVarInt(),
                    buffer.readVarInt(), buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean());
        }

        /** The capabilities the server granted, as a set. */
        public Set<Capability> capabilitySet() {
            return Capability.fromMask(capabilities);
        }

        /**
         * Whether an incoming association may replace the one held for the same identity: never one
         * from an older generation (a promotion's messages may arrive out of order), and a removal only
         * of the exact runtime ids it names. On the record rather than the channel class, so it can be
         * pinned by {@code ProxyPairCodecTest} without a network stack.
         */
        static boolean supersedes(Pair existing, Pair incoming) {
            if (existing == null) {
                return !incoming.remove();
            }
            if (incoming.generation() < existing.generation()) {
                return false;
            }
            return !incoming.remove()
                    || (existing.proxyId() == incoming.proxyId() && existing.guardId() == incoming.guardId());
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

    /**
     * Stores or retracts an association for this client level. An association from an older generation
     * than the one held for the same identity is ignored; a removal applies only to the exact runtime
     * ids it names. Called by the client receiver only.
     */
    public static void store(Level level, Pair pair) {
        if (level == null || pair == null || !level.dimension().location().equals(pair.dimension())) {
            return;
        }
        LinkedHashMap<UUID, Pair> pairs = CLIENT.computeIfAbsent(level, ignored -> new LinkedHashMap<>());
        Map<UUID, UUID> actors = CLIENT_ACTORS.computeIfAbsent(level, ignored -> new LinkedHashMap<>());
        Pair existing = pairs.get(pair.proxy());
        if (!Pair.supersedes(existing, pair)) {
            return;
        }
        if (pair.remove()) {
            if (existing != null) {
                pairs.remove(pair.proxy());
                actors.remove(existing.guard(), existing.proxy());
            }
            return;
        }
        if (existing != null) {
            pairs.remove(pair.proxy());
            actors.remove(existing.guard(), existing.proxy());
        }
        pairs.put(pair.proxy(), pair);
        actors.put(pair.guard(), pair.proxy());
        while (pairs.size() > CLIENT_CACHE_LIMIT) {
            UUID eldest = pairs.keySet().iterator().next();
            Pair dropped = pairs.remove(eldest);
            if (dropped != null) {
                actors.remove(dropped.guard(), dropped.proxy());
            }
        }
    }

    /** Drops every client association. Called on logout. */
    public static void clearClient() {
        CLIENT.clear();
        CLIENT_ACTORS.clear();
    }

    /** Drops one client level's associations. Called when the client unloads that level. */
    public static void clearClient(Level level) {
        CLIENT.remove(level);
        CLIENT_ACTORS.remove(level);
    }

    private static Pair clientPair(Entity proxy) {
        Map<UUID, Pair> pairs = CLIENT.get(proxy.level());
        Pair pair = pairs == null ? null : pairs.get(proxy.getUUID());
        return pair != null && pair.proxyId() == proxy.getId() ? pair : null;
    }

    /** Client side: whether the server has paired this exact MCA villager as an identity. */
    public static boolean isClientProxy(Entity entity) {
        return entity != null && McaEntities.isMcaVillager(entity) && clientPair(entity) != null;
    }

    /** Client side: the actor the server paired with this identity, matched by UUID and runtime id. */
    public static Mob clientGuardOf(Entity proxy) {
        Pair pair = proxy == null ? null : clientPair(proxy);
        if (pair == null) {
            return null;
        }
        Entity guard = proxy.level().getEntity(pair.guardId());
        return guard instanceof Mob mob && mob.getUUID().equals(pair.guard()) && !McaEntities.isMcaVillager(mob)
                ? mob : null;
    }

    /** Client side: the association naming this exact actor, by UUID and runtime id, or null. */
    public static Pair clientPairOfActor(Entity actor) {
        if (actor == null) {
            return null;
        }
        Map<UUID, UUID> actors = CLIENT_ACTORS.get(actor.level());
        UUID proxy = actors == null ? null : actors.get(actor.getUUID());
        Map<UUID, Pair> pairs = proxy == null ? null : CLIENT.get(actor.level());
        Pair pair = pairs == null ? null : pairs.get(proxy);
        return pair != null && pair.guardId() == actor.getId() ? pair : null;
    }

    /** Client side: the identity villager behind this actor, when the client has it, or null. */
    public static Villager clientIdentityOf(Entity actor) {
        Pair pair = clientPairOfActor(actor);
        if (pair == null) {
            return null;
        }
        Entity identity = actor.level().getEntity(pair.proxyId());
        return identity instanceof Villager villager && villager.getUUID().equals(pair.proxy()) ? villager : null;
    }

    /** Client side: whether the server says a plain click on this actor is routed to its identity. */
    public static boolean clientRoutesGuard(Entity guard) {
        Pair pair = clientPairOfActor(guard);
        return pair != null && pair.enabled();
    }

    /** Client side, for diagnostics: how many associations the client knows in this level. */
    public static int clientPairCount(Level level) {
        Map<UUID, Pair> pairs = CLIENT.get(level);
        return pairs == null ? 0 : pairs.size();
    }

    /** Client side, for diagnostics and the renderer's bookkeeping: every association in this level. */
    public static Map<UUID, Pair> clientPairs(Level level) {
        Map<UUID, Pair> pairs = CLIENT.get(level);
        return pairs == null ? Map.of() : Collections.unmodifiableMap(pairs);
    }

    // ---------------------------------------------------------------- server side

    private static Pair describe(Mob guard, Villager proxy, boolean remove) {
        NativeCharacterAdapter adapter = NativeCharacterAdapters.bridging(guard);
        GuardIdentityData.Entry entry = guard.getServer() == null ? null
                : GuardIdentityStore.get(guard.getServer()).find(guard.getUUID());
        boolean enabled = adapter != null && adapter.opensMcaInteraction();
        Set<Capability> capabilities = adapter == null ? Set.of() : adapter.capabilities(guard);
        boolean appearance = adapter != null && adapter.appearanceMode() == AppearanceMode.MCA
                && capabilities.contains(Capability.MCA_APPEARANCE);
        return new Pair(entry == null ? proxy.getUUID() : entry.characterUuid(), proxy.getUUID(), proxy.getId(),
                guard.getUUID(), guard.getId(), guard.level().dimension().location(),
                entry == null ? 1 : entry.generation(), entry == null ? 0 : entry.appearanceRevision(),
                Capability.mask(capabilities), enabled, appearance, remove);
    }

    /** Everything clients cache about this pair, as one string, so the heartbeat re-sends only on change. */
    public static String fingerprint(Mob guard, Villager proxy) {
        Pair pair = describe(guard, proxy, false);
        return pair.proxyId() + "/" + pair.guardId() + "/" + pair.generation() + "/" + pair.appearanceRevision()
                + "/" + pair.capabilities() + "/" + pair.enabled() + "/" + pair.mcaAppearance() + "/" + pair.dimension();
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

    /** Retracts this pair from every player tracking either half. */
    public static void broadcastRemoval(Mob guard, Villager proxy) {
        if (guard == null || proxy == null || proxy.level().isClientSide) {
            return;
        }
        Pair pair = describe(guard, proxy, true);
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> proxy), pair);
        if (guard.level() == proxy.level()) {
            CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> guard), pair);
        }
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
            proxy = CharacterBridge.isBridgedActor(target) || target.getPersistentData().hasUUID(GuardProxy.KEY_PROXY)
                    ? GuardProxy.proxyOf(target) : null;
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
