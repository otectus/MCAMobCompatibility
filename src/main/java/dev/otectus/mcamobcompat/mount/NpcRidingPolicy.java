package dev.otectus.mcamobcompat.mount;

/** What happens when an MCA villager is put on a mount that will not let it steer. */
public enum NpcRidingPolicy {
    /** The mount refuses the villager, and the player who gave the order is told why. */
    REFUSE,
    /** The villager mounts as a passenger that cannot steer: the behaviour before 0.2.1. */
    ALLOW_INERT
}
