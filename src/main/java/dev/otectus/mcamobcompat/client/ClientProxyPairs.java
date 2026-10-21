package dev.otectus.mcamobcompat.client;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.bridge.GuardProxy;
import dev.otectus.mcamobcompat.network.ProxyPairs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * The client end of {@link ProxyPairs}: records each association for the level the player is in,
 * keeps a paired identity hidden and narrow on the client too, and drops what it knows when the level
 * or the connection goes.
 *
 * <p>An association can arrive before either entity's own spawn packet (the player started tracking
 * the actor first), so it is stored by UUID and resolved when used, and hiding is also re-asserted
 * from the identity's client tick rather than only on arrival. Nothing is ever matched by nearness.
 * Physically client-only; reached from common code only on a physical client.
 */
@EventBusSubscriber(modid = McaMobCompat.MOD_ID, value = Dist.CLIENT)
public final class ClientProxyPairs {

    private ClientProxyPairs() {
    }

    /** Stores an association for the current client level and applies it to the identity if present. */
    public static void accept(ProxyPairs.Pair pair) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        ProxyPairs.store(level, pair);
        CharacterRenderViews.invalidate(pair.proxy());
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

    /** Ages the render views once a second, so the cache cannot outgrow what is on screen. */
    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        if (Minecraft.getInstance().level != null) {
            CharacterRenderViews.tick(Minecraft.getInstance().level);
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof Level level && level.isClientSide()) {
            ProxyPairs.clearClient(level);
            CharacterRenderViews.clear();
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ProxyPairs.clearClient();
        CharacterRenderViews.clear();
    }
}
