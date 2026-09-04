package dev.otectus.mcamobcompat.util;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/**
 * Reads config values without letting a not-yet-loaded spec throw.
 *
 * <p>Server config is world-scoped, so it is genuinely absent on the main menu and during early
 * startup -- and this mod is asked questions from damage handling and classification, either of
 * which can run before the config exists in a malformed setup. Returning the shipped default there
 * is correct; a crash is not (spec section 25: never crash).
 */
public final class ConfigAccess {

    private ConfigAccess() {
    }

    public static boolean bool(ForgeConfigSpec.BooleanValue value, boolean fallback) {
        try {
            Boolean current = value.get();
            return current == null ? fallback : current;
        } catch (IllegalStateException e) {
            return fallback;
        }
    }

    public static List<? extends String> strings(ForgeConfigSpec.ConfigValue<List<? extends String>> value,
                                                 List<? extends String> fallback) {
        try {
            List<? extends String> current = value.get();
            return current == null ? fallback : current;
        } catch (IllegalStateException e) {
            return fallback;
        }
    }

    public static int integer(ForgeConfigSpec.IntValue value, int fallback) {
        try {
            Integer current = value.get();
            return current == null ? fallback : current;
        } catch (IllegalStateException e) {
            return fallback;
        }
    }
}
