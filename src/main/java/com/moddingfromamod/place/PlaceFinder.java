package com.moddingfromamod.place;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildRegistry;
import com.moddingfromamod.mob.Creatures;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Find the thing an {@code at} names, in the live world, and resolve its spot</b> —
 * the server half of {@link PlaceResolver}, and the only place any placing tool asks "where is
 * {@code castle:roof}".
 *
 * <p>The thing, in order:
 * <ol>
 *   <li>{@code me} → the child the wish is for;</li>
 *   <li>a building STANDING this run, by id — or his fork of it ({@code castle} finds
 *   {@code castle~1a2b3c4d}), or by its display name — read through {@link BuildRegistry}, so when
 * saves where buildings stand, every {@code at} survives a restart with no change here;</li>
 *   <li>a creature (his own first, then the nearest) or a real mob by its name, within
 *   {@link #CREATURE_REACH} of him.</li>
 * </ol>
 * A building that exists but is not standing is said as such (it is the commonest real failure:
 * named, never put up since the server started), never silently swapped for his aim.
 */
public final class PlaceFinder {

    private static final Logger LOG = LoggerFactory.getLogger("mfam-place");

    /** How far an outside spot is LIFTED out of the ground onto the terrain ({@link #feet}). */
    public static final int GROUND_REACH = 32;
    /** How far an outside spot may STEP DOWN to the terrain: a doorstep, never a cliff. */
    public static final int STEP_DOWN = 2;

    /**
     * <b>Where an outside spot's feet go</b>, read off the spot's OWN column ({@code solid(y)}: is
     * the block at that height in the way) — pure. A building's lowest block is its foundations,
     * and the real builds put those 4–26 blocks underground ({@code RealBuildAutoSpotsTest}),
     * so feet INSIDE the ground are lifted to the first two free blocks above (up to
     * {@link #GROUND_REACH}). Feet in the air step DOWN onto support only a doorstep
     * ({@link #STEP_DOWN}), never off a cliff, a raised platform or a sky castle's edge.
     *
     * <p>Not the heightmap, and both ways it was wrong were measured by Gate B: snapped to it, a
     * front spot beside a raised hall went to the ground eight blocks below (off the edge), and one
     * under something overhead went six blocks UP onto it. The column says what is really there.
     */
    public static double feet(double spotY, java.util.function.IntPredicate solid) {
        int y = (int) Math.floor(spotY);
        if (solid.test(y) || solid.test(y + 1)) {
            for (int up = 1; up <= GROUND_REACH; up++) {
                if (!solid.test(y + up) && !solid.test(y + up + 1)) {
                    return y + up;
                }
            }
            return spotY;
        }
        for (int down = 0; down <= STEP_DOWN; down++) {
            if (solid.test(y - down - 1)) {
                return y - down;
            }
        }
        return spotY;
    }

    /** How far from him a creature an {@code at} names is looked for. */
    public static final double CREATURE_REACH = 96.0;

    private PlaceFinder() {
    }

    /**
     * Where an {@code at} resolved: its world, the pure answer, and what it found. {@code buildId}
     * is set when the thing is a building (what a placement waits on while it goes up).
     */
    public record Place(ServerLevel level, PlaceResolver.Resolved r, String kind, String buildId, boolean ground,
                        double ox, double oz) {

        public boolean ok() {
            return level != null && r != null && r.ok();
        }

        public String why() {
            return r == null ? "nothing was named" : r.why();
        }

        static Place no(String why) {
            return new Place(null, PlaceResolver.Resolved.no(why), null, null, false, 0, 0);
        }

        /** Its feet as a block position (on the ground, for an outside spot). */
        public BlockPos block() {
            return BlockPos.containing(r.x(), feetAt(r.x(), r.z()), r.z());
        }

        /**
         * <b>The feet height at {@code x,z}</b> — for a spot OUTSIDE a building (its front, a side,
         * beside, behind, a point on its ring), {@link PlaceFinder#feet}. A spot in or on the
         * building is never moved.
         */
        public double feetAt(double x, double z) {
            if (!ground || level == null) {
                return r.y();
            }
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
            if (!level.hasChunkAt(new BlockPos(bx, 0, bz))) {
                return r.y();
            }
            return feet(r.y(), y -> {
                BlockPos q = new BlockPos(bx, y, bz);
                return !level.getBlockState(q).getCollisionShape(level, q).isEmpty();
            });
        }
    }

    /** Resolve {@code at} for {@code child}; an at with no where takes {@code dflt}. */
    public static Place find(MinecraftServer server, ServerPlayer child, String at, String dflt) {
        At a = At.parse(at);
        if (a == null) {
            return Place.no("no place was named (it reads <thing>:<where> — a building, a creature or me)");
        }
        return find(server, child, a, dflt);
    }

    public static Place find(MinecraftServer server, ServerPlayer child, At a, String dflt) {
        if (a.isMe()) {
            if (child == null) {
                return Place.no("he is not in the world, so there is no 'me' to put it by");
            }
            return new Place(child.level(), PlaceResolver.resolve(body(child, At.ME, true), a.where(), dflt),
                    "me", null, false, child.getX(), child.getZ());
        }
        String buildId = building(a);
        if (buildId != null) {
            BuildPlan plan = BuildRegistry.get(buildId);
            Long anchor = BuildRegistry.anchorOf(buildId);
            if (plan != null && anchor != null) {
                BlockPos p = BlockPos.of(anchor);
                int[] toward = BuildRegistry.towardOf(buildId);
                ServerLevel level = com.moddingfromamod.agent.ArtifactLevel.resolve(server,
                        BuildRegistry.dimensionOf(buildId));
                var r = PlaceResolver.resolve(new PlaceResolver.Building(plan, p.getX(), p.getY(), p.getZ(),
                        toward[0], toward[1]), a.where(), dflt);
                // Outside: an automatic spot that is only somewhere to stand by it. A spot the building
                // marked itself is exactly where it said, whatever its name.
                boolean outside = r.ok() && !com.moddingfromamod.builder.BuildSpots.keepsItsSeat(r.spot())
                        && plan.spots().stream().noneMatch(sp -> sp.name().equals(r.spot()));
                int[] b = com.moddingfromamod.builder.BuildSpots.bounds(plan);
                double ox = p.getX() + (b == null ? 0 : (b[0] + b[3] + 1) / 2.0);
                double oz = p.getZ() + (b == null ? 0 : (b[2] + b[5] + 1) / 2.0);
                return new Place(level, r, "building", plan.id(), outside, ox, oz);
            }
        }
        LivingEntity c = creature(server, child, a);
        if (c != null) {
            String id = Creatures.kindId(c);
            String name = id == null ? a.thing() : id.replace("wishmob:", "");
            return new Place((ServerLevel) c.level(), PlaceResolver.resolve(body(c, name, false), a.where(), dflt),
                    "creature", null, false, c.getX(), c.getZ());
        }
        for (String n : a.lookups()) {
            BuildPlan known = BuildRegistry.get(n);
            if (known != null) {
                String why = BuildRegistry.whyNotHere(known.id());
                return Place.no((why != null ? why + " " : "'" + known.id() + "' is a building he has, but it is "
                        + "not standing. ") + "Build it first (wishbuild " + known.id() + "), then use the at");
            }
        }
        List<String> several = wordMatches(a, BuildRegistry.standingIds());
        if (several.size() > 1) {
            return Place.no("more than one building could be '" + a.thing() + "' (" + String.join(", ", several)
                    + ") — name the one he means by its id");
        }
        LOG.info("[place] nothing called '{}'", a.thing());
        return Place.no("there is nothing called '" + a.thing() + "' — not a standing building, not a creature "
                + "near him, not 'me'" + standingNote());
    }

    /**
     * A standing building's box in the world, {@code {x0, y0, z0, x1, y1, z1}} inclusive, or null —
     * what a camera fits when something happens TO it. Read here, beside the level it stands in,
     * so a tool never looks a recorded position up itself (LEARNINGS: resolve an artifact's world
     * from the artifact).
     */
    public static int[] buildingBox(String id) {
        if (id == null) {
            return null;
        }
        BuildPlan plan = BuildRegistry.get(id);
        Long anchor = BuildRegistry.anchorOf(id);
        int[] b = plan == null ? null : com.moddingfromamod.builder.BuildSpots.bounds(plan);
        if (b == null || anchor == null) {
            return null;
        }
        BlockPos a = BlockPos.of(anchor);
        return new int[]{a.getX() + b[0], a.getY() + b[1], a.getZ() + b[2], a.getX() + b[3], a.getY() + b[4],
                a.getZ() + b[5]};
    }

    /** Where a standing building's anchor is, or null — for the tools that lay something on it. */
    public static BlockPos anchor(String id) {
        Long a = id == null ? null : BuildRegistry.anchorOf(id);
        return a == null ? null : BlockPos.of(a);
    }

    /** The id of the standing building {@code a} names, or null. */
    static String building(At a) {
        List<String> standing = BuildRegistry.standingIds();
        for (String n : a.lookups()) {
            if (standing.contains(n) && BuildRegistry.get(n) != null) {
                return n;
            }
        }
        // His fork of it: "castle" when what stands is castle~1a2b3c4d. The newest such.
        for (String n : a.lookups()) {
            String found = null;
            for (String id : standing) {
                if (id.startsWith(n + "~") && BuildRegistry.get(id) != null) {
                    found = id;
                }
            }
            if (found != null) {
                return found;
            }
        }
        // By what it is CALLED: "my_house" for a building saved as cozy_cottage named "My House".
        for (String n : a.lookups()) {
            for (String id : standing) {
                BuildPlan p = BuildRegistry.get(id);
                if (p != null && p.name() != null && norm(p.name()).equals(n)) {
                    return id;
                }
            }
        }
        // By the WORD he uses for it: "my barn" when what stands is red_barn ("Little Red Barn") —
        // only when exactly ONE standing building has that word, so two barns are asked about, never
        // guessed between (see ambiguous()).
        List<String> hits = wordMatches(a, standing);
        return hits.size() == 1 ? hits.get(0) : null;
    }

    /** The standing buildings whose id or name has the thing's word ({@link At#wordMatches}). */
    static List<String> wordMatches(At a, List<String> standing) {
        java.util.Map<String, String> names = new java.util.LinkedHashMap<>();
        for (String id : standing) {
            BuildPlan p = BuildRegistry.get(id);
            if (p != null) {
                names.put(id, p.name() == null ? "" : p.name());
            }
        }
        return a.wordMatches(names);
    }

    private static String norm(String s) {
        return s.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_').replace("'", "");
    }

    /** The creature or mob {@code a} names: a wished creature before a real mob, his own first, nearest. */
    static LivingEntity creature(MinecraftServer server, ServerPlayer child, At a) {
        if (child == null) {
            return null;
        }
        ServerLevel level = child.level();
        List<LivingEntity> found = new ArrayList<>();
        for (String n : a.lookups()) {
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class,
                    new AABB(child.blockPosition()).inflate(CREATURE_REACH),
                    e -> e.isAlive() && !(e instanceof Player) && !Creatures.isRigInternal(e))) {
                if (Creatures.isCreature(e) ? Creatures.matches(e, n) : realMobIs(e, n)) {
                    found.add(e);
                }
            }
            if (!found.isEmpty()) {
                break;
            }
        }
        found.sort(Comparator.<LivingEntity>comparingInt(e -> Creatures.isCreature(e) ? 0 : 1)
                .thenComparingDouble(e -> e.distanceToSqr(child)));
        List<LivingEntity> wished = new ArrayList<>(found.stream().filter(Creatures::isCreature).toList());
        if (!wished.isEmpty()) {
            return Creatures.hisFirst(wished, child.getUUID().toString()).get(0);
        }
        return found.isEmpty() ? null : found.get(0);
    }

    private static PlaceResolver.Body body(LivingEntity e, String id, boolean me) {
        return new PlaceResolver.Body(id, me, e.getX(), e.getY(), e.getZ(), e.getYRot(), e.getBbWidth(),
                e.getBbHeight());
    }

    private static String standingNote() {
        List<String> s = BuildRegistry.standingIds();
        if (s.isEmpty()) {
            return "";
        }
        return ". Standing now: " + String.join(", ", s.size() > 12 ? s.subList(s.size() - 12, s.size()) : s);
    }

    /**
     * A REAL mob answers only to its own type's name ({@code pig}, {@code iron_golem}), never to a
     * fragment of it: the creature match is deliberately fuzzy, and a fuzzy "house" that is not
     * standing must say so rather than land on whatever mob's id contains the letters.
     */
    static boolean realMobIs(LivingEntity e, String n) {
        String type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
        return type.equals(n) || (n.endsWith("s") && type.equals(n.substring(0, n.length() - 1)));
    }
}
