package dev.otectus.mcamobcompat.threat;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.McaMobCompatConfig;
import dev.otectus.mcamobcompat.util.ConfigAccess;
import dev.otectus.mcamobcompat.util.ModIds;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.npc.Villager;

import java.util.Comparator;
import java.util.List;

/**
 * Gathers the facts {@link ThreatPolicy} decides on, for the handler and the diagnostic command.
 *
 * <p>Which mobs count is data: the {@code mcamobcompat:npc_threats} entity tag, shipped with
 * {@code minecraft:creeper}. A pack adds a mod's exploding or ambushing mob with one line. The
 * creeper-only facts (a burning fuse, a charge) are read through vanilla {@link Creeper}; any other
 * tagged mob is judged on distance, sight and whom it is hunting.
 */
public final class ThreatAssessor {

    /** Mobs an MCA villager should treat as a threat to react to before it is hurt. */
    public static final ResourceLocation NPC_THREATS_ID = McaMobCompat.id("npc_threats");

    /** MCA's two combat professions, by id. */
    private static final ResourceLocation MCA_GUARD = McaMobCompat.location(ModIds.MCA, "guard");
    private static final ResourceLocation MCA_ARCHER = McaMobCompat.location(ModIds.MCA, "archer");

    /** How close a threat's quarry must be to this villager for the villager to feel hunted. */
    private static final double HUNTED_RADIUS_SQR = 16.0D;

    /** At most this many nearest threats are weighed per scan. */
    public static final int MAX_THREATS = 4;

    private static volatile TagKey<EntityType<?>> npcThreats;

    private ThreatAssessor() {
    }

    /** One threat, with the facts and the decision. */
    public record Assessment(LivingEntity threat, ThreatPolicy.Facts facts, ThreatPolicy.Response response) {
    }

    public static TagKey<EntityType<?>> npcThreatsTag() {
        TagKey<EntityType<?>> tag = npcThreats;
        if (tag == null) {
            tag = TagKey.create(Registries.ENTITY_TYPE, NPC_THREATS_ID);
            npcThreats = tag;
        }
        return tag;
    }

    /** The operator's current tuning. */
    public static ThreatPolicy.Settings settings() {
        return new ThreatPolicy.Settings(
                ConfigAccess.integer(McaMobCompatConfig.SERVER.creeperAlertRange, 8),
                ConfigAccess.integer(McaMobCompatConfig.SERVER.swellingFleeRange, 4),
                ConfigAccess.bool(McaMobCompatConfig.SERVER.guardsEngageCreepers, true));
    }

    /** Whether this entity is a tagged threat. */
    public static boolean isThreat(LivingEntity entity) {
        try {
            return entity.getType().is(npcThreatsTag());
        } catch (RuntimeException unbound) {
            return false;
        }
    }

    /** The nearest tagged threats in range, nearest first, at most {@link #MAX_THREATS}. */
    public static List<LivingEntity> nearbyThreats(ServerLevel level, Villager villager, double range) {
        List<LivingEntity> found = level.getEntitiesOfClass(LivingEntity.class,
                villager.getBoundingBox().inflate(range), candidate -> candidate.isAlive() && isThreat(candidate));
        found.sort(Comparator.comparingDouble(villager::distanceToSqr));
        return found.size() > MAX_THREATS ? found.subList(0, MAX_THREATS) : found;
    }

    /** The facts and the answer for one threat. */
    public static Assessment assess(Villager villager, LivingEntity threat, ThreatPolicy.Settings settings) {
        boolean fuseLit = false;
        boolean powered = false;
        if (threat instanceof Creeper creeper) {
            fuseLit = creeper.getSwellDir() > 0 || creeper.isIgnited();
            powered = creeper.isPowered();
        }
        boolean hunting = false;
        if (threat instanceof Mob mob && mob.getTarget() != null) {
            LivingEntity quarry = mob.getTarget();
            hunting = quarry == villager || quarry.distanceToSqr(villager) <= HUNTED_RADIUS_SQR;
        }
        ResourceLocation profession =
                BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().getProfession());
        boolean combatant = MCA_GUARD.equals(profession) || MCA_ARCHER.equals(profession);
        ThreatPolicy.Facts facts = new ThreatPolicy.Facts(
                villager.distanceTo(threat),
                villager.hasLineOfSight(threat),
                fuseLit,
                powered,
                hunting,
                combatant,
                villager.isBaby(),
                villager.getMaxHealth() <= 0.0F ? 0.0F : villager.getHealth() / villager.getMaxHealth());
        return new Assessment(threat, facts, ThreatPolicy.decide(facts, settings));
    }
}
