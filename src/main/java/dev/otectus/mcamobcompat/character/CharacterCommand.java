package dev.otectus.mcamobcompat.character;

import java.util.UUID;

/**
 * An order a character can be given from outside its native mod: MCA's Follow, Stay and Move
 * buttons, or a role command. Always carried out through the adapter, which applies the native
 * mod's own ownership and permission rules; an order the actor's native controller would refuse is
 * refused here too.
 */
public enum CharacterCommand {

    /** Follow the initiating player. */
    FOLLOW,
    /** Hold the current position. */
    STAY,
    /** Release to the native default: wander, patrol, or get back to work. */
    MOVE;

    /** Where an order came from, so the adapter can apply the right rules. */
    public enum Source {
        /** MCA's own interaction screen, read from the identity's movement state. */
        MCA_SCREEN,
        /** A command or a role action. */
        COMMAND
    }

    /**
     * Who gave an order, from where, and which one it was. {@code initiator} may be null for an
     * order MCA recorded without a player (an add-on's), which an owned actor then refuses.
     */
    public record Context(Source source, UUID initiator, long sequence) {
    }

    /** The outcome, with a translatable reason for anything but {@link Outcome#APPLIED}. */
    public record Result(Outcome outcome, String reasonKey) {

        public static final Result APPLIED = new Result(Outcome.APPLIED, "");

        public static Result refused(String reasonKey) {
            return new Result(Outcome.REFUSED, reasonKey);
        }

        public static Result unsupported(String reasonKey) {
            return new Result(Outcome.UNSUPPORTED, reasonKey);
        }

        public boolean applied() {
            return outcome == Outcome.APPLIED;
        }
    }

    public enum Outcome {
        APPLIED,
        /** The native rules said no: not the owner, hostile, restrained. */
        REFUSED,
        /** This adapter has no native operation for it, or its binding is missing. */
        UNSUPPORTED
    }
}
