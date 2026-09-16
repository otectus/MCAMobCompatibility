package dev.otectus.mcamobcompat.util;

/**
 * Mod ids of every mod this addon knows about.
 *
 * <p>These are used only with {@code ModList.get().isLoaded(...)} and to compare an entity id's
 * namespace. No class in this jar references a type from any of these mods, so nothing here can
 * cause a {@code NoClassDefFoundError} -- see {@code NoOptionalModStaticLinkTest}.
 */
public final class ModIds {

    /** MCA Reborn. The one mandatory dependency. */
    public static final String MCA = "mca";

    /** Guard Villagers, by TallestEgg. Entity: {@code guardvillagers:guard}. */
    public static final String GUARD_VILLAGERS = "guardvillagers";

    /** SWEM, the Star Worm Equestrian Mod. Entity: {@code swem:swem_horse}. */
    public static final String SWEM = "swem";

    /**
     * Genetic Animals, by mokiyoki.
     *
     * <p>The mod id really is {@code eanimod}, not {@code geneticanimals}: the jar declares
     * {@code modId="eanimod"} and its package root is {@code mokiyoki.enhancedanimals} (the mod was
     * originally called Enhanced Animals). Its entities are {@code eanimod:enhanced_cow},
     * {@code eanimod:enhanced_pig}, and so on. Guessing {@code geneticanimals} produces tag entries
     * that silently never match, which is the worst kind of wrong.
     */
    public static final String GENETIC_ANIMALS = "eanimod";

    /** DragN's Livestock Overhaul. Entities: {@code dragnlivestock:o_cow}, {@code o_horse}, ... */
    public static final String DRAGN_LIVESTOCK = "dragnlivestock";

    private ModIds() {
    }
}
