package dev.otectus.mcamobcompat.integration.alexsmobs;

import dev.otectus.mcamobcompat.McaMobCompatConfig;
import dev.otectus.mcamobcompat.integration.IntegrationModule;
import dev.otectus.mcamobcompat.util.ModIds;

/**
 * Alex's Mobs. Pure recognition: its ground-rideable animals are listed in the shipped
 * {@code mount} tag, and its tamed animals are pets through vanilla ownership
 * ({@code OwnedAnimalProvider}), which also puts them under the hunting chore's protection. Nothing
 * of Alex's Mobs is named, bound or written; see {@code docs/ALEXS_MOBS_AUDIT.md} for what was
 * checked, species by species, and what is deliberately left alone.
 */
public final class AlexsMobsModule extends IntegrationModule {

    public AlexsMobsModule() {
        super(ModIds.ALEXS_MOBS, ModIds.ALEXS_MOBS, "Alex's Mobs");
    }

    @Override
    public boolean isEnabled() {
        return safeBoolean(McaMobCompatConfig.COMMON.alexsMobsModule, true)
                && safeBoolean(McaMobCompatConfig.SERVER.alexsRecognizeAnimals, true);
    }
}
