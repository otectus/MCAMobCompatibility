package dev.otectus.mcamobcompat.character;

/**
 * What Guard Villagers' recruit gesture -- crouch and right-click an MCA villager holding a sword or
 * crossbow -- does to that villager. Two different controllers, so the choice is the server owner's.
 */
public enum GuardRecruitmentMode {

    /**
     * The villager becomes a real Guard Villagers guard with Guard Villagers' own combat, patrol and
     * inventory, and stays the same MCA person behind it: same UUID, face, family and hearts. The
     * villager can return to civilian life later. The 0.3.0 default.
     */
    NATIVE_GUARD,

    /**
     * The villager takes MCA's own {@code mca:guard} profession in place and keeps MCA's own guard AI.
     * No Guard Villagers entity is involved. The behaviour of 0.2.x.
     */
    MCA_PROFESSION
}
