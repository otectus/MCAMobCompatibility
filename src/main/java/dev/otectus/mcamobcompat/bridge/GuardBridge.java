package dev.otectus.mcamobcompat.bridge;

import dev.otectus.mcamobcompat.api.MobRole;
import dev.otectus.mcamobcompat.classification.TagClassifier;
import dev.otectus.mcamobcompat.integration.ModIntegrations;
import dev.otectus.mcamobcompat.mca.McaEntities;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

/**
 * The one question the whole bridge turns on: is this entity a guard we give an MCA identity to?
 *
 * <p>Matched by the {@code mcamobcompat:guard} tag, not by an entity id compare, which is how
 * {@code GuardDefenseHandler} already decides what a guard is -- so a pack that tags another mod's
 * defender gets the bridge for free. The Guard Villagers module still gates it: with that mod
 * absent there are no MCA-style guards to be compatible with, and a tagged NPC from some third mod
 * should not silently acquire a name.
 *
 * <p>MCA's own villagers are excluded explicitly. They already have every one of these things from
 * MCA, and shadowing them with a second identity is exactly the kind of double bookkeeping this mod
 * exists to prevent.
 */
public final class GuardBridge {

    private GuardBridge() {
    }

    /**
     * Whether this entity gets a bridged identity. Null-safe, side-agnostic, and ordered so the
     * cheap checks reject first: this runs on every entity join.
     */
    public static boolean isBridgedGuard(Entity entity) {
        if (!(entity instanceof Mob)) {
            return false;
        }
        if (!ModIntegrations.guardVillagers().bridgesGuards()) {
            return false;
        }
        if (McaEntities.isMcaVillager(entity)) {
            return false;
        }
        return TagClassifier.hasRole(entity, MobRole.GUARD);
    }
}
