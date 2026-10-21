package dev.otectus.mcamobcompat;

import com.mojang.logging.LogUtils;
import dev.otectus.mcamobcompat.api.MobCompatibilityAPI;
import dev.otectus.mcamobcompat.character.NativeCharacterAdapter;
import dev.otectus.mcamobcompat.character.NativeCharacterAdapters;
import dev.otectus.mcamobcompat.classification.ClassificationCache;
import dev.otectus.mcamobcompat.classification.OwnedAnimalProvider;
import dev.otectus.mcamobcompat.classification.TamedMountProvider;
import dev.otectus.mcamobcompat.event.ChoreSafetyHandler;
import dev.otectus.mcamobcompat.hook.Hooks;
import dev.otectus.mcamobcompat.integration.ModIntegrations;
import dev.otectus.mcamobcompat.integration.recruits.RecruitsEvents;
import dev.otectus.mcamobcompat.mca.McaBinding;
import dev.otectus.mcamobcompat.network.ProxyPairs;
import dev.otectus.mcamobcompat.util.ConfigAccess;
import dev.otectus.mcamobcompat.util.ModIds;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
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
 * Entity identification uses entity-type registry ids, MCA's brain memories are reached by registry
 * id, guards are found through tags and commanded through vanilla {@code Mob.setTarget}, and
 * ownership is read through vanilla {@code AbstractHorse} and {@code OwnableEntity}. Optional mods'
 * own members are reached by name on their runtime objects only, never by naming a class. There is
 * no mixin, and no way to get a {@code NoClassDefFoundError} out of this mod without breaking this
 * rule -- {@code NoOptionalModStaticLinkTest} and {@code checkJarContents} fail the build if that
 * ever stops being true.
 */
@Mod(McaMobCompat.MOD_ID)
public final class McaMobCompat {

    public static final String MOD_ID = "mcamobcompat";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** A {@link ResourceLocation} in this mod's namespace. */
    public static ResourceLocation id(String path) {
        return location(MOD_ID, path);
    }

    /**
     * A {@link ResourceLocation} in any namespace. The one place this mod builds one, because the
     * factory is spelled differently on each loader's Minecraft version; everything else calls this.
     */
    public static ResourceLocation location(String namespace, String path) {
        return new ResourceLocation(namespace, path);
    }

    /**
     * Posts one of this mod's events on the game bus. Returns whether a listener cancelled it --
     * false for an event that cannot be cancelled.
     */
    public static boolean post(Event event) {
        return MinecraftForge.EVENT_BUS.post(event);
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

    public McaMobCompat() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, McaMobCompatConfig.COMMON_SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, McaMobCompatConfig.SERVER_SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, McaMobCompatConfig.CLIENT_SPEC);

        final IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::onCommonSetup);
        modBus.addListener(this::onConfigReload);
        // A config that loads for the first time, or unloads with a world, changes what the cached
        // answers were computed from just as a reload does.
        modBus.addListener((ModConfigEvent.Loading event) -> onConfigChanged(event.getConfig().getSpec()));
        modBus.addListener((ModConfigEvent.Unloading event) -> onConfigChanged(event.getConfig().getSpec()));

        // The client learns which villagers are hidden proxies from this channel alone.
        ProxyPairs.register();

        LOGGER.info("MCA: Mob Compatibility initialising (mod id '{}')", MOD_ID);
    }

    /**
     * A config change can turn an integration module on or off, and that decision is baked into the
     * cached classification of every entity type in that mod's namespace. Drop the cache so the next
     * lookup reflects the new setting.
     */
    private void onConfigReload(ModConfigEvent.Reloading event) {
        onConfigChanged(event.getConfig().getSpec());
    }

    private static void onConfigChanged(Object spec) {
        if (spec == McaMobCompatConfig.COMMON_SPEC || spec == McaMobCompatConfig.SERVER_SPEC) {
            ClassificationCache.invalidate();
            NativeCharacterAdapters.invalidate();
            ChoreSafetyHandler.invalidateConfig();
        }
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

            // The native-character adapters, and the hooks the riskier transitions depend on. A hook
            // that did not land on an installed mod is said out loud: its protection is missing.
            if (ModIntegrations.recruits().isActive() || ModIntegrations.workers().isActive()) {
                RecruitsEvents.register();
            }
            for (NativeCharacterAdapter adapter : NativeCharacterAdapters.all()) {
                if (adapter.platformProblem().isPresent()) {
                    // Said whether or not the mod is installed: this platform has no build of it to support.
                    LOGGER.info("Character adapter {}: {}", adapter.id(), adapter.platformProblem().get());
                } else if (adapter.module().isPresent()) {
                    LOGGER.info("Character adapter {}: {}{}", adapter.id(), adapter.isActive() ? "active" : "switched off",
                            adapter.bindingProblem().map(problem -> " -- binding incomplete: " + problem).orElse(""));
                }
            }
            // A hook lands as its target class loads; load MCA's editor request now, so the report below
            // is the truth rather than "not yet".
            McaBinding.loadEditorRequestClass();
            LOGGER.info("Hooks: {}", Hooks.describe());
            if (ModIntegrations.recruits().isActive()) {
                for (String hook : new String[]{Hooks.RECRUITS_CREATE_RECRUIT, Hooks.RECRUITS_CREATE_NOBLE}) {
                    if (!Hooks.armed(hook)) {
                        LOGGER.warn("Villager Recruits is installed but this build of it does not take the {} hook: "
                                + "MCA villagers are NOT protected from that conversion. See "
                                + "/mcamobcompat characters adapters.", hook);
                    }
                }
            }
            if (ModIntegrations.recruits().isActive() && !Hooks.armed(Hooks.RECRUITS_HIRE_FROM_VILLAGER)) {
                LOGGER.error("Villager Recruits is installed but this build of it does not take this mod's conversion "
                        + "hooks. MCA villagers are NOT protected from Recruits' noble hire, and enlistment through "
                        + "it is unavailable. See /mcamobcompat characters adapters.");
            }
            if (!Hooks.armed(Hooks.MCA_EDITOR_SAVE)) {
                LOGGER.warn("MCA's editor-save hook did not land on this MCA build; MCA's editor is refused on "
                        + "bridged characters rather than left unchecked.");
            }

            // A tamed horse is a mount whatever mod it came from -- a fact about the animal rather
            // than about its type, so no tag can carry it. Registered unconditionally because it is
            // built on vanilla AbstractHorse and is just as true without SWEM installed.
            MobCompatibilityAPI.registerProvider(TamedMountProvider.INSTANCE);
            // Likewise any animal a player owns is a pet -- a raccoon from Alex's Mobs as much as a
            // vanilla wolf -- which also puts it under the hunting chore's protection.
            MobCompatibilityAPI.registerProvider(OwnedAnimalProvider.INSTANCE);

            LOGGER.info("MCA: Mob Compatibility ready. MCA villagers recognised as {}.",
                    dev.otectus.mcamobcompat.mca.McaEntities.villagerIds());
        });
    }
}
