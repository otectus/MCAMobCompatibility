package dev.otectus.mcamobcompat.network;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.bridge.GuardProxy;
import dev.otectus.mcamobcompat.integration.ModIntegrations;
import dev.otectus.mcamobcompat.mca.McaEntities;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Minimal required protocol: exact identities and server-owned presentation/interaction policy. */
@EventBusSubscriber(modid = McaMobCompat.MOD_ID)
public final class ProxyPairs {
    private static final Map<Level, LinkedHashMap<UUID, Pair>> CLIENT = new WeakHashMap<>();
    private ProxyPairs() {}

    public record Pair(UUID proxy, int proxyId, UUID guard, int guardId, boolean enabled, boolean remove)
            implements CustomPacketPayload {
        public static final Type<Pair> TYPE = new Type<>(McaMobCompat.id("proxy_pair"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Pair> CODEC = StreamCodec.of(
                (buf, p) -> { buf.writeUUID(p.proxy); buf.writeVarInt(p.proxyId); buf.writeUUID(p.guard);
                    buf.writeVarInt(p.guardId); buf.writeBoolean(p.enabled); buf.writeBoolean(p.remove); },
                buf -> new Pair(buf.readUUID(), buf.readVarInt(), buf.readUUID(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean()));
        @Override public Type<Pair> type() { return TYPE; }
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        // Required, versioned negotiation rejects absent/incompatible installations at login.
        event.registrar("1").playToClient(Pair.TYPE, Pair.CODEC, (pair, context) -> {
            Level level = context.player().level();
            if (!level.isClientSide) return;
            var pairs = CLIENT.computeIfAbsent(level, ignored -> new LinkedHashMap<>());
            if (pair.remove) { pairs.remove(pair.proxy); return; }
            pairs.put(pair.proxy, pair);
            while (pairs.size() > 4096) pairs.remove(pairs.keySet().iterator().next());
            Entity proxy = level.getEntity(pair.proxyId);
            if (proxy instanceof Villager villager && proxy.getUUID().equals(pair.proxy) && McaEntities.isMcaVillager(proxy))
                GuardProxy.hide(villager);
        });
    }

    public static void clearClient() { CLIENT.clear(); }
    public static boolean isProxy(Entity entity) {
        return entity != null && McaEntities.isMcaVillager(entity) && pair(entity) != null;
    }
    private static Pair pair(Entity proxy) {
        var pairs = CLIENT.get(proxy.level());
        Pair p = pairs == null ? null : pairs.get(proxy.getUUID());
        return p != null && p.proxyId == proxy.getId() ? p : null;
    }
    public static Mob guardOf(Entity proxy) {
        Pair p = pair(proxy);
        if (p == null) return null;
        Entity entity = proxy.level().getEntity(p.guardId);
        return entity instanceof Mob mob && mob.getUUID().equals(p.guard) && !McaEntities.isMcaVillager(mob) ? mob : null;
    }
    public static boolean routesGuard(Entity guard) {
        var pairs = CLIENT.get(guard.level());
        return pairs != null && pairs.values().stream().anyMatch(p -> p.enabled && p.guard.equals(guard.getUUID()) && p.guardId == guard.getId());
    }
    public static void broadcast(Mob guard, Villager proxy) {
        if (guard.level().isClientSide || guard.level() != proxy.level()) return;
        PacketDistributor.sendToPlayersTrackingEntity(guard, packet(guard, proxy, false));
        PacketDistributor.sendToPlayersTrackingEntity(proxy, packet(guard, proxy, false));
    }
    private static Pair packet(Mob guard, Villager proxy, boolean remove) {
        return new Pair(proxy.getUUID(), proxy.getId(), guard.getUUID(), guard.getId(),
                ModIntegrations.guardVillagers().opensMcaInteraction() && ModIntegrations.guardVillagers().bridgesGuards(), remove);
    }
    @SubscribeEvent
    public static void startTracking(PlayerEvent.StartTracking event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !(event.getTarget() instanceof Mob target)) return;
        Mob guard = GuardProxy.isProxy(target) ? GuardProxy.guardOf(target) : target;
        Villager proxy = guard == null ? null : GuardProxy.proxyOf(guard);
        if (proxy != null && proxy.level() == guard.level()) PacketDistributor.sendToPlayer(player, packet(guard, proxy, false));
    }
    @SubscribeEvent
    public static void stopTracking(PlayerEvent.StopTracking event) {
        if (!GuardProxy.isProxy(event.getTarget())) return;
        if (!(event.getEntity() instanceof ServerPlayer player) || !(event.getTarget() instanceof Mob target)) return;
        Mob guard = GuardProxy.isProxy(target) ? GuardProxy.guardOf(target) : target;
        Villager proxy = guard == null ? null : GuardProxy.proxyOf(guard);
        if (proxy != null) PacketDistributor.sendToPlayer(player, packet(guard, proxy, true));
    }
}
