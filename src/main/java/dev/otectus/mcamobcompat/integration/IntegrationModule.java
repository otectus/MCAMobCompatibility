package dev.otectus.mcamobcompat.integration;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.util.ConfigAccess;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.Optional;

/**
 * One optional mod this addon knows how to work with.
 *
 * <p>A module owns three things: whether its mod is installed, whether the operator wants the
 * integration, and the entity namespace it speaks for. Everything else about a mod lives in
 * datapack tags.
 *
 * <p><b>In 0.1.0 no subclass references a single type from the mod it integrates.</b> Guard
 * Villagers is reached by cancelling NeoForge events and reading its item tag by id; SWEM ownership is
 * read through vanilla {@code AbstractHorse}; Genetic Animals and DLO are pure tag data. The
 * subpackages exist because that will not always be true -- when a later version needs to read
 * something only a foreign class can give, this is where the reflective binding goes, and the seam
 * is already drawn around it. {@code NoOptionalModStaticLinkTest} fails the build the moment a
 * foreign type appears anywhere in the jar.
 */
public abstract class IntegrationModule {

    private final String modId;
    private final String namespace;
    private final String displayName;

    private volatile boolean present;

    protected IntegrationModule(String modId, String namespace, String displayName) {
        this.modId = modId;
        this.namespace = namespace;
        this.displayName = displayName;
    }

    /** The mod id, as it appears in the other mod's neoforge.mods.toml. */
    public final String modId() {
        return modId;
    }

    /** The resource namespace this module speaks for, which is normally the mod id. */
    public final String namespace() {
        return namespace;
    }

    /** Human-readable name, for logs and command output. */
    public final String displayName() {
        return displayName;
    }

    /** Whether the mod is installed. Only meaningful after {@link ModIntegrations#init()}. */
    public final boolean isPresent() {
        return present;
    }

    /** Installed, and the operator has not switched the integration off. */
    public final boolean isActive() {
        return present && isEnabled();
    }

    /** Whether the config asks for this integration. Read defensively -- never throws. */
    public abstract boolean isEnabled();

    /** Hook for a module that has real work to do once it is known to be active. */
    protected void onActivated() {
    }

    /** The installed version, when the mod is present and reports one. */
    public final Optional<String> installedVersion() {
        if (!present) {
            return Optional.empty();
        }
        try {
            return ModList.get().getModContainerById(modId)
                    .map(container -> container.getModInfo().getVersion().toString());
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    final void detect() {
        try {
            present = ModList.get().isLoaded(modId);
        } catch (Throwable t) {
            McaMobCompat.LOGGER.warn("Could not check whether {} is loaded; assuming it is not", modId, t);
            present = false;
        }
        if (present && isEnabled()) {
            try {
                onActivated();
            } catch (Throwable t) {
                // One broken module must never take the rest of the mod down with it (spec section 25).
                McaMobCompat.LOGGER.error("{} integration failed to activate; continuing without it",
                        displayName, t);
            }
        }
    }

    /**
     * Reads a boolean config value without letting a not-yet-loaded spec throw. See
     * {@link ConfigAccess}; the default passed in is what the option ships as, so an early answer
     * matches the eventual one for anyone who has not changed it.
     */
    protected static boolean safeBoolean(ModConfigSpec.BooleanValue value, boolean fallback) {
        return ConfigAccess.bool(value, fallback);
    }
}
