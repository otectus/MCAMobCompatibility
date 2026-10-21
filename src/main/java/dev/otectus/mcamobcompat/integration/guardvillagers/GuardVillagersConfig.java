package dev.otectus.mcamobcompat.integration.guardvillagers;

import dev.otectus.mcamobcompat.util.ModIds;
import net.minecraftforge.fml.config.ConfigTracker;
import net.minecraftforge.fml.config.ModConfig;

import java.util.List;

/**
 * Guard Villagers' own configuration, for the one value its native behaviour depends on here: the
 * reputation a player needs before a guard opens its inventory to them, which Guard Villagers' own
 * conversion grants the player who made a guard.
 *
 * <p>Read by its key through the loader's config tracker -- never through Guard Villagers' classes,
 * which this jar may not name -- and its shipped default when the config cannot be read. The key is
 * the same in Guard Villagers 1.20.1-1.6.19 and the NeoForge 1.21.1 build 2.4.12.
 */
final class GuardVillagersConfig {

    /** Guard Villagers' default for its inventory's reputation requirement. */
    static final int DEFAULT_REPUTATION_REQUIREMENT = 15;

    private static final List<String> REPUTATION_REQUIREMENT = List.of("guard stuff",
            "Minimum reputation requirement for guards to give you access to their inventories");

    private GuardVillagersConfig() {
    }

    /** The reputation Guard Villagers requires to open a guard's inventory: as configured, or its default. */
    static int reputationRequirement() {
        try {
            for (ModConfig config : ConfigTracker.INSTANCE.configSets().get(ModConfig.Type.COMMON)) {
                if (ModIds.GUARD_VILLAGERS.equals(config.getModId()) && config.getConfigData() != null
                        && config.getConfigData().get(REPUTATION_REQUIREMENT) instanceof Number value) {
                    return value.intValue();
                }
            }
        } catch (RuntimeException | LinkageError unreadable) {
            // Unreadable: the default below is what an unconfigured Guard Villagers asks for.
        }
        return DEFAULT_REPUTATION_REQUIREMENT;
    }
}
