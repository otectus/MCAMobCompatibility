package dev.otectus.mcamobcompat.integration.dragnlivestock;

import dev.otectus.mcamobcompat.McaMobCompatConfig;
import dev.otectus.mcamobcompat.integration.IntegrationModule;
import dev.otectus.mcamobcompat.util.ModIds;

/**
 * DragN's Livestock Overhaul, whose "O-Animals" replace vanilla animals with separate entities of
 * its own ({@code dragnlivestock:o_cow}, {@code o_pig}, {@code o_horse}, and so on).
 *
 * <p>Most of the work here is already done by DLO itself: it registers its animals into the Forge
 * conventional entity-type tags ({@code forge:cow}, {@code forge:pig}, {@code forge:horses} ...),
 * and the shipped role tags reference those, so DLO classifies correctly without this mod naming a
 * single one of its entities. The explicit ids in the tag files are belt-and-braces for the species
 * Forge has no conventional tag for.
 *
 * <p>Two ids that look real but are not, and must never appear in a tag: {@code
 * dragnlivestock:freight_wagon} and {@code dragnlivestock:o_undead_horse}. Both have language keys
 * and loot tables in the jar but neither is a registered entity type -- undead is a body-layer
 * variant of {@code o_horse}, and the freight wagon no longer exists.
 *
 * <p>Genders (DLO uses an enum gender code), breeding and genetics are entirely DLO's.
 */
public final class DragnLivestockModule extends IntegrationModule {

    public DragnLivestockModule() {
        super(ModIds.DRAGN_LIVESTOCK, ModIds.DRAGN_LIVESTOCK, "DragN's Livestock Overhaul");
    }

    @Override
    public boolean isEnabled() {
        return safeBoolean(McaMobCompatConfig.COMMON.dragnLivestockModule, true)
                && safeBoolean(McaMobCompatConfig.SERVER.dragnLivestockRecognizeLivestock, true);
    }
}
