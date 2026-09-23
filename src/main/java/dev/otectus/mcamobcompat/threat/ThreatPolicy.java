package dev.otectus.mcamobcompat.threat;

/**
 * Decides how an MCA villager answers one nearby threat.
 *
 * <p>Free of every Minecraft type, like {@code ChoreProtectionPolicy}: the handler gathers the
 * facts, this decides, and a unit test pins the decision without a server.
 *
 * <p>The order is the contract:
 * <ol>
 *   <li>Out of range: ignore it.</li>
 *   <li>Out of sight: ignore it -- unless its fuse is lit close by, which you can hear through a
 *       wall. No omniscient reactions to a creeper on the far side of a hill.</li>
 *   <li>A lit fuse within blast reach: everyone runs, guards included. A charged creeper's reach is
 *       doubled, as its explosion is.</li>
 *   <li>A healthy adult MCA guard or archer engages, when the operator allows it.</li>
 *   <li>Anyone else runs from a lit fuse, from a threat that is coming for them or someone next to
 *       them, or from one inside half the alert range.</li>
 *   <li>Otherwise: keep an eye on it.</li>
 * </ol>
 */
public final class ThreatPolicy {

    /** How low a combatant's health may fall before it runs instead of fighting. */
    public static final float ENGAGE_HEALTH_FLOOR = 0.35F;

    /** The answers, in increasing urgency. */
    public enum Response { IGNORE, WATCH, ENGAGE, FLEE }

    /**
     * What the handler observed about one threat.
     *
     * @param distance       blocks between the villager and the threat
     * @param visible        the villager has line of sight to it
     * @param fuseLit        a creeper's fuse is burning (swelling, or ignited by flint and steel)
     * @param powered        a charged creeper, whose explosion is twice as large
     * @param hunting        its target is this villager or someone within a few blocks of it
     * @param combatant      the villager is an MCA guard or archer
     * @param child          the villager is a child
     * @param healthFraction the villager's health over its maximum
     */
    public record Facts(double distance, boolean visible, boolean fuseLit, boolean powered, boolean hunting,
                        boolean combatant, boolean child, float healthFraction) {
    }

    /**
     * The operator's tuning.
     *
     * @param alertRange        how far a villager notices a threat
     * @param swellingFleeRange how close a lit fuse must be before everyone runs
     * @param combatantsEngage  whether MCA guards and archers fight instead of running
     */
    public record Settings(double alertRange, double swellingFleeRange, boolean combatantsEngage) {
    }

    private ThreatPolicy() {
    }

    /** How far a lit fuse is treated as deadly. */
    public static double blastReach(boolean powered, double swellingFleeRange) {
        return powered ? swellingFleeRange * 2.0D : swellingFleeRange;
    }

    /** Whether this threat is about to go off close enough to matter: the emergency case. */
    public static boolean deadly(Facts facts, Settings settings) {
        return facts.fuseLit() && facts.distance() <= blastReach(facts.powered(), settings.swellingFleeRange()) + 1.0D;
    }

    public static Response decide(Facts facts, Settings settings) {
        if (facts.distance() > settings.alertRange()) {
            return Response.IGNORE;
        }
        boolean deadly = deadly(facts, settings);
        if (!facts.visible()) {
            return deadly ? Response.FLEE : Response.IGNORE;
        }
        if (deadly) {
            return Response.FLEE;
        }
        if (facts.combatant() && !facts.child() && settings.combatantsEngage()
                && facts.healthFraction() >= ENGAGE_HEALTH_FLOOR) {
            return Response.ENGAGE;
        }
        if (facts.fuseLit() || facts.hunting() || facts.distance() <= settings.alertRange() / 2.0D) {
            return Response.FLEE;
        }
        return Response.WATCH;
    }
}
