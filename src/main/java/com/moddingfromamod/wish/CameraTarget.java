package com.moddingfromamod.wish;

/**
 * <b>Where a camera that booted before it knew should dial</b>, the wish camera on its own
 * VM. Minecraft-free and unit-tested ({@code CameraTargetTest}).
 *
 * <p>A camera on the server's own machine is told its server at launch ({@code -Dmoddingfromamod.server=HOST:PORT}). A
 * camera VM cannot be: its client is booted while the VM is idle — so a resumed camera pays a
 * ~15 s resume instead of a ~60 s client boot on the wish's path — and only a LEASE says which server
 * it serves. So the VM's agent writes the address into a file ({@code -Dmoddingfromamod.serverFile})
 * when the lease arrives, and {@code CameraClientTest} reads it each second until it is there.
 *
 * <p>The file holds one line, {@code HOST:PORT}. Anything else reads as "not yet" rather than as an
 * address, because a half-written file and a garbled one must never dial somewhere.
 */
public final class CameraTarget {

    private CameraTarget() {
    }

    /** The address in a target file's text, or null while there is none (absent, blank, partial). */
    public static String parse(String text) {
        if (text == null) {
            return null;
        }
        String line = text.strip();
        int nl = line.indexOf('\n');
        if (nl >= 0) {
            line = line.substring(0, nl).strip();
        }
        int colon = line.lastIndexOf(':');
        if (colon <= 0 || colon == line.length() - 1) {
            return null;
        }
        String host = line.substring(0, colon);
        if (!host.matches("[A-Za-z0-9.\\-]{1,253}")) {
            return null;
        }
        int port;
        try {
            port = Integer.parseInt(line.substring(colon + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        return port >= 1 && port <= 65535 ? host + ":" + port : null;
    }

    /**
     * <b>Is a leased client ready for its NEXT lease?</b> A camera VM's client used to be
     * restarted after every lease (a fresh JVM, ~55 s on 2 vCPUs) — and a wish arriving inside the
     * 60 s linger then waited for that boot: measured on a test server, a "warm" lease joined in 28.5 s,
     * slower than a resume from SUSPENDED (21 s). The agent now removes the target file when a lease
     * ends; a client that has dialled ({@code asked}) and finds no target re-arms itself — back to
     * the title screen, waiting — and dials the next lease's address in ~1 s with no restart. Pure.
     */
    public static boolean rearm(boolean leased, boolean asked, boolean inWorld, boolean targetPresent) {
        return leased && asked && !inWorld && !targetPresent;
    }
}
