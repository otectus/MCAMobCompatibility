package dev.otectus.mcamobcompat.threat;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.McaMobCompatConfig;
import dev.otectus.mcamobcompat.event.GuardAlerts;
import dev.otectus.mcamobcompat.integration.ModIntegrations;
import dev.otectus.mcamobcompat.mca.McaEntities;
import dev.otectus.mcamobcompat.mca.McaMemories;
import dev.otectus.mcamobcompat.util.ConfigAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lets MCA villagers see a creeper coming, and answer it the way MCA itself would.
 *
 * <p>MCA already reacts to a creeper -- but only one lit with flint and steel, through its own
 * {@code ExplodingCreeperSensor}. An ordinary creeper that walks up and starts to swell is invisible
 * to MCA until the explosion's damage arrives, by which time there is nobody left to react. This
 * closes that gap without adding a single goal or task to MCA's brain: it writes the same memories
 * MCA's own sensors write, and MCA's own behaviours do the rest.
 * <ul>
 *   <li><b>Flee</b>: vanilla's {@code NEAREST_HOSTILE} memory, which MCA's panic package reacts to,
 *       and a {@code WALK_TARGET} away from the threat, which MCA's movement follows. A mounted
 *       villager dismounts first when the operator allows it, since nothing here can steer its
 *       mount.</li>
 *   <li><b>Engage</b>: MCA's own {@code mca:nearest_guard_enemy} memory, which MCA's guard combat
 *       acts on -- the same seam MCA's guard sensor uses.</li>
 *   <li><b>Watch</b>: a look target, and nothing else.</li>
 * </ul>
 *
 * <p>Nearby Guard Villagers guards are alerted through the shared, bounded {@link GuardAlerts}; Guard
 * Villagers' own do-not-attack list names creepers by default, and that list is respected, never
 * edited. Creepers, their explosions, iron golems and every gamerule -- including Golems Kill
 * Creepers' {@code doIronGolemsAttackCreepers} and {@code doIronGolemsInstakillCreepers} -- are left
 * exactly as they are.
 *
 * <p><b>Survival outranks routine combat.</b> A creeper whose fuse is burning within blast reach is
 * an emergency: for as long as it lasts, every tick of the villager's own clears that creeper from
 * MCA's attack and guard-enemy memories and keeps it running, because MCA's guard brain (7.7 in
 * particular) will otherwise re-acquire it on its next tick and walk into the blast.
 *
 * <p>Cost: each MCA villager scans every half second, staggered by entity id, over a small box, and
 * weighs at most four threats. Hidden proxies (brainless) and sleeping villagers are skipped. The
 * per-tick emergency hold touches only villagers in an emergency, a handful at most.
 */
@EventBusSubscriber(modid = McaMobCompat.MOD_ID)
public final class ThreatHandler {

    /** Ticks between scans for one villager. */
    public static final int SCAN_INTERVAL = 10;

    /** How long the hostile memory lasts without being renewed. */
    private static final long HOSTILE_MEMORY_TICKS = 40L;

    /** How far a fleeing villager tries to run, and how fast. */
    private static final int FLEE_DISTANCE = 16;
    private static final int FLEE_VERTICAL = 7;
    private static final float FLEE_SPEED = 0.75F;

    /** How long one deadly sighting holds the emergency, in ticks; renewed while it lasts. */
    private static final long EMERGENCY_TICKS = SCAN_INTERVAL + 5L;

    private record Emergency(LivingEntity threat, long until) {
    }

    /** Villagers in an emergency, by UUID. */
    private static final Map<UUID, Emergency> EMERGENCIES = new ConcurrentHashMap<>();

    private ThreatHandler() {
    }

    /** Forgets every emergency; called when the server stops. */
    public static void clear() {
        EMERGENCIES.clear();
    }

    @SubscribeEvent
    public static void onLivingTick(EntityTickEvent.Pre event) {
        if (!(event.getEntity() instanceof Villager villager) || !(villager.level() instanceof ServerLevel level)) {
            return;
        }
        if (!EMERGENCIES.isEmpty()) {
            holdEmergency(villager);
        }
        if ((villager.tickCount + villager.getId()) % SCAN_INTERVAL != 0 || !McaEntities.isMcaVillager(villager)
                || villager.isNoAi() || !villager.isAlive() || villager.isSleeping()) {
            return;
        }
        if (!ConfigAccess.bool(McaMobCompatConfig.SERVER.creeperAwareness, true)) {
            return;
        }
        ThreatPolicy.Settings settings = ThreatAssessor.settings();
        List<LivingEntity> threats = ThreatAssessor.nearbyThreats(level, villager, settings.alertRange());
        if (threats.isEmpty()) {
            return;
        }
        ThreatAssessor.Assessment chosen = null;
        for (LivingEntity threat : threats) {
            ThreatAssessor.Assessment assessment = ThreatAssessor.assess(villager, threat, settings);
            if (chosen == null || assessment.response().ordinal() > chosen.response().ordinal()) {
                chosen = assessment;
            }
        }
        apply(level, villager, chosen);
    }

    /** Keeps a villager in an emergency out of the fight and on the run, every tick until it ends. */
    private static void holdEmergency(Villager villager) {
        Emergency emergency = EMERGENCIES.get(villager.getUUID());
        if (emergency == null) {
            return;
        }
        LivingEntity threat = emergency.threat();
        if (!villager.isAlive() || !threat.isAlive() || villager.level().getGameTime() > emergency.until()
                || threat.level() != villager.level()) {
            EMERGENCIES.remove(villager.getUUID());
            return;
        }
        Brain<Villager> brain = villager.getBrain();
        if (McaMemories.registers(villager, MemoryModuleType.ATTACK_TARGET)
                && brain.getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) == threat) {
            brain.eraseMemory(MemoryModuleType.ATTACK_TARGET);
        }
        McaMemories.clearGuardEnemy(villager, threat);
        runFrom(villager, brain, threat);
    }

    private static void apply(ServerLevel level, Villager villager, ThreatAssessor.Assessment assessment) {
        LivingEntity threat = assessment.threat();
        Brain<Villager> brain = villager.getBrain();
        switch (assessment.response()) {
            case FLEE -> {
                if (villager.isPassenger() && ConfigAccess.bool(McaMobCompatConfig.SERVER.dismountToFlee, true)) {
                    villager.stopRiding();
                }
                if (McaMemories.registers(villager, MemoryModuleType.NEAREST_HOSTILE)) {
                    brain.setMemoryWithExpiry(MemoryModuleType.NEAREST_HOSTILE, threat, HOSTILE_MEMORY_TICKS);
                }
                if (McaMemories.registers(villager, MemoryModuleType.ATTACK_TARGET)
                        && brain.getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) == threat) {
                    brain.eraseMemory(MemoryModuleType.ATTACK_TARGET);
                }
                McaMemories.clearGuardEnemy(villager, threat);
                runFrom(villager, brain, threat);
                if (ThreatPolicy.deadly(assessment.facts(), ThreatAssessor.settings())) {
                    EMERGENCIES.put(villager.getUUID(), new Emergency(threat, level.getGameTime() + EMERGENCY_TICKS));
                }
                if (threat instanceof Creeper && ModIntegrations.guardVillagers().protectsVillagers()) {
                    GuardAlerts.alert(level, villager, threat,
                            ConfigAccess.integer(McaMobCompatConfig.SERVER.guardHelpRange, 32),
                            ConfigAccess.integer(McaMobCompatConfig.SERVER.maxGuardsAlerted, 8));
                }
                McaMobCompat.debug("{} flees {} ({})", villager.getName().getString(),
                        threat.getName().getString(), assessment.facts());
            }
            case ENGAGE -> {
                if (McaMemories.setGuardEnemy(villager, threat)) {
                    McaMobCompat.debug("{} engages {}", villager.getName().getString(), threat.getName().getString());
                }
            }
            case WATCH -> {
                if (McaMemories.registers(villager, MemoryModuleType.LOOK_TARGET)) {
                    brain.setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(threat, true));
                }
            }
            default -> {
            }
        }
    }

    /** Gives MCA's movement a destination away from the threat, unless it already has one. */
    private static void runFrom(Villager villager, Brain<Villager> brain, LivingEntity threat) {
        if (!McaMemories.registers(villager, MemoryModuleType.WALK_TARGET)) {
            return;
        }
        WalkTarget current = brain.getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
        if (current != null) {
            Vec3 goal = current.getTarget().currentPosition();
            if (goal.distanceToSqr(threat.position()) > villager.distanceToSqr(threat) + 4.0D
                    && goal.distanceToSqr(villager.position()) > 4.0D) {
                return;
            }
        }
        Vec3 away = DefaultRandomPos.getPosAway(villager, FLEE_DISTANCE, FLEE_VERTICAL, threat.position());
        if (away == null) {
            // A cramped spot can defeat the wide search; a short run on solid ground still helps.
            away = LandRandomPos.getPosAway(villager, FLEE_DISTANCE / 2, FLEE_VERTICAL, threat.position());
        }
        if (away != null) {
            brain.setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(away, FLEE_SPEED, 0));
        }
    }
}
