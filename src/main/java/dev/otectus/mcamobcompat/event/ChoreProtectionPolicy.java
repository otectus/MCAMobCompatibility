package dev.otectus.mcamobcompat.event;

import dev.otectus.mcamobcompat.api.MobRole;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Decides whether an MCA villager's chore is allowed to hit a given animal.
 *
 * <p>Deliberately free of every Minecraft type. The gathering of the facts -- roles, ownership,
 * name, lead, who is targeting whom -- is the event handler's job; the decision itself is plain
 * Java, so it is pinned by a unit test that needs no bootstrapped registry and no MCA on the
 * classpath. {@link ChoreSafetyHandler} is the only caller.
 *
 * <p>Chore context is not an input. Protected animals are passive, so an MCA villager has no
 * legitimate reason to hit one at all except self-defence, which {@code victimAlreadyTargetsAttacker}
 * covers. Skipping the context costs nothing and keeps chore safety working even if MCA renames its
 * packages again and the reflective binding goes dark.
 */
public final class ChoreProtectionPolicy {

    private ChoreProtectionPolicy() {
    }

    /**
     * Turns the configured role names into roles.
     *
     * <p>Matching is case-insensitive, so {@code Mount}, {@code MOUNT} and {@code mount} are the
     * same entry -- the config comment names the lower-case tag paths, the enum constants are upper
     * case, and a pack author should not have to know which one this reads. Anything that is not a
     * role is handed to {@code onUnknown} and dropped; the caller decides how loud that is, and is
     * expected to do it once per config load rather than once per event.
     */
    public static Set<MobRole> parseRoles(Collection<? extends String> names, Consumer<String> onUnknown) {
        EnumSet<MobRole> parsed = EnumSet.noneOf(MobRole.class);
        if (names == null) {
            return parsed;
        }
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String trimmed = name.trim();
            MobRole match = null;
            for (MobRole role : MobRole.VALUES) {
                if (role.path().equalsIgnoreCase(trimmed) || role.name().equalsIgnoreCase(trimmed)) {
                    match = role;
                    break;
                }
            }
            if (match == null) {
                onUnknown.accept(trimmed);
            } else {
                parsed.add(match);
            }
        }
        return parsed;
    }

    /**
     * Whether this animal must be left alone.
     *
     * @param roles                       every role the victim holds
     * @param owned                       the victim reports an owner through vanilla
     * @param named                       the victim carries a custom name
     * @param leashed                     the victim is on a lead
     * @param victimAlreadyTargetsAttacker the victim is already attacking the villager
     * @param protectedRoles              the parsed {@code chores.protectedRoles} option
     * @param protectOwnedAnimals         {@code chores.protectOwnedAnimals}
     * @param protectNamedAnimals         {@code chores.protectNamedAnimals}
     * @param protectLeashedAnimals       {@code chores.protectLeashedAnimals}
     */
    public static boolean shouldProtect(Set<MobRole> roles,
                                        boolean owned,
                                        boolean named,
                                        boolean leashed,
                                        boolean victimAlreadyTargetsAttacker,
                                        Set<MobRole> protectedRoles,
                                        boolean protectOwnedAnimals,
                                        boolean protectNamedAnimals,
                                        boolean protectLeashedAnimals) {
        // Self-defence exemption. If the animal is already attacking the villager, protecting it
        // would hand a hostile mob a free hit -- and a pack is free to tag anything PET, including
        // something that fights back. Being on the protected list buys immunity from being hunted,
        // not immunity from being hit back.
        if (victimAlreadyTargetsAttacker) {
            return false;
        }
        if (protectOwnedAnimals && owned) {
            return true;
        }
        if (protectNamedAnimals && named) {
            return true;
        }
        if (protectLeashedAnimals && leashed) {
            return true;
        }
        if (roles == null || protectedRoles == null || protectedRoles.isEmpty()) {
            return false;
        }
        for (MobRole role : protectedRoles) {
            if (roles.contains(role)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a blocked attack should also end the chore.
     *
     * <p>Tier 2. Blocking alone leaves MCA's hunting task holding a target it can never kill, so the
     * villager swings at it forever; this is what breaks that loop. Only meaningful while the
     * villager is actually hunting.
     */
    public static boolean shouldAbandonHunt(boolean protectedTarget,
                                            boolean choreIsHunt,
                                            boolean abandonHuntOnProtectedTarget) {
        return protectedTarget && choreIsHunt && abandonHuntOnProtectedTarget;
    }
}
