package org.zeronura.engine.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Finding other games on the local network. Each game announces itself now and then with a small UDP packet and
 * hears everyone else's. Nothing is connected by this: an announcement only says who is there and how to reach them.
 * Announcements can be lost, so a game repeats its own every second or two, and it also hears itself.
 *
 * <h2>Multicast, on every network</h2>
 * Announcements go to a multicast group on each network the computer is on, loopback included, rather than as a
 * broadcast. A broadcast leaves through the network card and is looped back through it, where a desktop firewall
 * such as ufw drops it — so two games on the same computer never hear each other. Multicast on loopback stays inside
 * the computer, and the copy sent on the network card reaches other computers (whose firewalls must allow the port).
 *
 * <p>The port is shared: several programs can listen on it at once, so more than one game on the same computer can
 * all hear the network.
 */
public final class LanBeacon implements AutoCloseable {

    /** An announcement, and the address it came from. */
    public record Heard(String message, InetAddress from) {
    }

    /** Announcements are short; this is well inside what a LAN delivers in one packet. */
    public static final int MAX_MESSAGE = 1024;

    private final MulticastSocket socket;
    private final InetAddress target;
    /** The networks to announce on, one copy each; empty to send a single copy by the default route. */
    private final List<NetworkInterface> networks;
    private final Queue<Heard> heard = new ConcurrentLinkedQueue<>();

    /** Listens and announces on a multicast group, on every network this computer is on. */
    public static LanBeacon multicast(InetAddress group, int port) throws IOException {
        List<NetworkInterface> joined = new ArrayList<>();
        LanBeacon beacon = new LanBeacon(port, group, joined);
        for (NetworkInterface network : usableNetworks()) {
            try {
                beacon.socket.joinGroup(new InetSocketAddress(group, port), network);
                joined.add(network);
            } catch (IOException e) {
                // That network can't do multicast; the others still can.
            }
        }
        if (joined.isEmpty()) {
            beacon.close();
            throw new IOException("no network can join multicast group " + group.getHostAddress());
        }
        return beacon;
    }

    /** Listens on {@code port} (0 for any free one) and announces to {@code target} on the port it listens on. */
    public LanBeacon(int port, InetAddress target) throws IOException {
        this(port, target, List.of());
    }

    private LanBeacon(int port, InetAddress target, List<NetworkInterface> networks) throws IOException {
        this.target = target;
        this.networks = networks;
        socket = new MulticastSocket(null);
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(port));
        } catch (IOException e) {
            socket.close();
            throw e;
        }
        Thread thread = new Thread(this::listen, "lan-beacon " + socket.getLocalPort());
        thread.setDaemon(true);
        thread.start();
    }

    /** Networks that are up and have an IPv4 address: the network card, Wi-Fi, loopback. */
    private static List<NetworkInterface> usableNetworks() throws IOException {
        List<NetworkInterface> usable = new ArrayList<>();
        for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            boolean ipv4 = Collections.list(network.getInetAddresses()).stream().anyMatch(address -> address instanceof Inet4Address);
            if (network.isUp() && ipv4 && (network.supportsMulticast() || network.isLoopback())) {
                usable.add(network);
            }
        }
        return usable;
    }

    private void listen() {
        byte[] buffer = new byte[MAX_MESSAGE];
        while (!socket.isClosed()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
                heard.add(new Heard(new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8),
                        packet.getAddress()));
            } catch (IOException e) {
                if (socket.isClosed()) {
                    return;
                }
            }
        }
    }

    /** Sends one announcement on each network. It may not arrive, so announce again every so often. */
    public synchronized void announce(String message) {
        byte[] data = message.getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_MESSAGE) {
            throw new IllegalArgumentException("announcement longer than " + MAX_MESSAGE + " bytes");
        }
        DatagramPacket packet = new DatagramPacket(data, data.length, target, socket.getLocalPort());
        if (networks.isEmpty()) {
            send(packet);
            return;
        }
        for (NetworkInterface network : networks) {
            try {
                socket.setNetworkInterface(network);
            } catch (IOException e) {
                continue;
            }
            send(packet);
        }
    }

    private void send(DatagramPacket packet) {
        try {
            socket.send(packet);
        } catch (IOException ignored) {
            // That network is unavailable right now; the next announcement tries again.
        }
    }

    /** The next announcement heard, or null if none is waiting. */
    public Heard poll() {
        return heard.poll();
    }

    public int port() {
        return socket.getLocalPort();
    }

    @Override
    public void close() {
        socket.close();
    }
}
