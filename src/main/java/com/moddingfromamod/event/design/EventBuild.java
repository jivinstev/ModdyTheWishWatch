package com.moddingfromamod.event.design;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.moddingfromamod.event.EventAnnounce;
import com.moddingfromamod.event.EventDirector;
import com.moddingfromamod.event.EventGuard;
import com.moddingfromamod.event.EventMotion;
import com.moddingfromamod.event.catching.BlockHits;
import com.moddingfromamod.event.catching.Catch;
import com.moddingfromamod.event.catching.CatchingMotion;
import com.moddingfromamod.event.catching.Effect;
import com.moddingfromamod.event.catching.Effects;
import com.moddingfromamod.event.motion.CountdownMotion;
import com.moddingfromamod.event.motion.CrackMotion;
import com.moddingfromamod.event.motion.LayerMotion;
import com.moddingfromamod.event.motion.MovingFrontMotion;
import com.moddingfromamod.event.motion.SkyDropMotion;
import com.moddingfromamod.event.motion.SlideMotion;
import com.moddingfromamod.event.motion.StampMotion;
import com.moddingfromamod.event.shape.Countdown;
import com.moddingfromamod.event.shape.CrackShape;
import com.moddingfromamod.event.shape.FallShape;
import com.moddingfromamod.event.shape.FrontShape;
import com.moddingfromamod.event.shape.LayerShape;
import com.moddingfromamod.event.shape.SkyDrop;
import com.moddingfromamod.event.shape.SlideShape;
import com.moddingfromamod.event.shape.StampShape;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * <b>A design, played in the world</b> (E4): resolves its ids, sizes its shape from the word
 * it was given, wraps it in what it catches, and stages it with the director. When it ends, its
 * {@code then} is chosen from the spot it ended at (water near → one design, a hill near → another)
 * and staged in its turn; a countdown's {@code at_zero} the same way.
 *
 * <p>Nothing here is saved: the design is. A follow-on is found by NAME in the world's designs at
 * the moment it is due, so a design edited in between is the one that happens.
 */
public final class EventBuild {

    private EventBuild() {
    }

    /** How deep a chain of follow-ons may go, so two designs naming each other cannot loop forever. */
    public static final int MAX_CHAIN = 4;
    /** How far ahead of him an event he did not aim is put down. */
    public static final int AHEAD = 14;
    /** How far to look for water or a hill when choosing what follows. */
    public static final int LOOK = 16;

    private static final AtomicLong SEQ = new AtomicLong();

    public record Result(String id, String error) {
        public boolean ok() {
            return id != null;
        }

        static Result no(String why) {
            return new Result(null, why);
        }
    }

    /** Stage {@code d} a little ahead of {@code p}, heading the way he faces. */
    /** does this design spawn living creatures (a crowd or a shower of a mob)? */
    public static boolean bringsCreatures(EventDesign d) {
        if (d == null || d.drop() == null || d.drop().isEmpty()) {
            return false;
        }
        return (d.motion() == EventDesign.Motion.CROWD || d.motion() == EventDesign.Motion.FROM_THE_SKY)
                && crowdEntity(d.drop()) != null;
    }

    public static Result nearPlayer(ServerPlayer p, EventDesign d, String owner) {
        ServerLevel level = p.level();
        float yaw = p.getYRot();
        double r = Math.toRadians(yaw);
        int ahead = d.motion() == EventDesign.Motion.FALLING_BODY ? fallAim(d.scale())
                : d.motion() == EventDesign.Motion.VORTEX ? com.moddingfromamod.event.shape.Vortex.aim(d.vortexShape(), d.scale())
                : AHEAD;
        int x = (int) Math.floor(p.getX() - Math.sin(r) * ahead);
        int z = (int) Math.floor(p.getZ() + Math.cos(r) * ahead);
        if (d.motion() == EventDesign.Motion.MOVING_FRONT) {
            // A wave is born AHEAD of him, where he is looking, as far out as it travels before it
            // breaks, and rolls back TOWARD him: he watches it rear up and come in, and it crashes
            // over him. The first cut started it four blocks behind him heading away, and the film
            // showed what that is: water he never saw coming, then a wave rolling off into the distance.
            int out = DesignSizes.wave(d.scale(), d.breaks()).breaksAt();
            x = (int) Math.floor(p.getX() - Math.sin(r) * out);
            z = (int) Math.floor(p.getZ() + Math.cos(r) * out);
            yaw = yaw + 180f;
        }
        BlockPos aimed = ground(level, x, z);
        if (d.motion() == EventDesign.Motion.VORTEX && d.vortexShape() == com.moddingfromamod.event.shape.Vortex.Shape.DRAIN
                && vortexFamily(d) == com.moddingfromamod.event.shape.VortexLook.Family.WATER) {
            // A whirlpool he did not aim goes in the nearest water, if there is some in sight.
            BlockPos wet = nearestWater(level, aimed);
            if (wet != null) {
                aimed = wet;
            }
        }
        return stage(level, d, aimed, yaw, p, owner, 0);
    }

    /** The nearest surface water within {@link #LOOK} of {@code at}, or null. */
    static BlockPos nearestWater(ServerLevel level, BlockPos at) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -LOOK; dx <= LOOK; dx++) {
            for (int dz = -LOOK; dz <= LOOK; dz++) {
                int x = at.getX() + dx, z = at.getZ() + dz;
                if (!level.hasChunkAt(new BlockPos(x, 0, z))) {
                    continue;
                }
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
                if (level.getFluidState(new BlockPos(x, y, z)).is(net.minecraft.tags.FluidTags.WATER)) {
                    double dd = dx * dx + dz * dz;
                    if (dd < bestD) {
                        bestD = dd;
                        best = new BlockPos(x, y, z);
                    }
                }
            }
        }
        return best;
    }

    /** The material family a vortex is drawn in: its material's, else its shape's own (EVENTS_PLAN §16.4). */
    public static com.moddingfromamod.event.shape.VortexLook.Family vortexFamily(EventDesign d) {
        return d.material() != null ? com.moddingfromamod.event.shape.VortexLook.Family.of(d.material())
                : com.moddingfromamod.event.shape.VortexLook.defaultFamily(d.vortexShape());
    }

    /** The seed a vortex's path walks by: the same design at the same spot walks the same way (camera = motion). */
    static long vortexSeed(EventDesign d, BlockPos centre) {
        return d.name().hashCode() * 0x9E3779B97F4A7C15L ^ centre.asLong();
    }

    /** The paths of each of a vortex's funnels (1-3), side by side across his view. */
    static List<com.moddingfromamod.event.shape.VortexPath> vortexPaths(EventDesign d, BlockPos centre, float yaw,
                                                                        double[] around) {
        var shape = d.vortexShape();
        var z = com.moddingfromamod.event.shape.Vortex.size(d.scale());
        var path = d.vortexPath();
        int n = com.moddingfromamod.event.shape.Vortex.funnels(d.count());
        double r = Math.toRadians(yaw);
        double hx = -Math.sin(r), hz = Math.cos(r);
        double speed = path == com.moddingfromamod.event.shape.Vortex.Path.WANDER ? z.speed() * 0.7 : z.speed();
        double ring = com.moddingfromamod.event.shape.Vortex.circleRadius(d.scale());
        if (around != null) {
            // Round a building: just outside its widest wall.
            ring = Math.max(ring, Math.hypot(around[3] - around[0], around[5] - around[2]) / 2 + z.top() * 0.6 + 3);
        }
        double spacing = (shape == com.moddingfromamod.event.shape.Vortex.Shape.SPIRAL
                ? com.moddingfromamod.event.shape.Vortex.spiralRadius(d.scale()) : z.top()) * 2.5;
        List<com.moddingfromamod.event.shape.VortexPath> out = new ArrayList<>();
        long seed = vortexSeed(d, centre);
        for (int i = 0; i < n; i++) {
            double off = (i - (n - 1) / 2.0) * spacing;
            // Side by side along his look: one nearer, one further, so all of them are in the shot.
            var origin = new com.moddingfromamod.event.shape.VortexPath.At(centre.getX() + 0.5 + hx * off,
                    centre.getZ() + 0.5 + hz * off);
            if (path == com.moddingfromamod.event.shape.Vortex.Path.CIRCLE && around == null && n > 1) {
                origin = new com.moddingfromamod.event.shape.VortexPath.At(centre.getX() + 0.5, centre.getZ() + 0.5);
            }
            // A wander dances about the spot, never off to run him over (the first film: it came at the camera).
            out.add(new com.moddingfromamod.event.shape.VortexPath(path, origin, hx, hz, speed,
                    ring, com.moddingfromamod.event.shape.Vortex.circleRadius(d.scale()) * 0.6, seed + i * 7919L));
        }
        return out;
    }

    /**
     * How far ahead a falling body he did not aim lands: close enough to look into what it leaves, and
     * far enough that a bigger one's crater does not reach his feet.
     */
    public static int fallAim(com.moddingfromamod.event.EventScale s) {
        int grow = DesignSizes.stampRadius(s) - DesignSizes.stampRadius(com.moddingfromamod.event.EventScale.MEDIUM);
        return FallShape.MIN_AIM + (int) Math.ceil(Math.max(0, grow) * (1 + StampShape.RIM_REACH));
    }

    /** The top block of the ground at a column. */
    public static BlockPos ground(ServerLevel level, int x, int z) {
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z);
    }

    /**
     * Stage a design at {@code centre} (the top ground block), heading {@code yaw}, watched by
     * {@code viewer} (may be null). {@code depth} counts follow-ons already chained.
     */
    public static Result stage(ServerLevel level, EventDesign d, BlockPos aimed, float yaw,
                               ServerPlayer viewer, String owner, int depth) {
        // a crowd "around me" stands round HIM, wherever the wish was aimed.
        final BlockPos centre = d.motion() == EventDesign.Motion.CROWD && d.pattern() == EventDesign.Crowd.AROUND_ME
                && viewer != null && viewer.level() == level
                ? ground(level, viewer.getBlockX(), viewer.getBlockZ()) : aimed;
        if (depth > MAX_CHAIN) {
            return Result.no("too many follow-ons in a row (" + MAX_CHAIN + " at most)");
        }
        if (!level.hasChunkAt(centre)) {
            return Result.no("that spot is not loaded");
        }
        List<String> err = new ArrayList<>();
        BlockState material = block(d.material(), err, "material");
        BlockState core = block(d.core(), err, "core");
        BlockState rim = block(d.rim(), err, "rim");
        long seed = level.getRandom().nextLong();
        String id = d.name() + "_" + SEQ.incrementAndGet() + "_" + (level.getGameTime() % 100000);
        BlockPos[] spot = {centre};
        EventMotion motion;
        EventGuard.Policy policy = EventGuard.Policy.INTO_OPEN;
        switch (d.motion()) {
            case MOVING_FRONT -> {
                MovingFrontMotion front = new MovingFrontMotion(DesignSizes.wave(d.scale(), d.breaks()),
                        material, centre, yaw).wrecking(d.wrecksBuilds());
                int life = DesignSizes.wave(d.scale(), d.breaks()).lifeTicks();
                BlockPos end = front.frontAt(life);
                spot[0] = ground(level, end.getX(), end.getZ());
                motion = front;
            }
            case LAYER -> motion = new LayerMotion(
                    LayerShape.fit(DesignSizes.floodDepth(d.scale()), d.scale().radius, d.scale().totalBlocks),
                    material, centre);
            case IMPACT_STAMP -> {
                policy = EventGuard.digging(d.restore());
                motion = new StampMotion(new StampShape(d.form(), DesignSizes.stampRadius(d.scale())),
                        new StampMotion.Palette(material, core != null ? core : material, rim != null ? rim : material),
                        centre, 20);
            }
            case FALLING_BODY -> {
                FallShape.Point target = new FallShape.Point(centre.getX() + 0.5, centre.getY() + 1, centre.getZ() + 0.5);
                FallShape.Point from = viewer != null
                        ? new FallShape.Point(viewer.getX(), viewer.getEyeY(), viewer.getZ())
                        : new FallShape.Point(centre.getX() + 0.5, centre.getY() + 2, centre.getZ() + FallShape.MIN_AIM);
                int radius = DesignSizes.bodyRadius(d.scale());
                BlockState lump = core != null ? core : net.minecraft.world.level.block.Blocks.BLACKSTONE.defaultBlockState();
                motion = new com.moddingfromamod.event.motion.DisplayBodyMotion(
                        BodyLooks.rock(material, lump, radius), radius, 0.12f, from, target);
            }
            case FROM_THE_SKY -> {
                SkyDrop shape = new SkyDrop(Math.min(d.scale().radius, DesignSizes.skyRadius(d.scale())),
                        DesignSizes.skyPerTick(d.scale()), DesignSizes.SKY_TICKS);
                EntityType<?> type = entity(d.drop());
                BlockState fall = type == null ? block(d.drop(), null, "drop") : null;
                if (type == null && fall == null) {
                    err.add("drop: " + d.drop() + " is neither a creature nor a block");
                    motion = null;
                } else {
                    motion = type != null ? SkyDropMotion.entities(shape, centre, seed, type, skyCount(type, d.scale()))
                            : SkyDropMotion.settling(shape, centre, seed, fall);
                }
            }
            case SPREADING_CRACKS -> {
                policy = EventGuard.digging(d.restore());
                motion = new CrackMotion(new CrackShape(DesignSizes.cracks(d.scale()),
                        DesignSizes.crackLength(d.scale()), DesignSizes.crackDepth(d.scale())), seed, centre, 2);
            }
            case SLIDE -> {
                policy = EventGuard.digging(d.restore());
                motion = new SlideMotion(centre);
            }
            case COUNTDOWN -> {
                String title = d.title().isEmpty() ? "Get out!" : d.title();
                motion = new CountdownMotion(new Countdown(d.seconds()),
                        left -> tellNear(level, centre, title, left + "", left <= 10),
                        () -> follow(level, d.atZero(), centre, yaw, viewer, owner, depth));
            }
            case NONE -> {
                int life = 20 * Math.max(5, Math.min(60, d.seconds()));
                motion = (w, age) -> age >= life;
            }
            case CROWD -> {
                //. A creature (a mob, a villager) wins over an item of the same name; an id that is
                // only a thing (an egg, a snowball) is the ITEM lying there, not the projectile.
                EntityType<?> type = crowdEntity(d.drop());
                net.minecraft.world.item.Item item = type == null ? crowdItem(d.drop()) : null;
                if (d.drop().startsWith(com.moddingfromamod.ModdingFromAModMod.MODID + ":")) {
                    // A wished creature is a rig, not a bare entity: spawned here it would arrive
                    // invisible. Many of HIS creature is the creature tool's own count.
                    err.add("drop: " + d.drop() + " is a wished creature — make several of it with "
                            + "define_creature's count, not a crowd");
                    motion = null;
                } else if (type == null && item == null) {
                    err.add("drop: " + d.drop() + " is neither a creature nor an item");
                    motion = null;
                } else {
                    motion = new com.moddingfromamod.event.motion.CrowdMotion(centre, type,
                            item == null ? null : new net.minecraft.world.item.ItemStack(item),
                            crowdCount(d, type), d.pattern() != EventDesign.Crowd.CLUSTER, d.baby());
                }
            }
            case TRANSFORM -> {
                //. "The ground" changes only NATURAL blocks: a column's top block is his roof
                // where his house stands, and "turn the ground into gold" is not a gold roof. A
                // block or tag he NAMED changes wherever it is (a staying event's rule), journaled,
                // so undo rebuilds it exactly.
                java.util.function.Predicate<BlockState> what = replaces(d.replaces(), err);
                policy = what == null ? EventGuard.Policy.TERRAIN : EventGuard.digging(d.restore());
                motion = material == null ? null
                        : new com.moddingfromamod.event.motion.TransformMotion(centre, d.scale().radius, what,
                                material, DesignSizes.transformTicks(d.scale()));
            }
            case VORTEX -> {
                // Vortexes (EVENTS_PLAN §16): a tornado, a whirlpool, a hurricane.
                var fam = vortexFamily(d);
                // A display cannot draw a fluid: "a water tornado" is drawn in its family's glass (EVENTS_PLAN §16.4).
                boolean fluid = material != null && !material.getFluidState().isEmpty();
                BlockState wall = material != null && !fluid ? material : block(fam.material, err, "material");
                BlockState wall2 = core != null && core.getFluidState().isEmpty() ? core
                        : fam.core != null ? block(fam.core, err, "core") : wall;
                EntityType<?> riders = null;
                BlockState riderBlock = null;
                if (d.drop() != null) {
                    riders = crowdEntity(d.drop());
                    if (riders == null) {
                        riderBlock = block(d.drop(), null, "drop");
                        if (riderBlock == null) {
                            err.add("drop: " + d.drop() + " is neither a creature nor a block");
                        }
                    } else if (d.drop().startsWith(com.moddingfromamod.ModdingFromAModMod.MODID + ":")) {
                        err.add("drop: " + d.drop() + " is a wished creature — make it with define_creature, "
                                + "then a vortex near it lifts it");
                        riders = null;
                    }
                }
                String who = viewer != null ? viewer.getGameProfile().getName() : owner;
                java.util.function.Supplier<ServerPlayer> him = () -> who == null ? null
                        : level.getServer().getPlayerList().getPlayerByName(who);
                java.util.Set<String> cams = new java.util.HashSet<>(com.moddingfromamod.wish.CameraPool.names(
                        com.moddingfromamod.runtime.RuntimeConfig.cameraName(), com.moddingfromamod.wish.CameraPool.MAX));
                cams.remove("");
                double[] around = subjectBox;
                motion = wall == null ? null : new com.moddingfromamod.event.motion.VortexMotion(d.vortexShape(), d.scale(),
                        wall, wall2, vortexPaths(d, centre, yaw, around),
                        com.moddingfromamod.event.shape.Vortex.life(d.scale(), d.seconds()), seed, riders,
                        riders == null ? 0 : skyCount(riders, d.scale()), riderBlock,
                        him, !d.restore() && !d.wrecksBuilds(), d.wrecksBuilds(), cams)
                        .style(d.look());
            }
            default -> motion = null;
        }
        if (motion != null && d.seconds() > 0 && d.motion() != EventDesign.Motion.COUNTDOWN
                && d.motion() != EventDesign.Motion.NONE && d.motion() != EventDesign.Motion.VORTEX) {
            motion = atLeast(motion, 20 * d.seconds());
        }
        if (motion == null || !err.isEmpty()) {
            return Result.no(err.isEmpty() ? "nothing to stage" : String.join("; ", err));
        }
        CatchingMotion caught = new CatchingMotion(motion);
        for (EventDesign.CatchSpec c : d.catches()) {
            java.util.function.IntToDoubleFunction strength = age -> 1.0;
            if (motion instanceof MovingFrontMotion f) {
                strength = f::strength;
            }
            Catch where = motion instanceof com.moddingfromamod.event.motion.VortexMotion vm
                    ? vortexCatch(vm, c)
                    : c.where() == EventDesign.Where.INSIDE
                    ? Catch.inside(d.motion() == EventDesign.Motion.MOVING_FRONT ? yaw : Float.NaN, strength)
                    : Catch.near(centre.above(), c.reach());
            if (c.from() > 0 || c.to() != Integer.MAX_VALUE) {
                where = where.between(c.from(), c.to());
            }
            if (c.every() > 1) {
                where = where.every(c.every());
            }
            List<Effect> does = new ArrayList<>();
            for (EventDesign.EffectSpec e : c.does()) {
                Effect ef = switch (e.does()) {
                    case CARRY -> Effects.carry();
                    case LIFT -> motion instanceof MovingFrontMotion f ? Effects.lift(f::slamming) : Effects.lift();
                    case SHAKE -> Effects.shake();
                    case IGNITE -> Effects.ignite();
                    case HURT -> Effects.hurt(e.amount());
                    case STATUS -> Effects.status(e.status(), e.amount());
                };
                if (ef == null) {
                    return Result.no("status: " + e.status() + " is not one of the kind effects, which are "
                            + String.join(", ", com.moddingfromamod.datapack.StatusEffect.EFFECTS.keySet()));
                }
                does.add(ef);
            }
            caught.on(where, does.toArray(Effect[]::new));
        }
        if (d.hurtsCreatures()) {
            // A wrecking wave or vortex batters what it catches, once a second (Effects.batter).
            float hit = switch (d.scale()) {
                case LITTLE -> 2f;
                case MEDIUM -> 3f;
                case BIG -> 4f;
                case GIGANTIC -> 6f;
            };
            Catch caughtBy = motion instanceof com.moddingfromamod.event.motion.VortexMotion vm
                    ? (w, age, out) -> vm.caughtNow().forEach(e -> out.accept(new com.moddingfromamod.event.catching.Caught(e, 1.0, 0, 0)))
                    : Catch.inside(Float.NaN, age -> 1.0);
            caught.on(caughtBy.every(20), Effects.batter(hit));
        }
        if (motion instanceof com.moddingfromamod.event.motion.VortexMotion vm && d.catches().isEmpty()
                && vortexFamily(d) == com.moddingfromamod.event.shape.VortexLook.Family.FIRE) {
            caught.on((w, age, out) -> vm.caughtNow().forEach(e -> out.accept(
                    new com.moddingfromamod.event.catching.Caught(e, 1.0, 0, 0))), Effects.ignite());   // a fire whirl burns
        }
        java.util.function.IntFunction<BlockPos> hitsAt = motion instanceof com.moddingfromamod.event.motion.VortexMotion vm
                ? age -> vm.footBlock(0) : age -> centre;
        for (EventDesign.BlockSpec b : d.blocks()) {
            int reach = Math.min(b.reach(), d.scale().radius);
            caught.blocks(b.hit() == EventDesign.Hit.FIRE
                    ? BlockHits.lightFires(hitsAt, reach, b.perTick(), b.strength())
                    : BlockHits.breakFragile(hitsAt, reach, b.perTick(), b.strength()));
        }
        EventMotion played = caught;
        if (!d.then().isEmpty()) {
            EventMotion inner = caught;
            played = new EventMotion() {
                @Override
                public boolean step(com.moddingfromamod.event.EventWriter w, int age) {
                    boolean done = inner.step(w, age);
                    if (done) {
                        boolean water = waterNear(level, spot[0]);
                        boolean slope = slopeNear(level, spot[0]);
                        String next = d.follow(water, slope);
                        if (next != null) {
                            follow(level, next, spot[0], yaw, viewer, owner, depth);
                        }
                    }
                    return done;
                }

                @Override
                public void stopped(ServerLevel l) {
                    inner.stopped(l);
                }
            };
        }
        var staged = EventDirector.start(level, id, owner, d.scale(), policy, d.restore(), played);
        staged.traits(d.traits());
        staged.focus(centre);
        com.moddingfromamod.runtime.EventReach planned = reach(d, centre, yaw, viewer);
        if (subjectBox != null && depth == 0) {
            // aimed AT a building — the camera keeps the building in the shot as well.
            planned = planned.including(subjectBox[0], subjectBox[1], subjectBox[2], subjectBox[3],
                    subjectBox[4], subjectBox[5]);
        }
        staged.planned(planned);
        if (cueUntilMs > 0) {
            staged.holdUntil(cueUntilMs);   // the camera is coming: its first tick waits for it
        }
        if (!d.title().isEmpty() && d.motion() != EventDesign.Motion.COUNTDOWN) {
            tellNear(level, centre, d.title(), null, true);
        }
        return new Result(id, null);
    }

    /**
     * A design's own {@code catch} on a vortex: {@code inside} is whatever the vortex caught this tick; {@code near}
     * is within {@code reach} of its foot WHERE IT IS NOW, never where it started.
     */
    static Catch vortexCatch(com.moddingfromamod.event.motion.VortexMotion vm, EventDesign.CatchSpec c) {
        if (c.where() == EventDesign.Where.INSIDE) {
            return (w, age, out) -> vm.caughtNow().forEach(e -> out.accept(
                    new com.moddingfromamod.event.catching.Caught(e, 1.0, 0, 0)));
        }
        return (w, age, out) -> {
            for (int i = 0; i < vm.funnels(); i++) {
                Catch.near(vm.footBlock(i), c.reach()).find(w, age, out);
            }
        };
    }

    /**
     * Held start for the NEXT stage on this thread: set by {@link #withCue} round the one
     * call that stages a filmed event, so a follow-on staged later never inherits it. Server thread.
     */
    private static long cueUntilMs;

    /**
     * The world box of the thing this stage is aimed AT, for the camera: set by
     * {@link #aimedAt} round the one call that stages it, never inherited by a follow-on. Server thread.
     */
    private static double[] subjectBox;

    /** Stage through {@code body} with the camera told to keep {@code box} in the shot as well. */
    public static <T> T aimedAt(double[] box, java.util.function.Supplier<T> body) {
        subjectBox = box;
        try {
            return body.get();
        } finally {
            subjectBox = null;
        }
    }

    /** Stage through {@code body} with the event's first tick held until {@code untilMs} at the latest. */
    public static <T> T withCue(long untilMs, java.util.function.Supplier<T> body) {
        cueUntilMs = untilMs;
        try {
            return body.get();
        } finally {
            cueUntilMs = 0;
        }
    }

    /**
     * <b>Where this event's action goes over the camera's clip</b>, from the same sizes the
     * motion is built from — so the framer fits the wave's path, the shower's fall and the meteor's
     * approach rather than the spot they started from.
     */
    static com.moddingfromamod.runtime.EventReach reach(EventDesign d, BlockPos centre, float yaw,
                                                        ServerPlayer viewer) {
        // ⚠ EXHAUSTIVE ON PURPOSE, NO DEFAULT: a new motion does not compile until somebody decides
        // where the camera looks for it. Changing how a motion MOVES (its speed, height, path, where
        // it is born) changes its reach too — update the case here and re-shoot it in the studio:
        // docs/EVENTS_PLAN.md, "Changing an event? Change its camera too".
        double cx = centre.getX() + 0.5, cz = centre.getZ() + 0.5, gy = centre.getY() + 1;
        int radius = d.scale().radius;
        return switch (d.motion()) {
            case MOVING_FRONT -> {
                FrontShape w = DesignSizes.wave(d.scale(), d.breaks());
                double r = Math.toRadians(yaw);
                yield com.moddingfromamod.runtime.EventReach.front(cx, gy, cz, -Math.sin(r), Math.cos(r),
                        FrontShape.SPEED, w.lifeTicks(), w.width / 2.0, w.height);   // its body, not the wash
            }
            case FROM_THE_SKY -> com.moddingfromamod.runtime.EventReach.sky(cx, gy, cz,
                    Math.min(radius, DesignSizes.skyRadius(d.scale())), SkyDrop.HEIGHT);
            case FALLING_BODY -> {
                FallShape.Point target = new FallShape.Point(cx, gy, cz);
                FallShape.Point from = viewer != null
                        ? new FallShape.Point(viewer.getX(), viewer.getEyeY(), viewer.getZ())
                        : new FallShape.Point(cx, gy + 1, cz + FallShape.MIN_AIM);
                FallShape.Point start = FallShape.start(from, target);
                double crater = DesignSizes.stampRadius(d.scale()) * (1 + StampShape.RIM_REACH);
                yield com.moddingfromamod.runtime.EventReach.fall(start.x(), start.y(), start.z(),
                        cx, gy, cz, crater);
            }
            case IMPACT_STAMP -> {
                double r = DesignSizes.stampRadius(d.scale()) * (1 + StampShape.RIM_REACH);
                yield com.moddingfromamod.runtime.EventReach.around(cx, gy - 2, cz, r, Math.max(3, r / 2));
            }
            case LAYER -> com.moddingfromamod.runtime.EventReach.around(cx, gy, cz, radius * 0.5,
                    DesignSizes.floodDepth(d.scale()) + 2);
            case SPREADING_CRACKS -> com.moddingfromamod.runtime.EventReach.around(cx, gy - 2, cz,
                    Math.min(radius, DesignSizes.crackLength(d.scale())), DesignSizes.crackDepth(d.scale()) + 2);
            case CROWD -> {
                EntityType<?> type = crowdEntity(d.drop());
                yield com.moddingfromamod.runtime.EventReach.crowd(cx, gy, cz,
                        com.moddingfromamod.event.shape.CrowdShape.radius(crowdCount(d, type),
                                d.pattern() != EventDesign.Crowd.CLUSTER));
            }
            case TRANSFORM -> com.moddingfromamod.runtime.EventReach.transform(cx, gy, cz, radius,
                    d.replaces() != null && !"ground".equals(d.replaces()));
            case SLIDE, COUNTDOWN, NONE -> com.moddingfromamod.runtime.EventReach.around(cx, gy, cz,
                    radius * 0.5, Math.max(3, radius * 0.25));
            case VORTEX -> vortexReach(d, centre, yaw, viewer, subjectBox);
        };
    }

    /**
     * Where a vortex goes over the clip, from the SAME paths the motion walks (seeded by the design and the
     * spot). A line is a tracked front, its top radius each side; anything else is the box its feet cover during the
     * clip, grown by how far it reaches and as tall as it stands; a follower is held round him.
     */
    static com.moddingfromamod.runtime.EventReach vortexReach(EventDesign d, BlockPos centre, float yaw,
                                                             ServerPlayer viewer, double[] around) {
        var shape = d.vortexShape();
        // Its reach, plus how far its top sways (VortexLook.sway): the bent top is still in the shot.
        double reachR = com.moddingfromamod.event.shape.Vortex.reach(shape, d.scale())
                + (shape == com.moddingfromamod.event.shape.Vortex.Shape.FUNNEL
                ? com.moddingfromamod.event.shape.Vortex.size(d.scale()).top() * 0.45 : 0);
        double tall = com.moddingfromamod.event.shape.Vortex.tall(shape, d.scale());
        double gy = centre.getY() + 1;
        double floor = shape == com.moddingfromamod.event.shape.Vortex.Shape.DRAIN
                ? gy - com.moddingfromamod.event.shape.Vortex.drainDepth(com.moddingfromamod.event.shape.Vortex.size(d.scale()))
                : gy;
        int life = com.moddingfromamod.event.shape.Vortex.life(d.scale(), d.seconds());
        var paths = vortexPaths(d, centre, yaw, around);
        var path = d.vortexPath();
        if (path == com.moddingfromamod.event.shape.Vortex.Path.LINE && paths.size() == 1) {
            var p = paths.get(0);
            var start = p.lineStart(life);
            double[] h = p.heading();
            return com.moddingfromamod.runtime.EventReach.front(start.x(), floor, start.z(), h[0], h[1], p.speed(), life,
                    reachR, tall + (gy - floor));
        }
        if (path == com.moddingfromamod.event.shape.Vortex.Path.FOLLOW && viewer != null) {
            double x0 = Math.min(viewer.getX(), centre.getX() + 0.5), x1 = Math.max(viewer.getX(), centre.getX() + 0.5);
            double z0 = Math.min(viewer.getZ(), centre.getZ() + 0.5), z1 = Math.max(viewer.getZ(), centre.getZ() + 0.5);
            return com.moddingfromamod.runtime.EventReach.box(x0 - reachR, floor, z0 - reachR, x1 + reachR, gy + tall,
                    z1 + reachR);
        }
        double x0 = Double.MAX_VALUE, z0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
        for (var p : paths) {
            double[] b = p.bounds(0, Math.min(life, com.moddingfromamod.runtime.EventReach.CLIP_TICKS), life);
            x0 = Math.min(x0, b[0]);
            z0 = Math.min(z0, b[1]);
            x1 = Math.max(x1, b[2]);
            z1 = Math.max(z1, b[3]);
        }
        return com.moddingfromamod.runtime.EventReach.box(x0 - reachR, floor, z0 - reachR, x1 + reachR, gy + tall,
                z1 + reachR);
    }

    /**
     * {@code replaces} as a block test: null for "the ground" (the transform then takes each column's
     * top natural block), a tag's members, or one block. An unknown block or tag is an error, never a
     * transform that silently changes nothing.
     */
    static java.util.function.Predicate<BlockState> replaces(String spec, List<String> err) {
        if (spec == null || spec.equals("ground")) {
            return null;
        }
        if (spec.startsWith("#")) {
            ResourceLocation id = ResourceLocation.tryParse(spec.substring(1));
            var key = id == null ? null
                    : net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK, id);
            // Membership is tested block by block: the registry's own tag lookup is spelled
            // differently on 1.21.1 and 1.21.4, and BlockState.is(TagKey) is not.
            boolean any = false;
            if (key != null) {
                for (net.minecraft.world.level.block.Block b : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
                    if (b.defaultBlockState().is(key)) {
                        any = true;
                        break;
                    }
                }
            }
            if (!any) {
                err.add("replaces: no block tag " + spec);
                return s -> false;
            }
            return s -> s.is(key);
        }
        BlockState one = block(spec, err, "replaces");
        if (one == null) {
            return s -> false;
        }
        net.minecraft.world.level.block.Block b = one.getBlock();
        return s -> s.is(b);
    }

    /** Stage the design called {@code name} at {@code at}, after this tick (the director is mid-loop). */
    private static void follow(ServerLevel level, String name, BlockPos at, float yaw, ServerPlayer viewer,
                               String owner, int depth) {
        EventDesign next = EventDesigns.get(level.getServer(), name);
        if (next == null) {
            return;
        }
        BlockPos g = ground(level, at.getX(), at.getZ());
        EventDirector.later(level.getServer(), () -> stage(level, next, g, yaw, viewer, owner, depth + 1));
    }

    /** Title every player within sight of the spot. */
    private static void tellNear(ServerLevel level, BlockPos at, String title, String line, boolean sound) {
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(at.getX(), at.getY(), at.getZ()) < 128 * 128) {
                EventAnnounce.tell(p, title, line, at, sound ? SoundEvents.NOTE_BLOCK_BELL.value() : null);
            }
        }
    }

    /** Any water at the surface within {@link #LOOK} of the spot. */
    /**
     * How many creatures a sky shower drops: its size's number, never more than one wish's wave of
     * that class — a shower of creepers is an explosive wave, not a 96-creeper one.
     */
    static int skyCount(EntityType<?> type, com.moddingfromamod.event.EventScale scale) {
        var kind = com.moddingfromamod.mob.SpawnBudget.classify(
                net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(type).toString(),
                type.getCategory() != net.minecraft.world.entity.MobCategory.MISC);
        int sized = DesignSizes.skyCreatures(scale);
        return kind == null || kind == com.moddingfromamod.mob.SpawnBudget.Kind.SCULPTED ? sized
                : Math.min(sized, com.moddingfromamod.mob.SpawnBudget.perWish(kind));
    }

    /**
     * What to tell him when a crowd came smaller than he asked (one wish's wave), or null.
     * Honest numbers, so "100 bats" does not get a reveal that says a hundred.
     */
    public static String crowdWave(EventDesign d) {
        if (d.motion() != EventDesign.Motion.CROWD || d.count() <= 0) {
            return null;
        }
        int came = crowdCount(d, crowdEntity(d.drop()));
        return came >= d.count() ? null : "Only " + came + " of the " + d.count() + " came: that is one "
                + "wish's wave of " + d.drop().replace("minecraft:", "") + ". Tell him the number, and that "
                + "wishing for more brings another wave.";
    }

    /** A crowd's creature, or null when the id is only an item (a projectile's name is the item's). */
    static EntityType<?> crowdEntity(String id) {
        EntityType<?> t = entity(id);
        if (t == null) {
            return null;
        }
        boolean thing = t.getCategory() == net.minecraft.world.entity.MobCategory.MISC;
        return thing && crowdItem(id) != null ? null : t;
    }

    static net.minecraft.world.item.Item crowdItem(String id) {
        if (id == null) {
            return null;
        }
        var item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(id)).orElse(null);
        return item == null || item == net.minecraft.world.item.Items.AIR ? null : item;
    }

    /**
     * How many a crowd brings: what he said, else the size's number, never past one wish's wave of
     * that class — a hundred bats is a hundred flyers, and flyers stop at their own number.
     */
    static int crowdCount(EventDesign d, EntityType<?> type) {
        int asked = d.count() > 0 ? d.count() : DesignSizes.crowd(d.scale());
        if (type == null) {
            return Math.min(asked, EventDesign.MAX_CROWD);
        }
        var kind = com.moddingfromamod.mob.SpawnBudget.classify(
                BuiltInRegistries.ENTITY_TYPE.getKey(type).toString(),
                type.getCategory() != net.minecraft.world.entity.MobCategory.MISC);
        return kind == null ? asked : Math.max(1, Math.min(asked, com.moddingfromamod.mob.SpawnBudget.perWish(kind)));
    }

    static boolean waterNear(ServerLevel level, BlockPos at) {
        for (int dx = -LOOK; dx <= LOOK; dx += 2) {
            for (int dz = -LOOK; dz <= LOOK; dz += 2) {
                int x = at.getX() + dx, z = at.getZ() + dz;
                if (!level.hasChunkAt(new BlockPos(x, 0, z))) {
                    continue;
                }
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
                if (level.getFluidState(new BlockPos(x, y, z)).is(net.minecraft.tags.FluidTags.WATER)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A drop of at least {@link SlideShape#MIN_DROP} within {@link #LOOK}: somewhere a slide can happen. */
    static boolean slopeNear(ServerLevel level, BlockPos at) {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int dx = -LOOK; dx <= LOOK; dx += 2) {
            for (int dz = -LOOK; dz <= LOOK; dz += 2) {
                int x = at.getX() + dx, z = at.getZ() + dz;
                if (!level.hasChunkAt(new BlockPos(x, 0, z))) {
                    continue;
                }
                int y = ground(level, x, z).getY();
                lo = Math.min(lo, y);
                hi = Math.max(hi, y);
            }
        }
        return hi != Integer.MIN_VALUE && hi - lo >= SlideShape.MIN_DROP;
    }

    private static BlockState block(String id, List<String> err, String field) {
        if (id == null) {
            return null;
        }
        Block b = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(id)).orElse(null);
        if (b == null || b == Blocks.AIR && !id.equals("minecraft:air")) {
            if (err != null) {
                err.add(field + ": no block called " + id);
            }
            return null;
        }
        return b.defaultBlockState();
    }

    private static EntityType<?> entity(String id) {
        if (id == null) {
            return null;
        }
        return BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.parse(id)).orElse(null);
    }

    /**
     * Keep an event going until {@code ticks} have passed even if its shape finished sooner: what it
     * catches (shaking, burning) keeps happening, and what it restores is given back after. The
     * inner motion is not stepped again once it has finished.
     */
    public static com.moddingfromamod.event.EventMotion atLeast(com.moddingfromamod.event.EventMotion inner, int ticks) {
        boolean[] done = {false};
        return new com.moddingfromamod.event.EventMotion() {
            @Override
            public boolean step(com.moddingfromamod.event.EventWriter w, int age) {
                if (!done[0]) {
                    done[0] = inner.step(w, age);
                }
                return done[0] && age >= ticks;
            }

            @Override
            public void stopped(ServerLevel l) {
                if (!done[0]) {
                    inner.stopped(l);
                }
            }
        };
    }
}
