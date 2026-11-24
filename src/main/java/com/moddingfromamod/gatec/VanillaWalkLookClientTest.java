package com.moddingfromamod.gatec;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.runtime.BedrockAnimation;
import com.moddingfromamod.runtime.CreatureAnatomy;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.vanilla.DisplayRig;
import com.moddingfromamod.vanilla.RigAnimator;
import com.moddingfromamod.vanilla.RigGeometry;
import com.moddingfromamod.vanilla.RigPlan;
import com.moddingfromamod.vanilla.VanillaRigAnimator;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * <b>Gate C for does a WALKING creature stay in one piece while its head follows you?</b>
 *
 * <p>A tester filmed the starter pet dragon in play and its head (eyes, horns) slid
 * against its body and wings while it walked. {@code vanillawalk} never saw it, because it drives
 * the rig by hand and never turns the head; {@code vanillaclips}' head take turns the head of a
 * creature standing still. This one drives the <b>shipping</b> path &mdash; the rig is handed to
 * {@link VanillaRigAnimator}, which poses it from the carrier exactly as it does on a server &mdash;
 * and only moves the CARRIER: round a circle, turning as it goes, its head tracking the camera.
 *
 * <h2>The oracle is a statement that is FALSE of a trailing head</h2>
 *
 * <p>A rig is N display entities, each drawn at its own interpolated position and yaw, then through
 * its own interpolated transform. It is RIGID exactly when every display is drawn with the same
 * carrier motion and the same tween clock. So, on the client, on every rendered frame, at the
 * frame's own partial tick &mdash; i.e. reading what the renderer reads &mdash; this measures:
 *
 * <ol>
 *   <li><b>position spread</b>: the largest distance between two displays' rendered positions. Every
 *       display is moved to the carrier's feet every tick, so a rigid rig has 0 here; a display that
 *       eases into each move over a different number of ticks than its neighbour trails it by
 *       {@code speed x lag} blocks, which is a tester's film;</li>
 *   <li><b>yaw spread</b>: the same for the rendered entity yaw, which a turn pulls apart;</li>
 *   <li><b>tween spread</b>: across every display whose transform tween is in flight, the largest
 *       difference in interpolation progress, when those tweens END on different PHASES of the pose
 *       beat. Two displays started on different beats are on two different curves, which is a head
 *       re-tweened every tick against a body re-tweened every third. (A pose eases over the beat plus a
 *       tick, so one re-posed now and one finishing the last beat's tween are both in flight on
 *       one schedule; only the phase tells them from an earlier change.)</li>
 * </ol>
 *
 * <p>None of the three is a magnitude that a trailing head also has ("it moved", "the head turned");
 * each is zero for a rigid rig and non-zero for the bug. The controls that make the zero mean
 * something are separate and are asserted: the creature travelled, it turned, the walk clip was
 * selected, and the head actually followed through a real range of angles &mdash; otherwise a rig
 * that never animated at all would be perfectly "rigid".
 *
 * <p>{@code rigsabotage=teleport} re-dresses every other display with a different
 * {@code teleport_duration} after spawn &mdash; this must go RED on a fixed tree, or the oracle is
 * blind. {@code rigmotion=N} rewrites every display to {@code N}, for choosing the value.
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class VanillaWalkLookClientTest {

    private VanillaWalkLookClientTest() {
    }

    private static final String P = ModdingFromAModMod.MODID + ".";

    private static final boolean ENABLED =
            "vanillawalklook".equalsIgnoreCase(System.getProperty(P + "testmode", ""));

    private static final Path SIG = Path.of(
            System.getProperty(P + "sigdir", "/tmp/moddingfromamod-vanillawalklook"));

    /** The starter species to walk. a tester's film was of this one. */
    private static final String SPECIES = System.getProperty(P + "species", "pet_dragon");

    private static final String SABOTAGE = System.getProperty(P + "rigsabotage", "off");

    private static final boolean STOPGO =
            "stopgo".equalsIgnoreCase(System.getProperty(P + "motion", "circle"));
    private static float heading;

    private static final boolean MARKERS =
            "on".equalsIgnoreCase(System.getProperty(P + "markers", "off"));

    /** -1 = leave whatever the rig chose. */
    private static final int MOTION =
            Integer.parseInt(System.getProperty(P + "rigmotion", "-1"));

    private static final int STAND_TICKS =
            Integer.parseInt(System.getProperty(P + "standticks", "80"));
    private static final int WALK_TICKS =
            Integer.parseInt(System.getProperty(P + "walkticks", "240"));

    /**
     * {@code walkdie=N}: kill the carrier at walk tick N, so the film shows the SHIPPED death (the flop and
     * the red flash VanillaRigAnimator drives from the carrier's deathTime) mid-stride, on whatever cadence and lag the
     * run sets. From then on the carrier is not pushed and the per-frame oracle stops (a dead rig is not walking);
     * the film window still closes at walkticks. -1 (the default) never kills it.
     */
    private static final int DIE_AT = Integer.getInteger(P + "walkdie", -1);

    /**
     * {@code walkhurt=N}: hit the carrier for one point at walk tick N and every 30 ticks after, so a film shows
     * a hurt creature mid-stride (the red flash, and whether its walk carries on); it keeps walking.
     */
    private static final int HURT_AT = Integer.getInteger(P + "walkhurt", -1);
    private static boolean killed;

    /** Blocks per tick round the circle. 0.09 is a brisk vanilla walk. */
    private static final double SPEED =
            Double.parseDouble(System.getProperty(P + "walkspeed", "0.09"));
    private static final double RADIUS =
            Double.parseDouble(System.getProperty(P + "radius", "2.5"));

    /**
     * The game's tick rate while filming (as {@code vanillawalk}'s): 5 runs the scene 4x slower, so a film sped
     * back up 4x shows the walk at ~60 fps on a software client. 20 (the default) changes nothing.
     */
    private static final int FILM_TICK_RATE = Integer.getInteger(P + "filmtickrate", 20);

    private enum Phase { WAIT_TITLE, WAIT_INGAME, SPAWN, SETTLE, STAND, WALK, DONE }

    private static volatile Phase phase = Phase.WAIT_TITLE;
    private static int ticks;
    private static boolean fired;
    private static final List<String> NOTES = new ArrayList<>();

    private static final double CAM_X = 8.5;
    private static final double CAM_Y = 97.0;
    private static final double CAM_Z = 8.5;
    private static final double CENTRE_X = CAM_X;
    private static final double CENTRE_Z = CAM_Z + 7.0;
    private static final double FOOT_Y = 95.0;

    private static volatile DisplayRig rig;
    /** Bone names in the head's subtree, so the report can name the head-vs-body gap. */
    private static final Set<String> HEAD_BONES = new HashSet<>();
    private static List<CreatureSculpt.Part> mirrored;
    private static Map<String, BedrockAnimation> clips;

    // ── the server-side controls ──────────────────────────────────────────────────────────────
    private static double angle;
    private static double travelled;
    private static float yawTurned;
    private static float lastYaw = Float.NaN;
    private static float headMin = Float.MAX_VALUE;
    private static float headMax = -Float.MAX_VALUE;
    private static int walkClipTicks;
    private static int serverWalkTicks;
    private static float sweep;

    // ── the client-side oracle, per phase ─────────────────────────────────────────────────────
    private static final class Worst {
        final String name;
        int frames;
        double pos;
        double headBody;
        double yaw;
        double tween;
        String posPair = "";
        int smoothTicks;
        int snapTicks;
        double lagSum;
        int lagN;
        /** Frames over tolerance, and the longest unbroken stretch of them, in ms. */
        int badFrames;
        long badSince;
        long longestBadMs;

        /**
         * One frame's verdict. A frame out of tolerance for LESS than a client tick is vanilla's
         * packet delivery, not the rig: one server tick's packets for forty displays can reach the
         * client split across a client tick, so a few displays start that tick's move and tween one
         * tick late and catch up on the next (measured on a FIXED rig: 0.047 blocks, 0.333 of a
         * tween, on isolated frames). The bug is the opposite shape -- a trailing head trails for
         * as long as the creature walks -- so the verdict is the longest unbroken stretch, not the
         * worst single frame.
         */
        int explained;
        /** what delivery looked like in the ticks before the longest unexplained stretch. */
        String longestStart = "";
        private String stretchStart = "";

        void frame(boolean out) {
            long now = System.currentTimeMillis();
            if (out && nearSplit()) {
                explained++;
            }
            boolean bad = out && !nearSplit();
            if (out) {
                badFrames++;
            }
            if (bad) {
                if (badSince == 0L) {
                    badSince = now;
                    stretchStart = recentDelivery();
                }
                if (now - badSince > longestBadMs) {
                    longestStart = stretchStart;
                }
                longestBadMs = Math.max(longestBadMs, now - badSince);
            } else {
                badSince = 0L;
            }
        }

        Worst(String name) {
            this.name = name;
        }

        String line() {
            return String.format("%s: %d frames | worst position spread %.4f blocks (%s) | worst "
                            + "head-vs-body %.4f blocks | worst yaw spread %.3f deg | worst tween "
                            + "spread %.4f | out of tolerance on %d frames (%d within %d ticks of a "
                            + "delivery split), longest UNEXPLAINED stretch %d ms | motion: %d ticks rendered as smooth, %d as a snap | "
                            + "mean rig-vs-carrier %.3f blocks",
                    name, frames, pos, posPair, headBody, yaw, tween, badFrames, explained,
                    splitWindow, longestBadMs,
                    smoothTicks, snapTicks, lagN == 0 ? 0 : lagSum / lagN)
                    + (longestStart.isEmpty() ? "" : " | delivery before the longest stretch "
                    + "(tick:moved/n,retweened,targets,step spread): " + longestStart);
        }
    }

    private static final Worst STAND = new Worst("stand (head sweeping)");
    private static final Worst WALK = new Worst("walk (circling, head on the camera)");
    private static long filmStart;

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
                    if (ClientHarness.bootstrapWorld(mc, "vanilla-walklook", ticks, true)) {
                        go(Phase.SPAWN);
                    }
                }
                case SPAWN -> {
                    if (ClientHarness.inWorld(mc) && ticks > ClientHarness.INGAME_SETTLE) {
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
                        go(Phase.STAND);
                    }
                }
                case STAND -> {
                    hold(mc);
                    if (ticks > STAND_TICKS) {
                        writeFilmWindow("stand");
                        filmStart = System.currentTimeMillis();
                        go(Phase.WALK);
                    }
                }
                case WALK -> {
                    hold(mc);
                    if (ticks > WALK_TICKS) {
                        writeFilmWindow("walk");
                        score();
                    }
                }
                default -> { }
            }
        } catch (Exception e) {
            finish(false, "threw " + e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────── the setup

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
        JsonObject sculpt;
        try {
            sculpt = resource("sculpt.json");
            clips = BedrockAnimation.parseAll(resource("animations.json"));
        } catch (Exception e) {
            finish(false, "could not read the starter " + SPECIES + ": " + e);
            return;
        }
        List<CreatureSculpt.Part> parsed = CreatureSculpt.parseParts(sculpt.getAsJsonArray("parts"));
        // markers=on films the same creature in flat marker colours, for a stock-client walk metric
        // -- so the pixel instrument the stock-client check uses is calibrated HERE, where the
        // render-state oracle already knows the answer.
        List<CreatureSculpt.Part> parts = MARKERS
                ? com.moddingfromamod.lite.StockWalkStage.markers(parsed) : parsed;
        double scale = sculpt.has("scale") ? sculpt.get("scale").getAsDouble() : 1.0;
        mirrored = com.moddingfromamod.vanilla.RigFit.intoRigSpace(parts);
        headSubtree(parts);
        NOTES.add("species " + SPECIES + ": " + parts.size() + " parts, scale " + scale
                + ", clips " + clips.keySet() + ", head subtree " + HEAD_BONES);
        server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
            if (p == null) {
                return;
            }
            ServerLevel level = p.level();
            var extents = CreatureAnatomy.extents(RigGeometry.of(parts));
            DisplayRig made = DisplayRig.spawn(level,
                    STOPGO ? new Vec3(com.moddingfromamod.lite.StockWalkStage.stopGoTarget(
                                    -1, CENTRE_X, CENTRE_Z, SPEED)[0], FOOT_Y, CENTRE_Z)
                            : new Vec3(CENTRE_X + RADIUS, FOOT_Y, CENTRE_Z), 180f, parts, scale,
                    CreatureAnatomy.widthBlocks(extents) * scale,
                    CreatureAnatomy.heightBlocks(extents) * scale, null);
            made.base().setNoGravity(true);
            stopAiMoving(made.base());
            for (var d : made.all()) {
                d.setNoGravity(true);
            }
            if (MOTION >= 0) {
                int n = 0;
                for (Entity d : made.displays()) {
                    setTeleport(d, MOTION);
                    n++;
                }
                NOTES.add("rigmotion=" + MOTION + ": every one of " + n
                        + " displays set to teleport_duration " + MOTION);
            }
            if ("teleport".equalsIgnoreCase(SABOTAGE)) {
                int n = 0;
                List<Entity> ds = made.displays();
                for (int i = 0; i < ds.size(); i += 2) {
                    setTeleport(ds.get(i), teleportOf(ds.get(i)) == 0 ? 3 : 0);
                    n++;
                }
                NOTES.add("⚠ rigsabotage=teleport: " + n + " displays given a DIFFERENT "
                        + "teleport_duration -- this run MUST fail the position spread, or the "
                        + "oracle is blind");
            }
            updateSplitWindow(made);
            // THE SHIPPING DRIVER. Nothing below poses a bone: every transform this rig receives
            // comes from VanillaRigAnimator reading the carrier, as in a real game.
            VanillaRigAnimator.drive(made, mirrored, clips);
            rig = made;
            NOTES.add("the rig = " + made.displays().size() + " item_display + 1 "
                    + (made.choice() == null ? "?" : made.choice().id())
                    + ", teleport_duration at spawn " + teleportCensus(made));
        });
        go(Phase.SETTLE);
    }

    private static JsonObject resource(String file) throws Exception {
        String at = "/starter-library/species/" + SPECIES + "/" + file;
        try (InputStream in = com.moddingfromamod.plugin.ModResources.open(at)) {
            if (in == null) {
                throw new IllegalStateException(at + " is not on the classpath");
            }
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    private static void headSubtree(List<CreatureSculpt.Part> parts) {
        Map<String, String> parent = new LinkedHashMap<>();
        for (CreatureSculpt.Part p : parts) {
            parent.putIfAbsent(p.name(), p.parent());
        }
        for (String name : parent.keySet()) {
            String at = name;
            Set<String> seen = new HashSet<>();
            while (at != null && !at.isEmpty() && seen.add(at)) {
                if (RigPlan.isHeadBone(at)) {
                    HEAD_BONES.add(name);
                    break;
                }
                at = parent.get(at);
            }
        }
    }

    /** As {@code vanillawalk}: AI on (travel() is the walk animation), but it cannot move itself. */
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

    private static int teleportOf(Entity d) {
        return com.moddingfromamod.compat.EntityNbt.save(d).getIntOr("teleport_duration", 0);
    }

    private static void setTeleport(Entity d, int ticks) {
        CompoundTag t = com.moddingfromamod.compat.EntityNbt.save(d);
        t.putInt("teleport_duration", ticks);
        com.moddingfromamod.compat.EntityNbt.load(d, t);
    }

    private static String teleportCensus(DisplayRig r) {
        Map<Integer, Integer> by = new java.util.TreeMap<>();
        for (Entity d : r.displays()) {
            by.merge(teleportOf(d), 1, Integer::sum);
        }
        return by.toString();
    }

    // ─────────────────────────────────────────────────────── the carrier, moved on the server

    /**
     * BEFORE the entities tick, so the carrier's own {@code travel()} applies the step (that is what
     * advances {@code walkAnimation}) and {@code VanillaRigAnimator}, on the Post event, sees the
     * result. Only the CARRIER is touched.
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Pre event) {
        DisplayRig r = rig;
        if (!ENABLED || r == null || (phase != Phase.STAND && phase != Phase.WALK)) {
            return;
        }
        var base = r.base();
        var server = event.getServer();
        ServerPlayer cam = server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
        if (killed) {
            return;
        }
        if (phase == Phase.WALK && HURT_AT >= 0 && serverWalkTicks >= HURT_AT
                && (serverWalkTicks - HURT_AT) % 30 == 0) {
            NOTES.add("walkhurt: the carrier hit at walk tick " + serverWalkTicks);
            com.moddingfromamod.compat.Damage.hurt(base, base.damageSources().generic(), 1f);
        }
        if (phase == Phase.WALK && DIE_AT >= 0 && serverWalkTicks >= DIE_AT) {
            killed = true;
            NOTES.add("walkdie: the carrier killed at walk tick " + serverWalkTicks);
            base.kill((net.minecraft.server.level.ServerLevel) base.level());
            return;
        }
        if (phase == Phase.STAND) {
            // Standing still, facing the camera, head sweeping +-50 degrees: a head that MOVES on a
            // body that does not.
            base.setDeltaMovement(Vec3.ZERO);
            sweep += 0.05f;
            float yaw = 180f;
            base.setYRot(yaw);
            base.setYBodyRot(yaw);
            double lx = base.getX() + Math.sin(Math.sin(sweep) * 0.9) * -4.0;
            base.getLookControl().setLookAt(lx, base.getEyeY(), base.getZ() - 4.0);
        } else {
            // Round the circle, anticlockwise seen from above, at SPEED. The step is aimed at the
            // NEXT point on the circle rather than along the tangent, so it cannot drift off it.
            double tx;
            double tz;
            if (STOPGO) {
                // motion=stopgo: straight across and back with stops, the scene the stock-client
                // film uses, so the pixel metric is calibrated on the scene it will be read on.
                double[] at = com.moddingfromamod.lite.StockWalkStage.stopGoTarget(
                        serverWalkTicks, CENTRE_X, CENTRE_Z, SPEED);
                tx = at[0];
                tz = at[1];
            } else {
                double w = SPEED / RADIUS;
                angle += w;
                tx = CENTRE_X + RADIUS * Math.cos(angle);
                tz = CENTRE_Z + RADIUS * Math.sin(angle);
            }
            double dx = tx - base.getX();
            double dz = tz - base.getZ();
            // Minecraft yaw for a heading (dx, dz): x = -sin(yaw), z = cos(yaw). A stopped
            // creature keeps the way it was facing.
            if (Math.abs(dx) + Math.abs(dz) > 1e-4) {
                heading = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90f;
            }
            float yaw = heading;
            base.setYRot(yaw);
            base.setYBodyRot(yaw);
            base.setDeltaMovement(dx, 0, dz);
            if (cam != null) {
                base.getLookControl().setLookAt(cam.getX(), cam.getEyeY(), cam.getZ());
            }
            travelled += Math.sqrt(dx * dx + dz * dz);
            if (!Float.isNaN(lastYaw)) {
                yawTurned += Math.abs(Mth.degreesDifference(lastYaw, yaw));
            }
            lastYaw = yaw;
            serverWalkTicks++;
            if (serverWalkTicks % 20 == 1) {
                updateSplitWindow(r);
            }
            if (RigAnimator.selectionFor(RigAnimator.motionOf(base)).clip()
                    == BedrockAnimation.Clip.WALK) {
                walkClipTicks++;
            }
        }
        float rel = Mth.wrapDegrees(base.getYHeadRot() - base.getYRot());
        headMin = Math.min(headMin, rel);
        headMax = Math.max(headMax, rel);
    }

    // ───────────────────────────────────────────────────────── the oracle, read off each frame

    @SubscribeEvent
    public static void onRenderFrame(RenderFrameEvent.Post event) {
        DisplayRig r = rig;
        Minecraft mc = Minecraft.getInstance();
        if (!ENABLED || r == null || mc.level == null || killed
                || (phase != Phase.STAND && phase != Phase.WALK)) {
            return;
        }
        Worst w = phase == Phase.STAND ? STAND : WALK;
        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        List<Entity> server = r.displays();
        List<RigPlan.Group> groups = r.groups();
        List<Display> live = new ArrayList<>();
        List<Boolean> head = new ArrayList<>();
        for (int i = 0; i < server.size() && i < groups.size(); i++) {
            Entity e = mc.level.getEntity(server.get(i).getId());
            if (e instanceof Display d) {
                live.add(d);
                head.add(HEAD_BONES.contains(groups.get(i).boneName()));
            }
        }
        if (live.size() < 2) {
            return;
        }
        w.frames++;
        Display ref = live.get(0);
        Vec3 refPos = rendered(ref, pt);
        float refYaw = renderedYaw(ref, pt);
        double worstPos = 0;
        String pair = "";
        double headBody = 0;
        double yaw = 0;
        double lo = 2;
        double hi = -1;
        java.util.Set<Integer> phases = new java.util.HashSet<>();
        List<Vec3> at = new ArrayList<>();
        for (Display d : live) {
            at.add(rendered(d, pt));
        }
        for (int i = 0; i < live.size(); i++) {
            Display d = live.get(i);
            double gap = at.get(i).distanceTo(refPos);
            if (gap > worstPos) {
                worstPos = gap;
                pair = "display " + i + (head.get(i) ? " (head)" : "") + " vs display 0";
            }
            yaw = Math.max(yaw, Math.abs(Mth.degreesDifference(refYaw, renderedYaw(d, pt))));
            // What the renderer itself calls with the same partial tick this frame, so the side
            // effect on lastProgress is a no-op.
            float prog = d.calculateInterpolationProgress(pt);
            if (prog < 1f) {
                lo = Math.min(lo, prog);
                hi = Math.max(hi, prog);
                // a pose eases over the beat plus a tick, so a display re-posed on this beat (progress 0)
                // and one finishing the last beat's tween (progress 3/4) are BOTH in flight, on one schedule. What
                // the rule catches is two schedules: when each tween ENDS, modulo the beat. (A look change re-arms a
                // running tween from now for the ticks it had left, RigCadence.REARM: it starts off the beat and still
                // ends on it.)
                int left = tweenLeft(d);
                if (left != Integer.MIN_VALUE) {
                    phases.add(Math.floorMod(left, com.moddingfromamod.vanilla.RigAnimator.DEFAULT_INTERVAL));
                }
            }
        }
        for (int i = 0; i < live.size(); i++) {
            if (!head.get(i)) {
                continue;
            }
            for (int j = 0; j < live.size(); j++) {
                if (!head.get(j)) {
                    headBody = Math.max(headBody, at.get(i).distanceTo(at.get(j)));
                }
            }
        }
        if (worstPos > w.pos) {
            w.pos = worstPos;
            w.posPair = pair;
        }
        w.headBody = Math.max(w.headBody, headBody);
        w.yaw = Math.max(w.yaw, yaw);
        // in flight on more than one beat phase: two writers, the head swimming against the body
        double off = hi >= 0 && (phases.size() > 1 || startUnknown) ? hi - lo : 0;
        w.tween = Math.max(w.tween, off);
        w.frame(worstPos > POS_TOL || yaw > YAW_TOL || off > TWEEN_TOL);
    }

    private static java.lang.reflect.Field tweenStart;
    private static java.lang.reflect.Field tweenLength;
    private static boolean startUnknown;

    /** Client ticks until this display's transform tween ends, or MIN_VALUE if that cannot be read (then progress decides). */
    private static int tweenLeft(Display d) {
        try {
            if (tweenStart == null) {
                tweenStart = Display.class.getDeclaredField("interpolationStartClientTick");
                tweenStart.setAccessible(true);
                tweenLength = Display.class.getDeclaredField("interpolationDuration");
                tweenLength.setAccessible(true);
            }
            return (int) (tweenStart.getLong(d) + tweenLength.getInt(d) - d.tickCount);
        } catch (ReflectiveOperationException | RuntimeException e) {
            startUnknown = true;
            return Integer.MIN_VALUE;
        }
    }

    /**
     * Per CLIENT tick: did the rig's last tick of travel render as motion across the tick
     * ({@code xOld != x}, a smooth lerp) or as a jump at the tick boundary ({@code xOld == x} while
     * the display did move, i.e. a snap — 20 Hz stutter at any frame rate)? And how far is the
     * drawn rig from where the client draws the carrier, whose hitbox is what a player hits?
     */
    @SubscribeEvent
    public static void onClientTickMotion(ClientTickEvent.Pre event) {
        DisplayRig r = rig;
        Minecraft mc = Minecraft.getInstance();
        if (!ENABLED || r == null || mc.level == null || phase != Phase.WALK) {
            return;
        }
        List<Entity> ds = r.displays();
        if (ds.isEmpty()) {
            return;
        }
        Entity d = mc.level.getEntity(ds.get(0).getId());
        Entity carrier = mc.level.getEntity(r.base().getId());
        if (d == null) {
            return;
        }
        Vec3 now = d.position();
        if (lastSeen != null && now.distanceTo(lastSeen) > 1e-4) {
            if (Math.abs(d.getX() - d.xOld) + Math.abs(d.getZ() - d.zOld) > 1e-4) {
                WALK.smoothTicks++;
            } else {
                WALK.snapTicks++;
            }
        }
        lastSeen = now;
        if (carrier != null) {
            WALK.lagSum += now.distanceTo(carrier.position());
            WALK.lagN++;
        }
    }

    private static Vec3 lastSeen;

    // ── DELIVERY: did one server tick's updates for the rig reach the client in ONE client tick? ──
    //
    // Every display is moved every server tick, and every write cycle posts all of its displays'
    // transforms in the same server tick (RigDriver.animate's loop). The client can still process
    // part of one server tick's packets, run a client tick, then the rest: the late displays start
    // that move, that turn or that tween one tick behind. No rig change can prevent that, so a
    // frame out of tolerance within the split window of one is counted as explained, and only an
    // UNEXPLAINED stretch fails -- the systematic bug is out of tolerance whether or not a split
    // happened, so it cannot hide behind this. A split is recognised only from what arrived,
    // display by display (onClientTickDelivery says the four shapes), never from a magnitude.
    //
    // (2026-09-27): the first version knew one shape -- some displays moving while walking --
    // and 5 of 10 runs failed on stretches that followed the other three (a split turn while
    // STANDING, a split whose late half arrived with the next tick's move, a pose write split
    // across two ticks). Attributed over 5 runs on main and 5 with an earlier change's glow mirror removed
    // (3/5 and 2/5 failed): not, not the rig.

    /**
     * How long a split's own effect can last, DERIVED from vanilla's lerp rather than tuned.
     *
     * <p>{@code Display.lerpTo} restarts a {@code teleport_duration}-step ease on every packet, so
     * each tick a display closes 1/T of what is left: a display one tick behind its neighbour
     * stays behind by a factor (1 - 1/T) per tick. From at most one tick of travel ({@code SPEED})
     * down to {@link #POS_TOL} is ln(SPEED/POS_TOL) / ln(T/(T-1)) ticks -- 11.1 at T=3, which is
     * the 449 ms a fixed rig was measured out of tolerance after a split. Plus one 3-tick tween,
     * for a transform that started a tick late. T is read off the rig itself at spawn, so this is
     * the same rule on `main` and on the fix.
     */
    private static volatile int splitWindow = 6;
    private static long clientTick;
    private static long lastSplitTick = Long.MIN_VALUE / 2;
    private static int moveSplits;
    private static int targetSplits;
    private static int tweenSplits;
    private static int tweenOverlaps;
    private static Set<Integer> lastArrived = new HashSet<>();
    /** client ticks on which some display's interpolation restarted with its target unchanged. */
    private static int sameRestartTicks;
    private static final StringBuilder RESTARTS = new StringBuilder();
    private static final Map<Integer, Object> LAST_LERP = new java.util.HashMap<>();
    private static java.lang.reflect.Field lerpField;
    private static boolean lerpFieldTried;

    /** Display's private pos/rot interpolation target, or null if this version has none by that name. */
    private static Object interpolationTarget(Display d) {
        if (!lerpFieldTried) {
            lerpFieldTried = true;
            try {
                lerpField = Display.class.getDeclaredField("posRotInterpolationTarget");
                lerpField.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException e) {
                NOTES.add("instrument unavailable: " + e);
            }
        }
        try {
            return lerpField == null ? null : lerpField.get(d);
        } catch (IllegalAccessException e) {
            return null;
        }
    }
    /** Two moves "take the same step" to within float noise; a real double step is ~SPEED. */
    private static final double STEP_EPS = 1e-6;
    /** Folds a yaw step into the step comparison; any positive weight works, it only has to differ. */
    private static final double YAW_STEP_WEIGHT = 1e-3;
    private static final Map<Integer, double[]> LAST_TARGET = new java.util.HashMap<>();
    private static final Map<Integer, Object> LAST_STATE = new java.util.HashMap<>();

    @SubscribeEvent
    public static void onClientTickDelivery(ClientTickEvent.Pre event) {
        DisplayRig r = rig;
        Minecraft mc = Minecraft.getInstance();
        if (!ENABLED || r == null || mc.level == null
                || (phase != Phase.STAND && phase != Phase.WALK)) {
            return;
        }
        clientTick++;
        int n = 0;
        int moved = 0;
        int restartedSame = 0;
        double stepLo = Double.MAX_VALUE;
        double stepHi = 0;
        Set<String> targets = new HashSet<>();
        Set<Integer> arrived = new HashSet<>();
        for (Entity e : r.displays()) {
            if (!(mc.level.getEntity(e.getId()) instanceof Display d)) {
                continue;
            }
            n++;
            // Packets are handled before the tick: lerpTo has set a new TARGET, and the synced
            // data has been stored, but Display.tick has not applied either yet. The YAW is part
            // of the target: a creature standing still still turns its body, and a turn
            // delivered across two client ticks pulls the displays apart in yaw exactly as a
            // split move does in position.
            double[] t = {d.getInterpolation().position().x, d.getInterpolation().position().z, d.getInterpolation().yRot()};
            double[] was = LAST_TARGET.put(d.getId(), t);
            targets.add(t[0] + "," + t[1] + "," + t[2]);
            // an interpolation RESTARTED with the target unchanged (a resync packet re-sent the
            // same position) is invisible to the target comparison, and it puts that display on a
            // different easing curve from its neighbours. Display replaces its target object on
            // every lerpTo, so the object's identity says when one began.
            Object lerp = interpolationTarget(d);
            Object hadLerp = LAST_LERP.put(d.getId(), lerp);
            boolean sameTarget = was != null && was[0] == t[0] && was[1] == t[1] && was[2] == t[2];
            if (lerp != null && lerp != hadLerp && sameTarget) {
                restartedSame++;
            }
            if (was != null && (was[0] != t[0] || was[1] != t[1] || was[2] != t[2])) {
                moved++;
                double step = Math.hypot(t[0] - was[0], t[1] - was[1])
                        + Math.abs(Mth.wrapDegrees(t[2] - was[2])) * YAW_STEP_WEIGHT;
                stepLo = Math.min(stepLo, step);
                stepHi = Math.max(stepHi, step);
            }
            Object st = d.renderState();
            Object had = LAST_STATE.put(d.getId(), st);
            if (had != null && had != st) {
                arrived.add(d.getId());
            }
        }
        // ── A MOVE SPLIT: one server tick's moves reached this client over two client ticks ──
        // Every display is sent the SAME target on every server tick (RigDriver.follow), so on a
        // client tick after a whole delivery all targets agree and every display that moved took
        // the same step. Three observations each prove a split, and found the last two in
        // the runs that failed (measured, 10 runs, 2026-09-27):
        //   - only SOME displays took a new target;
        //   - every display moved but the targets DISAGREE (the late ones took the previous tick's);
        //   - every display moved to one target but by different STEPS (the late ones caught up
        //     two server ticks at once).
        boolean moveSplit = moved > 0 && moved < n;
        boolean targetSplit = !moveSplit && (targets.size() > 1
                || (moved > 1 && stepHi - stepLo > STEP_EPS));
        if (moveSplit) {
            moveSplits++;
        }
        if (targetSplit) {
            targetSplits++;
        }
        // ── A TWEEN SPLIT: one pose write reached this client over two client ticks ──
        // renderState is replaced in the PREVIOUS tick's Display.tick, so this sees last tick's
        // arrivals. Two consecutive ticks with arrivals are a split ONLY when their display sets
        // are DISJOINT: every display was written once, and some of them got it a tick late
        // (measured: 1 then 35, 11 then 25, of one 36-display write). The bug fixed has the
        // opposite shape -- the head re-tweened EVERY tick on top of the body's write -- so its
        // consecutive arrivals OVERLAP, are counted as `tweenOverlaps`, and never excuse a frame.
        boolean tweenSplit = false;
        if (!arrived.isEmpty() && !lastArrived.isEmpty()) {
            Set<Integer> both = new HashSet<>(arrived);
            both.retainAll(lastArrived);
            if (both.isEmpty()) {
                tweenSplits++;
                tweenSplit = true;
            } else {
                tweenOverlaps++;
            }
        }
        if (moveSplit || targetSplit || tweenSplit) {
            lastSplitTick = clientTick;
        }
        if (restartedSame > 0) {
            sameRestartTicks++;
            if (RESTARTS.length() < 1500) {
                RESTARTS.append(phase == Phase.STAND ? "s" : "w").append(clientTick).append(':')
                        .append(restartedSame).append('/').append(n).append(' ');
            }
        }
        DELIVERY.addLast(clientTick + ":" + moved + "/" + n + (restartedSame > 0 ? "r" + restartedSame : "")
                + "," + arrived.size() + ","
                + targets.size() + "," + String.format("%.4f", moved == 0 ? 0 : stepHi - stepLo)
                + (moveSplit || targetSplit || tweenSplit ? "*" : ""));
        while (DELIVERY.size() > 24) {
            DELIVERY.removeFirst();
        }
        lastArrived = arrived;
    }

    /** the last client ticks' delivery, one entry each, for the report on a bad stretch. */
    private static final java.util.ArrayDeque<String> DELIVERY = new java.util.ArrayDeque<>();

    private static String recentDelivery() {
        return String.join(" ", DELIVERY);
    }

    /** Largest teleport_duration any display has carried so far -- see {@link #splitWindow}. */
    private static int maxTeleport;

    private static void updateSplitWindow(DisplayRig r) {
        for (Entity d : r.displays()) {
            maxTeleport = Math.max(maxTeleport, teleportOf(d));
        }
        int t = maxTeleport;
        splitWindow = 3 + (t <= 1 ? 0
                : (int) Math.ceil(Math.log(SPEED / POS_TOL) / Math.log(t / (t - 1.0))));
    }

    private static boolean nearSplit() {
        return clientTick - lastSplitTick <= splitWindow;
    }

    /** What DisplayRenderer asks for ({@code getYRot(partial)} on 1.21.4, the same rotLerp). */
    private static float renderedYaw(Entity e, float pt) {
        return Mth.rotLerp(pt, e.yRotO, e.getYRot());
    }

    private static Vec3 rendered(Entity e, float pt) {
        return new Vec3(Mth.lerp(pt, e.xOld, e.getX()), Mth.lerp(pt, e.yOld, e.getY()),
                Mth.lerp(pt, e.zOld, e.getZ()));
    }

    // ─────────────────────────────────────────────────────────────────────────────── the verdict

    /** Rigid means these are exactly equal for every display; float noise is far below them. */
    private static final double POS_TOL = 1e-3;
    private static final double YAW_TOL = 0.05;
    private static final double TWEEN_TOL = 1e-3;
    /**
     * Two client ticks. Longer than any delivery split can last (it heals on the next tick, see
     * {@link Worst#frame}); far shorter than a walk, which is where the bug lives.
     */
    private static final long PERSISTS_MS = 100;

    private static void score() {
        List<String> bad = new ArrayList<>();
        DisplayRig r = rig;
        if (r != null) {
            NOTES.add("teleport_duration at the end " + teleportCensus(r));
        }
        NOTES.add("delivery: " + moveSplits + " client ticks saw only SOME displays take a new "
                + "move target, " + targetSplits + " saw every display move but to different "
                + "targets or by different steps, " + tweenSplits + " saw one pose write arrive "
                + "over two ticks (disjoint displays) -- all three delivery splits; "
                + sameRestartTicks + " saw an interpolation restart with its target unchanged ("
                + "reported only); "
                + tweenOverlaps + " saw the SAME displays re-posed on consecutive ticks (never "
                + "excused: that is an earlier change's two-writer bug) -- of " + clientTick + " ticks measured");
        NOTES.add("same-target restarts (phase tick:displays/n): " + RESTARTS);
        NOTES.add("delivery-split window " + splitWindow + " ticks (largest teleport_duration "
                + maxTeleport + ", " + SPEED + " blocks/tick, tolerance " + POS_TOL + ")");
        NOTES.add(STAND.line());
        NOTES.add(WALK.line());
        NOTES.add(String.format("controls: travelled %.2f blocks, turned %.0f deg, walk clip on "
                        + "%d of %d ticks, head yaw relative to body ranged %.1f..%.1f deg",
                travelled, yawTurned, walkClipTicks, serverWalkTicks, headMin, headMax));
        // ── THE CONTROLS: a rig that never moved, turned, walked or looked is trivially rigid ──
        if (travelled < 5.0) {
            bad.add(String.format("the carrier travelled only %.2f blocks -- the TEST is broken",
                    travelled));
        }
        if (yawTurned < 90f) {
            bad.add(String.format("the carrier turned only %.0f degrees", yawTurned));
        }
        if (walkClipTicks < serverWalkTicks / 2) {
            bad.add("the walk clip was selected on only " + walkClipTicks + " of "
                    + serverWalkTicks + " ticks");
        }
        if (headMax - headMin < 30f) {
            bad.add(String.format("the head only ranged %.1f..%.1f deg -- head-look was not "
                    + "exercised, so nothing here is about", headMin, headMax));
        }
        if (STAND.frames < 20 || WALK.frames < 60) {
            bad.add("too few frames measured (" + STAND.frames + " stand, " + WALK.frames
                    + " walk)");
        }
        // ── THE ORACLE ────────────────────────────────────────────────────────────────────────
        for (Worst w : List.of(STAND, WALK)) {
            if (w.longestBadMs < PERSISTS_MS) {
                continue;
            }
            if (w.pos > POS_TOL) {
                bad.add(String.format("%s: the rig CAME APART -- two displays were drawn %.3f "
                                + "blocks apart (%s; head-vs-body %.3f), out of tolerance for %d ms "
                                + "unbroken. Every display stands at the carrier's feet, so the only "
                                + "way this persists is displays easing into the same move over "
                                + "different teleport_durations",
                        w.name, w.pos, w.posPair, w.headBody, w.longestBadMs));
            }
            if (w.yaw > YAW_TOL) {
                bad.add(String.format("%s: displays were drawn up to %.2f deg apart in yaw",
                        w.name, w.yaw));
            }
            if (w.tween > TWEEN_TOL) {
                bad.add(String.format("%s: two in-flight transform tweens were %.3f apart in "
                                + "progress -- the head and the body are on different tween "
                                + "clocks, so the head swims against the body", w.name, w.tween));
            }
        }
        if (WALK.snapTicks > WALK.smoothTicks) {
            bad.add("the rig's travel rendered as a SNAP on " + WALK.snapTicks + " ticks against "
                    + WALK.smoothTicks + " smooth -- a 20 Hz stutter at any frame rate");
        }
        finish(bad.isEmpty(), String.join("; ", bad));
    }

    // ────────────────────────────────────────────────────────────────────────────── the camera

    private static void hold(Minecraft mc) {
        if (mc.player == null) {
            return;
        }
        com.moddingfromamod.gatec.ClientHarness.setHudHidden(mc, true);
        mc.gui.setScreen(null);
        mc.player.setPos(CAM_X, CAM_Y, CAM_Z);
        mc.player.setYRot(0f);
        mc.player.setXRot(16f);
        mc.player.xOld = CAM_X;
        mc.player.yOld = CAM_Y;
        mc.player.zOld = CAM_Z;
        mc.player.yRotO = 0f;
        mc.player.xRotO = 16f;
    }

    /** The recorder's contract — see tools/record-client.sh. */
    private static void writeFilmWindow(String label) {
        if (filmStart == 0L) {
            return;
        }
        try {
            Path windows = SIG.resolve("film-windows.tsv");
            Files.createDirectories(windows.getParent());
            Files.writeString(windows,
                    filmStart + "\t" + System.currentTimeMillis() + "\t" + label + "\n",
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // no recorder attached; the assertions still stand
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
        rig = null;
        Minecraft.getInstance().stop();
    }
}
