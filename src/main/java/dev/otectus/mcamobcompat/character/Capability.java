package dev.otectus.mcamobcompat.character;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * What an adapter can actually do for one actor on the installed build. Reported by
 * {@code /mcamobcompat characters adapters} and sent to clients as a bit mask, so a capability a
 * binding failed to reach is visibly missing rather than silently broken.
 */
public enum Capability {

    /** The actor gets a persistent MCA identity. */
    IDENTITY,
    /** A plain click opens MCA's own interaction screen for the identity. */
    MCA_INTERACTION,
    /** Clients may draw the actor with the identity's MCA appearance. */
    MCA_APPEARANCE,
    /** MCA's editor item opens MCA's editor for the identity. */
    EDITOR,
    /** The native mod's own inventory or management screen is reachable. */
    NATIVE_INTERFACE,
    /** MCA's Follow is carried out through the native mod's own follow order. */
    FOLLOW,
    /** MCA's Stay is carried out through the native mod's own hold order. */
    STAY,
    /** MCA's Move releases the actor to its native default (wander, patrol, work). */
    MOVE,
    /** An existing MCA adult can take this native role, keeping the same person. */
    ENLIST,
    /** An enlisted person can go back to civilian life. */
    RETURN_TO_CIVILIAN,
    /** The native mod's replacement of the actor (a promotion) keeps the same character. */
    HOST_REPLACEMENT,
    /** A target an MCA add-on sets on the identity is carried out by the actor. */
    ADDON_TARGETS,
    /** The identity is recognised as law enforcement by MCA's guard profession. */
    LAW_ENFORCEMENT;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The capabilities as a bit mask, for the network. */
    public static int mask(Set<Capability> capabilities) {
        int mask = 0;
        for (Capability capability : capabilities) {
            mask |= 1 << capability.ordinal();
        }
        return mask;
    }

    /** The bit mask back as a set; unknown bits from a newer server are ignored. */
    public static Set<Capability> fromMask(int mask) {
        EnumSet<Capability> set = EnumSet.noneOf(Capability.class);
        for (Capability capability : values()) {
            if ((mask & (1 << capability.ordinal())) != 0) {
                set.add(capability);
            }
        }
        return set;
    }
}
