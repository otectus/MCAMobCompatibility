package dev.otectus.mcamobcompat.event;

import dev.otectus.mcamobcompat.api.MobRole;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins which animals an MCA villager's chores may hit.
 *
 * <p>The decision is the safety-critical half of chore support -- getting it wrong either lets a
 * hunting villager butcher a player's tamed horse or neuters the chore entirely -- and it is plain
 * Java precisely so it can be pinned here, with no bootstrapped registry and no MCA present.
 */
class ChoreProtectionPolicyTest {

    private static final Set<MobRole> DEFAULT_PROTECTED =
            ChoreProtectionPolicy.parseRoles(List.of("mount", "pet"), name -> {
                throw new AssertionError("The shipped default contains an unknown role: " + name);
            });

    /** The shipped defaults: every protection on, mount and pet protected. */
    private static boolean protectedByDefault(Set<MobRole> roles, boolean owned, boolean named, boolean leashed) {
        return ChoreProtectionPolicy.shouldProtect(roles, owned, named, leashed, false,
                DEFAULT_PROTECTED, true, true, true);
    }

    @Test
    void mountsAndPetsAreProtectedByDefault() {
        assertTrue(protectedByDefault(Set.of(MobRole.MOUNT, MobRole.HORSE), false, false, false),
                "A mount must not be a hunting target: SWEM and DragN's equines extend AbstractHorse, "
                        + "which MCA's hunting task accepts as a plain Animal.");
        assertTrue(protectedByDefault(Set.of(MobRole.PET), false, false, false),
                "A pet must not be a hunting target.");
    }

    @Test
    void ownedNamedAndLeashedAnimalsAreProtectedByDefault() {
        assertTrue(protectedByDefault(Set.of(MobRole.LIVESTOCK), true, false, false),
                "An owned animal is somebody's, whatever role it holds.");
        assertTrue(protectedByDefault(Set.of(MobRole.LIVESTOCK), false, true, false),
                "A name tag is the vanilla signal that a player cares about this one animal.");
        assertTrue(protectedByDefault(Set.of(MobRole.LIVESTOCK), false, false, true),
                "A leashed animal is being taken somewhere by somebody.");
    }

    @Test
    void livestockIsNotProtectedByDefault() {
        assertFalse(protectedByDefault(Set.of(MobRole.LIVESTOCK, MobRole.CATTLE), false, false, false),
                "The hunting chore exists to produce meat. Protecting an untamed, unnamed cow by default "
                        + "would neuter the chore for every pack that replaces vanilla farm animals.");
    }

    @Test
    void addingLivestockToTheListProtectsIt() {
        Set<MobRole> configured = ChoreProtectionPolicy.parseRoles(
                List.of("mount", "pet", "livestock"), name -> {
                    throw new AssertionError("Unexpected unknown role: " + name);
                });

        assertTrue(ChoreProtectionPolicy.shouldProtect(Set.of(MobRole.LIVESTOCK, MobRole.CATTLE),
                        false, false, false, false, configured, true, true, true),
                "A pack that keeps its herds off the menu should need exactly one list entry.");
    }

    @Test
    void roleNamesAreMatchedCaseInsensitively() {
        Set<MobRole> configured = ChoreProtectionPolicy.parseRoles(
                List.of("MOUNT", "Pet", "humanoid_npc"), name -> {
                    throw new AssertionError("Unexpected unknown role: " + name);
                });

        assertEquals(Set.of(MobRole.MOUNT, MobRole.PET, MobRole.HUMANOID_NPC), configured,
                "A pack author should not have to know that the tag paths are lower case and the enum "
                        + "constants are upper case.");
    }

    @Test
    void unknownRoleNamesAreReportedAndDropped() {
        List<String> unknown = new ArrayList<>();
        Set<MobRole> configured =
                ChoreProtectionPolicy.parseRoles(List.of("mount", "wyvern", ""), unknown::add);

        assertEquals(Set.of(MobRole.MOUNT), configured, "A typo must not take the whole option down.");
        assertEquals(List.of("wyvern"), unknown,
                "Every unrecognised name should be reported exactly once, so the caller can log it once "
                        + "per config load rather than once per attack.");
    }

    @Test
    void anAnimalAlreadyAttackingTheVillagerIsNotProtected() {
        assertFalse(ChoreProtectionPolicy.shouldProtect(Set.of(MobRole.PET), true, true, true, true,
                        DEFAULT_PROTECTED, true, true, true),
                "Self-defence exemption: a pack is free to tag something hostile as a PET, and being on "
                        + "the protected list must not buy it a free hit.");
    }
}
