package dev.otectus.mcamobcompat.integration.guardvillagers;

import dev.otectus.mcamobcompat.McaMobCompat;
import dev.otectus.mcamobcompat.McaMobCompatConfig;
import dev.otectus.mcamobcompat.integration.IntegrationModule;
import dev.otectus.mcamobcompat.util.ModIds;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * Guard Villagers. The integration this mod was written for.
 *
 * <p>Guard Villagers converts a villager into a guard on crouch-and-right-click with a weapon, and
 * its only type check is {@code instanceof net.minecraft.world.entity.npc.Villager}. MCA villagers
 * pass that check, and its default convertible-profession list contains {@code "none"}, so an
 * unemployed MCA villager is convertible with no configuration at all. The conversion carries over
 * position, custom name, invulnerability and the held stack, then discards the original -- so the
 * family tree, marriage, children, genetics, personality and MCA inventory are destroyed, with no
 * way back, because a guard holds none of that data.
 *
 * <p>{@code ConversionGuardHandler} stops it. This class holds the one piece of Guard Villagers data
 * that stopping it needs: which items trigger it.
 */
public final class GuardVillagersModule extends IntegrationModule {

    /**
     * The item tag Guard Villagers gates its conversion on.
     *
     * <p>Referenced by id rather than by importing its {@code GuardTags} constant, so this compiles
     * and runs whether or not the mod is installed. When it is absent the tag is simply empty and
     * matches nothing, which is exactly the behaviour we want. Reading the tag rather than
     * hardcoding "swords and crossbows" also means a pack that customises the tag customises our
     * blocker to match, for free.
     */
    public static final TagKey<Item> CONVERTIBLE_GUARD_ITEMS = TagKey.create(
            Registries.ITEM, McaMobCompat.location(ModIds.GUARD_VILLAGERS, "convertible_guard_items"));

    public GuardVillagersModule() {
        super(ModIds.GUARD_VILLAGERS, ModIds.GUARD_VILLAGERS, "Guard Villagers");
    }

    @Override
    public boolean isEnabled() {
        return safeBoolean(McaMobCompatConfig.COMMON.guardVillagersModule, true);
    }

    /** Whether to block the villager-to-guard conversion on MCA villagers. */
    public boolean blocksConversion() {
        return isActive() && safeBoolean(McaMobCompatConfig.SERVER.blockGuardConversion, true);
    }

    /** Whether to alert nearby guards when an MCA villager is attacked. */
    public boolean protectsVillagers() {
        return isActive() && safeBoolean(McaMobCompatConfig.SERVER.protectMcaVillagers, true);
    }

    /** Whether to stop guards from choosing an MCA villager as a target. */
    public boolean preventsFriendlyFire() {
        return isActive() && safeBoolean(McaMobCompatConfig.SERVER.preventGuardFriendlyFire, true);
    }

    /**
     * Whether guards get an MCA-style identity -- name, gender, personality, per-player hearts.
     *
     * <p>Gated on this module rather than on a module of its own, because the bridge only makes
     * sense in a world that has guards to bridge: with Guard Villagers absent, a {@code
     * mcamobcompat:guard} tagged NPC from some other mod is nobody's MCA guard.
     */
    public boolean bridgesGuards() {
        return isActive() && safeBoolean(McaMobCompatConfig.SERVER.treatGuardsAsMcaGuards, true);
    }

    /**
     * Whether a plain right-click on a bridged guard opens MCA's own interaction screen, through
     * the hidden proxy villager, and crouching opens Guard Villagers' inventory instead.
     *
     * <p>Gated on the module the same way {@link #bridgesGuards()} is: the proxy is seeded from the
     * bridged identity, so with Guard Villagers absent there is nothing to stand in for.
     */
    public boolean opensMcaInteraction() {
        return isActive() && safeBoolean(McaMobCompatConfig.SERVER.guardMcaInteraction, true);
    }

    /**
     * Whether crouch-clicking an MCA villager with a convertible weapon recruits it as a guard in
     * place, rather than simply being blocked.
     *
     * <p>Sits behind {@code blocksConversion}: this is what happens <em>instead of</em> the block,
     * on the same gesture, so with the block off Guard Villagers' own destructive conversion is
     * what runs and there is nothing to replace.
     */
    public boolean recruitsAsGuards() {
        return isActive() && safeBoolean(McaMobCompatConfig.SERVER.recruitMcaVillagersAsGuards, true);
    }

    /**
     * Whether an attack target that another MCA addon set on a guard's hidden proxy is pushed up to the
     * guard itself, so MCACrime's lawful pursuit of a wanted player actually engages the guard.
     */
    public boolean followsAddonTargets() {
        return isActive() && safeBoolean(McaMobCompatConfig.SERVER.followAddonTargets, true);
    }

    /** Whether a newly identified guard gets its name shown permanently above its head. */
    public boolean showsNameTag() {
        return isActive() && safeBoolean(McaMobCompatConfig.SERVER.guardShowNameTag, true);
    }
}
