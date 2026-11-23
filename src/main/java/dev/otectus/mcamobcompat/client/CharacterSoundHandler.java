package dev.otectus.mcamobcompat.client;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.McaMobCompatConfig;
import dev.otectus.mcamobcompat.mca.McaBinding;
import dev.otectus.mcamobcompat.network.ProxyPairs;
import dev.otectus.mcamobcompat.util.ConfigAccess;
import dev.otectus.mcamobcompat.util.ModIds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;

/**
 * Replaces native guard/villager voice sounds with MCA's own villager voices for bridged actors
 * that are currently presented as MCA characters.
 *
 * <p>Unconverted actors (those without an active {@link ProxyPairs.Pair} or with
 * {@code mcaAppearance == false}) keep their native sounds untouched.
 */
@Mod.EventBusSubscriber(modid = McaMobCompat.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CharacterSoundHandler {

    /** Maximum squared distance between a sound origin and the paired actor's position. */
    private static final double MATCH_RADIUS_SQ = 4.0D;

    enum SoundKind {
        AMBIENT,
        HURT,
        DEATH,
        YES,
        NO
    }

    private static final Map<ResourceLocation, SoundKind> SOUND_KINDS = Map.of(
            McaMobCompat.location("guardvillagers", "entity.guard.ambient"), SoundKind.AMBIENT,
            McaMobCompat.location("guardvillagers", "entity.guard.hurt"), SoundKind.HURT,
            McaMobCompat.location("guardvillagers", "entity.guard.death"), SoundKind.DEATH,
            McaMobCompat.location("guardvillagers", "entity.guard.yes"), SoundKind.YES,
            McaMobCompat.location("guardvillagers", "entity.guard.no"), SoundKind.NO,
            McaMobCompat.location("minecraft", "entity.villager.ambient"), SoundKind.AMBIENT,
            McaMobCompat.location("minecraft", "entity.villager.hurt"), SoundKind.HURT,
            McaMobCompat.location("minecraft", "entity.villager.death"), SoundKind.DEATH,
            McaMobCompat.location("minecraft", "entity.villager.yes"), SoundKind.YES,
            McaMobCompat.location("minecraft", "entity.villager.no"), SoundKind.NO
    );

    private static final ResourceLocation MALE_HURT = McaMobCompat.location(ModIds.MCA, "villager.male.hurt");
    private static final ResourceLocation FEMALE_HURT = McaMobCompat.location(ModIds.MCA, "villager.female.hurt");
    private static final ResourceLocation MALE_SCREAM = McaMobCompat.location(ModIds.MCA, "villager.male.scream");
    private static final ResourceLocation FEMALE_SCREAM = McaMobCompat.location(ModIds.MCA, "villager.female.scream");
    private static final ResourceLocation MALE_YES = McaMobCompat.location(ModIds.MCA, "villager.male.yes");
    private static final ResourceLocation FEMALE_YES = McaMobCompat.location(ModIds.MCA, "villager.female.yes");
    private static final ResourceLocation MALE_NO = McaMobCompat.location(ModIds.MCA, "villager.male.no");
    private static final ResourceLocation FEMALE_NO = McaMobCompat.location(ModIds.MCA, "villager.female.no");
    private static final ResourceLocation MALE_SNORE = McaMobCompat.location(ModIds.MCA, "villager.male.snore");
    private static final ResourceLocation FEMALE_SNORE = McaMobCompat.location(ModIds.MCA, "villager.female.snore");

    private CharacterSoundHandler() {
    }

    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        SoundInstance original = event.getSound();
        if (original == null) {
            return;
        }
        SoundKind kind = classify(original.getLocation());
        if (kind == null) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !ConfigAccess.bool(McaMobCompatConfig.CLIENT.mcaAppearance, true)) {
            return;
        }

        Mob actor = findPairedActor(level, original.getX(), original.getY(), original.getZ());
        if (actor == null) {
            return;
        }

        if (!McaBinding.usesMcaVoices()) {
            if (!McaBinding.usesVanillaVoices()) {
                event.setSound(null);
            }
            return;
        }

        Villager identity = ProxyPairs.clientIdentityOf(actor);
        boolean female = identity != null && "FEMALE".equals(McaBinding.gender(identity).orElse("MALE"));
        ResourceLocation targetId = mcaSoundLocation(kind, female, actor.isSleeping());
        if (targetId == null) {
            // Normal adult MCA villagers are silent on ambient ticks.
            event.setSound(null);
            return;
        }
        if (!BuiltInRegistries.SOUND_EVENT.containsKey(targetId)) {
            return;
        }
        float pitch = identity != null ? identity.getVoicePitch() : 1.0F;
        event.setSound(new SimpleSoundInstance(
                targetId,
                original.getSource(),
                1.0F,
                pitch,
                SoundInstance.createUnseededRandom(),
                original.isLooping(),
                original.getDelay(),
                original.getAttenuation(),
                original.getX(),
                original.getY(),
                original.getZ(),
                original.isRelative()
        ));
    }

    static SoundKind classify(ResourceLocation location) {
        return location == null ? null : SOUND_KINDS.get(location);
    }

    static ResourceLocation mcaSoundLocation(SoundKind kind, boolean female, boolean sleeping) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case AMBIENT -> sleeping ? (female ? FEMALE_SNORE : MALE_SNORE) : null;
            case HURT -> female ? FEMALE_HURT : MALE_HURT;
            case DEATH -> female ? FEMALE_SCREAM : MALE_SCREAM;
            case YES -> female ? FEMALE_YES : MALE_YES;
            case NO -> female ? FEMALE_NO : MALE_NO;
        };
    }

    private static Mob findPairedActor(ClientLevel level, double x, double y, double z) {
        Mob best = null;
        double bestDistSq = MATCH_RADIUS_SQ;
        for (ProxyPairs.Pair pair : ProxyPairs.clientPairs(level).values()) {
            if (!pair.mcaAppearance()) {
                continue;
            }
            Entity entity = level.getEntity(pair.guardId());
            if (!(entity instanceof Mob mob) || !mob.getUUID().equals(pair.guard())) {
                continue;
            }
            double dx = mob.getX() - x;
            double dz = mob.getZ() - z;
            double dyFeet = mob.getY() - y;
            double dyEye = mob.getEyeY() - y;
            double distSq = dx * dx + dz * dz + Math.min(dyFeet * dyFeet, dyEye * dyEye);
            if (distSq <= bestDistSq) {
                bestDistSq = distSq;
                best = mob;
            }
        }
        return best;
    }
}
