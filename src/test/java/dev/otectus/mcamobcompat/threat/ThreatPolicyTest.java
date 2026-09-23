package dev.otectus.mcamobcompat.threat;

import dev.otectus.mcamobcompat.threat.ThreatPolicy.Facts;
import dev.otectus.mcamobcompat.threat.ThreatPolicy.Response;
import dev.otectus.mcamobcompat.threat.ThreatPolicy.Settings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins the order of {@link ThreatPolicy}'s rules; see its javadoc for the contract. */
class ThreatPolicyTest {

    private static final Settings DEFAULTS = new Settings(8.0D, 4.0D, true);

    private static Facts civilian(double distance, boolean visible, boolean lit, boolean powered, boolean hunting) {
        return new Facts(distance, visible, lit, powered, hunting, false, false, 1.0F);
    }

    private static Facts guard(double distance, boolean lit, boolean powered, boolean hunting, float health) {
        return new Facts(distance, true, lit, powered, hunting, true, false, health);
    }

    @Test
    void aThreatOutOfRangeIsIgnored() {
        assertEquals(Response.IGNORE, ThreatPolicy.decide(civilian(9.0D, true, true, true, true), DEFAULTS));
    }

    @Test
    void anUnseenThreatIsIgnoredUnlessItsFuseIsBurningNearby() {
        assertEquals(Response.IGNORE, ThreatPolicy.decide(civilian(3.0D, false, false, false, true), DEFAULTS));
        assertEquals(Response.FLEE, ThreatPolicy.decide(civilian(3.0D, false, true, false, false), DEFAULTS),
                "A hissing creeper on the other side of a wall is still heard.");
        assertEquals(Response.IGNORE, ThreatPolicy.decide(civilian(7.0D, false, true, false, false), DEFAULTS));
    }

    @Test
    void aBurningFuseCloseByMakesEvenAGuardRun() {
        assertEquals(Response.FLEE, ThreatPolicy.decide(guard(4.5D, true, false, false, 1.0F), DEFAULTS));
    }

    @Test
    void aChargedCreeperIsDeadlyTwiceAsFarAway() {
        assertEquals(Response.ENGAGE, ThreatPolicy.decide(guard(7.0D, true, false, false, 1.0F), DEFAULTS),
                "An ordinary creeper seven blocks off is still a fight.");
        assertEquals(Response.FLEE, ThreatPolicy.decide(guard(7.0D, true, true, false, 1.0F), DEFAULTS),
                "A charged one is not.");
    }

    @Test
    void aHealthyGuardEngagesAndAnInjuredOneDoesNot() {
        assertEquals(Response.ENGAGE, ThreatPolicy.decide(guard(6.0D, false, false, true, 1.0F), DEFAULTS));
        assertEquals(Response.FLEE, ThreatPolicy.decide(guard(6.0D, false, false, true, 0.2F), DEFAULTS));
        assertEquals(Response.WATCH, ThreatPolicy.decide(guard(6.0D, false, false, false, 0.2F), DEFAULTS));
    }

    @Test
    void guardsRunLikeEveryoneElseWhenEngagingIsOff() {
        Settings noFighting = new Settings(8.0D, 4.0D, false);
        assertEquals(Response.FLEE, ThreatPolicy.decide(guard(6.0D, false, false, true, 1.0F), noFighting));
    }

    @Test
    void aCivilianRunsFromAHunterOrACloseThreatAndWatchesAFarOne() {
        assertEquals(Response.FLEE, ThreatPolicy.decide(civilian(7.0D, true, false, false, true), DEFAULTS));
        assertEquals(Response.FLEE, ThreatPolicy.decide(civilian(3.5D, true, false, false, false), DEFAULTS));
        assertEquals(Response.FLEE, ThreatPolicy.decide(civilian(7.0D, true, true, false, false), DEFAULTS));
        assertEquals(Response.WATCH, ThreatPolicy.decide(civilian(7.0D, true, false, false, false), DEFAULTS));
    }

    @Test
    void aChildNeverFights() {
        Facts childGuard = new Facts(6.0D, true, false, false, true, true, true, 1.0F);
        assertEquals(Response.FLEE, ThreatPolicy.decide(childGuard, DEFAULTS));
    }
}
