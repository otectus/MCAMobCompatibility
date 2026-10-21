package dev.otectus.mcamobcompat.character;

/**
 * Who may open MCA's editor on a bridged character. Cosmetic fields only; age, traits, personality,
 * memories and family links always need an operator, whatever this says.
 */
public enum EditorPermission {

    /** Anyone holding MCA's editor item, which is MCA's own rule for its villagers. */
    ANYONE,

    /** The native owner of an owned actor, anyone for an unowned one (a village guard), and operators. */
    OWNER_OR_UNOWNED,

    /** Operators only. */
    OPERATORS
}
