package com.moddingfromamod.vanilla;

import java.util.Locale;

/**
 * <b>Where a client gets the rig pack, and how it is told about it.</b>
 *
 * <p>Minecraft-free so the two decisions that must not be wrong — the URL, and which route a
 * connection takes — are unit-testable without a server, a world or a client. Everything else in
 * this area needs all three, which is exactly how the bug shipped.
 *
 * <h2>ONE TRANSPORT: HTTP, for everybody</h2>
 *
 * <p>Before there were two ways bytes reached a client, and only one of them was written
 * down:
 *
 * <ul>
 *   <li>a <b>stock</b> client fetched the pack over HTTP, from a URL in a resource-pack push;</li>
 *   <li>a <b>modded</b> client read the pack <b>off local disk</b> — {@code mc.gameDirectory} —
 *       which works only because in singleplayer the client's game directory <i>is</i> the
 *       integrated server's directory.</li>
 * </ul>
 *
 * <p><b>That second one is an assumption, not a mechanism</b>, and the moment a modded client is a
 * different process it fails: it has the mod and not the disk. That is the camera — it
 * photographed a creature as a cloud of paper, with every gate green, because the
 * models were on the server's disk and it was reading its own.
 *
 * <p>So both routes now fetch <b>the same URL</b>, and only ONE of them is still
 * TOLD anything: a stock client has no channel, so it is the one that is pushed a pack.
 *
 * <table border="1">
 *   <caption>the routing rule, and it is the whole of it</caption>
 *   <tr><th>connection</th><th>what it is sent</th><th>where its artwork comes from</th></tr>
 *   <tr><td>has our jar ({@code Send.hasOurJar})</td><td>nothing</td>
 *       <td>the carrier's item definitions the mod ships in its own jar — the same bindings the
 *       join-time item pack carries, including the atom cube ({@code CarrierModelDriftTest})</td></tr>
 *   <tr><td>no channel (stock)</td><td>{@code ClientboundResourcePackPushPacket}</td>
 *       <td>the vanilla resource-pack reload, ONCE, at join</td></tr>
 * </table>
 *
 * <h2>What removed, so nobody rebuilds it</h2>
 *
 * <p>The modded row used to say "a mod payload carrying url + sha1, installed by
 * {@code RigDirectModels} with vanilla's own parser and bakery, no reload" — the direct-install
 * route. It existed to spare a MODDED client the ~6654 ms reload a PER-CREATURE
 * pack costs. The atom rig (the shipped default since 2026-09-13) has no per-creature pack, so
 * there was nothing left to install and the route was deleted rather than kept as a switch
 * ({@code docs/COMPAT_SHIMS.md}, "Four shims that should never be created", 1). The one fact it
 * held that is still live is WHICH route a connection takes, and that is {@link #routeFor}.
 */
public final class PackDelivery {

    /**
     * Which route a connection takes. {@code ItemPackService} (the join-time item pack) and
     * {@code RigVeilService} (who may see a rig before the reveal) both ask.
     */
    public enum Route {
        /**
         * A client with our jar: it already holds every override the server could push, so it is
         * pushed nothing and is never veiled. (The name predates, when this route also
         * installed per-creature packs directly; it is kept because the veil and the item pack
         * still read it as "the modded side".)
         */
        DIRECT,
        /** A stock client: the vanilla resource-pack push. The only transport it has. */
        VANILLA_PACK
    }

    /** The path segment the host serves packs under. Shared by the host and the URL builder. */
    public static final String PATH_PREFIX = "/pack/";

    /**
     * <b>Every pack we push is REQUIRED.</b>
     *
     * <p>The vanilla-compat path exists so a child who installs nothing gets the same game as one
     * who installs everything, and <b>the artwork is not a garnish on that — it IS the game</b>.
     * Without the pack a wished thing is a leather horse armour, the button he presses to
     * wish at all is a leather horse armour, and a creature is a heap of paper (#515b). A child who
     * declined and played on would not be having a worse version of the experience; he would be
     * having a different one, silently, while every log line said the wish was granted.
     *
     * <p>So this is one constant read by every push site rather than a boolean typed at each,
     * because two of them agreeing today is exactly how the two override tables ended up
     * only accidentally compatible.
     *
     * <h2>⚠ What the flag actually does, read off the bytecode rather than assumed</h2>
     *
     * <p>It is <b>entirely a client-side decision about the PROMPT</b>, and there is a second,
     * separate knob that is easy to confuse with it:
     *
     * <ul>
     *   <li><b>This flag</b> rides to {@code ClientCommonPacketListenerImpl.addOrUpdatePackPrompt},
     *       which picks {@code multiplayer.requiredTexturePrompt.line1/line2} over
     *       {@code multiplayer.texturePrompt.*} — and on a decline the <b>client disconnects
     *       itself</b> with {@code multiplayer.requiredTexturePrompt.disconnect}. That string lives
     *       in {@code PackConfirmScreen} and nowhere else in the client. Photographed on a real
     *       stock client: <i>"Rejecting this custom resource pack will disconnect you from this
     *       server."</i> — {@code ACCEPT=leave NOPRIMER=on ./tools/stock-icons.sh}.</li>
     *   <li><b>{@code server.properties require-resource-pack} is NOT this.</b>
     *       {@code ServerCommonPacketListenerImpl.handleResourcePackResponse} disconnects on
     *       {@code DECLINED} gated on {@code server.isResourcePackRequired()}, which reads that
     *       property. We deliberately do not set it: the client-side self-disconnect above already
     *       covers a decline, and the property would also apply to a pack the server operator
     *       configured, which is not ours to decide.</li>
     * </ul>
     *
     * <p><b>The blast radius, per case</b> — and the third row is why this is a safe change rather
     * than a brave one:
     *
     * <table border="1">
     *   <caption>what changes for a stock client</caption>
     *   <tr><th>case</th><th>before</th><th>after</th></tr>
     *   <tr><td>accepts the prompt</td><td>pack applies</td><td>unchanged</td></tr>
     *   <tr><td>declines the prompt</td><td>plays on with the wrong artwork, silently</td>
     *       <td><b>the client disconnects itself</b>, having been told why</td></tr>
     *   <tr><td><b>cannot FETCH the pack</b></td><td>plays on with the wrong artwork</td>
     *       <td><b>UNCHANGED</b> — the client reports {@code FAILED_DOWNLOAD}, and the server's
     *       disconnect is gated on {@code DECLINED}, so nobody is kicked for an unreachable
     *       host</td></tr>
     *   <tr><td>already answered ENABLED for this server</td><td>applies with no prompt</td>
     *       <td>unchanged — the flag only reaches the prompt, and there is none</td></tr>
     *   <tr><td>already answered <b>DISABLED</b> (never, for this server)</td>
     *       <td>not asked again</td><td><b>asked again</b> — see the branch below</td></tr>
     * </table>
     *
     * <p>That last row is not a guess about intent; it is the shape of the branch. Written out,
     * {@code handleResourcePackPush} raises a screen
     * <b>iff {@code status == PROMPT || (required && status == DISABLED)}</b>, where {@code status}
     * is the per-server answer and defaults to {@code PROMPT} when there is no {@code ServerData}.
     * So a required push re-opens a question a child once closed. Given that the answer decides
     * whether he can see what he wished for, being asked twice is the kinder failure.
     *
     * <p>That third row matters because {@code PackHostConfig}'s advertised host is an open
     * question for a cloud deployment. Had a failed download disconnected, turning this on would
     * have converted an unanswered design question into a child who cannot play; it does not.
     */
    public static final boolean REQUIRED = true;

    private PackDelivery() {
    }

    /**
     * Which route a connection takes.
     *
     * @param hasModChannel whether the connection has our jar — {@code Send.hasOurJar}, the ONE
     *                      probe every caller uses, which asks the same
     *                      {@code connection.hasChannel} that {@code Send.to} already uses to avoid
     *                      killing a vanilla client with a payload it cannot decode
     */
    public static Route routeFor(boolean hasModChannel) {
        return hasModChannel ? Route.DIRECT : Route.VANILLA_PACK;
    }

    /**
     * <b>Does this player need the ITEM pack pushed?</b> (D32,
     * docs/public-repo/DECISIONS.md.) Not if the connection has our jar ({@link #routeFor}), and not
     * if the player is the HOST of the integrated server (single-player, or the one who opened a
     * world to LAN): their game is the one running the mod, so it already draws Moddy's art from the
     * jar's own assets, and a push would only put Minecraft's "this server requires a custom resource
     * pack" prompt in front of someone opening their own world. The public mod has no client channel
     * at all, so without this every single-player host was asked. A LAN guest on a stock client is
     * not the host and still gets it; a dedicated server has no host, so it is unchanged.
     *
     * @param hasModChannel   {@code Send.hasOurJar}
     * @param integratedHost  the player owns the integrated server this is ({@code
     *                        MinecraftServer.isSingleplayerOwner}, never true on a dedicated one)
     */
    public static boolean itemPackNeeded(boolean hasModChannel, boolean integratedHost) {
        return routeFor(hasModChannel) == Route.VANILLA_PACK && !integratedHost;
    }

    /**
     * The URL a client fetches the pack from.
     *
     * <p><b>The sha1 is in the PATH, not a query string</b>, and that is load-bearing rather than
     * stylistic: a vanilla client caches a pack by URL, so a URL that changed only in its query
     * would let a client serve a stale pack from cache for new content. Putting the hash in the
     * path makes a changed pack a different resource by construction, which is also what makes an
     * UNCHANGED pack a genuine cache hit — measured in the Lite spike as a warm rejoin with 0
     * fetches.
     *
     * @param host the host a CLIENT should connect to. Not necessarily the bind address: a server
     *             may bind {@code 0.0.0.0} and still have to advertise a name a client can reach.
     *             Choosing this correctly for a cloud deployment is an open question and is
     *             deliberately this method's INPUT rather than its decision.
     */
    public static String url(String host, int port, String sha1) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("no host to advertise; a client cannot fetch from ''");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        requireSha1(sha1);
        return "http://" + host + ":" + port + PATH_PREFIX + sha1 + ".zip";
    }

    /**
     * The sha1 a URL is asking for, or null if it is not one of ours.
     *
     * <p>Used by the host to answer a request without trusting the path — a served file is chosen
     * by hash, so a path that is not a hash can never name one.
     */
    public static String sha1InPath(String path) {
        if (path == null || !path.startsWith(PATH_PREFIX) || !path.endsWith(".zip")) {
            return null;
        }
        String candidate = path.substring(PATH_PREFIX.length(), path.length() - ".zip".length());
        return isSha1(candidate) ? candidate.toLowerCase(Locale.ROOT) : null;
    }

    /**
     * 40 lowercase hex characters.
     *
     * <p>Vanilla validates the hash it is handed and a malformed one is refused, so a caller that
     * built one wrongly would see "the client ignored my pack" rather than an error. Checking here
     * turns that into a thrown exception at the point the mistake was made.
     */
    public static boolean isSha1(String s) {
        if (s == null || s.length() != 40) {
            return false;
        }
        for (int i = 0; i < 40; i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private static void requireSha1(String sha1) {
        if (!isSha1(sha1)) {
            throw new IllegalArgumentException("not a sha1: " + sha1);
        }
    }
}
