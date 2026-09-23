package dev.otectus.mcamobcompat.util;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads config values without letting a not-yet-loaded spec throw.
 *
 * <p>Server config is world-scoped, so it is genuinely absent on the main menu and during early
 * startup -- and this mod is asked questions from damage handling and classification, either of
 * which can run before the config exists in a malformed setup. Returning the shipped default there
 * is correct; a crash is not (spec section 25: never crash).
 *
 * <p>{@link #override} exists for GameTests. Setting a {@code ModConfigSpec} value writes the
 * file, and NeoForge's watcher then reloads that file on its own thread while the server keeps
 * reading; mid-reload a read can briefly see no value and fall back to the default. A test that
 * flips an option that way is racing the watcher. An override is confined to the calling thread
 * and to one synchronous block, touches no file, and is empty in normal play.
 */
public final class ConfigAccess {

    private static final ThreadLocal<Map<ModConfigSpec.ConfigValue<?>, Object>> OVERRIDES =
            ThreadLocal.withInitial(IdentityHashMap::new);

    private ConfigAccess() {
    }

    public static boolean bool(ModConfigSpec.BooleanValue value, boolean fallback) {
        Object forced = OVERRIDES.get().get(value);
        if (forced instanceof Boolean flag) {
            return flag;
        }
        try {
            Boolean current = value.get();
            return current == null ? fallback : current;
        } catch (IllegalStateException e) {
            return fallback;
        }
    }

    public static List<? extends String> strings(ModConfigSpec.ConfigValue<List<? extends String>> value,
                                                 List<? extends String> fallback) {
        try {
            List<? extends String> current = value.get();
            return current == null ? fallback : current;
        } catch (IllegalStateException e) {
            return fallback;
        }
    }

    public static int integer(ModConfigSpec.IntValue value, int fallback) {
        Object forced = OVERRIDES.get().get(value);
        if (forced instanceof Integer number) {
            return number;
        }
        try {
            Integer current = value.get();
            return current == null ? fallback : current;
        } catch (IllegalStateException e) {
            return fallback;
        }
    }

    public static <E extends Enum<E>> E enumValue(ModConfigSpec.EnumValue<E> value, E fallback) {
        Object forced = OVERRIDES.get().get(value);
        if (fallback.getDeclaringClass().isInstance(forced)) {
            return fallback.getDeclaringClass().cast(forced);
        }
        try {
            E current = value.get();
            return current == null ? fallback : current;
        } catch (IllegalStateException e) {
            return fallback;
        }
    }

    /**
     * Runs {@code body} on this thread with {@code value} reading as {@code replacement}, then
     * restores whatever was in force before. For GameTests; gameplay never calls it.
     */
    public static <T> void override(ModConfigSpec.ConfigValue<T> value, T replacement, Runnable body) {
        Map<ModConfigSpec.ConfigValue<?>, Object> overrides = OVERRIDES.get();
        boolean had = overrides.containsKey(value);
        Object previous = overrides.put(value, replacement);
        try {
            body.run();
        } finally {
            if (had) {
                overrides.put(value, previous);
            } else {
                overrides.remove(value);
            }
        }
    }
}
