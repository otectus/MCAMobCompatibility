package dev.otectus.mcamobcompat.hook.mixin;

import dev.otectus.mcamobcompat.character.RecruitsConversions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The Villager Recruits conversion hooks: the three places Recruits turns a villager into one of its
 * NPCs and discards the villager -- for an MCA villager, its family, genes, memories and possessions.
 *
 * <p><b>Why hooks.</b> Each of these paths creates the new NPC and calls {@code discard()} on the
 * villager in one method, with no event between; the only Recruits event inside is posted before the
 * villager is chosen, and it does not name the villager. An after-the-fact repair would have to find a
 * discarded villager by position, which two villagers side by side defeat. So each method is entered
 * once, at its head, and for an MCA villager the original never runs:
 * <ul>
 *   <li>{@code createRecruit} -- a villager whose profession is a recruit profession, converted from
 *       Recruits' living-tick listener with no player involved: refused, and the profession put back.</li>
 *   <li>{@code createNobleVillager} -- a jobless villager becoming a noble: refused.</li>
 *   <li>{@code createHiredRecruitFromVillager} -- the hire a player bought on a noble's screen, for a
 *       recruit or a worker: replaced by the enlistment of the same person
 *       ({@code character.CharacterTransitions}), or refused unpaid, by the server's policy.</li>
 * </ul>
 * Vanilla villagers, and every other Recruits path, are untouched.
 *
 * <p>{@code RecruitsGroup} is Recruits' own type, so the hire's group arrives as {@link Coerce}d
 * {@code Object}. This class holds Recruits' dotted class name and is the exact entry the build's
 * foreign-name scans allow it in; it names no Recruits type.
 */
@Pseudo
@Mixin(targets = "com.talhanation.recruits.VillagerEvents", remap = false)
public abstract class RecruitsConversionHook {

    @Inject(method = "createRecruit", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void mcamobcompat$createRecruit(Villager villager, EntityType<?> type, CallbackInfo callback) {
        if (RecruitsConversions.refuseAutomatic(villager, RecruitsConversions.Path.PROFESSION)) {
            callback.cancel();
        }
    }

    @Inject(method = "createNobleVillager", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void mcamobcompat$createNoble(Villager villager, CallbackInfo callback) {
        if (RecruitsConversions.refuseAutomatic(villager, RecruitsConversions.Path.NOBLE)) {
            callback.cancel();
        }
    }

    @Inject(method = "createHiredRecruitFromVillager", at = @At("HEAD"), cancellable = true, remap = false,
            require = 0)
    private static void mcamobcompat$hireFromVillager(ServerLevel level, Villager villager, EntityType<?> type,
                                                    Player player, @Coerce Object group, CallbackInfo callback) {
        if (RecruitsConversions.interceptHire(level, villager, type, player, group)) {
            callback.cancel();
        }
    }
}
