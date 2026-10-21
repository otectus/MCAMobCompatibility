package dev.otectus.mcamobcompat.integration.workers;

import net.minecraft.world.entity.Mob;

/**
 * Gives back the chunks a worker forced loaded, when it leaves the world for a civilian return.
 *
 * <p>On Forge 1.20.1 this releases the tickets Recruits' chunk-loading entities took under Recruits'
 * own ticket owner. NeoForge 1.21.1 has no Villager Workers build -- its adapter reports itself
 * unavailable here (see {@code integration.PlatformArtifacts}) -- and NeoForge's tickets belong to a
 * {@code TicketController} the owning mod registers itself, which no other mod can release on its
 * behalf; so there is nothing this build could release, and it releases nothing.
 */
final class WorkersChunkTickets {

    private WorkersChunkTickets() {
    }

    static void release(Mob worker) {
        // Unavailable on this platform: see the class comment.
    }
}
