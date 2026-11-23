package com.moddingfromamod.net;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Send a payload to a player, unless that "player" cannot receive one.
 *
 * <p><b>Why this exists.</b> A GameTest's mock player sits in the real player list but has no real
 * connection, and sending it a custom payload does not fail quietly — it <b>crashes the server</b>
 * with {@code Payload moddingfromamod:species_sync may not be sent to the client}. Several of these
 * payloads are per-tick broadcasts over the whole player list, so any GameTest that puts a mock
 * player in the level became fatal whether or not it had anything to do with syncing.
 *
 * <p>The failure also names the payload rather than the test that created the player, so it reads as
 * a bug in whichever sync happened to fire first — I chased the watch sync, fixed it, and the next
 * run blamed the species sync instead. One guarded send, used everywhere, ends that game.
 *
 * <p>Costs a real player nothing: a connected client always has the channel.
 */
public final class Send {

    private Send() {
    }

    /**
     * True if this player can actually be sent this payload.
     *
     * <p>⚠ <b>A null connection is not the only shape of "no client", and the second one THROWS.</b>
     * NeoForge's {@code FakePlayer} does have a {@code connection} — a {@code FakePlayerNetHandler}
     * over a {@code DUMMY_CONNECTION} — so the null check above passes. But that connection has no
     * netty {@code channel}, and {@code hasChannel} reaches straight through to
     * {@code connection.channel().attr(...)}:
     *
     * <pre>
     *   NullPointerException: Cannot invoke "io.netty.channel.Channel.attr(...)" because
     *       "net.minecraft.network.Connection.channel" is null
     *     at NetworkRegistry.hasChannel
     *     at Send.canReceive
     *     at WatchSnapshot.onServerTick        &lt;- a PER-TICK broadcast
     * </pre>
     *
     * <p>Measured: it killed a dedicated server outright — <i>"Encountered an unexpected
     * exception"</i> — 24 seconds into a run, from a tick handler that merely walks the player list.
     * Exactly the failure this class was written to end, arriving through the one door it left open.
     *
     * <p>So the question is answered rather than propagated: <b>if asking whether they can receive
     * throws, they cannot receive.</b> A guard that throws is not a guard, and this one runs on
     * every tick for every player.
     */
    public static boolean canReceive(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        if (player == null || player.connection == null) {
            return false;
        }
        try {
            return player.connection.hasChannel(type);
        } catch (RuntimeException noChannel) {
            return false;
        }
    }

    /**
     * <b>Does this connection have our jar?</b> — the ONE probe for "modded", asked by every
     * caller that routes on it ({@code ItemPackService}, {@code RigVeilService},
     * {@code RigReveal}) through {@code PackDelivery.routeFor}.
     *
     * <p>Until each of those asked {@code canReceive(p, RigPackPayload.TYPE)} — a channel
     * nothing on the shipped default ever sent, kept registered only because it was being used as a
     * PROBE by four call sites that nobody had written down as depending on it. Deleting the
     * direct-install route would have turned every modded client into a "stock" one: pushed the
     * item pack it already ships, and veiled from its own camera's view. So the question is asked
     * here, once, by {@link ModdedClient#hasOurJar} (the channel is the private plug-in's; with none,
     * nobody has our jar, which is the public single-player mod).
     */
    public static boolean hasOurJar(ServerPlayer player) {
        return ModdedClient.hasOurJar(player);
    }

    /**
     * Send it if it can be received; otherwise do nothing.
     *
     * <p><b>This one method is what makes an ADDITIVE upgrade possible at all</b>, and it is worth
     * knowing that before deleting anything that looks like mod-only client code. A payload sent
     * beside content every client already gets — never instead of it — reaches the modded client
     * and is silently skipped for the vanilla one, which is how
     * {@code AskWhichPayload}/{@code WishPickScreen} give a nicer answer to a question chat already
     * answers. See
     * and {@code AdditiveUpgradeGameTest}, which drives this method from both sides.
     *
     * <p>⚠ "Otherwise do nothing" is not politeness. An unknown channel is a protocol ERROR on the
     * receiving end, so sending a mod payload to a vanilla client disconnects them.
     */
    public static void to(ServerPlayer player, CustomPacketPayload payload) {
        if (payload != null && canReceive(player, payload.type())) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    /**
     * <b>Broadcast to everyone who can actually receive it.</b>
     *
     * <p>This class existed, was carefully written, and was reachable from the TARGETED sends only:
     * every {@code PacketDistributor.sendToAllPlayers} call in the mod went straight past it. That
     * is the shape {@code docs/CAMERA.md} records — <i>"there is no check" and "the check is not
     * wired to this path" look identical from outside and want opposite fixes</i> — so the fix is a
     * broadcast that goes through the same door rather than a second guard.
     *
     * <p><b>Why a broadcast is the dangerous one.</b> {@code sendToAllPlayers} ends in
     * {@code ServerCommonPacketListenerImpl.send}, whose first line is
     * {@code NetworkRegistry.checkPacket(packet, this)} — and that <b>throws</b>
     * {@code UnsupportedOperationException: Payload … may not be sent to the client!} for a
     * listener with no such channel. It does not skip that player; it aborts the whole broadcast,
     * on the server thread, from whatever tick handler called it.
     *
     * <p><b>Who has no channel.</b> A GameTest mock (the case this class was written for) — and a
     * <b>VANILLA CLIENT</b>, which is the entire premise of the carrier route. So a Lite server
     * would throw once a second, from the sweep that records a wished item, as soon as one child
     * joined without the mod. Found by asking whether the creative menu could be hooked when the
     * mod IS installed; the answer turned out to depend on what happens when it is not.
     */
    /**
     * The same, resolving the running server itself.
     *
     * <p>Exists so converting a {@code sendToAllPlayers} call site is a pure swap: most of them are
     * deep in a tool or a command and have no {@code MinecraftServer} in scope, and plumbing one
     * through seven call sites to reach a one-line guard is how a safety change turns into a
     * refactor nobody merges. A null server (no world loaded) is simply nobody to send to.
     */
    public static void all(CustomPacketPayload payload) {
        all(net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer(), payload);
    }

    public static void all(net.minecraft.server.MinecraftServer server,
            CustomPacketPayload payload) {
        if (server == null || payload == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            to(player, payload);
        }
    }
}
