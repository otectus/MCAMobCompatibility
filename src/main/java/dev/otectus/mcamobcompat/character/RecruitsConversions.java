package dev.otectus.mcamobcompat.character;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.bridge.GuardProxy;
import dev.otectus.mcamobcompat.hook.Hooks;
import dev.otectus.mcamobcompat.integration.ModIntegrations;
import dev.otectus.mcamobcompat.mca.McaEntities;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.player.Player;

/**
 * What happens when Villager Recruits reaches for an MCA villager to turn into one of its NPCs.
 *
 * <p>Each of Recruits' conversions discards the villager, and an MCA villager discarded is a family
 * member, a set of genes, a marriage and a life's possessions gone; so none of them ever runs on one.
 * Vanilla villagers are untouched: every method here answers "not mine" for them first. Called from
 * the Recruits hooks ({@code hook.mixin.RecruitsConversionHook}) at the head of each conversion.
 */
public final class RecruitsConversions {

    /** Recruits' conversions that no player asked for. */
    public enum Path {
        /** A villager that took a recruit profession at one of Recruits' work blocks. */
        PROFESSION,
        /** A jobless villager becoming a noble. */
        NOBLE
    }

    private static volatile boolean loggedProfession;
    private static volatile boolean loggedNoble;

    private RecruitsConversions() {
    }

    /**
     * A conversion nobody asked for: refused for an MCA villager. The profession case also puts the
     * villager's profession back and frees the work block, or Recruits' living-tick listener would ask
     * again on the very next tick.
     *
     * @return whether to cancel the conversion
     */
    public static boolean refuseAutomatic(Villager villager, Path path) {
        if (villager == null || !McaEntities.isMcaVillager(villager) || !ModIntegrations.recruits().isActive()) {
            return false;
        }
        Hooks.called(path == Path.PROFESSION ? Hooks.RECRUITS_CREATE_RECRUIT : Hooks.RECRUITS_CREATE_NOBLE);
        if (GuardProxy.isProxy(villager)) {
            return true;
        }
        if (path == Path.PROFESSION) {
            villager.releasePoi(MemoryModuleType.JOB_SITE);
            villager.releasePoi(MemoryModuleType.POTENTIAL_JOB_SITE);
            villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.NONE));
            if (villager.level() instanceof ServerLevel level) {
                villager.refreshBrain(level);
            }
            if (!loggedProfession) {
                loggedProfession = true;
                McaMobCompat.LOGGER.info("Villager Recruits tried to turn MCA villager {} into a recruit for its "
                        + "profession; refused, and the profession put back. (Logged once.)", villager.getUUID());
            }
        } else if (!loggedNoble) {
            loggedNoble = true;
            McaMobCompat.LOGGER.info("Villager Recruits tried to turn MCA villager {} into a noble; refused. "
                    + "(Logged once.)", villager.getUUID());
        }
        return true;
    }

    /**
     * A player's hire on a noble's screen picked an MCA villager. By the server's policy the same person
     * is enlisted through Recruits' own hire -- or the hire is refused before anything is paid. Either
     * way Recruits' own conversion does not run.
     *
     * @return whether to cancel Recruits' conversion
     */
    public static boolean interceptHire(ServerLevel level, Villager villager, EntityType<?> type, Player player,
                                        Object group) {
        if (villager == null || !McaEntities.isMcaVillager(villager)
                || (!ModIntegrations.recruits().isActive() && !ModIntegrations.workers().isActive())) {
            return false;
        }
        Hooks.called(Hooks.RECRUITS_HIRE_FROM_VILLAGER);
        ServerPlayer serverPlayer = player instanceof ServerPlayer sp ? sp : null;
        if (GuardProxy.isProxy(villager)) {
            tell(serverPlayer, "message.mcamobcompat.recruits.hire_identity", villager);
            return true;
        }
        if (serverPlayer == null || ModIntegrations.recruits().villagerConversion() == RecruitsConversionPolicy.BLOCK) {
            tell(serverPlayer, "message.mcamobcompat.recruits.hire_blocked", villager);
            return true;
        }
        CharacterTransitions.Outcome outcome = CharacterTransitions.enlistHired(level, villager, type, serverPlayer, group);
        tell(serverPlayer, outcome.messageKey(), villager);
        return true;
    }

    private static void tell(ServerPlayer player, String key, Villager villager) {
        if (player != null && key != null && !key.isEmpty()) {
            player.displayClientMessage(Component.translatable(key, villager.getName()), false);
        }
    }
}
