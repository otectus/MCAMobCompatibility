package dev.otectus.mcamobcompat.integration;

import dev.otectus.mcamobcompat.util.ModIds;
import net.minecraft.SharedConstants;

import java.util.Optional;
import java.util.Set;

/**
 * Which native-character mods this platform has a verified upstream build of.
 *
 * <p>An adapter whose mod is not listed here is reported unavailable on this platform, with
 * {@link #unavailable} as the reason, and stays off even when a mod with its id is installed:
 * compatibility with a build nobody audited is not claimed. The audited builds themselves are pinned in
 * {@code gradle.properties} and listed with their checksums in
 * {@code docs/NATIVE_CHARACTER_COMPATIBILITY.md}.
 *
 * <p>Forge 1.20.1: Guard Villagers, Villager Recruits and Villager Workers, all audited.
 */
public final class PlatformArtifacts {

    /** The loader this jar is built for, for messages. */
    public static final String LOADER = "Forge";

    private static final Set<String> VERIFIED = Set.of(ModIds.GUARD_VILLAGERS, ModIds.RECRUITS, ModIds.WORKERS);

    private PlatformArtifacts() {
    }

    /** Whether this platform has an audited build of this mod for a character adapter. */
    public static boolean verified(String modId) {
        return VERIFIED.contains(modId);
    }

    /** Why the adapter for this mod is unavailable on this platform, or empty when it is available. */
    public static Optional<String> unavailable(String modId, String displayName) {
        return verified(modId) ? Optional.empty()
                : Optional.of("unavailable on " + LOADER + " " + minecraft() + ": no verified " + displayName
                        + " build exists for this platform");
    }

    private static String minecraft() {
        try {
            return SharedConstants.getCurrentVersion().getName();
        } catch (Throwable t) {
            return "this Minecraft version";
        }
    }
}
