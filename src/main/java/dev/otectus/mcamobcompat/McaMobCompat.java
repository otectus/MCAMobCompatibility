package dev.otectus.mcamobcompat;

import com.mojang.logging.LogUtils;
import dev.otectus.mcamobcompat.api.MobCompatibilityAPI;
import dev.otectus.mcamobcompat.classification.ClassificationCache;
import dev.otectus.mcamobcompat.classification.TamedMountProvider;
import dev.otectus.mcamobcompat.integration.ModIntegrations;
import dev.otectus.mcamobcompat.mca.McaBinding;
import dev.otectus.mcamobcompat.util.ConfigAccess;
import dev.otectus.mcamobcompat.util.ModIds;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

import org.slf4j.Logger;

/**
 * Entrypoint for MCA: Mob Compatibility.
 *
 * <p>A recognition layer, not a behaviour layer. It sorts entities into semantic roles using
 * datapack tags so that MCA and mods that add or replace animals, mounts, livestock and guards can
 * coexist -- and it blocks the one interaction that would destroy MCA data.
 *
 * <p>The architectural rule the whole mod rests on: <b>no class in this jar references a type from
 * MCA or from any optional mod, except for dot-form string literals in {@code mca.McaBinding}.</b>
 * Entity identification uses entity-type registry ids, guards are found through tags and commanded
 * through vanilla {@code Mob.setTarget}, and SWEM ownership is read through vanilla
 * {@code AbstractHorse}. There is no mixin, and no way to get a {@code NoClassDefFoundError} out of
 * this mod without breaking this rule -- {@code NoOptionalModStaticLinkTest} and
 * {@code checkJarContents} fail the build if that ever stops being true.
 */
@Mod(McaMobCompat.MOD_ID)
public final class McaMobCompat {

    public static final String MOD_ID = "mcamobcompat";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** A {@link ResourceLocation} in this mod's namespace. */
    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    /**
     * Logs only when {@code general.debugLogging} is on.
     *
     * <p>The classification and defence paths run on discrete events but common ones, so these
     * messages would be genuine spam at info level. The {@code /mcamobcompat} command gives the same
     * information on demand, which is what most people actually want.
     */
    public static void debug(String message, Object... args) {
        if (ConfigAccess.bool(McaMobCompatConfig.COMMON.debugLogging, false)) {
            LOGGER.info(message, args);
        }
    }

    public McaMobCompat(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, McaMobCompatConfig.COMMON_SPEC);
        container.registerConfig(ModConfig.Type.SERVER, McaMobCompatConfig.SERVER_SPEC);

        modBus.addListener(this::onCommonSetup);
        modBus.addListener(this::onConfigReload);
        modBus.addListener((ModConfigEvent.Loading event) -> invalidateConfig());
        modBus.addListener((ModConfigEvent.Unloading event) -> invalidateConfig());

        LOGGER.info("MCA: Mob Compatibility initialising (mod id '{}')", MOD_ID);
    }

    /**
     * A config change can turn an integration module on or off, and that decision is baked into the
     * cached classification of every entity type in that mod's namespace. Drop the cache so the next
     * lookup reflects the new setting.
     */
    private void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == McaMobCompatConfig.COMMON_SPEC
                || event.getConfig().getSpec() == McaMobCompatConfig.SERVER_SPEC) {
            invalidateConfig();
        }
    }

    private static void invalidateConfig() {
        ClassificationCache.invalidate();
        dev.otectus.mcamobcompat.event.ChoreSafetyHandler.invalidateConfig();
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        // Inside enqueueWork: every mod has finished loading by now, so ModList is authoritative.
        event.enqueueWork(() -> {
            ModIntegrations.init();

            // Reflective, string-only, and optional: nothing else in this mod depends on it binding.
            McaBinding.resolve();
            LOGGER.info("MCA binding: root {}, MCA {}, members {}.",
                    McaBinding.packageRoot().orElse("not found"),
                    ModList.get().getModContainerById(ModIds.MCA)
                            .map(container -> container.getModInfo().getVersion().toString())
                            .orElse("absent"),
                    McaBinding.describeBindings());

            // A tamed horse is a mount whatever mod it came from -- a fact about the animal rather
            // than about its type, so no tag can carry it. Registered unconditionally because it is
            // built on vanilla AbstractHorse and is just as true without SWEM installed.
            MobCompatibilityAPI.registerProvider(TamedMountProvider.INSTANCE);

            LOGGER.info("MCA: Mob Compatibility ready. MCA villagers recognised as {}.",
                    dev.otectus.mcamobcompat.mca.McaEntities.villagerIds());
        });
    }
}
