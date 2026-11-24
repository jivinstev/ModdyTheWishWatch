package com.moddingfromamod.gatec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.tools.SculptCreatureTool;
import com.moddingfromamod.runtime.CreatureAnatomy;
import com.moddingfromamod.runtime.CreatureFiles;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.ModelRepository;
import com.moddingfromamod.vanilla.CopyOfMeFixture;
import com.moddingfromamod.vanilla.DisplayRig;
import com.moddingfromamod.vanilla.RigGeometry;
import com.moddingfromamod.vanilla.RigHeadYaw;
import com.moddingfromamod.vanilla.VanillaRigAnimator;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * <b>Gate C — does a creature tilt its head to look at a child above or below it?</b>
 *
 * <p>The {@link CopyOfMeFixture} creature faces the camera, and its carrier's own vanilla
 * {@code LookControl} is pointed at the camera every tick, as {@code LookAtPlayerGoal} would. The
 * camera then rises 3 blocks above the creature's eyes and drops into a pit below them. The
 * shipping driver does the rest; {@code headpitch=off} is the arm without tilt.
 *
 * <h2>The oracle: where the face sits inside the head</h2>
 *
 * <p>For each held camera height, find the head's silhouette (hair + skin rows above the body)
 * and the face (eyes + mouth) inside it, and take {@code f} = how far down the silhouette the
 * face's centre sits. Face-on at eye level that is the REFERENCE. A head that tilts to the camera
 * keeps {@code f} near the reference from above and below. A level head seen from above has its
 * face pushed to the bottom of the silhouette (the top of the hair fills the rest); seen from
 * below, pushed up. "Pixels changed" and "the head is visible" are true of both arms and are not
 * oracles.
 *
 * <p>Measured (2026-09-25), face position vs face-on, above / below: tilt off {@code +0.200 /
 * -0.054}; tilt on {@code -0.031 / -0.019}; tilt with the WRONG sign {@code +0.339 / -0.155}. The
 * 0.10 tolerance sits between the arms from above, where the difference is large; from below a
 * level head barely differs, so that check mainly catches a sign error.
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class VanillaPitchClientTest {

    private VanillaPitchClientTest() {
    }

    private static final String P = ModdingFromAModMod.MODID + ".";

    private static final boolean ENABLED =
            "vanillapitch".equalsIgnoreCase(System.getProperty(P + "testmode", ""));

    private static final Path SIG = Path.of(
            System.getProperty(P + "sigdir", "/tmp/moddingfromamod-vanillapitch"));

    private enum Phase { WAIT_TITLE, WAIT_INGAME, EMPTY, PREPARE, RELOAD, SPAWN, SETTLE, MOVE, DONE }

    private static volatile Phase phase = Phase.WAIT_TITLE;
    private static int ticks;
    private static boolean fired;
    private static boolean reloadDone;
    private static boolean groundLaid;
    private static String key;
    private static volatile DisplayRig rig;
    private static List<CreatureSculpt.Part> parts;
    private static long filmStart;

    private static final List<String> NOTES = new ArrayList<>();
    private static final List<String> BAD = new ArrayList<>();

    private static final double X = 8.5;
    private static final double FOOT_Y = 95.0;
    private static final int GROUND_Y = (int) FOOT_Y - 1;
    private static final double STAGE_Z = 20.5;
    private static final double STANDOFF = 4.0;
    /** Where the creature's eyes are (a 32-unit humanoid at scale 1): the level camera height. */
    private static final double EYES = FOOT_Y + 1.8;

    /** Camera eye heights relative to the creature's eyes: level, above, below (in the pit). */
    private static final double LEVEL = 0.0;
    private static final double ABOVE = 3.0;
    private static final double BELOW = -1.6;

    private static volatile double camDy = LEVEL;
    private static Frame level;
    private static Frame above;
    private static Frame below;

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
                    if (ClientHarness.bootstrapWorld(mc, "vanilla-pitch", ticks, true)) {
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
                            go(Phase.PREPARE);
                        }
                    }
                }
                case PREPARE -> prepare(mc);
                case RELOAD -> {
                    if (reloadDone && mc.getOverlay() == null && ticks > 20) {
                        go(Phase.SPAWN);
                    }
                }
                case SPAWN -> {
                    hold(mc);
                    if (ticks > 10) {
                        spawn(mc);
                    }
                }
                case SETTLE -> {
                    hold(mc);
                    look(mc);
                    if (ticks > 40) {
                        filmStart = System.currentTimeMillis();
                        go(Phase.MOVE);
                    }
                }
                case MOVE -> move(mc);
                default -> { }
            }
        } catch (Exception e) {
            finish(false, "threw " + e);
        }
    }

    /**
     * The camera's path: hold level, rise, hold above, descend into the pit, hold below. Each hold is
     * photographed once the head has SETTLED there ({@link #settled}), never at a fixed client tick.
     *
     * <p>the head's tilt is the integrated SERVER's work — its LookControl, then the rig's write,
     * then the client's interpolation — and that server runs on its own thread, behind the client on a
     * busy machine. Photographed at fixed client ticks, "above" caught the head before it got there
     * (+0.204, the no-tilt arm's own number, and +0.12-0.18 on the way) in 2 runs of 4 on main (2026-11-02).
     */
    private static int legStart;
    private static int stableSince = -1;
    private static float lastPitch = Float.NaN;
    private static int lastServerTick = -1;
    private static int heldServerTicks;
    private static int settleWaited;

    private static void move(Minecraft mc) {
        int t = ticks;
        hold(mc);
        look(mc);
        switch (leg) {
            case 0 -> {   // level
                camDy = LEVEL;
                if (settled(mc, t)) {
                    level = Frame.of(grab(mc, "1-level"));
                    NOTES.add("level: " + level + waitedNote());
                    nextLeg(t);
                }
            }
            case 1 -> {   // rise
                camDy = LEVEL + (ABOVE - LEVEL) * Math.min(1.0, (t - legStart) / 40.0);
                if (t - legStart >= 40) {
                    nextLeg(t);
                }
            }
            case 2 -> {   // above
                camDy = ABOVE;
                if (settled(mc, t)) {
                    above = Frame.of(grab(mc, "2-above"));
                    NOTES.add("above (camera +" + ABOVE + " blocks): " + above + waitedNote());
                    nextLeg(t);
                }
            }
            case 3 -> {   // descend
                camDy = ABOVE + (BELOW - ABOVE) * Math.min(1.0, (t - legStart) / 60.0);
                if (t - legStart >= 60) {
                    nextLeg(t);
                }
            }
            default -> {  // below
                camDy = BELOW;
                if (settled(mc, t)) {
                    below = Frame.of(grab(mc, "3-below"));
                    NOTES.add("below (camera " + BELOW + " blocks): " + below + waitedNote());
                    writeFilm("pitch");
                    verdict();
                }
            }
        }
    }

    private static int leg;

    private static void nextLeg(int t) {
        leg++;
        legStart = t;
        stableSince = -1;
        lastPitch = Float.NaN;
        lastServerTick = -1;
        heldServerTicks = 0;
    }

    /** Server ticks a hold must last, and how long the pitch must then stay still (server ticks). */
    static final int HOLD_SERVER_TICKS = 30, STILL_SERVER_TICKS = 10, DRAW_CLIENT_TICKS = 6;
    /** A head that has not settled after this many client ticks is photographed anyway, and judged. */
    static final int SETTLE_TIMEOUT = 200;

    /**
     * Has the head settled at this camera height? The server has run {@link #HOLD_SERVER_TICKS} ticks
     * since the hold began, the carrier's pitch has moved less than half a degree for
     * {@link #STILL_SERVER_TICKS} server ticks, and the client has had {@link #DRAW_CLIENT_TICKS} more
     * ticks to interpolate and draw it. Or the timeout, so a head that never tilts is photographed and judged anyway.
     */
    private static boolean settled(Minecraft mc, int t) {
        var server = mc.getSingleplayerServer();
        if (server == null || rig == null) {
            return t - legStart >= SETTLE_TIMEOUT;
        }
        int st = server.getTickCount();
        if (st != lastServerTick) {
            if (lastServerTick >= 0) {
                heldServerTicks += st - lastServerTick;
            }
            lastServerTick = st;
            float pitch = aimedPitch;
            if (Float.isNaN(lastPitch) || Math.abs(pitch - lastPitch) >= 0.5f) {
                stableSince = heldServerTicks;
                lastPitch = pitch;
            }
        }
        boolean still = stableSince >= 0 && heldServerTicks >= HOLD_SERVER_TICKS
                && heldServerTicks - stableSince >= STILL_SERVER_TICKS;
        if (still) {
            settleWaited++;
        }
        if (settleWaited >= DRAW_CLIENT_TICKS || t - legStart >= SETTLE_TIMEOUT) {
            settleWaited = 0;
            return true;
        }
        return false;
    }

    private static String waitedNote() {
        return String.format(" [after %d client / %d server ticks, carrier pitch %.1f]",
                ticks - legStart, heldServerTicks, aimedPitch);
    }

    private static void verdict() {
        Mob base = rig.base();
        NOTES.add(String.format("carrier xRot at the end %.1f, pitch mode %s/%s",
                base.getXRot(), RigHeadYaw.PITCH_MODE, RigHeadYaw.PITCH_STEP));
        if (level.f() < 0 || above.f() < 0 || below.f() < 0) {
            finish(false, "a frame had no head or face to measure -- a camera fault, not a finding");
            return;
        }
        double dAbove = above.f() - level.f();
        double dBelow = below.f() - level.f();
        NOTES.add(String.format("face position in the head, level %.3f; above %+.3f; below %+.3f",
                level.f(), dAbove, dBelow));
        // The tolerance is the level frame's own measurement grain: one pixel row of the head.
        double grain = 1.0 / Math.max(1, level.headRows());
        NOTES.add(String.format("grain (one head row) %.3f", grain));
        if (Math.abs(dAbove) > 0.10) {
            BAD.add(String.format("from ABOVE the face sits %+.3f of the head away from where it sits "
                    + "face-on (the head did not tilt up to the camera)", dAbove));
        }
        if (Math.abs(dBelow) > 0.10) {
            BAD.add(String.format("from BELOW the face sits %+.3f of the head away from where it sits "
                    + "face-on (the head did not tilt down to the camera)", dBelow));
        }
        finish(BAD.isEmpty(), String.join("; ", BAD));
    }

    // ------------------------------------------------------------------------------ instrument

    /**
     * The head's silhouette (hair + skin rows above the top of the blue body) and where the face
     * centre sits inside it, as a fraction from the top. -1 when there is no head or no face.
     */
    record Frame(int headTop, int headBottom, int face, double faceY) {
        int headRows() {
            return headBottom - headTop + 1;
        }

        double f() {
            if (headTop < 0 || face == 0 || headBottom <= headTop) {
                return -1;
            }
            return (faceY - headTop) / (double) (headBottom - headTop);
        }

        static Frame of(int[][] px) {
            int h = px.length;
            int w = px[0].length;
            // The body's top: the first row, from the top, with a run of overall-blue pixels.
            int bodyTop = h;
            for (int y = 0; y < h && bodyTop == h; y++) {
                int blue = 0;
                for (int x = w / 4; x < 3 * w / 4; x++) {
                    if (isBody(px[y][x])) {
                        blue++;
                    }
                }
                if (blue >= 6) {
                    bodyTop = y;
                }
            }
            int top = -1;
            int bottom = -1;
            int face = 0;
            double fy = 0;
            for (int y = 0; y < bodyTop; y++) {
                boolean headRow = false;
                for (int x = w / 4; x < 3 * w / 4; x++) {
                    int c = px[y][x];
                    if (VanillaHairClientTest.Census.isFace(c)) {
                        face++;
                        fy += y;
                        headRow = true;
                    } else if (VanillaHairClientTest.Census.isHair(c)
                            || VanillaHairClientTest.Census.isSkin(c)) {
                        headRow = true;
                    }
                }
                if (headRow) {
                    if (top < 0) {
                        top = y;
                    }
                    bottom = y;
                }
            }
            return new Frame(top, bottom, face, face == 0 ? -1 : fy / face);
        }

        private static boolean isBody(int c) {
            int r = (c >> 16) & 0xFF;
            int g = (c >> 8) & 0xFF;
            int b = c & 0xFF;
            return b > 60 && b > 1.6 * r && b > 1.2 * g && r < 90;
        }

        @Override
        public String toString() {
            return String.format("head rows %d..%d, face %d px at y %.0f, f=%.3f",
                    headTop, headBottom, face, faceY, f());
        }
    }

    // ------------------------------------------------------------------------------- the stage

    private static void prepare(Minecraft mc) {
        if (fired) {
            return;
        }
        fired = true;
        key = "vpitch_copy_of_me";
        parts = SculptCreatureTool.withKits(CreatureSculpt.parseParts(
                JsonParser.parseString(CopyOfMeFixture.JSON).getAsJsonArray()), new JsonObject());
        try {
            CreatureFiles.write(key, parts, "{}");
        } catch (Exception e) {
            finish(false, "creature write failed: " + e);
            return;
        }
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
        server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
            if (p == null) {
                return;
            }
            ServerLevel level = p.level();
            var extents = CreatureAnatomy.extents(RigGeometry.of(parts));
            var clips = ModelRepository.animations(key);
            // Yaw 180 faces -Z: TOWARD the camera, which stands at lower Z looking +Z.
            rig = DisplayRig.spawn(level, new Vec3(X, FOOT_Y, STAGE_Z), 180f, parts, 1.0,
                    CreatureAnatomy.widthBlocks(extents), CreatureAnatomy.heightBlocks(extents),
                    null);
            Mob base = rig.base();
            base.setNoAi(true);
            base.setInvulnerable(true);
            base.setSilent(true);
            base.setYHeadRot(180f);
            base.setYBodyRot(180f);
            VanillaRigAnimator.drive(rig, com.moddingfromamod.vanilla.RigFit.intoRigSpace(parts), clips);
            NOTES.add("driving " + rig.displays().size() + " displays; pitch mode "
                    + RigHeadYaw.PITCH_MODE + "/" + RigHeadYaw.PITCH_STEP);
        });
        go(Phase.SETTLE);
    }

    /**
     * The carrier's own LookControl on the camera's eye, at the start of EVERY SERVER tick — before the
     * rig animator reads the pitch (ServerTickEvent.Post), the way a real mob's LookAtPlayerGoal runs in
     * its own serverAiStep.
     *
     * <p>this used to queue one look per CLIENT tick with {@code server.execute}, and the rig's
     * own per-tick move puts the carrier's pitch back to 0. Whenever the server ran several ticks between
     * two client ticks (catching up on a busy machine), the head was drawn from the zeroed ticks: the
     * failing run's carrier read pitch 0.0 at every hold, and "above" measured +0.170, a head that had
     * not tilted. A harness fault — a real creature's look runs inside its tick.
     */
    private static void look(Minecraft mc) {
        if (!aimRegistered) {
            aimRegistered = true;
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                    (net.neoforged.neoforge.event.tick.ServerTickEvent.Pre e) -> aim());
        }
    }

    private static boolean aimRegistered;

    /** Server thread, every tick before the animator: point the carrier's look at the camera's eye. */
    private static void aim() {
        DisplayRig r = rig;
        if (!ENABLED || r == null || phase == Phase.DONE) {
            return;
        }
        Mob base = r.base();
        if (base == null) {
            return;
        }
        base.getLookControl().setLookAt(X, EYES + camDy, STAGE_Z - STANDOFF);
        base.getLookControl().tick();
        aimedPitch = base.getXRot();   // as the animator will read it this tick
    }

    /** The carrier's pitch as the last server tick aimed it (the rig zeroes it again later that tick). */
    private static volatile float aimedPitch;

    private static void hold(Minecraft mc) {
        if (mc.player == null) {
            return;
        }
        mc.options.hideGui = true;
        mc.setScreen(null);
        double eye = EYES + camDy;
        double feet = eye - mc.player.getEyeHeight();
        // Aim the camera at the creature's eyes, so the head stays centred as the camera moves.
        float pitch = (float) Math.toDegrees(Math.atan2(eye - EYES, STANDOFF));
        mc.player.setPos(X, feet, STAGE_Z - STANDOFF);
        mc.player.setYRot(0f);
        mc.player.setXRot(pitch);
        mc.player.xOld = X;
        mc.player.yOld = feet;
        mc.player.zOld = STAGE_Z - STANDOFF;
        mc.player.yRotO = 0f;
        mc.player.xRotO = pitch;
        mc.player.getAbilities().flying = true;
    }

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
            var floor = net.minecraft.world.level.block.Blocks.WHITE_CONCRETE.defaultBlockState();
            var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            for (int x = (int) X - 12; x <= (int) X + 12; x++) {
                for (int z = (int) STAGE_Z - 12; z <= (int) STAGE_Z + 12; z++) {
                    level.setBlock(new BlockPos(x, GROUND_Y, z), floor, 3);
                }
            }
            // The pit the camera drops into, so "below" is not a camera inside a block.
            int cz = (int) Math.floor(STAGE_Z - STANDOFF);
            for (int x = (int) X - 1; x <= (int) X + 1; x++) {
                for (int z = cz - 1; z <= cz + 1; z++) {
                    for (int y = GROUND_Y - 3; y <= GROUND_Y; y++) {
                        level.setBlock(new BlockPos(x, y, z), air, 3);
                    }
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
                level.setDayTime(6000L);
                level.setWeatherParameters(6000, 0, false, false);
            }
            server.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT)
                    .set(false, server);
            server.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_WEATHER_CYCLE)
                    .set(false, server);
        });
    }

    private static int[][] grab(Minecraft mc, String name) {
        try (var img = Screenshots.take(mc.getMainRenderTarget())) {
            int w = img.getWidth();
            int h = img.getHeight();
            int[][] px = new int[h][w];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int abgr = com.moddingfromamod.compat.Pixels.abgr(img, x, y);
                    px[y][x] = ((abgr & 0xFF) << 16) | (abgr & 0xFF00) | ((abgr >> 16) & 0xFF);
                }
            }
            try {
                java.nio.file.Files.createDirectories(SIG);
                img.writeToFile(SIG.resolve(name + ".png"));
            } catch (Exception ignored) {
                // the pixels carry the verdict
            }
            return px;
        }
    }

    private static void writeFilm(String label) {
        if (filmStart == 0L) {
            return;
        }
        try {
            Path windows = SIG.resolve("film-windows.tsv");
            java.nio.file.Files.createDirectories(windows.getParent());
            java.nio.file.Files.writeString(windows,
                    filmStart + "\t" + System.currentTimeMillis() + "\t" + label + "\n",
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // no recorder attached
        }
        filmStart = 0L;
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
