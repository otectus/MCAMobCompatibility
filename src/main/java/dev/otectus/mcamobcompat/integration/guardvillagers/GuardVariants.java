package dev.otectus.mcamobcompat.integration.guardvillagers;

import net.minecraft.resources.ResourceLocation;

import java.util.Locale;
import java.util.Set;

/**
 * Maps a Guard Villagers guard variant onto a vanilla villager type id.
 *
 * <p>The hidden MCA proxy villager is spawned to look like the guard it stands in for, and the one
 * thing MCA's villager shares with the guard's appearance is its biome type. Guard Villagers ships
 * nine variants; seven of them are named exactly after a vanilla villager type, and the two that
 * are not -- {@code beach} and {@code ocean} -- have no villager equivalent at all.
 *
 * <p>Everything unknown falls to {@code plains}, deliberately. A variant this map has not heard of
 * means Guard Villagers has grown a new one, and a plains villager is the neutral answer; the
 * alternative is a registry miss and no proxy at all, which costs the player every MCA interaction
 * with that guard.
 *
 * <p>Pure string-to-id: no registry is touched here, so this is unit-testable and the caller owns
 * the lookup.
 */
public final class GuardVariants {

    /** The variant names that are also vanilla villager type ids. */
    private static final Set<String> VANILLA_TYPES =
            Set.of("desert", "savanna", "swamp", "jungle", "taiga", "snow", "plains");

    /** What everything else becomes. */
    public static final ResourceLocation DEFAULT_TYPE = new ResourceLocation("minecraft", "plains");

    private GuardVariants() {
    }

    /** The villager type id for this variant. Never null, never throws, null-safe. */
    public static ResourceLocation villagerType(String variant) {
        if (variant == null) {
            return DEFAULT_TYPE;
        }
        String normalised = variant.trim().toLowerCase(Locale.ROOT);
        return VANILLA_TYPES.contains(normalised)
                ? new ResourceLocation("minecraft", normalised)
                : DEFAULT_TYPE;
    }
}
