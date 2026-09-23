package dev.otectus.mcamobcompat.event;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.bridge.GuardProxySync;
import dev.otectus.mcamobcompat.classification.ClassificationCache;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Drops every piece of per-server transient state when a server stops.
 *
 * <p>An integrated server stops every time a player leaves a single-player world and starts again
 * for the next one in the same JVM; state keyed by entity or player UUID, or holding live entities,
 * must not survive into a different world.
 */
@Mod.EventBusSubscriber(modid = McaMobCompat.MOD_ID)
public final class ServerLifecycleHandler {

    private ServerLifecycleHandler() {
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        GuardDamageRelayHandler.clear();
        GuardProxySync.clear();
        GuardBridgeHandler.clear();
        MountPolicyHandler.clear();
        dev.otectus.mcamobcompat.threat.ThreatHandler.clear();
        ClassificationCache.invalidate();
        ChoreSafetyHandler.invalidateConfig();
    }
}
