package dev.otectus.mcamobcompat.mount;

/**
 * Whether an MCA villager may be put on a mount, and if not, why -- as a translation key, so the
 * player who gave the order can be told in their own language.
 */
public record MountDecision(boolean allowed, String reasonKey) {

    public static final MountDecision ALLOW = new MountDecision(true, null);

    public static MountDecision refuse(String reasonKey) {
        return new MountDecision(false, reasonKey);
    }
}
