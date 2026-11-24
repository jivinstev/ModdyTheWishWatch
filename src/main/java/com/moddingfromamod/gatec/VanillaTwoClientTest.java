package com.moddingfromamod.gatec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.runtime.CreatureAnatomy;
import com.moddingfromamod.runtime.CreatureFiles;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.vanilla.DisplayRig;
import com.moddingfromamod.vanilla.RigGeometry;
import com.moddingfromamod.vanilla.RigCarrier;
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
 * <b>Gate C for D5 — TWO creatures standing in one world at once.</b>
 *
 * <p>Every other run in this spike has photographed one creature. A child has several, and the pack
 * that carries them is <b>one directory with one global override table</b>: {@code custom_model_data}
 * is a number on a shared carrier item and the carrier's model file maps numbers to models for the
 * whole game. There is no per-creature pack — 1.21.1 has no {@code item_model} component (D3).
 *
 * <h2>The oracle is ALPHA'S OWN PICTURE, before and after beta exists</h2>
 *
 * <p>The obvious test — "are there two creatures on screen" — is satisfied by both of the bugs this
 * run exists to catch, which is §2.2's rule again: a control true of the bug is not a control.
 *
 * <ul>
 *   <li>If the writer WIPES (as it did), beta's write deletes alpha's model files. Alpha's displays
 *       are still standing and still asking for their numbers, so alpha becomes a cluster of
 *       <b>magenta missing-model cubes</b> — creature-shaped, creature-sized, two things on screen.</li>
 *   <li>If the numbering COLLIDES (as it did — every creature started at 1), alpha's displays
 *       resolve to <b>beta's models</b>. Two things on screen, both of them beta.</li>
 * </ul>
 *
 * <p>So the run photographs alpha ALONE first, then writes beta into the same pack, reloads, and
 * photographs the same lane again. <b>Alpha's pixels must not have moved.</b> That single comparison
 * fails loudly on both bugs and cannot be satisfied by anything else being drawn.
 *
 * <p>Two supporting numbers, each closing a way the headline could pass while the feature is broken:
 * beta must draw <em>something</em> (a pack that silently dropped it would leave alpha untouched and
 * the headline green), and the two silhouettes must <em>differ</em> (if both lanes render the same
 * models, alpha is unchanged and beta is present, and the pack is still wrong).
 *
 * <p>⚠ <b>Since deleted the pack rig, the two bugs above cannot happen</b>: the atom rig has
 * no per-creature pack, no writer and no slots — every creature wears the same constant cube,
 * tinted per box. The oracle is kept because it is still a true statement about the atom rig (a
 * second creature must not change the first one's picture — shared tint or shared display state
 * would), but the failure modes it was DESIGNED for are gone, so a green run here is weaker
 * evidence than this header's history suggests.
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class VanillaTwoClientTest {

    private VanillaTwoClientTest() {
    }

    private static final String P = ModdingFromAModMod.MODID + ".";

    private static final boolean ENABLED =
            "vanillatwo".equalsIgnoreCase(System.getProperty(P + "testmode", ""));

    private static final Path SIG = Path.of(
            System.getProperty(P + "sigdir", "/tmp/moddingfromamod-vanillatwo"));

    /** Two REAL creatures, deliberately different shapes — see {@code differ} below. */
    private static final String CASE_A = System.getProperty(P + "caseA", "thornshell");
    private static final String CASE_B = System.getProperty(P + "caseB", "legfish-b");

    private enum Phase { WAIT_TITLE, WAIT_INGAME, EMPTY, PREPARE_A, RELOAD_A, SPAWN_A, SETTLE_A,
                         SHOOT_A, PREPARE_B, SPAWN_B, SETTLE_B, SHOOT_B, DONE }

    private static Phase phase = Phase.WAIT_TITLE;
    private static int ticks;
    private static boolean fired;
    private static boolean reloadDone;
    private static boolean groundLaid;
    private static String failLater;

    private static String keyA;
    private static String keyB;
    private static DisplayRig rigA;
    private static DisplayRig rigB;

    private static final List<String> NOTES = new ArrayList<>();
    private static final Map<String, String> RESULTS = new LinkedHashMap<>();

    // The same stage as the state and death runs, so the pictures are comparable.
    private static final double CAM_X = 8.5;
    private static final double CAM_Y = 100.0;
    private static final double CAM_Z = 8.5;
    private static final float CAM_PITCH = 12f;
    private static final double STAGE_Z =
            CAM_Z + Double.parseDouble(System.getProperty(P + "standoff", "17.0"));
    private static final double FOOT_Y = 95.0;
    private static final int GROUND_Y = (int) FOOT_Y - 1;
    private static final double LANE = Double.parseDouble(System.getProperty(P + "lane", "9.0"));
    /**
     * 🔴 <b>+X is screen LEFT at yaw 0, and getting it backwards costs a whole run.</b>
     *
     * <p>The camera looks along +Z, so the world's +X axis runs to the viewer's left. Alpha is
     * measured in the left half of the frame, so alpha stands at {@code CAM_X + LANE}. The first
     * cut had these the other way round and the run failed with <em>"alpha drew nothing at all"</em>
     * — which reads as a broken pack, a missing model or a rig that never spawned, and is none of
     * them: the creature was rendering perfectly, 63x94 px of it, in the half of the frame nobody
     * was looking at. The column histogram of the frame is what settled it in one command.
     */
    private static final double LANE_A = CAM_X + LANE;
    private static final double LANE_B = CAM_X - LANE;

    private static int[][] shotEmpty;
    private static int[][] shotAlone;
    private static int[] boxAlone;

    /**
     * How much of alpha's silhouette is allowed to move when beta joins the world, as a fraction
     * of alpha's own pixels.
     *
     * <p>Not zero: a real client re-bakes every model on a resource reload and the frame is
     * resampled, so a handful of edge pixels differ between two captures of a motionless subject.
     * Both failure modes are nothing like this small — a magenta cube cluster and a different
     * creature's models each move most of the body — so the threshold does not need to be tight to
     * be decisive, and a tight one would make the run flaky rather than strict.
     */
    private static final double ALPHA_DRIFT_LIMIT = 0.05;

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
                    if (ClientHarness.bootstrapWorld(mc, "vanilla-two", ticks, true)) {
                        go(Phase.EMPTY);
                    }
                }
                case EMPTY -> {
                    if (ClientHarness.inWorld(mc) && ticks > ClientHarness.INGAME_SETTLE) {
                        hold(mc);
                        pinTheSky(mc);
                        ClientHarness.quietParticles(mc);
                        ground(mc);
                        if (ticks > ClientHarness.INGAME_SETTLE + 10) {
                            go(Phase.PREPARE_A);
                        }
                    }
                }
                case PREPARE_A -> prepareOne(mc);
                case RELOAD_A -> {
                    if (reloadDone && mc.gui.overlay() == null && ticks > 20) {
                        go(Phase.SPAWN_A);
                    }
                }
                case SPAWN_A -> {
                    hold(mc);
                    if (ticks > 10) {
                        if (shotEmpty == null) {
                            shotEmpty = grab(mc, "0-empty");
                        }
                        spawnA(mc);
                    }
                }
                case SETTLE_A -> {
                    hold(mc);
                    if (ticks > 30) {
                        go(Phase.SHOOT_A);
                    }
                }
                case SHOOT_A -> shootAlone(mc);
                case PREPARE_B -> prepareTwo(mc);
                case SPAWN_B -> {
                    hold(mc);
                    if (ticks > 10) {
                        spawnB(mc);
                    }
                }
                case SETTLE_B -> {
                    hold(mc);
                    if (ticks > 40) {
                        go(Phase.SHOOT_B);
                    }
                }
                case SHOOT_B -> shootBoth(mc);
                default -> { }
            }
        } catch (Exception e) {
            finish(false, "threw " + e);
        }
    }

    // --------------------------------------------------------------------------- the creatures
    // (the pack-rig arm wrote both creatures into ONE shared per-creature pack here, from a clean
    // root, and checked the two were handed different slots; all of that was deleted by an earlier change)

    private static void prepareOne(Minecraft mc) {
        if (fired) {
            return;
        }
        fired = true;
        keyA = "vtwo_" + RigCarrier.safe(CASE_A);
        List<CreatureSculpt.Part> parts = SpikeFixture.parts(CASE_A);
        if (parts.isEmpty()) {
            finish(false, "fixture " + CASE_A + " is empty -- the corpus is missing");
            return;
        }
        try {
            CreatureFiles.write(keyA, parts, "{}");
        } catch (Exception e) {
            finish(false, "alpha creature write failed: " + e);
            return;
        }
        NOTES.add(ClientHarness.excludePack(mc, "file/mfam-vanilla-rig"));
        reload(mc, Phase.RELOAD_A);
    }

    private static void prepareTwo(Minecraft mc) {
        if (fired) {
            return;
        }
        fired = true;
        keyB = "vtwo_" + RigCarrier.safe(CASE_B);
        List<CreatureSculpt.Part> parts = SpikeFixture.parts(CASE_B);
        if (parts.isEmpty()) {
            finish(false, "fixture " + CASE_B + " is empty -- the corpus is missing");
            return;
        }
        try {
            CreatureFiles.write(keyB, parts, "{}");
        } catch (Exception e) {
            finish(false, "beta creature write failed: " + e);
            return;
        }
        // No reload: nothing per-creature reaches the client on the atom rig.
        go(Phase.SPAWN_B);
    }

    private static void reload(Minecraft mc, Phase next) {
        reloadDone = false;
        long t0 = System.currentTimeMillis();
        mc.reloadResourcePacks().thenRun(() -> {
            reloadDone = true;
            NOTES.add("reload " + next + " took " + (System.currentTimeMillis() - t0) + " ms");
        });
        go(next);
    }

    // ------------------------------------------------------------------------------ the spawns

    private static void spawnA(Minecraft mc) {
        if (fired) {
            return;
        }
        fired = true;
        spawnRig(mc, CASE_A, keyA, LANE_A, r -> rigA = r);
        go(Phase.SETTLE_A);
    }

    private static void spawnB(Minecraft mc) {
        if (fired) {
            return;
        }
        fired = true;
        spawnRig(mc, CASE_B, keyB, LANE_B, r -> rigB = r);
        go(Phase.SETTLE_B);
    }

    private static void spawnRig(Minecraft mc, String caseName, String key, double laneX,
                                 java.util.function.Consumer<DisplayRig> sink) {
        var server = mc.getSingleplayerServer();
        if (server == null) {
            finish(false, "no integrated server");
            return;
        }
        List<CreatureSculpt.Part> parts = SpikeFixture.parts(caseName);
        double scale = SpikeFixture.scale(caseName);
        server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
            if (p == null) {
                return;
            }
            ServerLevel level = p.level();
            var extents = CreatureAnatomy.extents(RigGeometry.of(parts));
            DisplayRig rig = DisplayRig.spawn(level, new Vec3(laneX, FOOT_Y, STAGE_Z), 270f,
                    parts, scale,
                    CreatureAnatomy.widthBlocks(extents) * scale,
                    CreatureAnatomy.heightBlocks(extents) * scale, null);
            rig.base().setNoGravity(true);
            rig.base().setNoAi(true);
            rig.base().setSilent(true);
            rig.base().setInvulnerable(true);
            for (var d : rig.all()) {
                d.setNoGravity(true);
            }
            sink.accept(rig);
            NOTES.add(caseName + ": " + rig.displays().size() + " displays");
        });
    }

    // ------------------------------------------------------------------------- the measurements

    private static void shootAlone(Minecraft mc) {
        hold(mc);
        if (ticks < 20) {
            return;
        }
        shotAlone = grab(mc, "1-alpha-alone");
        boxAlone = box(shotEmpty, shotAlone, 0, shotAlone[0].length / 2);
        if (boxAlone == null) {
            finish(false, "alpha drew nothing at all when it was the only creature in the pack -- "
                    + "the run cannot measure whether beta disturbed it");
            return;
        }
        NOTES.add("alpha alone: " + fmt(boxAlone) + " " + pixels(shotEmpty, shotAlone, boxAlone)
                + " px, mean " + meanIn(shotAlone, boxAlone));
        go(Phase.PREPARE_B);
    }

    private static void shootBoth(Minecraft mc) {
        hold(mc);
        if (ticks < 20) {
            return;
        }
        int[][] shot = grab(mc, "2-both");
        int w = shot[0].length;
        int[] boxA2 = box(shotEmpty, shot, 0, w / 2);
        int[] boxB2 = box(shotEmpty, shot, w / 2, w);

        List<String> bad = new ArrayList<>();

        // ── the headline: alpha must be UNCHANGED ───────────────────────────────────────────
        if (boxA2 == null) {
            bad.add("alpha VANISHED when beta was written -- its model files were deleted");
        } else {
            int moved = 0;
            int total = 0;
            for (int y = Math.max(0, boxAlone[1]); y <= Math.min(shot.length - 1, boxAlone[3]);
                    y++) {
                for (int x = Math.max(0, boxAlone[0]);
                        x <= Math.min(w - 1, boxAlone[2]); x++) {
                    if (delta(shotEmpty[y][x], shotAlone[y][x]) > BODY_THRESHOLD) {
                        total++;
                        if (delta(shotAlone[y][x], shot[y][x]) > BODY_THRESHOLD) {
                            moved++;
                        }
                    }
                }
            }
            double drift = total == 0 ? 1.0 : (double) moved / total;
            RESULTS.put("alpha unchanged", moved + " of " + total + " px moved ("
                    + r3(drift) + "), limit " + ALPHA_DRIFT_LIMIT);
            if (drift > ALPHA_DRIFT_LIMIT) {
                bad.add("alpha's picture CHANGED when beta joined: " + moved + "/" + total
                        + " px (" + r3(drift) + ") -- it is wearing somebody else's models");
            }
        }

        // ── beta really drew something ──────────────────────────────────────────────────────
        if (boxB2 == null) {
            bad.add("beta drew NOTHING -- the second creature never reached the pack");
        } else {
            RESULTS.put("beta present", fmt(boxB2) + " "
                    + pixels(shotEmpty, shot, boxB2) + " px");
        }

        // ── and they are two DIFFERENT creatures ────────────────────────────────────────────
        if (boxA2 != null && boxB2 != null) {
            double aA = aspect(boxA2);
            double aB = aspect(boxB2);
            RESULTS.put("two different creatures", "alpha aspect " + r2(aA)
                    + " beta aspect " + r2(aB) + " | alpha " + meanIn(shot, boxA2)
                    + " beta " + meanIn(shot, boxB2));
            if (sameShape(shot, boxA2, boxB2)) {
                bad.add("the two lanes are the SAME creature -- aspect " + r2(aA) + " vs "
                        + r2(aB) + " and the same mean colour, so both are rendering one "
                        + "creature's models");
            }
        }

        // ── the magenta check: a missing model is BRIGHT, not absent ────────────────────────
        //  Worth its own number because it is the wipe bug's real signature and it would satisfy
        //  every box-based assertion above on its own: a cluster of missing-model cubes has a box,
        //  has pixels, and has an aspect.
        int magentaA = magenta(shot, boxA2);
        int magentaB = magenta(shot, boxB2);
        RESULTS.put("no missing models", "magenta px: alpha " + magentaA + " beta " + magentaB);
        if (magentaA > 200 || magentaB > 200) {
            bad.add("missing-model cubes on screen (magenta px alpha=" + magentaA
                    + " beta=" + magentaB + ") -- the pack does not carry both creatures");
        }

        for (var e : RESULTS.entrySet()) {
            NOTES.add(e.getKey() + ": " + e.getValue());
        }
        if (failLater != null) {
            bad.add(failLater);
        }
        finish(bad.isEmpty(), String.join("; ", bad));
    }

    /**
     * Are the two lanes the same creature? Aspect AND mean colour together, because either alone
     * is weak: two different creatures can share an aspect, and two creatures painted from the
     * same palette can share a mean.
     */
    private static boolean sameShape(int[][] shot, int[] a, int[] b) {
        double da = Math.abs(aspect(a) - aspect(b)) / Math.max(aspect(a), aspect(b));
        return da < 0.06 && meanIn(shot, a).equals(meanIn(shot, b));
    }

    // ------------------------------------------------------------------------------- the stage

    private static void ground(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        if (server == null || groundLaid) {
            return;
        }
        groundLaid = true;
        server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
            if (p == null) {
                groundLaid = false;
                return;
            }
            ServerLevel level = p.level();
            var floor = net.minecraft.world.level.block.Blocks.CONCRETE.white().defaultBlockState();
            for (int x = (int) CAM_X - 20; x <= (int) CAM_X + 20; x++) {
                for (int z = (int) STAGE_Z - 8; z <= (int) STAGE_Z + 8; z++) {
                    level.setBlock(new net.minecraft.core.BlockPos(x, GROUND_Y, z), floor, 3);
                }
            }
        });
    }

    private static void pinTheSky(Minecraft mc) {
        mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
        var server = mc.getSingleplayerServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            for (ServerLevel level : server.getAllLevels()) {
                com.moddingfromamod.mv.DayTime.set(level, 6000L);
                com.moddingfromamod.mv.DayTime.weather(level, 6000, 0, false, false);
            }
            server.overworld().getGameRules().set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME,false, server);
            server.overworld().getGameRules().set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_WEATHER,false, server);
        });
    }

    private static void hold(Minecraft mc) {
        if (mc.player == null) {
            return;
        }
        com.moddingfromamod.gatec.ClientHarness.setHudHidden(mc, true);
        mc.gui.setScreen(null);
        mc.player.setPos(CAM_X, CAM_Y, CAM_Z);
        mc.player.setYRot(0f);
        mc.player.setXRot(CAM_PITCH);
        mc.player.xOld = CAM_X;
        mc.player.yOld = CAM_Y;
        mc.player.zOld = CAM_Z;
        mc.player.yRotO = 0f;
        mc.player.xRotO = CAM_PITCH;
    }

    // ------------------------------------------------------------------------- the instruments

    private static final int BODY_THRESHOLD = 70;

    private static double aspect(int[] b) {
        return b == null ? -1 : (double) (b[2] - b[0] + 1) / Math.max(1, b[3] - b[1] + 1);
    }

    private static int pixels(int[][] before, int[][] after, int[] box) {
        if (box == null) {
            return 0;
        }
        int n = 0;
        for (int y = box[1]; y <= box[3]; y++) {
            for (int x = box[0]; x <= box[2]; x++) {
                if (delta(before[y][x], after[y][x]) > BODY_THRESHOLD) {
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * Minecraft's missing-model cube is the classic magenta-and-black chequer. Count strongly
     * magenta pixels — high red, high blue, low green — because "alpha is gone" and "alpha is a
     * pile of missing-model cubes" are different findings with the same box.
     */
    private static int magenta(int[][] shot, int[] box) {
        if (box == null) {
            return 0;
        }
        int n = 0;
        for (int y = Math.max(0, box[1]); y <= Math.min(shot.length - 1, box[3]); y++) {
            for (int x = Math.max(0, box[0]); x <= Math.min(shot[0].length - 1, box[2]); x++) {
                int c = shot[y][x];
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                if (r > 130 && b > 130 && g < 90 && Math.abs(r - b) < 70) {
                    n++;
                }
            }
        }
        return n;
    }

    private static String meanIn(int[][] shot, int[] box) {
        if (shot == null || box == null) {
            return "?";
        }
        long r = 0;
        long g = 0;
        long b = 0;
        long n = 0;
        for (int y = Math.max(0, box[1]); y <= Math.min(shot.length - 1, box[3]); y++) {
            for (int x = Math.max(0, box[0]); x <= Math.min(shot[0].length - 1, box[2]); x++) {
                int c = shot[y][x];
                r += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                b += c & 0xFF;
                n++;
            }
        }
        return n == 0 ? "?" : "(" + r / n + "," + g / n + "," + b / n + ")";
    }

    private static int[] box(int[][] before, int[][] after, int xFrom, int xTo) {
        int x0 = Integer.MAX_VALUE;
        int y0 = Integer.MAX_VALUE;
        int x1 = -1;
        int y1 = -1;
        int yMax = (int) (before.length * 0.90);
        for (int y = 0; y < yMax; y++) {
            for (int x = xFrom; x < Math.min(xTo, before[0].length); x++) {
                if (delta(before[y][x], after[y][x]) > BODY_THRESHOLD) {
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    x1 = Math.max(x1, x);
                    y1 = Math.max(y1, y);
                }
            }
        }
        return x1 < 0 ? null : new int[] {x0, y0, x1, y1};
    }

    private static int delta(int a, int b) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF))
                + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                + Math.abs((a & 0xFF) - (b & 0xFF));
    }

    private static String fmt(int[] b) {
        return b == null ? "none" : "(" + b[0] + "," + b[1] + ")-(" + b[2] + "," + b[3] + ")";
    }

    private static String r2(double d) {
        return d < 0 ? "?" : String.format("%.2f", d);
    }

    private static String r3(double d) {
        return d < 0 ? "?" : String.format("%.3f", d);
    }

    private static int[][] grab(Minecraft mc, String name) {
        if (mc.gui.overlay() != null) {
            failLater = "a frame was captured with " + mc.gui.overlay().getClass().getSimpleName()
                    + " still on screen";
        }
        try (var img = Screenshots.take(mc.gameRenderer.mainRenderTarget())) {
            int w = img.getWidth();
            int h = img.getHeight();
            int[][] px = new int[h][w];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    // getPixelRGBA is ABGR -- see VanillaStateClientTest.grab for the whole story.
                    int abgr = com.moddingfromamod.compat.Pixels.abgr(img, x, y);
                    px[y][x] = ((abgr & 0xFF) << 16) | (abgr & 0xFF00) | ((abgr >> 16) & 0xFF);
                }
            }
            if (name != null) {
                try {
                    java.nio.file.Files.createDirectories(SIG);
                    img.writeToFile(SIG.resolve(name + ".png"));
                } catch (Exception ignored) {
                    // the pixels carry the verdict
                }
            }
            return px;
        }
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
