package dev.otectus.mcamobcompat.character;

import java.util.Locale;

/**
 * The journal stages of one transition, in order: {@code PREPARED -> ACTOR_READY ->
 * ASSOCIATION_COMMITTED -> CLEANED}, or {@code ROLLED_BACK} from any stage before the commit.
 *
 * <p>Each stage is written to the record before the step it names is taken, and the transaction id
 * travels in both entities' persistent data, so a world loaded after an interruption can tell how far
 * a transition got. See {@link TransitionRecovery} for what each combination of evidence means.
 */
public enum TransitionStage {

    /** Intent and the affected state are recorded; nothing in the world has changed yet. */
    PREPARED,

    /** The new actor exists in the world and passed the native mod's own registration. */
    ACTOR_READY,

    /** The record and both entities name each other. From here the transition only rolls forward. */
    ASSOCIATION_COMMITTED,

    /** Costs, possessions and leftovers are settled. Terminal. */
    CLEANED,

    /** Undone before the commit; only what this transition changed was put back. Terminal. */
    ROLLED_BACK;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Whether nothing further can happen to the transition. */
    public boolean isTerminal() {
        return this == CLEANED || this == ROLLED_BACK;
    }

    /** Whether the association is in place, so recovery completes rather than reverts. */
    public boolean isCommitted() {
        return this == ASSOCIATION_COMMITTED || this == CLEANED;
    }

    public static TransitionStage parse(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            return valueOf(id.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
