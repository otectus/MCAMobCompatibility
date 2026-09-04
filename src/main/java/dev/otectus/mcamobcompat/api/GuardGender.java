package dev.otectus.mcamobcompat.api;

/**
 * The gender of a bridged guard's identity.
 *
 * <p>Two values, matching what MCA itself models for a villager, and rolled 50/50 from the guard's
 * UUID rather than read from MCA -- nothing in this jar may name an MCA type. It exists so a
 * sibling addon can pick gendered dialogue for a guard the same way it does for a villager.
 */
public enum GuardGender {

    MALE,
    FEMALE;

    /** The lowercase name, which is how the gender is persisted and how dialogue keys spell it. */
    public String id() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
