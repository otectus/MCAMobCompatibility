package dev.otectus.mcamobcompat.bridge;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.integration.ModIntegrations;
import dev.otectus.mcamobcompat.integration.guardvillagers.GuardReflection;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mirrors the visible consequences of MCA: Crime's law onto a bridged guard (0.2.2).
 *
 * <p>Every sibling addon acts on the villager it can see, and for a bridged guard that is the hidden
 * proxy: MCA: Crime cuffs it or blinds it with a sand bottle -- and the Guard Villagers guard standing
 * in the same spot keeps patrolling as though nothing happened. So the Crime effects in
 * {@link #MIRRORED} are copied from the proxy onto the guard once a second, and taken off again when
 * the proxy loses them, while a restrained proxy holds its guard in place every tick. Effects are
 * reached by registry id only, never by a Crime class name (the rule every optional-mod integration
 * here keeps), so an install without MCA: Crime resolves nothing and does nothing.
 *
 * <p>Only effects this class put on the guard are ever taken off it: a sand bottle thrown at the
 * guard itself lands on the guard natively and is left alone. Custody itself stays on the proxy --
 * cells, transport and sentences are MCA: Crime's, and it reads them from the villager it arrested.
 */
final class ProxyEffects {

    /**
     * The MCA: Crime effects worth mirroring, by registry id: exactly the effects MCA: Crime registers
     * ({@code effect/CrimeEffects} in 0.7.5). This list is a cross-mod contract, recorded in
     * {@code MCAReputation/docs/FAMILY_COMPATIBILITY.md} and pinned by {@code ProxyEffectsContractTest};
     * add an id here only after the Crime build it targets registers it. Absent ids resolve to nothing.
     */
    static final List<ResourceLocation> MIRRORED = List.of(
            McaMobCompat.location("mcacrime", "restrained"),
            McaMobCompat.location("mcacrime", "sand_blinded"));

    private static final ResourceLocation RESTRAINED = MIRRORED.get(0);

    /** Which mirrored effects each guard currently carries because of this class. */
    private static final Map<UUID, Set<ResourceLocation>> APPLIED = new ConcurrentHashMap<>();

    /** Guards currently held in place by a restrained proxy, so follow/patrol are only cleared once. */
    private static final Set<UUID> HELD = ConcurrentHashMap.newKeySet();

    private ProxyEffects() {
    }

    /** One tick of the pair: hold a restrained guard every tick, mirror the effects on the slow tick. */
    static void tick(Mob guard, Villager proxy, boolean slow) {
        MobEffect restrained = BuiltInRegistries.MOB_EFFECT.get(RESTRAINED);
        boolean held = restrained != null && proxy.hasEffect(restrained);
        hold(guard, held);
        if (slow) {
            mirror(guard, proxy);
        }
    }

    private static void hold(Mob guard, boolean held) {
        UUID id = guard.getUUID();
        if (held) {
            guard.getNavigation().stop();
            if (HELD.add(id) && ModIntegrations.guardVillagers().isActive()) {
                GuardReflection.setFollowing(guard, false);
                GuardReflection.setPatrolling(guard, false);
            }
        } else {
            HELD.remove(id);
        }
    }

    private static void mirror(Mob guard, Villager proxy) {
        Set<ResourceLocation> applied = APPLIED.computeIfAbsent(guard.getUUID(), key -> ConcurrentHashMap.newKeySet());
        for (ResourceLocation id : MIRRORED) {
            MobEffect effect = BuiltInRegistries.MOB_EFFECT.get(id);
            if (effect == null) {
                continue; // MCA: Crime is not installed, or this build lacks the effect
            }
            MobEffectInstance onProxy = proxy.getEffect(effect);
            if (onProxy != null) {
                MobEffectInstance onGuard = guard.getEffect(effect);
                if (onGuard == null || onGuard.getAmplifier() != onProxy.getAmplifier()
                        || onGuard.getDuration() + 2 * 20 < onProxy.getDuration()) {
                    guard.addEffect(new MobEffectInstance(onProxy));
                }
                applied.add(id);
            } else if (applied.remove(id) && guard.hasEffect(effect)) {
                guard.removeEffect(effect);
            }
        }
    }

    /** Forgets a guard's bookkeeping; its effects are left to run out on their own. */
    static void forget(Mob guard) {
        if (guard != null) {
            APPLIED.remove(guard.getUUID());
            HELD.remove(guard.getUUID());
        }
    }

    /** Drops all bookkeeping. Called when the server stops. */
    static void clear() {
        APPLIED.clear();
        HELD.clear();
    }

    /**
     * Says once, at server start, which mirrored ids the installed MCA: Crime registers, so an operator
     * sees drift between {@link #MIRRORED} and the Crime build in the log instead of a guard that
     * silently ignores a cuff. With no Crime effect registered at all (MCA: Crime absent) it logs at
     * debug level only.
     */
    static void logResolution() {
        List<ResourceLocation> resolved = new ArrayList<>();
        List<ResourceLocation> missing = new ArrayList<>();
        for (ResourceLocation id : MIRRORED) {
            (BuiltInRegistries.MOB_EFFECT.containsKey(id) ? resolved : missing).add(id);
        }
        if (resolved.isEmpty()) {
            McaMobCompat.debug("No MCA: Crime effect is registered; nothing will be mirrored onto bridged "
                    + "guards ({}).", MIRRORED);
        } else if (missing.isEmpty()) {
            McaMobCompat.LOGGER.info("Mirroring MCA: Crime effects onto bridged guards: {}.", resolved);
        } else {
            McaMobCompat.LOGGER.warn("MCA: Crime is installed but does not register {}; only {} will be "
                    + "mirrored onto bridged guards. This build of MCA: Mob Compatibility expects a "
                    + "different MCA: Crime.", missing, resolved);
        }
    }
}
