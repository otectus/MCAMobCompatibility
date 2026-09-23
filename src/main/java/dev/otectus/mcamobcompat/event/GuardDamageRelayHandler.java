package dev.otectus.mcamobcompat.event;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.bridge.GuardBridge;
import dev.otectus.mcamobcompat.bridge.GuardIdentityStore;
import dev.otectus.mcamobcompat.bridge.GuardProxy;
import dev.otectus.mcamobcompat.bridge.GuardProxySync;
import dev.otectus.mcamobcompat.mca.McaEntities;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.living.LivingExperienceDropEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Makes MCA feel a hit that landed on a guard, and a death that really happened to one.
 *
 * <p>The guard takes the damage -- it is the entity with the model, the armour and the health bar --
 * but MCA, MCACrime and MCAReputation all watch for a <em>villager</em> being hurt or killed. So each
 * hit is replayed onto the hidden proxy with the same damage source, which is what makes an assault
 * on a guard an assault on a villager to every addon in the suite, and what lets MCA apply its own
 * memory penalty instead of a second scale invented here.
 *
 * <p><b>Final damage, exactly once.</b> The relay listens to {@link LivingDamageEvent} at
 * {@code LOWEST}, which on Forge carries the health damage after armour, enchantments, absorption
 * and every other mod's mitigation, and only for a hit nothing cancelled. The proxy receives that
 * exact amount (its own mitigation is overridden for the relay) and is topped back up afterwards,
 * so relayed hits never accumulate into a death. The identity's snapshot of MCA's hearts is taken
 * straight after each relay, so a heart penalty MCA applied for the hit is on record at once.
 *
 * <p><b>Death is confirmed, not assumed.</b> A hit that would be lethal is held until the end of the
 * server tick, when the guard's real outcome is known: a totem of undying or a listener that cancels
 * the death leaves a living guard, and killing its MCA identity then would be unrecoverable. A death
 * that stands is relayed once, through {@code hurt} rather than {@code kill} -- {@code kill()}
 * hides the killer, and MCACrime and MCAReputation read the damage source to decide who murdered a
 * villager -- and the link is released only once the proxy is really dying, so MCA's own death
 * handling (family tree, grave, village announcement) runs undisturbed. A relayed death another mod
 * cancels is never retried: MCA's own death override may already have run its relationship
 * callbacks, and the link is kept for an operator to look at.
 *
 * <p>Nothing else may damage a proxy. It stands in lava, in fire and in front of whatever is
 * attacking the guard, with no AI to get out of the way; without a veto it would die of a fall the
 * guard walked off and take the guard's MCA identity with it. The one exception is damage that
 * bypasses invulnerability -- an operator's {@code /kill}, the void -- which is how a proxy is
 * deliberately removed.
 */
@Mod.EventBusSubscriber(modid = McaMobCompat.MOD_ID)
public final class GuardDamageRelayHandler {

    /** The relay in progress on this thread: which proxy, and the exact health damage it must take. */
    private record Relay(Villager proxy, float amount) {
    }

    /** A potentially lethal hit, held until the guard's real outcome is known. */
    private record Hit(Mob guard, Villager proxy, DamageSource source, float amount) {
    }

    /** A guard death, held until nothing can cancel it any more. */
    private record Death(Mob guard, DamageSource source, LivingDeathEvent event) {
    }

    private static final ThreadLocal<Relay> ACTIVE = new ThreadLocal<>();
    private static final List<Hit> SURVIVAL_CHECKS = new ArrayList<>();
    private static final Map<UUID, Death> DEATHS = new LinkedHashMap<>();

    private GuardDamageRelayHandler() {
    }

    /** Whether this entity is the proxy currently receiving a relayed hit. */
    public static boolean isRelaying(Entity entity) {
        Relay relay = ACTIVE.get();
        return relay != null && relay.proxy() == entity;
    }

    /** The guard took final health damage: relay it now, or hold it if it may be lethal. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onGuardDamaged(LivingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide || event.getAmount() <= 0.0F || !(victim instanceof Mob guard)
                || McaEntities.isMcaVillager(guard) || isRelaying(guard)) {
            return;
        }
        if (!guard.getPersistentData().hasUUID(GuardProxy.KEY_PROXY) && !GuardBridge.isBridgedGuard(guard)) {
            return;
        }
        Villager proxy = GuardProxy.proxyOf(guard);
        if (proxy == null || !proxy.isAlive()) {
            return;
        }
        float amount = event.getAmount();
        if (guard.getHealth() - amount <= 0.0F) {
            SURVIVAL_CHECKS.add(new Hit(guard, proxy, event.getSource(), amount));
            return;
        }
        relay(proxy, event.getSource(), Math.min(amount, proxy.getMaxHealth() - 1.0F), false);
        GuardIdentityStore.synchronize(guard);
    }

    /** Records a guard's death, to be confirmed at the end of the tick. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onGuardDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide || !(victim instanceof Mob guard) || McaEntities.isMcaVillager(guard)
                || isRelaying(guard) || victim.getServer() == null) {
            return;
        }
        if (GuardIdentityStore.get(victim.getServer()).find(guard.getUUID()) != null) {
            DEATHS.put(guard.getUUID(), new Death(guard, event.getSource(), event));
        }
    }

    /** Settles every held death and lethal hit, once the tick's outcomes are final. */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || (DEATHS.isEmpty() && SURVIVAL_CHECKS.isEmpty())) {
            return;
        }
        for (Death death : List.copyOf(DEATHS.values())) {
            Mob guard = death.guard();
            if (death.event().isCanceled() || guard.isAlive()) {
                DEATHS.remove(guard.getUUID());
            } else if (guard.isDeadOrDying() || guard.getRemovalReason() == Entity.RemovalReason.KILLED) {
                finishDeath(guard, death.source());
            }
        }
        for (Hit hit : List.copyOf(SURVIVAL_CHECKS)) {
            if (hit.guard().isAlive() && hit.proxy().isAlive()) {
                relay(hit.proxy(), hit.source(), Math.min(hit.amount(), hit.proxy().getMaxHealth() - 1.0F), false);
                GuardIdentityStore.synchronize(hit.guard());
            }
        }
        SURVIVAL_CHECKS.clear();
    }

    /**
     * The guard was removed as killed. Settles its death now if it is still held; if no death was
     * ever announced for it (another mod swallowed the event but the guard died anyway), settles it
     * with the last damage it took.
     */
    public static void confirmRemoval(Mob guard) {
        Death death = DEATHS.get(guard.getUUID());
        if (death != null) {
            finishDeath(guard, death.source());
            return;
        }
        if (guard.getServer() == null || GuardProxy.proxyOf(guard) == null) {
            return;
        }
        var entry = GuardIdentityStore.get(guard.getServer()).find(guard.getUUID());
        if (entry != null && !entry.deceased()) {
            DamageSource last = guard.getLastDamageSource();
            finishDeath(guard, last != null ? last : guard.damageSources().generic());
        }
    }

    private static void finishDeath(Mob guard, DamageSource source) {
        DEATHS.remove(guard.getUUID());
        if (guard.getServer() != null) {
            GuardIdentityStore.get(guard.getServer()).markDeceased(guard.getUUID(), guard.level().getGameTime());
        }
        Villager proxy = GuardProxy.proxyOf(guard);
        GuardProxySync.forget(guard);
        if (proxy == null) {
            return;
        }
        if (proxy.isAlive()) {
            relay(proxy, source, proxy.getMaxHealth() + 1.0F, true);
        }
        if (proxy.isDeadOrDying() || proxy.isRemoved()) {
            // The last snapshot of MCA's hearts, while the link can still reach them.
            GuardIdentityStore.synchronize(guard);
            GuardProxy.unlink(guard);
            McaMobCompat.debug("Relayed the death of bridged guard {} onto proxy {}", guard.getUUID(), proxy.getUUID());
        } else {
            McaMobCompat.LOGGER.warn("Guard {} died but its MCA proxy {} rejected the death; keeping the link "
                    + "for diagnosis rather than retrying.", guard.getUUID(), proxy.getUUID());
        }
    }

    private static void relay(Villager proxy, DamageSource source, float amount, boolean lethal) {
        if (amount <= 0.0F) {
            return;
        }
        Relay previous = ACTIVE.get();
        boolean invulnerable = proxy.isInvulnerable();
        ACTIVE.set(new Relay(proxy, amount));
        try {
            proxy.setInvulnerable(false);
            proxy.invulnerableTime = 0;
            proxy.setHealth(proxy.getMaxHealth());
            proxy.hurt(source, amount);
        } finally {
            // A callback may legitimately have killed the proxy. Never revive it here.
            if (!lethal && proxy.isAlive() && !proxy.isDeadOrDying()) {
                proxy.setHealth(proxy.getMaxHealth());
            }
            proxy.setInvulnerable(invulnerable);
            if (previous == null) {
                ACTIVE.remove();
            } else {
                ACTIVE.set(previous);
            }
        }
    }

    /** The proxy takes exactly the relayed amount: none of its own mitigation applies. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void relayExactAmount(LivingDamageEvent event) {
        Relay relay = ACTIVE.get();
        if (relay != null && relay.proxy() == event.getEntity()) {
            event.getEntity().setAbsorptionAmount(0.0F);
            event.setAmount(relay.amount());
        }
    }

    /** Nothing but a relay, or damage that bypasses invulnerability, may reach a proxy. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onProxyAttacked(LivingAttackEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide || !McaEntities.isMcaVillager(victim) || isRelaying(victim)
                || event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return;
        }
        if (GuardProxy.isProxy(victim)) {
            event.setCanceled(true);
        }
    }

    /** The visible guard drops the combat loot; its proxy drops nothing of its own. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void proxyLoot(LivingDropsEvent event) {
        if (GuardProxy.isProxy(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void proxyExperience(LivingExperienceDropEvent event) {
        if (GuardProxy.isProxy(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    /** Drops every held relay; called when the server stops. */
    public static void clear() {
        DEATHS.clear();
        SURVIVAL_CHECKS.clear();
        ACTIVE.remove();
    }
}
