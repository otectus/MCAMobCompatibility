package dev.otectus.mcamobcompat.character;

/**
 * What happens when Villager Recruits' own hiring (a noble's hire screen) picks an MCA villager to
 * turn into a recruit or a worker. Recruits' conversion would discard the villager -- family, genes
 * and all -- so it never runs on an MCA villager either way.
 */
public enum RecruitsConversionPolicy {

    /**
     * The same person is enlisted: a real recruit or worker is hired through Recruits' own payment and
     * ownership, and the villager becomes its hidden MCA identity instead of being discarded.
     */
    ENLIST,

    /** The hire is refused before anything is paid, with a message. */
    BLOCK
}
