package dev.otectus.mcamobcompat.mount;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins how the mount policy combines the player-only tag and integrations' refusals. */
class MountPolicyTest {

    @Test
    void anOrdinaryMountIsAllowed() {
        assertTrue(MountPolicy.decide(false, List.of()).allowed());
        assertTrue(MountPolicy.decide(false, List.of(Optional.empty(), Optional.empty())).allowed());
    }

    @Test
    void aPlayerOnlyMountIsRefusedWhateverElseSays() {
        MountDecision decision = MountPolicy.decide(true, List.of(Optional.of("other")));
        assertEquals(MountDecision.refuse(MountPolicy.REASON_PLAYER_ONLY), decision);
    }

    @Test
    void theFirstIntegrationRefusalIsTheReasonGiven() {
        MountDecision decision = MountPolicy.decide(false,
                List.of(Optional.empty(), Optional.of("first"), Optional.of("second")));
        assertEquals(MountDecision.refuse("first"), decision);
    }
}
