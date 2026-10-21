package dev.otectus.mcamobcompat.character;

/**
 * How clients draw a bridged actor. The server decides per adapter; a client may still fall back to
 * {@link #NATIVE} on its own preference, and does so whenever the MCA appearance cannot be drawn.
 */
public enum AppearanceMode {

    /** The actor is drawn as its MCA person: face, skin, hair, clothes and genetics, in its own pose. */
    MCA,

    /** The actor is drawn by its own mod's renderer, exactly as without this mod. */
    NATIVE
}
