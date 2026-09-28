package dev.otectus.mcamobcompat.event;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.bridge.GuardProxySync;
import dev.otectus.mcamobcompat.classification.ClassificationCache;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Drops every piece of per-server transient state when a server stops, and reports once at start
 * which MCA: Crime effects the guard bridge will mirror.
 *
 * <p>An integrated server stops every time a player leaves a single-player world and starts again
 * for the next one in the same JVM; state keyed by entity or player UUID, or holding live entities,
 * must not survive into a different world.
 */
@EventBusSubscriber(modid = McaMobCompat.MOD_ID)
public final class ServerLifecycleHandler {

    private ServerLifecycleHandler() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        GuardProxySync.logMirroredEffects();
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
