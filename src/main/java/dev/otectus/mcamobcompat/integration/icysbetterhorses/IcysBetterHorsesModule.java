package dev.otectus.mcamobcompat.integration.icysbetterhorses;

import dev.otectus.mcamobcompat.McaMobCompatConfig;
import dev.otectus.mcamobcompat.integration.IntegrationModule;
import dev.otectus.mcamobcompat.mount.MountPolicy;
import dev.otectus.mcamobcompat.util.ModIds;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;

import java.util.Optional;
import java.util.UUID;

/**
 * Icy's Better Horses: its horses are vanilla horses with an owner, a command and (in release 1.1.6)
 * a hitching post of Icy's own. Release 1.1.6 adds no entities, so its horses are classified by the
 * vanilla horse tags; release 2.0 adds fifteen breeds that extend vanilla {@code Horse}, listed in
 * the shipped {@code horse} tag through Icy's own {@code #icys-better-horses:breed_horses}, and a
 * cart that is no animal at all and gets no role. This module reads the facts that matter to an MCA
 * villager:
 * <ul>
 *   <li><b>Owner.</b> A horse Icy says belongs to a player is owned for chore protection, like one
 *       tamed the vanilla way.</li>
 *   <li><b>Riding.</b> Icy lets only an owned horse's owner (and, in 2.0, riders the owner trusts)
 *       take the driver's seat: it refuses any other player and bucks one off. It checks players
 *       only -- a non-player first passenger is left alone in both releases -- so an MCA villager
 *       could still ride off on somebody's horse. This module applies Icy's rule to villagers too:
 *       an owned horse refuses an MCA villager up front, with a reason.</li>
 *   <li><b>Hitching.</b> In 1.1.6, a horse tied to Icy's hitching post stays tied. Release 2.0
 *       has no hitching posts, and this check simply finds nothing to read.</li>
 * </ul>
 * Icy's commands (Follow, Stay, Wander, Return Home) need nothing from here: every one of its command
 * goals runs only while the horse is owned <em>and</em> carries no rider, so a villager on an
 * unowned horse never competes with them -- and a fresh horse's default command, Follow, is inert
 * until somebody owns it. Genetics, gaits, bonding, gear and commands stay entirely Icy's; nothing is
 * written back.
 */
public final class IcysBetterHorsesModule extends IntegrationModule {

    public static final String REASON_OWNED = "message.mcamobcompat.mount.icy_owned";
    public static final String REASON_HITCHED = "message.mcamobcompat.mount.icy_hitched";

    public IcysBetterHorsesModule() {
        super(ModIds.ICYS_BETTER_HORSES, ModIds.ICYS_BETTER_HORSES_ENTITIES, "Icy's Better Horses");
    }

    @Override
    public boolean isEnabled() {
        return safeBoolean(McaMobCompatConfig.COMMON.icysBetterHorsesModule, true);
    }

    @Override
    protected void onActivated() {
        MountPolicy.register(this::refusalFor);
    }

    /** Icy's owner for this entity, when the module is active and ownership is recognised. */
    public Optional<UUID> owner(Entity entity) {
        if (!(entity instanceof AbstractHorse horse) || !isActive()
                || !safeBoolean(McaMobCompatConfig.SERVER.icyRecognizeOwnership, true)) {
            return Optional.empty();
        }
        return IcyReflection.owner(horse);
    }

    private Optional<String> refusalFor(LivingEntity rider, Entity mount) {
        if (!(mount instanceof AbstractHorse horse) || !isActive()) {
            return Optional.empty();
        }
        if (safeBoolean(McaMobCompatConfig.SERVER.icyRecognizeOwnership, true) && IcyReflection.isOwned(horse)) {
            return Optional.of(REASON_OWNED);
        }
        if (IcyReflection.isHitched(horse)) {
            return Optional.of(REASON_HITCHED);
        }
        return Optional.empty();
    }
}
