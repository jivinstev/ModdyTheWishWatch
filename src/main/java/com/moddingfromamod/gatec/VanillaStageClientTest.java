package com.moddingfromamod.gatec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.runtime.AnimationState;
import com.moddingfromamod.runtime.BedrockAnimation;
import com.moddingfromamod.runtime.CreatureAnatomy;
import com.moddingfromamod.runtime.CreatureFiles;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.ModelRepository;
import com.moddingfromamod.vanilla.AtomLibrary;
import com.moddingfromamod.vanilla.DisplayRig;
import com.moddingfromamod.vanilla.RigCarrier;
import com.moddingfromamod.vanilla.RigDriver;
import com.moddingfromamod.vanilla.RigFit;
import com.moddingfromamod.vanilla.RigGeometry;
import com.moddingfromamod.vanilla.RigPose;
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
 * <b>Gate C, 1.21.4: one atom rig on a still stage — the atlas it paints (G3) and the client tween
 * (A8)</b> ({@code docs/VANILLA_RIG_SCOREBOARD.md}).
 *
 * <p>The port of the two halves of the parked {@code VanillaCreatureAbClientTest}
 * ({@code vanillacreature}, branch {@code parked/create-trains-mc1.21.1}) that never needed a
 * second arm. That test also drove the 1.21.1 item-model mechanism (reading the carrier's
 * {@code ItemOverrides} by reflection, resolving a {@code BakedModel} per stack), which is why it
 * could not run on 1.21.4; nothing here asks the item renderer anything. Both measurements are
 * PIXELS, read off the frame the client really drew:
 *
 * <ol>
 *   <li><b>G3 — the rig's atlas is there.</b> The missing model is magenta and BRIGHT, so every
 *       "the frame is not dark" and "something drew" check passes on total failure. It is counted
 *       by colour inside the subject's own box, and must be <b>0</b>; and the rig must have drawn
 *       above the noise of photographing the same frame twice (a frame of nothing has no magenta
 *       either). {@code stagesabotage=atlas} points every display at an item model that does not
 *       exist, which is the failure photographed: the run must go red.</li>
 *   <li><b>A8 — the tween really tweens and lands exactly.</b> Snap the rig half a walk cycle away,
 *       then ask the CLIENT to fly it back over {@code tweenTicks}; photograph along the way. The
 *       pixel distance from the destination must FALL monotonically (within the same-frame floor)
 *       and END at the floor — and it is TWO-SIDED, because "it landed" is satisfied by a client
 *       that ignored the duration and snapped, so the mid-flight frame must still be measurably
 *       away. The control first: the away pose must have moved the picture at all.
 *       {@code stagesabotage=snap} sends the tween with no duration (must go red as SNAPPED), and
 *       {@code stagesabotage=miss} flies it to a different frame (must go red as MISSED).</li>
 * </ol>
 *
 * <p>The stage is the parked test's: superflat, a fixed seat in the air, gravity and AI off, clouds
 * off, a fixed noon — so the background is identical in every frame and the subject region really is
 * the creature (the camera notes). The camera standoff is derived once from the MODEL's height,
 * never from the carrier's hitbox.
 *
 * <p>Run: {@code TESTMODE=vanillastage ./tools/client-test.sh}, or
 * {@code ./tools/gate-c-rig.sh --only vanillastage}. Defaults are the A8 run of record: Spikor, the
 * walk clip at t=0.45, a 30-tick tween.
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class VanillaStageClientTest {

    private VanillaStageClientTest() {
    }

    private static final String P = ModdingFromAModMod.MODID + ".";
    private static final String TAG = "VANILLA_STAGE";

    private static final boolean ENABLED =
            "vanillastage".equalsIgnoreCase(System.getProperty(P + "testmode", ""));

    private static final Path SIG = Path.of(
            System.getProperty(P + "sigdir", "/tmp/moddingfromamod-vanillastage"));

    private static final String CASE = System.getProperty(P + "cases", SpikeFixture.SPIKOR).trim();

    private static final float STAGE_YAW =
            Float.parseFloat(System.getProperty(P + "stageyaw", "90"));

    private static final String POSE_CLIP = System.getProperty(P + "poseClip", "walk");

    private static final float POSE_TIME =
            Float.parseFloat(System.getProperty(P + "poseTime", "0.45"));

    private static final int TWEEN_TICKS =
            Integer.parseInt(System.getProperty(P + "tweenTicks", "30"));

    /**
     * The A/B seam that shows each measurement going red: {@code atlas} (every display wears an
     * item model that does not exist — G3), {@code snap} (the tween is sent with no duration — A8's
     * mid-flight side), {@code miss} (the tween flies to a different frame — A8's landing side).
     */
    private static final String SABOTAGE = System.getProperty(P + "stagesabotage", "off").trim();

    /** An item model id nothing ships — what a display wears when the atlas did not arrive. */
    private static final String NO_SUCH_MODEL = ModdingFromAModMod.MODID + ":rig_atom_not_shipped";

    private enum Phase { WAIT_TITLE, WAIT_INGAME, PREPARE, RELOAD, SHOOT_EMPTY, SPAWN, SHOOT,
                         SHOOT_FLOOR, TWEEN_AWAY, TWEEN_BACK, SCORE, DONE }

    private static Phase phase = Phase.WAIT_TITLE;
    private static int ticks;
    private static boolean fired;
    private static final List<String> NOTES = java.util.Collections.synchronizedList(new ArrayList<>());
    private static final List<String> FAILED = java.util.Collections.synchronizedList(new ArrayList<>());

    private static int[][] shotEmpty;
    private static int[][] shot;
    private static int[][] shotFloor;
    private static int[][] shotAway;
    private static int[][] shotMid;
    private static int[][] shotLanded;
    private static final int[][][] SAMPLES = new int[4][][];
    private static volatile UUID subject;
    /** Every display the rig spawned: a frame is taken only once the CLIENT holds all of them. */
    private static volatile java.util.Set<UUID> displayIds = java.util.Set.of();
    /** The tick (of this phase) on which the client first held the whole rig, or -1. */
    private static int seenAt = -1;
    private static volatile DisplayRig rig;

    private static final double CAM_X = 8.5;
    private static final double CAM_Y = 96.0;
    private static final double CAM_Z = 8.5;
    private static double camDistance;
    private static double camAimY;
    private static final int SETTLE = 20;

    private static Vec3 stage() {
        return new Vec3(CAM_X, CAM_Y - 0.8, CAM_Z + camDistance);
    }

    private static String key() {
        return "vstage_" + RigCarrier.safe(CASE);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!ENABLED || phase == Phase.DONE) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ticks++;
        try {
            step(mc);
        } catch (Exception e) {
            finish(false, "threw in " + phase + ": " + e);
        }
    }

    private static void step(Minecraft mc) {
        switch (phase) {
            case WAIT_TITLE -> {
                if (ClientHarness.bootstrapWorld(mc, "vanilla-stage", ticks, true)) {
                    go(Phase.WAIT_INGAME);
                }
            }
            case WAIT_INGAME -> {
                if (ClientHarness.inWorld(mc) && ticks > ClientHarness.INGAME_SETTLE) {
                    go(Phase.PREPARE);
                }
            }
            case PREPARE -> prepare(mc);
            case RELOAD -> {
                if (ticks > 20) {
                    go(Phase.SHOOT_EMPTY);
                }
            }
            case SHOOT_EMPTY -> {
                hold(mc);
                // Longer than the arms' settle: the first frame after joining can still be a
                // chunk or an atlas behind, and an empty baseline that differs from everything
                // reads as "the creature drew the whole screen".
                if (ticks > SETTLE * 4) {
                    shotEmpty = grab(mc, "empty");
                    go(Phase.SPAWN);
                }
            }
            case SPAWN -> spawn(mc);
            case SHOOT -> {
                if (settled(mc)) {
                    shot = grab(mc, "destination");
                    go(Phase.SHOOT_FLOOR);
                }
            }
            case SHOOT_FLOOR -> {
                // THE FLOOR: the same frame again, nothing changed. Every number below is "px
                // changed", which means nothing without what two identical frames score.
                if (settled(mc)) {
                    shotFloor = grab(mc, "floor");
                    go(Phase.TWEEN_AWAY);
                }
            }
            case TWEEN_AWAY -> {
                // Snap somewhere ELSE first. Without this the tween back has nowhere to travel
                // from, and "it arrived" is satisfied by a rig that never moved.
                if (!fired) {
                    fired = true;
                    pose(mc, POSE_TIME + clipLength() / 2f, "away (snap)", 0);
                }
                hold(mc);
                if (ticks == 14) {
                    shotAway = grab(mc, "away");
                }
                if (ticks > 15) {
                    go(Phase.TWEEN_BACK);
                }
            }
            case TWEEN_BACK -> {
                if (!fired) {
                    fired = true;
                    float to = "miss".equalsIgnoreCase(SABOTAGE)
                            ? POSE_TIME + clipLength() / 4f : POSE_TIME;
                    int over = "snap".equalsIgnoreCase(SABOTAGE) ? 0 : TWEEN_TICKS;
                    pose(mc, to, "back over " + over + " ticks", over);
                }
                hold(mc);
                // Four points on the flight, not one: "not there yet" is satisfied by a rig that
                // has not started; a curve that falls to the floor is the client flying it.
                for (int k = 0; k < SAMPLES.length; k++) {
                    if (ticks == Math.max(2 + k, (TWEEN_TICKS * (k + 1)) / (SAMPLES.length + 1))) {
                        SAMPLES[k] = grab(mc, "flight" + k);
                    }
                }
                if (ticks == Math.max(2, TWEEN_TICKS / 2)) {
                    shotMid = grab(mc, "mid");
                }
                if (ticks > TWEEN_TICKS + 20) {
                    shotLanded = grab(mc, "landed");
                    go(Phase.SCORE);
                }
            }
            case SCORE -> score(mc);
            default -> { }
        }
    }

    // ── setup ────────────────────────────────────────────────────────────────────────────────

    private static void prepare(Minecraft mc) {
        if (fired) {
            return;
        }
        fired = true;
        try {
            List<CreatureSculpt.Part> parts = SpikeFixture.parts(CASE);
            double scale = SpikeFixture.scale(CASE);
            CreatureFiles.write(key(), parts, "{}");
            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            var srv = mc.getSingleplayerServer();
            if (srv != null) {
                srv.execute(() -> {
                    for (ServerLevel l : srv.getAllLevels()) {
                        com.moddingfromamod.mv.DayTime.set(l, 6000L);
                        l.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME,false, srv);
                        l.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_WEATHER,false, srv);
                        com.moddingfromamod.mv.DayTime.weather(l, 6000, 0, false, false);
                    }
                });
            }
            var extents = CreatureAnatomy.extents(RigGeometry.of(parts));
            double h = CreatureAnatomy.heightBlocks(extents) * scale;
            camDistance = Math.max(2.2, Math.min(26.0, h * 1.3));
            camAimY = h * 0.55;
            NOTES.add("case " + CASE + " (" + parts.size() + " boxes, " + String.format("%.2f", h)
                    + " blocks tall), clip " + POSE_CLIP + " t=" + POSE_TIME + " (cycle "
                    + String.format("%.2f", clipLength()) + " s), tween " + TWEEN_TICKS
                    + " ticks, sabotage=" + SABOTAGE + "; atom cube " + AtomLibrary.modelName());
            go(Phase.RELOAD);
        } catch (Exception e) {
            finish(false, "could not prepare " + CASE + ": " + e);
        }
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
        List<CreatureSculpt.Part> parts = SpikeFixture.parts(CASE);
        double scale = SpikeFixture.scale(CASE);
        server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
            if (p == null) {
                return;
            }
            var extents = CreatureAnatomy.extents(RigGeometry.of(parts));
            DisplayRig r = DisplayRig.spawn(p.level(), stage(), STAGE_YAW, parts, scale,
                    CreatureAnatomy.widthBlocks(extents) * scale,
                    CreatureAnatomy.heightBlocks(extents) * scale, null);
            r.base().setNoGravity(true);
            r.base().setNoAi(true);
            for (var d : r.all()) {
                d.setNoGravity(true);
            }
            for (String id : r.entityTypeIds()) {
                if (!id.startsWith("minecraft:")) {
                    FAILED.add("the rig spawned a non-vanilla entity: " + id);
                }
            }
            if ("atlas".equalsIgnoreCase(SABOTAGE)) {
                int n = 0;
                for (var d : r.displays()) {
                    if (d instanceof net.minecraft.world.entity.Display.ItemDisplay id) {
                        var st = id.getSlot(0).get().copy();
                        st.set(net.minecraft.core.component.DataComponents.ITEM_MODEL,
                                net.minecraft.resources.Identifier.parse(NO_SUCH_MODEL));
                        id.getSlot(0).set(st);
                        n++;
                    }
                }
                NOTES.add("SABOTAGE atlas: " + n + " displays now wear " + NO_SUCH_MODEL);
            }
            rig = r;
            poseNow(POSE_TIME, "destination", 0);
            NOTES.add("rig = " + r.displays().size() + " item_display + 1 "
                    + (r.choice() == null ? "?" : r.choice().id()) + " base");
            java.util.Set<UUID> ids = new java.util.HashSet<>();
            for (var d : r.displays()) {
                ids.add(d.getUUID());
            }
            displayIds = ids;
            subject = r.base().getUUID();
        });
        go(Phase.SHOOT);
    }

    private static float clipLength() {
        var chosen = BedrockAnimation.find(ModelRepository.animations(key()), clipKind());
        return chosen == null ? 1f : chosen.length();
    }

    private static BedrockAnimation.Clip clipKind() {
        try {
            return BedrockAnimation.Clip.valueOf(POSE_CLIP.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException e) {
            return BedrockAnimation.Clip.WALK;
        }
    }

    /** Drive the rig to one frame of the clip, on the server thread that owns it. */
    private static void poseNow(float seconds, String label, int tweenFor) {
        DisplayRig r = rig;
        if (r == null) {
            return;
        }
        var pose = AnimationState.pose(ModelRepository.animations(key()),
                new AnimationState.Selection(clipKind(), seconds));
        if (pose.isEmpty()) {
            FAILED.add(POSE_CLIP + " sampled to NOTHING at t=" + seconds
                    + " -- the rig is standing at rest and no tween is being measured");
            return;
        }
        var frame = RigPose.compose(RigFit.intoRigSpace(SpikeFixture.parts(CASE)), pose);
        int written = RigDriver.animate(r, frame, pose, tweenFor);
        NOTES.add("posed t=" + String.format("%.3f", seconds) + " (" + label + "): " + pose.size()
                + " bones keyed, " + written + " displays rewritten");
        if (written == 0) {
            FAILED.add("the " + label + " pose rewrote ZERO displays");
        }
    }

    /** The duration rides as an ARGUMENT across server.execute, never as a static (PARITY §12). */
    private static void pose(Minecraft mc, float seconds, String label, int tweenFor) {
        var server = mc.getSingleplayerServer();
        if (server != null) {
            server.execute(() -> poseNow(seconds, label, tweenFor));
        }
    }

    // ── camera + shutter ─────────────────────────────────────────────────────────────────────

    private static void hold(Minecraft mc) {
        if (mc.player == null) {
            return;
        }
        mc.options.hideGui = true;
        mc.setScreen(null);
        Vec3 foot = stage();
        double aimY = foot.y + camAimY;
        float pitch = (float) -Math.toDegrees(
                Math.atan2(aimY - (CAM_Y + mc.player.getEyeHeight()), camDistance));
        mc.player.setPos(CAM_X, CAM_Y, CAM_Z);
        mc.player.setYRot(0f);
        mc.player.setXRot(pitch);
        mc.player.xOld = CAM_X;
        mc.player.yOld = CAM_Y;
        mc.player.zOld = CAM_Z;
        mc.player.yRotO = 0f;
        mc.player.xRotO = pitch;
    }

    /**
     * Shoot {@link #SETTLE} ticks after the client holds the base AND every display — never
     * {@code SETTLE} ticks after the phase began. Measured: the first cut counted from the phase,
     * and one run in five photographed the stage before the displays arrived, so the
     * "destination" was byte-identical to the empty frame and every number was 0.
     */
    private static boolean settled(Minecraft mc) {
        hold(mc);
        if (seenAt < 0 && subject != null && find(mc, subject) != null && allDisplaysHere(mc)) {
            seenAt = ticks;
        }
        if (seenAt < 0) {
            if (ticks > 400) {
                FAILED.add("the rig never fully reached the client");
                return true;
            }
            return false;
        }
        return ticks - seenAt > SETTLE;
    }

    private static boolean allDisplaysHere(Minecraft mc) {
        java.util.Set<UUID> want = displayIds;
        if (want.isEmpty() || mc.level == null) {
            return false;
        }
        int n = 0;
        for (var e : mc.level.entitiesForRendering()) {
            if (want.contains(e.getUUID())) {
                n++;
            }
        }
        return n == want.size();
    }

    private static net.minecraft.world.entity.Entity find(Minecraft mc, UUID id) {
        if (id == null || mc.level == null) {
            return null;
        }
        for (var e : mc.level.entitiesForRendering()) {
            if (id.equals(e.getUUID())) {
                return e;
            }
        }
        return null;
    }

    private static int[][] grab(Minecraft mc, String label) {
        try (var img = Screenshots.take(mc.getMainRenderTarget())) {
            int w = img.getWidth();
            int h = img.getHeight();
            int[][] px = new int[h][w];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    px[y][x] = com.moddingfromamod.compat.Pixels.abgr(img, x, y) & 0xFFFFFF;
                }
            }
            try {
                java.nio.file.Files.createDirectories(SIG);
                img.writeToFile(SIG.resolve(RigCarrier.safe(CASE) + "_" + label + ".png"));
            } catch (Exception ignored) {
                // the pixels are the verdict; the file is for a human
            }
            return px;
        }
    }

    // ── scoring ──────────────────────────────────────────────────────────────────────────────

    private static void score(Minecraft mc) {
        int[] box = new int[6];
        int drew = diffIn(shotEmpty, shot, null, box);
        int[] region = pad(box);
        int area = Math.max(1, (region[2] - region[0] + 1) * (region[3] - region[1] + 1));
        int floor = diffIn(shot, shotFloor, region, null);
        int tol = Math.max(floor, 1);
        int magenta = magentaIn(shot, region);

        // G3. "Did it draw" first, or 0 magenta px is true of a photograph of nothing.
        String g3 = String.format("G3 drew %d px (%.1f%% of %d px region, box %dx%d), floor %d,"
                        + " missing-model px %d", drew, 100.0 * drew / area, area,
                box[2] - box[0], box[3] - box[1], floor, magenta);
        if (drew < area / 50 || drew <= tol) {
            FAILED.add("G3: the creature never rendered (" + drew + " px vs the empty stage, floor "
                    + floor + ")");
        }
        if (magenta > 0) {
            FAILED.add("G3: " + magenta + " missing-model (magenta) px inside the creature -- the "
                    + "atlas the rig paints did not arrive");
        }

        // A8. Control, flight, two-sided landing.
        int away = diffIn(shot, shotAway, region, null);
        int mid = diffIn(shot, shotMid, region, null);
        int landed = diffIn(shot, shotLanded, region, null);
        StringBuilder curve = new StringBuilder().append(away);
        int prev = away;
        boolean monotonic = true;
        for (int[][] s : SAMPLES) {
            int d = diffIn(shot, s, region, null);
            curve.append("->").append(d);
            if (d < 0 || d > prev + tol) {
                monotonic = false;
            }
            prev = d;
        }
        curve.append("->").append(landed);
        if (landed > prev + tol) {
            monotonic = false;
        }
        String a8 = String.format("A8 px from the destination %s%s, mid %d, landed %d (floor %d)",
                curve, monotonic ? " monotonic" : " NOT MONOTONIC", mid, landed, floor);
        if (away <= tol) {
            FAILED.add("A8 control: the away pose did not change the picture (" + away
                    + " px) -- the tween had nowhere to travel from");
        }
        if (mid <= tol) {
            FAILED.add("A8: SNAPPED -- mid-flight is already at the destination (" + mid
                    + " px), so the duration did nothing");
        }
        if (landed > tol) {
            FAILED.add("A8: MISSED -- landed " + landed + " px from the pose it was sent, against a "
                    + floor + " px floor");
        }
        if (!monotonic) {
            FAILED.add("A8: the flight is not monotonic: " + curve);
        }
        report(g3 + " | " + a8);
    }

    private static int[] pad(int[] b) {
        int h = shotEmpty.length;
        int w = shotEmpty[0].length;
        int p = 8;
        return new int[]{Math.max(0, b[0] - p), Math.max(0, b[1] - p),
                Math.min(w - 1, b[2] + p), Math.min(h - 1, b[3] + p), w, h};
    }

    /**
     * The missing model is magenta (#F800F8) and black; counted by the magenta half, by HUE.
     *
     * <p>⚠ <b>Not the parked test's {@code r > 200 && b > 200 && g < 80}.</b> That rule only sees
     * an UNSHADED magenta face; a display's side faces are drawn at vanilla's 0.6/0.8 shade, so
     * #F800F8 arrives as ~(149,0,149) and (198,0,198) and falls under 200. Measured on
     * {@code stagesabotage=atlas}, where every one of Spikor's 64 displays is the missing model:
     * the old rule counted <b>558</b> px, this one <b>16,266</b> (the black half is the rest);
     * both count 0 on the intact rig. A detector that sees 3% of a total failure would still have
     * gone red here, but not on a creature with ONE missing box.
     */
    private static int magentaIn(int[][] px, int[] region) {
        int n = 0;
        for (int y = region[1]; y <= region[3] && y < px.length; y++) {
            for (int x = region[0]; x <= region[2] && x < px[y].length; x++) {
                int q = px[y][x];
                int r = q >> 16 & 0xFF;
                int g = q >> 8 & 0xFF;
                int b = q & 0xFF;
                if (r >= 96 && b >= 96 && g * 4 < Math.min(r, b)
                        && Math.abs(r - b) * 4 <= Math.max(r, b)) {
                    n++;
                }
            }
        }
        return n;
    }

    private static int diffIn(int[][] a, int[][] b, int[] region, int[] boxOut) {
        if (a == null || b == null || a.length != b.length) {
            return -1;
        }
        int n = 0;
        int x0 = Integer.MAX_VALUE;
        int y0 = Integer.MAX_VALUE;
        int x1 = -1;
        int y1 = -1;
        int fromY = region == null ? 0 : Math.max(0, region[1]);
        int toY = region == null ? a.length : Math.min(a.length, region[3] + 1);
        for (int y = fromY; y < toY; y++) {
            int fromX = region == null ? 0 : Math.max(0, region[0]);
            int toX = Math.min(Math.min(a[y].length, b[y].length),
                    region == null ? Integer.MAX_VALUE : region[2] + 1);
            for (int x = fromX; x < toX; x++) {
                int p = a[y][x];
                int q = b[y][x];
                if (Math.abs((p >> 16 & 0xFF) - (q >> 16 & 0xFF))
                        + Math.abs((p >> 8 & 0xFF) - (q >> 8 & 0xFF))
                        + Math.abs((p & 0xFF) - (q & 0xFF)) > 24) {
                    n++;
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    x1 = Math.max(x1, x);
                    y1 = Math.max(y1, y);
                }
            }
        }
        if (boxOut != null) {
            boxOut[0] = x0 == Integer.MAX_VALUE ? 0 : x0;
            boxOut[1] = y0 == Integer.MAX_VALUE ? 0 : y0;
            boxOut[2] = Math.max(0, x1);
            boxOut[3] = Math.max(0, y1);
            boxOut[4] = a[0].length;
            boxOut[5] = a.length;
        }
        return n;
    }

    // ── plumbing ─────────────────────────────────────────────────────────────────────────────

    private static void go(Phase next) {
        phase = next;
        ticks = 0;
        fired = false;
        seenAt = -1;
    }

    private static void report(String headline) {
        StringBuilder sb = new StringBuilder(headline).append('\n');
        if (!"off".equalsIgnoreCase(SABOTAGE)) {
            sb.append("VERIFY-TO-FAIL RUN (stagesabotage=").append(SABOTAGE).append(")\n");
        }
        synchronized (NOTES) {
            for (String s : NOTES) {
                sb.append("  ").append(s).append('\n');
            }
        }
        synchronized (FAILED) {
            for (String s : FAILED) {
                sb.append("  FAILED: ").append(s).append('\n');
            }
        }
        finish(FAILED.isEmpty(), sb.toString());
    }

    private static void finish(boolean pass, String message) {
        phase = Phase.DONE;
        System.out.println(TAG + ": " + (pass ? "PASS" : "FAIL") + " — " + message);
        ClientHarness.verdict(SIG, pass, message);
        Minecraft.getInstance().stop();
    }
}
