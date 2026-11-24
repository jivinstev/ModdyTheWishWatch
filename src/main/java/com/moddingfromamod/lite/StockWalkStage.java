package com.moddingfromamod.lite;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.runtime.BedrockAnimation;
import com.moddingfromamod.runtime.CreatureAnatomy;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.vanilla.DisplayRig;
import com.moddingfromamod.vanilla.RigGeometry;
import com.moddingfromamod.vanilla.VanillaRigAnimator;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b> on a GENUINELY STOCK client: a starter creature walking round in front of the child with
 * its head on him, staged from the server.</b>
 *
 * <p>{@code GateC:vanillawalklook} measures rigidity on a NeoForge client, reading the displays'
 * render state directly. A stock client (a 26.3, through a version translator such as ViaProxy) cannot be read that
 * way, and the question it adds is whether the fields the fix relies on &mdash; one
 * {@code teleport_duration} per rig, one tween start per write &mdash; arrive through the
 * translator as they left. So the scene is built here, from the server, exactly as the shipping
 * path would run it (the rig is handed to {@link VanillaRigAnimator}; only the CARRIER is moved),
 * and {@code tools/stock-walk-film.sh} films the child's screen for {@code tools/stock-walk-metric.py}.
 *
 * <p>Off unless {@code -Dmoddingfromamod.stockwalk=<starter species>} is set, e.g. via
 * {@code EXTRAPROPS=stockwalk=pet_dragon ./tools/lite-server.sh start}.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class StockWalkStage {

    private StockWalkStage() {
    }

    private static final Logger LOG = LoggerFactory.getLogger("mfam-stock-walk");

    public static final String PROPERTY = ModdingFromAModMod.MODID + ".stockwalk";

    /** The child's seat, and the circle the creature walks, a little above his eyes and ahead. */
    private static final double SEAT_X = 8.5;
    private static final double SEAT_Y = 120.0;
    private static final double SEAT_Z = 8.5;
    private static final double CENTRE_Z = SEAT_Z + 5.0;
    private static final double FOOT_Y = SEAT_Y + 0.6;
    private static final double RADIUS = 1.5;
    /** Blocks a tick; {@code stockwalkspeed=0.3} is a run (the owner asked for a run beside the walk). */
    private static final double SPEED = Double.parseDouble(System.getProperty(PROPERTY + "speed", "0.12"));
    /** Seconds after the child joins: a stock client applies the item pack by RELOADING. */
    private static final int SETTLE_TICKS = 20 * 20;

    private static DisplayRig rig;
    private static int joinedTicks;
    private static int walkTicks;
    private static double angle;
    private static float lastYaw;
    /** {@code stockwalkmotion=circle} for a lap; the default stop-and-go is what a film can measure. */
    private static final boolean STOPGO =
            "stopgo".equalsIgnoreCase(System.getProperty(PROPERTY + "motion", "stopgo"));
    /**
     * {@code stockwalkmotion=frenzy}: the lap, with the creature's yaw thrown about half a turn every other
     * tick. A client must keep every part of the creature together through it; a phone film showed a dragon
     * whose head and eyes came off its body while it spun.
     */
    /**
     * {@code stockwalkmotion=still}: held on the circle's centre, side on to the walk take's camera, for a
     * scripted pose ({@code rigstate=napcycle}) that the AI must not move it out of.
     */
    private static final boolean STILL =
            "still".equalsIgnoreCase(System.getProperty(PROPERTY + "motion", "stopgo"));
    private static final boolean FRENZY =
            "frenzy".equalsIgnoreCase(System.getProperty(PROPERTY + "motion", "stopgo"));

    /**
     * The frenzy's yaw on this tick: a new heading every other tick, 150 to 210 degrees from the last (a half
     * turn, a little short or a little over, so both ways round are the short one in turn). Pure.
     */
    public static float frenzyYaw(int tick) {
        return frenzyYaw(tick, 150f);
    }

    /**
     * The same, with each turn {@code step} to 1.4 x {@code step} degrees: {@code stockwalkfrenzystep=45} is a
     * creature turning hard (about 500 degrees a second) rather than one thrown about.
     */
    public static float frenzyYaw(int tick, float step) {
        float yaw = 0f;
        for (int i = 0; i <= tick / 2; i++) {
            yaw = Mth.wrapDegrees(yaw + step + Math.floorMod(i * 37, 61) * step / 150f);
        }
        return yaw;
    }

    private static final float FRENZY_STEP = frenzyStep();

    private static float frenzyStep() {
        try {
            return Float.parseFloat(System.getProperty(PROPERTY + "frenzystep", "150"));
        } catch (NumberFormatException e) {
            return 150f;
        }
    }

    public static String species() {
        return System.getProperty(PROPERTY, "").trim();
    }

    /**
     * {@code stockwalk=crab+pet_dragon+turtle} (the owner's parade): several bodies take turns on the circle, each
     * for {@code stockwalkturn} ticks (default 200), the rest parked behind the camera; all are staged at once, so a
     * phone that joins after the staging holds every one's pack. A builder body (crab, butterfly) is found too.
     */
    static List<String> parade() {
        List<String> out = new java.util.ArrayList<>();
        for (String sp : species().split("[+,]")) {   // + (a test harness splits its properties on commas)
            if (!sp.isBlank()) {
                out.add(sp.trim());
            }
        }
        return out;
    }

    private static final int TURN_TICKS = Integer.getInteger(PROPERTY + "turn", 200);
    private static final List<DisplayRig> PARADE = new java.util.ArrayList<>();
    private static final List<String> PARADE_NAMES = new java.util.ArrayList<>();

    /** Where a parked body waits its turn: behind the walk takes' cameras, out of every frame. */
    private static Vec3 park(int i) {
        return new Vec3(-12.5 - 5 * i, FOOT_Y, -8.5);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Pre event) {
        if (species().isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        // The CHILD, never a camera (starts one on a dedicated server): the mod's own service
        // clients are registered before anybody joins, so PlayerKind can tell them apart.
        var service = com.moddingfromamod.marshal.PlayerKind.serviceClients();
        ServerPlayer child = server.getPlayerList().getPlayers().stream()
                .filter(p -> com.moddingfromamod.marshal.PlayerKind.isChild(p.getUUID(), service))
                .findFirst().orElse(null);
        if (child == null) {
            return;
        }
        joinedTicks++;
        if (joinedTicks % 10 == 1) {
            seat(child);
        }
        if (rig == null) {
            if (joinedTicks == SETTLE_TICKS) {
                try {
                    build(server, child);
                } catch (Exception e) {
                    LOG.error("could not stage the stock walk", e);
                }
            }
            return;
        }
        if (PARADE.size() > 1) {
            int active = (walkTicks / TURN_TICKS) % PARADE.size();
            if (PARADE.get(active) != rig) {
                Vec3 p = park(PARADE.indexOf(rig));
                rig.base().teleportTo(p.x, p.y, p.z);
                rig = PARADE.get(active);
                LOG.info("STOCKWALK parade: now '{}' (body {} of {}) at walk tick {}", PARADE_NAMES.get(active),
                        active + 1, PARADE.size(), walkTicks);
            }
        }
        var base = rig.base();
        double tx;
        double tz;
        if (STILL) {
            tx = SEAT_X;
            tz = CENTRE_Z;
            lastYaw = base.getYRot();   // where it was put: side on at staging, turned by a take's rotate
        } else if (STOPGO) {
            double[] at = stopGoTarget(walkTicks, SEAT_X, CENTRE_Z, SPEED);
            tx = at[0];
            tz = at[1];
        } else {
            angle += SPEED / RADIUS;
            tx = SEAT_X + RADIUS * Math.cos(angle);
            tz = CENTRE_Z + RADIUS * Math.sin(angle);
        }
        double dx = tx - base.getX();
        double dz = tz - base.getZ();
        if (Math.abs(dx) + Math.abs(dz) > 1e-4) {
            lastYaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90f;
        }
        float yaw = FRENZY ? frenzyYaw(walkTicks, FRENZY_STEP) : lastYaw;
        base.setYRot(yaw);
        base.setYBodyRot(yaw);
        base.setDeltaMovement(dx, 0, dz);
        if (view() != null) {
            base.getLookControl().setLookAt(SEAT_X, SEAT_Y + 1.62, SEAT_Z);
        } else {
            base.getLookControl().setLookAt(child.getX(), child.getEyeY(), child.getZ());
        }
        walkTicks++;
        if (walkTicks % 100 == 0) {
            LOG.info("STOCKWALK walking: tick {} at ({}, {}) yaw {} walkAnimation {}", walkTicks,
                    String.format("%.2f", base.getX()), String.format("%.2f", base.getZ()),
                    String.format("%.1f", yaw), String.format("%.3f", base.walkAnimation.speed()));
        }
    }

    private static JsonObject resource(String sp, String file) throws Exception {
        for (String root : new String[] {"/starter-library/species/", "/builder-bodies/species/"}) {
            try (InputStream in = StockWalkStage.class.getResourceAsStream(root + sp + "/" + file)) {
                if (in != null) {
                    return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                            .getAsJsonObject();
                }
            }
        }
        if (file.equals("animations.json")) {
            return new JsonObject();   // a builder body has no clips of its own: the generated layers move it
        }
        throw new IllegalStateException(sp + "/" + file + " is not on the classpath");
    }

    private static void build(MinecraftServer server, ServerPlayer child) throws Exception {
        for (ServerLevel l : server.getAllLevels()) {
            l.setDayTime(6000L);
            l.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
            l.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
            l.setWeatherParameters(6000, 0, false, false);
        }
        List<String> names = parade();
        for (int i = 0; i < names.size(); i++) {
            PARADE.add(stageOne(child, names.get(i), i == 0 ? null : park(i)));
            PARADE_NAMES.add(names.get(i));
        }
        rig = PARADE.get(0);
    }

    /** Stage one body: on the circle ({@code at} null) or parked at {@code at}. */
    private static DisplayRig stageOne(ServerPlayer child, String sp, Vec3 at) throws Exception {
        JsonObject sculpt = resource(sp, "sculpt.json");
        Map<String, BedrockAnimation> clips = BedrockAnimation.parseAll(resource(sp, "animations.json"));
        List<CreatureSculpt.Part> parts = CreatureSculpt.parseParts(sculpt.getAsJsonArray("parts"));
        if (!"off".equalsIgnoreCase(System.getProperty(PROPERTY + "markers", "on"))) {
            parts = markers(parts);
        }
        double scale = sculpt.has("scale") ? sculpt.get("scale").getAsDouble() : 1.0;
        var ext = CreatureAnatomy.extents(RigGeometry.of(parts));
        ServerLevel level = child.level();
        Vec3 start = STILL ? new Vec3(SEAT_X, FOOT_Y, CENTRE_Z) : STOPGO
                ? new Vec3(stopGoTarget(-1, SEAT_X, CENTRE_Z, SPEED)[0], FOOT_Y, CENTRE_Z)
                : new Vec3(SEAT_X + RADIUS, FOOT_Y, CENTRE_Z);
        if (at != null) {
            start = at;
        }
        DisplayRig rig = DisplayRig.spawn(level, start, STILL ? 36.9f : 0f, parts, scale,   // still: side on to the camera
                CreatureAnatomy.widthBlocks(ext) * scale, CreatureAnatomy.heightBlocks(ext) * scale,
                null);
        var mob = rig.base();
        mob.setNoGravity(true);
        var speed = mob.getAttribute(
                net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.setBaseValue(0.0);
        }
        mob.goalSelector.removeAllGoals(g -> true);
        mob.targetSelector.removeAllGoals(g -> true);
        for (var d : rig.all()) {
            d.setNoGravity(true);
        }
        VanillaRigAnimator.drive(rig, com.moddingfromamod.vanilla.RigFit.intoRigSpace(parts), clips);
        java.util.Map<Integer, Integer> census = new java.util.TreeMap<>();
        for (var d : rig.displays()) {
            census.merge(com.moddingfromamod.compat.EntityNbt.save(d)
                    .getIntOr("teleport_duration", 0), 1, Integer::sum);
        }
        LOG.info("STOCKWALK staged '{}' ({} displays, scale {}) circling r={} at z={}, "
                + "teleport_duration census {}", sp, rig.displays().size(), scale, RADIUS,
                CENTRE_Z, census);
        return rig;
    }

    /** Ticks per stop-and-go cycle: walk 30, stop 10, walk back 30, stop 10. */
    public static final int STOPGO_PERIOD = 80;

    /**
     * <b>Where a stop-and-go walker should be on this tick</b> — straight across the view and back,
     * with a stop at each end. A steady circle hides the trail from a camera: a display that
     * lags by a constant amount at a constant speed is a constant OFFSET, and a film cannot tell a
     * constant offset from the creature's own shape. Starting and stopping turn it into a
     * TRANSIENT — the lagging parts arrive late at every stop and leave late at every start —
     * which is what a pixel measurement can see.
     *
     * @return {x, z} for the carrier this tick
     */
    public static double[] stopGoTarget(int tick, double centreX, double centreZ, double speed) {
        int t = Math.floorMod(tick, STOPGO_PERIOD);
        double half = speed * 30 / 2;
        double x;
        if (t < 30) {
            x = -half + speed * (t + 1);
        } else if (t < 40) {
            x = half;
        } else if (t < 70) {
            x = half - speed * (t - 39);
        } else {
            x = -half;
        }
        return new double[]{centreX + x, centreZ};
    }

    /** Head box and snout — the big head marker. */
    public static final int HEAD_A = 0xFF00FF;
    /** Everything else on the head (horns, eyes, pupils, mouth) — the head-vs-head control. */
    public static final int HEAD_B = 0x2040FF;
    /** The body and everything hung off it (belly, wings, spikes, tail). */
    public static final int BODY = 0x00FFFF;
    /** Parts that belong to neither subtree (the legs, on this creature). */
    public static final int OTHER = 0xFFFF00;

    /**
     * <b>The same creature in four flat MARKER colours</b> — geometry, hierarchy, pivots and clips
     * untouched, patterns dropped. A film of the real palette cannot be measured: the dragon's head
     * markers (pupils, mouth) are a dozen pixels each and its head shares its green with its body,
     * so a centroid is noise (measured: the control wobbled more than the bug). Recolouring changes
     * nothing depends on — which displays exist, what they hang off and when they are written.
     */
    public static List<CreatureSculpt.Part> markers(List<CreatureSculpt.Part> parts) {
        java.util.Map<String, String> parent = new java.util.HashMap<>();
        for (CreatureSculpt.Part p : parts) {
            parent.putIfAbsent(p.name(), p.parent());
        }
        List<CreatureSculpt.Part> out = new java.util.ArrayList<>(parts.size());
        for (CreatureSculpt.Part p : parts) {
            String root = p.name();
            java.util.Set<String> seen = new java.util.HashSet<>();
            while (parent.get(root) != null && !parent.get(root).isEmpty() && seen.add(root)) {
                root = parent.get(root);
            }
            int rgb;
            if (root.equals("head")) {
                rgb = p.name().equals("head") || p.name().equals("snout") ? HEAD_A : HEAD_B;
            } else if (root.equals("body")) {
                rgb = BODY;
            } else {
                rgb = OTHER;
            }
            out.add(new CreatureSculpt.Part(p.name(), p.parent(), p.pivot(), p.from(), p.to(),
                    rgb, p.rotation(), p.inflate(), null, 0));
        }
        return out;
    }

    /** Hold the child's camera on the circle. {@code teleport} is the only thing that moves it. */
    /**
     * {@code stockwalkview=x,y,z,yaw,pitch}: where the child WATCHES from, when it is not the seat. The creature
     * still looks at the seat, so a film from here is the same scene a Bedrock phone films from the same spot
     * (tests/bedrock/client/evidence_stockturn.sh stands the phone at 11.5 121 10.5, yaw 45, pitch 18, while the
     * creature watches the probe's bot on the seat). Null: he watches from the seat.
     */
    static double[] view() {
        String v = System.getProperty(PROPERTY + "view", "").trim();
        if (v.isEmpty()) {
            return null;
        }
        String[] p = v.split("[,/ ]+");
        if (p.length != 5) {
            return null;
        }
        try {
            double[] out = new double[5];
            for (int i = 0; i < 5; i++) {
                out[i] = Double.parseDouble(p[i]);
            }
            return out;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void seat(ServerPlayer child) {
        double[] view = view();
        if (view != null) {
            child.connection.teleport(view[0], view[1], view[2], (float) view[3], (float) view[4]);
            child.setDeltaMovement(Vec3.ZERO);
            child.fallDistance = 0f;
            child.setNoGravity(true);
            return;
        }
        double dy = (FOOT_Y + 0.8) - (SEAT_Y + child.getEyeHeight());
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, CENTRE_Z - SEAT_Z));
        child.connection.teleport(SEAT_X, SEAT_Y, SEAT_Z, 0.0f, pitch);
        child.setDeltaMovement(Vec3.ZERO);
        child.fallDistance = 0f;
        child.setNoGravity(true);
    }
}
