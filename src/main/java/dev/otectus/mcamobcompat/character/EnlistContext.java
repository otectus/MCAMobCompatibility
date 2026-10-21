package dev.otectus.mcamobcompat.character;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;

/**
 * One enlistment, as the transition hands it to an adapter: who is enlisting whom into which role,
 * with what, and -- filled in by the adapter -- what it took from the player and why it refused.
 *
 * <p>The receipt is the evidence a rollback refunds from: exactly what the native mod's own payment
 * removed from the player's inventory, measured, never assumed.
 */
public final class EnlistContext {

    /**
     * Persistent-data key on an actor an enlistment is creating, from just before it joins the level
     * until the transition commits: its own join must not give it an adopted record of its own.
     */
    public static final String KEY_ENLISTING = dev.otectus.mcamobcompat.McaMobCompat.MOD_ID + ":enlisting";

    private final ServerLevel level;
    private final Villager person;
    private final ServerPlayer player;
    private final ItemStack payment;
    private final ResourceLocation role;
    private final Object group;
    private final CompoundTag receipt = new CompoundTag();
    private String refusal = "";

    public EnlistContext(ServerLevel level, Villager person, ServerPlayer player, ItemStack payment,
                         ResourceLocation role, Object group) {
        this.level = level;
        this.person = person;
        this.player = player;
        this.payment = payment == null ? ItemStack.EMPTY : payment;
        this.role = role;
        this.group = group;
    }

    public ServerLevel level() {
        return level;
    }

    public Villager person() {
        return person;
    }

    public ServerPlayer player() {
        return player;
    }

    /** The item the gesture offers -- Guard Villagers' weapon -- or empty. Never consumed by the adapter. */
    public ItemStack payment() {
        return payment;
    }

    /** The native entity type to enlist into, or null for the adapter's default. */
    public ResourceLocation role() {
        return role;
    }

    /** The native group the native hire named (a Recruits group), or null. */
    public Object group() {
        return group;
    }

    /** What the native payment took, by item id and count. Mutable: the adapter records into it. */
    public CompoundTag receipt() {
        return receipt;
    }

    /** Marks a new actor as this enlistment's, before the adapter adds it to the level. */
    public void prepare(net.minecraft.world.entity.Mob actor) {
        actor.getPersistentData().putBoolean(KEY_ENLISTING, true);
    }

    public void refuse(String reasonKey) {
        if (refusal.isEmpty()) {
            refusal = reasonKey;
        }
    }

    public String refusal() {
        return refusal.isEmpty() ? "message.mcamobcompat.enlist.unavailable" : refusal;
    }
}
