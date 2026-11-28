package com.moddingfromamod.gatec;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.mojang.math.Transformation;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.vanilla.DisplayRig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * <b>Where each part of a rig is DRAWN, frame by frame, and how far it sits from the body.</b>
 *
 * <p>Every atom of a rig stands at its carrier's feet on the server ({@code RigDriver.follow} snaps every display to
 * one point and one yaw); a part's place in the body is its own transformation, drawn relative to that point. So on
 * the client every atom's DRAWN entity position and yaw should be the same at every frame, and any difference is a
 * part drawn away from its body: a foot off its leg. This reads what the renderer reads ({@code xOld→x} lerped by the
 * frame's partial tick, {@code getYRot(partial)}, the transformation at {@code calculateInterpolationProgress}) and
 * writes, per frame and rig, how far the worst part is drawn from where the body's shared position would put it.
 * Per client tick it also writes which atoms STARTED a position ease (a move packet arrived) and which started a pose
 * ease, which is the schedule that explains a drift.
 *
 * <p>Off unless {@code -Dmoddingfromamod.rigdrift=<dir>}; the check over its output is {@code tools/rig-drift.py}.
 * Integrated server only (it groups the client's displays by the server's rig tags, by entity id).
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class RigDriftMeter {

    private RigDriftMeter() {
    }

    private static final String DIR = System.getProperty(ModdingFromAModMod.MODID + ".rigdrift", "");

    /** owner tag -> the rig's item-display ids, refreshed from the server every few ticks. */
    private static volatile Map<String, int[]> groups = Map.of();
    private static BufferedWriter frames;
    private static BufferedWriter ticks;
    private static int tickNo;
    private static long frameNo;
    private static final Map<Integer, Integer> lastSteps = new HashMap<>();
    private static final Map<Integer, Long> lastPoseStart = new HashMap<>();
    private static final Map<Integer, double[]> lastPos = new HashMap<>();

    /**
     * The fields a display's position ease is read from, per handler class. Resolved by NAME on the handler the entity
     * really has, walking its class hierarchy, because the shape moved between Minecraft versions and the films run on
     * every version branch (docs/RELEASING.md, "Gate C on every version"): through 1.21.x {@code InterpolationHandler}
     * is a class holding {@code interpolationData.steps} and {@code interpolationSteps}; from 26.x it is an interface,
     * and a display's {@code LinearInterpolationHandler} holds {@code interpolationData.remainingSteps} beside the
     * inherited {@code AbstractInterpolationHandler.interpolationSteps}. A shape this knows no name for fails the film
     * with the class it looked in (the tick handler throws), never a silent zero.
     */
    private record Ease(Field data, Field steps, Field length) {
        int steps(Object handler) throws IllegalAccessException {
            return steps.getInt(data.get(handler));
        }

        int length(Object handler) throws IllegalAccessException {
            return length.getInt(handler);
        }
    }

    private static final Map<Class<?>, Ease> EASES = new HashMap<>();
    private static Field poseStart;

    private static Ease ease(Object handler) throws IOException {
        Ease e = EASES.get(handler.getClass());
        if (e == null) {
            try {
                Field data = field(handler.getClass(), "interpolationData");
                e = new Ease(data, field(data.getType(), "steps", "remainingSteps"),
                        field(handler.getClass(), "interpolationSteps"));
            } catch (NoSuchFieldException x) {
                throw new IOException("rigdrift: cannot read the display's ease state on " + handler.getClass().getName()
                        + ": " + x.getMessage(), x);
            }
            EASES.put(handler.getClass(), e);
        }
        return e;
    }

    /** The first of {@code names} declared on {@code c} or a superclass, made accessible. */
    static Field field(Class<?> c, String... names) throws NoSuchFieldException {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (String n : names) {
                try {
                    Field f = k.getDeclaredField(n);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {
                    // the next name, then the superclass
                }
            }
        }
        throw new NoSuchFieldException(String.join(" or ", names) + " (in " + c.getName() + " or a superclass)");
    }

    static boolean enabled() {
        return !DIR.isEmpty();
    }

    private static void open() throws IOException {
        if (frames != null) {
            return;
        }
        Path d = Path.of(DIR);
        Files.createDirectories(d);
        frames = Files.newBufferedWriter(d.resolve("rigdrift-frames.csv"), StandardCharsets.UTF_8);
        frames.write("nanos,gametime,clienttick,partial,rig,atoms,spread_max,spread_mean,yaw_spread,pos_spread,worst_id,worst_yaw,mean_yaw,body_x,body_y,body_z,acc_part_max,acc_body,angvel_max,angacc_max,q0x,q0y,q0z,q0w,angvel_fwd\n");
        ticks = Files.newBufferedWriter(d.resolve("rigdrift-ticks.csv"), StandardCharsets.UTF_8);
        ticks.write("gametime,clienttick,rig,atoms,pos_starts,pose_starts,server_yaw,drawn_yaw,pos_changed,steps_hist,ease_hist,pos_ids,pose_ids\n");
        try {
            poseStart = field(Display.class, "interpolationStartClientTick");
        } catch (ReflectiveOperationException e) {
            throw new IOException("rigdrift: cannot read the display's ease state: " + e, e);
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!enabled()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        try {
            open();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        tickNo++;
        IntegratedServer server = mc.getSingleplayerServer();
        if (server != null && tickNo % 10 == 0) {
            server.execute(() -> groups = scan(server));
        }
        try {
            for (Map.Entry<String, int[]> g : groups.entrySet()) {
                int posStarts = 0;
                int posChanged = 0;
                int[] stepsHist = new int[11];
                java.util.TreeMap<Integer, Integer> easeHist = new java.util.TreeMap<>();
                int poseStarts = 0;
                int n = 0;
                StringBuilder posIds = new StringBuilder();
                StringBuilder poseIds = new StringBuilder();
                for (int id : g.getValue()) {
                    Entity e = mc.level.getEntity(id);
                    if (!(e instanceof Display d)) {
                        continue;
                    }
                    n++;
                    // After this tick's entity tick, a move that arrived this tick has stepped once: steps = len - 1.
                    // A rig's teleport_duration is 3, so 2 here (from 0 or 1 last tick) means a new move.
                    Object handler = d.getInterpolation();
                    Ease shape = ease(handler);
                    int steps = shape.steps(handler);
                    // Without a new move steps only falls (2, 1, 0), so 2 is a move that arrived since the last tick.
                    lastSteps.put(id, steps);
                    stepsHist[Math.max(0, Math.min(10, steps))]++;
                    easeHist.merge(shape.length(handler), 1, Integer::sum);
                    double[] lp = lastPos.put(id, new double[] {d.getX(), d.getY(), d.getZ()});
                    if (lp != null && (lp[0] != d.getX() || lp[1] != d.getY() || lp[2] != d.getZ())) {
                        posChanged++;
                    }
                    if (steps == DisplayRig.MOTION_TICKS - 1) {
                        posStarts++;
                        posIds.append(id).append(' ');
                    }
                    long ps = poseStart.getLong(d);
                    Long pPrev = lastPoseStart.put(id, ps);
                    if (pPrev != null && pPrev != ps) {
                        poseStarts++;
                        poseIds.append(id).append(' ');
                    }
                }
                // The server's yaw for this rig (every atom has the same one) and the yaw the client has reached at the
                // end of this tick: a turn the client never finishes shows as a lasting difference.
                float serverYaw = Float.NaN;
                float drawnYaw = Float.NaN;
                if (g.getValue().length > 0) {
                    int first = g.getValue()[0];
                    Entity c = mc.level.getEntity(first);
                    drawnYaw = c == null ? Float.NaN : c.getYRot();
                    if (server != null) {
                        Entity se = server.overworld().getEntity(first);
                        serverYaw = se == null ? Float.NaN : se.getYRot();
                    }
                }
                StringBuilder hist = new StringBuilder();
                for (int k = 0; k <= DisplayRig.MOTION_TICKS; k++) {
                    hist.append(k == 0 ? "" : "/").append(stepsHist[k]);
                }
                StringBuilder ease = new StringBuilder();
                easeHist.forEach((k, v) -> ease.append(ease.length() == 0 ? "" : " ").append(k).append('x').append(v));
                ticks.write(String.format(Locale.ROOT, "%d,%d,%s,%d,%d,%d,%.2f,%.2f,%d,%s,%s,%s,%s%n", mc.level.getGameTime(),
                        tickNo, shortTag(g.getKey()), n, posStarts, poseStarts, serverYaw, drawnYaw, posChanged,
                        hist, ease, posIds.toString().trim(), poseIds.toString().trim()));
            }
            ticks.flush();
        } catch (IOException | IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    @SubscribeEvent
    public static void onFrame(RenderFrameEvent.Post event) {
        if (!enabled() || frames == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        frameNo++;
        try {
            for (Map.Entry<String, int[]> g : groups.entrySet()) {
                List<Display> ds = new ArrayList<>();
                for (int id : g.getValue()) {
                    if (mc.level.getEntity(id) instanceof Display.ItemDisplay d && d.renderState() != null) {
                        ds.add(d);
                    }
                }
                if (ds.size() < 2) {
                    continue;
                }
                double[] m = measure(ds, partial);
                frames.write(String.format(Locale.ROOT, "%d,%d,%d,%.4f,%s,%d,%.5f,%.5f,%.4f,%.5f,%d,%.2f,%.2f,%.5f,%.5f,%.5f,%.3f,%.3f,%.3f,%.3f,%.7f,%.7f,%.7f,%.7f,%.3f%n", System.nanoTime(),
                        mc.level.getGameTime(), tickNo, partial, shortTag(g.getKey()), ds.size(), m[0], m[1], m[2],
                        m[3], ds.get((int) m[4]).getId(), m[5], m[6], m[7], m[8], m[9],
                        accel(ds, partial, System.nanoTime(), g.getKey(), new Vec3(m[7], m[8], m[9]))[0],
                        lastAccBody, lastAngVel, lastAngAcc, firstQ.x, firstQ.y, firstQ.z, firstQ.w, lastAngFwd));
            }
            if (frameNo % 30 == 0) {
                frames.flush();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * @return {worst part's distance from where the shared body position puts it, the mean of that, the yaw spread in
     *     degrees, the entity-position spread in blocks, the worst part's index, its yaw, the body's mean yaw}
     */
    static double[] measure(List<Display> ds, float partial) {
        int n = ds.size();
        Vec3[] pos = new Vec3[n];
        float[] yaw = new float[n];
        Vector3f[] local = new Vector3f[n];
        double sx = 0;
        double sy = 0;
        double sz = 0;
        double ss = 0;
        double sc = 0;
        for (int i = 0; i < n; i++) {
            Display d = ds.get(i);
            pos[i] = d.getPosition(partial);
            yaw[i] = d.getYRot(partial);
            Transformation t = d.renderState().transformation().get(d.calculateInterpolationProgress(partial));
            local[i] = t.getMatrix().transformPosition(new Vector3f());
            sx += pos[i].x;
            sy += pos[i].y;
            sz += pos[i].z;
            ss += Math.sin(Math.toRadians(yaw[i]));
            sc += Math.cos(Math.toRadians(yaw[i]));
        }
        Vec3 mean = new Vec3(sx / n, sy / n, sz / n);
        float meanYaw = (float) Math.toDegrees(Math.atan2(ss, sc));
        double worst = 0;
        double sum = 0;
        double yawSpread = 0;
        double posSpread = 0;
        int worstI = 0;
        for (int i = 0; i < n; i++) {
            Vec3 drawn = pos[i].add(rotated(local[i], yaw[i]));
            Vec3 body = mean.add(rotated(local[i], meanYaw));
            double dist = drawn.distanceTo(body);
            if (dist > worst) {
                worst = dist;
                worstI = i;
            }
            sum += dist;
            yawSpread = Math.max(yawSpread, Math.abs(wrap(yaw[i] - meanYaw)));
            posSpread = Math.max(posSpread, pos[i].distanceTo(mean));
        }
        return new double[] {worst, sum / n, yawSpread, posSpread, worstI, yaw[worstI], meanYaw, mean.x, mean.y, mean.z};
    }

    /** Per display (and per rig body, keyed by -1 - rig hash): its last two drawn points and their times. */
    private static final Map<Integer, double[]> history = new HashMap<>();
    private static double lastAccBody;

    /**
     * How sharply each part's DRAWN point changed velocity at this frame (blocks/s/s, from its last three drawn points):
     * a kink, overshoot or snap when a late move lands mid-ease is a spike here. Returns {worst part}; the body's own is
     * left in {@link #lastAccBody}.
     */
    static double[] accel(List<Display> ds, float partial, long nanos, String rig, Vec3 body) {
        double t = nanos / 1e9;
        double worst = 0;
        double angVel = 0;
        double angAcc = 0;
        double angFwd = -Double.MAX_VALUE;
        for (Display d : ds) {
            Transformation tr = d.renderState().transformation().get(d.calculateInterpolationProgress(partial));
            Vec3 p = d.getPosition(partial).add(rotated(tr.getMatrix().transformPosition(new Vector3f()), d.getYRot(partial)));
            worst = Math.max(worst, step(d.getId(), p, t));
            Quaternionf q = new Quaternionf().rotationY((float) Math.toRadians(-d.getYRot(partial)))
                    .mul(tr.getLeftRotation()).mul(tr.getRightRotation());
            double[] w = spin(d.getId(), q, tickNo + partial);
            angVel = Math.max(angVel, w[0]);
            angAcc = Math.max(angAcc, w[1]);
            angFwd = Math.max(angFwd, w[2]);
        }
        Display d0 = ds.get(0);
        Transformation t0 = d0.renderState().transformation().get(d0.calculateInterpolationProgress(partial));
        firstQ = new Quaternionf().rotationY((float) Math.toRadians(-d0.getYRot(partial)))
                .mul(t0.getLeftRotation()).mul(t0.getRightRotation());
        lastAngVel = angVel;
        lastAngAcc = angAcc;
        lastAngFwd = angFwd == -Double.MAX_VALUE ? 0 : angFwd;
        lastAccBody = step(-1 - rig.hashCode(), body, t);
        return new double[] {worst};
    }

    private static Quaternionf firstQ = new Quaternionf();
    private static double lastAngVel;
    private static double lastAngAcc;
    /**
     * the most any part is still turning THE WAY IT LAST TURNED (deg per game tick; negative when every part
     * has reversed). A part's direction is remembered while it turns faster than {@link #MOVING_DEG}, and held for the
     * first {@link #HOLD_AXIS} frames it moves again, so the frames after a stall are judged against the way it went
     * before. A stall that ends in a reversal is a limb at the end of its swing (two 3-tick samples either side of the
     * extreme are the same pose, so it is drawn still for a beat); one that ends going on the same way is a motion
     * that stopped and lurched on, which is what the spin check is for. {@code angvel_max} alone cannot tell them apart.
     */
    private static double lastAngFwd;
    private static final double MOVING_DEG = 0.3;
    /** Per display: the unit axis it last turned about faster than {@link #MOVING_DEG}. */
    private static final Map<Integer, Vector3f> lastAxis = new HashMap<>();
    /** Per display: frames in a row turning faster than {@link #MOVING_DEG}. */
    private static final Map<Integer, Integer> movingFrames = new HashMap<>();
    /** Frames a part moving again is judged against its pre-stall axis: {@code tools/rig-drift.py} looks 3 ahead. */
    private static final int HOLD_AXIS = 3;
    /** Per display: the game time it last turned faster than {@link #MOVING_DEG}. */
    private static final Map<Integer, Double> lastMovedAt = new HashMap<>();
    /**
     * Game ticks a part's direction is remembered once it stops: two 3-tick beats and slack, longer than any stall the
     * check judges (the longest measured, 4.2 ticks); a part still longer than that has ended its motion, and what it
     * does next is a new one (0 in {@code angvel_fwd}, never "the same way").
     */
    private static final double AXIS_FRESH_TICKS = 8;
    /** Per display: {its last drawn orientation, the time, its last angular velocity (rad/s, world axis)}. */
    private static final Map<Integer, Object[]> spins = new HashMap<>();

    /**
     * How fast each part's DRAWN orientation turns (deg per game tick) and how sharply that turn changes (deg/tick/tick),
     * timed by GAME time (client tick + partial), so a frame drawn late does not read as a spike: what is drawn, against
     * where the game clock says it should be. From its last
     * frames: a whole-body move (the death flop) that steps rather than sweeps shows as a spike in the second. Position
     * kinks miss a part turning about its own centre.
     */
    private static double[] spin(int key, Quaternionf q, double t) {
        Object[] h = spins.get(key);
        double vel = 0;
        double acc = 0;
        double fwd = 0;
        Vector3f w = new Vector3f();
        if (h != null && t > (double) h[1]) {
            double dt = t - (double) h[1];
            Quaternionf dq = new Quaternionf(q).mul(new Quaternionf((Quaternionf) h[0]).invert());
            if (dq.w < 0) {
                dq.set(-dq.x, -dq.y, -dq.z, -dq.w);
            }
            double ang = 2 * Math.acos(Math.min(1.0, dq.w));
            double sn = Math.sqrt(Math.max(0, 1 - dq.w * dq.w));
            if (sn > 1e-9) {
                w.set((float) (dq.x / sn * ang / dt), (float) (dq.y / sn * ang / dt), (float) (dq.z / sn * ang / dt));
            }
            vel = Math.toDegrees(w.length());
            Vector3f axis = lastAxis.get(key);
            Double movedAt = lastMovedAt.get(key);
            // a direction remembered from a motion long over is not "the way it was going": the lane-end turn of a walk
            // 33 ticks ago made the next one read as a stall resuming (under lag)
            boolean fresh = axis != null && movedAt != null && t - movedAt <= AXIS_FRESH_TICKS;
            fwd = fresh ? Math.toDegrees(w.dot(axis)) : 0;
            // the axis a stall is judged against must be the one from BEFORE it: a part moving again keeps its old axis
            // for its first HOLD_AXIS frames (the check looks that far ahead), so a reversal reads as one
            int moving = vel >= MOVING_DEG ? movingFrames.merge(key, 1, Integer::sum) : 0;
            if (vel < MOVING_DEG) {
                movingFrames.put(key, 0);
            }
            if (moving > HOLD_AXIS || !fresh && moving > 0) {
                lastAxis.put(key, new Vector3f(w).normalize());
            }
            if (moving > 0) {
                lastMovedAt.put(key, t);
            }
            if (h[2] != null && h.length > 3 && t > (double) h[3]) {
                acc = Math.toDegrees(new Vector3f(w).sub((Vector3f) h[2]).length()) / ((t - (double) h[3]) / 2);
            }
        }
        spins.put(key, new Object[] {new Quaternionf(q), t, h == null ? null : w, h == null ? 0.0 : (double) h[1]});
        return new double[] {vel, acc, fwd};
    }

    private static double step(int key, Vec3 p, double t) {
        double[] h = history.get(key);
        double acc = 0;
        if (h != null && h[7] > 0 && t > h[3] && h[3] > h[7]) {
            double dt1 = t - h[3];
            double dt2 = h[3] - h[7];
            double ax = ((p.x - h[0]) / dt1 - (h[0] - h[4]) / dt2) / ((dt1 + dt2) / 2);
            double ay = ((p.y - h[1]) / dt1 - (h[1] - h[5]) / dt2) / ((dt1 + dt2) / 2);
            double az = ((p.z - h[2]) / dt1 - (h[2] - h[6]) / dt2) / ((dt1 + dt2) / 2);
            acc = Math.sqrt(ax * ax + ay * ay + az * az);
        }
        double[] n = new double[8];
        n[0] = p.x;
        n[1] = p.y;
        n[2] = p.z;
        n[3] = t;
        if (h != null) {
            System.arraycopy(h, 0, n, 4, 4);
        }
        history.put(key, n);
        return acc;
    }

    /** The display renderer's own rotation: rotationYXZ(-yaw), pitch 0 for a rig atom. */
    private static Vec3 rotated(Vector3f v, float yawDeg) {
        Vector3f r = new Matrix4f().rotationYXZ((float) Math.toRadians(-yawDeg), 0f, 0f).transformPosition(new Vector3f(v));
        return new Vec3(r.x, r.y, r.z);
    }

    private static double wrap(double deg) {
        double d = deg % 360.0;
        if (d > 180) {
            d -= 360;
        } else if (d < -180) {
            d += 360;
        }
        return d;
    }

    private static String shortTag(String tag) {
        String s = tag.substring(DisplayRig.RIG_OWNER_PREFIX.length());
        return s.length() > 8 ? s.substring(0, 8) : s;
    }

    private static Map<String, int[]> scan(IntegratedServer server) {
        Map<String, List<Integer>> found = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getAllEntities()) {
                if (!(e instanceof Display.ItemDisplay)) {
                    continue;
                }
                for (String t : e.getTags()) {
                    if (t.startsWith(DisplayRig.RIG_OWNER_PREFIX)) {
                        found.computeIfAbsent(t, k -> new ArrayList<>()).add(e.getId());
                        break;
                    }
                }
            }
        }
        Map<String, int[]> out = new HashMap<>();
        found.forEach((k, v) -> out.put(k, v.stream().mapToInt(Integer::intValue).toArray()));
        return out;
    }
}
