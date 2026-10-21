package dev.otectus.mcamobcompat.integration.workers;

import dev.otectus.mcamobcompat.McaMobCompatConfig;
import dev.otectus.mcamobcompat.character.AppearanceMode;
import dev.otectus.mcamobcompat.integration.IntegrationModule;
import dev.otectus.mcamobcompat.integration.PlatformArtifacts;
import dev.otectus.mcamobcompat.integration.recruits.RecruitsBinding;
import dev.otectus.mcamobcompat.util.ConfigAccess;
import dev.otectus.mcamobcompat.util.ModIds;

/**
 * Villager Workers. Its workers are Villager Recruits recruits by inheritance, so they are hired
 * through Recruits' trades and owned through Recruits' state; {@link WorkersAdapter} is chosen before
 * the Recruits adapter for them, so a worker is never claimed twice.
 */
public final class WorkersModule extends IntegrationModule {

    public WorkersModule() {
        super(ModIds.WORKERS, ModIds.WORKERS, "Villager Workers");
    }

    /** Switched on, on a platform with a verified Villager Workers build ({@link PlatformArtifacts}). */
    @Override
    public boolean isEnabled() {
        return PlatformArtifacts.verified(ModIds.WORKERS) && safeBoolean(McaMobCompatConfig.COMMON.workersModule, true);
    }

    @Override
    protected void onActivated() {
        // Workers cannot load without Recruits, but its recruits half is bound by Recruits' own module,
        // which may be switched off on its own; bind it here too so a worker is still recognised.
        RecruitsBinding.resolve();
        WorkersBinding.resolve();
    }

    public boolean bridgesWorkers() {
        return isActive() && safeBoolean(McaMobCompatConfig.SERVER.workersCharacters, true);
    }

    public boolean opensMcaInteraction() {
        return isActive() && safeBoolean(McaMobCompatConfig.SERVER.workersMcaInteraction, true);
    }

    public AppearanceMode appearance() {
        return ConfigAccess.enumValue(McaMobCompatConfig.SERVER.workersAppearance, AppearanceMode.MCA);
    }
}
