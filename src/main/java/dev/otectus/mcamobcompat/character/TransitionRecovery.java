package dev.otectus.mcamobcompat.character;

/**
 * What to do with evidence of a transition that may not have finished, decided without touching the
 * world: pure, and pinned by {@code TransitionRecoveryTest} for every stage.
 *
 * <p><b>The guarantee, stated exactly.</b> A transition runs within one server tick, and Minecraft
 * never saves in the middle of a tick, so a single save is never half a transition. What Minecraft
 * does not do is save everything at once: the store, each entity's chunk and each player's inventory
 * are written at different times, so after an unclean stop they may each be from either side of a
 * transition. This mod therefore never relies on the store alone: the transaction id and the
 * association generation are stamped on both entities, the store is authoritative for which actor is
 * current, and recovery rolls forward wherever both sides agree a commit happened. It does not
 * restore inventories and never refunds or charges on its own: a payment's fate after an unclean stop
 * is not knowable from the world, and a guess would duplicate or delete items.
 */
public final class TransitionRecovery {

    private TransitionRecovery() {
    }

    /** How an entity's stamped generation relates to the store's for the same character. */
    public enum Comparison {
        /** Both name the same association. */
        SAME,
        /** The store moved on (a replacement committed); the entity is a stale actor from an unsaved removal. */
        STORE_NEWER,
        /** The entity saw a commit the store never saved; the store rolls forward to it. */
        ENTITY_NEWER,
        /** The entity carries no stamp, as every entity from before 0.3.0 does. */
        UNSTAMPED
    }

    /** What to do about one pair the store and an entity disagree on. */
    public enum Action {
        /** Nothing is wrong. */
        NONE,
        /** Record the pair the entities agree on: they saw a commit the store lost. */
        ROLL_FORWARD,
        /** Leave this actor alone and unbridged: a newer actor carries the character now. */
        IGNORE_STALE,
        /** Two records want the same person. Preserve both; an operator decides. */
        QUARANTINE
    }

    /** The terminal stage an open transition found at start-up settles to. */
    public static TransitionStage settle(TransitionStage open) {
        if (open == null || open.isTerminal()) {
            return open;
        }
        return open.isCommitted() ? TransitionStage.CLEANED : TransitionStage.ROLLED_BACK;
    }

    public static Comparison compare(int storeGeneration, int entityGeneration) {
        if (entityGeneration <= 0) {
            return Comparison.UNSTAMPED;
        }
        if (storeGeneration == entityGeneration) {
            return Comparison.SAME;
        }
        return storeGeneration > entityGeneration ? Comparison.STORE_NEWER : Comparison.ENTITY_NEWER;
    }

    /**
     * An actor whose persistent data names an identity, checked against the identity and the store.
     * The identity is the authority on whom it belongs to: it is the person, and it is stamped in the
     * same tick as its actor.
     *
     * @param recordLinksPair     the store has a record keyed by this actor naming this identity
     * @param identityNamesActor  the identity's own persistent data names this actor
     * @param otherRecord         some <em>other</em> actor's record names this identity
     * @param comparison          this actor's stamped generation against that other record's
     */
    public static Action forLinkedActor(boolean recordLinksPair, boolean identityNamesActor, boolean otherRecord,
                                        Comparison comparison) {
        if (recordLinksPair) {
            return Action.NONE;
        }
        if (!identityNamesActor) {
            // The person names some other body: this one is a stale copy from an unsaved removal.
            return Action.IGNORE_STALE;
        }
        if (!otherRecord) {
            return Action.ROLL_FORWARD;
        }
        return comparison == Comparison.ENTITY_NEWER ? Action.ROLL_FORWARD : Action.QUARANTINE;
    }
}
