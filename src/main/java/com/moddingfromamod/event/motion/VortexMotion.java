package com.moddingfromamod.event.motion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import com.moddingfromamod.event.EventMotion;
import com.moddingfromamod.event.EventScale;
import com.moddingfromamod.event.EventWriter;
import com.moddingfromamod.event.catching.Effects;
import com.moddingfromamod.event.shape.Vortex;
import com.moddingfromamod.event.shape.VortexForces;
import com.moddingfromamod.event.shape.VortexLook;
import com.moddingfromamod.event.shape.VortexPath;
import com.moddingfromamod.event.shape.VortexWeather;
import com.mojang.math.Transformation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * <b>Something that spins: a tornado, a whirlpool, a hurricane</b> (docs/EVENTS_PLAN.md §16).
 *
 * <p>The adapter for the pure {@link Vortex}, {@link VortexPath}, {@link VortexForces} and {@link VortexLook}: it
 * draws the walls as display blocks round each funnel's foot, moves the foot along its path, catches what the size
 * can lift and moves it with ONE velocity that replaces the last ({@link Effects#move}, so a landing is safe), tears
 * up the blocks the size may take (journaled through {@link EventWriter#breakBlock}, so the world comes back) and
 * flies them up the wall as debris. When it ends every display it drew goes.
 *
 * <p>Displays are re-posed every {@link #POSE_EVERY} ticks with a glide of the same length: the client eases both
 * the move and the turn, which is what makes a spinning wall smooth where rewriting blocks never could be.
 */
public final class VortexMotion implements EventMotion {

    /** Ticks between poses; each display glides over the same number. */
    public static final int POSE_EVERY = 2;

    /** A display drawn round one funnel's foot: one piece of wall, or one bit of debris. */
    private record Drawn(Entity display, VortexLook.Piece piece, int funnel, boolean ribbon, boolean flip) {
    }

    private static final class Debris {
        final Entity display;
        final int funnel;
        final double phase;
        final int born;
        final boolean loops;

        Debris(Entity display, int funnel, double phase, int born, boolean loops) {
            this.display = display;
            this.funnel = funnel;
            this.phase = phase;
            this.born = born;
            this.loops = loops;
        }
    }

    private final Vortex.Shape shape;
    private final EventScale scale;
    private final Vortex.Size size;
    private final BlockState material;
    private final BlockState core;
    private final VortexLook.Family family;
    private final List<VortexPath> paths;
    private final int life;
    private final EntityType<?> riders;
    private int ridersLeft;
    private final BlockState riderBlock;
    private final Supplier<ServerPlayer> him;
    private final boolean giveBackTrack;
    /** It wrecks his builds (the default), or tears up only what the world grew. */
    private final boolean wrecks;
    private final Set<String> cameras;
    private final Random random;
    /** Each funnel's sway phase, so twins do not bend in step. */
    private final double[] swayPhase;
    /** How its walls are drawn: the design's {@code look}, else {@link VortexLook#forVortex}. */
    private VortexLook.Style style;

    private final VortexPath.At[] feet;
    private final double[] footY;
    private final List<Drawn> walls = new ArrayList<>();
    private final List<Debris> debris = new ArrayList<>();
    private final List<BlockPos> torn = new ArrayList<>();
    private final Map<UUID, Integer> held = new HashMap<>();
    /** Thrown out at this age: left alone for {@link #LET_GO} ticks, or it is caught again before it can fly. */
    private final Map<UUID, Integer> thrownAt = new HashMap<>();
    /** Ticks a thing it threw out is left alone. */
    public static final int LET_GO = 40;
    /** Ticks a drain keeps pushing what it spat out: water drags a one-tick pop to nothing in two blocks. */
    public static final int SPIT_TICKS = 12;
    private final Map<UUID, Vec3> spit = new HashMap<>();
    /** Each column's top block when the vortex first looked at it: natural ground below it is never torn. */
    private final Map<Long, Integer> firstTop = new HashMap<>();
    private final List<Entity> caughtNow = new ArrayList<>();
    private int tornCount;
    private int liftedCount;
    private boolean ended;

    /**
     * @param giveBackTrack give every torn block back when it ends, even if the event keeps what it LEFT (a cyclone
     *                      of cats keeps its cats, never the hole it tore)
     */
    public VortexMotion(Vortex.Shape shape, EventScale scale, BlockState material, BlockState core,
                        List<VortexPath> paths, int life, long seed, EntityType<?> riders, int riderCount,
                        BlockState riderBlock,
                        Supplier<ServerPlayer> him, boolean giveBackTrack, boolean wrecks, Set<String> cameras) {
        this.shape = shape;
        this.scale = scale;
        this.size = Vortex.size(scale);
        this.material = material;
        this.core = core;
        this.family = VortexLook.Family.of(
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(material.getBlock()).toString());
        this.paths = List.copyOf(paths);
        this.life = life;
        this.riders = riders;
        this.ridersLeft = riders == null ? 0 : riderCount;
        this.riderBlock = riderBlock;
        this.him = him;
        this.giveBackTrack = giveBackTrack;
        this.wrecks = wrecks;
        this.cameras = cameras == null ? Set.of() : cameras;
        this.style = VortexLook.forVortex(shape, family);
        this.random = new Random(seed);
        this.swayPhase = new double[paths.size()];
        for (int i = 0; i < swayPhase.length; i++) {
            swayPhase[i] = random.nextDouble() * Math.PI * 2;
        }
        this.feet = new VortexPath.At[paths.size()];
        this.footY = new double[paths.size()];
        for (int i = 0; i < paths.size(); i++) {
            feet[i] = paths.get(i).at(0, life);
            footY[i] = Double.NaN;
        }
    }

    /** Draw it in {@code s}, the look his words named; null keeps the table's ({@link VortexLook#forVortex}). */
    public VortexMotion style(VortexLook.Style s) {
        if (s != null) {
            this.style = s;
        }
        return this;
    }

    /** The look it is drawn in. */
    public VortexLook.Style look() {
        return style;
    }

    // ── for the tests and the camera ──────────────────────────────────────────────────────────────

    public int life() {
        return life;
    }

    /** Where funnel {@code i}'s foot is now (its ground, its middle). */
    public Vec3 foot(int i) {
        return new Vec3(feet[i].x(), Double.isNaN(footY[i]) ? 0 : footY[i], feet[i].z());
    }

    public BlockPos footBlock(int i) {
        return BlockPos.containing(foot(i));
    }

    public int funnels() {
        return feet.length;
    }

    /** What it caught this tick (for a design's own {@code catch} effects). */
    public List<Entity> caughtNow() {
        return List.copyOf(caughtNow);
    }

    /** Blocks it has torn up so far, and things it has lifted (counted per tick each was moved). */
    public int torn() {
        return tornCount;
    }

    public int lifted() {
        return liftedCount;
    }

    public int displays() {
        return walls.size() + debris.size();
    }

    // ── the tick ──────────────────────────────────────────────────────────────────────────────────

    @Override
    public boolean step(EventWriter w, int age) {
        ServerLevel level = w.level();
        if (ended) {
            return true;
        }
        if (age >= life) {
            end(w);
            return true;
        }
        moveFeet(w, age);
        if (age == 0) {
            draw(w);
            start(w);
        }
        caughtNow.clear();
        for (int i = 0; i < feet.length; i++) {
            catchThings(level, i, age);
            if (age % 2 == 0) {
                tear(w, i, age);
            }
            effects(level, i, age);
        }
        if (age % POSE_EVERY == 0) {
            pose(age);
        }
        spawnRiders(w, age);
        if (shape == Vortex.Shape.SPIRAL) {
            localWeather(level, false);
        }
        return false;
    }

    private void moveFeet(EventWriter w, int age) {
        ServerPlayer p = him == null ? null : him.get();
        for (int i = 0; i < feet.length; i++) {
            VortexPath path = paths.get(i);
            if (path.path() == Vortex.Path.FOLLOW) {
                if (p != null && p.level() == w.level()) {
                    double gap = Vortex.reach(shape, scale) * (shape == Vortex.Shape.SPIRAL ? 0.2 : 0.8) + 2;
                    feet[i] = VortexPath.chase(feet[i], new VortexPath.At(p.getX(), p.getZ()), path.speed(), gap);
                }
            } else {
                feet[i] = path.at(age, life);
            }
            int sy = w.surface((int) Math.floor(feet[i].x()), (int) Math.floor(feet[i].z()));
            if (sy == Integer.MIN_VALUE) {
                continue;   // an unloaded column: it keeps its last height and never generates the world ahead
            }
            double ground = sy + 1;
            footY[i] = Double.isNaN(footY[i]) ? ground : footY[i] + Math.max(-0.35, Math.min(0.35, ground - footY[i]));
        }
    }

    private void start(EventWriter w) {
        ServerLevel level = w.level();
        Vec3 f = foot(0);
        // The hurricane's weather is SENT to the players near it, never set on the server (localWeather).
        if (riderBlock != null) {
            for (int k = 0; k < Math.min(size.maxDebris(), 6 + 4 * scale.ordinal()); k++) {
                Entity d = display(level, riderBlock, foot(k % feet.length), 0.7f, w);
                if (d != null) {
                    debris.add(new Debris(d, k % feet.length, random.nextDouble() * Math.PI * 2, -k * 9, true));
                }
            }
        }
        level.playSound(null, f.x, f.y, f.z, sound(), SoundSource.WEATHER, 3.0f, size.spinPitch());
    }

    // ── creatures riding it ───────────────────────────────────────────────────────────────────────────

    /** Ticks over which a cyclone of creatures is fed in: a stream, not one pile. */
    public static final int RIDER_TICKS = 80;

    /**
     * A cyclone of cats: its whole count (the size's number, never past one wish's wave of that creature —
     * {@code EventBuild.skyCount}, the same budget as "it rains cats") fed in over {@link #RIDER_TICKS}, each appearing
     * at a random height inside the wall so the funnel is full of them.
     */
    private void spawnRiders(EventWriter w, int age) {
        if (riders == null || ridersLeft <= 0 || age >= RIDER_TICKS) {
            return;
        }
        int now = (int) Math.ceil(ridersLeft / (double) Math.max(1, RIDER_TICKS - age));
        for (int k = 0; k < now && ridersLeft > 0; k++) {
            Entity e = riders.create(w.level(), EntitySpawnReason.EVENT);
            ridersLeft--;
            if (e == null) {
                continue;
            }
            int i = ridersLeft % feet.length;
            Vec3 at = foot(i);
            double h = 1 + random.nextDouble() * size.height() * 0.6;
            double a = random.nextDouble() * Math.PI * 2;
            double r = Vortex.funnelRadius(size, h) * 0.75;
            e.snapTo(at.x + Math.cos(a) * r, at.y + h, at.z + Math.sin(a) * r, random.nextFloat() * 360, 0);
            w.spawn(e);
        }
    }

    // ── a hurricane's weather, near it only ───────────────────────────────────────────────────────────

    private final Map<UUID, float[]> weatherSent = new HashMap<>();

    /**
     * Rain and thunder for each player near the storm, fading with distance ({@link VortexWeather}); the server's
     * weather is never touched, so no other island's sky changes (a shared world: "we all share one sky"). When it is
     * already raining for real there is nothing to send. {@code ending}: give every player we touched his real sky back.
     */
    private void localWeather(ServerLevel level, boolean ending) {
        if (level.isRaining() && !ending) {
            return;
        }
        Vec3 f = foot(0);
        double reach = Vortex.spiralRadius(scale);
        for (ServerPlayer p : level.players()) {
            float[] was = weatherSent.get(p.getUUID());
            float target = ending ? 0f : VortexWeather.target(Math.hypot(p.getX() - f.x, p.getZ() - f.z), reach);
            if (was == null && target <= 0f) {
                continue;
            }
            if (was == null) {
                was = new float[] {0f};
                weatherSent.put(p.getUUID(), was);
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundGameEventPacket(
                        net.minecraft.network.protocol.game.ClientboundGameEventPacket.START_RAINING, 0f));
            }
            float next = ending ? 0f : VortexWeather.step(was[0], target);
            if (next != was[0] || ending) {
                was[0] = next;
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundGameEventPacket(
                        net.minecraft.network.protocol.game.ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, next));
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundGameEventPacket(
                        net.minecraft.network.protocol.game.ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, next));
            }
            if (next <= 0f && (ending || target <= 0f) && !level.isRaining()) {
                // Out of it: his own sky again (STOP_RAINING sets the level to 1, so the zeroes follow it).
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundGameEventPacket(
                        net.minecraft.network.protocol.game.ClientboundGameEventPacket.STOP_RAINING, 0f));
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundGameEventPacket(
                        net.minecraft.network.protocol.game.ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, 0f));
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundGameEventPacket(
                        net.minecraft.network.protocol.game.ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, 0f));
                weatherSent.remove(p.getUUID());
            }
        }
    }

    /** Test seam: how many players are being sent this storm's weather now. */
    public int playersInWeather() {
        return weatherSent.size();
    }

    // ── drawing ───────────────────────────────────────────────────────────────────────────────────

    private void draw(EventWriter w) {
        List<VortexLook.Piece> pieces = VortexLook.pieces(shape, scale);
        int perFunnel = Math.max(12, size.maxDisplays() / feet.length);
        VortexLook.Style look = look();
        BlockState[] sk = streakBlocks();
        BlockState streak = sk[0], streak2 = sk[1];
        for (int i = 0; i < feet.length; i++) {
            int stride = Math.max(1, (int) Math.ceil(pieces.size() / (double) perFunnel));
            for (int k = 0; k < pieces.size(); k += stride) {
                VortexLook.Piece p = VortexLook.styled(pieces.get(k), look);
                Entity d;
                if (look.ribbons()) {
                    d = ribbon(w.level(), VortexLook.mistColour(p, family), foot(i), w, false);
                } else if (look == VortexLook.Style.STREAKS) {
                    d = display(w.level(), p.core() ? streak2 : streak, foot(i), 1f, w);
                } else {
                    d = display(w.level(), p.core() && core != null ? core : material, foot(i), 1f, w);
                }
                if (d != null) {
                    walls.add(new Drawn(d, p, i, look.ribbons(), false));
                }
                // A text background is drawn from its FRONT only, so one band showed the funnel's far wall and left
                // its near wall invisible. Each band gets a twin facing the other way.
                if (d != null && look.ribbons()) {
                    Entity back = ribbon(w.level(), VortexLook.mistColour(p, family), foot(i), w, true);
                    if (back != null) {
                        walls.add(new Drawn(back, p, i, true, true));
                    }
                }
            }
        }
        pose(0);
    }

    /** A display of {@code state} at {@code at}, gliding over {@link #POSE_EVERY} ticks, seen from far off. */
    private static Entity display(ServerLevel level, BlockState state, Vec3 at, float scale, EventWriter w) {
        Entity d = EntityType.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND);
        if (d == null) {
            return null;
        }
        d.snapTo(at.x, at.y, at.z, 0f, 0f);
        CompoundTag tag = com.moddingfromamod.compat.EntityNbt.save(d);
        tag.put("block_state", NbtUtils.writeBlockState(state));
        tag.putInt("teleport_duration", POSE_EVERY);
        tag.putFloat("view_range", 6f);
        tag.put("brightness", openSky());
        com.moddingfromamod.compat.EntityNbt.load(d, tag);
        w.spawn(d);
        return d;
    }

    /** A family's streak slivers — its own blocks where they are opaque, a concrete of its colour for glass. */
    private BlockState[] streakBlocks() {
        return switch (family) {
            case WATER -> new BlockState[] {net.minecraft.world.level.block.Blocks.LIGHT_BLUE_CONCRETE.defaultBlockState(),
                    net.minecraft.world.level.block.Blocks.BLUE_CONCRETE.defaultBlockState()};
            case FIRE -> new BlockState[] {net.minecraft.world.level.block.Blocks.ORANGE_CONCRETE.defaultBlockState(),
                    net.minecraft.world.level.block.Blocks.RED_CONCRETE.defaultBlockState()};
            case ENDER -> new BlockState[] {net.minecraft.world.level.block.Blocks.PURPLE_CONCRETE.defaultBlockState(),
                    net.minecraft.world.level.block.Blocks.OBSIDIAN.defaultBlockState()};
            case WIND, CLOUD, OTHER -> new BlockState[] {net.minecraft.world.level.block.Blocks.WHITE_CONCRETE.defaultBlockState(),
                    net.minecraft.world.level.block.Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState()};
            default -> new BlockState[] {material, core != null ? core : material};
        };
    }

    /**
     * A see-through band of mist — a text display whose only content is its background (one space), lit as open sky
     * (unlike the reveal's lights, which are full-bright: a band dims at night). {@code back}: the band's
     * back-facing twin (a text background is drawn from its front only), which a Bedrock player is never sent.
     */
    private static Entity ribbon(ServerLevel level, int argb, Vec3 at, EventWriter w, boolean back) {
        Entity d = EntityType.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND);
        if (d == null) {
            return null;
        }
        d.snapTo(at.x, at.y, at.z, 0f, 0f);
        CompoundTag tag = com.moddingfromamod.compat.EntityNbt.save(d);
        tag.put("text", net.minecraft.network.chat.ComponentSerialization.CODEC
                .encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, net.minecraft.network.chat.Component.literal(" "))
                .getOrThrow());
        tag.putString("billboard", "fixed");
        tag.putInt("background", argb);
        tag.putInt("line_width", 200);
        tag.putBoolean("shadow", false);
        tag.putInt("teleport_duration", POSE_EVERY);
        tag.putFloat("view_range", 6f);
        tag.put("brightness", openSky());
        com.moddingfromamod.compat.EntityNbt.load(d, tag);
        d.addTag(RIBBON_TAG);
        if (back) {
            d.addTag(RIBBON_BACK_TAG);
        }
        w.spawn(d);
        return d;
    }

    /**
     * A vortex's mist band. For a Bedrock player the server sends it as the same see-through atom quad the reveal's
     * lights become ({@code BedrockLights}), its colour and see-through amount kept, lit by the world rather than
     * glowing; Java is sent nothing different.
     */
    public static final String RIBBON_TAG = "moddy_vortex_ribbon";
    /** A band's back-facing twin: Java needs it, Bedrock's atom quad is already two-sided, so Bedrock is not sent it. */
    public static final String RIBBON_BACK_TAG = "moddy_vortex_ribbon_back";

    /**
     * Every display a vortex draws is lit as open sky, never by the block at its foot: a display is lit where it
     * STANDS, and all of them stand at the funnel's foot — in torn ground or under a tree a big tornado's mist came out
     * dark grey and the leaves it tore up black (2026-11-15). Sky light still dims at night, so nothing glows.
     */
    private static CompoundTag openSky() {
        CompoundTag light = new CompoundTag();
        light.putInt("sky", 15);
        light.putInt("block", 0);
        return light;
    }

    /** One space's background, in blocks at scale 1 (5 × 10 px at 1/40 block), and its half-pixel offset per unit. */
    static final float RIBBON_UNIT_W = 0.125f, RIBBON_UNIT_H = 0.25f, RIBBON_OFF_X = 0.0125f;

    /** A ribbon at its angle now: the band's face is radial, its length along the ring, centred on its spot. */
    static Transformation ribbonPose(VortexLook.Piece p, int tick) {
        return ribbonPose(p, tick, false);
    }

    /** {@code flip}: the band's back-facing twin, turned half round about its own up axis. */
    static Transformation ribbonPose(VortexLook.Piece p, int tick, boolean flip) {
        double a = p.angle(tick);
        Quaternionf l = new Quaternionf().rotationY((float) -(a + Math.PI / 2)).rotateX((float) p.tilt());
        if (flip) {
            l.rotateY((float) Math.PI);
        }
        float sx = (float) (p.along() / RIBBON_UNIT_W);
        float sy = (float) (p.tall() / RIBBON_UNIT_H);
        Vector3f local = new Vector3f(-RIBBON_OFF_X * sx, (float) -p.tall() / 2, 0f);
        Vector3f t = new Quaternionf(l).transform(local)
                .add((float) (Math.cos(a) * p.radius()), (float) p.y(), (float) (Math.sin(a) * p.radius()));
        return new Transformation(t, l, new Vector3f(sx, sy, 1f), new Quaternionf());
    }

    /**
     * One piece at its angle now. A display draws the unit cube as {@code T + L·(S·v)}; we want the slab centred on
     * its spot on the ring, its long side along the ring (tangent), leaning by its tilt.
     */
    static Transformation piecePose(VortexLook.Piece p, int tick) {
        double a = p.angle(tick);
        Quaternionf l = new Quaternionf().rotationY((float) -(a + Math.PI / 2)).rotateX((float) p.tilt());
        Vector3f s = new Vector3f((float) p.along(), (float) p.tall(), (float) p.thick());
        Vector3f half = new Vector3f(-s.x / 2, -s.y / 2, -s.z / 2);
        Vector3f t = new Quaternionf(l).transform(half)
                .add((float) (Math.cos(a) * p.radius()), (float) p.y(), (float) (Math.sin(a) * p.radius()));
        return new Transformation(t, l, s, new Quaternionf());
    }

    static Transformation debrisPose(double[] at, float edge, int tick) {
        Quaternionf l = new Quaternionf().rotationXYZ(tick * 0.31f, tick * 0.23f, tick * 0.17f);
        Vector3f half = new Quaternionf(l).transform(new Vector3f(-edge / 2, -edge / 2, -edge / 2));
        return new Transformation(half.add((float) at[0], (float) at[1], (float) at[2]), l,
                new Vector3f(edge, edge, edge), new Quaternionf());
    }

    private void pose(int age) {
        for (Drawn d : walls) {
            Vec3 f = foot(d.funnel());
            double[] bend = VortexLook.sway(shape, scale, d.piece().y(), age, swayPhase[d.funnel()]);
            Transformation t = d.ribbon() ? ribbonPose(d.piece(), age, d.flip()) : piecePose(d.piece(), age);
            set(d.display(), f, bend[0] == 0 && bend[1] == 0 ? t : new Transformation(
                    t.getTranslation().add((float) bend[0], 0f, (float) bend[1], new Vector3f()),
                    t.getLeftRotation(), t.getScale(), t.getRightRotation()));
        }
        List<Debris> gone = new ArrayList<>();
        for (Debris b : debris) {
            int a = age - b.born;
            if (a < 0) {
                continue;
            }
            double[] at = VortexLook.debris(shape, scale, b.loops ? a % 90 : a, b.phase);
            if (at == null) {
                gone.add(b);
                continue;
            }
            double[] bend = VortexLook.sway(shape, scale, at[1], age, swayPhase[b.funnel]);
            set(b.display, foot(b.funnel), debrisPose(new double[] {at[0] + bend[0], at[1], at[2] + bend[1]}, 0.8f, age));
        }
        for (Debris b : gone) {
            Vec3 f = foot(b.funnel);
            ((ServerLevel) b.display.level()).sendParticles(ParticleTypes.POOF, f.x, f.y + Vortex.tall(shape, scale), f.z,
                    2, 1, 0.5, 1, 0.02);
            b.display.discard();
            debris.remove(b);
        }
    }

    private static void set(Entity d, Vec3 at, Transformation t) {
        if (d.position().distanceToSqr(at) > 1e-6) {
            d.snapTo(at.x, at.y, at.z, 0f, 0f);
        }
        var pose = (com.moddingfromamod.mixin.DisplayPoseInvoker) d;
        pose.moddingfromamod$setTransformation(t);
        pose.moddingfromamod$setTransformationInterpolationDuration(POSE_EVERY);
        pose.moddingfromamod$setTransformationInterpolationDelay(0);
    }

    // ── catching ──────────────────────────────────────────────────────────────────────────────────

    private void catchThings(ServerLevel level, int i, int age) {
        Vec3 f = foot(i);
        double reach = Vortex.reach(shape, scale) + 1;
        double up = Vortex.tall(shape, scale) + 3;
        double down = shape == Vortex.Shape.DRAIN ? Vortex.drainDepth(size) + 2 : 2;
        AABB box = new AABB(f.x - reach, f.y - down, f.z - reach, f.x + reach, f.y + up, f.z + reach);
        for (Entity e : level.getEntities((Entity) null, box, e -> catchable(e))) {
            Integer thrown = thrownAt.get(e.getUUID());
            if (thrown != null && age - thrown < LET_GO) {
                Vec3 out = spit.get(e.getUUID());
                if (out != null && age - thrown < SPIT_TICKS) {
                    Effects.move(level, e, out);   // replaces, never adds: it holds the spit, never builds on it
                }
                continue;
            }
            Vortex.Body body = body(e);
            boolean liftable = size.canLift(body);
            double dy = e.getY() - f.y;
            double[] bend = VortexLook.sway(shape, scale, dy, age, swayPhase[i]);   // the axis where it is, bent
            double dx = e.getX() - f.x - bend[0], dz = e.getZ() - f.z - bend[1];
            VortexForces.Push push = switch (shape) {
                case FUNNEL -> VortexForces.funnel(scale, dx, dy, dz, e.getDeltaMovement().y, liftable);
                case DRAIN -> VortexForces.drain(scale, dx, -dy, dz, held.getOrDefault(e.getUUID(), 0), liftable);
                case SPIRAL -> VortexForces.spiral(scale, dx, dy, dz, e.getDeltaMovement().y,
                        body == Vortex.Body.LIGHT);
            };
            if (!push.caught()) {
                continue;
            }
            caughtNow.add(e);
            Effects.move(level, e, new Vec3(push.vx(), push.vy(), push.vz()));
            if (liftable) {
                liftedCount++;
                held.merge(e.getUUID(), 1, Integer::sum);
            }
            if (e instanceof LivingEntity living) {
                if (shape == Vortex.Shape.DRAIN && liftable) {
                    living.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, 200, 0, true, false));
                }
                if (push.released()) {
                    // Thrown out: it floats down. An event he wished for is never what hurts him (CatchRules).
                    living.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 120, 0, true, false));
                }
            }
            if (push.released()) {
                held.remove(e.getUUID());
                thrownAt.put(e.getUUID(), age);
                if (shape == Vortex.Shape.DRAIN) {
                    spit.put(e.getUUID(), new Vec3(push.vx() * 0.6, 0.7, push.vz() * 0.6));
                }
            }
        }
    }

    private boolean catchable(Entity e) {
        if (!e.isAlive() || e instanceof Display || e.isPassenger() || e instanceof LightningBolt
                || com.moddingfromamod.mob.Creatures.isRigInternal(e)) {
            return false;
        }
        if (e instanceof Player p && (p.isSpectator() || cameras.contains(p.getGameProfile().getName()))) {
            return false;
        }
        return !com.moddingfromamod.film.FilmCamera.isCamera(e);
    }

    /** What it weighs to a vortex: its box plus everyone riding it, and a boss never. */
    static Vortex.Body body(Entity e) {
        if (e instanceof EnderDragon || e instanceof WitherBoss) {
            return Vortex.Body.NEVER;
        }
        double mass = Vortex.Body.mass(e.getBbWidth(), e.getBbHeight());
        for (Entity rider : e.getIndirectPassengers()) {
            if (rider instanceof EnderDragon || rider instanceof WitherBoss) {
                return Vortex.Body.NEVER;
            }
            mass += Vortex.Body.mass(rider.getBbWidth(), rider.getBbHeight());
        }
        return Vortex.Body.ofMass(mass);
    }

    // ── tearing up blocks ─────────────────────────────────────────────────────────────────────────

    /** What sort of block this is to a vortex (EVENTS_PLAN §16.2). */
    static Vortex.Loose loose(ServerLevel level, BlockPos pos, BlockState s) {
        if (s.isAir() || s.hasBlockEntity() || !s.getFluidState().isEmpty() || s.getDestroySpeed(level, pos) < 0
                || s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) || s.hasProperty(BlockStateProperties.BED_PART)) {
            return Vortex.Loose.NEVER;   // a chest, the sea, bedrock; a door or a tall flower would leave half behind
        }
        if (s.is(BlockTags.FLOWERS) || s.is(BlockTags.SAPLINGS) || s.is(BlockTags.CROPS) || s.is(Blocks.SNOW)
                || s.is(Blocks.SHORT_GRASS) || s.is(Blocks.FERN) || s.is(Blocks.DEAD_BUSH) || s.canBeReplaced()) {
            return Vortex.Loose.PLANT;
        }
        if (s.is(BlockTags.LEAVES) || s.is(BlockTags.SAND) || s.is(Blocks.GRAVEL) || s.is(Blocks.SNOW_BLOCK)
                || s.is(BlockTags.ICE) || s.is(net.neoforged.neoforge.common.Tags.Blocks.GLASS_BLOCKS)
                || s.is(net.neoforged.neoforge.common.Tags.Blocks.GLASS_PANES)) {
            return Vortex.Loose.LOOSE;
        }
        if (s.is(BlockTags.LOGS) || s.is(BlockTags.DIRT) || s.is(BlockTags.FENCES) || s.is(BlockTags.FENCE_GATES)
                || s.is(BlockTags.WOOL_CARPETS) || s.is(BlockTags.FLOWER_POTS) || s.is(Blocks.TORCH)
                || s.is(Blocks.WALL_TORCH) || s.is(Blocks.LANTERN) || s.is(Blocks.CLAY)) {
            return Vortex.Loose.GROUND;
        }
        return Vortex.Loose.BUILT;
    }

    /** What the world grew rather than he built: a vortex that spares his builds takes only these. */
    static boolean grown(BlockState s) {
        return natural(s) || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || s.is(BlockTags.FLOWERS)
                || s.is(BlockTags.SAPLINGS) || s.is(BlockTags.CROPS) || s.is(Blocks.SHORT_GRASS) || s.is(Blocks.FERN)
                || s.is(Blocks.DEAD_BUSH) || s.is(Blocks.SNOW) || (s.canBeReplaced() && s.getFluidState().isEmpty());
    }

    /** The ground the world is made of (soil, stone, sand, snow): only ever its top block is torn. */
    static boolean natural(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.BASE_STONE_NETHER)
                || s.is(BlockTags.SAND) || s.is(BlockTags.SNOW) || s.is(BlockTags.ICE) || s.is(Blocks.GRAVEL)
                || s.is(Blocks.CLAY) || s.is(Blocks.SANDSTONE) || s.is(BlockTags.TERRACOTTA);
    }

    private void tear(EventWriter w, int i, int age) {
        if (shape == Vortex.Shape.DRAIN || age < 10) {
            return;   // a whirlpool sits in water; and the first half second is for the funnel to form
        }
        ServerLevel level = w.level();
        Vec3 f = foot(i);
        int budget = size.tearsPerTick() * 2;   // it tears every other tick
        double r = shape == Vortex.Shape.SPIRAL ? Vortex.spiralEye(scale) : size.foot() + 1;
        for (int tries = 0; tries < budget * 3 && budget > 0; tries++) {
            double a = random.nextDouble() * Math.PI * 2;
            double d = shape == Vortex.Shape.SPIRAL ? r + random.nextDouble() * 2 : Math.sqrt(random.nextDouble()) * r;
            int x = (int) Math.floor(f.x + Math.cos(a) * d), z = (int) Math.floor(f.z + Math.sin(a) * d);
            int sy = w.surface(x, z);
            if (sy == Integer.MIN_VALUE) {
                continue;
            }
            BlockPos ground = new BlockPos(x, sy, z);
            BlockPos above = ground.above();
            BlockState on = level.getBlockState(above);
            BlockPos pos = !on.isAir() && on.getFluidState().isEmpty() ? above : ground;
            BlockState s = level.getBlockState(pos);
            // Never dig a pit: natural ground gives up only its TOP block (the one first seen in that column). What
            // stands on it — a tree, a wall, his house — comes away layer by layer, as far as the size reaches.
            long col = BlockPos.asLong(x, 0, z);
            int first = firstTop.computeIfAbsent(col, c -> sy);
            if (natural(s) && pos.getY() < first) {
                continue;
            }
            if (!size.tears(loose(level, pos, s), wrecks, grown(s))) {
                continue;
            }
            if (w.breakBlock(pos)) {
                budget--;
                tornCount++;
                torn.add(pos);
                if (debris.size() < size.maxDebris()) {
                    Entity bit = display(level, s, f, 0.8f, w);
                    if (bit != null) {
                        debris.add(new Debris(bit, i, a, age, false));
                    }
                }
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, s), x + 0.5, pos.getY() + 0.5, z + 0.5,
                        6, 0.3, 0.3, 0.3, 0.1);
            }
        }
    }

    // ── particles and sound ───────────────────────────────────────────────────────────────────────

    private ParticleOptions dust() {
        return switch (family) {
            case WIND, CLOUD -> ParticleTypes.CLOUD;
            case WATER -> shape == Vortex.Shape.DRAIN ? ParticleTypes.SPLASH : ParticleTypes.FALLING_WATER;
            case FIRE -> ParticleTypes.FLAME;
            case SNOW -> ParticleTypes.SNOWFLAKE;
            case ENDER -> ParticleTypes.PORTAL;
            default -> new BlockParticleOption(ParticleTypes.BLOCK, material);
        };
    }

    private SoundEvent sound() {
        return switch (shape) {
            case FUNNEL -> SoundEvents.BREEZE_WHIRL;
            case DRAIN -> SoundEvents.BUBBLE_COLUMN_WHIRLPOOL_AMBIENT;
            case SPIRAL -> SoundEvents.WEATHER_RAIN;
        };
    }

    /**
     * Air you can see moving (a MIST funnel) — particles launched along the funnel's wall, TANGENT to it (a count-0
     * particle takes its offset as its velocity), on a few helical strands that climb as they turn.
     */
    private void strands(ServerLevel level, int i, int age) {
        VortexLook.Style look = look();
        if (!look.strands() || shape != Vortex.Shape.FUNNEL) {   // the strands are a funnel's; a drain or a spiral keeps its own
            return;
        }
        Vec3 f = foot(i);
        int strands = 3 + scale.ordinal();
        // A light stream of puffs only: dense dust specks read as confetti (the prototypes, 2026-11-15).
        int per = 2 + scale.ordinal();
        ParticleOptions wisp = family == VortexLook.Family.WIND || family == VortexLook.Family.CLOUD
                ? ParticleTypes.CLOUD : dust();
        for (int s = 0; s < strands; s++) {
            for (int k = 0; k < per; k++) {
                double h = size.height() * ((k + random.nextDouble()) / per);
                double r = Vortex.funnelRadius(size, h) * (0.85 + 0.25 * random.nextDouble());
                double spin = VortexLook.SPIN * (1.3 - 0.6 * h / size.height());
                double a = 2 * Math.PI * s / strands + spin * age + h * 0.35;
                double[] bend = VortexLook.sway(shape, scale, h, age, swayPhase[i]);
                double x = f.x + bend[0] + Math.cos(a) * r, y = f.y + h, z = f.z + bend[1] + Math.sin(a) * r;
                double v = spin * r;   // the wall's own speed, blocks a tick
                double vx = -Math.sin(a) * v - Math.cos(a) * 0.04, vz = Math.cos(a) * v - Math.sin(a) * 0.04;
                // Per player: Bedrock draws a cloud puff several times bigger and longer-lived than Java, so the same
                // stream turned a medium funnel into a white-out that hid the cats in it (filmed on the phone,
                // 2026-11-15). A Bedrock player gets every third puff.
                boolean bedrockToo = (k + s + age) % 3 == 0;
                for (ServerPlayer p : level.players()) {
                    if (bedrockToo || !com.moddingfromamod.net.PlayerClient.isBedrock(p)) {
                        level.sendParticles(p, wisp, true, true, x, y, z, 0, vx, 0.06, vz, 0.6);
                    }
                }
            }
        }
    }

    private void effects(ServerLevel level, int i, int age) {
        Vec3 f = foot(i);
        strands(level, i, age);
        ParticleOptions dust = dust();
        if (age % 2 == 0) {
            switch (shape) {
                case FUNNEL -> {
                    double h = random.nextDouble() * size.height();
                    double r = Vortex.funnelRadius(size, h);
                    double a = random.nextDouble() * Math.PI * 2;
                    level.sendParticles(dust, true, true, f.x + Math.cos(a) * r, f.y + h, f.z + Math.sin(a) * r,
                            4 + scale.ordinal() * 2, r * 0.2, 1, r * 0.2, 0.05);
                    // The dust kicked up at its foot.
                    level.sendParticles(ParticleTypes.CLOUD, true, true, f.x, f.y + 0.3, f.z, 3 + scale.ordinal() * 2,
                            size.foot(), 0.2, size.foot(), 0.08);
                }
                case DRAIN -> {
                    double a = random.nextDouble() * Math.PI * 2;
                    double r = size.top() * random.nextDouble();
                    level.sendParticles(ParticleTypes.SPLASH, true, true, f.x + Math.cos(a) * r, f.y + 0.1,
                            f.z + Math.sin(a) * r, 8 + scale.ordinal() * 4, 0.6, 0.1, 0.6, 0.2);
                    level.sendParticles(ParticleTypes.BUBBLE, true, true, f.x, f.y - Vortex.drainDepth(size) / 2, f.z,
                            6, size.top() * 0.3, 1, size.top() * 0.3, 0.1);
                    if (family != VortexLook.Family.WATER) {
                        level.sendParticles(dust, true, true, f.x + Math.cos(a) * r, f.y + 0.2, f.z + Math.sin(a) * r,
                                4, 0.5, 0.1, 0.5, 0.05);
                    }
                }
                case SPIRAL -> {
                    double reach = Vortex.spiralRadius(scale);
                    double alt = Vortex.spiralAltitude(scale);
                    for (int k = 0; k < 4 + scale.ordinal() * 2; k++) {
                        double a = random.nextDouble() * Math.PI * 2;
                        double r = Vortex.spiralEye(scale) + random.nextDouble() * (reach - Vortex.spiralEye(scale));
                        level.sendParticles(ParticleTypes.FALLING_WATER, true, true, f.x + Math.cos(a) * r,
                                f.y + alt - 1, f.z + Math.sin(a) * r, 6, 1.5, 0.5, 1.5, 0);
                    }
                    double a = random.nextDouble() * Math.PI * 2;
                    double e = Vortex.spiralEye(scale) * 1.1;
                    level.sendParticles(ParticleTypes.CLOUD, true, true, f.x + Math.cos(a) * e, f.y + 1, f.z + Math.sin(a) * e,
                            6, 1, 1, 1, 0.12);
                }
            }
        }
        int every = shape == Vortex.Shape.SPIRAL ? 40 : 30;
        if (age % every == i * 7) {
            level.playSound(null, f.x, f.y + 2, f.z, sound(), SoundSource.WEATHER,
                    1.5f + scale.ordinal(), size.spinPitch() * (0.9f + random.nextFloat() * 0.2f));
        }
        if (shape == Vortex.Shape.SPIRAL && age % 35 == 17) {
            // Lightning along the eye wall: visual only, so no fire and no hurt (EVENTS_PLAN §16.2).
            LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level, EntitySpawnReason.EVENT);
            if (bolt != null) {
                double a = random.nextDouble() * Math.PI * 2;
                double r = Vortex.spiralEye(scale) * (1.2 + random.nextDouble());
                int x = (int) Math.floor(f.x + Math.cos(a) * r), z = (int) Math.floor(f.z + Math.sin(a) * r);
                bolt.setVisualOnly(true);
                bolt.snapTo(x + 0.5, level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z),
                        z + 0.5, 0, 0);
                level.addFreshEntity(bolt);
            }
        }
        if (family == VortexLook.Family.FIRE && age % 20 == 0) {
            level.playSound(null, f.x, f.y + 2, f.z, SoundEvents.FIRE_AMBIENT, SoundSource.BLOCKS, 2f, 0.8f);
        }
    }

    // ── the end ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Undone mid-spin (the vortex leak): a hurricane gives every player it rained on his own sky back — the director restores
     * the blocks and removes the displays, but the rain was SENT, so nothing else would ever stop it.
     */
    @Override
    public void stopped(ServerLevel level) {
        if (ended) {
            return;
        }
        ended = true;
        if (shape == Vortex.Shape.SPIRAL) {
            localWeather(level, true);
        }
        walls.forEach(d -> d.display().discard());
        walls.clear();
        debris.forEach(b -> b.display.discard());
        debris.clear();
    }

    private void end(EventWriter w) {
        ended = true;
        if (shape == Vortex.Shape.SPIRAL) {
            localWeather(w.level(), true);
        }
        for (Drawn d : walls) {
            d.display().discard();
        }
        walls.clear();
        for (Debris b : debris) {
            b.display.discard();
        }
        debris.clear();
        if (giveBackTrack) {
            for (BlockPos p : torn) {
                w.release(p);
            }
        }
        for (int i = 0; i < feet.length; i++) {
            Vec3 f = foot(i);
            w.level().sendParticles(ParticleTypes.CLOUD, true, true, f.x, f.y + 2, f.z, 30, 2, 3, 2, 0.15);
        }
    }
}
