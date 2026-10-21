package dev.otectus.mcamobcompat.event;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.bridge.GuardProxySync;
import dev.otectus.mcamobcompat.character.CharacterTransitions;
import dev.otectus.mcamobcompat.character.McaEditorBridge;
import dev.otectus.mcamobcompat.character.NativeCharacterAdapters;
import dev.otectus.mcamobcompat.classification.ClassificationCache;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Per-server bookkeeping: what to settle when a server starts, at the end of each tick, when a player
 * leaves and when the server stops.
 *
 * <p>An integrated server stops every time a player leaves a single-player world and starts again
 * for the next one in the same JVM; state keyed by entity or player UUID, or holding live entities,
 * must not survive into a different world. A transition left open by an unclean stop is settled at
 * start, before any entity of that world has ticked.
 */
@EventBusSubscriber(modid = McaMobCompat.MOD_ID)
public final class ServerLifecycleHandler {

    private ServerLifecycleHandler() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        GuardProxySync.logMirroredEffects();
        CharacterTransitions.recover(event.getServer());
    }

    /** Host replacements settle once the tick that opened them is over. */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        CharacterTransitions.onServerTickEnd(event.getServer());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        McaEditorBridge.forget(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        GuardDamageRelayHandler.clear();
        GuardProxySync.clear();
        GuardBridgeHandler.clear();
        MountPolicyHandler.clear();
        McaEditorBridge.clear();
        CharacterTransitions.clear();
        dev.otectus.mcamobcompat.threat.ThreatHandler.clear();
        ClassificationCache.invalidate();
        NativeCharacterAdapters.invalidate();
        ChoreSafetyHandler.invalidateConfig();
    }
}
