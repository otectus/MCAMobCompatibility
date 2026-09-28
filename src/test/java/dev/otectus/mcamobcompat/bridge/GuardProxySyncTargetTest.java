package dev.otectus.mcamobcompat.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the pure half of {@link GuardProxySync#mirrorTargets}: when an attack target another addon set
 * on the hidden proxy may be pushed up to the guard. No entity is needed; the live overload reduces to
 * these four booleans.
 */
class GuardProxySyncTargetTest {

    @Test
    void aPlayerTargetFollowsTheConfigSwitch() {
        assertTrue(GuardProxySync.acceptsOrder(true, false, true, true),
                "followAddonTargets on: MCACrime's pursuit of a wanted player reaches the guard");
        assertFalse(GuardProxySync.acceptsOrder(true, false, false, true),
                "followAddonTargets off: a guard never turns on a player at an addon's request");
    }

    @Test
    void aHostileMobTargetIsAlwaysPushedUp() {
        assertTrue(GuardProxySync.acceptsOrder(false, false, false, true));
        assertTrue(GuardProxySync.acceptsOrder(false, false, true, false));
    }

    @Test
    void anMcaVillagerTargetRespectsFriendlyFirePrevention() {
        assertFalse(GuardProxySync.acceptsOrder(false, true, true, true),
                "friendly-fire prevention would cancel it next tick, so the pair must not churn");
        assertTrue(GuardProxySync.acceptsOrder(false, true, true, false),
                "with friendly-fire prevention off, a thief villager MCACrime names is a lawful target");
    }
}
