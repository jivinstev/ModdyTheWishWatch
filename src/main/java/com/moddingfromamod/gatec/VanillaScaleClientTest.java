package com.moddingfromamod.gatec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.runtime.BedrockAnimation;
import com.moddingfromamod.runtime.CreatureAnatomy;
import com.moddingfromamod.runtime.CreatureFiles;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.ModelRepository;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.vanilla.AtomLibrary;
import com.moddingfromamod.vanilla.DisplayRig;
import com.moddingfromamod.vanilla.RigAnimator;
import com.moddingfromamod.vanilla.RigDriver;
import com.moddingfromamod.vanilla.RigGeometry;
import com.moddingfromamod.vanilla.RigCarrier;
import com.moddingfromamod.vanilla.RigPlan;
import com.moddingfromamod.vanilla.RigPose;
import com.moddingfromamod.vanilla.RigTransform;
import com.moddingfromamod.vanilla.SpikeFixture;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * <b>Phase 4 — how many creatures before it lags</b> (the atom rig's design notes).
 *
 * <p>a tester's question, and the one that decides whether the atom rig ships as the default.
 * N creatures walking in view of one client, N ∈ {1, 4, 8, 16, 32}, at two subjects, sampled for
 * ten seconds each. <b>One arm since an earlier change</b>: the pack-rig arms (exact and grouped) this was
 * measured against are deleted, so a run now reports the atom rig alone; the historical A/B ratios
 * live in the atom rig's design notes
 *
 * <h2>Three things about the shape of this measurement</h2>
 *
 * <p><b>The comparison WAS atoms against EXACT, not against grouped</b> (§5.7.4: the grouped arm
 * wrote three displays because it could not move Spikor's legs at all, so it was not a fair
 * control). Both pack arms went with an earlier change.
 *
 * <p><b>Every N runs in ONE client session.</b> A client boot is a minute and a sample is ten
 * seconds, so a run per N would be mostly boot — and worse, it would put each N in its own JVM,
 * where a difference between two N values could be a difference between two JIT warm-ups. Stepping
 * N inside one session holds everything but the creature count fixed.
 *
 * <p><b>The camera does not move with N.</b> Thirty-two creatures at the standoff that frames one
 * would be off-screen, and a creature that is not drawn does not cost what a drawn one costs. So
 * the camera is placed once, far enough back to hold the largest grid, and every N is measured
 * from it. That makes N=1 a small subject in a big frame, which is honest: the number being
 * compared was between ARMS at one N, never between two N values on one arm.
 *
 * <h2>What is sampled, and the one that is not a measurement of this repo</h2>
 *
 * <p>Client frame rate is {@code Minecraft.getFps()} — the client's own counter, a one-second
 * average, read once a second over the window and reported as a median. Server tick time is
 * {@code MinecraftServer.getAverageTickTimeNanos()}. Traffic is the animator's own write count
 * times the 53 bytes §5.7.4's cost model uses, which is the same arithmetic that section printed
 * from a test rather than from prose after getting it wrong by a factor of twenty by hand.
 *
 * <p>⚠ <b>The absolute frame rate is llvmpipe's, not the atom rig's.</b> This runs on a 4-vCPU
 * cloud VM with no GPU and a software rasteriser; a real client is a different machine entirely.
 * With one arm left, a finding needs a second run to ratio against — same container, same world
 * seed, same camera — never an absolute fps quoted on its own.
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class VanillaScaleClientTest {

    private VanillaScaleClientTest() {
    }

    private static final String P = ModdingFromAModMod.MODID + ".";

    private static final boolean ENABLED =
            "vanillascale".equalsIgnoreCase(System.getProperty(P + "testmode", ""));

    private static final Path SIG = Path.of(
            System.getProperty(P + "sigdir", "/tmp/moddingfromamod-vanillascale"));

    private static final String CASE = System.getProperty(P + "cases", "thornshell");

    /**
     * The creature counts to step through.
     *
     * <p>⚠ <b>A COMMA cannot reach here through {@code -Ptestprops}</b>, which splits its own value
     * on commas to make the key=value pairs — so {@code counts=2,1,4} arrives as {@code counts=2}
     * plus four garbage keys, and the run measures ONE step and reports PASS.
     * {@code build.gradle:119} records the same trap for {@code smokejars} and solves it with a
     * property of its own. Here the separator is simply widened: {@code +} and {@code ;} both work
     * and a comma still works from a plain {@code -D}.
     */
    private static final int[] COUNTS = parseCounts(
            System.getProperty(P + "counts", "1+4+8+16+32"));

    /** Seconds of sampling per N, after a warm-up. Ten is the plan's own figure. */
    private static final int SAMPLE_SECONDS =
            Integer.parseInt(System.getProperty(P + "samplesecs", "10"));

    /** Ticks of walking before sampling starts, so a spawn's own cost is not in the window. */
    private static final int WARM_TICKS =
            Integer.parseInt(System.getProperty(P + "warmticks", "60"));

    private static final int INTERVAL =
            Integer.parseInt(System.getProperty(P + "interval",
                    String.valueOf(RigAnimator.DEFAULT_INTERVAL)));

    private static final double SPEED =
            Double.parseDouble(System.getProperty(P + "walkspeed", "0.09"));

    private static final double LEG = Double.parseDouble(System.getProperty(P + "walkleg", "3"));

    private static int[] parseCounts(String s) {
        String[] bits = s.split("[,+;]");
        int[] out = new int[bits.length];
        for (int i = 0; i < bits.length; i++) {
            out[i] = Integer.parseInt(bits[i].trim());
        }
        return out;
    }

    private enum Phase { WAIT_TITLE, WAIT_INGAME, PREPARE, RELOAD, SPAWN, WARM, SAMPLE, CLEAR, DONE }

    private static Phase phase = Phase.WAIT_TITLE;
    private static int ticks;
    private static boolean fired;
    /**
     * ⚠ <b>Concurrent for the same reason {@code RIGS} is.</b> {@code spawn} and {@code drive} add
     * notes from inside {@code server.execute} while {@code finish} joins the list on the CLIENT
     * thread, so a plain {@code ArrayList} throws {@code ConcurrentModificationException} — and it
     * throws it from the reporting path, so the run fails with a stack trace instead of the table
     * it spent four minutes measuring.
     */
    private static final List<String> NOTES = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final List<String> ROWS = new ArrayList<>();
    private static volatile boolean reloadDone;

    private static final double CAM_X = 8.5;
    private static final double CAM_Y = 98.0;
    private static final double CAM_Z = 8.5;
    private static double footY = 95.0;

    /**
     * How far back the camera stands. Wide enough that a 6x6 grid of creatures is on screen, so
     * every N is drawn rather than culled — a creature the frustum has thrown away costs nothing
     * and would make the largest N read as the cheapest.
     */
    private static final double STANDOFF =
            Double.parseDouble(System.getProperty(P + "standoff", "34.0"));

    /**
     * <b>Treat the FIRST step as a warm-up and label it as discarded.</b> Default on.
     *
     * <p>Measured on the first run of this harness: with {@code counts=1,4,8,16,32} the N=1 step
     * reported <b>37 fps median, 14 minimum and an 18.96 ms server tick</b> while N=4 — four times
     * the work — reported <b>51 / 35 / 7.71</b>. A cost curve cannot go that way round, so the
     * first row was not measuring one creature, it was measuring the session's own JIT and chunk
     * warm-up landing inside the first window. Running a throwaway step first and SAYING SO in the
     * row is the fix; remembering to ignore the first line is not one.
     */
    private static final boolean DISCARD_FIRST =
            !"off".equalsIgnoreCase(System.getProperty(P + "discardfirst", "on"));

    /** Which N to write a film window for. The plan asks for a clip of sixteen. */
    private static final int FILM_N = Integer.parseInt(System.getProperty(P + "filmn", "16"));

    private static long filmStart;

    /** The recorder's contract — see tools/record-client.sh and the filming notes. */
    private static void writeFilmWindow(String label) {
        if (filmStart == 0L) {
            return;
        }
        try {
            java.nio.file.Path windows = SIG.resolve("film-windows.tsv");
            java.nio.file.Files.createDirectories(windows.getParent());
            java.nio.file.Files.writeString(windows,
                    filmStart + "\t" + System.currentTimeMillis() + "\t" + label + "\n",
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            NOTES.add("  film window '" + label + "' " + filmStart + ".."
                    + System.currentTimeMillis());
        } catch (Exception ignored) {
            // no recorder attached; every number still stands
        }
        filmStart = 0L;
    }

    /** Blocks between creatures in the grid. */
    private static final double PITCH = Double.parseDouble(System.getProperty(P + "pitch", "4.0"));

    private static String key;
    private static List<CreatureSculpt.Part> mirrored;
    private static Map<String, BedrockAnimation> clips;
    private static int boxCount;
    private static int displaysPerCreature;

    /**
     * ⚠ <b>Concurrent, because two threads touch it.</b> Rigs are appended inside
     * {@code server.execute} and iterated there, while {@code clear()} copies and empties the list
     * on the CLIENT thread. A plain {@code ArrayList} threw {@code ConcurrentModificationException}
     * on the six-step run and survived the five-step one — i.e. it is a race that a shorter run
     * hides, which is the worst kind to leave in a measurement harness.
     */
    private static final List<DisplayRig> RIGS = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static int countIndex;
    private static double travelled;
    private static int direction = 1;

    // ── the sample window ────────────────────────────────────────────────────────────────────
    private static long rigWrites;
    private static final List<Integer> FPS = new ArrayList<>();
    /**
     * ⚠ <b>Concurrent — this one is the SAMPLE, so losing it loses the measurement.</b> The server
     * tick time is read inside {@code server.execute}, on the server thread, and {@code record}
     * averages the list on the client thread while {@code resetWindow} clears it there. Third of
     * three lists in this file that two threads touch; the first two were found by a run failing
     * and this one by asking which of the remaining fields was written from a queued task.
     */
    private static final List<Long> TICKNS = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static int lastFpsSecond = -1;
    private static int peakClientEntities;
    private static int peakRenderedEntities;
    private static int peakServerEntities;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!ENABLED || phase == Phase.DONE) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ticks++;
        try {
            switch (phase) {
                case WAIT_TITLE -> {
                    if (ticks > ClientHarness.TITLE_SETTLE) {
                        go(Phase.WAIT_INGAME);
                    }
                }
                case WAIT_INGAME -> {
                    if (ClientHarness.bootstrapWorld(mc, "vanilla-scale", ticks, true)) {
                        go(Phase.PREPARE);
                    }
                }
                case PREPARE -> {
                    if (ClientHarness.inWorld(mc) && ticks > ClientHarness.INGAME_SETTLE) {
                        prepare(mc);
                    }
                }
                case RELOAD -> {
                    if (reloadDone && ticks > 20) {
                        go(Phase.SPAWN);
                    }
                }
                case SPAWN -> spawn(mc);
                case WARM -> {
                    hold(mc);
                    drive(mc, false);
                    if (ticks > WARM_TICKS) {
                        resetWindow();
                        go(Phase.SAMPLE);
                    }
                }
                case SAMPLE -> {
                    hold(mc);
                    drive(mc, true);
                    // Film the step the plan asks for a clip of, so a human can SEE the lag rather
                    // than read it. The window is the sample window itself, which is also the only
                    // stretch where the scene is what the numbers describe.
                    if (ticks == 1 && COUNTS[countIndex] == FILM_N) {
                        filmStart = System.currentTimeMillis();
                    }
                    if (ticks > SAMPLE_SECONDS * 20) {
                        if (filmStart != 0L) {
                            writeFilmWindow("N" + COUNTS[countIndex]);
                        }
                        record(mc);
                        go(Phase.CLEAR);
                    }
                }
                case CLEAR -> clear(mc);
                default -> { }
            }
        } catch (Exception e) {
            finish(false, "threw " + e);
        }
    }

    // ------------------------------------------------------------------------------- the setup

    private static void prepare(Minecraft mc) {
        if (fired) {
            return;
        }
        fired = true;
        NOTES.add("arm = ATOMS (one display per box, " + AtomLibrary.modelName() + ")"
                + "; " + AtomLibrary.describePatterns());
        NOTES.add("subject = " + CASE + "; counts = " + java.util.Arrays.toString(COUNTS)
                + "; sample = " + SAMPLE_SECONDS + "s each; standoff = " + STANDOFF
                + "; pitch = " + PITCH);
        key = "vscale_" + RigCarrier.safe(CASE);
        List<CreatureSculpt.Part> parts = SpikeFixture.parts(CASE);
        boxCount = parts.size();
        mirrored = com.moddingfromamod.vanilla.RigFit.intoRigSpace(parts);
        try {
            CreatureFiles.write(key, parts, "{}");
            clips = ModelRepository.animations(key);
            // (the pack-rig arms' per-creature pack write was deleted by an earlier change)
            displaysPerCreature = RigPlan.atoms(mirrored).size();
            NOTES.add(boxCount + " boxes | displays per creature = " + displaysPerCreature);
            NOTES.add("clips: " + clips.keySet() + "; animated bones "
                    + RigPose.animatedBones(clips));
        } catch (Exception e) {
            finish(false, "creature write failed: " + e);
            return;
        }
        // §5.8.3: a selected per-creature rig pack REPLACES the carrier's override table, so every
        // atom display resolves the pack's last creature group and the whole measurement is of the
        // wrong models. Nothing delivers such a pack any more; a stale one must not be selected.
        NOTES.add(ClientHarness.excludePack(mc, "file/mfam-vanilla-rig"));
        mc.reloadResourcePacks().thenRun(() -> reloadDone = true);
        go(Phase.RELOAD);
    }

    private static void spawn(Minecraft mc) {
        if (fired) {
            return;
        }
        fired = true;
        var server = mc.getSingleplayerServer();
        if (server == null) {
            finish(false, "no integrated server");
            return;
        }
        final int n = COUNTS[countIndex];
        List<CreatureSculpt.Part> parts = SpikeFixture.parts(CASE);
        double scale = SpikeFixture.scale(CASE);
        server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
            if (p == null) {
                return;
            }
            ServerLevel level = p.level();
            SpeciesRegistry.put(server, Species.fromJson(JsonParser.parseString(
                    "{\"id\":\"" + key + "\",\"modelKey\":\"" + key + "\",\"health\":40,"
                            + "\"speed\":0.0,\"scale\":" + scale + "}").getAsJsonObject()));
            var extents = CreatureAnatomy.extents(RigGeometry.of(parts));
            int cols = (int) Math.ceil(Math.sqrt(n));
            for (int i = 0; i < n; i++) {
                double x = CAM_X + ((i % cols) - (cols - 1) / 2.0) * PITCH;
                double z = CAM_Z + STANDOFF + (i / cols) * PITCH;
                DisplayRig r = DisplayRig.spawn(level, new Vec3(x, footY, z), 270f, parts, scale,
                        CreatureAnatomy.widthBlocks(extents) * scale,
                        CreatureAnatomy.heightBlocks(extents) * scale, null);
                r.base().setNoGravity(true);
                stopAiMoving(r.base());
                for (var d : r.all()) {
                    d.setNoGravity(true);
                }
                RIGS.add(r);
            }
            NOTES.add("N=" + n + ": spawned " + RIGS.size() + " rigs x "
                    + (RIGS.isEmpty() ? 0 : RIGS.get(0).displays().size()) + " displays = "
                    + RIGS.stream().mapToInt(r -> r.displays().size()).sum() + " item_display");
        });
        go(Phase.WARM);
    }

    private static void stopAiMoving(net.minecraft.world.entity.Mob mob) {
        var speed = mob.getAttribute(
                net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.setBaseValue(0.0);
        }
        mob.setTarget(null);
        mob.goalSelector.removeAllGoals(g -> true);
        mob.targetSelector.removeAllGoals(g -> true);
    }

    // -------------------------------------------------------------------------- the measurement

    private static void resetWindow() {
        rigWrites = 0;
        FPS.clear();
        TICKNS.clear();
        lastFpsSecond = -1;
        peakClientEntities = 0;
        peakRenderedEntities = 0;
        peakServerEntities = 0;
    }

    /** Walk every rig, and — inside the window — sample. */
    private static void drive(Minecraft mc, boolean sampling) {
        var server = mc.getSingleplayerServer();
        if (server == null || RIGS.isEmpty()) {
            return;
        }
        server.execute(() -> {
            ServerLevel level = server.getAllLevels().iterator().next();
            travelled += SPEED;
            if (travelled > LEG) {
                travelled = 0;
                direction = -direction;
            }
            // Yaw 90 faces -X and 270 faces +X (VanillaWalkClientTest's moon-walk note), so the
            // facing has to follow the sign of travel or every creature walks backwards -- which
            // would still animate, still write, and still measure a plausible number.
            float yaw = direction > 0 ? 270f : 90f;
            long writes = 0;
            for (DisplayRig r : RIGS) {
                var base = r.base();
                base.setYRot(yaw);
                base.setYBodyRot(yaw);
                base.setYHeadRot(yaw);
                base.setDeltaMovement(SPEED * direction, 0, 0);
                RigDriver.follow(r, base.position(), yaw);
                writes += RigAnimator.tick(r, mirrored, clips, level.getGameTime(), INTERVAL);
            }
            if (sampling) {
                rigWrites += writes;
                TICKNS.add(server.getAverageTickTimeNanos());
                peakServerEntities = Math.max(peakServerEntities,
                        (int) java.util.stream.StreamSupport
                                .stream(level.getAllEntities().spliterator(), false).count());
            }
        });
        if (sampling) {
            // getFps() is a one-second average, so sampling it every tick would just repeat each
            // value twenty times and make the median look far more precise than it is. One sample
            // per second, keyed on the window's own tick count.
            int second = ticks / 20;
            if (second != lastFpsSecond) {
                lastFpsSecond = second;
                FPS.add(mc.getFps());
            }
            if (mc.level != null) {
                peakClientEntities = Math.max(peakClientEntities, mc.level.getEntityCount());
            }
            peakRenderedEntities = Math.max(peakRenderedEntities, renderedEntities(mc));
        }
    }

    /**
     * How many entities the client's own renderer says it drew.
     *
     * <p>Parsed out of {@code LevelRenderer.getEntityStatistics()} — the F3 line — because the
     * field behind it is private and this is a measurement rather than a behaviour. Its shape is
     * {@code "E: 12/34, B: 0, SD: 8"}: drawn over total.
     */
    private static int renderedEntities(Minecraft mc) {
        try {
            String s = mc.levelRenderer.getEntityStatistics();
            int e = s.indexOf("E: ");
            int slash = s.indexOf('/', e);
            if (e < 0 || slash < 0) {
                return 0;
            }
            return Integer.parseInt(s.substring(e + 3, slash).trim());
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static void record(Minecraft mc) {
        int n = COUNTS[countIndex];
        int fpsMed = median(FPS);
        int fpsMin = FPS.isEmpty() ? 0 : FPS.stream().mapToInt(Integer::intValue).min().orElse(0);
        double tickMs = TICKNS.isEmpty() ? 0
                : TICKNS.stream().mapToLong(Long::longValue).average().orElse(0) / 1.0e6;
        // §5.7.4's cost model: ~53 bytes per moved display, and the window is a known length, so
        // this is bytes on the wire per second rather than a write count nobody can compare.
        double bytesPerSec = rigWrites * 53.0 / SAMPLE_SECONDS;
        boolean warmup = DISCARD_FIRST && countIndex == 0;
        String row = String.format(
                "  N=%-3d fps med %-4d min %-4d | server tick %6.2f ms | writes %-7d "
                        + "= %8.0f B/s | client entities %-5d rendered %-5d | server entities %d%s",
                n, fpsMed, fpsMin, tickMs, rigWrites, bytesPerSec,
                peakClientEntities, peakRenderedEntities, peakServerEntities,
                warmup ? "   <-- WARM-UP, DISCARDED (see discardfirst)" : "");
        ROWS.add(row);
        NOTES.add(row);
        NOTES.add("      fps samples " + FPS);
    }

    private static int median(List<Integer> v) {
        if (v.isEmpty()) {
            return 0;
        }
        List<Integer> s = new ArrayList<>(v);
        java.util.Collections.sort(s);
        return s.get(s.size() / 2);
    }

    private static void clear(Minecraft mc) {
        if (fired) {
            return;
        }
        fired = true;
        var server = mc.getSingleplayerServer();
        if (server == null) {
            finish(false, "no integrated server");
            return;
        }
        final List<DisplayRig> doomed = new ArrayList<>(RIGS);
        RIGS.clear();
        server.execute(() -> {
            for (DisplayRig r : doomed) {
                for (var d : r.all()) {
                    d.discard();
                }
                r.base().discard();
            }
        });
        countIndex++;
        if (countIndex >= COUNTS.length) {
            report();
            return;
        }
        go(Phase.SPAWN);
    }

    private static void report() {
        NOTES.add("");
        NOTES.add("PHASE 4 TABLE — " + CASE + " on the "
                + "ATOM rig, "
                + boxCount + " boxes -> " + displaysPerCreature + " displays per creature");
        NOTES.add("  llvmpipe, 4 vCPU, no GPU: the absolute fps is the VM's, the RATIO is the "
                + "finding");
        NOTES.addAll(ROWS);
        finish(true, "");
    }

    private static void hold(Minecraft mc) {
        if (mc.player == null) {
            return;
        }
        mc.options.hideGui = true;
        mc.setScreen(null);
        mc.player.setPos(CAM_X, CAM_Y, CAM_Z);
        mc.player.setYRot(0f);
        mc.player.setXRot(4f);
        mc.player.xOld = CAM_X;
        mc.player.yOld = CAM_Y;
        mc.player.zOld = CAM_Z;
        mc.player.yRotO = 0f;
        mc.player.xRotO = 4f;
    }

    private static void go(Phase next) {
        phase = next;
        ticks = 0;
        fired = false;
    }

    private static void finish(boolean pass, String message) {
        ClientHarness.verdict(SIG, pass,
                (pass ? "PASS" : "FAIL — " + message) + "\n" + String.join("\n", NOTES));
        phase = Phase.DONE;
        Minecraft.getInstance().stop();
    }
}
