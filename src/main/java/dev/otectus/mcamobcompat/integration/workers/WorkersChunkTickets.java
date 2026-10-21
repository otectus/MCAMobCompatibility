package dev.otectus.mcamobcompat.integration.workers;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.common.world.ForgeChunkManager;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Gives back the chunks a worker forced loaded, when it leaves the world for a civilian return.
 *
 * <p>Recruits' chunk-loading entities force the 3x3 chunks around them under Recruits' own ticket
 * ({@code "recruits"}, owned by the entity) and release them only when they die. A worker going home
 * does not die, so the same tickets are released here, through the loader's chunk manager, under the
 * same mod id and owner: exactly the tickets Recruits took, around the chunk it records as loaded and
 * around the one it stands in. Releasing a ticket that was never taken does nothing.
 */
final class WorkersChunkTickets {

    private static final String RECRUITS_TICKETS = "recruits";

    private WorkersChunkTickets() {
    }

    static void release(Mob worker) {
        if (!(worker.level() instanceof ServerLevel level)) {
            return;
        }
        Set<ChunkPos> centres = new LinkedHashSet<>();
        centres.add(worker.chunkPosition());
        try {
            CompoundTag saved = worker.saveWithoutId(new CompoundTag());
            if (saved.contains("chunkX") && saved.contains("chunkZ")) {
                centres.add(new ChunkPos(saved.getInt("chunkX"), saved.getInt("chunkZ")));
            }
        } catch (RuntimeException ignored) {
            // The worker's own record is unreadable; its current chunk is still released.
        }
        for (ChunkPos centre : centres) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    ForgeChunkManager.forceChunk(level, RECRUITS_TICKETS, worker, centre.x + dx, centre.z + dz,
                            false, false);
                }
            }
        }
    }
}
