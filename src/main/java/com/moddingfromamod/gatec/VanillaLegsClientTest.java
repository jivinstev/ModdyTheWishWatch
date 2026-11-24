package com.moddingfromamod.gatec;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import com.moddingfromamod.vanilla.DisplayRig;
import com.moddingfromamod.vanilla.RigAnatomy;
import com.moddingfromamod.vanilla.RigGeometry;
import com.moddingfromamod.vanilla.RigLocomotion;
import com.moddingfromamod.vanilla.RigPlan;
import com.moddingfromamod.vanilla.VanillaRigAnimator;
import com.moddingfromamod.vanilla.VanillaRigBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * <b>Gate C — a spider, an insect, a horse whose hind legs are {@code legBL/legBR} and a
 * biped, WALKING, filmed close enough to see every leg.</b>
 *
 * <p>Each subject is the shipping path (a species, a wish mob, the bridge's carrier, the shipping
 * animator) moved by vanilla's own {@code travel()}: AI on, its own speed zeroed, a velocity set
 * every tick, facing the way it goes. A {@code NoAI} carrier never animates its walk
 * (VANILLA_RIG_PARITY §11), so the stride comes off the same limb speed and distance the renderer
 * reads. The camera rides beside it, side-on and then at three quarters from the front, over a
 * CHECKERED floor: a foot that is planted stays on its square while the body passes over it, and
 * one that slides (a moon-walk, ladder §2.2) slides across the squares.
 *
 * <p>Oracles per take, read off the server: the legs the rig found, how many of them STEPPED (a leg
 * display whose pose changed, the issue's count), the body display as the control that must NOT
 * move, and the limb speed (a take that never walked proves nothing). Whether the step LOOKS right
 * is the film's, and a person's.
 *
 * <p>Properties: {@code takes} ({@code +}-separated {@code <subject>-<side|3q>}); {@code sculpt.<subject>}
 * a sculpt to film instead of the built-in one; {@code -Dmoddingfromamod.locomotion=off} films the
 * rig as it was (the clip alone).
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class VanillaLegsClientTest {

    private VanillaLegsClientTest() {
    }

    private static final String P = ModdingFromAModMod.MODID + ".";
    private static final boolean ENABLED =
            "vanillalegs".equalsIgnoreCase(System.getProperty(P + "testmode", ""));
    private static final Path SIG = Path.of(
            System.getProperty(P + "sigdir", "/tmp/moddingfromamod-vanillalegs"));
    private static final List<String> TAKES = List.of(System.getProperty(P + "takes",
            "spider-side+spider-3q+tarantula-side+tarantula-front+ant-side+ant-3q+horse-side+horse-3q"
            + "+person-side+person-3q")
            .split("[,+]"));
    private static final boolean OLD = "off".equals(System.getProperty(P + "locomotion"));

    private enum Phase { WAIT_TITLE, WAIT_INGAME, STAGE, SETUP, SETTLE, RUN, DONE }

    private static volatile Phase phase = Phase.WAIT_TITLE;
    private static int ticks;
    private static int take = -1;
    private static long filmStart;
    private static final List<String> NOTES = new ArrayList<>();
    private static final List<String> BAD = new ArrayList<>();

    private static final double FOOT_Y = 100.0;
    private static final int FLOOR_Y = (int) FOOT_Y - 1;
    private static final double START_X = 0.5;
    private static final double LANE_Z = 0.5;
    /** Every subject is sized to this many blocks along its longest side. */
    private static final double FIT_BLOCKS = 1.6;
    /** Blocks a tick: a gentle walk. */
    private static final double SPEED = 0.075;
    private static final int SETTLE = 30;
    private static final int RUN = 130;

    private static volatile Mob subject;
    private static volatile int subjectId = -1;
    private static volatile boolean walking;
    private static double[] lastCam;
    private static boolean built;

    // per take, written on the server thread
    private static final Map<Integer, Set<String>> SEEN = new HashMap<>();
    private static volatile float peakSpeed;
    private static volatile double travelled;
    private static volatile double worstY;

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
                    if (ClientHarness.bootstrapWorld(mc, "vanilla-legs", ticks, true)) {
                        go(Phase.STAGE);
                    }
                }
                case STAGE -> {
                    if (ClientHarness.inWorld(mc) && ticks > ClientHarness.INGAME_SETTLE) {
                        // On the first tick the world is READY, not on a fixed tick: the first
                        // film skipped the build (the world came up a tick late) and every subject
                        // walked on the superflat ground 160 blocks under the camera.
                        if (!built) {
                            built = true;
                            ticks = ClientHarness.INGAME_SETTLE + 1;
                            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
                            ClientHarness.quietParticles(mc);
                            onServer(mc, VanillaLegsClientTest::buildStage);
                        }
                        follow(mc, null);
                        if (ticks > ClientHarness.INGAME_SETTLE + 60) {
                            nextTake();
                        }
                    }
                }
                case SETUP -> {
                    follow(mc, null);
                    if (ticks == 1) {
                        String t = current();
                        onServer(mc, s -> setUp(s, t));
                    }
                    if (ticks > 20 && subject != null) {
                        walking = true;
                        go(Phase.SETTLE);
                    }
                    if (ticks > 200) {
                        finish(false, "take " + current() + ": no subject after 200 ticks; " + BAD);
                    }
                }
                case SETTLE -> {
                    follow(mc, current());
                    if (ticks > SETTLE) {
                        filmStart = System.currentTimeMillis();
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

    private static String subjectOf(String t) {
        int dash = t.indexOf('-');
        return dash < 0 ? t : t.substring(0, dash);
    }

    private static void nextTake() {
        take++;
        if (take >= TAKES.size()) {
            finish(BAD.isEmpty(), String.join("; ", BAD));
            return;
        }
        go(Phase.SETUP);
    }

    private static void run(Minecraft mc) {
        String t = current();
        follow(mc, t);
        if (ticks == 30 || ticks == 60 || ticks == 90) {
            ClientHarness.snap(mc, SIG, t + "-" + ticks + ".png", true);
        }
        if (ticks < RUN) {
            return;
        }
        writeFilm(t);
        walking = false;
        Mob m = subject;
        onServer(mc, s -> {
            score(t, m);
            clearTake(s);
        });
        go(Phase.STAGE);
        ticks = ClientHarness.INGAME_SETTLE + 45;
    }

    // ===================== the camera: riding beside the subject =====================

    /** {dx, eyeHeight, dz, yaw, pitch} from the subject, which walks +X facing +X. */
    private static double[] offset(String t) {
        // The eye is never under 1.75 blocks: with the player's FEET inside the floor block the
        // client pushed it out now and then, and those frames filmed the floor edge-on.
        double d = 2.6;
        if (t != null && t.endsWith("front")) {
            // Straight ahead of it, looking back: a front view, where a slab and a leg differ most.
            return new double[]{d * 1.15, 1.75, 0, 90, 26};
        }
        if (t != null && t.endsWith("3q")) {
            // In front and to its left (+X is ahead, -Z is its left), looking back at it.
            return new double[]{d * 0.75, 1.8, -d * 0.75, 45, 28};
        }
        return new double[]{0, 1.75, -d, 0, 27};
    }

    private static void follow(Minecraft mc, String t) {
        if (mc.player == null) {
            return;
        }
        if (t != null) {
            // The server's FilmCamera has the view; the player stays where he was put.
            com.moddingfromamod.gatec.ClientHarness.setHudHidden(mc, true);
            mc.gui.setScreen(null);
            return;
        }
        double sx = START_X;
        double sz = LANE_Z;
        // The SERVER's carrier, read across threads (one JVM): the client's copy of an invisible
        // carrier trailed the drawn rig by ~2 blocks in the first film, so the camera fell behind
        // and the subject walked off to one side of the frame.
        Mob live = subject;
        if (t != null && live != null) {
            sx = live.getX();
            sz = live.getZ();
        }
        double[] o = offset(t);
        double cx = sx + o[0];
        double cy = FOOT_Y + o[1] - 1.62;
        double cz = sz + o[2];
        com.moddingfromamod.gatec.ClientHarness.setHudHidden(mc, true);
        mc.gui.setScreen(null);
        mc.player.getAbilities().flying = true;
        mc.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        double[] prev = lastCam == null ? new double[]{cx, cy, cz} : lastCam;
        mc.player.setPos(cx, cy, cz);
        mc.player.xOld = prev[0];
        mc.player.yOld = prev[1];
        mc.player.zOld = prev[2];
        mc.player.xo = prev[0];
        mc.player.yo = prev[1];
        mc.player.zo = prev[2];
        mc.player.setYRot((float) o[3]);
        mc.player.setXRot((float) o[4]);
        mc.player.yRotO = (float) o[3];
        mc.player.xRotO = (float) o[4];
        lastCam = new double[]{cx, cy, cz};
    }

    // ===================== the server side =====================

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onServerTick(ServerTickEvent.Pre event) {
        Mob m = subject;
        if (!ENABLED || m == null || m.isRemoved()) {
            return;
        }
        // Vanilla's own travel() moves it: its speed is zeroed, so its AI cannot, and the walk
        // animation is measured off the real displacement.
        double vx = walking ? SPEED : 0;
        m.setDeltaMovement(vx, m.getDeltaMovement().y, 0);
        m.setYRot(-90f);
        m.setYBodyRot(-90f);
        m.setYHeadRot(-90f);
        m.setXRot(0f);
        // The camera is film/FilmCamera, a display the server moves (the rule: never teleport
        // the onlooker in a film). Moving the client player instead jumped the view every few
        // ticks in the third film: the server put him back where it thought he was.
        Phase ph = phase;
        if (ph == Phase.SETTLE || ph == Phase.RUN) {
            var players = event.getServer().getPlayerList().getPlayers();
            if (!players.isEmpty()) {
                double[] o = offset(current());
                var target = new net.minecraft.world.phys.Vec3(m.getX(), FOOT_Y + 0.45, m.getZ());
                var at = new net.minecraft.world.phys.Vec3(m.getX() + o[0], FOOT_Y + o[1], m.getZ() + o[2]);
                com.moddingfromamod.film.FilmCamera.aim(players.get(0), at, target, 1.0);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerTickRead(ServerTickEvent.Post event) {
        Mob m = subject;
        if (!ENABLED || m == null || phase != Phase.RUN) {
            return;
        }
        peakSpeed = Math.max(peakSpeed, m.walkAnimation.speed());
        travelled = m.getX() - START_X;
        // On the stage, in front of the camera: the first film's subjects fell 160 blocks to the
        // world's floor and every count above still passed.
        worstY = Math.max(worstY, Math.abs(m.getY() - FOOT_Y));
        DisplayRig rig = VanillaRigAnimator.rigOf(m);
        if (rig == null) {
            return;
        }
        for (int i = 0; i < rig.displays().size(); i++) {
            var tr = com.moddingfromamod.compat.EntityNbt.save(rig.displays().get(i)).get("transformation");
            SEEN.computeIfAbsent(i, k -> new java.util.HashSet<>()).add(String.valueOf(tr));
        }
    }

    private static void score(String t, Mob m) {
        DisplayRig rig = m == null ? null : VanillaRigAnimator.rigOf(m);
        RigLocomotion.Plan plan = m == null ? null : VanillaRigAnimator.planOf(m);
        if (rig == null) {
            BAD.add(t + ": the subject was not being driven");
            return;
        }
        List<RigPlan.Group> groups = rig.groups();
        String line;
        if (plan == null) {
            line = t + ": no locomotion plan (locomotion=off?)";
        } else {
            List<RigAnatomy.Leg> legs = plan.anatomy().legs();
            int stepping = 0;
            StringBuilder still = new StringBuilder();
            for (RigAnatomy.Leg l : legs) {
                Set<String> chain = RigLocomotion.subtree(plan.anatomy().parts(), l.bone());
                boolean moved = false;
                for (int i = 0; i < groups.size(); i++) {
                    if (chain.contains(groups.get(i).boneName()) && SEEN.getOrDefault(i, Set.of()).size() > 2) {
                        moved = true;
                    }
                }
                if (moved) {
                    stepping++;
                } else {
                    still.append(' ').append(l.bone());
                }
            }
            int body = 0;
            for (int i = 0; i < groups.size(); i++) {
                if (groups.get(i).boneName().equals(plan.anatomy().body())) {
                    body = Math.max(body, SEEN.getOrDefault(i, Set.of()).size());
                }
            }
            line = String.format("%s: legs found %d, stepping %d%s; body poses %d; limb speed peak %.2f;"
                    + " travelled %.1f blocks", t, legs.size(), stepping,
                    still.length() == 0 ? "" : " (still:" + still + ")", body, peakSpeed, travelled);
            if (!OLD) {
                if (stepping != legs.size()) {
                    BAD.add(line);
                }
                if (body != 1) {
                    BAD.add(t + ": the body (the control) moved -- " + body + " poses");
                }
            }
            int expect = switch (subjectOf(t)) {
                case "spider", "tarantula" -> 8;
                case "ant" -> 6;
                case "horse" -> 4;
                case "person" -> 2;
                default -> legs.size();
            };
            if (legs.size() != expect) {
                BAD.add(t + ": found " + legs.size() + " legs, expected " + expect);
            }
        }
        if (peakSpeed < 0.1f || travelled < 3) {
            BAD.add(t + String.format(": it did not walk (limb speed %.2f, %.1f blocks)", peakSpeed, travelled));
        }
        if (worstY > 0.6) {
            BAD.add(t + String.format(": it was not on the stage (%.1f blocks off the floor) -- the "
                    + "camera filmed an empty platform", worstY));
        }
        NOTES.add(line);
        worstY = 0;
        SEEN.clear();
        peakSpeed = 0;
        travelled = 0;
    }

    private static void onServer(Minecraft mc, java.util.function.Consumer<ServerLevel> job) {
        var server = mc.getSingleplayerServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            try {
                job.accept(server.overworld());
            } catch (Exception e) {
                BAD.add("server job threw " + e);
            }
        });
    }

    private static void buildStage(ServerLevel level) {
        var server = level.getServer();
        var gr = server.overworld().getGameRules();
        gr.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME,false, server);
        gr.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_WEATHER,false, server);
        gr.set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS,false, server);
        com.moddingfromamod.mv.DayTime.set(level, 6000L);
        com.moddingfromamod.mv.DayTime.weather(level, 6000, 0, false, false);
        // A checkerboard: a planted foot stays on its square while the body passes over it.
        for (int x = -6; x <= 22; x++) {
            for (int z = -8; z <= 8; z++) {
                var block = ((x + z) & 1) == 0 ? Blocks.CONCRETE.white() : Blocks.CONCRETE.lightGray();
                level.setBlock(new BlockPos(x, FLOOR_Y, z), block.defaultBlockState(), 3);
                for (int y = FLOOR_Y + 1; y < FLOOR_Y + 6; y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    private static void clearTake(ServerLevel level) {
        Mob m = subject;
        subject = null;
        subjectId = -1;
        if (m != null) {
            DisplayRig.reap(level, m.getUUID());
            m.discard();
        }
    }

    private static void setUp(ServerLevel level, String t) {
        String who = subjectOf(t);
        String id = species(level, who);
        WishMobEntity mob = ModEntities.WISH_MOB.get().create(level, EntitySpawnReason.COMMAND);
        mob.snapTo(START_X, FOOT_Y, LANE_Z, -90f, 0f);
        mob.setSpeciesId(id);
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()),
                EntitySpawnReason.COMMAND, null);
        LivingEntity carrier = VanillaRigBridge.manifestNow(level, mob);
        if (!(carrier instanceof Mob m)) {
            BAD.add(t + ": the bridge did not make a rig for " + id);
            return;
        }
        m.setPermanentlyInvulnerable(true);
        m.setSilent(true);
        m.setPersistenceRequired();
        // A staged walker: it does not look round at the onlooker. Its look-at-player goal turned
        // the tarantula 45° off its travel half way through the front take, and the body followed
        // its head -- a creature walking sideways, which is not what this film is about.
        m.goalSelector.removeAllGoals(g -> true);
        var speed = m.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.setBaseValue(0.0);
        }
        m.snapTo(START_X, FOOT_Y, LANE_Z, -90f, 0f);
        subjectId = m.getId();
        subject = m;
        NOTES.add(t + ": " + id + " rides a "
                + net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()));
    }

    // ===================== the subjects =====================

    static final String SPIDER = """
            {"parts":[
             {"name":"body","from":[-4,5,-2],"to":[4,11,8],"colour":"#3a2a1e"},
             {"name":"head","parent":"body","from":[-3,5,-7],"to":[3,10,-1],"colour":"#5a4030"},
             {"name":"eyeL","parent":"head","from":[1,8,-7.5],"to":[2,9,-6.5],"colour":"#ff2020"},
             {"name":"eyeR","parent":"head","from":[-2,8,-7.5],"to":[-1,9,-6.5],"colour":"#ff2020"}],
             "kits":[{"kit":"leg_row","on":"body","count":4,"colour":"#2a1e14"}]}""";

    /**
     * A real typed wish's shape ("a giant fuzzy purple tarantula"): the agent's own kit call,
     * {@code leg_row} count 4 size L, on a big body -- the one that came out as SLABS before the leg
     * row was made of rods.
     */
    static final String TARANTULA = """
            {"parts":[
             {"name":"body","from":[-6,6,-2],"to":[6,15,14],"colour":"#6a2a9a","pattern":"fur","patternColour":"#4a1a6a"},
             {"name":"head","parent":"body","from":[-4,6,-9],"to":[4,13,-1],"colour":"#7a3aaa"},
             {"name":"eyeL","parent":"head","from":[1,10,-9.5],"to":[3,12,-8.5],"colour":"#101010"},
             {"name":"eyeR","parent":"head","from":[-3,10,-9.5],"to":[-1,12,-8.5],"colour":"#101010"}],
             "kits":[{"kit":"leg_row","on":"body","count":4,"size":"L","colour":"#5a2088"}]}""";

    static final String ANT = """
            {"parts":[
             {"name":"body","from":[-2,4,-3],"to":[2,7,3],"colour":"#a03a18"},
             {"name":"abdomen","parent":"body","from":[-3,4,3],"to":[3,9,11],"colour":"#802a10"},
             {"name":"head","parent":"body","from":[-2.5,4,-8],"to":[2.5,8,-3],"colour":"#a03a18"},
             {"name":"antennaL","parent":"head","from":[1,8,-8],"to":[2,12,-7],"colour":"#3a1004"},
             {"name":"antennaR","parent":"head","from":[-2,8,-8],"to":[-1,12,-7],"colour":"#3a1004"}],
             "kits":[{"kit":"leg_row","on":"body","count":3,"colour":"#3a1004"}]}""";

    private static String sculptJson(String who) {
        String path = System.getProperty(P + "sculpt." + who, "");
        try {
            if (!path.isBlank()) {
                return Files.readString(Path.of(path), StandardCharsets.UTF_8);
            }
            String res = switch (who) {
                case "spider", "tarantula" -> null;
                case "ant" -> null;
                case "horse" -> "/builder-bodies/species/horse/sculpt.json";
                case "person" -> "/builder-bodies/species/person/sculpt.json";
                default -> "/starter-library/species/" + who + "/sculpt.json";
            };
            if (res == null) {
                return "spider".equals(who) ? SPIDER : "tarantula".equals(who) ? TARANTULA : ANT;
            }
            try (var in = com.moddingfromamod.plugin.ModResources.open(res)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            throw new IllegalStateException("no sculpt for " + who, e);
        }
    }

    private static final Map<String, String> MADE = new LinkedHashMap<>();

    private static String species(ServerLevel level, String who) {
        String id = "vlegs_" + who;
        if (MADE.containsKey(id)) {
            return id;
        }
        JsonObject spec = JsonParser.parseString(sculptJson(who)).getAsJsonObject();
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
        // Its longest side, standing up included: a person sized by his width came out 2.9 blocks
        // tall and the film was of his knees.
        double across = Math.max(Math.max(CreatureAnatomy.widthBlocks(ext), CreatureAnatomy.heightBlocks(ext)), 0.2);
        double scale = Math.min(2.0, FIT_BLOCKS / across);
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", "Legs " + who);
        o.addProperty("modelKey", id);
        o.addProperty("health", 40);
        o.addProperty("speed", 0.25);
        o.addProperty("scale", scale);
        o.addProperty("base", "wild");
        JsonArray pw = new JsonArray();
        pw.add("friendly");
        o.add("powers", pw);
        SpeciesRegistry.put(level.getServer(), Species.fromJson(o));
        MADE.put(id, id);
        NOTES.add(who + ": " + parts.size() + " parts, scale " + String.format("%.2f", scale)
                + (OLD ? " (locomotion OFF: the rig as it was)" : ""));
        return id;
    }

    // ===================== bookkeeping =====================

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
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server != null) {
            server.execute(() -> server.getPlayerList().getPlayers()
                    .forEach(pl -> com.moddingfromamod.film.FilmCamera.release(pl)));
        }
        ClientHarness.verdict(SIG, pass,
                (pass ? "PASS" : "FAIL — " + message) + "\n" + String.join("\n", NOTES));
        phase = Phase.DONE;
        Minecraft.getInstance().stop();
    }
}
