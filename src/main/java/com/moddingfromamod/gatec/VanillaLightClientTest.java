package com.moddingfromamod.gatec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * <b>Gate C — is a sculpted creature lit like a vanilla mob standing in the same place?</b>
 *
 * <p>A sculpted creature (the atom rig, {@link CopyOfMeFixture}: a ~2-block humanoid) and a
 * VILLAGER (a ~2-block humanoid, safe on Peaceful) stand side by side, and the child's own eyes
 * photograph them. <b>No night vision, no brightness boost</b> — not {@code PhotoClient}, which
 * raises brightness for a take (the camera notes), because the question is what the CHILD sees. The
 * client's player is checked every tick for night vision and the run FAILS if he ever has it.
 *
 * <p>Five settings, each its own stage far enough apart that no light reaches the next:
 * <ol>
 *   <li>{@code noon} — outdoors, spawned at noon;</li>
 *   <li>{@code night} — the same two, the clock moved to midnight;</li>
 *   <li>{@code room} — a closed room that was DARK when they appeared and was lit afterwards (four
 *       glowstones in the ceiling): the order an earlier change's castle had, lanterns after the boss;</li>
 *   <li>{@code throne} — a throne room lit BEFORE they were seated, each on a gold throne with a
 *       back (an earlier change's spot): does sitting against blocks darken a rig by itself?;</li>
 *   <li>{@code walkin} — they appeared outdoors at noon and then went into a dim room (one torch):
 *       the opposite direction.</li>
 * </ol>
 *
 * <h2>The control is the villager, never "the rig at noon"</h2>
 *
 * <p>Two textures do not have one brightness, so each subject is read as a RATIO of its own noon:
 * {@code ratio = luma(setting) / luma(noon)}. A rig lit like a mob keeps its ratio beside the
 * villager's; a rig whose light is wrong drifts away from it in either direction. Each subject's
 * pixels are the difference against a PLATE (the same held pose seconds later, both subjects
 * hidden by a render-only toggle), rig on the left half of the frame and villager on the right.
 * The night frame reuses noon's mask: the subjects did not move, and a black subject against a
 * black plate would otherwise mask itself out — the very thing being measured.
 *
 * <p>Beside the pixels it records the structural fact (the camera notes): the light at each
 * carrier's eye, the override every display carries, and the villager's own eye light.
 *
 * <p>{@code -Ptestprops="riglight=off"} is the A/B seam: the rig keeps the light it was spawned in,
 * which is what shipped before an earlier change.
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class VanillaLightClientTest {

    private VanillaLightClientTest() {
    }

    private static final String P = ModdingFromAModMod.MODID + ".";

    private static final boolean ENABLED =
            "vanillalight".equalsIgnoreCase(System.getProperty(P + "testmode", ""));

    private static final Path SIG = Path.of(
            System.getProperty(P + "sigdir", "/tmp/moddingfromamod-vanillalight"));

    /**
     * How far the rig's ratio may sit from the villager's, as a FACTOR either way, before its light
     * is called wrong. The two ratios are divided rather than subtracted: a subtracted gap shrinks
     * with the light (the first clean run's black rig in the lit room was only -0.18 off, inside a
     * 0.20 band, at 0.06 against 0.23). Set between the arms, two runs each: {@code riglight=off}
     * room 0.23 and walk-in 4.14–4.22 (FAIL); with the fix every setting 0.83–1.24 (the 1.2 is a
     * llama's eye one light level above a villager's).
     */
    static final double TOLERANCE = Double.parseDouble(
            System.getProperty(P + "lighttol", "1.5"));

    private enum Phase {
        WAIT_TITLE, WAIT_INGAME, BUILD, PREPARE, RELOAD, SPAWN, LIGHT, SHOOT, NIGHT, DONE
    }

    private static Phase phase = Phase.WAIT_TITLE;
    private static int ticks;
    private static boolean fired;
    private static boolean reloadDone;
    private static boolean built;
    private static String key;
    private static List<CreatureSculpt.Part> parts;
    private static boolean nightVisionSeen;

    private static final List<String> NOTES = new ArrayList<>();
    private static final List<String> BAD = new ArrayList<>();

    private static final double FOOT_Y = 100.0;
    private static final int FLOOR_Y = (int) FOOT_Y - 1;
    /**
     * Every room sits inside ONE chunk (x 16k..16k+15, z 32..47): the client's light across a chunk
     * border lags, and the third run's walk-in plate was lit on one side of x=144 and not the other.
     */
    private static final double STAGE_Z = 42.5;
    private static final double STANDOFF = 5.0;
    /**
     * The camera's FEET, above the floor. A fixed feet height, not an eye height minus
     * {@code getEyeHeight()}: an eye 1.3 above the floor put a standing player's feet inside the
     * floor block, the client changed his pose, his eye height changed, and the camera bobbed
     * ~1.1 blocks between a frame and its plate (101.45 ↔ 102.52), so every mask was a picture of
     * the room moving.
     */
    private static final double FEET_UP = 0.2;
    /** The rig stands this far to +X (the LEFT of the frame, facing +Z); the villager to -X. */
    private static final double SPREAD = 1.4;
    /** The walk-in pair's holding spot, outdoors, before they go in. */
    private static final double HOLD_Z = STAGE_Z - 40;

    /** One stage: where it is, and whether its subjects stand one block up on a throne. */
    private record Stage(String name, double x, boolean throne) {
        double footY() {
            return FOOT_Y + (throne ? 1 : 0);
        }
    }

    private static final Stage NOON = new Stage("noon", 8.5, false);
    private static final Stage ROOM = new Stage("room", 72.5, false);
    private static final Stage THRONE = new Stage("throne", 136.5, true);
    private static final Stage WALKIN = new Stage("walkin", 200.5, false);
    private static final List<Stage> STAGES = List.of(NOON, ROOM, THRONE, WALKIN);

    /** The order the takes are shot in; night is the noon stage after the clock moves. */
    private static final List<String> TAKES = List.of("noon", "room", "throne", "walkin", "night");

    private static final Map<String, DisplayRig> RIGS = new LinkedHashMap<>();
    private static final Map<String, Mob> MOBS = new LinkedHashMap<>();
    private static final Map<String, int[][]> PLATES = new LinkedHashMap<>();
    private static final Map<String, int[][]> FRAMES = new LinkedHashMap<>();
    private static final Map<String, String> SHAS = new LinkedHashMap<>();
    private static final Map<String, String> LIGHTS = new LinkedHashMap<>();

    private static Stage held;
    private static int shotIndex;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!ENABLED || phase == Phase.DONE) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ticks++;
        if (mc.player != null && mc.player.hasEffect(MobEffects.NIGHT_VISION)) {
            nightVisionSeen = true;
        }
        try {
            switch (phase) {
                case WAIT_TITLE -> {
                    if (ticks > ClientHarness.TITLE_SETTLE) {
                        go(Phase.WAIT_INGAME);
                    }
                }
                case WAIT_INGAME -> {
                    if (ClientHarness.bootstrapWorld(mc, "vanilla-light", ticks, true)) {
                        go(Phase.BUILD);
                    }
                }
                case BUILD -> {
                    if (ClientHarness.inWorld(mc) && ticks > ClientHarness.INGAME_SETTLE) {
                        hold(mc, NOON);
                        if (!built) {
                            built = true;
                            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
                            ClientHarness.quietParticles(mc);
                            server(mc, VanillaLightClientTest::buildAll);
                        }
                        if (ticks > ClientHarness.INGAME_SETTLE + 40) {
                            go(Phase.PREPARE);
                        }
                    }
                }
                case PREPARE -> prepare(mc);
                case RELOAD -> {
                    hold(mc, NOON);
                    if (reloadDone && mc.getOverlay() == null && ticks > 20) {
                        go(Phase.SPAWN);
                    }
                }
                case SPAWN -> {
                    hold(mc, NOON);
                    if (!fired && ticks > 40) {
                        fired = true;
                        server(mc, VanillaLightClientTest::spawnAll);
                    }
                    if (ticks > 80) {
                        go(Phase.LIGHT);
                    }
                }
                case LIGHT -> {
                    hold(mc, NOON);
                    if (!fired) {
                        fired = true;
                        // AFTER they appeared: the room is lit, the walk-in pair goes indoors.
                        server(mc, level -> {
                            roomLights(level, ROOM, Blocks.GLOWSTONE.defaultBlockState());
                            walkIn();
                        });
                    }
                    if (ticks > 60) {
                        go(Phase.SHOOT);
                    }
                }
                case SHOOT -> shoot(mc);
                case NIGHT -> night(mc);
                default -> { }
            }
        } catch (Exception e) {
            finish(false, "threw " + e);
        }
    }

    // ------------------------------------------------------------------------------- the stages

    private static void buildAll(ServerLevel level) {
        com.moddingfromamod.mv.DayTime.set(level, 6000L);
        com.moddingfromamod.mv.DayTime.weather(level, 6000, 0, false, false);
        var server = level.getServer();
        server.overworld().getGameRules().set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME,false, server);
        server.overworld().getGameRules().set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_WEATHER,false, server);
        server.overworld().getGameRules().set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS,false, server);
        BlockState floor = Blocks.WHITE_CONCRETE.defaultBlockState();
        for (Stage s : STAGES) {
            int cx = (int) Math.floor(s.x());
            for (int x = cx - 10; x <= cx + 10; x++) {
                for (int z = (int) STAGE_Z - 12; z <= (int) STAGE_Z + 8; z++) {
                    level.setBlock(new BlockPos(x, FLOOR_Y, z), floor, 3);
                }
            }
            if (s != NOON) {
                room(level, s);
            }
        }
        // The walk-in pair's holding platform, outdoors in full sun.
        int hx = (int) Math.floor(WALKIN.x());
        for (int x = hx - 4; x <= hx + 4; x++) {
            for (int z = (int) HOLD_Z - 4; z <= (int) HOLD_Z + 4; z++) {
                level.setBlock(new BlockPos(x, FLOOR_Y, z), floor, 3);
            }
        }
        // The room is DARK when they appear (its lights go in at LIGHT); the throne room is lit
        // before they are seated, at PREPARE — not here: lights set in the same tick as the room
        // around them never reached the client in two runs (server 9/0, client 0/0, forever).
        // The walk-in room is dim: one torch, well away from the pair.
        level.setBlock(new BlockPos((int) Math.floor(WALKIN.x()), (int) FOOT_Y,
                (int) STAGE_Z - 9), Blocks.TORCH.defaultBlockState(), 3);
        if (THRONE.throne()) {
            for (double dx : new double[]{SPREAD, -SPREAD}) {
                int tx = (int) Math.floor(THRONE.x() + dx);
                int tz = (int) Math.floor(STAGE_Z);
                level.setBlock(new BlockPos(tx, (int) FOOT_Y, tz), Blocks.GOLD_BLOCK.defaultBlockState(), 3);
                for (int h = 0; h <= 2; h++) {
                    level.setBlock(new BlockPos(tx, (int) FOOT_Y + h, tz + 1),
                            Blocks.GOLD_BLOCK.defaultBlockState(), 3);
                }
            }
        }
    }

    /** A closed room of stone bricks with white walls behind the subjects. */
    private static void room(ServerLevel level, Stage s) {
        int cx = (int) Math.floor(s.x());
        int z0 = (int) STAGE_Z - 9;
        int z1 = (int) STAGE_Z + 4;   // walls at 32 and 47: chunk z 2, edge to edge
        int y0 = FLOOR_Y;
        int y1 = (int) FOOT_Y + 6;
        BlockState wall = Blocks.STONE_BRICKS.defaultBlockState();
        BlockState back = Blocks.WHITE_CONCRETE.defaultBlockState();
        for (int x = cx - 7; x <= cx + 7; x++) {
            for (int z = z0 - 1; z <= z1 + 1; z++) {
                for (int y = y0; y <= y1; y++) {
                    boolean shell = x == cx - 7 || x == cx + 7 || z == z0 - 1 || z == z1 + 1
                            || y == y0 || y == y1;
                    BlockState st = !shell ? Blocks.AIR.defaultBlockState()
                            : z == z1 + 1 ? back : y == y0 ? Blocks.WHITE_CONCRETE.defaultBlockState() : wall;
                    level.setBlock(new BlockPos(x, y, z), st, 3);
                }
            }
        }
    }

    /** Four glowstones in the ceiling over the pair (or stone, to take them out). */
    private static void roomLights(ServerLevel level, Stage s, BlockState st) {
        int cx = (int) Math.floor(s.x());
        int y = (int) FOOT_Y + 6;
        for (int dx : new int[]{-3, 3}) {
            for (int dz : new int[]{-3, 1}) {
                level.setBlock(new BlockPos(cx + dx, y, (int) STAGE_Z + dz), st, 3);
            }
        }
    }

    // ------------------------------------------------------------------------------- the creature

    private static void prepare(Minecraft mc) {
        hold(mc, NOON);
        if (fired) {
            return;
        }
        fired = true;
        key = "vlight_copy_of_me";
        parts = SculptCreatureTool.withKits(CreatureSculpt.parseParts(
                JsonParser.parseString(CopyOfMeFixture.JSON).getAsJsonArray()), new JsonObject());
        try {
            CreatureFiles.write(key, parts, "{}");
        } catch (Exception e) {
            finish(false, "creature write failed: " + e);
            return;
        }
        server(mc, level -> roomLights(level, THRONE, Blocks.GLOWSTONE.defaultBlockState()));
        NOTES.add(ClientHarness.excludePack(mc, "file/mfam-vanilla-rig"));
        NOTES.add("child's options: gamma " + mc.options.gamma().get() + " (left as the child has it)");
        mc.reloadResourcePacks().thenRun(() -> reloadDone = true);
        go(Phase.RELOAD);
    }

    // ------------------------------------------------------------------------------- subjects

    private static void spawnAll(ServerLevel level) {
        var extents = CreatureAnatomy.extents(RigGeometry.of(parts));
        var clips = ModelRepository.animations(key);
        for (Stage s : STAGES) {
            double z = STAGE_Z;
            double y = s.footY();
            double rx = s.x() + SPREAD;
            double vx = s.x() - SPREAD;
            if (s == WALKIN) {
                z = HOLD_Z;
                y = FOOT_Y;
            }
            DisplayRig rig = DisplayRig.spawn(level, new Vec3(centre(rx), y, z), 180f, parts, 1.0,
                    CreatureAnatomy.widthBlocks(extents), CreatureAnatomy.heightBlocks(extents), null);
            freeze(rig.base(), 180f);
            VanillaRigAnimator.drive(rig, com.moddingfromamod.vanilla.RigFit.intoRigSpace(parts), clips);
            RIGS.put(s.name(), rig);
            Mob v = net.minecraft.world.entity.EntityType.VILLAGER.create(level, EntitySpawnReason.COMMAND);
            if (v == null) {
                continue;
            }
            v.snapTo(centre(vx), y, z, 180f, 0f);
            freeze(v, 180f);
            level.addFreshEntity(v);
            MOBS.put(s.name(), v);
        }
        NOTES.add("spawned " + RIGS.size() + " rigs (" + RIGS.get("noon").displays().size()
                + " displays each) and " + MOBS.size() + " villagers; carrier "
                + RIGS.get("noon").base().getType().toShortString());
    }

    /** Snap to a block centre in X so a throne seat holds the subject square on the gold. */
    private static double centre(double x) {
        return Math.floor(x) + 0.5;
    }

    private static void freeze(Mob m, float yaw) {
        m.setNoAi(true);
        m.setInvulnerable(true);
        m.setSilent(true);
        m.setYRot(yaw);
        m.setYHeadRot(yaw);
        m.setYBodyRot(yaw);
    }

    private static void walkIn() {
        Mob base = RIGS.get("walkin").base();
        base.teleportTo(centre(WALKIN.x() + SPREAD), FOOT_Y, STAGE_Z);
        Mob v = MOBS.get("walkin");
        v.teleportTo(centre(WALKIN.x() - SPREAD), FOOT_Y, STAGE_Z);
        freeze(base, 180f);
        freeze(v, 180f);
    }

    // ------------------------------------------------------------------------------- the takes

    private static void shoot(Minecraft mc) {
        String take = TAKES.get(shotIndex);
        if ("night".equals(take)) {
            go(Phase.NIGHT);
            return;
        }
        Stage s = stage(take);
        hold(mc, s);
        if (take(mc, take, s, 0)) {
            shotIndex++;
            ticks = 0;
        }
    }

    /**
     * The frame, then its PLATE from the same held pose seconds later with both subjects hidden.
     * The plate was once its own phase minutes earlier, and two runs' masks swallowed half a room
     * (80,632 px for a 2-block villager) because the room's smooth light was not what it had been.
     * Hiding is a RENDER toggle only — {@code view_range} 0 on the displays, the villager made
     * invisible — so the light the frame was shot in is not touched. True when done.
     */
    private static boolean take(Minecraft mc, String take, Stage s, int t0) {
        int t = ticks - t0;
        if (t == 90) {
            lights(mc, take, s);
        } else if (t >= 100 && frameAt < 0) {
            // ⚠ WAIT FOR THE CLIENT'S LIGHT, not for a tick count. One run's throne room was lit on
            // the server (9/0 at the carrier's eye) and DARK on the client, glowstone and all: the
            // villager, lit by the client, came out at luma 4.7, and the rig, lit by the server's
            // number, at 47 — "ten times brighter", and a fault of the harness, not of the rig.
            String why = clientLightDisagrees(mc, take);
            if (why == null) {
                frameAt = t;
                cam.put(take, camera(mc));
                FRAMES.put(take, grab(mc, take));
                hide(mc, take, true);
                if (t > 100) {
                    NOTES.add(take + ": the client's light caught up with the server's after "
                            + (t - 100) + " more ticks");
                }
            } else if (t == 200 || t == 400) {
                // the client reads 0/0 — no light data for the eye's section at all — and a
                // client never recomputes light it was never sent, however often the blocks change
                // (2026-11-02: 1 run in 4 on main stayed 0/0 to the end). Send the chunks round both eyes again
                // WITH their light, the packet a client gets when a chunk loads. The rig is lit from
                // the server's number either way; this is the vanilla villager's light catching up.
                resendWithLight(mc, take);
                NOTES.add(take + ": client light behind at +" + (t - 100) + " ticks (" + why
                        + "), chunks re-sent with their light");
            }
            if (why != null && t == 400) {
                // Once: set the stage's lights again, which re-sends their light to the client.
                NOTES.add(take + ": client light still behind at +300 ticks (" + why + "), lights re-set");
                Stage st = "night".equals(take) ? NOON : s;
                if (st == ROOM || st == THRONE) {
                    server(mc, level -> {
                        roomLights(level, st, Blocks.STONE_BRICKS.defaultBlockState());
                        roomLights(level, st, Blocks.GLOWSTONE.defaultBlockState());
                    });
                }
            } else if (why != null && t > 900) {
                finish(false, take + ": the client's light never matched the server's (" + why
                        + ") — a harness fault, not a finding");
            }
        } else if (frameAt >= 0 && t == frameAt + 25) {
            String c = camera(mc);
            if (!c.equals(cam.get(take))) {
                BAD.add(take + ": the camera moved between the frame (" + cam.get(take)
                        + ") and its plate (" + c + ") — the mask is not trustworthy");
            }
            PLATES.put(take, grab(mc, "plate-" + take));
            hide(mc, take, false);
        } else if (frameAt >= 0 && t >= frameAt + 40) {
            frameAt = -1;
            return true;
        }
        return false;
    }

    private static int frameAt = -1;
    private static final Map<String, String> cam = new LinkedHashMap<>();
    /** take -> {carrier eye x,y,z, block, sky, villager eye x,y,z, block, sky}, from the server. */
    private static final Map<String, int[]> SERVER_LIGHT = new java.util.concurrent.ConcurrentHashMap<>();

    private static String camera(Minecraft mc) {
        var p = mc.gameRenderer.mainCamera().position();
        return String.format(Locale.ROOT, "%.2f %.2f %.2f", p.x, p.y, p.z);
    }

    /** Null when the client sees the light the server has at both eyes; else what differs. */
    private static String clientLightDisagrees(Minecraft mc, String take) {
        int[] sv = SERVER_LIGHT.get(take);
        if (sv == null || mc.level == null) {
            return "no server reading yet";
        }
        for (int k = 0; k < 2; k++) {
            BlockPos at = new BlockPos(sv[k * 5], sv[k * 5 + 1], sv[k * 5 + 2]);
            int b = mc.level.getBrightness(LightLayer.BLOCK, at);
            int sk = mc.level.getBrightness(LightLayer.SKY, at);
            if (b != sv[k * 5 + 3] || sk != sv[k * 5 + 4]) {
                return (k == 0 ? "carrier" : "villager") + " eye client " + b + "/" + sk
                        + " server " + sv[k * 5 + 3] + "/" + sv[k * 5 + 4];
            }
        }
        return null;
    }

    private static void hide(Minecraft mc, String take, boolean hidden) {
        String stageName = "night".equals(take) ? "noon" : take;
        DisplayRig rig = RIGS.get(stageName);
        Mob v = MOBS.get(stageName);
        server(mc, level -> {
            for (Entity d : rig.displays()) {
                if (hidden) {
                    var t = com.moddingfromamod.compat.EntityNbt.save(d);
                    t.putFloat("view_range", 0f);
                    com.moddingfromamod.compat.EntityNbt.load(d, t);
                } else {
                    DisplayRig.matchViewRange(d, rig.base());
                }
            }
            v.setInvisible(hidden);
        });
    }

    private static void night(Minecraft mc) {
        hold(mc, NOON);
        if (!fired) {
            fired = true;
            server(mc, level -> com.moddingfromamod.mv.DayTime.set(level, 18000L));
        }
        if (take(mc, "night", NOON, 0)) {
            verdict();
        }
    }

    private static Stage stage(String take) {
        for (Stage s : STAGES) {
            if (s.name().equals(take)) {
                return s;
            }
        }
        return NOON;
    }

    /** The structural record: each eye's light and what the displays were told. */
    private static void lights(Minecraft mc, String take, Stage s) {
        String stageName = "night".equals(take) ? "noon" : s.name();
        DisplayRig rig = RIGS.get(stageName);
        Mob v = MOBS.get(stageName);
        server(mc, level -> {
            Mob base = rig.base();
            BlockPos re = BlockPos.containing(base.getEyePosition(1.0f));
            BlockPos ve = BlockPos.containing(v.getEyePosition(1.0f));
            java.util.Set<String> overrides = new java.util.TreeSet<>();
            for (Entity d : rig.displays()) {
                var t = com.moddingfromamod.compat.EntityNbt.save(d);
                overrides.add(t.contains("brightness")
                        ? t.getCompoundOrEmpty("brightness").getIntOr("block", 0) + "/" + t.getCompoundOrEmpty("brightness").getIntOr("sky", 0)
                        : "none");
            }
            String line = String.format(Locale.ROOT,
                    "%s: carrier eye block/sky %d/%d, displays %s; villager eye %d/%d",
                    take, level.getBrightness(LightLayer.BLOCK, re), level.getBrightness(LightLayer.SKY, re),
                    overrides, level.getBrightness(LightLayer.BLOCK, ve), level.getBrightness(LightLayer.SKY, ve));
            LIGHTS.put(take, line);
            SERVER_LIGHT.put(take, new int[]{
                    re.getX(), re.getY(), re.getZ(),
                    level.getBrightness(LightLayer.BLOCK, re), level.getBrightness(LightLayer.SKY, re),
                    ve.getX(), ve.getY(), ve.getZ(),
                    level.getBrightness(LightLayer.BLOCK, ve), level.getBrightness(LightLayer.SKY, ve)});
        });
    }

    // ------------------------------------------------------------------------------- the verdict

    private static void verdict() {
        if (nightVisionSeen) {
            BAD.add("the child's player had NIGHT VISION during the run — not his view");
        }
        Map<String, double[]> luma = new LinkedHashMap<>();
        for (String take : TAKES) {
            // Night is measured inside NOON's mask: same pose, and a black subject against a black
            // plate would mask itself out — the very thing being measured.
            String maskFrom = "night".equals(take) ? "noon" : take;
            int[][] plate = PLATES.get(maskFrom);
            int[][] maskFrame = FRAMES.get(maskFrom);
            int[][] frame = FRAMES.get(take);
            if (plate == null || maskFrame == null || frame == null) {
                finish(false, "a frame is missing for " + take + " — a harness fault, not a finding");
                return;
            }
            double[] l = measure(plate, maskFrame, frame);
            luma.put(take, l);
            NOTES.add(String.format(Locale.ROOT,
                    "%-6s rig %.1f (%d px)  villager %.1f (%d px)  sha %s", take,
                    l[0], (int) l[1], l[2], (int) l[3], SHAS.get(take)));
        }
        LIGHTS.values().forEach(NOTES::add);
        double[] noon = luma.get("noon");
        if (noon[1] < 200 || noon[3] < 200) {
            finish(false, "a subject covers almost nothing at noon (rig " + (int) noon[1]
                    + " px, villager " + (int) noon[3] + " px) — a camera fault, not a finding");
            return;
        }
        StringBuilder table = new StringBuilder(
                "ratio to its own noon — setting: rig / villager / rig÷villager");
        for (String take : TAKES) {
            if ("noon".equals(take)) {
                continue;
            }
            double[] l = luma.get(take);
            double rig = l[0] / noon[0];
            double mob = l[2] / noon[2];
            double rel = rig / mob;
            table.append(String.format(Locale.ROOT, "\n  %-6s %.2f / %.2f / %.2f", take, rig, mob, rel));
            if (rel > TOLERANCE || rel < 1.0 / TOLERANCE) {
                BAD.add(String.format(Locale.ROOT, "%s: the rig is %s than the villager (ratio %.2f vs %.2f)",
                        take, rel < 1 ? "DARKER" : "BRIGHTER", rig, mob));
            }
        }
        NOTES.add(table.toString());
        NOTES.add("riglight=" + System.getProperty(P + "riglight", "on") + ", tolerance " + TOLERANCE);
        writeTsv(luma);
        finish(BAD.isEmpty(), String.join("; ", BAD));
    }

    /**
     * Mean luma of the rig (left half) and the villager (right half) in {@code frame}, over the
     * pixels where {@code maskFrame} differs from {@code plate}. {rigLuma, rigPx, mobLuma, mobPx}.
     */
    static double[] measure(int[][] plate, int[][] maskFrame, int[][] frame) {
        int h = frame.length;
        int w = frame[0].length;
        double[] sum = new double[2];
        int[] n = new int[2];
        for (int y = h / 10; y < h * 9 / 10; y++) {
            for (int x = w / 10; x < w * 9 / 10; x++) {
                if (diff(plate[y][x], maskFrame[y][x]) < 12) {
                    continue;
                }
                int side = x < w / 2 ? 0 : 1;
                sum[side] += luma(frame[y][x]);
                n[side]++;
            }
        }
        return new double[]{n[0] == 0 ? -1 : sum[0] / n[0], n[0], n[1] == 0 ? -1 : sum[1] / n[1], n[1]};
    }

    private static int diff(int a, int b) {
        int dr = Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF));
        int dg = Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF));
        int db = Math.abs((a & 0xFF) - (b & 0xFF));
        return Math.max(dr, Math.max(dg, db));
    }

    private static double luma(int c) {
        return 0.299 * ((c >> 16) & 0xFF) + 0.587 * ((c >> 8) & 0xFF) + 0.114 * (c & 0xFF);
    }

    private static void writeTsv(Map<String, double[]> luma) {
        StringBuilder sb = new StringBuilder("take\trig_luma\trig_px\tvillager_luma\tvillager_px\tsha\n");
        luma.forEach((k, l) -> sb.append(String.format(Locale.ROOT, "%s\t%.2f\t%d\t%.2f\t%d\t%s\n",
                k, l[0], (int) l[1], l[2], (int) l[3], SHAS.get(k))));
        try {
            Files.createDirectories(SIG);
            Files.writeString(SIG.resolve("light.tsv"), sb.toString());
        } catch (Exception ignored) {
            // the verdict carries the same numbers
        }
    }

    // ------------------------------------------------------------------------------- camera

    /** The child's eyes, level with the pair's chests, a few blocks back, every tick. */
    private static void hold(Minecraft mc, Stage s) {
        if (mc.player == null) {
            return;
        }
        if (s != held) {
            // A server-side move as well: the server moves a creative player WITH collision, so a
            // client-only jump through a room's wall was refused and the frame came back one stage
            // behind (the first run's "noon" frame was the throne room). A teleport ignores
            // collision. NOT spectator: a spectator SEES invisible mobs, and the second run's
            // frames had a translucent white llama (the carrier) standing over every rig.
            held = s;
            double ex = s.x();
            double ey = s.footY() + FEET_UP;
            server(mc, level -> {
                ServerPlayer p = level.getServer().getPlayerList().getPlayers().get(0);
                p.connection.teleport(ex, ey, STAGE_Z - STANDOFF, 0f, 6f);
            });
        }
        mc.options.hideGui = true;
        mc.gui.setScreen(null);
        double feet = s.footY() + FEET_UP;
        double z = STAGE_Z - STANDOFF;
        float pitch = 6f;
        mc.player.setPos(s.x(), feet, z);
        mc.player.setYRot(0f);
        mc.player.setXRot(pitch);
        mc.player.xOld = s.x();
        mc.player.yOld = feet;
        mc.player.zOld = z;
        mc.player.yRotO = 0f;
        mc.player.xRotO = pitch;
        mc.player.getAbilities().flying = true;
    }

    private static int[][] grab(Minecraft mc, String name) {
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
            try {
                Files.createDirectories(SIG);
                Path out = SIG.resolve(name + ".png");
                img.writeToFile(out);
                byte[] d = MessageDigest.getInstance("MD5").digest(Files.readAllBytes(out));
                StringBuilder hex = new StringBuilder();
                for (int i = 0; i < 6; i++) {
                    hex.append(String.format("%02x", d[i]));
                }
                SHAS.put(name, hex.toString());
            } catch (Exception ignored) {
                // the pixels carry the verdict
            }
            return px;
        }
    }

    /** Send the chunks round both eyes of a take to the client again, with their light. */
    private static void resendWithLight(Minecraft mc, String take) {
        int[] sv = SERVER_LIGHT.get(take);
        var server = mc.getSingleplayerServer();
        if (sv == null || server == null) {
            return;
        }
        server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
            if (p == null) {
                return;
            }
            ServerLevel level = p.level();
            java.util.Set<Long> sent = new java.util.HashSet<>();
            for (int k = 0; k < 2; k++) {
                int cx = sv[k * 5] >> 4, cz = sv[k * 5 + 2] >> 4;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (sent.add(net.minecraft.world.level.ChunkPos.pack(cx + dx, cz + dz))) {
                            p.connection.send(new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(
                                    level.getChunk(cx + dx, cz + dz), level.getLightEngine(), null, null));
                        }
                    }
                }
            }
        });
    }

    private static void server(Minecraft mc, java.util.function.Consumer<ServerLevel> job) {
        var server = mc.getSingleplayerServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
            if (p != null) {
                job.accept(p.level());
            }
        });
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
