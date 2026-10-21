package dev.otectus.mcamobcompat.character;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.bridge.GuardInteractions;
import dev.otectus.mcamobcompat.integration.IntegrationModule;
import dev.otectus.mcamobcompat.integration.PlatformArtifacts;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Everything this mod knows about one native mod's NPCs as MCA characters: which entities they are,
 * what MCA's orders mean to them, how their own screens are reached, and which structural changes
 * (enlistment, civilian return, host replacement) are safe on the installed build.
 *
 * <p>An adapter <b>never runs a second controller</b>. Combat, navigation, formations and work stay
 * with the native mod; an adapter only translates an explicit, validated order through the native
 * mod's own operations, and reads native state through the native mod's own accessors. It holds no
 * native type: the actor arrives as a vanilla {@link Mob} and every native member is reached through
 * the integration's binding class.
 *
 * <p>Exactly one adapter is selected per actor (see {@link NativeCharacterAdapters}), most specific
 * first, so a Villager Workers worker -- which is a Villager Recruits recruit by inheritance -- is
 * never claimed by both.
 */
public abstract class NativeCharacterAdapter {

    private final ResourceLocation id;
    private final IntegrationModule module;

    protected NativeCharacterAdapter(String path, IntegrationModule module) {
        this.id = McaMobCompat.id(path);
        this.module = module;
    }

    /** The adapter id, persisted in each record: {@code mcamobcompat:recruits} and so on. */
    public final ResourceLocation id() {
        return id;
    }

    /** The integration module whose mod this adapter speaks for. */
    public final IntegrationModule module() {
        return module;
    }

    public final String displayName() {
        return module.displayName();
    }

    /** The native mod is installed and its module is switched on, on a platform it is verified for. */
    public boolean isActive() {
        return module.isActive() && platformProblem().isEmpty();
    }

    /**
     * Why this adapter is unavailable on this platform -- no verified build of its mod exists for it --
     * or empty when it is available. Reported whether or not a mod with its id is installed.
     */
    public Optional<String> platformProblem() {
        return PlatformArtifacts.unavailable(module.modId(), module.displayName());
    }

    /**
     * Whether the binding this adapter needs resolved on the installed build. Empty when it did; the
     * reason otherwise, which the adapters report shows.
     */
    public Optional<String> bindingProblem() {
        return Optional.empty();
    }

    /** Whether the server gives this adapter's actors an MCA identity at all (its config option). */
    public abstract boolean bridgesActors();

    /** Whether a plain click opens MCA's screen through the hidden identity (its config option). */
    public abstract boolean opensMcaInteraction();

    /** How clients should draw this adapter's actors (its config option). */
    public abstract AppearanceMode appearanceMode();

    /**
     * Whether this adapter claims the actor. Structural and cheap -- it runs for every mob that ticks
     * until the answer is cached per type and class -- and never consults per-entity state.
     */
    public abstract boolean supports(Mob actor);

    /** Whether the actor is one this adapter can bridge right now: active, enabled and supported. */
    public final boolean bridges(Mob actor) {
        return isActive() && bridgesActors() && bindingProblem().isEmpty() && supports(actor);
    }

    /** What the adapter can do for this actor on this build. */
    public Set<Capability> capabilities(Mob actor) {
        EnumSet<Capability> set = EnumSet.of(Capability.IDENTITY, Capability.EDITOR);
        if (opensMcaInteraction()) {
            set.add(Capability.MCA_INTERACTION);
        }
        if (appearanceMode() == AppearanceMode.MCA) {
            set.add(Capability.MCA_APPEARANCE);
        }
        return set;
    }

    /** The native role and owner, read from the native mod's own state. */
    public NativeRoleState inspect(Mob actor) {
        return NativeRoleState.of(EntityType.getKey(actor.getType()).getPath());
    }

    /**
     * Carries out an order through the native mod's own operations, honouring its ownership rules.
     * The default is that the adapter has no such operation.
     */
    public CharacterCommand.Result applyCommand(CharacterCommand.Context context, Mob actor, CharacterCommand command) {
        return CharacterCommand.Result.unsupported("message.mcamobcompat.command.unsupported");
    }

    /**
     * Opens the native mod's own inventory or management screen for this player: the gesture the
     * native mod expects, replayed onto the actor after the Forge hooks were already offered.
     */
    public InteractionResult openNativeInterface(ServerPlayer player, Mob actor, InteractionHand hand) {
        return GuardInteractions.interactAfterHook(player, actor, hand);
    }

    /**
     * Opens the native screen from a command rather than a click: the same gesture as a crouching
     * click, with the crouch set for the one call.
     */
    public InteractionResult openNativeControls(ServerPlayer player, Mob actor) {
        return GuardInteractions.withCrouch(player, true, () -> openNativeInterface(player, actor, InteractionHand.MAIN_HAND));
    }

    /** Whether a held item belongs to the native mod's own controls and must reach the actor natively. */
    public boolean isNativeTool(ItemStack stack) {
        return !stack.isEmpty() && module.namespace().equals(
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace());
    }

    /** Whether this actor is hostile to the player right now, in which case MCA's social screen stays shut. */
    public boolean isHostileTo(Mob actor, Player player) {
        return actor.getTarget() == player;
    }

    /**
     * The profession the identity should carry for this actor, or null to leave whatever it has. The
     * profession is what MCA and its add-ons read, so it decides whether a character is law
     * enforcement; a native combat role is not by itself a reason to make anyone a guard.
     */
    public ResourceLocation identityProfession(Mob actor, CharacterOrigin origin) {
        return null;
    }

    /** Whether paths MCA lays for the identity (an add-on's escort) are replayed onto the actor. */
    public boolean relaysNavigation() {
        return false;
    }

    /** Whether a target an add-on set on the identity is pushed up to this actor. */
    public boolean acceptsAddonTarget(Mob actor, LivingEntity target) {
        return false;
    }

    /**
     * Whether a hit on this actor counts, to MCA and its add-ons, as an assault on the person. A
     * soldier struck in a battle its own side is fighting is not a crime victim; the physical damage
     * lands natively either way.
     */
    public boolean relaysSocialConsequences(Mob actor, Entity attacker) {
        return true;
    }

    /** The actor's custom name if it is a real name, not a generic label the native mod gave it. */
    public Optional<String> meaningfulName(Mob actor) {
        if (!actor.hasCustomName()) {
            return Optional.empty();
        }
        String name = actor.getCustomName().getString();
        return name.isBlank() || genericNames().contains(name) ? Optional.empty() : Optional.of(name);
    }

    /** Labels the native mod uses as names that do not name anybody: "Recruit", "Village Guard". */
    protected List<String> genericNames() {
        return List.of();
    }

    /** Which structural transitions are safe on this build, with a reason for each one that is not. */
    public TransitionSupport transitions() {
        return TransitionSupport.NONE;
    }

    /**
     * Creates, initialises and adds the native actor an enlisting villager becomes, through the native
     * mod's own initialisation and, where it has one, its own hiring and payment. Null, with the reason
     * in the context, when it could not be done; nothing is left in the world and nothing is kept from
     * the player then.
     */
    public Mob createEnlistedActor(EnlistContext context) {
        context.refuse("message.mcamobcompat.enlist.unavailable");
        return null;
    }

    /**
     * Undoes {@link #createEnlistedActor} for an actor the transition could not commit: the native
     * mod's own bookkeeping first, then the payment from the receipt, then the actor itself. The
     * transition removes the actor from the world after this returns.
     */
    public void rollbackEnlistedActor(EnlistContext context, Mob actor) {
    }

    /**
     * Releases the actor from the native mod's own bookkeeping before it leaves the world for a
     * civilian return: groups, unit counts, work assignments, chunk tickets. Must not kill it.
     */
    public void releaseNativeState(Mob actor, ServerPlayer player) {
    }

    /**
     * The actor's possessions to hand to the person on a civilian return: equipment and cargo. The
     * stacks are removed from the actor only by {@link #clearPossessions}, after they have all fitted.
     */
    public List<ItemStack> possessions(Mob actor) {
        return List.of();
    }

    /** Empties what {@link #possessions} reported, once the transfer has been committed. */
    public void clearPossessions(Mob actor) {
    }

    /**
     * The player who owns this actor for the editor policy: the native owner of a hired NPC, or null
     * for one nobody owns, which anyone the policy admits may edit.
     */
    public UUID editorOwner(Mob actor) {
        return inspect(actor).owner();
    }

    /** Whether this player may end the actor's native role and send the person home. */
    public boolean mayReleaseToCivilian(Mob actor, ServerPlayer player) {
        UUID owner = inspect(actor).owner();
        return owner == null ? player.hasPermissions(2) : owner.equals(player.getUUID()) || player.hasPermissions(2);
    }

    /** A short, localised role label for the role line: the native entity's own name. */
    public Component roleLabel(Mob actor) {
        return actor.getType().getDescription();
    }

    /**
     * What an adapter can do structurally, with a translatable reason for anything it cannot. A
     * transition whose interception is not proven on this build is reported here, never attempted.
     */
    public record TransitionSupport(boolean enlist, String enlistReason, boolean returnToCivilian,
                                    String returnReason, boolean hostReplacement, String hostReason) {

        public static final TransitionSupport NONE = new TransitionSupport(
                false, "message.mcamobcompat.enlist.unavailable",
                false, "message.mcamobcompat.release.unavailable",
                false, "message.mcamobcompat.transition.unavailable");
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
