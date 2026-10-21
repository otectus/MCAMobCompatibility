package dev.otectus.mcamobcompat.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.otectus.mcamobcompat.bridge.GuardIdentityData;
import dev.otectus.mcamobcompat.bridge.GuardIdentityStore;
import dev.otectus.mcamobcompat.bridge.GuardProxy;
import dev.otectus.mcamobcompat.character.Capability;
import dev.otectus.mcamobcompat.character.CharacterOrigin;
import dev.otectus.mcamobcompat.character.CharacterState;
import dev.otectus.mcamobcompat.character.CharacterTransitions;
import dev.otectus.mcamobcompat.character.NativeCharacterAdapter;
import dev.otectus.mcamobcompat.character.NativeCharacterAdapters;
import dev.otectus.mcamobcompat.character.NativeRoleState;
import dev.otectus.mcamobcompat.character.TransitionRecovery;
import dev.otectus.mcamobcompat.hook.Hooks;
import dev.otectus.mcamobcompat.integration.guardvillagers.GuardVillagersAdapter;
import dev.otectus.mcamobcompat.integration.recruits.RecruitsBinding;
import dev.otectus.mcamobcompat.integration.workers.WorkersBinding;
import dev.otectus.mcamobcompat.mca.McaEntities;
import dev.otectus.mcamobcompat.network.ProxyPairs;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The character commands: {@code /mcamobcompat characters ...} for operators, and
 * {@code /mcamobcompat character ...} for players.
 *
 * <p>The operator half reports and repairs. It reads through the store's finders and never creates a
 * record: a diagnostic that writes to the save is a trap. Repair is targeted and previewable --
 * {@code repair <target>} alone says what is wrong and which actions apply -- and no action re-rolls a
 * character: reconnecting a verified pair keeps it exactly. The one destructive tool, a new MCA villager
 * for a guard, stays {@code guards rebuild}, documented as such and never needed for migration.
 *
 * <p>The player half is the discoverable form of the role actions: the role line, enlisting an MCA
 * adult into a native role through the native mod's own hiring and costs, sending an enlisted person
 * home, and opening the native controls without a crouching click. Every one runs through the same
 * transitions and the same native rules as the gestures.
 */
public final class CharacterCommands {

    private static final int REACH = 16;

    private CharacterCommands() {
    }

    /** {@code characters}: the operator diagnostics. */
    static LiteralArgumentBuilder<CommandSourceStack> operator() {
        return Commands.literal("characters")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("status").executes(context -> status(context.getSource())))
                .then(Commands.literal("inspect")
                        .executes(context -> inspect(context.getSource(), lookedAt(context.getSource())))
                        .then(Commands.argument("target", EntityArgument.entity())
                                .executes(context -> inspect(context.getSource(), EntityArgument.getEntity(context, "target")))))
                .then(Commands.literal("adapters").executes(context -> adapters(context.getSource())))
                .then(Commands.literal("transitions").executes(context -> transitions(context.getSource())))
                .then(Commands.literal("repair")
                        .then(Commands.argument("target", EntityArgument.entity())
                                .executes(context -> repair(context.getSource(), EntityArgument.getEntity(context, "target"), null))
                                .then(Commands.argument("action", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                List.of("relink", "clear-stale-link", "restore-civilian", "settle"), builder))
                                        .executes(context -> repair(context.getSource(),
                                                EntityArgument.getEntity(context, "target"),
                                                StringArgumentType.getString(context, "action"))))));
    }

    /** {@code character}: the role actions any player may try; each checks the native rules. */
    static LiteralArgumentBuilder<CommandSourceStack> player() {
        return Commands.literal("character")
                .then(Commands.literal("info").executes(context -> info(context.getSource())))
                .then(Commands.literal("controls").executes(context -> controls(context.getSource())))
                .then(Commands.literal("release").executes(context -> release(context.getSource())))
                .then(Commands.literal("enlist")
                        .then(Commands.argument("role", StringArgumentType.greedyString())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(roles(), builder))
                                .executes(context -> enlist(context.getSource(),
                                        StringArgumentType.getString(context, "role")))));
    }

    // ------------------------------------------------------------------------------- player actions

    private static int info(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Mob actor = actorOf(lookedAt(source));
        NativeCharacterAdapter adapter = actor == null ? null : NativeCharacterAdapters.claimant(actor);
        if (actor == null || adapter == null || GuardIdentityStore.record(actor) == null) {
            player.displayClientMessage(Component.translatable("message.mcamobcompat.character.not_bridged"), false);
            return 0;
        }
        player.displayClientMessage(roleLine(actor, adapter), false);
        return 1;
    }

    /** Name -- native role -- owner -- what the native mod says it is doing. */
    static Component roleLine(Mob actor, NativeCharacterAdapter adapter) {
        NativeRoleState state = adapter.inspect(actor);
        MutableComponent line = Component.empty().append(actor.getDisplayName()).append(" — ").append(adapter.roleLabel(actor));
        if (state.owner() != null && actor.getServer() != null) {
            ServerPlayer owner = actor.getServer().getPlayerList().getPlayer(state.owner());
            line.append(" — ").append(Component.translatable("message.mcamobcompat.character.owner",
                    owner == null ? Component.literal(state.owner().toString().substring(0, 8)) : owner.getDisplayName()));
        }
        if (!state.activityKey().isEmpty()) {
            line.append(" — ").append(Component.translatable(state.activityKey()));
        }
        return line;
    }

    private static int controls(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Mob actor = actorOf(lookedAt(source));
        NativeCharacterAdapter adapter = actor == null ? null : NativeCharacterAdapters.bridging(actor);
        if (adapter == null) {
            player.displayClientMessage(Component.translatable("message.mcamobcompat.character.not_bridged"), false);
            return 0;
        }
        if (actor.level() != player.level() || player.distanceToSqr(actor) > 8.0D * 8.0D) {
            player.displayClientMessage(Component.translatable("message.mcamobcompat.character.too_far"), false);
            return 0;
        }
        InteractionResult result = adapter.openNativeControls(player, actor);
        return result.consumesAction() ? 1 : 0;
    }

    private static int release(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Mob actor = actorOf(lookedAt(source));
        if (actor == null) {
            player.displayClientMessage(Component.translatable("message.mcamobcompat.character.not_bridged"), false);
            return 0;
        }
        CharacterTransitions.Outcome outcome = CharacterTransitions.returnToCivilian(player, actor);
        if ("message.mcamobcompat.release.no_room".equals(outcome.messageKey())) {
            NativeCharacterAdapter adapter = NativeCharacterAdapters.claimant(actor);
            player.displayClientMessage(Component.translatable(outcome.messageKey(), actor.getName(),
                    adapter == null ? 0 : adapter.possessions(actor).size()), false);
            return 0;
        }
        player.displayClientMessage(Component.translatable(outcome.messageKey(), actor.getName()), false);
        return outcome.done() ? 1 : 0;
    }

    private static int enlist(CommandSourceStack source, String roleText) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Entity target = lookedAt(source);
        ResourceLocation role = ResourceLocation.tryParse(roleText.trim().toLowerCase(Locale.ROOT));
        if (!(target instanceof Villager person) || !McaEntities.isMcaVillager(person) || GuardProxy.isProxy(person)) {
            player.displayClientMessage(Component.translatable("message.mcamobcompat.enlist.look_at_villager"), false);
            return 0;
        }
        if (role == null) {
            player.displayClientMessage(Component.translatable("message.mcamobcompat.enlist.unknown_role"), false);
            return 0;
        }
        CharacterTransitions.Outcome outcome = CharacterTransitions.enlistByCommand(player, person, role);
        player.displayClientMessage(Component.translatable(outcome.messageKey(), person.getName()), false);
        return outcome.done() ? 1 : 0;
    }

    /** The native roles an MCA adult can be given: every installed adapter's character entity types. */
    private static List<String> roles() {
        List<String> roles = new ArrayList<>();
        for (NativeCharacterAdapter adapter : NativeCharacterAdapters.all()) {
            if (!adapter.isActive() || !adapter.transitions().enlist()) {
                continue;
            }
            if (adapter == GuardVillagersAdapter.INSTANCE) {
                roles.add(GuardVillagersAdapter.GUARD_TYPE.toString());
                continue;
            }
            for (ResourceLocation id : BuiltInRegistries.ENTITY_TYPE.keySet()) {
                if (id.getNamespace().equals(adapter.module().namespace()) && !id.getPath().endsWith("area")
                        && !id.getPath().contains("bobber")) {
                    roles.add(id.toString());
                }
            }
        }
        roles.sort(String::compareTo);
        return roles;
    }

    // ----------------------------------------------------------------------------- operator reports

    private static int status(CommandSourceStack source) {
        GuardIdentityData data = GuardIdentityStore.get(source.getServer());
        line(source, ChatFormatting.GOLD, "MCA characters");
        Map<String, Integer> byAdapter = new TreeMap<>();
        Map<String, Integer> byState = new TreeMap<>();
        Map<String, Integer> byOrigin = new TreeMap<>();
        int open = 0;
        for (GuardIdentityData.Entry entry : data.entries()) {
            byAdapter.merge(entry.adapter().isEmpty() ? "(not seen yet)" : entry.adapter(), 1, Integer::sum);
            byState.merge(data.isQuarantined(entry.uuid()) ? CharacterState.QUARANTINED.id() : entry.state().id(), 1, Integer::sum);
            byOrigin.merge(entry.origin().id(), 1, Integer::sum);
            if (entry.openTransition() != null) {
                open++;
            }
        }
        line(source, ChatFormatting.WHITE, "  " + data.size() + " record(s), schema " + GuardIdentityData.SCHEMA_VERSION
                + "; by adapter " + byAdapter + "; by state " + byState + "; by origin " + byOrigin);
        line(source, open == 0 ? ChatFormatting.GREEN : ChatFormatting.YELLOW, "  " + open + " transition(s) open");
        if (data.isReadOnly()) {
            line(source, ChatFormatting.RED, "  The store uses a newer schema than this version understands; it is "
                    + "preserved read-only and nothing is created, changed or saved over it.");
        }
        if (data.quarantinedCount() > 0) {
            line(source, ChatFormatting.RED, "  " + data.quarantinedCount() + " record(s) quarantined: "
                    + data.quarantined() + ". Preserved as-is; see 'characters repair'.");
        }
        if (data.rejectedCount() > 0) {
            line(source, ChatFormatting.YELLOW, "  " + data.rejectedCount() + " unreadable record(s) preserved verbatim.");
        }
        int actors = 0;
        int waiting = 0;
        int identities = 0;
        int stale = 0;
        for (ServerLevel level : source.getServer().getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (GuardProxy.isProxy(entity)) {
                    identities++;
                } else if (entity instanceof Mob mob && NativeCharacterAdapters.claimant(mob) != null) {
                    if (GuardProxy.proxyOf(mob) != null) {
                        actors++;
                    } else if (mob.getPersistentData().hasUUID(GuardProxy.KEY_PROXY)) {
                        stale++;
                    } else if (NativeCharacterAdapters.bridging(mob) != null) {
                        waiting++;
                    }
                }
            }
        }
        line(source, ChatFormatting.WHITE, "  loaded: " + actors + " actor(s) with their person, " + identities
                + " hidden identit(ies), " + waiting + " actor(s) awaiting an identity, " + stale
                + " actor(s) naming a person not here (kept; see 'characters repair')");
        for (String hook : Hooks.describe()) {
            line(source, hook.contains(": armed") ? ChatFormatting.GRAY : ChatFormatting.YELLOW, "  hook " + hook);
        }
        return data.size();
    }

    private static int adapters(CommandSourceStack source) {
        line(source, ChatFormatting.GOLD, "Character adapters (selection order)");
        for (NativeCharacterAdapter adapter : NativeCharacterAdapters.all()) {
            java.util.Optional<String> unavailable = adapter.platformProblem();
            if (unavailable.isPresent()) {
                line(source, ChatFormatting.DARK_GRAY, "  " + adapter.id() + " -- " + adapter.displayName() + ": "
                        + unavailable.get());
                continue;
            }
            String version = adapter.module().installedVersion().orElse("not installed");
            ChatFormatting colour = !adapter.module().isPresent() ? ChatFormatting.DARK_GRAY
                    : adapter.isActive() && adapter.bindingProblem().isEmpty() ? ChatFormatting.GREEN : ChatFormatting.YELLOW;
            line(source, colour, "  " + adapter.id() + " -- " + adapter.displayName() + " " + version
                    + (adapter.module().isPresent() && !adapter.isActive() ? " (module switched off)" : ""));
            if (!adapter.module().isPresent()) {
                continue;
            }
            line(source, ChatFormatting.WHITE, "    characters " + onOff(adapter.bridgesActors()) + ", MCA interaction "
                    + onOff(adapter.opensMcaInteraction()) + ", appearance " + adapter.appearanceMode());
            adapter.bindingProblem().ifPresent(problem -> line(source, ChatFormatting.RED, "    binding: " + problem));
            NativeCharacterAdapter.TransitionSupport support = adapter.transitions();
            line(source, ChatFormatting.WHITE, "    enlist " + supported(support.enlist(), support.enlistReason())
                    + ", return to civilian " + supported(support.returnToCivilian(), support.returnReason())
                    + ", host replacement " + supported(support.hostReplacement(), support.hostReason()));
        }
        if (dev.otectus.mcamobcompat.integration.ModIntegrations.recruits().isPresent()) {
            for (String binding : RecruitsBinding.describeBindings()) {
                line(source, binding.endsWith("ok") ? ChatFormatting.GRAY : ChatFormatting.YELLOW, "    recruits " + binding);
            }
        }
        if (dev.otectus.mcamobcompat.integration.ModIntegrations.workers().isPresent()) {
            for (String binding : WorkersBinding.describeBindings()) {
                line(source, binding.endsWith("ok") ? ChatFormatting.GRAY : ChatFormatting.YELLOW, "    workers " + binding);
            }
        }
        for (String hook : Hooks.describe()) {
            line(source, hook.contains(": armed") ? ChatFormatting.GREEN : ChatFormatting.YELLOW, "  hook " + hook);
        }
        return NativeCharacterAdapters.all().size();
    }

    private static int transitions(CommandSourceStack source) {
        GuardIdentityData data = GuardIdentityStore.get(source.getServer());
        line(source, ChatFormatting.GOLD, "Character transitions");
        int shown = 0;
        for (GuardIdentityData.Entry entry : data.entries()) {
            for (GuardIdentityData.Transition transition : entry.journal()) {
                boolean openOne = !transition.stage().isTerminal();
                line(source, openOne ? ChatFormatting.YELLOW : ChatFormatting.WHITE, "  " + entry.characterUuid()
                        + " " + transition.kind().id() + " " + transition.stage().id() + " at " + transition.updated()
                        + (transition.note().isEmpty() ? "" : " (" + transition.note() + ")"));
                shown++;
            }
        }
        if (shown == 0) {
            line(source, ChatFormatting.GRAY, "  None recorded.");
        }
        return shown;
    }

    private static int inspect(CommandSourceStack source, Entity target) {
        if (target == null) {
            line(source, ChatFormatting.RED, "Not looking at an entity. Aim at one, or pass a target selector.");
            return 0;
        }
        GuardIdentityData data = GuardIdentityStore.get(source.getServer());
        Mob actor = actorOf(target);
        GuardIdentityData.Entry entry = actor != null ? data.find(actor.getUUID())
                : data.findByProxy(target.getUUID());
        NativeCharacterAdapter adapter = actor == null ? null : NativeCharacterAdapters.claimant(actor);
        line(source, ChatFormatting.GOLD, target.getName().getString() + " (" + EntityType.getKey(target.getType()) + ")");
        if (adapter != null) {
            line(source, ChatFormatting.WHITE, "  adapter " + adapter.id() + " (" + adapter.displayName() + " "
                    + adapter.module().installedVersion().orElse("?") + "), bridged " + onOff(NativeCharacterAdapters.bridging(actor) != null)
                    + ", MCA interaction " + onOff(adapter.opensMcaInteraction()) + ", appearance " + adapter.appearanceMode());
            line(source, ChatFormatting.WHITE, "  role line: " + roleLine(actor, adapter).getString());
            Set<Capability> have = adapter.capabilities(actor);
            EnumSet<Capability> missing = EnumSet.allOf(Capability.class);
            missing.removeAll(have);
            line(source, ChatFormatting.WHITE, "  capabilities: " + have);
            line(source, ChatFormatting.GRAY, "  not available: " + missing);
            line(source, ChatFormatting.GRAY, "  controller: " + adapter.displayName() + " (native AI and navigation)");
        } else if (actor == null && !GuardProxy.isProxy(target)) {
            line(source, ChatFormatting.YELLOW, "  not a native actor or a hidden identity");
        }
        if (entry == null) {
            line(source, ChatFormatting.YELLOW, "  no record" + (actor != null && actor.getPersistentData().hasUUID(GuardProxy.KEY_PROXY)
                    ? ", but it names a person: " + actor.getPersistentData().getUUID(GuardProxy.KEY_PROXY) : ""));
            return 0;
        }
        line(source, ChatFormatting.WHITE, "  character " + entry.characterUuid() + ", actor " + entry.uuid() + ", identity "
                + entry.proxyUuid());
        line(source, ChatFormatting.WHITE, "  origin " + entry.origin().id() + ", state "
                + (data.isQuarantined(entry.uuid()) ? CharacterState.QUARANTINED.id() : entry.state().id())
                + ", generation " + entry.generation() + ", appearance revision " + entry.appearanceRevision()
                + (entry.migratedFrom() > 0 ? ", migrated from schema " + entry.migratedFrom() : ""));
        if (entry.origin() == CharacterOrigin.ENLISTED_PERSON) {
            line(source, ChatFormatting.WHITE, "  civilian profession " + entry.civilianProfession());
        }
        if (!entry.lastDimension().isEmpty()) {
            line(source, ChatFormatting.GRAY, "  last seen in " + entry.lastDimension() + " at "
                    + net.minecraft.core.BlockPos.of(entry.lastPos()).toShortString());
        }
        Villager identity = actor == null ? null : GuardProxy.proxyOf(actor);
        line(source, identity == null ? ChatFormatting.YELLOW : ChatFormatting.GREEN, "  identity "
                + (identity == null ? "not loaded" : "loaded, " + BuiltInRegistries.VILLAGER_PROFESSION.getKey(
                identity.getVillagerData().getProfession())));
        List<GuardIdentityData.Transition> journal = entry.journal();
        for (int i = Math.max(0, journal.size() - 3); i < journal.size(); i++) {
            GuardIdentityData.Transition transition = journal.get(i);
            line(source, ChatFormatting.GRAY, "  journal: " + transition.kind().id() + " " + transition.stage().id()
                    + (transition.note().isEmpty() ? "" : " (" + transition.note() + ")"));
        }
        if (adapter != null) {
            NativeCharacterAdapter.TransitionSupport support = adapter.transitions();
            if (entry.origin() == CharacterOrigin.ENLISTED_PERSON) {
                line(source, ChatFormatting.GRAY, "  return to civilian: " + supported(support.returnToCivilian(), support.returnReason()));
            }
        }
        return 1;
    }

    /**
     * Targeted repair. Without an action it is a preview: what is wrong with this entity's character
     * and which actions would apply. No action re-rolls anybody.
     */
    private static int repair(CommandSourceStack source, Entity target, String action) {
        GuardIdentityData data = GuardIdentityStore.get(source.getServer());
        Mob actor = actorOf(target);
        Villager identity = target instanceof Villager villager && McaEntities.isMcaVillager(villager) ? villager
                : actor == null ? null : GuardProxy.proxyOf(actor);
        GuardIdentityData.Entry entry = actor != null ? data.find(actor.getUUID())
                : identity == null ? null : data.findByProxy(identity.getUUID());
        List<String> applicable = new ArrayList<>();
        UUID named = actor == null || !actor.getPersistentData().hasUUID(GuardProxy.KEY_PROXY) ? null
                : actor.getPersistentData().getUUID(GuardProxy.KEY_PROXY);
        Entity namedEntity = named == null ? null : ((ServerLevel) target.level()).getEntity(named);
        if (actor != null && identity != null && (entry == null || !identity.getUUID().equals(entry.proxyUuid()))) {
            applicable.add("relink");
        }
        if (actor != null && named != null && namedEntity instanceof Villager person
                && !actor.getUUID().equals(GuardProxy.linkedGuardUuid(person))) {
            applicable.add("clear-stale-link");
        }
        if (identity != null && GuardProxy.isProxy(identity) && (actor == null || !actor.isAlive())
                && GuardProxy.guardOf(identity) == null) {
            applicable.add("restore-civilian");
        }
        if (entry != null && entry.openTransition() != null) {
            applicable.add("settle");
        }
        if (action == null) {
            line(source, ChatFormatting.GOLD, "Repair preview for " + target.getName().getString());
            line(source, applicable.isEmpty() ? ChatFormatting.GREEN : ChatFormatting.YELLOW,
                    applicable.isEmpty() ? "  nothing to repair" : "  applicable: " + applicable);
            line(source, ChatFormatting.GRAY, "  relink: record the pair the entities agree on (never re-rolls); "
                    + "clear-stale-link: forget a person this actor names who names another body; restore-civilian: "
                    + "bring back a hidden person whose actor is gone; settle: close an open transition.");
            return applicable.size();
        }
        if (!applicable.contains(action)) {
            line(source, ChatFormatting.RED, "'" + action + "' does not apply here; applicable: " + applicable);
            return 0;
        }
        switch (action) {
            case "relink" -> CharacterTransitions.repairLink(actor, identity);
            case "clear-stale-link" -> actor.getPersistentData().remove(GuardProxy.KEY_PROXY);
            case "restore-civilian" -> {
                if (!CharacterTransitions.restoreCivilian(data, entry, identity)) {
                    line(source, ChatFormatting.RED, "Could not restore " + identity.getName().getString() + ".");
                    return 0;
                }
            }
            case "settle" -> {
                GuardIdentityData.Transition open = entry.openTransition();
                data.advanceTransition(entry, open.id(), TransitionRecovery.settle(open.stage()), null,
                        target.level().getGameTime(), "settled by an operator", null);
            }
            default -> {
                return 0;
            }
        }
        ProxyPairs.broadcast(actor, identity);
        line(source, ChatFormatting.GREEN, "Applied '" + action + "' to " + target.getName().getString() + ".");
        return 1;
    }

    // ------------------------------------------------------------------------------------- helpers

    /** The native actor an entity is, or stands behind: itself, or a hidden identity's actor. */
    static Mob actorOf(Entity target) {
        if (target instanceof Mob mob && !McaEntities.isMcaVillager(mob)) {
            return mob;
        }
        return target != null && GuardProxy.isProxy(target) ? GuardProxy.guardOf(target) : null;
    }

    private static String onOff(boolean value) {
        return value ? "on" : "off";
    }

    private static String supported(boolean ok, String reason) {
        return ok ? "available" : "unavailable (" + Component.translatable(reason).getString() + ")";
    }

    /** The entity the source's player is aiming at, or null. */
    static Entity lookedAt(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return null;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0F).scale(REACH);
        AABB box = player.getBoundingBox().expandTowards(look).inflate(1.0D);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, eye, eye.add(look), box,
                candidate -> !candidate.isSpectator(), (double) REACH * REACH);
        Entity found = hit == null ? null : hit.getEntity();
        // A click lands on the actor; so does a look, even where the identity's narrow box pokes out.
        return found != null && GuardProxy.isProxy(found) && GuardProxy.guardOf(found) != null
                ? GuardProxy.guardOf(found) : found;
    }

    private static void line(CommandSourceStack source, ChatFormatting colour, String text) {
        source.sendSuccess(() -> Component.literal(text).withStyle(colour), false);
    }
}
