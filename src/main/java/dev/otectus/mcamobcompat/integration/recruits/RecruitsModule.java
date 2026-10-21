package dev.otectus.mcamobcompat.integration.recruits;

import dev.otectus.mcamobcompat.McaMobCompatConfig;
import dev.otectus.mcamobcompat.character.AppearanceMode;
import dev.otectus.mcamobcompat.character.RecruitsConversionPolicy;
import dev.otectus.mcamobcompat.integration.IntegrationModule;
import dev.otectus.mcamobcompat.integration.PlatformArtifacts;
import dev.otectus.mcamobcompat.util.ConfigAccess;
import dev.otectus.mcamobcompat.util.ModIds;

/**
 * Villager Recruits. Its recruits become MCA characters through {@link RecruitsAdapter}, and the
 * conversions Recruits runs on villagers -- every one of which discards the villager -- never reach an
 * MCA villager (see {@code character.RecruitsConversions}).
 *
 * <p>Reached only through {@link RecruitsBinding}, which names Recruits' classes as strings, and through
 * Recruits' own Forge events and its hire paths, bracketed by the optional hooks in {@code hook}.
 */
public final class RecruitsModule extends IntegrationModule {

    public RecruitsModule() {
        super(ModIds.RECRUITS, ModIds.RECRUITS, "Villager Recruits");
    }

    /** Switched on, on a platform with a verified Villager Recruits build ({@link PlatformArtifacts}). */
    @Override
    public boolean isEnabled() {
        return PlatformArtifacts.verified(ModIds.RECRUITS) && safeBoolean(McaMobCompatConfig.COMMON.recruitsModule, true);
    }

    @Override
    protected void onActivated() {
        RecruitsBinding.resolve();
    }

    public boolean bridgesRecruits() {
        return isActive() && safeBoolean(McaMobCompatConfig.SERVER.recruitsCharacters, true);
    }

    public boolean opensMcaInteraction() {
        return isActive() && safeBoolean(McaMobCompatConfig.SERVER.recruitsMcaInteraction, true);
    }

    public AppearanceMode appearance() {
        return ConfigAccess.enumValue(McaMobCompatConfig.SERVER.recruitsAppearance, AppearanceMode.MCA);
    }

    public RecruitsConversionPolicy villagerConversion() {
        return ConfigAccess.enumValue(McaMobCompatConfig.SERVER.recruitsVillagerConversion, RecruitsConversionPolicy.ENLIST);
    }

    public boolean lawEnforcement() {
        return isActive() && safeBoolean(McaMobCompatConfig.SERVER.recruitsLawEnforcement, false);
    }
}
