package dev.otectus.mcamobcompat.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Pins the pairing message's wire shape. The client matches a proxy on both UUID and runtime id,
 * so every field has to come back exactly; a change here also needs {@code ProxyPairs.PROTOCOL}
 * bumped, which this test cannot check but a reviewer can.
 */
class ProxyPairCodecTest {

    @Test
    void everyFieldSurvivesTheRoundTrip() {
        ProxyPairs.Pair sent = new ProxyPairs.Pair(UUID.randomUUID(), 4242, UUID.randomUUID(), 17, true, false);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        sent.encode(buffer);
        ProxyPairs.Pair received = ProxyPairs.Pair.decode(buffer);
        assertEquals(sent, received);
        assertFalse(buffer.isReadable(), "Nothing may be left over for the next message.");
    }

    @Test
    void aRemovalKeepsItsIdentity() {
        ProxyPairs.Pair removal = new ProxyPairs.Pair(UUID.randomUUID(), 1, UUID.randomUUID(), 2, false, true);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        removal.encode(buffer);
        assertEquals(removal, ProxyPairs.Pair.decode(buffer));
    }
}
