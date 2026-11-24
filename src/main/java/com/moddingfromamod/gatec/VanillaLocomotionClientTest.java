package com.moddingfromamod.gatec;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.entity.ModEntities;
import com.moddingfromamod.entity.WishMobEntity;
import com.moddingfromamod.runtime.CreatureAnatomy;
import com.moddingfromamod.runtime.CreatureFiles;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.SculptKits;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.vanilla.RigGeometry;
import com.moddingfromamod.vanilla.RigLocomotion;
import com.moddingfromamod.vanilla.VanillaRigAnimator;
import com.moddingfromamod.vanilla.VanillaRigBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * <b>Gate C — a wished creature flying, swimming, hopping,
 * sitting and sleeping, in the same frame as the vanilla mob it is modelled on, beside a CONTROL
 * that would show the bug.</b>
 *
 * <p>Every subject is the shipping path: a real species is registered, a wish mob is spawned, and
 * {@link VanillaRigBridge} gives it a real carrier that {@link VanillaRigAnimator} drives. The film
 * only ever changes what the CARRIER is doing — where it is, how fast it moves, whether it stands
 * on the ground, whether it was told to stay, the time of day — and reads which layer the gait
 * chose off the animator.
 *
 * <h2>The controls, and why each one is false of the bug (ladder §2.2)</h2>
 * <ul>
 *   <li><b>fly</b>: the same winged species standing on the floor. A rig that flapped whenever it
 *       had wings would beat on the ground at the flight rate; the control's lane motion and its
 *       {@code fly} weight both say whether it does.</li>
 *   <li><b>swim</b>: the same swimmer on dry land. A rig that swam whenever it had a tail would
 *       sweep it on the floor.</li>
 *   <li><b>hop</b>: the same sculpt WITHOUT {@code hops}, walked over the same ground. A rig that
 *       played JUMP on any movement would leap here; the control must never be in JUMP.</li>
 *   <li><b>sit / sleep</b>: the same sculpt, not told to stay and without {@code naps}. A rig that
 *       sat or slept on its own would lie down beside the one that should.</li>
 * </ul>
 *
 * <p>Lanes run left to right across the frame: vanilla reference(s), the wished creature, the
 * control. Each lane's motion is the mean changed pixels between consecutive frames inside its own
 * third of the picture.
 *
 * <p>Properties: {@code takes} (default {@code fly,swim,hop,sit}); {@code sculpt.<take>} a path to
 * a {@code sculpt.json} to film instead of the starter one.
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class VanillaLocomotionClientTest {

    private VanillaLocomotionClientTest() {
    }

    private static final String P = ModdingFromAModMod.MODID + ".";
    private static final boolean ENABLED =
            "vanillalocomotion".equalsIgnoreCase(System.getProperty(P + "testmode", ""));
    private static final Path SIG = Path.of(
            System.getProperty(P + "sigdir", "/tmp/moddingfromamod-vanillalocomotion"));
    private static final List<String> TAKES = List.of(
            System.getProperty(P + "takes", "fly+hop+sit+swim").split("[,+]"));

    private enum Phase { WAIT_TITLE, WAIT_INGAME, STAGE, SETUP, SETTLE, RUN, DONE }

    private static Phase phase = Phase.WAIT_TITLE;
    private static int ticks;
    private static int take = -1;
    private static long filmStart;
    private static final List<String> NOTES = new ArrayList<>();
    private static final List<String> BAD = new ArrayList<>();
    private static final Map<String, String> RESULTS = new LinkedHashMap<>();

    // ---- the stage. The camera looks +Z; lanes run along X.
    private static final double CAM_X = 8.5;
    private static final double FOOT_Y = 100.0;
    /**
     * ("record a winged creature flying"): {@code flypath=loop} flies the airborne flier round
     * a real loop (across, toward and away from the camera, climbing and diving, the body facing where it goes) instead
     * of hovering in place, so a film shows a creature MOVING through the air; {@code flyticks} sets the take's length.
     */
    private static final String FLY_PATH = System.getProperty(P + "flypath", "hover");
    private static final int FLY_TICKS = Integer.getInteger(P + "flyticks", 100);
    /** The game's tick rate while filming, as VanillaWalkClientTest's: 5 films at 4x slow motion; 20 changes nothing. */
    private static final int FILM_TICK_RATE = Integer.getInteger(P + "filmtickrate", 20);
    private static final int FLOOR_Y = (int) FOOT_Y - 1;
    private static final double STAGE_Z = 20.5;
    private static final double LANE = 2.4;
    /** Every subject is sized to this many blocks across, so a lane holds it. */
    private static final double FIT_BLOCKS = 1.5;

    /** Server-side scripts, run BEFORE the animator reads the carrier each tick. */
    private static final Map<java.util.UUID, java.util.function.IntConsumer> SCRIPTS =
            new ConcurrentHashMap<>();
    private static final Map<String, Mob> SUBJECTS = new ConcurrentHashMap<>();
    private static final List<Entity> SPAWNED = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static volatile int serverTick;

    // per-take measurements, written on the server thread
    private static final Map<String, Map<String, Integer>> LAYERS = new ConcurrentHashMap<>();
    private static final Map<String, Float> PEAK_FLY = new ConcurrentHashMap<>();
    private static final Map<String, Float> PEAK_SWIM = new ConcurrentHashMap<>();
    private static final Map<String, Float> PEAK_PITCH = new ConcurrentHashMap<>();
    private static final Map<String, Double> TRAVEL = new ConcurrentHashMap<>();
    private static final Map<String, Vec3> START = new ConcurrentHashMap<>();
    private static int[][] lastFrame;
    private static final long[] LANE_MOTION = new long[4];
    private static int frames;

    // ===================== the client tick =====================

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
                    if (ClientHarness.bootstrapWorld(mc, "vanilla-locomotion", ticks, true)) {
                        go(Phase.STAGE);
                    }
                }
                case STAGE -> {
                    if (ClientHarness.inWorld(mc) && ticks > ClientHarness.INGAME_SETTLE) {
                        if (ticks == ClientHarness.INGAME_SETTLE + 1) {
                            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
                            ClientHarness.quietParticles(mc);
                            onServer(mc, VanillaLocomotionClientTest::buildStage);
                        }
                        hold(mc, cameraFor(null));
                        if (ticks > ClientHarness.INGAME_SETTLE + 40) {
                            nextTake();
                        }
                    }
                }
                case SETUP -> {
                    hold(mc, cameraFor(current()));
                    if (ticks == 2 && FILM_TICK_RATE != 20 && mc.player != null) {
                        mc.player.connection.sendCommand("tick rate " + FILM_TICK_RATE);
                    }
                    if (ticks == 1) {
                        onServer(mc, s -> setUp(s, current()));
                    }
                    if (ticks == 25) {
                        String t = current();
                        var cam = mc.player == null ? null : mc.player.position();
                        onServer(mc, s -> {
                            int floor = 0;
                            for (int x = (int) CAM_X - 16; x <= (int) CAM_X + 16; x++) {
                                for (int z = (int) STAGE_Z - 8; z <= (int) STAGE_Z + 14; z++) {
                                    if (s.getBlockState(new BlockPos(x, FLOOR_Y, z)).is(Blocks.WHITE_CONCRETE)) {
                                        floor++;
                                    }
                                }
                            }
                            var sp = s.getServer().getPlayerList().getPlayers().stream().findFirst()
                                    .map(pl -> pl.position().toString()).orElse("none");
                            NOTES.add(t + " stage: " + floor + " of 759 floor blocks; client camera "
                                    + cam + ", server player " + sp);
                        });
                    }
                    if (ticks > 30) {
                        go(Phase.SETTLE);
                    }
                }
                case SETTLE -> {
                    hold(mc, cameraFor(current()));
                    if (ticks > settleTicks(current())) {
                        filmStart = System.currentTimeMillis();
                        lastFrame = null;
                        java.util.Arrays.fill(LANE_MOTION, 0);
                        frames = 0;
                        go(Phase.RUN);
                    }
                }
                case RUN -> run(mc);
                default -> { }
            }
        } catch (Exception e) {
            finish(false, "threw " + e);
        }
    }

    private static String current() {
        return take >= 0 && take < TAKES.size() ? TAKES.get(take).trim() : "";
    }

    private static void nextTake() {
        take++;
        if (take >= TAKES.size()) {
            RESULTS.forEach((k, v) -> NOTES.add(k + ": " + v));
            finish(BAD.isEmpty(), String.join("; ", BAD));
            return;
        }
        go(Phase.SETUP);
    }

    private static int settleTicks(String t) {
        return switch (t) {
            case "sit" -> 20;
            default -> 40;
        };
    }

    /** How long each take films. */
    private static int runTicks(String t) {
        return switch (t) {
            case "fly" -> FLY_TICKS;
            case "swim" -> 140;
            case "hop" -> 260;
            case "sit" -> 540;
            default -> 100;
        };
    }

    // ===================== a take =====================

    private static void run(Minecraft mc) {
        String t = current();
        hold(mc, cameraFor(t));
        if (ticks % 2 == 0) {
            measureFrame(mc);
        }
        if (ticks == 40 || ticks == runTicks(t) - 5) {
            ClientHarness.snap(mc, SIG, t + "-" + ticks, true);
        }
        if ("sit".equals(t)) {
            sitScript(mc);
        }
        if (ticks < runTicks(t)) {
            return;
        }
        writeFilm(t);
        score(t);
        onServer(mc, VanillaLocomotionClientTest::clearTake);
        go(Phase.STAGE);
        // STAGE re-enters nextTake after its settle; skip the stage build by jumping ticks
        ticks = ClientHarness.INGAME_SETTLE + 2;
    }

    /** The day/night beats of the sit take, on the CLIENT clock: day, then night, then day again. */
    /**
     * The sit take's beats, on camera, so every transition is in the film (a tester could not
     * tell whether the first film's kitten sat down or was sitting from the start -- it had sat
     * during the settle, before the recording window opened):
     * standing → told to stay (it sits) → night (it lies down asleep) → morning (back up to its
     * sit) → let go (it stands).
     */
    private static void sitScript(Minecraft mc) {
        if (ticks == SIT_STAY) {
            onServer(mc, s -> stay(SUBJECTS.get("wished"), 0));
        }
        if (ticks == SIT_NIGHT) {
            onServer(mc, s -> s.setDayTime(18000L));
        }
        if (ticks == SIT_DAWN) {
            onServer(mc, s -> s.setDayTime(6000L));
        }
        if (ticks == SIT_FREE) {
            onServer(mc, s -> stay(SUBJECTS.get("wished"), com.moddingfromamod.mob.PetBehaviour.STAY_OFF));
        }
        if (ticks >= SIT_HOLD_FROM && ticks < SIT_HOLD_TO && ticks % 2 == 0) {
            measureHold(mc);
        }
    }

    static final int SIT_STAY = 40;
    static final int SIT_HOLD_FROM = 110;
    static final int SIT_HOLD_TO = 190;
    static final int SIT_NIGHT = 200;
    static final int SIT_DAWN = 420;
    static final int SIT_FREE = 480;

    private static void stay(Mob m, int stay) {
        if (m != null) {
            com.moddingfromamod.mob.PetBehaviours.apply(m,
                    new com.moddingfromamod.mob.PetBehaviour(null, null, null, null, stay, null), null);
        }
    }

    // ---- the held-sit stillness check: pixels changed per frame inside each subject's own column
    private static int[][] holdLast;
    private static final long[] HOLD = new long[3];   // control, wished, wolf
    private static int holdFrames;

    /** Each subject's screen column, projected from where it stands (+X is screen-LEFT). */
    private static int[] column(int w, int h, double worldX) {
        double[] c = cameraFor("sit");
        double depth = STAGE_Z - c[2];
        double f = (h / 2.0) / Math.tan(Math.toRadians(35));   // vertical FOV 70
        int centre = (int) Math.round(w / 2.0 - f * (worldX - c[0]) / depth);
        int half = (int) Math.round(f * 0.55 * FIT_BLOCKS / depth);
        return new int[]{Math.max(0, centre - half), Math.min(w, centre + half)};
    }

    private static void measureHold(Minecraft mc) {
        int[][] f = grab(mc);
        if (f == null) {
            return;
        }
        if (holdLast != null) {
            int h = f.length;
            int w = f[0].length;
            double[] xs = {SIT_X[3], SIT_X[2], SIT_X[1]};
            for (int k = 0; k < 3; k++) {
                int[] col = column(w, h, xs[k]);
                for (int y = h / 4; y < h; y++) {
                    for (int x = col[0]; x < col[1]; x++) {
                        if (delta(f[y][x], holdLast[y][x]) > 60) {
                            HOLD[k]++;
                        }
                    }
                }
            }
            holdFrames++;
        }
        holdLast = f;
    }

    private static void score(String t) {
        double frameCount = Math.max(1, frames);
        double[] lane = new double[3];
        for (int i = 0; i < 3; i++) {
            lane[i] = LANE_MOTION[i] / frameCount;
        }
        Map<String, Integer> wished = LAYERS.getOrDefault("wished", Map.of());
        Map<String, Integer> control = LAYERS.getOrDefault("control", Map.of());
        String motion = String.format("lane motion px/frame: vanilla %.0f, wished %.0f, control %.0f",
                lane[0], lane[1], lane[2]);
        RESULTS.put(t + " layers", "wished " + wished + " | control " + control);
        RESULTS.put(t + " motion", "hop".equals(t)
                ? "n/a -- the hop rows are depths, not thirds; its oracles are the layers and travel"
                : motion);
        switch (t) {
            case "fly" -> {
                expect(PEAK_FLY.getOrDefault("wished", 0f) > 0.95f, "fly: the airborne flier never "
                        + "reached FLY (" + PEAK_FLY.get("wished") + ")");
                expect(PEAK_FLY.getOrDefault("control", 0f) == 0f, "fly: the GROUNDED control "
                        + "flew (" + PEAK_FLY.get("control") + ")");
                expect(lane[1] > 3 * Math.max(1, lane[2]), "fly: the airborne wings moved "
                        + String.format("%.0f px/frame against the grounded control's %.0f -- "
                        + "a beat must be several times a twitch", lane[1], lane[2]));
                expect(lane[0] > 0, "fly: the vanilla flier did not move at all");
            }
            case "swim" -> {
                expect(PEAK_SWIM.getOrDefault("wished", 0f) > 0.95f, "swim: the swimmer never "
                        + "reached SWIM (" + PEAK_SWIM.get("wished") + ")");
                expect(PEAK_SWIM.getOrDefault("control", 0f) == 0f, "swim: the control on LAND "
                        + "swam (" + PEAK_SWIM.get("control") + ")");
                expect(Math.abs(PEAK_PITCH.getOrDefault("wished", 0f)) > 10f, "swim: the body "
                        + "never pitched toward its travel (" + PEAK_PITCH.get("wished") + ")");
                expect(lane[1] > 1.5 * Math.max(1, lane[2]), "swim: the swimming lane moved "
                        + String.format("%.0f px/frame against land's %.0f", lane[1], lane[2]));
            }
            case "hop" -> {
                int jumps = wished.getOrDefault("JUMP", 0);
                expect(jumps > 10, "hop: the hopper was in JUMP on only " + jumps + " ticks");
                expect(control.getOrDefault("JUMP", 0) == 0, "hop: the WALKING control played "
                        + "JUMP on " + control.get("JUMP") + " ticks");
                double wt = TRAVEL.getOrDefault("wished", 0.0);
                double ct = TRAVEL.getOrDefault("control", 0.0);
                RESULTS.put("hop travel", String.format("hopper %.1f blocks, walker %.1f", wt, ct));
                expect(wt > 2.0, "hop: the hopper did not get anywhere (" + wt + " blocks)");
                expect(ct > 2.0, "hop: the walking control did not walk (" + ct + " blocks), so it "
                        + "proves nothing about JUMP");
            }
            case "sit" -> {
                expect(wished.getOrDefault("SIT", 0) > 60, "sit: the staying creature sat on "
                        + "only " + wished.getOrDefault("SIT", 0) + " ticks");
                expect(wished.getOrDefault("SLEEP", 0) > 60, "sit: the napper slept on only "
                        + wished.getOrDefault("SLEEP", 0) + " ticks of the night");
                double n = Math.max(1, holdFrames);
                double still = HOLD[1] / n;
                double idle = HOLD[0] / n;
                RESULTS.put("sit: displays re-sent per tick while held", "wished "
                        + RESENT.getOrDefault("wished", 0L) + "/" + RESENT_TICKS.getOrDefault("wished", 0L)
                        + " ticks, control " + RESENT.getOrDefault("control", 0L) + "/"
                        + RESENT_TICKS.getOrDefault("control", 0L) + " by data id " + RESENT_IDS);
                RESULTS.put("sit: held still", String.format("while the sit is held, px changed per "
                        + "frame: sitting %.1f, standing control %.1f, static vanilla wolf %.1f",
                        still, idle, HOLD[2] / n));
                // The oracle that is FALSE of the bug: a held sit sends its displays nothing. Before
                // the fix every display of the sitting creature was re-sent each write (390 in 80
                // ticks) with an unchanged pose, and each re-send restarted its tween from the pose
                // before -- the twitch. Pixels are the second instrument; they swing with load.
                expect(RESENT.getOrDefault("wished", 0L) == 0, "sit: a HELD sit re-sent "
                        + RESENT.getOrDefault("wished", 0L) + " displays over "
                        + RESENT_TICKS.getOrDefault("wished", 0L) + " ticks -- every re-send snaps "
                        + "a display back to its previous pose");
                expect(still < 0.5 * Math.max(4, idle), String.format("sit: a HELD sit is not "
                        + "still -- %.1f px/frame against the standing idle's %.1f", still, idle));
                expect(control.getOrDefault("SIT", 0) == 0 && control.getOrDefault("SLEEP", 0) == 0
                        && control.getOrDefault("LIE", 0) == 0, "sit: the control (not staying, "
                        + "no naps) sat, lay or slept: " + control);
            }
            default -> { }
        }
        LAYERS.clear();
        PEAK_FLY.clear();
        PEAK_SWIM.clear();
        PEAK_PITCH.clear();
        TRAVEL.clear();
        START.clear();
    }

    private static void expect(boolean ok, String failure) {
        if (!ok) {
            BAD.add(failure);
        }
    }

    // ===================== the server side =====================

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!ENABLED) {
            return;
        }
        serverTick++;
        for (var e : SCRIPTS.entrySet()) {
            try {
                e.getValue().accept(serverTick);
            } catch (Exception ex) {
                BAD.add("a carrier script threw " + ex);
            }
        }
    }

    private static final Map<String, Long> RESENT_IDS = new java.util.TreeMap<>();

    /** Which synched-data ids are dirty right now (reflection: a diagnosis, not a path). */
    private static List<Integer> dirtyIds(net.minecraft.network.syncher.SynchedEntityData data) {
        List<Integer> out = new ArrayList<>();
        try {
            for (var f : data.getClass().getDeclaredFields()) {
                if (f.getType().isArray()) {
                    f.setAccessible(true);
                    Object[] items = (Object[]) f.get(data);
                    for (int i = 0; i < items.length; i++) {
                        Object it = items[i];
                        if (it == null) {
                            continue;
                        }
                        var m = it.getClass().getMethod("isDirty");
                        if (Boolean.TRUE.equals(m.invoke(it))) {
                            out.add(i);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            out.add(-1);
        }
        return out;
    }

    /** Displays the animator re-sent on a tick of the held sit, per subject (an earlier change's twitch). */
    private static final Map<String, Long> RESENT = new ConcurrentHashMap<>();
    private static final Map<String, Long> RESENT_TICKS = new ConcurrentHashMap<>();

    /** After the animator: what layer each subject is in. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerTickRead(ServerTickEvent.Post event) {
        if (!ENABLED || phase != Phase.RUN) {
            return;
        }
        if ("sit".equals(current()) && ticks >= SIT_HOLD_FROM && ticks < SIT_HOLD_TO) {
            for (var e : SUBJECTS.entrySet()) {
                Mob m = e.getValue();
                String own = com.moddingfromamod.vanilla.DisplayRig.RIG_OWNER_PREFIX + m.getUUID();
                long dirty = 0;
                for (Entity d : m.level().getEntities((Entity) null, m.getBoundingBox().inflate(4),
                        x -> x instanceof net.minecraft.world.entity.Display && x.getTags().contains(own))) {
                    if (d.getEntityData().isDirty()) {
                        dirty++;
                        for (int id : dirtyIds(d.getEntityData())) {
                            RESENT_IDS.merge(e.getKey() + "#" + id, 1L, Long::sum);
                        }
                    }
                }
                RESENT.merge(e.getKey(), dirty, Long::sum);
                RESENT_TICKS.merge(e.getKey(), 1L, Long::sum);
            }
        }
        for (var e : SUBJECTS.entrySet()) {
            Mob m = e.getValue();
            String layer = VanillaRigAnimator.layerOf(m);
            LAYERS.computeIfAbsent(e.getKey(), k -> new ConcurrentHashMap<>()).merge(layer, 1,
                    Integer::sum);
            RigLocomotion.Weights w = VanillaRigAnimator.weightsOf(m);
            if (w != null) {
                PEAK_FLY.merge(e.getKey(), w.fly(), Math::max);
                PEAK_SWIM.merge(e.getKey(), w.swim(), Math::max);
                PEAK_PITCH.merge(e.getKey(), Math.abs(w.travelPitch()) * (w.swim() > 0.5f ? 1 : 0),
                        Math::max);
            }
            Vec3 s = START.computeIfAbsent(e.getKey(), k -> m.position());
            TRAVEL.merge(e.getKey(), Math.abs(m.getX() - s.x) + Math.abs(m.getZ() - s.z), Math::max);
        }
    }

    private static void onServer(Minecraft mc, java.util.function.Consumer<ServerLevel> job) {
        var server = mc.getSingleplayerServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            ServerLevel level = server.overworld();
            try {
                job.accept(level);
            } catch (Exception e) {
                BAD.add("server job threw " + e);
            }
        });
    }

    private static void buildStage(ServerLevel level) {
        var gr = level.getServer().overworld().getGameRules();
        gr.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME,false, level.getServer());
        gr.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_WEATHER,false, level.getServer());
        gr.set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS,false, level.getServer());
        level.setDayTime(6000L);
        level.setWeatherParameters(6000, 0, false, false);
        var floor = Blocks.WHITE_CONCRETE.defaultBlockState();
        for (int x = (int) CAM_X - 16; x <= (int) CAM_X + 16; x++) {
            for (int z = (int) STAGE_Z - 8; z <= (int) STAGE_Z + 14; z++) {
                level.setBlock(new BlockPos(x, FLOOR_Y, z), floor, 3);
                for (int y = FLOOR_Y + 1; y < FLOOR_Y + 9; y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        // Invisible light so the night beat is still a picture.
        for (int x = (int) CAM_X - 8; x <= (int) CAM_X + 8; x += 4) {
            level.setBlock(new BlockPos(x, FLOOR_Y + 6, (int) STAGE_Z - 2),
                    Blocks.LIGHT.defaultBlockState(), 3);
            level.setBlock(new BlockPos(x, FLOOR_Y + 6, (int) STAGE_Z + 2),
                    Blocks.LIGHT.defaultBlockState(), 3);
        }
    }

    private static void clearTake(ServerLevel level) {
        SCRIPTS.clear();
        SUBJECTS.clear();
        for (Entity e : SPAWNED) {
            if (e instanceof Mob m) {
                com.moddingfromamod.vanilla.DisplayRig.reap(level, m.getUUID());
            }
            e.discard();
        }
        SPAWNED.clear();
        level.setDayTime(6000L);
        // drain the tank and the pens the take built
        for (int x = (int) CAM_X - 12; x <= (int) CAM_X + 12; x++) {
            for (int z = (int) STAGE_Z - 6; z <= (int) STAGE_Z + 12; z++) {
                for (int y = FLOOR_Y + 1; y < FLOOR_Y + 6; y++) {
                    var pos = new BlockPos(x, y, z);
                    if (!level.getBlockState(pos).is(Blocks.LIGHT)) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        }
    }

    private static double laneX(int i) {
        return CAM_X + (i - 1) * LANE;
    }

    private static void setUp(ServerLevel level, String t) {
        // The floor again, every take: in one run every take after the swim tank was drained drew
        // NO floor on the client while all 759 blocks were there on the server (counted). Laying it
        // again re-sends it; what the drain does to the client's chunk is not this film's subject.
        var floor = Blocks.WHITE_CONCRETE.defaultBlockState();
        for (int x = (int) CAM_X - 16; x <= (int) CAM_X + 16; x++) {
            for (int z = (int) STAGE_Z - 8; z <= (int) STAGE_Z + 14; z++) {
                level.setBlock(new BlockPos(x, FLOOR_Y, z), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(new BlockPos(x, FLOOR_Y, z), floor, 3);
            }
        }
        switch (t) {
            case "fly" -> setUpFly(level);
            case "swim" -> setUpSwim(level);
            case "hop" -> setUpHop(level);
            case "sit" -> setUpSit(level);
            default -> BAD.add("unknown take " + t);
        }
    }

    // ---- FLY: parrot + bee | the flier, airborne | the same flier, grounded
    private static void setUpFly(ServerLevel level) {
        double air = FOOT_Y + 2.0;
        vanilla(level, EntityType.PARROT, laneX(0) + 0.4, air, STAGE_Z, true);
        vanilla(level, EntityType.BEE, laneX(0) - 0.4, air + 0.9, STAGE_Z, true);
        String id = species(level, "fly", List.of("friendly", "flies"));
        Mob w = creature(level, id, laneX(1), air, STAGE_Z, "wished");
        Mob c = creature(level, id, laneX(2), FOOT_Y, STAGE_Z, "control");
        if ("loop".equals(FLY_PATH)) {
            loop(w, laneX(1), air);
        } else {
            hover(w, laneX(1), air, true);
        }
        hover(c, laneX(2), FOOT_Y, false);
    }

    /** One lap every 60 ticks (~3.7 blocks a second): 1 block across, 2 toward and away, 0.8 up and down. */
    private static void loop(Mob m, double x, double y) {
        SCRIPTS.put(m.getUUID(), tick -> {
            double a = tick * 2 * Math.PI / 60.0;
            double px = x + 1.0 * Math.sin(a);
            double pz = STAGE_Z + 1.0 + 2.0 * Math.cos(a);
            double py = y + 0.8 * Math.sin(2 * a);
            double w = 2 * Math.PI / 60.0;
            double vx = 1.0 * Math.cos(a) * w;
            double vz = -2.0 * Math.sin(a) * w;
            double vy = 1.6 * Math.cos(2 * a) * w;
            float yaw = (float) Math.toDegrees(Math.atan2(-vx, vz));
            m.setNoAi(true);
            m.setNoGravity(true);
            m.setPos(px, py, pz);
            m.setDeltaMovement(vx, vy, vz);
            m.setOnGround(false);
            m.setYRot(yaw);
            m.setYBodyRot(yaw);
            m.setYHeadRot(yaw);
        });
    }

    // ---- SWIM: dolphin + cod in the tank | the swimmer in the tank | the swimmer on land
    private static void setUpSwim(ServerLevel level) {
        // A tank across the two left lanes, walls of glass, the front one facing the camera.
        int x0 = (int) Math.floor(laneX(0) - 1.2);
        int x1 = (int) Math.floor(laneX(1) + 1.0);
        // The camera is IN the tank: seen from outside, 2.5 blocks of water tint everything one blue
        // and the swimmer is a shadow. Under water, with conduit power, it is clear to the far wall.
        int z0 = (int) STAGE_Z - 7;
        int z1 = (int) STAGE_Z + 2;
        for (int x = x0 - 1; x <= x1 + 1; x++) {
            for (int z = z0 - 1; z <= z1 + 1; z++) {
                for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 4; y++) {
                    boolean wall = x == x0 - 1 || x == x1 + 1 || z == z0 - 1 || z == z1 + 1;
                    level.setBlock(new BlockPos(x, y, z), wall ? Blocks.BARRIER.defaultBlockState()
                            : Blocks.WATER.defaultBlockState(), 3);
                }
            }
        }
        for (var pl : level.getServer().getPlayerList().getPlayers()) {
            pl.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.CONDUIT_POWER, 20 * 120, 0, true, false));
            pl.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.NIGHT_VISION, 20 * 120, 0, true, false));
        }
        double mid = FOOT_Y + 1.6;
        vanilla(level, EntityType.DOLPHIN, laneX(0), mid - 0.3, STAGE_Z, true);
        vanilla(level, EntityType.COD, laneX(0) + 0.3, mid + 1.0, STAGE_Z - 1, true);
        String id = species(level, "swim", List.of("friendly"));
        Mob w = creature(level, id, laneX(1), mid, STAGE_Z, "wished");
        Mob c = creature(level, id, laneX(2), FOOT_Y, STAGE_Z, "control");
        double lx = laneX(1);
        SCRIPTS.put(w.getUUID(), tick -> {
            // Round a slow loop: forward and up, then forward and down, so the body has to pitch.
            double a = tick / 30.0;
            double x = lx + 0.4 * Math.sin(a);
            double y = mid + 0.7 * Math.sin(a * 2);
            double vx = 0.4 * Math.cos(a) / 30.0;
            double vy = 1.4 * Math.cos(a * 2) / 30.0;
            place(w, x, y, STAGE_Z, vx, vy, false, vx >= 0 ? -90f : 90f);
        });
        hover(c, laneX(2), FOOT_Y, false);
    }

    // ---- HOP: three rows at three depths, each travelling left and right ACROSS the frame, so a
    // hop is seen side-on (its crouch, its arc, its landing) the way a rabbit's is. Nearest the
    // camera the hopper, then the walker, then the rabbit and the frog.
    static final double[] HOP_ROWS = {STAGE_Z - 3.0, STAGE_Z + 1.0, STAGE_Z + 5.0};

    private static void setUpHop(ServerLevel level) {
        int xl = (int) CAM_X - 5;
        int xr = (int) CAM_X + 5;
        for (double rz : HOP_ROWS) {
            int zc = (int) Math.floor(rz);
            for (int x = xl - 1; x <= xr + 1; x++) {
                for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 3; y++) {
                    // Three blocks wide inside: a one-block corridor held the bunny's carrier and
                    // jammed the starter frog's wider one, so the gate's run measured 0 blocks.
                    level.setBlock(new BlockPos(x, y, zc - 2), Blocks.BARRIER.defaultBlockState(), 3);
                    level.setBlock(new BlockPos(x, y, zc + 2), Blocks.BARRIER.defaultBlockState(), 3);
                    if (x == xl - 1 || x == xr + 1) {
                        for (int dz = -1; dz <= 1; dz++) {
                            level.setBlock(new BlockPos(x, y, zc + dz), Blocks.BARRIER.defaultBlockState(), 3);
                        }
                    }
                }
            }
        }
        String hopId = species(level, "hop", List.of("friendly", "hops"));
        String walkId = species(level, "hop", List.of("friendly"), "_walker");
        Mob w = creature(level, hopId, CAM_X - 3.5, FOOT_Y, rowCentre(0), "wished");
        Mob c = creature(level, walkId, CAM_X - 3.5, FOOT_Y, rowCentre(1), "control");
        Mob rabbit = vanilla(level, EntityType.RABBIT, CAM_X - 3.5, FOOT_Y, rowCentre(2), false);
        Mob frog = vanilla(level, EntityType.FROG, CAM_X + 3.0, FOOT_Y, rowCentre(2), false);
        for (Mob m : new Mob[]{w, c, rabbit, frog}) {
            if (m == null) {
                continue;
            }
            double mz = m.getZ();
            SCRIPTS.put(m.getUUID(), tick -> {
                // Across and back, the same goal for every subject.
                if (tick % 90 == 0) {
                    double tx = (tick / 90) % 2 == 0 ? CAM_X + 4 : CAM_X - 4;
                    if (m instanceof net.minecraft.world.entity.animal.rabbit.Rabbit r) {
                        r.getMoveControl().setWantedPosition(tx, FOOT_Y, mz, 1.4);
                    } else {
                        m.getNavigation().moveTo(tx, FOOT_Y, mz, 1.0);
                    }
                }
            });
        }
    }

    private static double rowCentre(int i) {
        return Math.floor(HOP_ROWS[i]) + 0.5;
    }

    static final float SIT_YAW = 125f;
    /** fox, wolf, the wished creature, the control -- left to right in the WORLD (+X is screen-left). */
    static final double[] SIT_X = {CAM_X - 3.6, CAM_X - 1.2, CAM_X + 1.2, CAM_X + 3.6};

    // ---- SIT/SLEEP: fox asleep, wolf sitting | the staying napper | the same, free and no naps
    private static void setUpSit(ServerLevel level) {
        double[] x = SIT_X;
        Entity fox = sleepingFox(level, x[0], FOOT_Y, STAGE_Z);
        Mob wolf = vanilla(level, EntityType.WOLF, x[1], FOOT_Y, STAGE_Z, false);
        if (wolf instanceof net.minecraft.world.entity.TamableAnimal tame) {
            tame.setInSittingPose(true);
            tame.setNoAi(true);
        }
        String napId = species(level, "sit", List.of("friendly", "naps"));
        String plainId = species(level, "sit", List.of("friendly"), "_plain");
        Mob w = creature(level, napId, x[2], FOOT_Y, STAGE_Z, "wished");
        Mob c = creature(level, plainId, x[3], FOOT_Y, STAGE_Z, "control");
        // Held on the floor by script: a NoAI carrier never runs the physics that set onGround,
        // and the gait (rightly) only sits a creature that is standing on something.
        // Three-quarters on, so a sit reads in PROFILE: head-on, a sitting animal and a standing one
        // are the same picture (the first film could not tell the vanilla wolf's sit either).
        hover(w, x[2], FOOT_Y, false, SIT_YAW);
        hover(c, x[3], FOOT_Y, false, SIT_YAW);
        for (Entity e : new Entity[]{fox, wolf}) {
            if (e instanceof Mob m) {
                m.setYRot(SIT_YAW);
                m.setYBodyRot(SIT_YAW);
                m.setYHeadRot(SIT_YAW);
            }
        }
        // NOT told to stay yet: that happens on camera (sitScript), so the film shows it sit down.
        NOTES.add("sit: fox " + (fox != null) + ", wolf sitting "
                + (wolf instanceof net.minecraft.world.entity.TamableAnimal t2 && t2.isInSittingPose()));
    }

    // ===================== subjects =====================

    /** A carrier held where the script puts it: in the air, or standing on the floor. */
    private static void hover(Mob m, double x, double y, boolean airborne) {
        hover(m, x, y, airborne, 180f);
    }

    private static void hover(Mob m, double x, double y, boolean airborne, float yaw) {
        SCRIPTS.put(m.getUUID(), tick -> {
            if (airborne) {
                double a = tick / 25.0;
                double vx = 0.012 * Math.cos(a);
                place(m, x + 0.3 * Math.sin(a), y + 0.25 * Math.sin(a * 1.7), STAGE_Z, vx,
                        0.01 * Math.cos(a * 1.7), false, 180f);
            } else {
                place(m, x, y, STAGE_Z, 0, 0, true, yaw);
            }
        });
    }

    private static void place(Mob m, double x, double y, double z, double vx, double vy,
                              boolean onGround, float yaw) {
        m.setNoAi(true);
        m.setNoGravity(true);
        m.setPos(x, y, z);
        m.setDeltaMovement(vx, vy, 0);
        m.setOnGround(onGround);
        m.setYRot(yaw);
        m.setYBodyRot(yaw);
        m.setYHeadRot(yaw);
    }

    private static Mob vanilla(ServerLevel level, EntityType<? extends Mob> type, double x, double y,
                               double z, boolean frozen) {
        Mob m = type.create(level, EntitySpawnReason.COMMAND);
        if (m == null) {
            BAD.add("could not create " + type);
            return null;
        }
        m.snapTo(x, y, z, 180f, 0f);
        m.setYHeadRot(180f);
        m.setYBodyRot(180f);
        m.setInvulnerable(true);
        m.setPersistenceRequired();
        m.setSilent(true);
        if (frozen) {
            m.setNoAi(true);
            m.setNoGravity(true);
        }
        level.addFreshEntity(m);
        SPAWNED.add(m);
        return m;
    }

    /** A vanilla fox ASLEEP: `Sleeping` is only reachable through its saved data on both versions. */
    private static Entity sleepingFox(ServerLevel level, double x, double y, double z) {
        Mob fox = vanilla(level, EntityType.FOX, x, y, z, false);
        if (fox == null) {
            return null;
        }
        var tag = com.moddingfromamod.compat.EntityNbt.save(fox);
        tag.putBoolean("Sleeping", true);
        tag.putBoolean("NoAI", true);
        com.moddingfromamod.compat.EntityNbt.load(fox, tag);
        return fox;
    }

    /** A species for a take, from its sculpt, with these powers. */
    private static String species(ServerLevel level, String take, List<String> powers) {
        return species(level, take, powers, "");
    }

    private static String species(ServerLevel level, String take, List<String> powers,
                                  String suffix) {
        String id = "vloco_" + take + suffix;
        JsonObject spec = sculptFor(take);
        List<CreatureSculpt.Part> parts = CreatureSculpt.parseParts(spec.getAsJsonArray("parts"));
        if (spec.has("kits") && spec.get("kits").isJsonArray()) {
            List<CreatureSculpt.Part> grown = new ArrayList<>(parts);
            grown.addAll(SculptKits.expand(spec.getAsJsonArray("kits"), parts));
            parts = grown;
        }
        try {
            CreatureFiles.write(id, parts, spec.toString());
        } catch (Exception e) {
            BAD.add("could not write " + id + ": " + e);
        }
        var ext = CreatureAnatomy.extents(RigGeometry.of(parts));
        double across = Math.max(CreatureAnatomy.widthBlocks(ext), 0.2);
        double scale = Math.min(2.0, FIT_BLOCKS / across);
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", "Locomotion " + take + suffix);
        o.addProperty("modelKey", id);
        o.addProperty("health", 40);
        o.addProperty("speed", 0.25);
        o.addProperty("scale", scale);
        o.addProperty("base", "wild");
        JsonArray pw = new JsonArray();
        powers.forEach(pw::add);
        o.add("powers", pw);
        SpeciesRegistry.put(level.getServer(), Species.fromJson(o));
        NOTES.add(take + suffix + ": sculpt " + sculptSource(take) + ", " + parts.size()
                + " parts, scale " + String.format("%.2f", scale) + ", powers " + powers);
        return id;
    }

    /** The real shipped path: a wish mob joins, the bridge turns it into a rig. */
    private static Mob creature(ServerLevel level, String id, double x, double y, double z,
                                String role) {
        WishMobEntity mob = ModEntities.WISH_MOB.get().create(level, EntitySpawnReason.COMMAND);
        mob.snapTo(x, y, z, 180f, 0f);
        mob.setSpeciesId(id);
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()),
                EntitySpawnReason.COMMAND, null);
        LivingEntity carrier = VanillaRigBridge.manifestNow(level, mob);
        if (!(carrier instanceof Mob m)) {
            BAD.add(role + ": the bridge did not make a rig for " + id);
            throw new IllegalStateException("no rig for " + id);
        }
        m.setInvulnerable(true);
        m.setSilent(true);
        SUBJECTS.put(role, m);
        SPAWNED.add(m);
        NOTES.add(role + " " + id + " rides a "
                + net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()));
        return m;
    }

    /** The agent's own sculpt for a take, archived with the smoke pass that chose it. */
    static final String PASS = "corpus/wish-runs/2026-10-09-locomotion-1481/sculpts/";

    private static String agentSculpt(String take) {
        String name = switch (take) {
            case "fly" -> "fly_dragon_puppy";
            case "swim" -> "swim_glitter_shark";
            case "hop" -> "hop_purple_bunny";
            default -> "sit_nap_candy_kitten";
        };
        Path p = com.moddingfromamod.vanilla.SpikeFixture.find(PASS + name + ".sculpt.json");
        return p == null ? "" : p.toString();
    }

    private static String sculptPath(String take) {
        String path = System.getProperty(P + "sculpt." + take, "");
        return path.isBlank() ? agentSculpt(take) : path;
    }

    private static String sculptSource(String take) {
        String path = sculptPath(take);
        return path.isBlank() ? "starter " + starterFor(take) : path;
    }

    private static String starterFor(String take) {
        return switch (take) {
            case "fly" -> "pet_dragon";
            case "swim" -> "turtle";
            case "hop" -> "frog";
            default -> "cat";
        };
    }

    private static JsonObject sculptFor(String take) {
        String path = sculptPath(take);
        try {
            String text;
            if (!path.isBlank()) {
                text = Files.readString(Path.of(path), StandardCharsets.UTF_8);
            } else {
                try (var in = com.moddingfromamod.plugin.ModResources.open(
                        "/starter-library/species/" + starterFor(take) + "/sculpt.json")) {
                    text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            return JsonParser.parseString(text).getAsJsonObject();
        } catch (Exception e) {
            throw new IllegalStateException("no sculpt for " + take + " (" + path + ")", e);
        }
    }

    // ===================== the camera and the instruments =====================

    /** {x, y, z, yaw, pitch} for a take. */
    private static double[] cameraFor(String t) {
        if ("hop".equals(t)) {
            return new double[]{CAM_X, FOOT_Y + 3.6, STAGE_Z - 9.0, 0, 18};
        }
        if ("fly".equals(t)) {
            return new double[]{CAM_X, FOOT_Y + 2.2, STAGE_Z - 5.5, 0, 6};
        }
        if ("swim".equals(t)) {
            return new double[]{CAM_X, FOOT_Y + 1.7, STAGE_Z - 5.5, 0, 4};
        }
        return new double[]{CAM_X, FOOT_Y + 1.9, STAGE_Z - 4.6, 0, 15};
    }

    private static void hold(Minecraft mc, double[] c) {
        if (mc.player == null) {
            return;
        }
        mc.options.hideGui = true;
        mc.setScreen(null);
        mc.player.getAbilities().flying = true;
        mc.player.setPos(c[0], c[1], c[2]);
        mc.player.setDeltaMovement(Vec3.ZERO);
        mc.player.setYRot((float) c[3]);
        mc.player.setXRot((float) c[4]);
        mc.player.xOld = c[0];
        mc.player.yOld = c[1];
        mc.player.zOld = c[2];
        mc.player.yRotO = (float) c[3];
        mc.player.xRotO = (float) c[4];
    }

    private static void measureFrame(Minecraft mc) {
        int[][] f = grab(mc);
        if (f == null) {
            return;
        }
        if (lastFrame != null) {
            int h = f.length;
            int w = f[0].length;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (delta(f[y][x], lastFrame[y][x]) > 60) {
                        // Looking +Z puts +X on the LEFT of the screen, so lane 0 (lowest X) is the right third.
                        LANE_MOTION[2 - Math.min(2, x * 3 / w)]++;
                    }
                }
            }
            frames++;
        }
        lastFrame = f;
    }

    private static int delta(int a, int b) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF))
                + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                + Math.abs((a & 0xFF) - (b & 0xFF));
    }

    private static int[][] grab(Minecraft mc) {
        if (mc.getOverlay() != null) {
            return null;
        }
        try (var img = Screenshots.take(mc.getMainRenderTarget())) {
            int w = img.getWidth();
            int h = img.getHeight();
            int[][] px = new int[h / 2][w / 2];
            for (int y = 0; y < h / 2; y++) {
                for (int x = 0; x < w / 2; x++) {
                    int abgr = com.moddingfromamod.compat.Pixels.abgr(img, x * 2, y * 2);
                    px[y][x] = ((abgr & 0xFF) << 16) | (abgr & 0xFF00) | ((abgr >> 16) & 0xFF);
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
            Files.createDirectories(windows.getParent());
            Files.writeString(windows,
                    filmStart + "\t" + System.currentTimeMillis() + "\t" + label + "\n",
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // no recorder attached
        }
        filmStart = 0L;
    }

    private static void go(Phase next) {
        phase = next;
        ticks = 0;
    }

    private static void finish(boolean pass, String message) {
        ClientHarness.verdict(SIG, pass,
                (pass ? "PASS" : "FAIL — " + message) + "\n" + String.join("\n", NOTES));
        phase = Phase.DONE;
        Minecraft.getInstance().stop();
    }
}
