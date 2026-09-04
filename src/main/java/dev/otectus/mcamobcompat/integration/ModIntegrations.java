package dev.otectus.mcamobcompat.integration;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.integration.dragnlivestock.DragnLivestockModule;
import dev.otectus.mcamobcompat.integration.geneticanimals.GeneticAnimalsModule;
import dev.otectus.mcamobcompat.integration.guardvillagers.GuardVillagersModule;
import dev.otectus.mcamobcompat.integration.swem.SwemModule;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Detects which optional mods are installed and reports it, once, at startup.
 *
 * <p>Must be initialised from {@code FMLCommonSetupEvent.enqueueWork}: only there has every mod
 * finished loading, so only there is {@code ModList} authoritative.
 */
public final class ModIntegrations {

    private static final GuardVillagersModule GUARD_VILLAGERS = new GuardVillagersModule();
    private static final SwemModule SWEM = new SwemModule();
    private static final GeneticAnimalsModule GENETIC_ANIMALS = new GeneticAnimalsModule();
    private static final DragnLivestockModule DRAGN_LIVESTOCK = new DragnLivestockModule();

    private static final List<IntegrationModule> MODULES =
            List.of(GUARD_VILLAGERS, SWEM, GENETIC_ANIMALS, DRAGN_LIVESTOCK);

    private static final Map<String, IntegrationModule> BY_NAMESPACE = new HashMap<>();

    static {
        for (IntegrationModule module : MODULES) {
            BY_NAMESPACE.put(module.namespace(), module);
        }
    }

    private ModIntegrations() {
    }

    /** Detects every optional mod and logs one line each. Call once, during common setup. */
    public static void init() {
        for (IntegrationModule module : MODULES) {
            module.detect();
            if (!module.isPresent()) {
                McaMobCompat.LOGGER.info("{} not installed; integration skipped.", module.displayName());
            } else if (!module.isEnabled()) {
                McaMobCompat.LOGGER.info("Detected {} {}, but the integration is disabled in the config.",
                        module.displayName(), module.installedVersion().orElse("(unknown version)"));
            } else {
                McaMobCompat.LOGGER.info("Detected {} {}; integration enabled.",
                        module.displayName(), module.installedVersion().orElse("(unknown version)"));
            }
        }
    }

    /**
     * Whether this entity type belongs to a mod whose integration is switched off, in which case it
     * gets no roles at all.
     *
     * <p>"The module is off" has to mean the mod becomes invisible to this addon, not "off except
     * that classification still happens" -- otherwise turning a module off to stop two mods fighting
     * would not actually stop it. The result is cached with the rest of the classification and
     * dropped on config reload, so it cannot go stale.
     */
    public static boolean isSuppressed(EntityType<?> type) {
        if (BY_NAMESPACE.isEmpty()) {
            return false;
        }
        ResourceLocation id;
        try {
            id = EntityType.getKey(type);
        } catch (Throwable t) {
            return false;
        }
        if (id == null) {
            return false;
        }
        IntegrationModule module = BY_NAMESPACE.get(id.getNamespace());
        // An unknown namespace is never suppressed: third-party mobs are classified by whatever tags
        // name them, which is the entire point of a data-driven classifier.
        return module != null && !module.isActive();
    }

    /** Every module, installed or not, in a stable order. */
    public static List<IntegrationModule> modules() {
        return MODULES;
    }

    public static GuardVillagersModule guardVillagers() {
        return GUARD_VILLAGERS;
    }

    public static SwemModule swem() {
        return SWEM;
    }

    public static GeneticAnimalsModule geneticAnimals() {
        return GENETIC_ANIMALS;
    }

    public static DragnLivestockModule dragnLivestock() {
        return DRAGN_LIVESTOCK;
    }
}
