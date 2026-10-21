package dev.otectus.mcamobcompat.character;

import org.junit.jupiter.api.Test;

import static dev.otectus.mcamobcompat.character.TransitionRecovery.Action.IGNORE_STALE;
import static dev.otectus.mcamobcompat.character.TransitionRecovery.Action.NONE;
import static dev.otectus.mcamobcompat.character.TransitionRecovery.Action.QUARANTINE;
import static dev.otectus.mcamobcompat.character.TransitionRecovery.Action.ROLL_FORWARD;
import static dev.otectus.mcamobcompat.character.TransitionRecovery.Comparison.ENTITY_NEWER;
import static dev.otectus.mcamobcompat.character.TransitionRecovery.Comparison.SAME;
import static dev.otectus.mcamobcompat.character.TransitionRecovery.Comparison.STORE_NEWER;
import static dev.otectus.mcamobcompat.character.TransitionRecovery.Comparison.UNSTAMPED;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Fault injection for every journal stage and every save boundary, as decisions: what recovery does
 * with each combination of evidence an unclean stop can leave behind.
 */
class TransitionRecoveryTest {

    @Test
    void anOpenTransitionSettlesForwardOnlyOnceCommitted() {
        assertEquals(TransitionStage.ROLLED_BACK, TransitionRecovery.settle(TransitionStage.PREPARED));
        assertEquals(TransitionStage.ROLLED_BACK, TransitionRecovery.settle(TransitionStage.ACTOR_READY));
        assertEquals(TransitionStage.CLEANED, TransitionRecovery.settle(TransitionStage.ASSOCIATION_COMMITTED));
        assertEquals(TransitionStage.CLEANED, TransitionRecovery.settle(TransitionStage.CLEANED));
        assertEquals(TransitionStage.ROLLED_BACK, TransitionRecovery.settle(TransitionStage.ROLLED_BACK));
    }

    @Test
    void generationsCompare() {
        assertEquals(UNSTAMPED, TransitionRecovery.compare(3, 0), "A 0.2 entity carries no stamp.");
        assertEquals(SAME, TransitionRecovery.compare(2, 2));
        assertEquals(STORE_NEWER, TransitionRecovery.compare(3, 2));
        assertEquals(ENTITY_NEWER, TransitionRecovery.compare(2, 3));
    }

    @Test
    void aPairTheStoreAlreadyRecordsNeedsNothing() {
        assertEquals(NONE, TransitionRecovery.forLinkedActor(true, true, false, SAME));
    }

    /** The store's save was lost after a commit; both entities were saved with it. */
    @Test
    void aCommitTheStoreLostRollsForward() {
        assertEquals(ROLL_FORWARD, TransitionRecovery.forLinkedActor(false, true, false, UNSTAMPED));
    }

    /** A promotion committed, the store kept the old actor's record, the new actor saw the commit. */
    @Test
    void aReplacementTheStoreLostRollsForwardToTheNewerActor() {
        assertEquals(ROLL_FORWARD, TransitionRecovery.forLinkedActor(false, true, true, ENTITY_NEWER));
    }

    /** The old actor of a promotion reappears because its removal was never saved. */
    @Test
    void aStaleActorTheIdentityNoLongerNamesIsLeftAlone() {
        assertEquals(IGNORE_STALE, TransitionRecovery.forLinkedActor(false, false, true, STORE_NEWER));
        assertEquals(IGNORE_STALE, TransitionRecovery.forLinkedActor(false, false, false, UNSTAMPED));
    }

    /** The identity names this actor, but the store gives it to another at the same or a newer generation. */
    @Test
    void contradictoryEvidenceIsQuarantinedNotGuessed() {
        assertEquals(QUARANTINE, TransitionRecovery.forLinkedActor(false, true, true, SAME));
        assertEquals(QUARANTINE, TransitionRecovery.forLinkedActor(false, true, true, STORE_NEWER));
        assertEquals(QUARANTINE, TransitionRecovery.forLinkedActor(false, true, true, UNSTAMPED));
    }
}
