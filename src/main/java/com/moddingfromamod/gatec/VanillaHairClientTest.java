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
import com.moddingfromamod.vanilla.VanillaRigAnimator;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * <b>Gate C — a "copy of me" turns to look at the player standing BEHIND it.</b>
 *
 * <p>a tester's report, reproduced as a take: a humanoid with a big hair box over the top and back
 * of its head, facing away from the camera; the carrier's own vanilla {@code LookControl} is then
 * pointed at the camera every tick, exactly as {@code LookAtPlayerGoal} would point it, and the
 * shipped driver ({@link VanillaRigAnimator}) does everything else. The creature is built through
 * {@link SculptCreatureTool#withKits}, i.e. what the sculpt tool really saves, from parts whose hair
 * is a ROOT — the shape that turns the face and leaves the hair behind.
 *
 * <h2>Three oracles, each FALSE of the bug</h2>
 *
 * <p>The references are PHOTOGRAPHED, not tuned: before the turn the camera is walked round to the
 * creature's front and counts what a correct face-on view shows. After the turn, from behind:
 * <ol>
 *   <li><b>the face is skin, not hair</b> — inside the bounding box of the eyes and mouth, at most
 *       a tenth as many hair pixels as face-on skin pixels, and at least half the skin. The bug
 *       puts the face INSIDE the back of the hair box, the eyes z-fighting through the brown (the
 *       tester's frames); counting face pixels alone passed on it, 462 of 519.</li>
 *   <li><b>the hair is still above the face</b> — the brown centroid is higher on screen than the
 *       face centroid, as it is face-on. That is the issue's "stays on the same side of the
 *       face".</li>
 *   <li><b>the body came round too</b> — the yellow button on the chest is visible. Vanilla keeps
 *       a standing mob's body within 75 degrees of its head by turning the body
 *       ({@code BodyRotationControl}); a rig that pins the body to a stale {@code yRot} leaves it
 *       facing away while the head does a half turn.</li>
 * </ol>
 * "The head turned" and "pixels changed" are true of the bug and are deliberately not oracles
 * ({@code docs/VANILLA_RIG_ANIMATION_LADDER.md}'s playbook).
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class VanillaHairClientTest {

    private VanillaHairClientTest() {
    }

    private static final String P = ModdingFromAModMod.MODID + ".";

    private static final boolean ENABLED =
            "vanillahair".equalsIgnoreCase(System.getProperty(P + "testmode", ""));

    private static final Path SIG = Path.of(
            System.getProperty(P + "sigdir", "/tmp/moddingfromamod-vanillahair"));

    /** The live shape — see {@link CopyOfMeFixture}. */
    static final String COPY_OF_ME = CopyOfMeFixture.JSON;

    private enum Phase { WAIT_TITLE, WAIT_INGAME, EMPTY, PREPARE, RELOAD, SPAWN, SETTLE,
                         FRONT, BACK, LOOK, DONE }

    private static Phase phase = Phase.WAIT_TITLE;
    private static int ticks;
    private static boolean fired;
    private static boolean reloadDone;
    private static boolean groundLaid;
    private static String key;
    private static DisplayRig rig;
    private static List<CreatureSculpt.Part> parts;
    private static long filmStart;

    private static final List<String> NOTES = new ArrayList<>();
    private static final List<String> BAD = new ArrayList<>();

    private static final double X = 8.5;
    private static final double FOOT_Y = 95.0;
    private static final int GROUND_Y = (int) FOOT_Y - 1;
    private static final double STAGE_Z = 20.5;
    private static final double STANDOFF = 4.0;
    private static final double EYE_Y = FOOT_Y + 1.0;
    private static final float PITCH = 8f;

    private static Census front;
    private static Census back;

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
                    if (ClientHarness.bootstrapWorld(mc, "vanilla-hair", ticks, true)) {
                        go(Phase.EMPTY);
                    }
                }
                case EMPTY -> {
                    if (ClientHarness.inWorld(mc) && ticks > ClientHarness.INGAME_SETTLE) {
                        holdBehind(mc);
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
                    holdBehind(mc);
                    if (ticks > 10) {
                        spawn(mc);
                    }
                }
                case SETTLE -> {
                    holdBehind(mc);
                    if (ticks > 40) {
                        go(Phase.FRONT);
                    }
                }
                case FRONT -> {
                    holdInFront(mc);
                    if (ticks == 30) {
                        front = Census.of(grab(mc, "1-front-rest"));
                        NOTES.add("front (reference): " + front);
                        go(Phase.BACK);
                    }
                }
                case BACK -> {
                    holdBehind(mc);
                    if (ticks == 30) {
                        back = Census.of(grab(mc, "2-back-rest"));
                        NOTES.add("back, at rest: " + back);
                        filmStart = System.currentTimeMillis();
                        go(Phase.LOOK);
                    }
                }
                case LOOK -> look(mc);
                default -> { }
            }
        } catch (Exception e) {
            finish(false, "threw " + e);
        }
    }

    private static void prepare(Minecraft mc) {
        if (fired) {
            return;
        }
        fired = true;
        key = "vhair_copy_of_me";
        // What the sculpt tool SAVES, not the raw list: every assembly step runs here, so this
        // take measures the generator as well as the rig.
        parts = SculptCreatureTool.withKits(CreatureSculpt.parseParts(
                JsonParser.parseString(COPY_OF_ME).getAsJsonArray()), new JsonObject());
        StringBuilder parents = new StringBuilder();
        for (CreatureSculpt.Part p : parts) {
            if (p.name().startsWith("hair")) {
                parents.append(p.name()).append("->").append(p.parent()).append(' ');
            }
        }
        NOTES.add("as saved: " + parents.toString().trim());
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
            // Yaw 0 faces +Z: AWAY from the camera, which stands at lower Z looking +Z.
            rig = DisplayRig.spawn(level, new Vec3(X, FOOT_Y, STAGE_Z), 0f, parts, 1.0,
                    CreatureAnatomy.widthBlocks(extents), CreatureAnatomy.heightBlocks(extents),
                    null);
            Mob base = rig.base();
            base.setNoAi(true);
            base.setInvulnerable(true);
            base.setSilent(true);
            base.setYHeadRot(0f);
            base.setYBodyRot(0f);
            VanillaRigAnimator.drive(rig, com.moddingfromamod.vanilla.RigFit.intoRigSpace(parts), clips);
            NOTES.add("driving " + rig.displays().size() + " displays; clips: " + clips.keySet());
        });
        go(Phase.SETTLE);
    }

    /** The carrier's own LookControl, aimed at the camera, every tick — LookAtPlayerGoal's job. */
    private static void look(Minecraft mc) {
        holdBehind(mc);
        var server = mc.getSingleplayerServer();
        if (server == null || rig == null) {
            return;
        }
        Mob base = rig.base();
        if (ticks <= 120) {
            server.execute(() -> {
                base.getLookControl().setLookAt(X, EYE_Y + 0.6, STAGE_Z - STANDOFF);
                base.getLookControl().tick();
            });
            if (ticks % 20 == 0) {
                Census c = Census.of(grab(mc, String.format("3-look-t%03d", ticks)));
                NOTES.add(String.format("t=%d yHeadRot=%.1f yBodyRot=%.1f yRot=%.1f %s", ticks,
                        base.getYHeadRot(), base.yBodyRot, base.getYRot(), c));
            }
            return;
        }
        Census end = Census.of(grab(mc, "4-looked"));
        NOTES.add("after the turn, from behind: " + end);
        writeFilm("look");
        if (front.face() < 40) {
            BAD.add("the face-on REFERENCE shows only " + front.face() + " face px -- a camera "
                    + "fault, not a finding");
        }
        if (end.face() * 2 < front.face()) {
            BAD.add("the face is hidden: " + end.face() + " face px against " + front.face()
                    + " face-on");
        }
        // A tenth, not zero: the idle clip sways the head a couple of degrees, and a frame that
        // catches it puts the fringe's lower edge on the eyes' top row (16 of 408, measured).
        // The same rule the stock-client reference was measured with.
        if (front.skinInFace() < 100 || front.hairInFace() * 10 > front.skinInFace()) {
            BAD.add("the face-on REFERENCE does not show a skin face (" + front + ") -- a camera "
                    + "or classifier fault, not a finding");
        }
        if (end.hairInFace() * 10 > front.skinInFace() || end.skinInFace() * 2 < front.skinInFace()) {
            BAD.add("the hair is over the face: inside the face's own box " + end.hairInFace()
                    + " hair px and " + end.skinInFace() + " skin px, against 0 hair and "
                    + front.skinInFace() + " skin face-on");
        }
        if (end.face() > 0 && end.brown() > 0 && !(end.brownY() < end.faceY())) {
            BAD.add(String.format("the hair is no longer above the face: hair centroid y=%.0f, "
                    + "face y=%.0f (face-on: %.0f above %.0f)", end.brownY(), end.faceY(),
                    front.brownY(), front.faceY()));
        }
        if (end.yellow() * 2 < front.yellow()) {
            BAD.add("the body did not come round: " + end.yellow() + " chest-button px against "
                    + front.yellow() + " face-on -- the body is pinned while the head turns");
        }
        finish(BAD.isEmpty(), String.join("; ", BAD));
    }

    // ------------------------------------------------------------------------------ instrument

    /**
     * Colour classes in one frame. {@code hairInFace} / {@code skinInFace} count hair-brown and
     * skin pixels inside the bounding box of the face pixels themselves (eyes + mouth): face-on
     * that box is skin with ZERO hair, and a tester's bug fills it with the back of the hair box,
     * the eyes z-fighting through it — which is also why the face COUNT alone is no oracle (it read
     * 462 of 519 on the bug, measured).
     */
    record Census(int face, int brown, int yellow, double faceY, double brownY,
                  int hairInFace, int skinInFace) {
        static Census of(int[][] px) {
            int face = 0;
            int brown = 0;
            int yellow = 0;
            double fy = 0;
            double by = 0;
            int x0 = Integer.MAX_VALUE;
            int y0 = Integer.MAX_VALUE;
            int x1 = -1;
            int y1 = -1;
            for (int y = 0; y < px.length; y++) {
                for (int x = 0; x < px[0].length; x++) {
                    int c = px[y][x];
                    if (isFace(c)) {
                        face++;
                        fy += y;
                        x0 = Math.min(x0, x);
                        y0 = Math.min(y0, y);
                        x1 = Math.max(x1, x);
                        y1 = Math.max(y1, y);
                    } else if (isHair(c)) {
                        brown++;
                        by += y;
                    } else if (isButton(c)) {
                        yellow++;
                    }
                }
            }
            int hairIn = 0;
            int skinIn = 0;
            for (int y = Math.max(0, y0); y <= y1; y++) {
                for (int x = Math.max(0, x0); x <= x1; x++) {
                    if (isHair(px[y][x])) {
                        hairIn++;
                    } else if (isSkin(px[y][x])) {
                        skinIn++;
                    }
                }
            }
            return new Census(face, brown, yellow, face == 0 ? -1 : fy / face,
                    brown == 0 ? -1 : by / brown, hairIn, skinIn);
        }

        private static int r(int c) {
            return (c >> 16) & 0xFF;
        }

        private static int g(int c) {
            return (c >> 8) & 0xFF;
        }

        private static int b(int c) {
            return c & 0xFF;
        }

        static boolean isFace(int c) {
            boolean red = r(c) > 80 && r(c) > 2.5 * g(c) && r(c) > 2.5 * b(c);
            boolean black = r(c) < 40 && g(c) < 40 && b(c) < 40;
            return red || black;
        }

        static boolean isHair(int c) {
            return r(c) > 40 && r(c) > 1.5 * g(c) && g(c) > 1.5 * b(c) && r(c) < 2.5 * g(c);
        }

        static boolean isSkin(int c) {
            return r(c) > 120 && r(c) > 1.15 * g(c) && r(c) < 2.0 * g(c)
                    && g(c) > 0.9 * b(c) && g(c) < 1.4 * b(c);
        }

        static boolean isButton(int c) {
            return r(c) > 120 && g(c) > 0.7 * r(c) && b(c) < 0.35 * g(c);
        }

        @Override
        public String toString() {
            return String.format("face=%d (y %.0f) hair=%d (y %.0f) button=%d "
                            + "in-face-box: hair=%d skin=%d",
                    face, faceY, brown, brownY, yellow, hairInFace, skinInFace);
        }
    }

    // ------------------------------------------------------------------------------- the stage

    private static void holdBehind(Minecraft mc) {
        hold(mc, STAGE_Z - STANDOFF, 0f);
    }

    private static void holdInFront(Minecraft mc) {
        hold(mc, STAGE_Z + STANDOFF, 180f);
    }

    private static void hold(Minecraft mc, double z, float yaw) {
        if (mc.player == null) {
            return;
        }
        mc.options.hideGui = true;
        mc.setScreen(null);
        mc.player.setPos(X, EYE_Y, z);
        mc.player.setYRot(yaw);
        mc.player.setXRot(PITCH);
        mc.player.xOld = X;
        mc.player.yOld = EYE_Y;
        mc.player.zOld = z;
        mc.player.yRotO = yaw;
        mc.player.xRotO = PITCH;
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
            for (int x = (int) X - 12; x <= (int) X + 12; x++) {
                for (int z = (int) STAGE_Z - 12; z <= (int) STAGE_Z + 12; z++) {
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
                level.setDayTime(6000L);
                level.setWeatherParameters(6000, 0, false, false);
            }
            server.overworld().getGameRules().set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME,false, server);
            server.overworld().getGameRules().set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_WEATHER,false, server);
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
