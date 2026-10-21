package dev.otectus.mcamobcompat.network;

import dev.otectus.mcamobcompat.McaMobCompat;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the association message's wire shape, protocol 2, and the order the client accepts it in. The
 * client matches an identity and its actor on both UUID and runtime id, so every field has to come
 * back exactly; a change here also needs {@code ProxyPairs.PROTOCOL} bumped (protocol 2 since 0.3.0).
 */
class ProxyPairCodecTest {

    private static final ResourceLocation OVERWORLD = McaMobCompat.location("minecraft", "overworld");

    private static ProxyPairs.Pair pair(UUID proxy, int proxyId, int guardId, int generation, boolean remove) {
        return new ProxyPairs.Pair(proxy, proxy, proxyId, UUID.randomUUID(), guardId, OVERWORLD, generation, 3,
                0b1011, true, true, remove);
    }

    @Test
    void everyFieldSurvivesTheRoundTrip() {
        ProxyPairs.Pair sent = new ProxyPairs.Pair(UUID.randomUUID(), UUID.randomUUID(), 4242, UUID.randomUUID(), 17,
                OVERWORLD, 5, 9, 0x1FFF, true, false, false);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        sent.encode(buffer);
        ProxyPairs.Pair received = ProxyPairs.Pair.decode(buffer);
        assertEquals(sent, received);
        assertFalse(buffer.isReadable(), "Nothing may be left over for the next message.");
    }

    @Test
    void aRemovalKeepsItsIdentity() {
        ProxyPairs.Pair removal = pair(UUID.randomUUID(), 1, 2, 1, true);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        removal.encode(buffer);
        assertEquals(removal, ProxyPairs.Pair.decode(buffer));
    }

    @Test
    void anOlderGenerationNeverReplacesANewerOne() {
        UUID proxy = UUID.randomUUID();
        ProxyPairs.Pair promoted = pair(proxy, 10, 21, 2, false);
        ProxyPairs.Pair stale = pair(proxy, 10, 20, 1, false);
        assertFalse(ProxyPairs.Pair.supersedes(promoted, stale), "A promotion's old association arrived late and won.");
        assertTrue(ProxyPairs.Pair.supersedes(stale, promoted));
        assertTrue(ProxyPairs.Pair.supersedes(promoted, pair(proxy, 10, 21, 2, false)), "A revision update was refused.");
    }

    @Test
    void aRemovalRetractsOnlyTheExactIdsItNames() {
        UUID proxy = UUID.randomUUID();
        ProxyPairs.Pair held = pair(proxy, 10, 21, 2, false);
        assertFalse(ProxyPairs.Pair.supersedes(held, pair(proxy, 11, 21, 2, true)), "A reused runtime id retracted a live pair.");
        assertTrue(ProxyPairs.Pair.supersedes(held, pair(proxy, 10, 21, 2, true)));
        assertFalse(ProxyPairs.Pair.supersedes(null, pair(proxy, 10, 21, 2, true)), "A removal of nothing stored something.");
    }

    @Test
    void capabilitiesSurviveAsAMask() {
        java.util.Set<dev.otectus.mcamobcompat.character.Capability> set =
                java.util.EnumSet.of(dev.otectus.mcamobcompat.character.Capability.IDENTITY,
                        dev.otectus.mcamobcompat.character.Capability.LAW_ENFORCEMENT);
        assertEquals(set, dev.otectus.mcamobcompat.character.Capability.fromMask(
                dev.otectus.mcamobcompat.character.Capability.mask(set)));
    }
}
