package com.moddingfromamod.lite;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.tools.MakeItemTool;
import com.moddingfromamod.datapack.ItemBase;
import com.moddingfromamod.datapack.VanillaCarrier;
import com.moddingfromamod.net.ModNetwork;
import com.moddingfromamod.net.ModdedClient;
import com.moddingfromamod.net.Send;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>What a stock client actually gets — measured from the server, while one is really connected.</b>
 *
 * <p>Answers the enumeration half of
 * which of this
 * mod's surfaces silently vanish for a client that has no mod. It is a <em>probe</em>, not a
 * feature — it changes nothing, ships nothing, and is completely inert unless
 * {@code -Dmoddingfromamod.liteprobe=<dir>} names somewhere to write.
 *
 * <h2>The one line this class exists for</h2>
 *
 * <p>{@link NetworkRegistry#getConnectionType(net.minecraft.network.Connection)} must report
 * {@link ConnectionType#OTHER}. Without it the whole run is worthless, and worthless in the
 * reassuring direction: the dev client this repo tests with <em>is</em> a NeoForge client, so a run
 * that reads as a pass is equally consistent with <i>"a vanilla client joined fine"</i> and
 * <i>"you accidentally tested a modded client"</i>. This is the only thing that tells them apart,
 * so it is recorded first and quoted in the report.
 *
 * <h2>Why {@code canReceive} rather than a list of what we believe</h2>
 *
 * <p>The nine clientbound payloads are read from the network layer itself rather than transcribed,
 * for the reason this repo has already paid for twice: a transcription is a second source of truth,
 * and the one that is wrong is always the one nobody re-derived. Asking {@link Send#canReceive} is
 * asking the same question the shipping code asks, on the same connection, at the same moment.
 *
 * <p><b>It is not a prediction that they are absent.</b> A payload the client cannot receive is a
 * <em>surface</em> that vanishes, and which surface that is takes reading the handler — so the
 * report names the payload and the doc names the affordance, rather than this class guessing.
 */
public final class LiteProbe {

    private static final Logger LOG = LoggerFactory.getLogger(LiteProbe.class);

    /** {@code -Dmoddingfromamod.liteprobe=<dir>}. Absent = the class never registers. */
    public static final String PROPERTY = ModdingFromAModMod.MODID + ".liteprobe";

    /**
     * Every payload the server can push AT a client, in registration order.
     *
     * <p>Deliberately the clientbound ones only: a {@code playToServer} payload a vanilla client
     * cannot send is a surface the child never reaches rather than a broadcast that throws, and
     * conflating the two is how "nine things break" would be reported for a number that is not
     * nine.
     */
    private static final Map<String, ModdedClient.Channel> CLIENTBOUND = new LinkedHashMap<>();

    static {
        CLIENTBOUND.put("species_sync", ModdedClient.Channel.SPECIES_SYNC);
        CLIENTBOUND.put("preset_sync", ModdedClient.Channel.PRESET_SYNC);
        CLIENTBOUND.put("wish_item_sync", ModdedClient.Channel.WISH_ITEM_SYNC);
        CLIENTBOUND.put("photo", ModdedClient.Channel.PHOTO);
        CLIENTBOUND.put("capture_mode", ModdedClient.Channel.CAPTURE_MODE);
        CLIENTBOUND.put("ask_which", ModdedClient.Channel.ASK_WHICH);
        CLIENTBOUND.put("watch_sync", ModdedClient.Channel.WATCH_SYNC);
        CLIENTBOUND.put("clip", ModdedClient.Channel.CLIP);
    }

    private static final List<String> LINES = new ArrayList<>();
    private static long joinedAtMs;
    private static ServerPlayer subject;
    private static int step;

    private LiteProbe() {
    }

    /** Whether this run was asked for a probe at all. */
    public static boolean active() {
        return !System.getProperty(PROPERTY, "").isBlank();
    }

    private static Path sigDir() {
        return Path.of(System.getProperty(PROPERTY));
    }

    private static void record(String key, Object value) {
        String line = key + "\t" + value;
        LINES.add(line);
        LOG.info("LITEPROBE {}", line);
        try {
            Files.createDirectories(sigDir());
            Files.writeString(sigDir().resolve("probe.tsv"),
                    String.join("\n", LINES) + "\n", StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.warn("LITEPROBE could not write the report: {}", e.toString());
        }
    }

    /**
     * <b>Every channel on this server, and whether it is optional — which is the whole gate.</b>
     *
     * <p>{@code NetworkRegistry.initializeOtherConnection} negotiates the server's registrations
     * against an <b>empty</b> client list, so an optional component is dropped and a required one
     * fails the whole handshake. The list of required channels is therefore an exact,
     * per-mod list of what is keeping a stock client out — which is a far better instrument than
     * reading one suspected mod's bytecode, because it generalises to whatever else is installed.
     *
     * <p>Reflection, and stated as such. {@code PAYLOAD_REGISTRATIONS} is {@code private static}
     * and {@code PayloadRegistration} is {@code @ApiStatus.Internal}, so there is no supported way
     * to ask. A probe may do this; shipping code may not, which is why this lives here and is
     * wrapped so a NeoForge version that moves the field degrades to a recorded line rather than
     * to a crash on a child's server.
     */
    /**
     * Build the pack a stock client needs, <b>on the server</b>, and report its SHA-1.
     *
     * <p>{@code -Dmoddingfromamod.litepack=on|shift|drop}. This is the half that had never
     * existed: the pack could only ever be built by a {@code Dist.CLIENT} class, so a server had
     * nothing to push. Hosting and pushing need no mod code at all — vanilla
     * {@code server.properties} carries {@code resource-pack}, {@code resource-pack-sha1} and
     * {@code require-resource-pack} — so this stops at "the bytes exist and here is their hash",
     * which is the smallest thing that makes the rest measurable.
     */
    private static void buildPack() {
        String mode = System.getProperty(ModdingFromAModMod.MODID + ".litepack", "").trim();
        if (mode.isEmpty()) {
            return;
        }
        try {
            Path dir = sigDir().resolve("pack-" + mode);
            Path zip = sigDir().resolve("wishpack-" + mode + ".zip");
            com.moddingfromamod.pack.WishPackWriter.write(mode, dir);
            String sha1 = com.moddingfromamod.pack.WishPackWriter.zip(dir, zip);
            record("pack_mode", mode);
            record("pack_zip", zip);
            record("pack_bytes", Files.size(zip));
            record("pack_sha1", sha1);
        } catch (Exception e) {
            record("pack_build", "FAILED " + e);
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        buildPack();
        try {
            Class<?> reg = Class.forName(
                    "net.neoforged.neoforge.network.registration.NetworkRegistry");
            java.lang.reflect.Field f = reg.getDeclaredField("PAYLOAD_REGISTRATIONS");
            f.setAccessible(true);
            Map<?, ?> byProtocol = (Map<?, ?>) f.get(null);
            List<String> required = new ArrayList<>();
            int total = 0;
            for (Map.Entry<?, ?> proto : byProtocol.entrySet()) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) proto.getValue()).entrySet()) {
                    Object r = e.getValue();
                    boolean opt = (boolean) r.getClass().getMethod("optional").invoke(r);
                    total++;
                    if (!opt) {
                        required.add(proto.getKey() + "/" + e.getKey());
                    }
                }
            }
            record("channels_total", total);
            record("channels_required", required.size());
            java.util.Map<String, Integer> byMod = new java.util.TreeMap<>();
            for (String id : required) {
                String ns = id.substring(id.indexOf('/') + 1);
                ns = ns.contains(":") ? ns.substring(0, ns.indexOf(':')) : ns;
                byMod.merge(ns, 1, Integer::sum);
            }
            record("required_by_mod", byMod);
            for (String id : required) {
                record("required_channel", id);
            }
            record("vanilla_client_can_join", required.isEmpty()
                    ? "YES - nothing required" : "NO - " + required.size() + " required channel(s)");
        } catch (ReflectiveOperationException | RuntimeException e) {
            record("channel_census", "UNAVAILABLE " + e);
        }
    }

    /**
     * <b>The two-children arm: both mid-wish at once, narrating through the REAL tool.</b>
     *
     * <p>Fires when a second child joins, because that is the only configuration in which the
     * defect exists — {@code WishProgress}'s ticker renders only to a player waiting on a wish of
     * his own, so one child wishing while another stands about shows nothing and passes.
     *
     * <p>Drives {@code SetStatusTool} rather than {@code WishProgress.setStatus}, because the bug
     * was in the CALLER's loop and calling the store directly would step over the thing under test.
     * On its own thread for the same reason the gate is: {@code AgentBridge} refuses a call made
     * from the server thread.
     */
    /** Is this the two-children arm? One reader, so the two call sites cannot disagree. */
    private static boolean twoChildren() {
        return "on".equalsIgnoreCase(
                System.getProperty(com.moddingfromamod.ModdingFromAModMod.MODID + ".twochildren", ""));
    }

    /** Is this the REAL-WISH capture arm? One reader, so the call sites cannot disagree. */
    private static boolean wishArm() {
        return "on".equalsIgnoreCase(
                System.getProperty(com.moddingfromamod.ModdingFromAModMod.MODID + ".wishpaths", ""));
    }

    /** Is this the capture-paths arm? One reader, so the call sites cannot disagree. */
    private static boolean cameraArm() {
        return "on".equalsIgnoreCase(
                System.getProperty(com.moddingfromamod.ModdingFromAModMod.MODID + ".cameraproof", ""));
    }

    private static volatile boolean cameraShotFired;

    /**
     * <b>The two capture topologies, measured in the same rig so the difference is the finding.</b>
     *
     * <ol>
     *   <li><b>One machine.</b> No camera configured, the child's own modded client. The agent
     *       borrows his eyes: he is frozen, told, and warped back. Today's behaviour.</li>
     *   <li><b>Server + camera + a VANILLA child.</b> The camera shoots and
     *       <b>the child is never interrupted</b> — which is the whole point, and is the assertion.</li>
     * </ol>
     *
     * <p><b>⚠ The freeze is counted, not sampled.</b> {@code WishCapture.isCapturing} answers "is he
     * frozen right now", and a poll that lands between two four-second sessions cannot tell that
     * from "never frozen". {@code timesBorrowed} only goes up, so a session that opened and closed
     * while nobody was looking still shows.
     *
     * <p><b>It waits for the whole cast.</b> A child must be present in both arms, and in arm 2 the
     * camera as well — firing on "somebody joined" would let arm 2 pass having photographed through
     * the child, which is the exact thing it exists to rule out.
     */
    private static void runCapturePathArm(MinecraftServer server) {
        if (cameraShotFired) {
            return;
        }
        var players = server.getPlayerList().getPlayers();
        ServerPlayer child = null;
        ServerPlayer camera = null;
        for (ServerPlayer p : players) {
            if (com.moddingfromamod.marshal.PlayerKind.isChild(p.getUUID())) {
                if (child == null) {
                    child = p;
                }
            } else if (camera == null) {
                camera = p;
            }
        }
        boolean wantCamera = !com.moddingfromamod.runtime.RuntimeConfig.cameraName().isEmpty();
        if (child == null || (wantCamera && camera == null)) {
            return;   // the cast is not assembled; firing now would measure the wrong topology
        }
        cameraShotFired = true;

        final ServerPlayer subjectChild = child;
        buildSomethingToLookAt(server);
        record("paths.arm", wantCamera ? "2-server-camera-vanilla-child" : "1-one-machine");
        record("paths.child", child.getGameProfile().getName());
        record("paths.camera", camera == null ? "(none)" : camera.getGameProfile().getName());
        record("paths.viewpoint", String.valueOf(
                com.moddingfromamod.wish.Viewpoint.acquire(server)));
        int borrowedBefore =
                com.moddingfromamod.wish.WishCapture.timesBorrowed(child.getUUID());
        record("paths.child_borrowed_before", String.valueOf(borrowedBefore));

        Thread t = new Thread(() -> {
            // ⚠ TIME IT, because "raise the budget until it goes green" is tuning rather than
            // measuring, and this repo has a standing rule against exactly that. Both arms reported
            // failure to the agent while a good PNG was on disk — PhotographTool budgets 8s and a
            // headless llvmpipe client is slower — so the number that budget should be is a
            // measurement nobody has taken. This takes it.
            java.nio.file.Path out = com.moddingfromamod.runtime.RuntimeConfig.home()
                    .resolve("photos").resolve("cameraproof.png");
            long t0 = System.currentTimeMillis();
            try {
                var reply = new com.moddingfromamod.agent.tools.PhotographTool().call(
                        com.google.gson.JsonParser.parseString("{\"label\":\"cameraproof\"}")
                                .getAsJsonObject());
                record("paths.photograph", reply.isError() ? "ERROR " + reply.text() : "OK");
            } catch (RuntimeException e) {
                record("paths.photograph", "THREW " + e);
            }
            record("paths.tool_returned_after_ms", String.valueOf(System.currentTimeMillis() - t0));
            // How long until the file is there AND has stopped growing — the same question the tool
            // asks, asked without its deadline so the answer is a number rather than a verdict.
            long lastSize = -1;
            String stableAt = "never";
            while (System.currentTimeMillis() - t0 < 60_000) {
                long size = java.nio.file.Files.exists(out) ? out.toFile().length() : 0;
                if (size > 0 && size == lastSize) {
                    stableAt = String.valueOf(System.currentTimeMillis() - t0);
                    break;
                }
                lastSize = size;
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            record("paths.photo_stable_after_ms", stableAt);
            // Read AFTER the shot, on this thread, so the number covers the whole capture rather
            // than a tick that happened to be quiet.
            int after = com.moddingfromamod.wish.WishCapture.timesBorrowed(subjectChild.getUUID());
            record("paths.child_borrowed_after", String.valueOf(after));
            record("paths.child_was_interrupted", after > borrowedBefore ? "YES" : "NO");
        }, "capture-paths");
        t.setDaemon(true);
        t.start();
    }

    private static String wishChildName;
    private static java.util.UUID wishChildId;
    private static int wishBorrowedBefore;
    private static int wishBorrowedSeen;
    private static boolean wishVerdictRecorded;

    /**
     * <b>Who got photographed and who got frozen, across a REAL wish.</b>
     *
     * <p>{@link #runCapturePathArm} answers the same question about ONE {@code photograph} call
     * with no subject, which skips the building, held-item and orbit branches entirely — it proves
     * the shutter fires from the right eye and nothing about the rig a child actually gets. This
     * watches the whole grant instead: the four orbit stops and the clip all run through
     * {@code Viewpoint}, so if any one of them reached for the child this counter moves.
     *
     * <p><b>Both outcomes are recorded, never one.</b> A rig that writes {@code interrupted=YES}
     * on a freeze and nothing otherwise makes ABSENCE the evidence for the claim it most wants to
     * make — and this rig has already been bitten by exactly that (an arm that never started read
     * as an arm that failed). So the verdict is written when the wish's own result file appears,
     * and it says NO out loud.
     */
    private static void watchWishPathArm(MinecraftServer server) {
        if (wishVerdictRecorded) {
            return;
        }
        boolean wantCamera = !com.moddingfromamod.runtime.RuntimeConfig.cameraName().isEmpty();
        if (wishChildId == null) {
            ServerPlayer child = null;
            ServerPlayer camera = null;
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (com.moddingfromamod.marshal.PlayerKind.isChild(p.getUUID())) {
                    if (child == null) {
                        child = p;
                    }
                } else if (camera == null) {
                    camera = p;
                }
            }
            if (child == null || (wantCamera && camera == null)) {
                return;   // the cast is not assembled; a baseline taken now would measure nothing
            }
            wishChildId = child.getUUID();
            wishChildName = child.getGameProfile().getName();
            wishBorrowedBefore =
                    com.moddingfromamod.wish.WishCapture.timesBorrowed(wishChildId);
            wishBorrowedSeen = wishBorrowedBefore;
            record("wish.child", wishChildName);
            record("wish.camera", camera == null ? "(none)" : camera.getGameProfile().getName());
            record("wish.viewpoint", String.valueOf(
                    com.moddingfromamod.wish.Viewpoint.acquire(server)));
            record("wish.child_borrowed_before", String.valueOf(wishBorrowedBefore));
            return;
        }

        // ON CHANGE, not every tick. The counter only goes up, so a change is an event worth a
        // line and a steady value is not; recording every tick would bury the run in its own log.
        int now = com.moddingfromamod.wish.WishCapture.timesBorrowed(wishChildId);
        if (now != wishBorrowedSeen) {
            wishBorrowedSeen = now;
            record("wish.child_borrowed_now", String.valueOf(now));
        }

        // THE WISH'S OWN VERDICT FILE is the end of the grant -- the clip is the last thing shot,
        // so anything earlier would call the freeze before the rig had finished asking for one.
        String sig = System.getProperty(
                com.moddingfromamod.ModdingFromAModMod.MODID + ".headlesswishsig", "");
        if (!sig.isEmpty() && Files.exists(Path.of(sig).resolve("result"))) {
            wishVerdictRecorded = true;
            record("wish.child_borrowed_final", String.valueOf(now));
            record("wish.child_was_interrupted", now > wishBorrowedBefore ? "YES" : "NO");
        }
    }

    /**
     * Something for the lens to find.
     *
     * <p>A photograph of an empty world proves the shutter fired and nothing about what the camera
     * can SEE, which is the gap the first camera run left open. A gold pillar at spawn is not
     * framing — nothing aims at it — but it makes "the camera renders the world" a claim the picture
     * can settle rather than one it merely fails to contradict.
     */
    private static void buildSomethingToLookAt(MinecraftServer server) {
        var level = server.overworld();
        var spawn = level.getSharedSpawnPos();
        for (int dy = 0; dy < 12; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    level.setBlockAndUpdate(spawn.offset(dx, dy, dz + 10),
                            net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState());
                }
            }
        }
        record("paths.subject", "gold pillar at spawn+10z");
    }

    private static void narrateAsTwoAgents(MinecraftServer server) {
        var players = server.getPlayerList().getPlayers();
        if (players.size() < 2) {
            return;
        }
        var a = players.get(0);
        var b = players.get(1);
        record("twochildren.a", a.getGameProfile().getName());
        record("twochildren.b", b.getGameProfile().getName());
        // 🔴 PUT THEM MID-WISH, not merely "narrated at". WishProgress.setStatus writes to a MAP;
        // the action bar is drawn by WishProgress's own ticker, which only visits players who have
        // a WAITING entry — i.e. players for whom started() was called. A probe that narrates
        // without that exercises the tool and nothing else: measured, both children reported an
        // EMPTY action bar while the server's own record showed each slot holding the right line,
        // which reads as a broken transport and is a rig that never turned the surface on. Same
        // shape as the repo's standing rule that a guard tested where it is DEFINED is not tested
        // where it RUNS.
        String workerA = beginWishFor(a, "Building " + a.getGameProfile().getName() + "'s purple castle");
        String workerB = beginWishFor(b, "Building " + b.getGameProfile().getName() + "'s rocket ship");
        Thread t = new Thread(() -> {
            try {
                // Repeated on purpose: the second child's client is still loading terrain at the
                // moment he joins, so a single narration is a race between the client's first tick
                // and nothing at all. A real agent narrates through a wish anyway.
                for (int i = 0; i < 15; i++) {
                    sayAs(workerA, "Building " + a.getGameProfile().getName() + "'s purple castle");
                    sayAs(workerB, "Building " + b.getGameProfile().getName() + "'s rocket ship");
                    if (i == 0) {
                        Thread.sleep(1500L);
                        for (var p : List.of(a, b)) {
                            record(p.getGameProfile().getName() + ".status_slot",
                                    com.moddingfromamod.wish.WishProgress.currentStatus(p.getUUID()));
                        }
                        record("twochildren", "NARRATED");
                    } else {
                        Thread.sleep(2000L);
                    }
                }
            } catch (Throwable e) {
                record("twochildren", "FAILED " + e);
            }
        }, "lite-two-children");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Give this child a real, live wish of his own and return the worker holding it.
     *
     * <p>Server thread. {@code started} is what makes the child <i>waiting</i> as far as
     * {@link com.moddingfromamod.wish.WishProgress}'s ticker is concerned, and therefore what makes
     * his action bar a thing that gets drawn at all.
     */
    private static String beginWishFor(ServerPlayer child, String doing) {
        java.util.UUID wishId = java.util.UUID.randomUUID();
        com.moddingfromamod.wish.Wish wish = new com.moddingfromamod.wish.Wish(wishId, doing,
                com.moddingfromamod.wish.WishSource.TYPED,
                com.moddingfromamod.wish.WishStatus.RUNNING,
                System.currentTimeMillis(), child.getUUID(),
                child.getGameProfile().getName(), "minecraft:overworld",
                0, 64, 0, "", "", 0L);

        // ⚠ THE WISH BOOK IS NOT OPTIONAL, and leaving it out fails in the reassuring direction.
        // WishProgress's ticker looks the active wish up in WishBook every interval, and a wish it
        // cannot find is a wish that FINISHED — so it calls forget(pid), which drops the WAITING
        // entry AND the narration with it. Measured: with started() alone, both children's status
        // slots read back EMPTY a second and a half after the agent had written them, which reads
        // as a broken set_status and is a probe that never put anyone in a wish the mod believes in.
        com.moddingfromamod.wish.WishBook.get(child.getServer()).add(wish);

        var marshal = com.moddingfromamod.marshal.Marshal.global();
        marshal.submit(wishId, child.getUUID(), doing);
        String worker = "lite-" + child.getGameProfile().getName();
        marshal.assign(worker, wishId);
        com.moddingfromamod.wish.WishProgress.started(child, wish);
        return worker;
    }

    /** One child's agent says one thing, bound to that child's own run. */
    private static void sayAs(String worker, String doing) {
        com.moddingfromamod.agent.AgentContext.bind(worker);
        try {
            new com.moddingfromamod.agent.tools.SetStatusTool().call(
                    com.google.gson.JsonParser.parseString(
                            "{\"doing\":\"" + doing + "\"}").getAsJsonObject());
        } finally {
            com.moddingfromamod.agent.AgentContext.unbind();
        }
    }

    @SubscribeEvent
    public static void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        subject = player;
        joinedAtMs = System.currentTimeMillis();
        step = 0;

        // ── THE ORACLE. Everything else in this file is only worth reading if this says OTHER.
        ConnectionType type;
        try {
            type = NetworkRegistry.getConnectionType(player.connection.getConnection());
        } catch (RuntimeException e) {
            record("connection_type_error", e.toString());
            type = null;
        }
        // Keyed by PLAYER as well as globally, so a MIXED session records both rather than
        // whichever happened to join last -- which is the only shape in which the mixed
        // question can be answered at all.
        String who = player.getGameProfile().getName();
        record("player", who);
        record(who + ".connection_type", type == null ? "UNKNOWN" : type.name());
        record(who + ".is_other", type == ConnectionType.OTHER);
        record("connection_type", type == null ? "UNKNOWN" : type.name());
        record("is_other", type == ConnectionType.OTHER);
        record("optional_payloads", ModNetwork.optionalPayloads());
        record("carrier_route_enabled", VanillaCarrier.enabled());
        record("carrier_id", VanillaCarrier.itemId());

        // ── Which of our own surfaces can reach this client at all.
        int deliverable = 0;
        for (Map.Entry<String, ModdedClient.Channel> e : CLIENTBOUND.entrySet()) {
            boolean ok = ModdedClient.can(player, e.getValue());
            record(who + ".payload." + e.getKey(), ok ? "DELIVERABLE" : "VANISHES");
            if (ok) {
                deliverable++;
            }
        }
        record(who + ".payloads_deliverable", deliverable + "/" + CLIENTBOUND.size());

        // an earlier issue's stock-client chest: he owns two castles (memory only), so `ask_which` has a
        // question to ask. Inert unless `whichprobe` names a directory too.
        WhichProbe.seed(player);

        // ── The commands. The Tier-2 reading says VanillaConnectionNetworkFilter.filterCommandList
        // strips only commands using MODDED argument types, and that this mod registers none — so
        // ours should survive. Asked of the live dispatcher rather than of the source.
        MinecraftServer server = player.getServer();
        if (server != null) {
            for (String name : List.of("wish", "moddy")) {
                record("command." + name,
                        server.getCommands().getDispatcher().getRoot().getChild(name) != null
                                ? "REGISTERED" : "ABSENT");
            }
        }

        // TWO CHILDREN, both mid-wish. Only meaningful once a second one is here.
        if (server != null && twoChildren()) {
            narrateAsTwoAgents(server);
        }
        // THE TWO CAPTURE TOPOLOGIES, once the cast for this arm is assembled.
        if (server != null && cameraArm()) {
            runCapturePathArm(server);
        }
    }

    /**
     * The rest of the probe, spread over real seconds.
     *
     * <p>Spread rather than done at join because a join is the one moment the connection is least
     * settled, and because the interesting failures (a broadcast that throws, a pack that has not
     * finished applying) are not synchronous with it. Timed in wall-clock, not ticks — a dedicated
     * server does tick at 20/s, but the deadline is about the client, which is a different process.
     */
    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (subject == null || subject.hasDisconnected()) {
            return;
        }
        WhichProbe.tick(subject);
        // ⚠ THE WISH ARM IS WATCHED HERE, NOT AT JOIN, and putting it there first cost a run.
        //
        // A grant is a couple of minutes long: the baseline has to be taken when the cast arrives
        // and the verdict when the wish finishes, which is thousands of ticks later. Called from
        // onJoin it ran ONCE, so the run recorded a viewpoint and a borrowed-before count and
        // never the answer -- and an empty `interrupted` column reads as "the rig could not tell",
        // which is exactly the shape of evidence this arm exists to refuse.
        if (wishArm() && subject.getServer() != null) {
            watchWishPathArm(subject.getServer());
        }
        long elapsed = (System.currentTimeMillis() - joinedAtMs) / 1000L;
        MinecraftServer server = subject.getServer();
        if (server == null) {
            return;
        }
        // THE STOCK-CLIENT ATOM ARM. Here rather than at join for the reason above: the item pack
        // is pushed at join and a stock client applies one by RELOADING, so a creature staged on
        // the join tick lands while the client is still rebuilding its atlases.
        StockAtomStage.tick(server, subject, elapsed);
        // the same kind of arm for a "copy of me" turning to look at the child behind it.
        StockHairStage.tick(server, subject, elapsed);
        if (step == 0 && elapsed >= 5) {
            step = 1;
            // set_status's transport, driven the way WishProgress drives it: the vanilla action
            // bar. If this arrives, the agent's narration survives on a stock client.
            //
            // ⚠ NOT in two-children mode. That arm's whole subject is WHICH line lands in WHICH
            // child's action bar, and this one is written five seconds after each join — so it
            // arrives after the narration and overwrites it. Measured: both children reported
            // seeing "LITEPROBE status line", which reads as the mod addressing the room while the
            // server's own record showed each slot holding its own child's wish. One probe writing
            // over another is a rig fault, and a rig that can produce that reading is worse than
            // one that skips a row it cannot ask cleanly.
            if (twoChildren()) {
                record("sent.action_bar", "SKIPPED (twochildren arm owns the action bar)");
            } else {
                subject.displayClientMessage(Component.literal("LITEPROBE status line"), true);
                record("sent.action_bar", "displayClientMessage(overlay=true)");
            }
            subject.sendSystemMessage(Component.literal("LITEPROBE chat line"));
            record("sent.chat", "sendSystemMessage");
        }
        if (step == 1 && elapsed >= 8) {
            step = 2;
            giveAWishedThing(server);
        }
        if (step == 2 && elapsed >= 25) {
            step = 25;
            pushAgain();
        }
        if (step == 25 && elapsed >= 12) {
            step = 3;
            // The broadcast that says a Lite server would throw on once a second. Ours is
            // guarded; this asserts the guard on a REAL vanilla connection rather than on a mock,
            // which is the whole difference between Tier 2 and Tier 1 for that row.
            try {
                Send.all(server, ModdedClient.watch(java.util.List.of("lite probe"), java.util.List.of()));
                record("guarded_broadcast", "SURVIVED");
            } catch (RuntimeException e) {
                record("guarded_broadcast", "THREW " + e);
            }
            record("held_stack", subject.getMainHandItem().toString());
            record("held_count", subject.getMainHandItem().getCount());
            record("verdict", "COMPLETE");
        }
    }

    /**
     * <b>Push a DIFFERENT pack mid-session — the thing exists to price.</b>
     *
     * <p>{@code -Dmoddingfromamod.litepush=<url>|<sha1>}. A wish is granted while the child is
     * playing, and new content needs new pack content, so the only alternative to "wait until you
     * rejoin" is a push at the reveal. That costs a client resource reload, and the two independent
     * measurements on record (1,694 ms for items; 1,745-2,597 ms for rigs) say roughly how much.
     *
     * <p>Both of those were taken on a <b>modded</b> client reloading a locally-installed pack.
     * This is the same event on a stock client that must also FETCH the pack first, which is the
     * number the decision actually needs — and it is timed on the picture the child is looking at
     * rather than on the call, because the call returns immediately (the creature spike measured
     * 5-29 ms that way and had to redo it).
     */
    private static void pushAgain() {
        String spec = System.getProperty(ModdingFromAModMod.MODID + ".litepush", "").trim();
        if (spec.isEmpty() || subject == null) {
            return;
        }
        String[] parts = spec.split("\\|");
        if (parts.length != 2) {
            record("push_again", "MALFORMED " + spec);
            return;
        }
        try {
            long at = System.currentTimeMillis();
            subject.connection.send(new net.minecraft.network.protocol.common
                    .ClientboundResourcePackPushPacket(java.util.UUID.randomUUID(),
                    parts[0], parts[1], true, java.util.Optional.empty()));
            record("push_again_at_ms", at);
            record("push_again", parts[0]);
        } catch (RuntimeException e) {
            record("push_again", "FAILED " + e);
        }
    }

    /**
     * Hand over a real wished thing, through the REAL give line.
     *
     * <p>{@link MakeItemTool#gives} is the production renderer and the only thing that performs the
     * join between {@link ItemBase#id} and {@code wearsOurIcons} — §3.C.14: a fixture that rebuilds
     * the give line tests its rebuild. The command is then dispatched by the server as the player,
     * exactly as a granted wish does it.
     */
    private static void giveAWishedThing(MinecraftServer server) {
        String give;
        try {
            give = MakeItemTool.gives("plain", "liteprobe_cup", 3, 1,
                    "A cup for the Lite spike",
                    List.of(new MakeItemTool.Variant("Lite Probe Cup", 0xE53935,
                            new com.google.gson.JsonObject())));
        } catch (RuntimeException e) {
            record("give_line", "COULD NOT RENDER " + e);
            return;
        }
        record("give_line", give.replace("\n", " ⏎ "));
        CommandSourceStack src = subject.createCommandSourceStack()
                .withSuppressedOutput().withPermission(4);
        int ok = 0;
        int failed = 0;
        for (String line : give.split("\n")) {
            String cmd = line.trim();
            if (cmd.isEmpty()) {
                continue;
            }
            try {
                server.getCommands().performPrefixedCommand(src, cmd);
                ok++;
            } catch (RuntimeException e) {
                failed++;
                record("give_failed", cmd + " -> " + e);
            }
        }
        record("give_dispatched", ok + " ok, " + failed + " failed");
    }
}
