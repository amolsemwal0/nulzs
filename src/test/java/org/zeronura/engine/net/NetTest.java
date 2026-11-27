package org.zeronura.engine.net;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class NetTest {

    private static <T> T await(Supplier<T> poll) throws InterruptedException {
        long end = System.nanoTime() + 3_000_000_000L;
        while (System.nanoTime() < end) {
            T value = poll.get();
            if (value != null) {
                return value;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("timed out waiting");
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        await(() -> condition.getAsBoolean() ? Boolean.TRUE : null);
    }

    @Test
    void messagesArriveInOrderBothWaysOverTcp() throws Exception {
        try (TcpListener listener = new TcpListener(0)) {
            TcpConnection client = TcpConnection.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), listener.port()), 2000);
            TcpConnection server = await(listener::poll);

            client.send("hello");
            client.send("héllo | wörld");
            assertEquals("hello", await(server::poll));
            assertEquals("héllo | wörld", await(server::poll));
            server.send("back");
            assertEquals("back", await(client::poll));

            client.close();
            awaitTrue(() -> !server.isOpen());
        }
    }

    @Test
    void aMessageMustBeOneLine() {
        Connection[] link = LocalConnection.pair();
        assertThrows(IllegalArgumentException.class, () -> link[0].send("two\nlines"));
    }

    @Test
    void localPairsDeliverBothWaysAndCloseTogether() {
        Connection[] link = LocalConnection.pair();
        link[0].send("ping");
        link[1].send("pong");
        assertEquals("ping", link[1].poll());
        assertEquals("pong", link[0].poll());
        assertNull(link[0].poll());

        link[1].send("last words");
        link[1].close();
        assertFalse(link[0].isOpen(), "closing one end closes the link");
        assertEquals("last words", link[0].poll(), "messages sent before closing can still be read");
    }

    @Test
    void aBeaconHearsAnnouncements() throws Exception {
        try (LanBeacon beacon = new LanBeacon(0, InetAddress.getLoopbackAddress())) {
            beacon.announce("here I am");
            LanBeacon.Heard heard = await(beacon::poll);
            assertEquals("here I am", heard.message());
        }
    }
}
