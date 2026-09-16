package dev.otectus.mcamobcompat.mca;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.util.ModIds;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.Set;

/**
 * Recognises MCA Reborn's villagers by registry id, not by class. The package also contains
 * {@code McaBinding}, which does use reflection -- but only for chore features that require it.
 *
 * <p><b>Identification is by entity-type registry id, not by class.</b> That is what lets this mod
 * work across every MCA build without depending on MCA class stability: MCA has moved its Java package
 * twice ({@code net.mca} to {@code net.conczin.mca}, both behind a Forgix {@code forge.} prefix),
 * but {@code mca:male_villager} and {@code mca:female_villager} have never changed. Nothing here
 * loads an MCA class statically, so there is no rename to survive.
 *
 * <p>Note for anyone reading MCA's own structure: {@code VillagerEntityMCA} really does extend
 * {@code net.minecraft.world.entity.npc.Villager}. This is easy to get backwards, and getting it
 * backwards is exactly why Guard Villagers can convert (and destroy) an MCA villager -- its
 * conversion handler gates on {@code instanceof Villager}, which MCA villagers pass. Because they
 * are vanilla villagers, every vanilla API works on them and this mod needs no MCA types at all.
 */
public final class McaEntities {

    public static final ResourceLocation MALE_VILLAGER = ResourceLocation.fromNamespaceAndPath(ModIds.MCA, "male_villager");
    public static final ResourceLocation FEMALE_VILLAGER = ResourceLocation.fromNamespaceAndPath(ModIds.MCA, "female_villager");

    /**
     * The authoritative set of MCA villager types, as a Java constant.
     *
     * <p>Deliberately <b>not</b> read from a datapack tag. The conversion blocker is the one piece
     * of this mod whose failure destroys player data that cannot be recovered, and a tag can be
     * emptied by any datapack -- including by accident, with {@code "replace": true} in an unrelated
     * pack. The safety path must not be switchable from data. The tag below is additive on top of
     * this, never instead of it.
     *
     * <p>MCA's zombie villagers ({@code mca:male_zombie_villager}) are excluded on purpose: they are
     * zombies, they do not pass Guard Villagers' {@code instanceof Villager} gate, and guards should
     * be free to attack them.
     */
    private static final Set<ResourceLocation> VILLAGER_IDS = Set.of(MALE_VILLAGER, FEMALE_VILLAGER);

    /**
     * Id of the tag holding extra entity types to treat as MCA villagers, for packs running an MCA
     * fork or an addon that adds villager types. Purely additive to {@link #VILLAGER_IDS}.
     */
    public static final ResourceLocation ADDITIONAL_VILLAGERS_ID =
            ResourceLocation.fromNamespaceAndPath(McaMobCompat.MOD_ID, "mca_villagers");

    /**
     * Built on first use, not in the class initialiser: {@code TagKey.create} touches
     * {@code Registries}, whose class initialiser needs a bootstrapped Minecraft, and this class must
     * stay loadable in a plain unit test. The race is benign -- {@code TagKey.create} interns.
     */
    private static volatile TagKey<EntityType<?>> additionalVillagers;

    private McaEntities() {
    }

    /** True for an MCA villager. Null-safe. */
    public static boolean isMcaVillager(Entity entity) {
        return entity != null && isMcaVillager(entity.getType());
    }

    /** True for an MCA villager type. Null-safe, and safe before tags are bound. */
    public static boolean isMcaVillager(EntityType<?> type) {
        if (type == null) {
            return false;
        }
        if (VILLAGER_IDS.contains(EntityType.getKey(type))) {
            return true;
        }
        try {
            return type.is(additionalVillagersTag());
        } catch (Throwable t) {
            // Tags not bound yet, or a broken tag. The constant set above already answered the only
            // case that matters for data safety, so falling back to false here is correct.
            return false;
        }
    }

    /** The additive extension tag. Resolved lazily; see {@link #additionalVillagers}. */
    public static TagKey<EntityType<?>> additionalVillagersTag() {
        TagKey<EntityType<?>> resolved = additionalVillagers;
        if (resolved == null) {
            resolved = TagKey.create(Registries.ENTITY_TYPE, ADDITIONAL_VILLAGERS_ID);
            additionalVillagers = resolved;
        }
        return resolved;
    }

    /** The canonical MCA villager ids, for logs and command output. */
    public static Set<ResourceLocation> villagerIds() {
        return VILLAGER_IDS;
    }
}
