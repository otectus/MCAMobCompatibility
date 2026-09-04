package dev.otectus.mcamobcompat.classification;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.api.ClassificationProvider;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The registered {@link ClassificationProvider}s, in registration order.
 *
 * <p>Copy-on-write because registration happens once during setup and reads happen on every
 * classification of a live entity, from event handlers on the server thread.
 */
public final class ProviderRegistry {

    private static final CopyOnWriteArrayList<ClassificationProvider> PROVIDERS = new CopyOnWriteArrayList<>();

    private ProviderRegistry() {
    }

    /** Adds a provider. Duplicate instances are ignored so a double setup cannot double-classify. */
    public static void register(ClassificationProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("provider must not be null");
        }
        if (PROVIDERS.addIfAbsent(provider)) {
            McaMobCompat.LOGGER.debug("Registered classification provider {}", provider.getClass().getName());
        }
    }

    public static List<ClassificationProvider> providers() {
        return PROVIDERS;
    }

    public static int count() {
        return PROVIDERS.size();
    }

    /** Test hook: drops every provider so each test starts from a known state. */
    public static void clear() {
        PROVIDERS.clear();
    }
}
