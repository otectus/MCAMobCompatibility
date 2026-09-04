package dev.otectus.mcamobcompat.integration.swem;

import dev.otectus.mcamobcompat.McaMobCompatConfig;
import dev.otectus.mcamobcompat.integration.IntegrationModule;
import dev.otectus.mcamobcompat.util.ModIds;

/**
 * SWEM, the Star Worm Equestrian Mod. Entity {@code swem:swem_horse}, classified as horse + mount.
 *
 * <p>There is nothing for this module to bind. {@code SWEMHorseEntityBase} extends vanilla
 * {@code AbstractHorse} and overrides the vanilla owner accessors, so ownership -- the one piece of
 * SWEM state this mod reads -- comes back from {@code AbstractHorse.getOwnerUUID()} with no SWEM
 * class involved. See {@code MobCompatibilityAPI.getOwner}.
 *
 * <p>Everything else about a SWEM horse stays SWEM's: needs, training, tack, breeding tokens and
 * foals. This mod recognises the horse and writes nothing back to it.
 */
public final class SwemModule extends IntegrationModule {

    public SwemModule() {
        super(ModIds.SWEM, ModIds.SWEM, "SWEM");
    }

    @Override
    public boolean isEnabled() {
        return safeBoolean(McaMobCompatConfig.COMMON.swemModule, true)
                && safeBoolean(McaMobCompatConfig.SERVER.swemRecognizeAsMounts, true);
    }
}
