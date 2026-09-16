package dev.otectus.mcamobcompat.event;

import dev.otectus.mcamobcompat.McaMobCompat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;

/** Captures the completed initialization event without posting it a second time. */
@EventBusSubscriber(modid = McaMobCompat.MOD_ID)
public final class ProxySpawnHandler {
    private static final class Attempt {
        final Mob mob;
        FinalizeSpawnEvent event;
        Attempt(Mob mob) { this.mob = mob; }
    }
    private static final ThreadLocal<Attempt> ACTIVE = new ThreadLocal<>();
    private ProxySpawnHandler() {}

    public static boolean initialize(Mob proxy, ServerLevel level) {
        Attempt previous = ACTIVE.get();
        Attempt attempt = new Attempt(proxy);
        ACTIVE.set(attempt);
        try {
            EventHooks.finalizeMobSpawn(proxy, level, level.getCurrentDifficultyAt(proxy.blockPosition()),
                    MobSpawnType.MOB_SUMMONED, null);
            return attempt.event != null && !attempt.event.isCanceled() && !proxy.isSpawnCancelled();
        } finally {
            if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void capture(FinalizeSpawnEvent event) {
        Attempt attempt = ACTIVE.get();
        if (attempt != null && attempt.mob == event.getEntity()) attempt.event = event;
    }
}
