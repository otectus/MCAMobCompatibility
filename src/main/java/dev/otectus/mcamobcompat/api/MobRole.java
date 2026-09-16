package dev.otectus.mcamobcompat.api;

import dev.otectus.mcamobcompat.McaMobCompat;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;

import java.util.List;

/**
 * The semantic categories this mod sorts entities into, so MCA can treat a modded cow like a cow
 * without anyone hardcoding what a cow is.
 *
 * <p>Each role is backed by a datapack entity-type tag at
 * {@code data/mcamobcompat/tags/entity_type/&lt;path&gt;.json}. That is the whole extension story: a
 * modpack author teaches this mod about a mod we have never seen by adding entity ids to a tag, with
 * no code and no recompile. The shipped defaults are just a datapack like any other, loaded at the
 * lowest priority, so a later pack always wins.
 *
 * <p>Named {@code MobRole} rather than {@code MobCategory} on purpose: {@code
 * net.minecraft.world.entity.MobCategory} already exists (it is the spawn-group enum -- CREATURE,
 * MONSTER, AMBIENT), and sharing the simple name would force a fully-qualified reference in every
 * file that touched both.
 */
public enum MobRole {

    /** Any farmed animal. The umbrella role; the four species roles below are all inside it. */
    LIVESTOCK("livestock"),
    /** Cows, mooshrooms and their modded equivalents. */
    CATTLE("cattle"),
    /** Sheep and their modded equivalents. */
    SHEEP("sheep"),
    /** Pigs and their modded equivalents. */
    PIG("pig"),
    /** Chickens and other farmed birds. */
    POULTRY("poultry"),
    /** Horses, donkeys, mules and modded equines. */
    HORSE("horse"),
    /** Anything a villager could plausibly ride. A superset of {@link #HORSE}. */
    MOUNT("mount"),
    /** Owned companion animals -- cats, dogs, parrots and modded pets. */
    PET("pet"),
    /** Village defenders that are NPCs, such as {@code guardvillagers:guard}. */
    GUARD("guard"),
    /** Humanoid village inhabitants, including MCA's own villagers. */
    HUMANOID_NPC("humanoid_npc");

    /** Cached because {@code values()} allocates a fresh array on every call, and this is hot. */
    public static final List<MobRole> VALUES = List.of(values());

    private final String path;
    private final ResourceLocation tagId;

    /**
     * Built on first use rather than in the constructor.
     *
     * <p>{@code TagKey.create} touches {@code net.minecraft.core.registries.Registries}, whose class
     * initialiser needs a bootstrapped Minecraft. Doing it eagerly would make this enum -- and so
     * every class that mentions a role -- unloadable in a plain unit test. In game the first call
     * happens during classification, long after bootstrap. The race is benign: {@code TagKey.create}
     * interns, so two threads racing here get the same instance back.
     */
    private volatile TagKey<EntityType<?>> tag;

    MobRole(String path) {
        this.path = path;
        this.tagId = ResourceLocation.fromNamespaceAndPath(McaMobCompat.MOD_ID, path);
    }

    /** The tag file name for this role, without namespace or extension. */
    public String path() {
        return path;
    }

    /** The entity-type tag that decides membership of this role. */
    public TagKey<EntityType<?>> tag() {
        TagKey<EntityType<?>> resolved = tag;
        if (resolved == null) {
            resolved = TagKey.create(Registries.ENTITY_TYPE, tagId);
            tag = resolved;
        }
        return resolved;
    }

    /** {@code mcamobcompat:livestock} -- for logs and command output. */
    public ResourceLocation tagId() {
        return tagId;
    }
}
