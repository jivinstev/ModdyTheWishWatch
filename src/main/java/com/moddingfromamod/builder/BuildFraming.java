package com.moddingfromamod.builder;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.runtime.OrbitCamera;

/**
 * <b>Where to stand to photograph a building</b> — eight camera poses, from the plan alone.
 *
 * <p>One source of framing, two consumers: the live wish photo ({@code PhotographTool}) and the
 * test fixture ({@code BuildShotClientTest}). They had neither. The live path resolved its subject
 * with {@code findSubject}, which returns a <b>mob</b> and frames on creature model geometry, so a
 * building — which has no entity and no model — fell through to a single unframed snapshot taken
 * from wherever the child happened to be standing. That is the picture on every building wish card.
 * Meanwhile the fixture did its own arithmetic and aimed at the wrong site. A shared helper is how
 * the two stop disagreeing, and how the fixture can prove the thing the product actually does.
 *
 * <h2>Eight, not four</h2>
 * Four orbit shots say what it looks like. They cannot say whether there is anything inside, because
 * a hollow shell and a furnished hall photograph identically from outside — and this class's whole
 * history is a child who could not get in: a castle whose four doors a refinement had walled up.
 *
 * <h2>Everything comes from the PLAN, so this is pure</h2>
 * Bounds are the plan's own composed blocks, never the world — the world includes the ground it
 * stands on, and on a superflat that is every block to the horizon. Interior air is likewise "a cell
 * inside the bounding box that the plan does not fill", which needs no level access and no
 * server thread. So the whole of it is unit-testable, which the two ad-hoc versions were not.
 */
public final class BuildFraming {

    /** Where the camera stands and which way it looks. World coordinates. */
    public record Pose(double x, double y, double z, float yaw, float pitch, String name) {
    }

    /** The four interior headings, in the order their names are written. */
    private static final String[] INSIDE_NAMES = {"north", "east", "south", "west"};

    /** The four horizontal headings, as (dx, dz). */
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** How much more a lit room is worth than a dark one of the same size. */
    private static final int LIT_BONUS = 3;

    /** How far to look each way when scoring how open a spot is. */
    private static final int SIGHT = 10;

    /**
     * How far overhead to look for a roof.
     *
     * <p>Generous, because a keep's great hall genuinely is 27 blocks floor to ceiling. There used
     * to be a CAP on that gap instead, to keep the camera off a castle's roof — the space between
     * the roof and the balcony above it is a floor with a ceiling over it. Probing the roof in all
     * eight directions does that job properly, so the cap went: it was excluding real halls, and it
     * was the reason a genuinely enormous castle reported no interior at all.
     */
    private static final int ROOF_REACH = 40;

    /** How far to either side the roof must also reach for this to be a room and not an alley. */
    private static final int ROOF_SPREAD = 5;

    /**
     * Straight up, and a few paces every way — <b>eight</b>, not four.
     *
     * <p>A castle's gate arch covers a strip, so with four probes a courtyard between the outer
     * wall and the keep passed as a room and photographed as a wall with sky beside it. Something
     * that covers you in all eight directions five blocks out is a roof.
     */
    private static final int[][] ROOF_PROBES = {
            {0, 0},
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /** Floor, somewhere to stand, and head room: the least a room can be. */
    private static final int MIN_ROOM_HEIGHT = 3;

    /**
     * The least open a spot may be and still be worth photographing, out of {@code 4 * SIGHT}.
     *
     * <p>Below this it is a crawlspace or the gap between two walls, and four pictures of it say
     * nothing about whether the child can get in — which is the entire question these shots exist
     * to answer.
     */
    private static final int MIN_ROOM = 6;

    /**
     * How far above the subject to look down from, in degrees.
     *
     * <p>Chosen, not derived. The first rule put the camera above the ROOFLINE, which on a 76-tall
     * castle meant an 84-block climb and a <b>38° downward tilt</b> — a map view, where a castle
     * reads as a model on a table and its towers and doorway are all foreshortened away. Around 18°
     * is the angle a building is normally photographed at: high enough to see over the near wall
     * and read the roof, low enough that the facade is still the subject.
     */
    private static final double ELEVATION_DEG = 18.0;

    /**
     * How far back to stand <b>once already clear of the footprint</b> — per block of height, and
     * per block of span. The larger wins.
     *
     * <p>These are a fraction of what the lens arithmetic says, on purpose: at 70° vertical FOV a
     * distance of {@code h} already shows 1.4h of world, so framing the far face exactly leaves the
     * subject small and centred in sky. What makes a building look photographed is filling the
     * frame at its NEAR face, and these are tuned to that against real photographs of four real
     * castles rather than derived.
     */
    private static final double FIT_SPAN = 0.40;

    /** Half of Minecraft's default field of view, which is 70° and is <b>vertical</b>. */
    private static final double HALF_FOV_DEG = 35.0;

    /** How much of the frame the building fills top to bottom. A little air, not a lot. */
    private static final double FILL = 0.92;

    private BuildFraming() {
    }

    /** The composed extent of a plan, in plan-relative coordinates. */
    public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

        public int width() {
            return maxX - minX + 1;
        }

        public int height() {
            return maxY - minY + 1;
        }

        public int depth() {
            return maxZ - minZ + 1;
        }

        /** The longer horizontal extent — what the camera has to fit in frame. */
        public int span() {
            return Math.max(width(), depth());
        }
    }

    /**
     * How much of a building's height counts as its ground apron rather than the building.
     *
     * <p>A real Spikor castle is a 52-wide keep with towers, standing in a <b>96-wide lava moat</b>
     * with four 52-long bridges out of it. Framed on all of that, the castle is a smudge in the
     * middle of a car park: correct, complete, and not a photograph of a castle. A moat, a plaza, a
     * drive, a jetty and a lawn are ground the building sits ON.
     */
    private static final double APRON = 0.08;

    /** The plan's own extent, or null when it composes to nothing. */
    public static Bounds bounds(BuildPlan plan) {
        if (plan == null || plan.shapes().isEmpty()) {
            return null;
        }
        return bounds(ComposedPlan.of(plan));
    }

    /** The extent of a building already composed ({@link ComposedPlan}), or null when empty. */
    public static Bounds bounds(ComposedPlan built) {
        if (built == null || built.isEmpty()) {
            return null;
        }
        int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        built.forEach((x, y, z, block) -> {
            lo[0] = Math.min(lo[0], x); hi[0] = Math.max(hi[0], x);
            lo[1] = Math.min(lo[1], y); hi[1] = Math.max(hi[1], y);
            lo[2] = Math.min(lo[2], z); hi[2] = Math.max(hi[2], z);
        });
        return new Bounds(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]);
    }

    /**
     * The four orbit poses, framed on width AND height.
     *
     * <p>Framing on height alone puts a hall 40 long and 8 tall ten blocks from the lens with both
     * ends out of shot, which is why {@link OrbitCamera#distanceForBuild} exists.
     *
     * @param anchorX the world position the plan's origin was built at
     */
    public static List<Pose> orbit(BuildPlan plan, int anchorX, int anchorY, int anchorZ) {
        Bounds b = bounds(plan);
        List<Pose> out = new ArrayList<>();
        if (b == null) {
            return out;
        }
        double cx = anchorX + (b.minX() + b.maxX()) / 2.0;
        double cz = anchorZ + (b.minZ() + b.maxZ()) / 2.0;
        // ANYTHING BELOW THE ANCHOR IS BURIED, so it must not be framed for.
        //
        // The anchor is the ground the build was placed on, so a shape at a negative dy is under
        // it. Real builds do this constantly — a lake needs a bed dug 10 deep to hold its water,
        // foundations go down, a moat is a hole. Counting that as height backs the camera off for
        // 10 blocks of stone nobody can see, and it compounds: it also raises the aim point, so
        // the castle sits high in the frame with sky above it. One real castle measured 76 tall
        // and was 66 of anything you could photograph.
        //
        // A build that is ENTIRELY underground keeps its own bounds — it has to be framed on
        // something, and the alternative is a zero-height subject.
        int floorY = b.maxY() >= 0 ? Math.max(b.minY(), 0) : b.minY();
        double feetY = anchorY + floorY;
        double h = Math.max(1, b.maxY() - floorY + 1);
        // STAND CLEAR OF IT FIRST, THEN FRAME IT.
        //
        // Distance measured from the MIDDLE is not distance from the building. A 120-wide Spikor
        // castle framed at 63 blocks put the lens three blocks from its own corner tower: the tower
        // filled half the picture, the far half of the castle was behind it, and the base was out
        // of shot. So the footprint's own half-span is paid first and the framing distance is added
        // on top — that half-span is what the camera has to get past before any of it is framing.
        //
        // AIM AT THE MIDDLE OF IT AND RISE FROM THERE, rather than standing above the roof.
        //
        // Standing above the roofline was itself a fix, for two real builds: a floating castle on
        // stilts, where an eye-height camera is UNDERNEATH photographing its underside, and a
        // Spikor castle whose lava moat flowed out across the world, where the camera stood in a
        // lava sea. Both passed the "am I in a solid block" guard, being in air and in a fluid.
        //
        // But it over-corrected. Rising from the mid-height of the building by a fixed ANGLE cannot
        // put the lens under an overhang either — the camera is half the building's height up
        // before the elevation is applied — and it keeps the tilt at a photographer's angle rather
        // than a cartographer's.
        // FRAME ON THE BUILDING, NOT ITS GROUNDS. The span is measured above the apron — see
        // APRON — so a wide flat surround does not push the camera back until the building it
        // surrounds is a smudge. Height is unaffected: the apron is at the bottom of it.
        double span = spanAbove(plan, floorY + (int) Math.max(1, h * APRON), b);
        double clear = span / 2.0;
        double dist = clear + Math.max(heightFit(h), span * FIT_SPAN);
        dist = Math.max(24.0, Math.min(dist, OrbitCamera.MAX_BUILD_DISTANCE));
        double aimY = feetY + h * 0.5;
        double eyeY = aimY + dist * Math.tan(Math.toRadians(ELEVATION_DEG));
        for (int stop = 0; stop < OrbitCamera.STOPS; stop++) {
            double deg = OrbitCamera.bearing(stop);
            double rad = Math.toRadians(deg);
            double px = cx + dist * Math.sin(rad);
            double pz = cz - dist * Math.cos(rad);
            out.add(new Pose(px, eyeY, pz, (float) deg, (float) ELEVATION_DEG, stopName(stop)));
        }
        return out;
    }

    /**
     * How far back the ROOF has to be for it to be in the picture.
     *
     * <p>Derived, not tuned, because tuning it got this wrong by four blocks and cost a re-shoot.
     * Tilting the camera down by {@link #ELEVATION_DEG} spends part of the lens: only
     * {@code HALF_FOV - ELEVATION} of frame is left <b>above</b> the view axis, and the top of the
     * building has to fit in that. At 18° down that is 17°, not 35 — half the room a level camera
     * would have. A constant picked while thinking in half-angles put a real castle's spire four
     * blocks past the top edge, and the photograph simply had no spire in it.
     *
     * <p>The aim point is the middle of the building, so the roof is {@code h/2} above it, and it
     * rises by {@code dist·tan(elevation)} on the way out to the camera.
     */
    private static double heightFit(double h) {
        double elev = Math.toRadians(ELEVATION_DEG);
        double above = Math.toRadians(HALF_FOV_DEG - ELEVATION_DEG);
        return (h / 2.0) / ((Math.tan(elev) + Math.tan(above)) * FILL);
    }

    /** {@code face}, {@code left}, {@code back}, {@code right} — the names the files carry. */
    public static String stopName(int stop) {
        return switch (stop) {
            case 0 -> "face";
            case 1 -> "left";
            case 2 -> "back";
            case 3 -> "right";
            default -> "stop" + stop;
        };
    }

    /**
     * The four interior poses, or empty when the building has no inside to stand in.
     *
     * <p>The camera position is <b>searched for, not calculated</b>. The middle of a bounding box is
     * inside a solid keep as often as it is inside a room, and a camera inside a block photographs
     * black — which is indistinguishable from a dark interior, i.e. exactly the thing these shots
     * exist to reveal. So it spirals out from the middle for a cell the plan does not fill, with
     * another empty cell above it for head room, and returns nothing at all when there is none.
     */
    public static List<Pose> inside(BuildPlan plan, int anchorX, int anchorY, int anchorZ) {
        // ONE composition for every question below: the extent, what is solid, what is
        // liquid and where the lights are all describe the same finished building.
        ComposedPlan built = plan == null ? null : ComposedPlan.of(plan);
        Bounds b = bounds(built);
        List<Pose> out = new ArrayList<>();
        if (b == null) {
            return out;
        }
        java.util.Set<Long> solid = solidCells(built);
        java.util.Set<Long> liquid = liquidCells(built);
        int bestX = 0;
        int bestY = 0;
        int bestZ = 0;
        int bestScore = 0;
        // FIND THE ROOMS EXACTLY, by walking each column once and taking every run of air that has
        // a floor under it and a ceiling over it. That IS the definition of a room, and it costs
        // one pass over the bounding box.
        //
        // The version this replaced sampled six evenly spaced levels and looked at those. Six
        // levels through a 76-tall castle are 12 blocks apart, so a 5-tall room falls between two
        // of them and the building reports NO INTERIOR — which is what two of four real castles
        // did, both of which have rooms you can walk into.
        for (int x = b.minX(); x <= b.maxX(); x++) {
            for (int z = b.minZ(); z <= b.maxZ(); z++) {
                int runStart = Integer.MIN_VALUE;
                for (int y = b.minY(); y <= b.maxY() + 1; y++) {
                    boolean blocked = y > b.maxY() || solid.contains(key(x, y, z));
                    if (!blocked) {
                        if (runStart == Integer.MIN_VALUE) {
                            runStart = y;
                        }
                        continue;
                    }
                    if (runStart == Integer.MIN_VALUE) {
                        continue;
                    }
                    int height = y - runStart;
                    long below = key(x, runStart - 1, z);
                    boolean hasFloor = solid.contains(below) && !liquid.contains(below);
                    runStart = Integer.MIN_VALUE;
                    if (!hasFloor || height < MIN_ROOM_HEIGHT) {
                        continue;      // no floor, standing on a liquid, a crawlspace, or the sky
                    }
                    int eye = y - height + 1;           // stand on the floor, one block up
                    if (!walled(solid, b, x, eye, z) || !roofed(solid, x, eye, z)) {
                        continue;
                    }
                    // PREFER A ROOM WITH A LIGHT IN IT. A dark room is a real interior and an
                    // unusable photograph — one real castle's best room by openness alone was an
                    // unlit corridor that came back at brightness 19 out of 255. A lit room of half
                    // the size says far more about whether the child can go in there. Still only a
                    // preference: a building with no light anywhere is photographed anyway, and the
                    // gate reports the darkness rather than hiding it.
                    int score = openness(solid, x, eye, z)
                            * (BuildLighting.litNearby(built, x, eye, z) ? LIT_BONUS : 1);
                    if (score > bestScore) {
                        bestScore = score;
                        bestX = x;
                        bestY = eye;
                        bestZ = z;
                    }
                }
            }
        }
        if (bestScore < MIN_ROOM) {
            return out;                                  // solid through, or only crawlspaces
        }
        for (int dir = 0; dir < INSIDE_NAMES.length; dir++) {
            out.add(new Pose(anchorX + bestX + 0.5, anchorY + bestY, anchorZ + bestZ + 0.5,
                    dir * 90f, 0f, "in" + dir + "_" + INSIDE_NAMES[dir]));
        }
        return out;
    }

    /**
     * Something overhead a few paces away too, not merely over your own head.
     *
     * <p>A courtyard with a bridge across it satisfies every other test at exactly the column the
     * bridge crosses: floor below, walls either side, and a ceiling directly above. A real Spikor
     * castle has one, and its four "interior" photographs were taken outdoors in an alley with the
     * sky plainly visible in them. A roof covers a room; a bridge covers a line.
     *
     * <p>A neighbour that is itself solid is fine — that is a wall, and a room is allowed to have
     * walls.
     *
     * <p>It probes your own column too, which is what makes an air run that simply reaches the top
     * of the build — open sky — fail here rather than needing a second rule saying the same thing
     * in different words.
     */
    private static boolean roofed(java.util.Set<Long> solid, int x, int y, int z) {
        for (int[] d : ROOF_PROBES) {
            int nx = x + d[0] * ROOF_SPREAD;
            int nz = z + d[1] * ROOF_SPREAD;
            if (solid.contains(key(nx, y, nz))) {
                continue;
            }
            boolean over = false;
            for (int up = 1; up <= ROOF_REACH && !over; up++) {
                over = solid.contains(key(nx, y + up, nz));
            }
            if (!over) {
                return false;
            }
        }
        return true;
    }

    /**
     * Building material either side of you, at your own level, in all four directions.
     *
     * <p>Floor and ceiling alone still let the camera stand on the ROOF of a real castle: it had
     * the roof slope under its feet and the lighthouse balcony eighteen blocks overhead, which
     * satisfies both — and photographs a field. A room is enclosed on six sides.
     *
     * <p>It scans <b>as far as the building goes</b>, not a fixed distance. Capped at ten blocks it
     * required the camera to be within ten of BOTH opposite walls, which in a 19-wide hall is one
     * single column — and in the test castle that column is the one the front door is cut through,
     * so a perfectly ordinary room reported no interior at all. How wide a room is says nothing
     * about whether you are in it; that is what {@link #openness} is for.
     */
    private static boolean walled(java.util.Set<Long> solid, Bounds b, int x, int y, int z) {
        for (int[] d : DIRS) {
            int limit = d[0] != 0
                    ? (d[0] > 0 ? b.maxX() - x : x - b.minX())
                    : (d[1] > 0 ? b.maxZ() - z : z - b.minZ());
            boolean wall = false;
            for (int step = 1; step <= limit && !wall; step++) {
                wall = solid.contains(key(x + d[0] * step, y, z + d[1] * step));
            }
            if (!wall) {
                return false;
            }
        }
        return true;
    }

    /**
     * How far you can see from this cell, summed over the four headings — so the middle of a hall
     * beats a cupboard, and beats standing with your nose against the bookshelves.
     *
     * <p>The first version simply took the first air cell found spiralling out from the centre of
     * the bounding box. On a real castle the centre was a cell at the top of the keep hard against
     * a bookshelf wall, and all four interior photographs were of that wall from a block away.
     */
    private static int openness(java.util.Set<Long> solid, int x, int y, int z) {
        int total = 0;
        for (int[] d : DIRS) {
            for (int step = 1; step <= SIGHT; step++) {
                if (solid.contains(key(x + d[0] * step, y, z + d[1] * step))) {
                    break;
                }
                total++;
            }
        }
        return total;
    }

    /** All eight, orbit first — the set a wish card wants. */
    public static List<Pose> all(BuildPlan plan, int anchorX, int anchorY, int anchorZ) {
        List<Pose> out = new ArrayList<>(orbit(plan, anchorX, anchorY, anchorZ));
        out.addAll(inside(plan, anchorX, anchorY, anchorZ));
        return out;
    }

    /**
     * The wider horizontal extent of everything standing above {@code aboveY}, or the full span if
     * nothing does.
     *
     * <p>Falling back to the full span matters: a runway, an arena floor or a plaza is <i>meant</i>
     * to be flat, and there is nothing above the apron to frame on instead.
     */
    private static int spanAbove(BuildPlan plan, int aboveY, Bounds b) {
        int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE};
        ComposedPlan.of(plan).forEach((x, y, z, block) -> {
            if (y <= aboveY) {
                return;
            }
            lo[0] = Math.min(lo[0], x); hi[0] = Math.max(hi[0], x);
            lo[1] = Math.min(lo[1], z); hi[1] = Math.max(hi[1], z);
        });
        if (lo[0] > hi[0]) {
            return b.span();
        }
        return Math.max(hi[0] - lo[0] + 1, hi[1] - lo[1] + 1);
    }

    // ───────────────────────── plan geometry ─────────────────────────

    /**
     * Every cell the finished building fills with a <b>liquid</b>.
     *
     * <p>A liquid is not air, so to every other test in here it is a perfectly good floor — and a
     * real Spikor castle's lava moat is enclosed by its own towers with an arch overhead, which
     * made it the best "room" in the building. All four interior photographs were of the inside of
     * the moat. You cannot stand on a liquid, and this is how that gets said.
     *
     * <p>Matched on the id containing water or lava rather than a fixed list, so a mod's own
     * molten something is covered too — this class must not need updating when a mod is installed.
     */
    private static java.util.Set<Long> liquidCells(ComposedPlan built) {
        java.util.Set<Long> out = new java.util.HashSet<>();
        built.forEach((x, y, z, block) -> {
            String id = block.toLowerCase(java.util.Locale.ROOT);
            if (id.contains("water") || id.contains("lava")) {
                out.add(key(x, y, z));
            }
        });
        return out;
    }

    /**
     * Every cell the finished building fills — shapes in order, air carving rather than filling
     * ({@link ComposedPlan}, the one composition; a camera framed on a different building from the
     * one that gets built is the whole failure this class exists to end).
     */
    private static java.util.Set<Long> solidCells(ComposedPlan built) {
        java.util.Set<Long> out = new java.util.HashSet<>();
        built.forEach((x, y, z, block) -> out.add(key(x, y, z)));
        return out;
    }

    // Same packing as BuildGeometry: offsets are bounded by MAX_OFFSET + MAX_DIM = 224.
    private static long key(int x, int y, int z) {
        return ((long) (x + 512) << 40) | ((long) (y + 512) << 20) | (z + 512);
    }
}
