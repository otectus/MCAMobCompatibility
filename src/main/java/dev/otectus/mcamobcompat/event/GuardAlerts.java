package dev.otectus.mcamobcompat.event;

import dev.otectus.mcamobcompat.api.MobCompatibilityAPI;
import dev.otectus.mcamobcompat.bridge.GuardProxy;
import dev.otectus.mcamobcompat.mca.McaEntities;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Points nearby guards at a threat, boundedly.
 *
 * <p>Shared by the villager-defence handler and the creeper-awareness handler, so both obey the same
 * rules: only a guard with no target of its own is redirected (an ongoing fight is never
 * interrupted), only a guard whose own {@code canAttack} accepts the threat (Guard Villagers keeps
 * its own do-not-attack list, which this respects rather than overrides), never an MCA villager and
 * never a hidden proxy, and never more than the configured cap, so one mob in a large village cannot
 * start a village-wide pathfinding storm. Guards are found through the {@code mcamobcompat:guard}
 * tag and commanded through vanilla {@code Mob.setTarget}, so no Guard Villagers class is named.
 */
public final class GuardAlerts {

    private GuardAlerts() {
    }

    /**
     * Alerts up to {@code max} idle guards within {@code range} blocks of {@code centre} to
     * {@code threat}. Returns how many were alerted.
     */
    public static int alert(ServerLevel level, LivingEntity centre, LivingEntity threat, int range, int max) {
        if (level == null || centre == null || threat == null || !threat.isAlive() || max <= 0) {
            return 0;
        }
        AABB search = centre.getBoundingBox().inflate(range);
        List<Mob> candidates = level.getEntitiesOfClass(Mob.class, search, mob ->
                mob != threat
                        && mob.isAlive()
                        && mob.getTarget() == null
                        && !McaEntities.isMcaVillager(mob)
                        && !GuardProxy.isProxy(mob)
                        && MobCompatibilityAPI.isGuard(mob));
        int alerted = 0;
        for (Mob guard : candidates) {
            if (alerted >= max) {
                break;
            }
            if (!guard.canAttack(threat)) {
                continue;
            }
            guard.setTarget(threat);
            if (guard.getTarget() == threat) {
                alerted++;
            }
        }
        return alerted;
    }
}
