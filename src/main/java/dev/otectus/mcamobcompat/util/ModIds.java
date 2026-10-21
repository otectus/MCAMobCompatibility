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

    /**
     * Icy's Better Horses. It mixes into vanilla {@code AbstractHorse}, so its ownership and command
     * state are reached on vanilla horses. Release 1.1.6 registers no entity of its own; release 2.0
     * (NeoForge 1.21.1) adds fifteen breeds and a cart under {@link #ICYS_BETTER_HORSES_ENTITIES}.
     */
    public static final String ICYS_BETTER_HORSES = "icys_better_horses";

    /**
     * The namespace Icy's Better Horses 2.0 registers its entities under -- with hyphens, unlike its
     * mod id: {@code icys-better-horses:arabian_horse}, {@code icys-better-horses:horse_cart}, ...
     */
    public static final String ICYS_BETTER_HORSES_ENTITIES = "icys-better-horses";

    /** Alex's Mobs. Entities: {@code alexsmobs:elephant}, {@code alexsmobs:raccoon}, ... */
    public static final String ALEXS_MOBS = "alexsmobs";

    /**
     * Villager Recruits, by talhanation. Entities: {@code recruits:recruit}, {@code recruits:bowman},
     * {@code recruits:captain}, ... Audited at release 1.15.2 for Forge 1.20.1; it publishes no NeoForge
     * 1.21.1 build.
     */
    public static final String RECRUITS = "recruits";

    /**
     * Villager Workers, by talhanation, which requires Villager Recruits. Entities:
     * {@code workers:farmer}, {@code workers:courier}, ... Audited at release 2.0.3 for Forge 1.20.1.
     */
    public static final String WORKERS = "workers";

    /**
     * Golems Kill Creepers. Never integrated with -- only reported on, so a pack can see that its
     * two gamerules are left exactly as the operator set them.
     */
    public static final String GOLEMS_KILL_CREEPERS = "golemskillcreepers";

    private ModIds() {
    }
}
