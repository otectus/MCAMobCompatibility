package dev.otectus.mcamobcompat.mount;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.mca.McaEntities;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Which mounts an MCA villager may be put on.
 *
 * <p><b>Recognition is not capability.</b> MCA seats a villager on any saddled horse a player
 * points it at, and on a vanilla horse MCA then steers: a vanilla horse makes its first passenger,
 * the villager, its controlling passenger, and MCA steers the horse through that. A mount that lets
 * only a player drive -- SWEM's horses name only a player as their controlling passenger, and count
 * only a player as a rider at all -- takes the villager but never its orders, and goes on under its
 * own AI as if unridden, with the villager carried along wherever that AI takes it. That cannot be
 * fixed from outside the mount without a mixin, so such mounts are listed in the {@code
 * mcamobcompat:player_only_mounts} tag and refused, and the player is told why, rather than the
 * order looking like it worked.
 *
 * <p>Integrations add their own reasons through {@link MountRefusal}: an Icy's Better Horses horse
 * that belongs to a player, or is hitched to a post, is not the villager's to take.
 */
public final class MountPolicy {

    /** Mounts that only a player can steer. */
    public static final ResourceLocation PLAYER_ONLY_MOUNTS_ID = McaMobCompat.id("player_only_mounts");

    public static final String REASON_PLAYER_ONLY = "message.mcamobcompat.mount.player_only";

    private static final List<MountRefusal> REFUSALS = new CopyOnWriteArrayList<>();

    private static volatile TagKey<EntityType<?>> playerOnly;

    private MountPolicy() {
    }

    /** Adds an integration's refusal. Idempotent. */
    public static void register(MountRefusal refusal) {
        if (refusal != null && !REFUSALS.contains(refusal)) {
            REFUSALS.add(refusal);
        }
    }

    public static TagKey<EntityType<?>> playerOnlyMountsTag() {
        TagKey<EntityType<?>> tag = playerOnly;
        if (tag == null) {
            tag = TagKey.create(Registries.ENTITY_TYPE, PLAYER_ONLY_MOUNTS_ID);
            playerOnly = tag;
        }
        return tag;
    }

    /** Whether this mount lets only a player steer it. */
    public static boolean playerOnly(Entity mount) {
        try {
            return mount != null && mount.getType().is(playerOnlyMountsTag());
        } catch (RuntimeException unbound) {
            return false;
        }
    }

    /** The decision for this rider and mount. Anything but an MCA villager is always allowed. */
    public static MountDecision decide(LivingEntity rider, Entity mount) {
        if (!McaEntities.isMcaVillager(rider) || mount == null) {
            return MountDecision.ALLOW;
        }
        return decide(playerOnly(mount), REFUSALS.stream().map(refusal -> safely(refusal, rider, mount)).toList());
    }

    /**
     * The decision for an MCA villager and this mount, without a particular villager -- for the
     * diagnostic command. Refusals that depend on who the rider is are asked with a null rider.
     */
    public static MountDecision decideForAnyVillager(Entity mount) {
        if (mount == null) {
            return MountDecision.ALLOW;
        }
        return decide(playerOnly(mount), REFUSALS.stream().map(refusal -> safely(refusal, null, mount)).toList());
    }

    /** The decision from the facts alone. Package-visible for the unit test. */
    static MountDecision decide(boolean playerOnly, List<Optional<String>> refusals) {
        if (playerOnly) {
            return MountDecision.refuse(REASON_PLAYER_ONLY);
        }
        for (Optional<String> refusal : refusals) {
            if (refusal.isPresent()) {
                return MountDecision.refuse(refusal.get());
            }
        }
        return MountDecision.ALLOW;
    }

    private static Optional<String> safely(MountRefusal refusal, LivingEntity rider, Entity mount) {
        try {
            Optional<String> reason = refusal.refusalFor(rider, mount);
            return reason == null ? Optional.empty() : reason;
        } catch (RuntimeException e) {
            McaMobCompat.LOGGER.warn("A mount refusal check threw; ignoring it for this mount", e);
            return Optional.empty();
        }
    }

    /** How a villager rider would fare, for the diagnostic command. */
    public static String npcControl(Entity mount) {
        if (playerOnly(mount)) {
            return "player-only (listed in " + PLAYER_ONLY_MOUNTS_ID + ")";
        }
        if (mount instanceof AbstractHorse) {
            return "vanilla horse control (MCA steers it)";
        }
        return "unknown (not a horse; MCA does not ride it)";
    }
}
