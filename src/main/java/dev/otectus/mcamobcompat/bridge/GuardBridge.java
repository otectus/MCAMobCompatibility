package dev.otectus.mcamobcompat.bridge;

import dev.otectus.mcamobcompat.character.NativeCharacterAdapters;
import dev.otectus.mcamobcompat.integration.guardvillagers.GuardVillagersAdapter;
import net.minecraft.world.entity.Entity;

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
 *
 * <p>Since 0.3.0 guards are one adapter among three ({@code character.NativeCharacterAdapters}), and
 * this is the guard-only view of that registry: an entity is a bridged guard when the Guard Villagers
 * adapter is the one bridging it. A recruit or a worker is a bridged actor but never a bridged guard,
 * so nothing promised to guard listeners -- {@code GuardInteractEvent}, guard identities -- reaches it.
 */
public final class GuardBridge {

    private GuardBridge() {
    }

    /**
     * Whether this entity gets a bridged identity. Null-safe, side-agnostic, and ordered so the
     * cheap checks reject first: this runs on every entity join.
     */
    public static boolean isBridgedGuard(Entity entity) {
        return NativeCharacterAdapters.bridging(entity) == GuardVillagersAdapter.INSTANCE;
    }
}
