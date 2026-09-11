package dev.otectus.mcamobcompat.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.api.MobCompatibilityAPI;
import dev.otectus.mcamobcompat.api.GuardIdentity;
import dev.otectus.mcamobcompat.api.MobRole;
import dev.otectus.mcamobcompat.bridge.GuardBridge;
import dev.otectus.mcamobcompat.bridge.GuardIdentityData;
import dev.otectus.mcamobcompat.bridge.GuardIdentityStore;
import dev.otectus.mcamobcompat.bridge.GuardProxy;
import dev.otectus.mcamobcompat.classification.ClassificationCache;
import dev.otectus.mcamobcompat.classification.ProviderRegistry;
import dev.otectus.mcamobcompat.integration.IntegrationModule;
import dev.otectus.mcamobcompat.integration.ModIntegrations;
import dev.otectus.mcamobcompat.mca.McaBinding;
import dev.otectus.mcamobcompat.mca.McaEntities;
import dev.otectus.mcamobcompat.util.ModIds;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Diagnostics for MCA: Mob Compatibility.
 *
 * <p>{@code dump} is the important one. This mod classifies mobs it has never heard of, and the way
 * anyone finds out what a new mod actually registered -- rather than what its wiki claims -- is to
 * ask the live registry. It is how the Genetic Animals ids in the shipped tags were confirmed to be
 * {@code eanimod:enhanced_cow} and not {@code geneticanimals:cow}, and it is how the next pack
 * author will onboard a mod nobody here has seen.
 */
@Mod.EventBusSubscriber(modid = McaMobCompat.MOD_ID)
public final class McaMobCompatCommand {

    /** Chat cannot usefully hold a full vanilla registry dump; the log always gets all of it. */
    private static final int MAX_CHAT_LINES = 50;

    private static final int RAYCAST_REACH = 16;

    /** Ticks in one in-game day, which is the unit {@code guards purge} counts in. */
    private static final long TICKS_PER_DAY = 24000L;

    private McaMobCompatCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(McaMobCompat.MOD_ID)
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("status")
                        .executes(context -> status(context.getSource())))
                .then(Commands.literal("classify")
                        .executes(context -> classifyLookedAt(context.getSource()))
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .executes(context -> classify(context.getSource(),
                                        EntityArgument.getEntities(context, "targets")))))
                .then(Commands.literal("guards")
                        .then(Commands.literal("status")
                                .executes(context -> guardsStatus(context.getSource())))
                        .then(Commands.literal("inspect")
                                .executes(context -> inspectLookedAt(context.getSource()))
                                .then(Commands.argument("target", EntityArgument.entity())
                                        .executes(context -> inspect(context.getSource(),
                                                EntityArgument.getEntity(context, "target")))))
                        .then(Commands.literal("proxies")
                                .executes(context -> proxies(context.getSource())))
                        .then(Commands.literal("rebuild")
                                .executes(context -> rebuild(context.getSource())))
                        .then(Commands.literal("purge")
                                .then(Commands.argument("days", IntegerArgumentType.integer(0))
                                        .executes(context -> purge(context.getSource(),
                                                IntegerArgumentType.getInteger(context, "days"))))))
                .then(Commands.literal("mca")
                        .executes(context -> mcaBinding(context.getSource())))
                .then(Commands.literal("dump")
                        .then(Commands.argument("namespace", StringArgumentType.word())
                                .suggests((context, builder) ->
                                        SharedSuggestionProvider.suggest(namespaces(), builder))
                                .executes(context -> dump(context.getSource(),
                                        StringArgumentType.getString(context, "namespace"))))));
    }

    private static int status(CommandSourceStack source) {
        line(source, ChatFormatting.GOLD, "MCA: Mob Compatibility");

        line(source, ChatFormatting.WHITE, "  MCA villagers recognised as: " + McaEntities.villagerIds());

        line(source, ChatFormatting.GOLD, "  Integrations");
        for (IntegrationModule module : ModIntegrations.modules()) {
            String state;
            ChatFormatting colour;
            if (!module.isPresent()) {
                state = "not installed";
                colour = ChatFormatting.DARK_GRAY;
            } else if (!module.isEnabled()) {
                state = module.installedVersion().orElse("?") + " -- installed, disabled in config";
                colour = ChatFormatting.YELLOW;
            } else {
                state = module.installedVersion().orElse("?") + " -- active";
                colour = ChatFormatting.GREEN;
            }
            line(source, colour, "    " + module.displayName() + " (" + module.modId() + "): " + state);
        }

        line(source, ChatFormatting.GOLD, "  Roles");
        int[] counts = new int[MobRole.VALUES.size()];
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            Set<MobRole> roles = MobCompatibilityAPI.getRoles(type);
            for (MobRole role : roles) {
                counts[role.ordinal()]++;
            }
        }
        for (MobRole role : MobRole.VALUES) {
            line(source, ChatFormatting.WHITE,
                    "    " + role.tagId() + ": " + counts[role.ordinal()] + " entity type(s)");
        }

        line(source, ChatFormatting.GRAY, "  " + ClassificationCache.size() + " type(s) cached, "
                + ProviderRegistry.count() + " classification provider(s) registered.");
        return 1;
    }

    private static int classifyLookedAt(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Entity target = lookedAt(player);
        if (target == null) {
            line(source, ChatFormatting.RED,
                    "Not looking at an entity. Aim at one, or pass a target selector.");
            return 0;
        }
        return classify(source, List.of(target));
    }

    private static int classify(CommandSourceStack source, Collection<? extends Entity> targets) {
        if (targets.isEmpty()) {
            line(source, ChatFormatting.RED, "No entities matched.");
            return 0;
        }
        for (Entity entity : targets) {
            ResourceLocation id = EntityType.getKey(entity.getType());
            Set<MobRole> roles = MobCompatibilityAPI.getRoles(entity);
            line(source, ChatFormatting.GOLD, entity.getName().getString() + " (" + id + ")");
            line(source, roles.isEmpty() ? ChatFormatting.DARK_GRAY : ChatFormatting.GREEN,
                    "  roles: " + (roles.isEmpty() ? "none" : describe(roles)));
            if (McaEntities.isMcaVillager(entity)) {
                line(source, ChatFormatting.AQUA, "  recognised as an MCA villager (protected)");
                // Only from the reflective binding, so it is absent rather than wrong when MCA has
                // moved its packages again -- which is the point of printing it here.
                McaBinding.currentChore(entity).ifPresent(chore ->
                        line(source, ChatFormatting.WHITE, "  chore: " + chore));
            }
            if (GuardBridge.isBridgedGuard(entity)) {
                line(source, ChatFormatting.AQUA, "  bridged MCA guard");
                // find, never getOrCreate: a diagnostic that writes to the save file is a trap.
                GuardIdentityStore.find((Mob) entity).ifPresent(identity ->
                        line(source, ChatFormatting.WHITE, "    identity: " + summarise(identity)));
            }
            MobCompatibilityAPI.getOwner(entity).ifPresent(owner ->
                    line(source, ChatFormatting.WHITE, "  owner: " + owner));
            if (roles.contains(MobRole.MOUNT)) {
                reportMountState(source, entity);
            }
        }
        return targets.size();
    }

    /**
     * The three vanilla reads that tell the two mount failure modes apart.
     *
     * <p>MCA mounts a villager on player command, and its candidate filter needs a saddled,
     * unoccupied mount. No saddle means MCA never mounts the villager at all. A saddle plus a null
     * controlling passenger means the villager does mount and the mount then stays inert, because
     * the mount's own {@code getControllingPassenger()} refuses to hand steering to anything but a
     * player -- which is what SWEM does, and which nothing here can reach without a mixin.
     *
     * <p>All three are vanilla accessors, so this names no foreign class.
     */
    private static void reportMountState(CommandSourceStack source, Entity entity) {
        if (entity instanceof AbstractHorse horse) {
            line(source, horse.isSaddled() ? ChatFormatting.GREEN : ChatFormatting.YELLOW,
                    "  saddled: " + horse.isSaddled());
        }
        // The owner line above only prints when there is one; for a mount the absence is itself a fact.
        if (MobCompatibilityAPI.getOwner(entity).isEmpty()) {
            line(source, ChatFormatting.YELLOW, "  owner: none");
        }
        LivingEntity controller = entity.getControllingPassenger();
        line(source, controller == null ? ChatFormatting.YELLOW : ChatFormatting.GREEN,
                "  controllingPassenger: "
                        + (controller == null ? "none" : controller.getName().getString()));
    }

    private static int guardsStatus(CommandSourceStack source) {
        line(source, ChatFormatting.GOLD, "Guard bridge");
        boolean bridging = ModIntegrations.guardVillagers().bridgesGuards();
        line(source, bridging ? ChatFormatting.GREEN : ChatFormatting.YELLOW,
                "  " + (bridging ? "active" : "disabled (Guard Villagers absent, or switched off in the config)"));
        boolean mcaInteraction = ModIntegrations.guardVillagers().opensMcaInteraction();
        line(source, mcaInteraction ? ChatFormatting.GREEN : ChatFormatting.YELLOW,
                "  MCA interaction: " + (mcaInteraction
                        ? "on (plain click opens MCA's screen through a hidden proxy)"
                        : "off (stock Guard Villagers gestures, no proxy created)"));

        GuardIdentityData data = GuardIdentityStore.get(source.getServer());
        line(source, ChatFormatting.WHITE, "  " + data.size() + " identity(ies) stored, "
                + data.deceasedCount() + " deceased.");

        ProxyCensus census = census(source);
        line(source, ChatFormatting.WHITE, "  " + census.proxies + " proxy(ies) loaded, "
                + census.orphans + " orphaned, " + census.guardsWithout + " guard(s) without one.");
        return data.size();
    }

    /**
     * Walks every loaded entity in every level once and counts the three states worth knowing.
     *
     * <p>One walk rather than three: {@code getAllEntities()} is not cheap on a busy server, and
     * this is a diagnostic somebody runs while something is already wrong.
     */
    private static ProxyCensus census(CommandSourceStack source) {
        ProxyCensus census = new ProxyCensus();
        for (ServerLevel level : source.getServer().getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (GuardProxy.isProxy(entity)) {
                    census.proxies++;
                    if (GuardProxy.guardOf(entity) == null) {
                        census.orphans++;
                    }
                } else if (GuardBridge.isBridgedGuard(entity)
                        && GuardProxy.proxyOf((Mob) entity) == null) {
                    census.guardsWithout++;
                }
            }
        }
        return census;
    }

    /** Three counts from one walk of the loaded entities. */
    private static final class ProxyCensus {
        private int proxies;
        private int orphans;
        private int guardsWithout;
    }

    /**
     * Every loaded proxy, its guard, and every bridged guard still waiting for one.
     *
     * <p>The orphan list is the one that matters: a proxy whose guard is gone holds MCA state for an
     * NPC nobody can reach, and it is what {@code guards rebuild} exists to clear up.
     */
    private static int proxies(CommandSourceStack source) {
        line(source, ChatFormatting.GOLD, "MCA proxies");
        int found = 0;
        for (ServerLevel level : source.getServer().getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (GuardProxy.isProxy(entity)) {
                    found++;
                    Mob guard = GuardProxy.guardOf(entity);
                    line(source, guard == null ? ChatFormatting.RED : ChatFormatting.GREEN,
                            "  " + entity.getName().getString() + " (" + entity.getUUID() + ") in "
                                    + level.dimension().location() + " -> "
                                    + (guard == null ? "ORPHAN, no guard found" : guard.getUUID()));
                } else if (GuardBridge.isBridgedGuard(entity)
                        && GuardProxy.proxyOf((Mob) entity) == null) {
                    found++;
                    line(source, ChatFormatting.YELLOW, "  guard " + entity.getUUID() + " in "
                            + level.dimension().location() + " has no proxy");
                }
            }
        }
        if (found == 0) {
            line(source, ChatFormatting.GRAY, "  Nothing loaded to report.");
        }
        return found;
    }

    /**
     * Discards and recreates the proxy of every loaded bridged guard.
     *
     * <p>The repair tool, and a destructive one: a rebuilt proxy is a new villager to MCA, so the
     * family-tree node and the memories on the old one are gone. It is for a world where the links
     * have already been broken -- by a crash mid-save, or by removing and re-adding MCA -- and the
     * alternative is guards nobody can talk to.
     */
    private static int rebuild(CommandSourceStack source) {
        int rebuilt = 0;
        for (ServerLevel level : source.getServer().getAllLevels()) {
            List<Entity> guards = new ArrayList<>();
            for (Entity entity : level.getAllEntities()) {
                if (GuardBridge.isBridgedGuard(entity)) {
                    guards.add(entity);
                }
            }
            // Collected first: creating a proxy adds an entity, and the live view would then be
            // iterated while it changes.
            for (Entity entity : guards) {
                Mob guard = (Mob) entity;
                Villager existing = GuardProxy.proxyOf(guard);
                if (existing != null) {
                    GuardProxy.discard(existing);
                }
                if (GuardProxy.create(level, guard) != null) {
                    rebuilt++;
                }
            }
        }
        line(source, ChatFormatting.WHITE, "Rebuilt " + rebuilt + " guard proxy(ies).");
        return rebuilt;
    }

    private static int inspectLookedAt(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Entity target = lookedAt(player);
        if (target == null) {
            line(source, ChatFormatting.RED,
                    "Not looking at an entity. Aim at one, or pass a target selector.");
            return 0;
        }
        return inspect(source, target);
    }

    private static int inspect(CommandSourceStack source, Entity target) {
        if (!GuardBridge.isBridgedGuard(target)) {
            line(source, ChatFormatting.RED,
                    target.getName().getString() + " is not a bridged guard.");
            return 0;
        }
        Optional<GuardIdentity> found = GuardIdentityStore.find((Mob) target);
        if (found.isEmpty()) {
            line(source, ChatFormatting.YELLOW,
                    target.getName().getString() + " is a bridged guard but has no identity yet.");
            return 0;
        }

        GuardIdentity identity = found.get();
        line(source, ChatFormatting.GOLD, identity.name() + " (" + identity.uuid() + ")");
        line(source, ChatFormatting.WHITE, "  " + summarise(identity));
        line(source, ChatFormatting.WHITE, "  profession: " + identity.professionId());
        line(source, ChatFormatting.WHITE, "  created at game time " + identity.created());

        Villager proxy = GuardProxy.proxyOf((Mob) target);
        UUID proxyUuid = identity.proxyUuid().orElse(null);
        if (proxyUuid == null && proxy == null) {
            line(source, ChatFormatting.YELLOW, "  proxy: none -- every value above is the stored "
                    + "roll, not MCA's own");
        } else {
            line(source, proxy == null ? ChatFormatting.YELLOW : ChatFormatting.GREEN,
                    "  proxy: " + (proxyUuid == null ? proxy.getUUID() : proxyUuid)
                            + (proxy == null ? " (not loaded)" : " (loaded in "
                            + proxy.level().dimension().location() + ")"));
        }
        if (proxy != null) {
            line(source, ChatFormatting.WHITE, "  live MCA name: "
                    + (proxy.hasCustomName() ? proxy.getCustomName().getString() : "unnamed"));
            line(source, ChatFormatting.WHITE, "  live profession: " + ForgeRegistries
                    .VILLAGER_PROFESSIONS.getKey(proxy.getVillagerData().getProfession()));
        }
        if (source.getEntity() instanceof ServerPlayer player) {
            line(source, ChatFormatting.WHITE,
                    "  your hearts: " + identity.hearts(player.getUUID()));
        }
        return 1;
    }

    private static int purge(CommandSourceStack source, int days) {
        GuardIdentityData data = GuardIdentityStore.get(source.getServer());
        long now = source.getLevel().getGameTime();
        int removed = data.purgeDeceased(now, days * TICKS_PER_DAY);
        line(source, ChatFormatting.WHITE, "Purged " + removed + " deceased guard identity(ies) older than "
                + days + " in-game day(s); " + data.size() + " remain.");
        return removed;
    }

    /** One line of identity, shared by {@code classify} and {@code guards inspect}. */
    private static String summarise(GuardIdentity identity) {
        return identity.name() + ", " + identity.gender().id() + ", " + identity.personality()
                + (identity.deceased() ? ", deceased" : "");
    }

    /**
     * Proof of life for the reflective binding: which MCA is installed, which package layout it uses
     * and which members bound. If a chore feature has gone quiet, this says whether the cause is a
     * renamed MCA member or something in this mod.
     */
    private static int mcaBinding(CommandSourceStack source) {
        line(source, ChatFormatting.GOLD, "MCA binding");
        String version = ModList.get().getModContainerById(ModIds.MCA)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("not installed");
        line(source, ChatFormatting.WHITE, "  MCA version: " + version);

        Optional<String> root = McaBinding.packageRoot();
        line(source, root.isPresent() ? ChatFormatting.GREEN : ChatFormatting.YELLOW,
                "  package root: " + root.orElse("not found -- chore features are disabled"));
        for (String binding : McaBinding.describeBindings()) {
            line(source, binding.endsWith("ok") ? ChatFormatting.GREEN : ChatFormatting.YELLOW,
                    "    " + binding);
        }
        return McaBinding.isAvailable() ? 1 : 0;
    }

    private static int dump(CommandSourceStack source, String namespace) {
        List<String> lines = new ArrayList<>();
        int classified = 0;
        for (ResourceLocation id : new TreeSet<>(BuiltInRegistries.ENTITY_TYPE.keySet())) {
            if (!id.getNamespace().equals(namespace)) {
                continue;
            }
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
            Set<MobRole> roles = MobCompatibilityAPI.getRoles(type);
            if (!roles.isEmpty()) {
                classified++;
            }
            lines.add(id + " -> " + (roles.isEmpty() ? "-" : describe(roles)));
        }

        if (lines.isEmpty()) {
            line(source, ChatFormatting.RED, "No entity types registered under '" + namespace + "'.");
            line(source, ChatFormatting.GRAY, "Known namespaces: " + String.join(", ", namespaces()));
            return 0;
        }

        line(source, ChatFormatting.GOLD,
                namespace + ": " + lines.size() + " entity type(s), " + classified + " classified");
        McaMobCompat.LOGGER.info("Entity type dump for namespace '{}' ({} types, {} classified):",
                namespace, lines.size(), classified);
        for (String entry : lines) {
            McaMobCompat.LOGGER.info("  {}", entry);
        }
        for (int i = 0; i < Math.min(lines.size(), MAX_CHAT_LINES); i++) {
            line(source, ChatFormatting.WHITE, "  " + lines.get(i));
        }
        if (lines.size() > MAX_CHAT_LINES) {
            line(source, ChatFormatting.GRAY,
                    "  ... " + (lines.size() - MAX_CHAT_LINES) + " more; the full list is in the server log.");
        }
        return lines.size();
    }

    /** Every namespace that has registered an entity type, for tab completion. */
    private static List<String> namespaces() {
        Set<String> found = new TreeSet<>();
        for (ResourceLocation id : BuiltInRegistries.ENTITY_TYPE.keySet()) {
            found.add(id.getNamespace());
        }
        return List.copyOf(found);
    }

    /** The entity this player is aiming at, or null. */
    private static Entity lookedAt(ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0F).scale(RAYCAST_REACH);
        AABB box = player.getBoundingBox().expandTowards(look).inflate(1.0D);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                player, eye, eye.add(look), box, candidate -> !candidate.isSpectator(),
                (double) RAYCAST_REACH * RAYCAST_REACH);
        return hit == null ? null : hit.getEntity();
    }

    private static String describe(Set<MobRole> roles) {
        List<String> names = new ArrayList<>(roles.size());
        for (MobRole role : MobRole.VALUES) {
            if (roles.contains(role)) {
                names.add(role.path());
            }
        }
        return String.join(", ", names);
    }

    private static void line(CommandSourceStack source, ChatFormatting colour, String text) {
        source.sendSuccess(() -> Component.literal(text).withStyle(colour), false);
    }
}
