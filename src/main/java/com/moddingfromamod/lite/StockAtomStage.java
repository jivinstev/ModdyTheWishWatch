package com.moddingfromamod.lite;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.vanilla.AtomLibrary;
import com.moddingfromamod.runtime.CreatureAnatomy;
import com.moddingfromamod.vanilla.DisplayRig;
import com.moddingfromamod.vanilla.RigGeometry;
import com.moddingfromamod.vanilla.SpikeFixture;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Stand a synthetic atom fixture in front of a GENUINELY STOCK client, framed exactly as Gate C
 * frames it</b> — {@code docs/ATOM_RIG_PLAN.md} Phase 2b, task 2.
 *
 * <h2>Why this exists rather than another Gate C arm</h2>
 *
 * <p>§5.5 established that the tilted-atom shading defect is produced by two NeoForge patches to
 * vanilla rendering and that <b>a stock client has neither</b>, so it renders a tilted atom
 * correctly. That was read out of somebody else's source and asserted in a unit test; it had never
 * been photographed. Gate C cannot photograph it, by construction: {@code runClient} IS a NeoForge
 * client, which is the whole finding. The only genuinely stock renderer this repo has is
 * {@code tools/vanilla-client.sh} (0 loader entries on its classpath, {@code --proof} enforced),
 * and that client cannot be driven from inside — it has no mod in it. So the staging has to happen
 * on the SERVER, which is what this is.
 *
 * <h2>The frame is copied from the harness, number for number</h2>
 *
 * <p>A face's brightness is decided by its world-space normal and the light, not by where the
 * camera is — so the comparison against {@code VanillaCreatureAbClientTest}'s numbers is only valid
 * if the creature's WORLD ORIENTATION and the LIGHT are the same. Both are reproduced here:
 * {@code stageyaw} 90, {@code dayTime} 6000 with the daylight cycle and the weather frozen, the
 * subject in open sky. The camera pose (seat at {@code (8.5, 96, 8.5)}, yaw 0, pitch derived from
 * the model's own height) is copied too, so the same faces are in frame and a human comparing the
 * two pictures is comparing pictures of the same thing.
 *
 * <p><b>The player is re-posed every tick.</b> Same rule as the photo harness's: one teleport is
 * undone by the next tick of gravity or by a client-side position correction, and a camera two
 * blocks low takes a perfectly good photograph of the floor. Rotation only reaches a client through
 * a teleport PACKET — {@code setYRot} is a fact about the server's copy — so the pose goes out as
 * {@link ClientboundPlayerPositionPacket} rather than as a setter.
 *
 * <h2>The control is {@code __unitbox}, recoloured, and the recolour is the point</h2>
 *
 * <p>An axis-aligned unit cube is the corner of §5.5's 2×2 that both paths agree on, so it is the
 * control that says "this picture is of a working atom rig" — without it, a wrong reading on the
 * tilt could equally be a broken pipeline, a missing pack or a camera pointing at nothing. Both
 * fixtures are {@code #FF3030}, and one shade histogram cannot separate two subjects of one colour,
 * so the control's geometry is {@link SpikeFixture#unitBox()} verbatim with the colour mapped to
 * {@code #30FF30}. Nothing else about it changes.
 */
public final class StockAtomStage {

    private StockAtomStage() {
    }

    private static final Logger LOG = LoggerFactory.getLogger("mfam-stock-atom");

    /** {@code -Dmoddingfromamod.stockatom=__tiltx} — the fixture to stand in front of the child. */
    public static final String PROPERTY = ModdingFromAModMod.MODID + ".stockatom";

    /** The harness's own camera seat, copied so the two pictures are of the same thing. */
    private static final double CAM_X = 8.5;
    private static final double CAM_Y = 96.0;
    private static final double CAM_Z = 8.5;
    /** {@code VanillaCreatureAbClientTest}'s {@code stageyaw} default. */
    private static final float STAGE_YAW = 90.0f;
    /** How far to the player's left the control stands, in blocks. */
    private static final double CONTROL_DX = -2.6;
    /** The control's colour — see the class javadoc. */
    private static final int CONTROL_COLOUR = 0x30FF30;

    private static volatile boolean staged;
    private static double camDistance;
    private static double camPitch;

    /** Which fixture, or empty if this arm is off. */
    public static String fixture() {
        return System.getProperty(PROPERTY, "").trim();
    }

    public static boolean active() {
        return !fixture().isEmpty();
    }

    /**
     * Build the scene once, and re-pose the player every tick afterwards.
     *
     * <p>Called from {@link LiteProbe}'s tick so it inherits the probe's "a join is the least
     * settled moment" discipline: the item pack is pushed at join and a stock client applies one by
     * RELOADING, so staging at the join instant would put the creature in the world while the
     * client was still rebuilding its atlases.
     *
     * @param elapsedSeconds how long the subject has been in the world
     */
    public static void tick(MinecraftServer server, ServerPlayer subject, long elapsedSeconds) {
        if (!active() || server == null || subject == null) {
            return;
        }
        // ⚠ NOT whichever player joined last. CameraSupervisor starts a camera on any
        // dedicated server with a cameraName set, which is the default — measured here, it joined
        // 84 s in — and LiteProbe's `subject` is the LAST joiner. Following that would silently
        // stop re-posing the CHILD and start re-posing the CAMERA, so the photographs after that
        // moment would be of wherever the child had drifted to. A service client is registered as
        // the mod's own before anybody joins, so PlayerKind can tell them apart.
        ServerPlayer child = theChild(server, subject);
        if (child == null) {
            return;
        }
        if (!staged) {
            if (elapsedSeconds < 12) {
                return;
            }
            staged = true;
            try {
                build(server, child);
            } catch (RuntimeException e) {
                LOG.error("could not stage the stock-client atom scene", e);
            }
        }
        pose(child);
        if (!FIT_RIGS.isEmpty()) {
            turnFitScene();
        }
    }

    /** The first player who is not one of the mod's own service clients, or null. */
    private static ServerPlayer theChild(MinecraftServer server, ServerPlayer fallback) {
        var service = com.moddingfromamod.marshal.PlayerKind.serviceClients();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (com.moddingfromamod.marshal.PlayerKind.isChild(p.getUUID(), service)) {
                return p;
            }
        }
        return com.moddingfromamod.marshal.PlayerKind.isChild(fallback.getUUID(), service)
                ? fallback : null;
    }

    private static void build(MinecraftServer server, ServerPlayer child) {
        String name = fixture();
        ServerLevel level = child.level();

        // FREEZE THE SCENE, exactly as the harness does. A moving sun is a moving lightmap, and
        // the whole measurement is a brightness.
        for (ServerLevel l : server.getAllLevels()) {
            l.setDayTime(6000L);
            l.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
            l.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
            l.setWeatherParameters(6000, 0, false, false);
        }

        if (FIT_SCENE.equals(name)) {
            buildFitScene(level);
            return;
        }
        List<CreatureSculpt.Part> parts = SpikeFixture.parts(name);
        double scale = SpikeFixture.scale(name);
        var extents = CreatureAnatomy.extents(RigGeometry.of(parts));
        double modelHeight = CreatureAnatomy.heightBlocks(extents) * scale;
        double width = CreatureAnatomy.widthBlocks(extents) * scale;
        camDistance = Math.max(2.2, Math.min(26.0, modelHeight * 1.3));
        double aimY = (CAM_Y - 0.8) + modelHeight * 0.55;
        camPitch = -Math.toDegrees(
                Math.atan2(aimY - (CAM_Y + child.getEyeHeight()), camDistance));

        Vec3 stage = new Vec3(CAM_X, CAM_Y - 0.8, CAM_Z + camDistance);
        DisplayRig subject = DisplayRig.spawn(level, stage, STAGE_YAW, parts, scale, width,
                modelHeight);
        freeze(subject);

        // The control, beside it. Its own anatomy, so it is framed by its own size rather than by
        // the subject's — a control scaled to somebody else's model is not the fixture it names.
        List<CreatureSculpt.Part> ctlParts = recoloured(SpikeFixture.unitBox(), CONTROL_COLOUR);
        var ctlExtents = CreatureAnatomy.extents(RigGeometry.of(ctlParts));
        DisplayRig control = DisplayRig.spawn(level,
                new Vec3(CAM_X + CONTROL_DX, CAM_Y - 0.8, CAM_Z + camDistance), STAGE_YAW,
                ctlParts, 1.0, CreatureAnatomy.widthBlocks(ctlExtents),
                CreatureAnatomy.heightBlocks(ctlExtents));
        freeze(control);

        LOG.info("STOCKATOM staged '{}' ({} display(s), scale {}, height {} blocks) at {} and the "
                        + "__unitbox control (#{}) at dx {} — camera {} blocks back, pitch {}, "
                        + "yaw {}. atoms={} library={} at {}",
                name, subject.displays().size(), scale, String.format("%.3f", modelHeight), stage,
                String.format("%06X", CONTROL_COLOUR), CONTROL_DX,
                String.format("%.3f", camDistance), String.format("%.2f", camPitch), STAGE_YAW,
                true, AtomLibrary.modelName(), AtomLibrary.modelData());
    }

    /**
     * <b>an earlier issue's scene, for a stock client</b> ({@code FIXTURE=__rigfit774}): a whale whose sculpt
     * origin is its tail, in a pool, and Spikor on land, carriers GLOWING so the outline seen on film
     * is drawn, both turning a slow circle so a stock client's pictures show what they turn
     * about. Staged from the server because a stock client runs no mod code.
     */
    public static final String FIT_SCENE = "__rigfit774";
    private static final List<DisplayRig> FIT_RIGS = new ArrayList<>();
    private static float fitYaw;

    private static void buildFitScene(ServerLevel level) {
        int floor = (int) Math.floor(CAM_Y) - 9;
        double zc = CAM_Z + 16;
        for (int x = (int) CAM_X - 16; x <= (int) CAM_X + 16; x++) {
            for (int z = (int) CAM_Z + 4; z <= (int) CAM_Z + 30; z++) {
                level.setBlock(new net.minecraft.core.BlockPos(x, floor, z),
                        net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 2);
                boolean pool = x >= (int) CAM_X - 14 && x <= (int) CAM_X - 1
                        && z >= (int) CAM_Z + 7 && z <= (int) CAM_Z + 27;
                boolean rim = pool && (x == (int) CAM_X - 14 || x == (int) CAM_X - 1
                        || z == (int) CAM_Z + 7 || z == (int) CAM_Z + 27);
                for (int h = 1; h <= 3; h++) {
                    level.setBlock(new net.minecraft.core.BlockPos(x, floor + h, z), rim
                            ? net.minecraft.world.level.block.Blocks.GLASS.defaultBlockState()
                            : pool ? net.minecraft.world.level.block.Blocks.WATER.defaultBlockState()
                            : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        List<CreatureSculpt.Part> whale = List.of(
                fitPart("body", -8, 2, -56, 8, 18, 0), fitPart("head", -7, 3, -72, 7, 16, -56),
                fitPart("tail", -5, 6, 0, 5, 14, 12), fitPart("fluke", -14, 8, 12, 14, 10, 20),
                fitPart("finL", 8, 4, -44, 16, 6, -36), fitPart("finR", -16, 4, -44, -8, 6, -36));
        fitRig(level, "rigfit_whale", whale, 1.5, new Vec3(CAM_X - 7.5, floor + 1, zc));
        fitRig(level, "rigfit_spikor", SpikeFixture.parts(SpikeFixture.SPIKOR),
                SpikeFixture.scale(SpikeFixture.SPIKOR), new Vec3(CAM_X + 7.5, floor + 1, zc));
        camPitch = 28.0;
        LOG.info("STOCKATOM staged '{}': {}", FIT_SCENE, FIT_RIGS.stream()
                .map(r -> r.base().getType().toShortString() + " x" + String.format("%.2f",
                        r.choice() == null ? 0 : r.choice().scale())).toList());
    }

    private static CreatureSculpt.Part fitPart(String n, float x0, float y0, float z0,
                                               float x1, float y1, float z1) {
        return new CreatureSculpt.Part(n, null, new float[]{0, y0, 0},
                new float[]{x0, y0, z0}, new float[]{x1, y1, z1}, 0x3050E0);
    }

    private static void fitRig(ServerLevel level, String key, List<CreatureSculpt.Part> parts,
                               double scale, Vec3 at) {
        var ext = CreatureAnatomy.extents(RigGeometry.of(parts));
        DisplayRig rig = DisplayRig.spawn(level, at, 0f, parts, scale,
                CreatureAnatomy.widthBlocks(ext) * scale, CreatureAnatomy.heightBlocks(ext) * scale,
                key);
        freeze(rig);
        rig.base().setGlowingTag(true);
        FIT_RIGS.add(rig);
    }

    /** One degree and a half a tick: a full turn in twelve seconds. */
    private static void turnFitScene() {
        fitYaw = (fitYaw + 1.5f) % 360f;
        for (DisplayRig r : FIT_RIGS) {
            r.base().setYRot(fitYaw);
            r.base().setYBodyRot(fitYaw);
            r.base().setYHeadRot(fitYaw);
            com.moddingfromamod.vanilla.RigDriver.follow(r, r.base().position(), fitYaw);
        }
    }

    private static void freeze(DisplayRig rig) {
        rig.base().setNoGravity(true);
        rig.base().setNoAi(true);
        for (var d : rig.all()) {
            d.setNoGravity(true);
        }
    }

    /** {@link SpikeFixture#unitBox()} with one field changed — see the class javadoc. */
    private static List<CreatureSculpt.Part> recoloured(List<CreatureSculpt.Part> parts, int rgb) {
        List<CreatureSculpt.Part> out = new ArrayList<>(parts.size());
        for (CreatureSculpt.Part p : parts) {
            out.add(new CreatureSculpt.Part(p.name(), p.parent(), p.pivot(), p.from(), p.to(),
                    rgb, p.rotation(), p.inflate(), p.pattern(), p.patternColour()));
        }
        return out;
    }

    /**
     * Put the child on the harness's seat, every tick.
     *
     * <p>Through the connection rather than through {@code setYRot}: a server-side rotation is a
     * fact about the server's copy of the player and never reaches the client, which owns its own
     * camera. {@code teleport} is what {@code /tp} uses and is the only thing that moves a stock
     * client's view.
     */
    private static void pose(ServerPlayer child) {
        child.connection.teleport(CAM_X, CAM_Y, CAM_Z, 0.0f, (float) camPitch);
        child.setDeltaMovement(Vec3.ZERO);
        child.fallDistance = 0f;
        child.setNoGravity(true);
    }
}
