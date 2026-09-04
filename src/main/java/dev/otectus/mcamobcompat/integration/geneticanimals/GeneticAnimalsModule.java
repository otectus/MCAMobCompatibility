package dev.otectus.mcamobcompat.integration.geneticanimals;

import dev.otectus.mcamobcompat.McaMobCompatConfig;
import dev.otectus.mcamobcompat.integration.IntegrationModule;
import dev.otectus.mcamobcompat.util.ModIds;

/**
 * Genetic Animals, which replaces vanilla cows, pigs, chickens, sheep, llamas, rabbits, mooshrooms,
 * turtles and axolotls with genetically-driven entities of its own.
 *
 * <p>Its mod id is {@code eanimod}, not {@code geneticanimals} -- see {@link ModIds#GENETIC_ANIMALS}
 * -- and its entities are {@code eanimod:enhanced_cow}, {@code enhanced_pig}, {@code enhanced_sheep}
 * and so on. Classification is entirely tag-driven; this module exists to gate it and to report the
 * mod at startup.
 *
 * <p>Genes, gender, pregnancy, egg fertility, grazing, hunger and sleep are all owned by Genetic
 * Animals. This mod never calls {@code Animal.breed} or {@code spawnChildFromBreeding} on anything,
 * so there is no path by which it could override any of that.
 */
public final class GeneticAnimalsModule extends IntegrationModule {

    public GeneticAnimalsModule() {
        super(ModIds.GENETIC_ANIMALS, ModIds.GENETIC_ANIMALS, "Genetic Animals");
    }

    @Override
    public boolean isEnabled() {
        return safeBoolean(McaMobCompatConfig.COMMON.geneticAnimalsModule, true)
                && safeBoolean(McaMobCompatConfig.SERVER.geneticAnimalsRecognizeLivestock, true);
    }
}
