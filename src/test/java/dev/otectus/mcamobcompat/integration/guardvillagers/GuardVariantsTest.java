package dev.otectus.mcamobcompat.integration.guardvillagers;

import dev.otectus.mcamobcompat.McaMobCompat;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the guard-variant to villager-type map.
 *
 * <p>The two interesting cases are {@code beach} and {@code ocean}: Guard Villagers has them and
 * vanilla has no villager for either, so they must land on something real rather than on a registry
 * miss -- a miss means no proxy, and no proxy means the player cannot talk to that guard at all.
 * Everything unrecognised has to behave the same way, because the next Guard Villagers release may
 * add a variant this map has never heard of.
 */
class GuardVariantsTest {

    private static final ResourceLocation PLAINS = McaMobCompat.location("minecraft", "plains");

    @Test
    void theSevenVanillaNamesPassStraightThrough() {
        for (String variant : new String[]{"desert", "savanna", "swamp", "jungle", "taiga", "snow",
                "plains"}) {
            assertEquals(McaMobCompat.location("minecraft", variant),
                    GuardVariants.villagerType(variant),
                    "'" + variant + "' is the name of a vanilla villager type and must be used as one.");
        }
    }

    @Test
    void everythingElseBecomesPlains() {
        assertEquals(PLAINS, GuardVariants.villagerType("beach"));
        assertEquals(PLAINS, GuardVariants.villagerType("ocean"));
        assertEquals(PLAINS, GuardVariants.villagerType(null));
        assertEquals(PLAINS, GuardVariants.villagerType(""));
        assertEquals(PLAINS, GuardVariants.villagerType("   "));
        assertEquals(PLAINS, GuardVariants.villagerType("mushroom_island"));
    }

    @Test
    void theNameIsNormalisedBeforeItIsMatched() {
        assertEquals(McaMobCompat.location("minecraft", "taiga"), GuardVariants.villagerType("TAIGA"));
        assertEquals(McaMobCompat.location("minecraft", "snow"), GuardVariants.villagerType(" Snow "));
    }
}
