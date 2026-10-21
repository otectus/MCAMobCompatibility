package dev.otectus.mcamobcompat.character;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.McaMobCompatConfig;
import dev.otectus.mcamobcompat.bridge.GuardIdentityData;
import dev.otectus.mcamobcompat.bridge.GuardIdentityStore;
import dev.otectus.mcamobcompat.bridge.GuardProxy;
import dev.otectus.mcamobcompat.hook.Hooks;
import dev.otectus.mcamobcompat.mca.McaBinding;
import dev.otectus.mcamobcompat.util.ConfigAccess;
import dev.otectus.mcamobcompat.util.ModIds;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCA's own editor, on a native NPC's MCA person.
 *
 * <p><b>Opening.</b> MCA's editor item does nothing on an entity that is not an MCA villager, so the
 * interaction router hands the click to this class, which checks the server's editor policy, opens a
 * short-lived session, and then lets MCA's item act on the hidden identity -- MCA itself sends its
 * screen-open request for that villager. Every category, selector and preview is MCA's: its detached
 * preview villagers are never the identity and are drawn by MCA as they always are.
 *
 * <p><b>Saving.</b> Opening a screen authorises nothing. MCA applies an editor save to whatever entity
 * the client names, with no check of who sent it; for a bridged identity the editor hook calls
 * {@link #beforeSave} first, which refuses a save without a matching session, from another dimension
 * or too far away, after the character's actor was replaced, or after another player saved the same
 * character since this editor opened -- and otherwise rebuilds the payload through
 * {@link EditorPatchPolicy}, so MCA applies only the fields this player may change. Saves to anything
 * that is not a bridged character pass straight through, untouched.
 *
 * <p><b>Two editors at once.</b> Each accepted save counts against the character; a session remembers
 * the count it last saw, so the second editor's next save is refused, with a message, and its screen is
 * reopened on the latest version rather than silently writing over the first player's work.
 */
public final class McaEditorBridge {

    /** MCA's villager editor item. */
    public static final ResourceLocation EDITOR_ITEM = McaMobCompat.location(ModIds.MCA, "villager_editor");

    /** How far a player may be from the actor and still save: an editor screen is used standing still. */
    private static final double SAVE_RANGE = 16.0D;

    private record Session(UUID identity, UUID actor, int generation, int saves, long expires) {
    }

    /** One accepted save between the hook's head and its return, so the revision moves exactly once. */
    private record Pending(UUID actor, UUID identity) {
    }

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> SAVES = new ConcurrentHashMap<>();
    private static final ThreadLocal<Pending> PENDING = new ThreadLocal<>();

    private McaEditorBridge() {
    }

    /** Whether this stack is MCA's villager editor. */
    public static boolean isEditor(ItemStack stack) {
        return !stack.isEmpty() && EDITOR_ITEM.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    /** Whether this player may open MCA's editor on this actor's person at all. */
    public static boolean mayOpen(ServerPlayer player, Mob actor, NativeCharacterAdapter adapter) {
        if (player.hasPermissions(2) || player.hasPermissions(adminLevel())) {
            return true;
        }
        EditorPermission policy = ConfigAccess.enumValue(McaMobCompatConfig.SERVER.editorPermission,
                EditorPermission.OWNER_OR_UNOWNED);
        return switch (policy) {
            case ANYONE -> true;
            case OPERATORS -> false;
            case OWNER_OR_UNOWNED -> {
                UUID owner = adapter == null ? null : adapter.editorOwner(actor);
                yield owner == null || owner.equals(player.getUUID());
            }
        };
    }

    /**
     * Opens MCA's editor on the actor's person, through MCA's own item, after the policy allowed it.
     * A crouching click is MCA's own "inventory" use of the item, on the same person.
     */
    public static InteractionResult open(ServerPlayer player, Mob actor, Villager identity, InteractionHand hand) {
        if (!Hooks.armed(Hooks.MCA_EDITOR_SAVE)) {
            // Without the save hook a bridged person's edits could not be checked; refuse rather than
            // open an editor whose saves would go through unchecked.
            player.displayClientMessage(Component.translatable("message.mcamobcompat.editor.unavailable_build"), true);
            return InteractionResult.FAIL;
        }
        NativeCharacterAdapter adapter = NativeCharacterAdapters.claimant(actor);
        if (!mayOpen(player, actor, adapter)) {
            player.displayClientMessage(Component.translatable("message.mcamobcompat.editor.denied", actor.getName()), true);
            return InteractionResult.FAIL;
        }
        GuardIdentityData.Entry entry = GuardIdentityStore.record(actor);
        int generation = entry == null ? 0 : entry.generation();
        long expires = actor.level().getGameTime()
                + 20L * 60L * ConfigAccess.integer(McaMobCompatConfig.SERVER.editorSessionMinutes, 10);
        SESSIONS.put(player.getUUID(), new Session(identity.getUUID(), actor.getUUID(), generation,
                SAVES.getOrDefault(identity.getUUID(), 0), expires));
        ItemStack stack = player.getItemInHand(hand);
        return stack.interactLivingEntity(player, identity, hand);
    }

    /**
     * The editor hook's head. True lets MCA apply the save -- with the payload rebuilt in place for a
     * bridged character -- and false refuses it before anything changed.
     */
    public static boolean beforeSave(Object request, ServerPlayer player) {
        PENDING.remove();
        Optional<McaBinding.EditorRequest> read = McaBinding.editorRequest(request);
        MinecraftServer server = player == null ? null : player.getServer();
        if (read.isEmpty() || server == null) {
            return true;
        }
        McaBinding.EditorRequest editor = read.get();
        GuardIdentityData data = GuardIdentityStore.get(server);
        GuardIdentityData.Entry entry = data.findByProxy(editor.target());
        if (entry == null) {
            // A save naming a bridged actor itself would have MCA treat a native NPC as a villager.
            if (data.find(editor.target()) != null) {
                refuse(player, "message.mcamobcompat.editor.actor", null);
                return false;
            }
            return true;
        }
        Hooks.called(Hooks.MCA_EDITOR_SAVE);
        if (data.isReadOnly()) {
            refuse(player, "message.mcamobcompat.editor.read_only", null);
            return false;
        }
        Entity found = player.serverLevel().getEntity(editor.target());
        Mob actor = GuardIdentityStore.resolveLoaded(server, entry.uuid());
        Session session = SESSIONS.get(player.getUUID());
        if (!(found instanceof Villager identity) || actor == null || !actor.isAlive()) {
            refuse(player, "message.mcamobcompat.editor.unavailable", null);
            return false;
        }
        if (session == null || !session.identity().equals(identity.getUUID())
                || player.level().getGameTime() > session.expires()) {
            refuse(player, "message.mcamobcompat.editor.no_session", actor);
            return false;
        }
        if (actor.level() != player.level() || player.distanceToSqr(actor) > SAVE_RANGE * SAVE_RANGE) {
            refuse(player, "message.mcamobcompat.editor.too_far", actor);
            return false;
        }
        if (entry.generation() != session.generation() || !entry.uuid().equals(session.actor())
                || entry.openTransition() != null) {
            SESSIONS.remove(player.getUUID());
            refuse(player, "message.mcamobcompat.editor.changed", actor);
            reopen(player, actor, identity);
            return false;
        }
        int saves = SAVES.getOrDefault(identity.getUUID(), 0);
        if (saves != session.saves()) {
            SESSIONS.remove(player.getUUID());
            refuse(player, "message.mcamobcompat.editor.conflict", actor);
            reopen(player, actor, identity);
            return false;
        }
        CompoundTag current = new CompoundTag();
        identity.addAdditionalSaveData(current);
        boolean administrator = player.hasPermissions(adminLevel());
        EditorPatchPolicy.Decision decision = EditorPatchPolicy.rebuild(editor.command(), editor.data(), current,
                administrator);
        if (!decision.allowed()) {
            refuse(player, decision.reasonKey(), actor);
            return false;
        }
        PENDING.set(new Pending(actor.getUUID(), identity.getUUID()));
        return true;
    }

    /** The editor hook's return: the save went through, so the character's revision moves once. */
    public static void afterSave(Object request, ServerPlayer player) {
        Pending pending = PENDING.get();
        PENDING.remove();
        if (pending == null || player == null || player.getServer() == null) {
            return;
        }
        int saves = SAVES.merge(pending.identity(), 1, Integer::sum);
        SESSIONS.computeIfPresent(player.getUUID(), (id, session) -> session.identity().equals(pending.identity())
                ? new Session(session.identity(), session.actor(), session.generation(), saves, session.expires())
                : session);
        Mob actor = GuardIdentityStore.resolveLoaded(player.getServer(), pending.actor());
        Entity identity = player.serverLevel().getEntity(pending.identity());
        if (identity instanceof Villager villager) {
            // MCA reads the saved flags back in; a hidden person must not be visible for even one tick.
            GuardProxy.hide(villager);
            CharacterSync.appearanceChanged(actor, villager);
        } else {
            CharacterSync.appearanceChanged(actor, null);
        }
    }

    /** Opens MCA's editor again on the latest version, if the player still holds the editor. */
    private static void reopen(ServerPlayer player, Mob actor, Villager identity) {
        for (InteractionHand hand : InteractionHand.values()) {
            if (isEditor(player.getItemInHand(hand))) {
                open(player, actor, identity, hand);
                return;
            }
        }
    }

    private static void refuse(ServerPlayer player, String key, Mob actor) {
        player.displayClientMessage(Component.translatable(key,
                actor == null ? Component.literal("?") : actor.getName()), false);
    }

    private static int adminLevel() {
        return ConfigAccess.integer(McaMobCompatConfig.SERVER.editorAdminPermissionLevel, 2);
    }

    /** Drops a player's session, when they log out. */
    public static void forget(UUID player) {
        if (player != null) {
            SESSIONS.remove(player);
        }
    }

    /** Drops every session and count; called when the server stops. */
    public static void clear() {
        SESSIONS.clear();
        SAVES.clear();
        PENDING.remove();
    }
}
