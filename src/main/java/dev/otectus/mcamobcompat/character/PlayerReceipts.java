package dev.otectus.mcamobcompat.character;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * What a native mod's own payment took from a player, measured from the inventory before and after,
 * so a rolled-back hire refunds exactly that and nothing else.
 *
 * <p>Recruits takes its fee inside its own hire method; this mod does not know its currency and never
 * guesses it. The difference of two counts is the evidence a refund is made from, recorded in the
 * transition's receipt; with no difference, nothing is refunded.
 */
public final class PlayerReceipts {

    private PlayerReceipts() {
    }

    /** Every item in the player's inventory, counted by item. */
    public static Map<Item, Integer> count(ServerPlayer player) {
        Map<Item, Integer> counts = new HashMap<>();
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    /** Records into {@code receipt} what went from {@code before} to {@code after}: item id to count taken. */
    public static void record(Map<Item, Integer> before, Map<Item, Integer> after, CompoundTag receipt) {
        CompoundTag taken = new CompoundTag();
        for (Map.Entry<Item, Integer> entry : before.entrySet()) {
            int lost = entry.getValue() - after.getOrDefault(entry.getKey(), 0);
            if (lost > 0) {
                taken.putInt(BuiltInRegistries.ITEM.getKey(entry.getKey()).toString(), lost);
            }
        }
        if (!taken.isEmpty()) {
            receipt.put("Taken", taken);
        }
    }

    /** Gives back exactly what the receipt says was taken; anything that does not fit is dropped at the player. */
    public static void refund(ServerPlayer player, CompoundTag receipt) {
        CompoundTag taken = receipt.getCompound("Taken");
        for (String key : taken.getAllKeys()) {
            ResourceLocation id = ResourceLocation.tryParse(key);
            Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            int count = taken.getInt(key);
            int stackSize = item == null ? 0 : new ItemStack(item).getMaxStackSize();
            while (item != null && count > 0) {
                int batch = Math.min(count, stackSize);
                ItemStack stack = new ItemStack(item, batch);
                if (!player.getInventory().add(stack)) {
                    player.drop(stack, false);
                }
                count -= batch;
            }
        }
        receipt.remove("Taken");
        receipt.putBoolean("Refunded", true);
    }
}
