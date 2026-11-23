package com.moddingfromamod.vanilla;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.server.MinecraftServer;

/**
 * <b>Where the pack host listens, and what it tells a client to connect to.</b>
 *
 * <p>These are two different questions and conflating them is the classic way to ship a service
 * nobody can reach: a server may bind {@code 0.0.0.0} and still have to advertise a name that
 * resolves for the client, and it may bind loopback and correctly advertise loopback for a
 * co-located client and no one else.
 *
 * <p><b>This class deliberately answers only the cases we have measured</b>, and its defaults are
 * chosen so the failure is visible rather than silent:
 *
 * <table border="1">
 *   <caption>the deployments this covers today</caption>
 *   <tr><th>deployment</th><th>bind</th><th>advertise</th><th>status</th></tr>
 *   <tr><td>singleplayer — the client is this process</td><td>127.0.0.1</td><td>127.0.0.1</td>
 *       <td>works</td></tr>
 *   <tr><td>a dedicated server and a camera on the same machine </td><td>127.0.0.1</td>
 *       <td>127.0.0.1</td><td>works</td></tr>
 *   <tr><td>a family server on a LAN</td><td>0.0.0.0 (opt in)</td><td>the server's LAN address
 *       (opt in)</td><td>needs both properties set</td></tr>
 *   <tr><td><b>a cloud deployment</b></td><td colspan="2">an open design question</td>
 *       <td><b>not answered here, on purpose</b></td></tr>
 * </table>
 *
 * <p>The last row is the reason this is a class rather than two literals. Ingress, TLS, whether the
 * pack should be served by the game server at all or by something in front of it, and how a client
 * outside the network is told where to look, are decisions with real trade-offs that nobody has
 * taken. Defaulting to loopback means an unconfigured cloud deployment fails loudly and locally
 * instead of advertising an address that happens to resolve to the wrong machine.
 */
public final class PackHostConfig {

    /** {@code -Dmoddingfromamod.packhost.bind=0.0.0.0} */
    public static final String BIND_PROPERTY = ModdingFromAModMod.MODID + ".packhost.bind";
    /** {@code -Dmoddingfromamod.packhost.advertise=192.168.1.20} */
    public static final String ADVERTISE_PROPERTY = ModdingFromAModMod.MODID + ".packhost.advertise";
    /** {@code -Dmoddingfromamod.packhost.port=25599}; 0 asks the OS for a free one. */
    public static final String PORT_PROPERTY = ModdingFromAModMod.MODID + ".packhost.port";

    /**
     * Loopback, explicitly IPv4.
     *
     * <p>{@code localhost} can resolve to {@code ::1} while the host bound {@code 127.0.0.1}, which
     * produces a connection refused that looks like the host is down. {@code McpHttpServer} records
     * the same trap; naming the literal avoids it on both sides.
     */
    public static final String LOOPBACK = "127.0.0.1";

    private PackHostConfig() {
    }

    /** The address to LISTEN on. Loopback unless told otherwise. */
    public static String bindHost() {
        String v = System.getProperty(BIND_PROPERTY, "").trim();
        return v.isEmpty() ? LOOPBACK : v;
    }

    /**
     * The host a CLIENT is told to fetch from.
     *
     * <p>Falls back to the bind address when it is a real address, and to loopback when the bind is
     * a wildcard — because {@code 0.0.0.0} is a valid thing to LISTEN on and never a valid thing to
     * CONNECT to. A client handed {@code http://0.0.0.0:.../} simply fails, and the log line would
     * say the pack was offered.
     */
    public static String advertiseHost(MinecraftServer server) {
        String v = System.getProperty(ADVERTISE_PROPERTY, "").trim();
        if (!v.isEmpty()) {
            return v;
        }
        String bind = bindHost();
        return bind.isEmpty() || "0.0.0.0".equals(bind) || "::".equals(bind) ? LOOPBACK : bind;
    }

    /** The port to bind, or {@link PackHost#ANY_PORT} to let the OS choose. */
    public static int port() {
        String v = System.getProperty(PORT_PROPERTY, "").trim();
        if (v.isEmpty()) {
            return PackHost.ANY_PORT;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return PackHost.ANY_PORT;
        }
    }
}
