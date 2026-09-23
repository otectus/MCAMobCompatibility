package dev.otectus.mcamobcompat.event;

import dev.otectus.mcamobcompat.McaMobCompat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Initialises a new proxy the way any summoned mob is initialised, and reports whether another mod
 * rejected it.
 *
 * <p>The proxy goes through Forge's {@link MobSpawnEvent.FinalizeSpawn}, not a bare
 * {@code finalizeSpawn} call, so spawn-control mods see it like any other villager. MCA gives the
 * new villager its name, genetics, age state and family-tree node inside {@code finalizeSpawn},
 * so the outcome has to be known exactly: a cancelled event (MCA never ran) and a spawn marked
 * cancelled (MCA ran, but the villager must not enter the world) both mean the proxy is abandoned
 * and its family-tree node rolled back by the caller.
 *
 * <p>The event instance is captured rather than posted a second time, at {@code HIGHEST} with
 * {@code receiveCanceled}, and read after every other listener has had its say.
 */
@Mod.EventBusSubscriber(modid = McaMobCompat.MOD_ID)
public final class ProxySpawnHandler {

    /** The initialisation in progress on this thread, if any. */
    private static final ThreadLocal<Attempt> ACTIVE = new ThreadLocal<>();

    private ProxySpawnHandler() {
    }

    /** Runs Forge's spawn finalisation for this proxy. True only when nothing rejected it. */
    public static boolean initialize(Mob proxy, ServerLevel level) {
        Attempt previous = ACTIVE.get();
        Attempt attempt = new Attempt(proxy);
        ACTIVE.set(attempt);
        try {
            ForgeEventFactory.onFinalizeSpawn(proxy, level, level.getCurrentDifficultyAt(proxy.blockPosition()),
                    MobSpawnType.MOB_SUMMONED, null, null);
            return attempt.event != null && !attempt.event.isCanceled() && !proxy.isSpawnCancelled();
        } finally {
            if (previous == null) {
                ACTIVE.remove();
            } else {
                ACTIVE.set(previous);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void capture(MobSpawnEvent.FinalizeSpawn event) {
        Attempt attempt = ACTIVE.get();
        if (attempt != null && attempt.mob == event.getEntity()) {
            attempt.event = event;
        }
    }

    private static final class Attempt {
        private final Mob mob;
        private MobSpawnEvent.FinalizeSpawn event;

        private Attempt(Mob mob) {
            this.mob = mob;
        }
    }
}
