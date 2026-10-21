package dev.otectus.mcamobcompat.character;

import dev.otectus.mcamobcompat.McaMobCompatConfig;
import dev.otectus.mcamobcompat.integration.guardvillagers.GuardVillagersAdapter;
import dev.otectus.mcamobcompat.mca.McaBinding;
import dev.otectus.mcamobcompat.mca.McaMemories;
import dev.otectus.mcamobcompat.util.ConfigAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Carries MCA's Follow, Stay and Move buttons out through the actor's own mod.
 *
 * <p>MCA records an order as a movement state on the villager that received it -- the hidden
 * identity -- and the identity cannot walk. Each change of that state is read once a second and handed
 * to the actor's adapter as one {@link CharacterCommand}, with the player MCA recorded as the one to
 * follow. The adapter applies the native mod's own rules: a recruit takes orders from its owner only,
 * a guard follows only whom Guard Villagers' own follow goal accepts.
 *
 * <p><b>An order is consumed once.</b> The state that was applied is remembered, and nothing is set
 * again until MCA's state changes, so a native order the owner gave afterwards -- through the native
 * mod's own screen -- is never overwritten by an old MCA state that merely persists. An order the
 * native rules refused puts MCA's own state back to Move and tells the player why, so MCA's screen
 * does not claim a soldier is following somebody it will not follow.
 *
 * <p>The optional social pause is here too: a recruit or worker a player is talking to through MCA's
 * screen can be held still briefly, released by distance, damage, the player leaving or a time limit,
 * after which its native order simply carries on.
 */
public final class CharacterCommandRouter {

    /** Persistent-data key on the identity: whom it follows, so a restart keeps the order. */
    private static final String KEY_FOLLOW_TARGET = "mcamobcompat:follow_target";

    /**
     * Persistent-data key on the identity: the movement intent last applied, so a restart does not
     * apply an order a second time over whatever the native mod was told since.
     */
    private static final String KEY_APPLIED_MOVE = "mcamobcompat:applied_move";

    /** The intent recorded while the player to follow is offline, so their return applies it again. */
    private static final String FOLLOW_OFFLINE = "FOLLOW:offline";

    /** Longest social pause: thirty seconds. */
    private static final long PAUSE_TICKS = 600L;

    /** Distance at which a social pause ends, squared. */
    private static final double PAUSE_RANGE_SQR = 8.0D * 8.0D;

    /**
     * The last player to open an actor's MCA screen, by actor UUID -- only a fallback for MCA builds
     * whose following memory cannot be read. MCA's own memory says whom it was told to follow, and
     * merely opening another player's screen must not steal an existing follow order.
     */
    private static final Map<UUID, UUID> LAST_INTERACTION = new ConcurrentHashMap<>();

    /** The movement intent last applied to each actor, so it is applied once per change. */
    private static final Map<UUID, String> LAST_MOVE = new ConcurrentHashMap<>();

    /** Actors held still while a player talks to them. */
    private static final Map<UUID, Pause> PAUSES = new ConcurrentHashMap<>();

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private record Pause(UUID player, long until) {
    }

    private CharacterCommandRouter() {
    }

    /** Records who opened an actor's MCA screen, and starts a social pause if the server wants one. */
    public static void noteInteraction(Mob actor, UUID player) {
        if (actor == null || player == null) {
            return;
        }
        LAST_INTERACTION.put(actor.getUUID(), player);
        NativeCharacterAdapter adapter = NativeCharacterAdapters.bridging(actor);
        if (adapter != null && adapter != GuardVillagersAdapter.INSTANCE
                && ConfigAccess.bool(McaMobCompatConfig.SERVER.socialPause, false)) {
            PAUSES.put(actor.getUUID(), new Pause(player, actor.level().getGameTime() + PAUSE_TICKS));
        }
    }

    /** Holds a paused actor still for this tick, or ends the pause. */
    public static void tickPause(Mob actor) {
        Pause pause = PAUSES.get(actor.getUUID());
        if (pause == null) {
            return;
        }
        MinecraftServer server = actor.getServer();
        ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(pause.player());
        if (player == null || player.level() != actor.level() || actor.level().getGameTime() > pause.until()
                || actor.hurtTime > 0 || actor.getTarget() != null || player.distanceToSqr(actor) > PAUSE_RANGE_SQR) {
            PAUSES.remove(actor.getUUID());
            return;
        }
        actor.getNavigation().stop();
        actor.getLookControl().setLookAt(player, 30.0F, 30.0F);
    }

    /**
     * Applies a change of MCA's movement state through the adapter. Re-applied only when the intent
     * changes, so an actor an owner commanded by hand is not fought every second.
     */
    public static void mirrorMoveState(Mob actor, Villager identity, NativeCharacterAdapter adapter) {
        if (adapter == null || !ConfigAccess.bool(McaMobCompatConfig.SERVER.forwardMcaCommands, true)) {
            return;
        }
        Optional<String> state = McaBinding.moveState(identity);
        if (state.isEmpty()) {
            return;
        }
        String movement = state.get();
        UUID followTarget = null;
        if ("FOLLOW".equals(movement)) {
            followTarget = McaMemories.followTarget(identity).orElse(null);
            if (followTarget != null) {
                identity.getPersistentData().putUUID(KEY_FOLLOW_TARGET, followTarget);
            } else if (identity.getPersistentData().hasUUID(KEY_FOLLOW_TARGET)) {
                followTarget = identity.getPersistentData().getUUID(KEY_FOLLOW_TARGET);
            } else {
                followTarget = LAST_INTERACTION.get(actor.getUUID());
            }
            if (followTarget == null || actor.getServer() == null
                    || actor.getServer().getPlayerList().getPlayer(followTarget) == null) {
                // Nobody to follow right now: stop following rather than follow nobody, and try again
                // when the player is back.
                if (!FOLLOW_OFFLINE.equals(LAST_MOVE.get(actor.getUUID()))) {
                    adapter.applyCommand(new CharacterCommand.Context(CharacterCommand.Source.MCA_SCREEN, null,
                            SEQUENCE.incrementAndGet()), actor, CharacterCommand.FOLLOW);
                    remember(actor, identity, FOLLOW_OFFLINE);
                }
                return;
            }
        } else {
            identity.getPersistentData().remove(KEY_FOLLOW_TARGET);
        }

        String intent = movement + ":" + followTarget;
        String applied = LAST_MOVE.get(actor.getUUID());
        if (applied == null) {
            // The first look since this actor loaded. The identity remembers what was last applied; one
            // that never had anything applied starts from MCA's state as it stands. A state that merely
            // persisted is not a new order, and the native mod's own order stands until MCA's changes.
            CompoundTag data = identity.getPersistentData();
            applied = data.contains(KEY_APPLIED_MOVE) ? data.getString(KEY_APPLIED_MOVE) : intent;
            remember(actor, identity, applied);
        }
        if (intent.equals(applied)) {
            return;
        }
        remember(actor, identity, intent);
        CharacterCommand command = switch (movement) {
            case "FOLLOW" -> CharacterCommand.FOLLOW;
            case "STAY" -> CharacterCommand.STAY;
            default -> CharacterCommand.MOVE;
        };
        UUID initiator = followTarget != null ? followTarget : LAST_INTERACTION.get(actor.getUUID());
        CharacterCommand.Result result = adapter.applyCommand(
                new CharacterCommand.Context(CharacterCommand.Source.MCA_SCREEN, initiator, SEQUENCE.incrementAndGet()),
                actor, command);
        if (result.outcome() == CharacterCommand.Outcome.REFUSED && command != CharacterCommand.MOVE) {
            if (McaBinding.setMoveState(identity, "MOVE")) {
                remember(actor, identity, "MOVE:null");
                identity.getPersistentData().remove(KEY_FOLLOW_TARGET);
            }
            tell(actor, initiator, result.reasonKey());
        }
    }

    /** Applies a command from a role action, with the same native rules as MCA's buttons. */
    public static CharacterCommand.Result command(Mob actor, ServerPlayer player, CharacterCommand command) {
        NativeCharacterAdapter adapter = NativeCharacterAdapters.bridging(actor);
        if (adapter == null) {
            return CharacterCommand.Result.unsupported("message.mcamobcompat.character.not_bridged");
        }
        return adapter.applyCommand(new CharacterCommand.Context(CharacterCommand.Source.COMMAND, player.getUUID(),
                SEQUENCE.incrementAndGet()), actor, command);
    }

    private static void tell(Mob actor, UUID player, String reasonKey) {
        if (player == null || reasonKey == null || reasonKey.isEmpty() || actor.getServer() == null) {
            return;
        }
        ServerPlayer target = actor.getServer().getPlayerList().getPlayer(player);
        if (target != null) {
            target.displayClientMessage(Component.translatable(reasonKey, actor.getName()), true);
        }
    }

    /**
     * Forgets the orders an identity carried in its last role, when it starts a new one or goes back
     * to civilian life, so MCA's state at that moment is the starting point rather than an order.
     */
    public static void resetOrders(Villager identity) {
        if (identity != null) {
            identity.getPersistentData().remove(KEY_APPLIED_MOVE);
            identity.getPersistentData().remove(KEY_FOLLOW_TARGET);
        }
    }

    private static void remember(Mob actor, Villager identity, String intent) {
        LAST_MOVE.put(actor.getUUID(), intent);
        identity.getPersistentData().putString(KEY_APPLIED_MOVE, intent);
    }

    /** Forgets an actor's transient state. */
    public static void forget(Mob actor) {
        if (actor != null) {
            UUID id = actor.getUUID();
            LAST_INTERACTION.remove(id);
            LAST_MOVE.remove(id);
            PAUSES.remove(id);
        }
    }

    public static void clear() {
        LAST_INTERACTION.clear();
        LAST_MOVE.clear();
        PAUSES.clear();
    }
}
