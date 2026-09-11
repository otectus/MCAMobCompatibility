package dev.otectus.mcamobcompat.event;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.bridge.GuardBridge;
import dev.otectus.mcamobcompat.bridge.GuardProxy;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Makes MCA feel a hit that landed on a guard.
 *
 * <p>The guard takes the damage -- it is the entity with the model, the armour and the health bar --
 * but MCA, MCACrime and MCAReputation all watch for a <em>villager</em> being hurt or killed. So
 * every hit is replayed onto the hidden proxy with the same damage source, which is what makes an
 * assault on a guard an assault on a villager to every addon in the suite, and what lets MCA apply
 * its own memory penalty instead of a second scale invented here.
 *
 * <p>The proxy's own health is meaningless and is topped up either side of the relay, so it cannot
 * die from an accumulation of relayed hits: only the death relay kills it, and only when the guard
 * itself has died.
 *
 * <p>All three handlers hang off one static re-entrancy flag. It is read and written on the server
 * thread only, inside a {@code try/finally} that spans a single {@code hurt} call, so there is no
 * window in which an unrelated entity's damage can be mistaken for a relay.
 */
@Mod.EventBusSubscriber(modid = McaMobCompat.MOD_ID)
public final class GuardDamageRelayHandler {

    /** Set for exactly the length of one relayed {@code hurt} call. */
    private static boolean relaying;

    private GuardDamageRelayHandler() {
    }

    /**
     * Relays a hit on the guard onto its proxy.
     *
     * <p>{@code LOWEST} so the amount relayed is what the guard actually took, after armour,
     * enchantments and every other mod's mitigation. Every source is relayed, not only players: a
     * guard burnt or shot is a villager MCA should react to, and the addons key off the proxy's
     * events rather than off ours.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onGuardHurt(LivingHurtEvent event) {
        if (relaying || event.getEntity().level().isClientSide) {
            return;
        }
        if (!(event.getEntity() instanceof Mob guard) || !GuardBridge.isBridgedGuard(guard)) {
            return;
        }
        Villager proxy = GuardProxy.proxyOf(guard);
        if (proxy == null) {
            return;
        }

        float max = proxy.getMaxHealth();
        float amount = Math.min(event.getAmount(), max - 1.0F);
        if (amount <= 0.0F) {
            return;
        }
        relaying = true;
        try {
            proxy.setHealth(max);
            proxy.hurt(event.getSource(), amount);
        } finally {
            proxy.setHealth(max);
            relaying = false;
        }
    }

    /**
     * Kills the proxy with the guard, through {@code hurt} rather than {@code kill}.
     *
     * <p>{@code kill()} hides the killer, and the killer is the entire point: MCACrime and
     * MCAReputation read the damage source to decide who murdered a villager. The link is dropped
     * straight afterwards so nothing discards the proxy out from under MCA's death handling -- the
     * family tree, the grave and the village announcement all still have to run on it.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onGuardDeath(LivingDeathEvent event) {
        if (relaying || event.getEntity().level().isClientSide) {
            return;
        }
        if (!(event.getEntity() instanceof Mob guard) || !GuardBridge.isBridgedGuard(guard)) {
            return;
        }
        Villager proxy = GuardProxy.proxyOf(guard);
        if (proxy == null) {
            return;
        }

        relaying = true;
        try {
            proxy.hurt(event.getSource(), Float.MAX_VALUE);
        } finally {
            relaying = false;
        }
        GuardProxy.unlink(guard);
        McaMobCompat.debug("Relayed the death of bridged guard {} onto proxy {}",
                guard.getUUID(), proxy.getUUID());
    }

    /**
     * Nothing may damage the proxy except this class.
     *
     * <p>It stands in lava, in fire, in the rain and in front of whatever is attacking the guard,
     * and it has no AI to get out of the way. Without this it would quietly die of a fall the guard
     * walked off, and take the guard's MCA identity with it.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onProxyAttacked(LivingAttackEvent event) {
        if (!relaying && GuardProxy.isProxy(event.getEntity())) {
            event.setCanceled(true);
        }
    }
}
