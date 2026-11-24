package com.moddingfromamod.gatec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.runtime.AnimationState;
import com.moddingfromamod.runtime.BedrockAnimation;
import com.moddingfromamod.runtime.CreatureAnatomy;
import com.moddingfromamod.runtime.CreatureFiles;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.ModelRepository;
import com.moddingfromamod.vanilla.DisplayRig;
import com.moddingfromamod.vanilla.RigGeometry;
import com.moddingfromamod.vanilla.RigCarrier;
import com.moddingfromamod.vanilla.RigTransform;
import com.moddingfromamod.vanilla.SpikeFixture;
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
 * <b>Gate C for tier 5 — the four clips, driven by {@link VanillaRigAnimator} rather than by this
 * harness.</b>
 *
 * <p>Every earlier animation run in this spike posed the rig by hand: the harness computed a frame
 * and called {@code RigDriver.animate}. That measures the <em>renderer</em> and says nothing about
 * whether a creature in a game ever moves. This one spawns a rig, hands it to the real driver, and
 * then only ever changes what the CARRIER is doing — walking it, swinging it, turning its head.
 * Everything the creature does after that is the shipped path.
 *
 * <h2>Why the oracles are mostly server-side</h2>
 *
 * <p>The interesting claim of tier 5 is <b>which clip is selected and from what</b>, and that is a
 * fact about numbers rather than pixels. A pixel oracle can tell you something changed; it cannot
 * tell you the change came from {@code walkAnimation.speed()} rather than from the creature drifting.
 * So the clip selection is asserted directly and the film is the evidence that it reaches a screen.
 *
 * <p><b>The one pixel oracle that earns its place is idle</b>, because "a creature standing still is
 * animating" cannot be told from "a creature standing still" any other way — and a frozen rig is
 * exactly what the whole of tier 5 exists to prevent.
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class VanillaClipsClientTest {

    private VanillaClipsClientTest() {
    }

    private static final String P = ModdingFromAModMod.MODID + ".";

    private static final boolean ENABLED =
            "vanillaclips".equalsIgnoreCase(System.getProperty(P + "testmode", ""));

    private static final Path SIG = Path.of(
            System.getProperty(P + "sigdir", "/tmp/moddingfromamod-vanillaclips"));

    private static final String CASE = System.getProperty(P + "cases", "thornshell");

    /** the game's tick rate while filming (5 = 4x slow motion, sped back up to ~60 fps); 20 changes nothing. */
    private static final int FILM_TICK_RATE = Integer.getInteger(P + "filmtickrate", 20);

    /** the creature's yaw. 180 is every earlier run; 90 stands it side on to the camera, so an arm's arc reads. */
    private static final float CLIPS_YAW = Float.parseFloat(System.getProperty(P + "clipsyaw", "180"));

    /** set only when {@code clipsyaw} is given, so an unset run is byte-for-byte the old one. */
    private static final boolean PIN_YAW = System.getProperty(P + "clipsyaw") != null;

    private enum Phase { WAIT_TITLE, WAIT_INGAME, EMPTY, PREPARE, RELOAD, SPAWN, SETTLE,
                         IDLE, WALK, RUN, ATTACK, LOOK, DONE }

    private static Phase phase = Phase.WAIT_TITLE;
    private static int ticks;
    private static boolean fired;
    private static boolean reloadDone;
    private static boolean groundLaid;
    private static String failLater;
    private static String key;
    private static DisplayRig rig;
    private static long filmStart;

    private static final List<String> NOTES = new ArrayList<>();
    private static final Map<String, String> RESULTS = new LinkedHashMap<>();
    private static final List<String> BAD = new ArrayList<>();

    private static final double CAM_X = 8.5;
    /** the camera's height and pitch, knobs so a close side-on take frames a small creature (defaults unchanged). */
    private static final double CAM_Y = Double.parseDouble(System.getProperty(P + "camy", "100.0"));
    private static final double CAM_Z = 8.5;
    /** a narrower field of view (degrees) zooms in on a small creature; 0 leaves the player's own. */
    private static final int CAM_FOV = Integer.getInteger(P + "camfov", 0);
    private static final float CAM_PITCH = Float.parseFloat(System.getProperty(P + "campitch", "8"));
    /**
     * <b>How far the subject stands from the camera, in blocks. Default 20 — the framing every
     * earlier run of this fixture used, so nothing moves unless a caller asks.</b>
     *
     * <p>It is a knob because a FILM has a framing requirement a pixel-count does not.
     * the atom rig's design notes asks this fixture to show whether Spikor's six
     * {@code kit_horn2x} boxes and {@code hair2} turn with his skull — the seven displays §5.7.2
     * says head-follow used to leave behind — and at 20 blocks the whole creature is 65 px wide
     * and a horn is two of them. A film that cannot resolve the thing it is filming is
     * the camera notes' subject, not evidence.
     */
    private static final double STAGE_Z = CAM_Z
            + Double.parseDouble(System.getProperty(P + "standoff", "20.0"));
    private static final double FOOT_Y = 95.0;
    private static final int GROUND_Y = (int) FOOT_Y - 1;

    private static int[][] idleA;

    /**
     * the idle row samples ONE WHOLE IDLE LOOP, not two frames. The sculpted idle clip is
     * {@code animation_length 3.0} s (CreatureFiles) = 60 ticks, and this row used to compare tick 20
     * with tick 70: 50 ticks, ten ticks short of a loop, so on the loop's steep way BACK to the first
     * pose. Measured 2026-10-23, six runs, every 5 ticks: the curve is the same every run, peaking at
     * 359-368 px and returning to 0-4 at 60 ticks, and the 50-tick point read 145-173 against a floor
     * of 150 — so a few ticks of client lag slid it under (30-115 on a loaded container). The
     * row now samples the whole loop and judges the farthest pose from the first; a frozen rig is
     * still ~0 everywhere, so it keeps its teeth.
     */
    static final int IDLE_LOOP_TICKS = 60;
    static final int IDLE_STEP_TICKS = 5;
    private static final List<Integer> IDLE_CURVE = new java.util.ArrayList<>();

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
                    if (ClientHarness.bootstrapWorld(mc, "vanilla-clips", ticks, true)) {
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
                    if (reloadDone && mc.gui.overlay() == null && ticks > 20) {
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
                    if (ticks == 2 && FILM_TICK_RATE != 20) {
                        mc.player.connection.sendCommand("tick rate " + FILM_TICK_RATE);
                    }
                    if (ticks > 40) {
                        filmStart = System.currentTimeMillis();
                        go(Phase.IDLE);
                    }
                }
                case IDLE -> idle(mc);
                case WALK -> locomote(mc, 0.06, Phase.RUN, "walk");
                case RUN -> locomote(mc, 0.30, Phase.ATTACK, "run");
                case ATTACK -> attack(mc);
                case LOOK -> look(mc);
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
        key = "vclips_" + RigCarrier.safe(CASE);
        List<CreatureSculpt.Part> parts = SpikeFixture.parts(CASE);
        try {
            Path spec = SpikeFixture.find(SpikeFixture.CORPUS_DIR + "/" + CASE + "/sculpt.json");
            CreatureFiles.write(key, parts, spec == null ? "{}"
                    : java.nio.file.Files.readString(spec));
        } catch (Exception e) {
            finish(false, "creature write failed: " + e);
            return;
        }
        // (the pack-rig arm's per-creature pack write + select was deleted by an earlier change)
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
        List<CreatureSculpt.Part> parts = SpikeFixture.parts(CASE);
        double scale = SpikeFixture.scale(CASE);
        server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
            if (p == null) {
                return;
            }
            ServerLevel level = p.level();
            var extents = CreatureAnatomy.extents(RigGeometry.of(parts));
            var clips = ModelRepository.animations(key);
            rig = DisplayRig.spawn(level, new Vec3(CAM_X, FOOT_Y, STAGE_Z), CLIPS_YAW, parts, scale,
                    CreatureAnatomy.widthBlocks(extents) * scale,
                    CreatureAnatomy.heightBlocks(extents) * scale, null);
            rig.base().setNoAi(true);
            rig.base().setPermanentlyInvulnerable(true);
            rig.base().setSilent(true);
            // 🔴 THE REAL DRIVER, not this harness. Everything the creature does from here is the
            // shipped path; the test only ever changes what the CARRIER is doing.
            VanillaRigAnimator.drive(rig, com.moddingfromamod.vanilla.RigFit.intoRigSpace(parts), clips);
            NOTES.add("driving " + rig.displays().size() + " displays; clips: " + clips.keySet());
        });
        go(Phase.SETTLE);
    }

    // -------------------------------------------------------------------------------- the rows

    /** 5.1 — a creature doing NOTHING must still be moving, or the driver is not running. */
    private static void idle(Minecraft mc) {
        hold(mc);
        var server = mc.getSingleplayerServer();
        if (server == null || rig == null) {
            return;
        }
        if (ticks == 20) {
            idleA = grab(mc, "1-idle-a");
            IDLE_CURVE.clear();
            return;
        }
        if (ticks < 20 || (ticks - 20) % IDLE_STEP_TICKS != 0) {
            return;
        }
        int since = ticks - 20;
        IDLE_CURVE.add(changed(idleA, grab(mc, since == 50 ? "2-idle-b" : null)));
        if (since < IDLE_LOOP_TICKS) {
            return;
        }
        int moved = java.util.Collections.max(IDLE_CURVE);
        int at50 = IDLE_CURVE.get(50 / IDLE_STEP_TICKS - 1);     // what the old two-frame row read
        // The clip the driver would pick right now, from the carrier's own numbers.
        Mob base = rig.base();
        var sel = AnimationState.select(false, 0f, base.walkAnimation.speed(),
                base.walkAnimation.position(), base.tickCount / 20f);
        RESULTS.put("5.1 idle", "clip=" + sel.clip() + ", at most " + moved
                + " px changed over one " + IDLE_LOOP_TICKS + "-tick idle loop while standing still"
                + " (every " + IDLE_STEP_TICKS + " ticks: " + IDLE_CURVE + "; the old 50-tick pair read "
                + at50 + ")");
        if (sel.clip() != BedrockAnimation.Clip.IDLE) {
            BAD.add("a stationary creature selected " + sel.clip() + " rather than IDLE");
        }
        if (moved < 150) {
            BAD.add("a standing creature moved at most " + moved + " px over a whole "
                    + IDLE_LOOP_TICKS + "-tick idle loop -- the idle clip is not reaching the rig, "
                    + "which looks exactly like a creature with no idle");
        }
        writeFilm("idle");
        filmStart = System.currentTimeMillis();
        go(Phase.WALK);
    }

    /** 5.4 — walk and run are the SAME clip at two speeds; the speed is what selects it. */
    private static void locomote(Minecraft mc, double perTick, Phase next, String label) {
        hold(mc);
        var server = mc.getSingleplayerServer();
        if (server == null || rig == null) {
            return;
        }
        Mob base = rig.base();
        server.execute(() -> {
            // Move the CARRIER. `walkAnimation` updates itself from real movement, which is the
            // point — a harness that set the animation state directly would be driving the very
            // number under test.
            base.setPos(base.getX() + perTick, base.getY(), base.getZ());
            base.walkAnimation.update((float) perTick * 4f, 1f, base.isBaby() ? 3f : 1f);
        });
        if (ticks < 60) {
            return;
        }
        var sel = AnimationState.select(false, 0f, base.walkAnimation.speed(),
                base.walkAnimation.position(), base.tickCount / 20f);
        grab(mc, ("walk".equals(label) ? "3-walk" : "4-run"));
        RESULTS.put("5.4 " + label, "clip=" + sel.clip()
                + String.format(" speed=%.3f position=%.2f",
                        base.walkAnimation.speed(), base.walkAnimation.position()));
        if (sel.clip() != BedrockAnimation.Clip.WALK) {
            BAD.add("a creature moving at " + perTick + " blocks/tick selected " + sel.clip()
                    + " rather than WALK");
        }
        if ("run".equals(label)) {
            RESULTS.put("5.4 run vs walk", "the same clip, advanced by position -- run is not a "
                    + "second clip and needs no code");
        }
        writeFilm(label);
        filmStart = System.currentTimeMillis();
        go(next);
    }

    /** 5.2 — progress, not seconds, and it resets per swing. */
    private static void attack(Minecraft mc) {
        hold(mc);
        var server = mc.getSingleplayerServer();
        if (server == null || rig == null) {
            return;
        }
        Mob base = rig.base();
        // 🔴 SWING AND SAMPLE ON THE SERVER THREAD, both of them.
        //
        // `attackAnim` is a plain non-volatile float that the SERVER tick writes, and the first
        // cut read it from the CLIENT tick — a cross-thread read with no happens-before, on a
        // value that exists for six ticks and then returns to zero. It reported a peak of exactly
        // 0.00 while every other row on the page measured fine, which reads as "the attack clip is
        // not wired up" and is really "the reading was taken from the wrong thread".
        //
        // Re-swinging every few ticks as well, because one swing is 6 ticks out of 40 and a single
        // fire that happened to land in a gap is indistinguishable from a swing that never fired.
        if (ticks == 1) {
            // Back on its mark, for the reason spelt out in `look` — the two locomotion phases
            // walk the carrier +21.6 blocks along X, so every take after them is framed by how far
            // they travelled rather than by the camera. The attack take reads 0 px when the
            // subject has left the frame, which is a camera fault wearing an animation fault's
            // clothes.
            server.execute(() -> {
                base.setPos(CAM_X, FOOT_Y, STAGE_Z);
                com.moddingfromamod.vanilla.RigDriver.follow(rig, base.position(), base.getYRot());
            });
            return;
        }
        if (PIN_YAW) {
            // hold the heading through the swing, so a side-on take stays side on.
            server.execute(() -> {
                base.setYRot(CLIPS_YAW);
                base.setYBodyRot(CLIPS_YAW);
                base.setYHeadRot(CLIPS_YAW);
            });
        }
        if (ticks == 2) {
            attackBefore = grab(mc, "5a-attack-before");
        }
        if (ticks % 8 == 3 && ticks < 33) {
            server.execute(() -> com.moddingfromamod.compat.Swings.swing(base, net.minecraft.world.InteractionHand.MAIN_HAND, true));
        }
        server.execute(() -> {
            float p = base.getSwingAnimation(1.0f);
            if (p > peakAttack) {
                peakAttack = p;
            }
            if (base.isSwinging()) {
                sawSwinging = true;
            }
        });
        if (ticks < 41) {
            return;
        }
        grab(mc, "5-attack");
        // 🔴 VANILLA'S OWN NUMBER IS ZERO HERE, ALWAYS, AND THAT IS NOT THIS RIG'S FAULT.
        //
        // `updateSwingTime()` — the only thing that advances `attackAnim` — is called from
        // `Monster.aiStep()`, `Player.serverAiStep()` and `RemotePlayer`, and nowhere else in the
        // game. `WishMobEntity extends PathfinderMob`, and a rig carrier may not be a `Monster` at
        // all (Peaceful deletes one — RigBase). So `getAttackAnim` is structurally 0 on BOTH
        // paths, and an attack clip driven by it is selected correctly and frozen on frame one.
        //
        // So this row asserts two different things: that vanilla's number really is dead (the
        // finding), and that the rig animates anyway because VanillaRigAnimator keeps its own
        // swing clock (the improvement).
        RESULTS.put("5.2 vanilla getAttackAnim", String.format("peak %.2f over the swing "
                + "(swinging seen: %s) -- 0 is CORRECT: only Monster advances it, and neither "
                + "creature path is one", peakAttack, sawSwinging));
        if (!sawSwinging) {
            BAD.add("the swing never even set `swinging` -- the test could not start an attack");
        }
        if (peakAttack > 0f) {
            BAD.add("getAttackAnim advanced to " + peakAttack + " on a non-Monster carrier. That "
                    + "contradicts the bytecode this row is built on -- re-read who calls "
                    + "updateSwingTime before trusting anything else here");
        }
        int moved = changed(attackBefore, grab(mc, "5b-attack-mid"));
        var sel = AnimationState.select(true, 0.5f, 0f, 0f, base.tickCount / 20f);
        RESULTS.put("5.2 attack", "clip=" + sel.clip() + ", " + moved
                + " px changed across the swing window with our own clock driving it");
        if (sel.clip() != BedrockAnimation.Clip.ATTACK) {
            BAD.add("a swinging creature selected " + sel.clip() + " rather than ATTACK");
        }
        if (moved < 150) {
            BAD.add("only " + moved + " px changed across the swing -- the attack clip is not "
                    + "reaching the rig even with our own swing clock");
        }
        writeFilm("attack");
        filmStart = System.currentTimeMillis();
        go(Phase.LOOK);
    }

    private static volatile float peakAttack;
    private static int[][] attackBefore;
    private static volatile boolean sawSwinging;

    /** 5.3 — the head COMPOSES with the clip; it does not replace it. */
    private static void look(Minecraft mc) {
        hold(mc);
        var server = mc.getSingleplayerServer();
        if (server == null || rig == null) {
            return;
        }
        Mob base = rig.base();
        if (ticks == 1) {
            // 🔴 BACK ON ITS MARK FIRST. `locomote` walks the carrier +21.6 blocks along X across
            // the two locomotion phases (0.06 x 60, then 0.30 x 60), so by the time the head take
            // runs the subject's framing is a function of how far two EARLIER phases travelled.
            // At the default standoff that jams it against the left edge of the frame — the head
            // take that the atom rig's design notes asks for came back 65 px wide, which cannot show
            // whether a horn followed a skull — and at a close standoff it leaves the frame
            // altogether, which reads as "the head turn moved 0 px" rather than as a camera fault.
            // the camera notes' whole subject: a photograph of the wrong place passes every check
            // that only asks whether a file exists.
            server.execute(() -> {
                base.setPos(CAM_X, FOOT_Y, STAGE_Z);
                com.moddingfromamod.vanilla.RigDriver.follow(rig, base.position(), base.getYRot());
            });
            return;
        }
        if (ticks == 2) {
            headBefore = grab(mc, "6-head-straight");
        }
        if (ticks < 31) {
            float t = (ticks - 1) / 30f;
            server.execute(() -> {
                base.setYHeadRot(base.getYRot() + 55f * t);
                base.setXRot(-20f * t);
            });
            return;
        }
        if (ticks < 61) {
            return;
        }
        int[][] headAfter = grab(mc, "7-head-turned");
        int moved = changed(headBefore, headAfter);
        RESULTS.put("5.3 head look", moved + " px changed for a 55-degree turn; rig reports "
                + "headMoved=" + rig.headMoved(55f, -20f));
        if (moved < 100) {
            BAD.add("turning the head 55 degrees moved only " + moved + " px -- lookAt is not "
                    + "reaching the head display, or the clip is overwriting it");
        }
        writeFilm("head");
        for (var e : RESULTS.entrySet()) {
            NOTES.add(e.getKey() + ": " + e.getValue());
        }
        if (failLater != null) {
            BAD.add(failLater);
        }
        finish(BAD.isEmpty(), String.join("; ", BAD));
    }

    private static int[][] headBefore;

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
            for (int x = (int) CAM_X - 30; x <= (int) CAM_X + 30; x++) {
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
        if (CAM_FOV > 0 && mc.options.fov().get() != CAM_FOV) {
            mc.options.fov().set(CAM_FOV);
        }
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

    private static int changed(int[][] a, int[][] b) {
        if (a == null || b == null) {
            return -1;
        }
        int n = 0;
        for (int y = 0; y < Math.min(a.length, b.length); y++) {
            for (int x = 0; x < Math.min(a[0].length, b[0].length); x++) {
                if (delta(a[y][x], b[y][x]) > BODY_THRESHOLD) {
                    n++;
                }
            }
        }
        return n;
    }

    private static int delta(int a, int b) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF))
                + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                + Math.abs((a & 0xFF) - (b & 0xFF));
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
