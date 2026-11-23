package com.moddingfromamod.vanilla;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.datapack.VanillaCarrier;
import com.moddingfromamod.pack.WishPackWriter;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Getting the ITEM artwork to a client that has no mod</b> —.
 *
 * <p>A wished thing is handed out as {@link VanillaCarrier#ITEM_ID} carrying
 * {@code custom_model_data}, and the artwork that turns that carrier into a wand or a cup or into
 * Moddy is a {@code custom_model_data} override on the carrier's model. The mod <b>ships</b> that
 * file, so a client WITH the mod resolves it out of the jar and has always been fine. A client
 * without one has to be <i>given</i> it, and until this class existed nothing on any shipped server
 * path ever wrote such a pack: {@link WishPackWriter} was reachable only from {@code LiteProbe} and
 * from a {@code Dist.CLIENT} spike. So every wished item, and the watch a child holds to make a
 * wish at all, rendered as a plain leather horse armour for the only audience the vanilla-lite path
 * exists for.
 *
 * <h2>Why this is a SECOND pack rather than a merge into the rig pack</h2>
 *
 * <p>The two have genuinely different lifecycles and folding them together would be worse than the
 * bug:
 *
 * <table border="1">
 *   <caption>two packs, two clocks</caption>
 *   <tr><th></th><th>item pack (here)</th><th>rig pack ({@link RigReveal})</th></tr>
 *   <tr><td>published</td><td>at server START</td><td>when a CREATURE enters the world</td></tr>
 *   <tr><td>content</td><td>static — {@code WishIcons.all()} is a {@code List.of}</td>
 *       <td>grows with every creature a child wishes</td></tr>
 *   <tr><td>a child who has never wished</td><td>needs it — he is already holding Moddy</td>
 *       <td>has none, correctly</td></tr>
 * </table>
 *
 * <p>That last row is the constraint. Folding the item overrides into the rig pack would leave
 * Moddy as horse armour until the child's first <i>creature</i> wish — and the panel is the
 * zero-typing path, so the button he presses to make that wish would be the thing that is
 * unreadable. {@code ClientboundResourcePackPopPacket} exists in 1.21.1, i.e. a client keeps a
 * <b>UUID-keyed stack</b> of server packs rather than one slot, so two packs is protocol-supported
 * and needs nothing invented.
 *
 * <h2>Because the content is static, an earlier issue's 6654 ms never lands inside a wish</h2>
 *
 * <p>A stock client applies a pack by RELOADING, measured at ~6.7 s. The rig pack pays that per
 * creature, which is the whole subject. This one is a <b>build-time artefact</b>: the same
 * bytes every time, published once, and a client de-duplicates by sha1 — so the reload lands inside
 * a join, where the client is already loading, and a re-offer to somebody who already has it costs
 * nothing at all. That is why the offer below is unconditional rather than clever.
 *
 * <h2>⚠ Stock clients only, and one thing that is NOT decided here</h2>
 *
 * <p>A modded client is not offered this: it resolves the same bindings from
 * {@code assets/minecraft/items/leather_horse_armor.json} in the mod jar
 * ({@code WishPackWriterTest} keeps the two tables selecting the same pictures), so pushing would
 * buy a reload and nothing else.
 *
 * <p>The push is <b>required</b> ({@link PackDelivery#REQUIRED}), matching the rig route and
 * settling the question left open. an earlier issue's recorded decision was <i>"we need everyone to see
 * and experience all wishes together"</i>, and the artwork is not a garnish on that — without this
 * pack every wished thing, and the button a child presses to wish at all, is a leather horse
 * armour. A child who declined and played on would be having a different experience, silently,
 * while every log line said the wish was granted.
 *
 * <p>Read {@link PackDelivery#REQUIRED} before changing it: the flag is entirely about the PROMPT
 * and a decline, and a client that cannot FETCH the pack is deliberately still not disconnected.
 */
public final class ItemPackService {

    private static final Logger LOG = LoggerFactory.getLogger("mfam-item-pack");

    /**
     * The pack directory's name, beside {@link RigReveal#PACK_DIR}.
     *
     * <p>Under the server's {@code resourcepacks/} for the same reason the rig pack is: it is
     * where a human debugging "what did the child actually get" looks. Nothing reads it from there
     * — transport is HTTP for every client including one in this process.
     */
    public static final String PACK_DIR = "mfam-vanilla-items";

    /**
     * <b>{@code -Dmoddingfromamod.itempack=off} — the A/B seam, and nothing else.</b>
     *
     * <p>Gate C has to be able to photograph the world BEFORE this class existed, or the
     * photographs are of a nice day rather than of a fix. The obvious control — turn the carrier
     * route off — reproduces a <i>different</i> world: with it off a wished thing is
     * {@code moddingfromamod:wish_item}, a registry id a stock client does not have, so what is
     * measured is "an item that cannot arrive" rather than "an item that arrives with no artwork".
     *
     * <p>The bug was the carrier route ON and the pack MISSING, so the control has to be exactly
     * that. Same shape and same reasoning as {@code RuntimeConfig.deferPack}: a seam whose only
     * job is to make the arm honest.
     */
    public static final String OFF_PROPERTY = ModdingFromAModMod.MODID + ".itempack";

    /** {@code {url, sha1}} once published, or null. */
    private static volatile String[] current;

    /** Whether the push is on at all. Off only for the Gate C control. */
    public static boolean enabled() {
        String m = System.getProperty(OFF_PROPERTY, "").trim();
        return !("off".equalsIgnoreCase(m) || "false".equalsIgnoreCase(m));
    }

    /** How many stock clients have been pushed this pack. Read by the gates and the report. */
    private static final java.util.concurrent.atomic.AtomicInteger PUSHES =
            new java.util.concurrent.atomic.AtomicInteger();

    private ItemPackService() {
    }

    public static int pushes() {
        return PUSHES.get();
    }

    /** What is on offer, or null. Read by the gates. */
    public static String currentSha1() {
        String[] pack = current;
        return pack == null ? null : pack[1];
    }

    /** Forget the offer — the seam a GameTest needs instead of stopping a server. */
    public static void forgetForTest() {
        current = null;
        PUSHES.set(0);
    }

    /**
     * <b>Write the pack, exactly as it will be pushed.</b>
     *
     * <p>Separate from {@link #publish} and takes a plain directory so a unit test can compare the
     * bytes a stock client would receive against the mod's own shipped copy without a server, a
     * world or a client — which is {@code StockClientIconTest}, the gate that went red the day this
     * class did not exist.
     */
    public static Path buildPack(Path root) throws Exception {
        // "on" is the real pack; `shift` and `drop` are WishPackWriter's own break modes and exist
        // for the oracle. Named rather than passed through from a property: a server that could be
        // talked into publishing `drop` would hand every child a horse armour on purpose.
        return WishPackWriter.write("on", root);
    }

    /**
     * Build the pack under {@code serverDir}, serve it, and remember the offer.
     *
     * <p>Takes the directory and the advertised host rather than a {@code MinecraftServer} so the
     * whole publish path is drivable from a test.
     *
     * @return {@code {url, sha1}}
     */
    public static String[] publish(Path serverDir, String advertiseHost) throws Exception {
        Path root = serverDir.resolve("resourcepacks").resolve(PACK_DIR);
        buildPack(root);
        PackHost.start(PackHostConfig.bindHost(), PackHostConfig.port());
        String url = PackHost.publish(root, advertiseHost);
        String sha1 = PackHost.sha1Of(url);
        current = new String[] { url, sha1 };
        return current;
    }

    // ── the wiring ──────────────────────────────────────────────────────────────────────────

    /**
     * Publish at server start, before anybody can join.
     *
     * <p>{@code ServerStartedEvent} rather than a lazy first-join build, because the alternative is
     * a child's join blocking on a zip while he waits at the loading screen — and the pack does not
     * depend on anything a player brings with him.
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!enabled()) {
            // The Gate C control. Loud, because a silent one is indistinguishable from a broken
            // publish and the whole point of the arm is that the two frames differ for a KNOWN
            // reason.
            LOG.warn("the item pack push is OFF ({}=off) -- every wished thing and Moddy himself "
                    + "will render as a plain leather horse armour on any client without the mod. "
                    + "This is the #519 control arm; it is not a configuration a child should be "
                    + "playing on.", OFF_PROPERTY);
            return;
        }
        if (!VanillaCarrier.enabled()) {
            // Not a failure. With the carrier route off a wished thing is a MOD item, which a stock
            // client cannot be handed at all, so there is nothing for this pack to dress. Said out
            // loud because "no pack was published" and "the pack failed to publish" look identical
            // in a log that is silent about the first.
            LOG.info("the vanilla carrier route is off, so no item pack is published -- nothing a "
                    + "stock client can hold needs it");
            return;
        }
        try {
            String[] pack = publish(event.getServer().getServerDirectory(),
                    PackHostConfig.advertiseHost(event.getServer()));
            LOG.info("item pack published at {} -- {} wish icon(s) plus Moddy at {}",
                    pack[0], com.moddingfromamod.item.WishIcons.all().size(),
                    VanillaCarrier.WATCH_MODEL_DATA);
        } catch (Exception e) {
            // Loud, and it names the CONSEQUENCE rather than the exception alone: without this pack
            // a client with no mod holds a leather horse armour where his watch should be, which
            // reads as a broken item rather than as a missing download.
            LOG.error("the item pack could not be published -- every wished thing, and Moddy "
                    + "himself, will render as a plain leather horse armour on any client without "
                    + "the mod. This is a delivery failure, not an artwork one. Retrying every {} s "
                    + "until it is.", RETRY_MS / 1000, e);
            retryUntilPublished(event.getServer());
        }
    }

    /** How long between attempts when the boot-time publish failed. */
    static final long RETRY_MS = 15_000;

    private static volatile Thread retrier;

    /**
     * <b>Keep trying, because a dedicated server runs for DAYS on one boot.</b> The publish used to be tried
     * once, at server start, and a failure was final until the next restart. Measured on a live
     * server over three days: a release's camera prime held the pack port for the minute the
     * server took to boot, the bind failed with {@code Address already in use}, and every stock
     * client saw missing textures for three days while the server suspended and resumed the same
     * process. Whatever held the port had let it go within a minute.
     *
     * <p>A plain daemon thread with its own try/catch, not a scheduled executor (CLAUDE.md: a task
     * that dies takes its reason into a {@code Future} nobody reads). On success the pack is offered
     * to everyone already on, on the server thread, since they joined while there was none.
     */
    static void retryUntilPublished(MinecraftServer server) {
        Thread t = new Thread(() -> {
            for (int attempt = 1; retrier == Thread.currentThread(); attempt++) {
                try {
                    Thread.sleep(RETRY_MS);
                } catch (InterruptedException e) {
                    return;
                }
                if (retrier != Thread.currentThread()) {
                    return;
                }
                try {
                    String[] pack = publish(server.getServerDirectory(),
                            PackHostConfig.advertiseHost(server));
                    LOG.info("item pack published at {} on retry {} -- offering it to the {} "
                            + "player(s) already on", pack[0], attempt,
                            server.getPlayerList().getPlayerCount());
                    server.execute(() -> server.getPlayerList().getPlayers()
                            .forEach(p -> offerTo(p, pack[0], pack[1])));
                    retrier = null;
                    return;
                } catch (Exception e) {
                    // Once, then every ~5 minutes: a port held all day must not fill the log.
                    if (attempt == 1 || attempt % 20 == 0) {
                        LOG.warn("item pack retry {} failed: {}", attempt, e.toString());
                    }
                }
            }
        }, "mfam-item-pack-retry");
        t.setDaemon(true);
        retrier = t;
        t.start();
    }

    /**
     * <b>Offer it to a client with no mod, on join.</b>
     *
     * <p>Unconditional on purpose, twice over. The server cannot know what a joining client already
     * has, and the client CAN — a vanilla client de-duplicates a resource pack by sha1, so a
     * re-offer of bytes it already applied is free. And this pack never changes, so "offer once and
     * remember who" would be state that buys nothing and can go wrong.
     *
     * <p>The route predicate is the same one {@link RigReveal} and {@code Send.to} use: a
     * connection that can receive our payload is modded and needs none of this.
     */
    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        String[] pack = current;
        if (pack == null || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        offerTo(player, pack[0], pack[1]);
    }

    /**
     * Tell one client where the pack is, if it is a client that needs telling.
     *
     * @return true if it was pushed
     */
    public static boolean offerTo(ServerPlayer player, String url, String sha1) {
        boolean hasChannel = com.moddingfromamod.net.Send.hasOurJar(player);
        if (!PackDelivery.itemPackNeeded(hasChannel, isIntegratedHost(player))) {
            // A modded client -- or the integrated server's own host (D32) -- reads the same
            // bindings out of the mod jar. Pushing would buy it a multi-second reload, and the host
            // a "this server requires a custom resource pack" prompt on their own world, and change
            // nothing it renders.
            return false;
        }
        player.connection.send(new ClientboundResourcePackPushPacket(
                // A DIFFERENT UUID from the rig pack's, derived from these bytes, which is what
                // makes two packs a STACK rather than a replacement: a client keys a server pack by
                // this id and pops the one it names. Two packs sharing an id would silently mean
                // one artwork table.
                UUID.nameUUIDFromBytes(sha1.getBytes(StandardCharsets.UTF_8)),
                // REQUIRED, from the one place that decides it -- see PackDelivery.REQUIRED.
                url, sha1, PackDelivery.REQUIRED, Optional.empty()));
        PUSHES.incrementAndGet();
        PackHost.notePush("item stock " + sha1.substring(0, Math.min(8, sha1.length())));
        LOG.info("item pack {} offered to '{}' (no mod)", sha1.substring(0, 8),
                player.getGameProfile().getName());
        return true;
    }

    /** A GameTest's stand-in for the integrated server's host (null: none). */
    private static volatile UUID hostForTest;

    public static void hostForTest(UUID id) {
        hostForTest = id;
    }

    /**
     * Is this player the HOST of the integrated server (D32)? Decided by the public core itself,
     * from Minecraft's own answer: {@code isSingleplayerOwner} is false for everyone on a dedicated
     * server, and on an integrated one true only for the player whose game it runs in -- a LAN
     * guest is not the host.
     */
    static boolean isIntegratedHost(ServerPlayer player) {
        if (hostForTest != null && hostForTest.equals(player.getUUID())) {
            return true;
        }
        var server = player.getServer();
        return server != null && !server.isDedicatedServer()
                && server.isSingleplayerOwner(player.getGameProfile());
    }

    /**
     * Forget the offer when the world closes — the URL names a port this process will not be
     * listening on next time. Same reasoning as {@link RigReveal#onServerStopping}.
     *
     * <p><b>And close the host itself, which nothing did until MQP-C2b.</b> {@link PackHost#stop()}
     * had no production caller at all: both stopping handlers forgot their offer and left the
     * {@code HttpServer} listening, so a headless Gate C½ {@code runServer} kept a bound port and a
     * live dispatcher after the world had saved and the JVM had nothing else to do (measured
     * 2026-09-09: a millstone wish PASSed at 21:34:30 and the process was still up at 21:39:10).
     * {@link PackHost} now starts its dispatcher as a daemon, which is what lets the JVM exit; this
     * is the other half, and it is the half that frees the port.
     *
     * <p><b>Why here and not in {@link RigReveal}</b>, given both start the host and both have
     * a stopping handler. This service is the one whose lifecycle actually spans the host's: it
     * calls {@link PackHost#start} from {@link #onServerStarted}, on every server, before anyone can
     * join — while {@code RigReveal} starts it lazily and only if a child ever wishes a
     * creature. Opening and closing in one file is the version of this that a reader can check.
     * {@code stop()} is idempotent and null-safe, so it is still correct on a server where this
     * service published nothing and {@code RigReveal} opened the host instead.
     */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        Thread r = retrier;
        retrier = null;
        if (r != null) {
            r.interrupt();
        }
        current = null;
        PackHost.stop();
    }

    /** Re-publish now, for a caller that changed the route mid-session. */
    public static void republish(MinecraftServer server) throws Exception {
        publish(server.getServerDirectory(), PackHostConfig.advertiseHost(server));
    }
}
