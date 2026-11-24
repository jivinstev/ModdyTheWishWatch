package com.moddingfromamod.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.wish.Wish;
import com.moddingfromamod.wish.WishBook;
import com.moddingfromamod.wish.WishService;
import com.moddingfromamod.wish.WishSource;
import com.moddingfromamod.wish.WishStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Gate D with no client at all — the rung between the routing arm and the real Gate D.</b>
 *
 * <p>{@code tools/routing-arm.py} replays the real contract against a <em>stub</em> MCP; Gate D is
 * the real agent plus a real client. This is the middle: <b>a real agent, a real dedicated server, a
 * real MCP, and a real wish</b>, with no client anywhere. It exercises routing, tool calls, CBScript
 * compilation and actual world mutation, and costs no client boot.
 *
 * <h2>Why this class has to exist at all</h2>
 *
 * <p>The granting half of the wish path is already client-free — nothing under {@code agent/}
 * touches {@code Minecraft.getInstance()}, and {@code WishAgent.onServerStarted} is a server event.
 * <b>The wishing half is not.</b> Every way to originate a wish is typed on {@link
 * net.minecraft.server.level.ServerPlayer}: all twelve {@code /wish} entry points call
 * {@code getPlayerOrException()}, {@code WishService.submit} takes one, so does
 * {@code SubmitWishPayload}, and the resume path fires on {@code PlayerLoggedInEvent}. A
 * dedicated-server <em>console is not a player</em>, so {@code /wish} typed there simply throws, and
 * {@code GameTestHelper.makeMockPlayer} returns a {@code Player} rather than a {@code ServerPlayer}
 * so it does not satisfy {@code submit} either.
 *
 * <p>NeoForge's {@link FakePlayer} <em>does</em> extend {@code ServerPlayer}, and carries a real
 * (discarding) packet listener, so it can stand in for the child on the submission path. That is the
 * whole trick, and it is the honest one: <b>this is a fake player standing in for a child, not a
 * child.</b> What it proves is that a wish, once made, is granted end to end without a client. It
 * proves nothing about the chat UI, the watch, the buttons, or how any of it looks — see the
 * limitations below.
 *
 * <h2>Why not a GameTest</h2>
 *
 * <p>Because a real agent takes minutes of wall-clock and <b>a GameTest server ticks as fast as it
 * can</b>, with no fixed relationship to seconds (CLAUDE.md records 120 ticks passing in about a
 * fifth of a second). A {@code timeoutTicks} budget is therefore meaningless against a network
 * round-trip: the test would fail while the feature worked. So this runs on a <em>dedicated</em>
 * server, which does tick at 20/s, and every deadline here is nevertheless measured in
 * {@link System#currentTimeMillis()} rather than ticks, so a laggy server slows the run down instead
 * of failing it.
 *
 * <h2>Activation</h2>
 *
 * <p>Entirely inert unless {@code -Dmoddingfromamod.headlesswish} is set, and
 * {@link ModdingFromAModMod} only registers it when that property is present — so it has no
 * footprint whatsoever in real play. The wish arrives <b>base64-encoded</b>, for the reason
 * {@code run-gated.command} already documents at length: a wish is a sentence, and a sentence passed
 * raw through {@code -Ptestprops} is cut at its first comma, silently, after which the agent answers
 * a wish nobody made.
 *
 * <pre>
 *   -Dmoddingfromamod.headlesswish=&lt;base64 of the wish&gt;
 *   -Dmoddingfromamod.headlesswishsig=/tmp/moddingfromamod-headless   (where to write the verdict)
 *   -Dmoddingfromamod.headlesswishtimeout=900                          (seconds; default 900)
 *   -Dmoddingfromamod.headlesswishas=Steve      (with a cast: wish AS this joined child, not the
 *                                               FakePlayer, so a wish about him reaches him — T6C)
 *   -Dmoddingfromamod.headlesswishterrain=sea  (a sea where the child is looking, so the
 *                                               wish is aimed over open water, as five of fifteen
 *                                               wishes were on 2026-10-03)
 *   -Dmoddingfromamod.agent=on                                         (forces a REAL agent)
 * </pre>
 *
 * <p>That last one matters: {@code AgentSession.shouldRun()} is false for a test run, so without it
 * this probe would submit a wish that no agent is listening for and then time out — which reads
 * exactly like a broken agent.
 *
 * <h2>What it CANNOT see</h2>
 *
 * <p>The visual half, and it must say so rather than implying a pass. Whether the thing <em>looks</em>
 * like what was asked for needs an eye and a client; {@code docs/CAMERA.md} is a catalogue of runs
 * that reported PASS on a photograph of the wrong thing. This rung answers "was it granted", not
 * "is it any good".
 */
public final class HeadlessWishProbe {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);
    private static final String P = ModdingFromAModMod.MODID + ".";
    private static final String TAG = "[headlesswish]";

    /** The fake child. A fixed UUID so a re-run reuses one profile rather than littering the world. */
    private static final UUID PROBE_ID = UUID.nameUUIDFromBytes("moddingfromamod:headless".getBytes(StandardCharsets.UTF_8));
    private static final String PROBE_NAME = "HeadlessWisher";

    /**
     * How long to let the agents connect before wishing. They are spawned from
     * {@code ServerStartedEvent} and have to boot a CLI, read their brief and reach {@code
     * await_wish}; measured at roughly 60-70 s from server start on a cloud VM. Wishing before that
     * is not fatal — the queue holds it — but it makes the log much harder to read.
     */
    private static final long SETTLE_MS = 20_000L;

    /**
     * <b>How many REAL children to wait for before wishing — 0, i.e. don't, unless asked.</b>
     *
     * <p>Gate C½ wishes into an empty world on purpose: the point there is the agent's behaviour,
     * and the {@link FakePlayer} below is the whole cast. A TOPOLOGY rig is the opposite case — its
     * claim is about who gets photographed and who gets frozen, so a wish that fires before the
     * child has finished joining measures a world with nobody in it and reports that as a pass.
     * Measured on the capture rig: a vanilla child joins about 22 s after the server says Done,
     * against a 20 s settle, so the race is real and it is nearly a coin toss.
     *
     * <p>Opt-in rather than "wait for whoever turns up", because a default that waits would hang
     * every existing headless run in a world that will never have a player. Set
     * {@code -Dmoddingfromamod.headlesswishcast=1} (or 2 with a camera) and the probe holds until
     * that many are on the player list.
     */
    private static int castWanted() {
        try {
            return Integer.parseInt(System.getProperty(P + "headlesswishcast", "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private HeadlessWishProbe() {
    }

    private enum Phase { SETTLE, SUBMIT, WATCH, STAGED, DONE }

    /**
     * a STAGED creature instead of a wish — a JSON list of {@code {"tool":…,"args":{…}}} run
     * through the real tools, as a worker would call them, with no agent at all. It is how the
     * behaviour oracle is proven on a creature whose behaviour is known (a plain one must pass
     * nothing; one with a known effect must pass exactly that) before it judges an agent's.
     */
    private static String stage() {
        return System.getProperty(P + "bxstage", "");
    }

    private static volatile String stageOutcome;

    private static Phase phase = Phase.SETTLE;
    private static long warmSinceMs;
    private static long startedMs;
    private static UUID wishId;
    private static String wishText;

    /** True when a wish was supplied. {@link ModdingFromAModMod} gates registration on this. */
    public static boolean active() {
        // The pool soak rides this registration so the mod's entry point is untouched.
        return !decodedWish().isEmpty() || !stage().isEmpty() || SoakProbe.active();
    }

    private static String decodedWish() {
        String raw = System.getProperty(P + "headlesswish", "");
        if (raw.isEmpty()) {
            return "";
        }
        try {
            return new String(Base64.getDecoder().decode(raw), StandardCharsets.UTF_8).trim();
        } catch (IllegalArgumentException e) {
            // LOUD. A wish that failed to decode would otherwise submit as empty and the run would
            // report "the agent never answered", blaming the agent for a bad flag.
            LOGGER.error("{} could not base64-decode -D{}headlesswish: {}", TAG, P, e.toString());
            return "";
        }
    }

    private static long timeoutMs() {
        try {
            return Long.parseLong(System.getProperty(P + "headlesswishtimeout", "900")) * 1000L;
        } catch (NumberFormatException e) {
            return 900_000L;
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (SoakProbe.active()) {
            SoakProbe.tick(event.getServer());
            return;
        }
        if (com.moddingfromamod.seam.ProbeExercise.get().running()) {
            com.moddingfromamod.seam.ProbeExercise.get().tick(event.getServer());
            return;
        }
        if (phase == Phase.DONE) {
            return;
        }
        MinecraftServer server = event.getServer();
        long now = System.currentTimeMillis();
        if (startedMs == 0L) {
            startedMs = now;
            wishText = decodedWish();
            // A machine is built where `Builder.fallbackAnchor` scatters it, which is up to 960
            // blocks from spawn — far outside the ring forced below. Telling the placer to force
            // the chunks it actually builds into is what lets this tier see a machine turn at all
            //. Harness-only: read once, statically, by ContraptionPlacer.
            System.setProperty("moddingfromamod.forceloadbuilds", "true");
            LOGGER.info("{} armed — will wish for: {}", TAG, wishText);
        }

        switch (phase) {
            case SETTLE -> {
                int wanted = castWanted();
                int here = server.getPlayerList().getPlayerCount();
                // The child it wishes AS must be IN before the wish, not merely asked for: a client on a
                // recording rig can join a minute or more after the server is up, and the submit's "nobody by that
                // name is online" RIG FAULT ended every ability film before it joined (2026-11-08).
                String as = System.getProperty(P + "headlesswishas", "");
                boolean asAway = !as.isEmpty() && server.getPlayerList().getPlayerByName(as) == null;
                // headlesswishgo=<file>: hold until a recorder says it is rolling. On a recording rig the
                // exercise began the moment the client joined and HALTED the server when it was done — before the
                // film had started, so every ability film was a RIG FAULT ("the client did not join").
                String go = System.getProperty(P + "headlesswishgo", "");
                boolean notRolling = !go.isEmpty() && !java.nio.file.Files.exists(java.nio.file.Path.of(go));
                if ((wanted > 0 && here < wanted) || asAway || notRolling) {
                    // Not "and also the settle" — the cast IS the settle, and a stricter one. A
                    // client that has finished joining has necessarily been up longer than the
                    // agents have.
                    if (now - startedMs > timeoutMs()) {
                        LOGGER.error("{} gave up waiting for {} player(s); {} online", TAG, wanted, here);
                        phase = Phase.SUBMIT;
                    }
                    return;
                }
                if (now - startedMs < SETTLE_MS) {
                    return;
                }
                // WARM, AS A LIVE SERVER IS: `headlesswishwarm=on` wakes the pool and holds the
                // submit until a worker is parked in await_wish. Without it the wish itself wakes a
                // cold pool and waits out the boot, which hides everything that happens between a
                // submit and a WAITING worker's take — the gap a live server's warm pool really has.
                if (Boolean.getBoolean(P + "headlesswishwarm")
                        || "on".equalsIgnoreCase(System.getProperty(P + "headlesswishwarm", ""))) {
                    AgentSession.wake("headless probe, warming before the wish");
                    if (WishQueue.global().waitingWorkers() < 1) {
                        if (now - startedMs > timeoutMs()) {
                            LOGGER.error("{} no worker reached await_wish; submitting cold", TAG);
                            phase = Phase.SUBMIT;
                        }
                        return;
                    }
                    if (warmSinceMs == 0L) {
                        warmSinceMs = now;
                        LOGGER.info("{} a worker is waiting at await_wish — submitting warm", TAG);
                    }
                    if (now - warmSinceMs < 3_000L) {
                        return;           // let it settle into its long-poll, as a live server's worker has
                    }
                }
                phase = Phase.SUBMIT;
            }
            case SUBMIT -> submit(server, now);
            case WATCH -> watch(server, now);
            case STAGED -> {
                if (stageOutcome != null) {
                    finish(server, stageOutcome.startsWith("OK"), "staged: " + stageOutcome);
                } else if (now - startedMs > timeoutMs()) {
                    finish(server, false, "the staged tool calls never finished");
                }
            }
            default -> { }
        }
    }

    /**
     * How far around the wish spot to keep ticking. Six chunks is 96 blocks, which is what
     * {@code TestPlots} uses for the same reason and comfortably covers a wish's build area — the
     * biggest machine in the catalogue is under a hundred blocks across.
     */
    private static final int FORCED_CHUNKS = 6;

    /**
     * <b>A sea where the stand-in is looking</b>: it faces south from spawn, and a miss lands
     * {@code WishAim.MIN_MISS} or more out along its gaze — so a sand-bottomed sea eight deep from 4 to
     * 44 blocks south, with open sky above it, puts the wish's spot on the water, exactly where the
     * ocean of a live world put five wishes on 2026-10-03.
     */
    private static void seaAhead(ServerLevel level, BlockPos spawn) {
        int surface = spawn.getY() - 1;
        for (int dx = -20; dx <= 20; dx++) {
            for (int dz = 4; dz <= 44; dz++) {
                BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
                for (int y = surface - 9; y <= surface + 24; y++) {
                    m.set(spawn.getX() + dx, y, spawn.getZ() + dz);
                    var state = y == surface - 9 ? net.minecraft.world.level.block.Blocks.SAND.defaultBlockState()
                            : y <= surface ? net.minecraft.world.level.block.Blocks.WATER.defaultBlockState()
                            : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
                    level.setBlock(m, state, net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
                }
            }
        }
        LOGGER.info("{} carved a sea south of spawn {} (surface y={})", TAG, spawn.toShortString(), surface);
    }

    private static void submit(MinecraftServer server, long now) {
        try {
            ServerLevel level = server.overworld();
            // ── WISH AS A REAL CHILD, WHEN THE RIG BROUGHT ONE (T6C) ────────────────────────
            //
            // With a cast in the world, a FakePlayer that is NOT in it is still the wisher, so a
            // wish ABOUT the child measures the harness: `effect give @p` finds nobody, and a pet
            // follows the stand-in. Measured on the first T6C calibration run: 4 of 13 calls
            // refused that way, and the agent fell back to a watch button, reported PASS. So a
            // rig that brings a child can name him, and the wish is his, as a typed one would be.
            String as = System.getProperty(P + "headlesswishas", "");
            ServerPlayer realChild = as.isEmpty() ? null : server.getPlayerList().getPlayerByName(as);
            if (as.isEmpty() && com.moddingfromamod.seam.ProbeExercise.get().enabled()) {
                // a stand-in child IN the world (a stock mock in the player list), so the
                // creature is spawned at him and he can later ride it and be hit by it.
                realChild = com.moddingfromamod.seam.ProbeExercise.get().standInChild(level, PROBE_ID, PROBE_NAME);
                BlockPos at = level.getRespawnData().pos();
                realChild.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
                realChild.setYRot(0f);
                as = PROBE_NAME;
            }
            if (!as.isEmpty() && realChild == null) {
                finish(server, false, "RIG FAULT: asked to wish as '" + as + "' and nobody by that "
                        + "name is online. Nothing about this run is an agent result.");
                return;
            }
            FakePlayer child = FakePlayerFactory.get(level, new GameProfile(PROBE_ID, PROBE_NAME));
            // Stand it at world spawn. A wish anchors where it was AIMED (see the FOCUS rule in
            // CLAUDE.md), so a fake player left at 0,0,0 in a world whose spawn is elsewhere would
            // have its build placed somewhere nobody would ever look.
            BlockPos spawn = level.getRespawnData().pos();
            child.setPos(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
            child.setServerLevel(level);
            // Let the tools that ACT for a child act for this one. Without it conjure_at
            // refused 7 of 7 and a creature wish was abandoned outright ("You left the world
            // before I could make your dragon!"). Photographs still go through a real client only
            // -- ActingChild.online never answers with the stand-in.
            if (realChild == null) {
                ActingChild.standIn(child);
            }

            // ── KEEP THE WISH AREA TICKING. Without this the tier measures nothing.
            //
            // A FakePlayer is not a real player and holds no chunk ticket, so nothing here keeps
            // chunks ticking. A Create machine in a chunk that does not tick reads present,
            // kinetic, speed 0 and NOT overstressed -- field for field identical to a machine whose
            // drive is broken -- so verify_contraption reported "STOPPED (no power reaches it)" on
            // machines that were fine, and this whole tier was blind to the main thing it claims to
            // measure.
            //
            // Measured on one melter with one variable: chunks forced -> turning=8 stopped=0;
            // chunks not forced -> turning=0 stopped=8.
            //
            // The radius covers a wish's build area rather than one chunk: a machine is anchored in
            // front of the child and a big one straddles several chunks, and a machine with its
            // anchor ticking and its motor frozen is the case a narrower ring would report on
            // confidently and get wrong.
            if ("sea".equalsIgnoreCase(System.getProperty(P + "headlesswishterrain", ""))) {
                seaAhead(level, spawn);
                // Looking south and a little down, so the gaze meets the water ~15 blocks out
                // rather than running on to whatever hill is past the sea.
                child.setYRot(0f);
                child.setXRot(10f);
            }
            ChunkPos centre = new ChunkPos(spawn);
            for (int dx = -FORCED_CHUNKS; dx <= FORCED_CHUNKS; dx++) {
                for (int dz = -FORCED_CHUNKS; dz <= FORCED_CHUNKS; dz++) {
                    level.setChunkForced(centre.x + dx, centre.z + dz, true);
                }
            }
            LOGGER.info("{} forced {} chunks around {} so machines actually tick", TAG,
                    (2 * FORCED_CHUNKS + 1) * (2 * FORCED_CHUNKS + 1), centre);

            if (!stage().isEmpty()) {
                // The staged calls are HIS, as a typed wish's are: a random player id here left
                // `wishmob spawn` with nobody to spawn it at ("A player is required"), so the
                // starter-trex-land control never got a creature.
                runStage(Path.of(stage()), System.getProperty(P + "headlesswishsig", ""),
                        (realChild != null ? realChild : child).getUUID());
                phase = Phase.STAGED;
                return;
            }
            // THE IDS THAT EXISTED BEFORE, so the one that appears is provably OURS —.
            //
            // This used to take the FIRST probe-owned wish out of `recent(10)`, under a comment
            // saying it followed "THIS one rather than whatever is newest". It did the opposite.
            // The probe player's id is the same every run, `recent` returns insertion order —
            // OLDEST first — and `findFirst` therefore picked the EARLIEST probe wish still in the
            // window: a previous run's, already settled, with a previous run's verdict on it.
            //
            // The first run of a fresh world is the one case where that is right (`book 0 -> 1`,
            // one candidate), which is why this survived: it works exactly once and then reports
            // yesterday's answer as today's. Measured — three consecutive runs returned a wish
            // about a club as PASS with a sword's closing message, byte-identical, in 20s, with
            // ZERO make_item calls in the log.
            java.util.Set<java.util.UUID> knownBefore = WishBook.get(server).recent(50).stream()
                    .map(Wish::id)
                    .collect(java.util.stream.Collectors.toCollection(java.util.HashSet::new));
            int before = WishBook.get(server).size();
            ServerPlayer wisher = realChild != null ? realChild : child;
            WishService.Outcome outcome;
            try (var origin = com.moddingfromamod.wish.WishOrigin.scope(
                    com.moddingfromamod.wish.WishOrigin.PROBE)) {   //
                outcome = WishService.submit(wisher, wishText, WishSource.COMMAND);
            }
            WishBook book = WishBook.get(server);
            LOGGER.info("{} submitted (outcome={}, book {} -> {})", TAG, outcome, before, book.size());

            wishId = ourNewWish(book.recent(50), knownBefore, wisher.getUUID());

            if (outcome == WishService.Outcome.EMPTY) {
                finish(server, false, "the wish was rejected as EMPTY — check the base64 flag");
                return;
            }
            if (outcome == WishService.Outcome.BLOCKED) {
                // NAMED, rather than falling through to "no wish was recorded" below. A blocked
                // wish never reaches WishBook, so the generic branch would be reached and would
                // report a missing book entry -- true, and the wrong cause, which is the shape
                // this probe exists to avoid. FAIL, because the probe's question is "did the agent
                // grant it" and nothing was granted: a CS-S2 gate that WANTS a block reads this
                // line and gets its answer, rather than waiting out the timeout.
                finish(server, false, "the wish was BLOCKED by the child-safety floor before it "
                        + "reached the agent (docs/CHILD_SAFETY_PLAN.md §3 L0) — the child was "
                        + "told and nothing was built");
                return;
            }
            if (wishId == null) {
                finish(server, false, "submit returned " + outcome + " but no wish was recorded "
                        + "against the probe player");
                return;
            }
            phase = Phase.WATCH;
        } catch (RuntimeException e) {
            finish(server, false, "submitting threw: " + e);
        }
    }

    /** The staged calls, on a worker thread bound as an agent is (tools marshal to the server). */
    private static void runStage(Path file, String sig, UUID player) {
        wishText = "staged: " + file.getFileName();
        Thread t = new Thread(() -> {
            UUID wish = UUID.randomUUID();
            String worker = "bxstage-" + wish;
            Marshal.global().submit(wish, player, wishText);
            Marshal.global().assign(worker, wish);
            AgentContext.bind(worker);
            StringBuilder log = new StringBuilder();
            String outcome = "OK";
            try {
                ToolRegistry tools = WishAgent.buildTools();
                for (var el : com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonArray()) {
                    var call = el.getAsJsonObject();
                    String name = call.get("tool").getAsString();
                    McpTool tool = tools.byName(name);
                    if (tool == null) {
                        outcome = "no tool " + name;
                        break;
                    }
                    ToolResult r = tool.call(call.getAsJsonObject("args").deepCopy());
                    log.append(name).append(r.isError() ? " ERROR " : " ok ").append(r.text()).append("\n\n");
                    LOGGER.info("{} staged {} -> {}{}", TAG, name, r.isError() ? "ERROR " : "",
                            r.text().length() > 200 ? r.text().substring(0, 200) + "…" : r.text());
                    if (r.isError()) {
                        outcome = "ERROR in " + name;
                        break;
                    }
                }
            } catch (Exception e) {
                outcome = "threw: " + e;
            } finally {
                AgentContext.unbind();
                Marshal.global().finish(wish);
            }
            if (!sig.isEmpty()) {
                try {
                    Files.createDirectories(Path.of(sig));
                    Files.writeString(Path.of(sig, "stage.log"), log.toString());
                } catch (Exception ignored) {
                    // the verdict line still says how it went
                }
            }
            stageOutcome = outcome;
        }, "bxstage");
        t.setDaemon(true);
        t.start();
    }

    /**
     * The wish this probe just submitted: probe-owned, and NOT one that already existed.
     *
     * <p>Pure and extracted so the rule can be tested without a server, because the version it
     * replaces was wrong in a way no green run could show. It took the FIRST probe-owned
     * wish out of the book, under a comment claiming it followed "THIS one rather than whatever is
     * newest" — but the probe player's id is the same every run and the book returns insertion
     * order, oldest first, so from the second run onward it picked a PREVIOUS run's wish, already
     * settled, and reported that run's verdict.
     *
     * <p>It is right exactly once — on the first run of a fresh world, where there is only one
     * candidate — which is why it survived, and why the one genuine measurement taken with it was
     * the first one anybody made.
     *
     * @param after       the book after submitting, oldest first
     * @param knownBefore every id that existed before, so "new" is a fact rather than a guess
     * @return the id, or null if nothing new arrived — which is a failed submit, not a slow one
     */
    static java.util.UUID ourNewWish(java.util.List<Wish> after,
                                     java.util.Set<java.util.UUID> knownBefore,
                                     java.util.UUID probeId) {
        return after.stream()
                .filter(w -> probeId.equals(w.playerId()))
                .map(Wish::id)
                .filter(id -> !knownBefore.contains(id))
                // The newest, if a submit somehow produced two. Never the oldest: that is the bug.
                .reduce((first, second) -> second)
                .orElse(null);
    }

    private static void watch(MinecraftServer server, long now) {
        Optional<Wish> found = WishBook.get(server).byId(wishId);
        if (found.isEmpty()) {
            finish(server, false, "the wish vanished from the book");
            return;
        }
        Wish w = found.get();
        // A BACKSTOP THAT SHOULD BE UNREACHABLE, which is what makes it worth having. With the id
        // fix above the watched wish is by construction the one we submitted; if that ever stops
        // being true, the failure is a confident PASS describing somebody else's wish, and this is
        // the one assertion that can tell the difference. It costs a string comparison.
        if (!wishText.equals(w.text())) {
            finish(server, false, "the probe was watching a DIFFERENT wish than it submitted — "
                    + "asked for \"" + wishText + "\" and found \"" + w.text() + "\". Nothing "
                    + "about this run is evidence; see #280.");
            return;
        }
        WishStatus status = w.status();
        if (status == WishStatus.PENDING || status == WishStatus.RUNNING) {
            if (now - startedMs > timeoutMs()) {
                finish(server, false, "timed out after " + ((now - startedMs) / 1000)
                        + "s still " + status + " — the agent never finished. "
                        + "Was -D" + P + "agent=on set?");
            }
            return;
        }
        // Settled. DONE is the pass; everything else is a real, reportable outcome.
        boolean ok = status == WishStatus.DONE;
        finish(server, ok, "status=" + status + " after " + ((now - startedMs) / 1000) + "s"
                + (w.result() == null || w.result().isBlank() ? "" : " — " + w.result()));
    }

    private static void finish(MinecraftServer server, boolean pass, String detail) {
        phase = Phase.DONE;
        String verdict = (pass ? "PASS" : "FAIL") + " — " + detail;
        LOGGER.info("{} {}", TAG, verdict);
        System.out.println(TAG + ": RESULT=" + verdict);

        String sig = System.getProperty(P + "headlesswishsig", "");
        if (!sig.isEmpty()) {
            try {
                Path dir = Path.of(sig);
                Files.createDirectories(dir);
                // ── SAY HOW MUCH OF THIS RUN WAS THE HARNESS ─────────────────────────────
                //
                // This tier has NOBODY ONLINE for its whole life -- the submitter is a FakePlayer,
                // which is not in the player list -- so every tool that needs a player refuses,
                // and each refusal cost the agent a turn it could never have spent well.
                //
                // Measured over the first eight real wishes: conjure_at opened 7 of 8 and failed
                // 7 of 7, i.e. 6-14% of the calls in a C½ wish. Read as agent behaviour that is a
                // headline 's "a wish takes ~2x the turns it needs"; it is the harness, and
                // it was nearly written up as a defect twice in one session.
                //
                // Printed here rather than left in docs/TESTING.md because a caveat you must
                // remember is not a control -- the same lesson the datapack-layout check learned.
                int noPlayer = com.moddingfromamod.agent.ToolResult.noPlayerRefusals();
                String harness = noPlayer == 0 ? ""
                        : "harness: " + noPlayer + " tool call(s) failed only because no player was "
                                + "online (a FakePlayer is not in the player list). Those turns are "
                                + "this tier's cost, not the agent's — see docs/TESTING.md → Gate C½."
                                + System.lineSeparator();
                Files.writeString(dir.resolve("result"),
                        verdict + System.lineSeparator()
                                + "wish: " + wishText + System.lineSeparator()
                                + harness);
            } catch (Exception e) {
                LOGGER.error("{} could not write the verdict to {}: {}", TAG, sig, e.toString());
            }
        }
        // a granted wish is put through its paces before the server stops — what the
        // creature DID, written beside the verdict as behaviour.json. Only after a DONE: a wish
        // that failed has nothing to exercise, and the verdict above already says why.
        if (pass && !sig.isEmpty() && com.moddingfromamod.seam.ProbeExercise.get().enabled()) {
            com.moddingfromamod.seam.ProbeExercise.get().begin(server, Path.of(sig), wishText, PROBE_ID,
                    PROBE_NAME, () -> server.halt(false));
            return;
        }
        // headlesswishstay=on: the verdict is written and the server STAYS UP, so a client can look at what was
        // made (a second client filming a staged creature turning, — halting here ended the server before the
        // client had joined).
        if ("on".equalsIgnoreCase(System.getProperty(P + "headlesswishstay", ""))) {
            LOGGER.info("{} done — staying up (headlesswishstay=on)", TAG);
            return;
        }
        // Stop, so a driver does not have to guess when it is over. halt(false) leaves the world
        // saved, which is what lets the client job below fly to what this built.
        server.halt(false);
    }
}
