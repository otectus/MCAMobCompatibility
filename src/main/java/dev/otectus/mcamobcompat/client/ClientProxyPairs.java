package dev.otectus.mcamobcompat.client;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.bridge.GuardProxy;
import dev.otectus.mcamobcompat.network.ProxyPairs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * The client end of {@link ProxyPairs}: records each pair for the level the player is in, and
 * keeps a paired proxy hidden and narrow on the client too.
 *
 * <p>A pair can arrive before the proxy's own spawn packet (the player started tracking the guard
 * first), so hiding is also re-asserted from the proxy's client tick rather than only on arrival.
 * Physically client-only; reached from common code only on a physical client.
 */
@EventBusSubscriber(modid = McaMobCompat.MOD_ID, value = Dist.CLIENT)
public final class ClientProxyPairs {

    private ClientProxyPairs() {
    }

    /** Stores a pair for the current client level and applies it to the proxy if it is present. */
    public static void accept(ProxyPairs.Pair pair) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        ProxyPairs.store(level, pair);
        Entity proxy = level.getEntity(pair.proxyId());
        if (proxy instanceof Villager villager) {
            if (pair.remove()) {
                villager.refreshDimensions();
            } else if (GuardProxy.isProxy(villager)) {
                GuardProxy.hide(villager);
                villager.refreshDimensions();
            }
        }
    }

    @SubscribeEvent
    public static void onClientTick(EntityTickEvent.Pre event) {
        if (event.getEntity().level().isClientSide && event.getEntity() instanceof Villager villager
                && GuardProxy.isProxy(villager)) {
            GuardProxy.hide(villager);
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ProxyPairs.clearClient();
    }
}
