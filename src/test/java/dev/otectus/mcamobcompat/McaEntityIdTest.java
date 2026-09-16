package dev.otectus.mcamobcompat;

import dev.otectus.mcamobcompat.mca.McaEntities;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Guards the identity of an MCA villager, which is the safety-critical constant in this mod.
 *
 * <p>If this set is wrong, the guard-conversion blocker silently stops covering a villager type and
 * somebody loses a family tree that cannot be rebuilt. It is small enough to state exactly, so it is
 * stated exactly here, and a careless edit fails the build rather than a player's save.
 *
 * <p>It also pins the decision to keep the set in Java rather than in a datapack tag. A tag can be
 * emptied by any pack, including by accident with {@code "replace": true} in an unrelated file, and
 * the one thing in this mod whose failure destroys data must not be switchable from data.
 */
class McaEntityIdTest {

    @Test
    void mcaVillagerIdsAreExactlyTheTwoHumanVillagers() {
        assertEquals(
                Set.of(ResourceLocation.fromNamespaceAndPath("mca", "male_villager"),
                        ResourceLocation.fromNamespaceAndPath("mca", "female_villager")),
                McaEntities.villagerIds(),
                "The protected MCA villager set changed. These two ids have been stable across every MCA "
                        + "build, through two Java package renames, which is exactly why this mod "
                        + "identifies villagers by registry id instead of by class.");
    }

    @Test
    void zombieVillagersAreDeliberatelyExcluded() {
        // They are zombies: they do not pass the instanceof-Villager gate that Guard Villagers'
        // conversion uses, and guards ought to be free to attack them.
        assertFalse(McaEntities.villagerIds().contains(ResourceLocation.fromNamespaceAndPath("mca", "male_zombie_villager")));
        assertFalse(McaEntities.villagerIds().contains(ResourceLocation.fromNamespaceAndPath("mca", "female_zombie_villager")));
    }

    @Test
    void theExtensionTagIsInOurOwnNamespace() {
        // A pack extends the protected set through this tag; it is additive on top of the constants
        // above and can never remove one of them.
        assertEquals(ResourceLocation.fromNamespaceAndPath("mcamobcompat", "mca_villagers"),
                McaEntities.ADDITIONAL_VILLAGERS_ID);
    }

    @Test
    void nullsAreNotVillagers() {
        // Both handlers call this with whatever an event handed them, which can be null.
        assertFalse(McaEntities.isMcaVillager((Entity) null));
        assertFalse(McaEntities.isMcaVillager((EntityType<?>) null));
    }
}
