package com.moddingfromamod.builder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>What a {@link BuildPlan} actually comes out as, computed without a world.</b>
 *
 * <p>The oracle behind the buildings gate. A plan is a list of primitives, so most questions
 * worth asking about the result — is that room a room, or a two-walled shell? is the top a finished
 * roof or an unfinished box? — are arithmetic, and can be answered headless in milliseconds instead
 * of by a photograph and a judge.
 *
 * <h2>Why this is not per-shape</h2>
 *
 * <p>The check this replaces looked at one {@link BuildShape} at a time, and could therefore never
 * fail. A hollow box places a block wherever a coordinate is on a face, so at any interior height
 * every wall cell is present <i>by construction</i> and the middle is absent <i>by construction</i>.
 * Brute-forced over all 4,913 hollow boxes from 3³ to 19³, it reported zero faults; over the 19 real
 * agent plans, zero again — while announcing itself as checked on all 84 buildings cases. A gate
 * that cannot fail is indistinguishable from a gate that passes.
 *
 * <p>It also could not see the defect it was named for. Shapes are placed <b>in order and later ones
 * overwrite earlier ones</b> — that is how the agent carves a doorway — and in real output 31 of 396
 * shapes are {@code minecraft:air} and up to 43% of all placements land on a position an earlier
 * shape already claimed. A wall opened up by a later shape is invisible to anything that looks at
 * shapes one at a time. So this composes the whole plan first, exactly as {@link Builder} does, and
 * asks its questions of the result.
 *
 * <p>Pure — no Minecraft imports — so it runs in the unit suite against the real corpus rather than
 * only inside a game test.
 */
public final class BuildGeometry {

    /** Air does not fill a space, it empties one: a later air shape is how a doorway gets cut. */
    private static final String AIR = "minecraft:air";

    /**
     * Below this fraction, a wall is treated as absent rather than as having a door in it.
     *
     * <p><b>Calibrated on the 19 real agent builds, not on fixtures.</b> The two genuinely broken
     * ones come in at 0.35 and 0.43, the worst legitimate one (a battle arena, full of gateways) at
     * 0.70, and the median at 0.974. So this sits in a real gap rather than being fitted to either
     * side — which matters, because a gate tuned on hand-made fixtures once caught 0 of 14 real
     * creatures on its first live run. {@code RealBuildCalibrationTest} holds the distribution and
     * fails if it shifts.
     *
     * <p>Lives here rather than in a harness so that the stress harness, the corpus game test and
     * the calibration all mean the same thing by "missing". Two copies of this number would drift,
     * and the check this replaces had exactly that problem — two identical copies of a tautology.
     */
    public static final double WALL_MISSING = 0.5;

    private final Set<Long> solid;
    private final List<Room> rooms;
    /** Placed blocks a child can open and walk through — solid to the eye, a way in to a person. */
    private final Set<Long> openings;

    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;

    private BuildGeometry(Set<Long> solid, Set<Long> openings, List<Room> rooms,
                          int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.solid = solid;
        this.openings = openings;
        this.rooms = List.copyOf(rooms);
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    // Coordinates are bounded by MAX_OFFSET + MAX_DIM = 224, so 20 bits each with a bias is plenty
    // and lets the whole composed build live in one HashSet instead of a nested map.
    private static long key(int x, int y, int z) {
        return ((long) (x + 512) << 40) | ((long) (y + 512) << 20) | (z + 512);
    }

    /**
     * Compose a plan the way the builder will: shapes in order, later ones overwriting earlier ones,
     * air removing rather than filling.
     */
    public static BuildGeometry of(BuildPlan plan) {
        return of(ComposedPlan.of(plan));
    }

    /**
     * The same, from a building already composed ({@link ComposedPlan}) — so a tool call
     * that also asks about light composes the plan once rather than once per question.
     */
    public static BuildGeometry of(ComposedPlan built) {
        List<BuildShape> shapes = built.plan() == null ? List.of() : built.plan().shapes();
        // Every placed block fills; the walk-through ones are also kept, so a DOOR can be told
        // from a wall. A door built over by a wall is a wall, which the composition already says.
        Set<Long> solid = new HashSet<>();
        Set<Long> openings = new HashSet<>();
        built.forEach((x, y, z, block) -> {
            long k = key(x, y, z);
            solid.add(k);
            if (isWalkThrough(block)) {
                openings.add(k);
            }
        });
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        // Bounds are taken from the SOLID result, not from the shapes: a plan whose last act is to
        // hollow something out is smaller than its shape list claims.
        for (long k : solid) {
            int x = (int) ((k >> 40) & 0xFFFFF) - 512;
            int y = (int) ((k >> 20) & 0xFFFFF) - 512;
            int z = (int) (k & 0xFFFFF) - 512;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        List<Room> rooms = new ArrayList<>();
        for (int i = 0; i < shapes.size(); i++) {
            Room r = roomOf(shapes.get(i), i, solid);
            if (r != null) {
                rooms.add(r);
            }
        }
        openings.retainAll(solid);
        return new BuildGeometry(solid, openings, rooms, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * <b>How much room is sealed inside, and where a door would open it.</b>
     *
     * <p>Nothing else asked whether a child can get IN. A sealed box passes every other check here:
     * it has four walls, a solid lid, and {@code BuildLighting} will even add lights to it — which
     * fires precisely <i>because</i> it encloses a space, so a fully sealed building is reported as
     * improved. The driving complaint is recorded verbatim in the sibling repo: <i>"you didn't make
     * doors into any of the stores! I have to break the blocks myself!"</i>
     *
     * @param volume  air blocks enclosed by the building and unreachable from outside, summed over
     *                every separate room
     * @param breach  a spot where ONE block stands between the BIGGEST sealed room and the outside
     *                — the cheapest place to put a doorway — or null if that room's wall is
     *                thicker everywhere. This is {@code rooms.get(0).breach()}, kept as a field
     *                because most buildings have exactly one room and reading it should stay easy.
     * @param rooms   every separate sealed room, biggest first. <b>A doorway opens ONE of these.</b>
     * @param tooBig  the build was past {@link #MAX_FLOOD_VOLUME} and was not searched at all;
     *                {@code volume} is then 0 because it is UNKNOWN, not because it is zero
     */
    public record Sealed(int volume, int[] breach, List<Room> rooms, boolean tooBig) {

        /**
         * A single enclosed pocket, the cheapest place to cut into <b>it</b>, and where it sits.
         *
         * <p>{@code minY}/{@code maxY} are what tell a stacked STOREY from a sealed vault, and that
         * distinction is the answer to an earlier issue's open question. A tower whose sealed rooms are a
         * column of equal slabs, each between two decorative bands, has no missing front door — it
         * has no stairs.
         */
        public record Room(int volume, int[] breach, int minY, int maxY) {
        }

        /**
         * <b>Are these rooms STACKED — a tower with no stairs rather than a vault with no door?</b>
         *
         * <p>The distinction decides what to tell the agent, and getting it wrong sends it to fix
         * the wrong thing. Measured over {@code corpus/real-builds.jsonl} while answering an earlier issue's
         * *"how many of these are deliberate"*: nine of the sealed builds are lighthouses, and in
         * every one the sealed rooms are the gaps between decorative bands drawn across a hollow
         * tower. {@code real_05_purple_crystal_lighthouse} has glass bands at y=8,16,24…64 and
         * sealed rooms of 148 blocks at y=12-15, 20-23, 28-31… — one per storey, eight of them,
         * none connected to the next.
         *
         * <p>A band across a hollow tower is a <b>floor</b>. Nothing says so, and the consequence is
         * invisible from the plan: each band is a perfectly ordinary decorative shape. So "cut a
         * doorway" is the wrong remedy here — a doorway in the outside wall of storey three leaves
         * the other seven exactly as they were.
         *
         * <p>Three or more, with no two overlapping in Y. Three because two stacked rooms are as
         * likely a cellar under a hall; and DISJOINT because {@code real_08_spikor_castle}'s four
         * sealed corner towers all span y=4-57 — side by side, not stacked — and calling those
         * storeys would send the agent looking for stairs between things that are nowhere near
         * each other.
         */
        public boolean stackedStoreys() {
            List<Room> big = rooms.stream()
                    .filter(r -> r.volume() >= SEALED_MIN_ROOM)
                    .sorted((a, b) -> Integer.compare(a.minY(), b.minY()))
                    .toList();
            if (big.size() < MIN_STOREYS) {
                return false;
            }
            for (int i = 1; i < big.size(); i++) {
                if (big.get(i).minY() <= big.get(i - 1).maxY()) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * How many stacked rooms make a tower rather than a coincidence.
     *
     * <p>Two is a cellar under a hall often enough that saying "no stairs" about it would be the
     * cry-wolf failure (docs/WISH_LINTERS.md P6). Three in a column is a pattern.
     */
    public static final int MIN_STOREYS = 3;

    /**
     * Ceiling on the flood fill. This runs inside a live wish while a child watches a ticker, and a
     * city-sized plan would search tens of millions of cells. Past this the answer is "unknown" and
     * says so, rather than being silently 0 — an unknown reported as a pass is the failure mode
     * this whole class of check exists to remove.
     */
    public static final int MAX_FLOOD_VOLUME = 2_000_000;

    /**
     * How much enclosed air counts as a ROOM rather than a gap in the decoration —.
     *
     * <p>Measured over {@code corpus/real-builds.jsonl}, the 19 buildings the agent really built:
     * a bare {@code > 0} test fires on 14 of them, and the smallest is {@code real_16_gorilla_jungle}
     * at <b>4 blocks</b> — a pocket inside some scenery, not somewhere a child was meant to stand.
     * The rest jump straight to 741 and up, so the gap between "trapped air" and "a room with no
     * door" is wide and this sits in it. 3x3x3 is the smallest thing worth calling a room.
     *
     * <p>The remaining 13 are not noise: {@code real_08_spikor_castle} encloses <b>28,228</b> blocks
     * with no way in. That rate is a finding about the generator rather than about this threshold,
     * and it is why the note is worth carrying at all.
     */
    public static final int SEALED_MIN_ROOM = 27;

    /** Air enclosed by the build that no path from outside reaches. */
    public Sealed sealedInterior() {
        if (solid.isEmpty()) {
            return new Sealed(0, null, List.of(), false);
        }
        // One cell of padding, so the fill always starts in open air even for a building whose
        // walls sit exactly on the bounds.
        int x0 = minX - 1;
        int y0 = minY - 1;
        int z0 = minZ - 1;
        int w = maxX - minX + 3;
        int h = maxY - minY + 3;
        int d = maxZ - minZ + 3;
        long volume = (long) w * h * d;
        if (volume > MAX_FLOOD_VOLUME) {
            return new Sealed(0, null, List.of(), true);
        }
        boolean[] seen = new boolean[(int) volume];
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        seen[0] = true;
        queue.add(0);
        int[] dx = {1, -1, 0, 0, 0, 0};
        int[] dy = {0, 0, 1, -1, 0, 0};
        int[] dz = {0, 0, 0, 0, 1, -1};
        while (!queue.isEmpty()) {
            int at = queue.poll();
            int ax = at % w;
            int ay = (at / w) % h;
            int az = at / (w * h);
            for (int i = 0; i < 6; i++) {
                int nx = ax + dx[i];
                int ny = ay + dy[i];
                int nz = az + dz[i];
                if (nx < 0 || ny < 0 || nz < 0 || nx >= w || ny >= h || nz >= d) {
                    continue;
                }
                int n = nx + ny * w + nz * w * h;
                if (seen[n] || blocksAccess(x0 + nx, y0 + ny, z0 + nz)) {
                    continue;
                }
                seen[n] = true;
                queue.add(n);
            }
        }
        // SEPARATE the sealed air into ROOMS, rather than counting it as one number —.
        //
        // The total was always right and the advice was always wrong. `breach` used to be whichever
        // sealed cell the scan happened to reach first, and the note built from it said "a 1x2 hole
        // there would open it" — a claim that is only true when there is exactly one pocket.
        //
        // Measured on a real lighthouse: SEALED reported 742 blocks and one thinnest spot; the
        // agent cut a 3x4x8 doorway through it on the next call, and was told 619 blocks were still
        // sealed, at a different spot six blocks up. It was never one room. The agent did as it was
        // told, the note repeated itself, and the wish ended with the building still shut.
        //
        // That is an earlier issue's shape and this file's own comment already names it for the sibling defect:
        // "the defect is the CLAIM, not the light". So count the rooms and breach each of them.
        boolean[] grouped = new boolean[(int) volume];
        List<Sealed.Room> rooms = new ArrayList<>();
        int sealed = 0;
        for (int i = 0; i < volume; i++) {
            if (seen[i] || grouped[i]
                    || blocksAccess(x0 + i % w, y0 + (i / w) % h, z0 + i / (w * h))) {
                continue;
            }
            // A second flood, this time INSIDE one pocket, so two rooms in one building are two
            // entries rather than one sum with one misleading doorway.
            int roomVolume = 0;
            int[] roomBreach = null;
            int roomMinY = Integer.MAX_VALUE;
            int roomMaxY = Integer.MIN_VALUE;
            grouped[i] = true;
            queue.add(i);
            while (!queue.isEmpty()) {
                int at = queue.poll();
                int ax = at % w;
                int ay = (at / w) % h;
                int az = at / (w * h);
                roomVolume++;
                roomMinY = Math.min(roomMinY, y0 + ay);
                roomMaxY = Math.max(roomMaxY, y0 + ay);
                if (roomBreach == null) {
                    roomBreach = oneBlockWall(seen, w, h, d, x0, y0, z0, ax, ay, az);
                }
                for (int k = 0; k < 6; k++) {
                    int nx = ax + dx[k];
                    int ny = ay + dy[k];
                    int nz = az + dz[k];
                    if (nx < 0 || ny < 0 || nz < 0 || nx >= w || ny >= h || nz >= d) {
                        continue;
                    }
                    int n = nx + ny * w + nz * w * h;
                    if (grouped[n] || seen[n] || blocksAccess(x0 + nx, y0 + ny, z0 + nz)) {
                        continue;
                    }
                    grouped[n] = true;
                    queue.add(n);
                }
            }
            sealed += roomVolume;
            rooms.add(new Sealed.Room(roomVolume, roomBreach, roomMinY, roomMaxY));
        }
        // Biggest first, so the one field every existing caller reads names the room most worth
        // opening rather than whichever one the scan order reached first.
        rooms.sort((a, b) -> Integer.compare(b.volume(), a.volume()));
        return new Sealed(sealed, rooms.isEmpty() ? null : rooms.get(0).breach(),
                List.copyOf(rooms), false);
    }

    /**
     * Is this sealed cell separated from the outside by exactly ONE block? That block is the
     * cheapest doorway, and naming it is what turns "there is no way in" into an instruction
     * (docs/WISH_LINTERS.md P2).
     */
    private int[] oneBlockWall(boolean[] seen, int w, int h, int d,
                               int x0, int y0, int z0, int ax, int ay, int az) {
        int[] dx = {1, -1, 0, 0, 0, 0};
        int[] dy = {0, 0, 1, -1, 0, 0};
        int[] dz = {0, 0, 0, 0, 1, -1};
        for (int i = 0; i < 6; i++) {
            int mx = ax + dx[i];
            int my = ay + dy[i];
            int mz = az + dz[i];
            int fx = ax + dx[i] * 2;
            int fy = ay + dy[i] * 2;
            int fz = az + dz[i] * 2;
            if (fx < 0 || fy < 0 || fz < 0 || fx >= w || fy >= h || fz >= d) {
                continue;
            }
            // wall block, then open air the outside fill already reached. `blocksAccess`, not
            // `isSolid`: a door is not the wall to cut through, it is already a way in.
            if (blocksAccess(x0 + mx, y0 + my, z0 + mz)
                    && !blocksAccess(x0 + fx, y0 + fy, z0 + fz)
                    && seen[fx + fy * w + fz * w * h]) {
                return new int[]{x0 + mx, y0 + my, z0 + mz};
            }
        }
        return null;
    }

    /** True when a block ends up at this position once the whole plan has been placed. */
    public boolean isSolid(int x, int y, int z) {
        return solid.contains(key(x, y, z));
    }

    /**
     * Does this position BLOCK a person? A door is solid ({@link #isSolid}) and yet not a barrier,
     * which is the whole distinction — {@code solidCount}, the wall checks and the unfinished-lid
     * check all still count it as a block that was placed.
     */
    public boolean blocksAccess(int x, int y, int z) {
        long k = key(x, y, z);
        return solid.contains(k) && !openings.contains(k);
    }

    /**
     * <b>Blocks a child opens and walks through, so they are a WAY IN rather than a wall.</b>
     *
     * <p>Measured, and it is a hazard the SEALED note created for itself. Told a house had no way
     * in, an agent hung a real oak door in the hole it had just cut — the correct answer — and was
     * told the house was still sealed, because a closed door is not air. Its next call put
     * {@code minecraft:air} where the door's upper half belonged, which satisfies the check and
     * ships half a door. A check that punishes its own remedy is worse than no check.
     *
     * <p>Deliberately narrow. Glass, panes and bars stay solid: you can see in through them, which
     * is not the same as getting in, and the complaint this whole family answers is having to
     * break the blocks yourself. Only the three things a child opens are listed, because that is
     * the case that was observed; a general passability model is a bigger claim than the evidence.
     *
     * <p>None of the 19 builds in {@code corpus/real-builds.jsonl} contains one of these, so this
     * changes no historical measurement — those were all mined before the note existed to push an
     * agent towards doors in the first place.
     */
    static boolean isWalkThrough(String block) {
        if (block == null) {
            return false;
        }
        String b = block.toLowerCase(java.util.Locale.ROOT);
        int bracket = b.indexOf('[');          // minecraft:oak_door[facing=north,...]
        if (bracket >= 0) {
            b = b.substring(0, bracket);
        }
        return b.endsWith("_door") || b.endsWith("_trapdoor") || b.endsWith("_fence_gate");
    }

    public boolean isEmpty() {
        return solid.isEmpty();
    }

    /** How many blocks stand at the end — after later shapes have overwritten and carved. */
    public int solidCount() {
        return solid.size();
    }

    public int minY() {
        return minY;
    }

    public int maxY() {
        return maxY;
    }

    /** Tallest extent, in blocks. */
    public int height() {
        return isEmpty() ? 0 : maxY - minY + 1;
    }

    /** The longer of the two horizontal extents — a building's "how wide does it read". */
    public int span() {
        return isEmpty() ? 0 : Math.max(maxX - minX + 1, maxZ - minZ + 1);
    }

    /**
     * Tall-and-narrow versus low-and-wide, which is the difference between a tower and a floor.
     *
     * <p>Load-bearing for the roof question: "a flat top is unfinished" is true of a tower and
     * flatly false of an arena, a runway or the bottom of an ocean trench, all of which the agent
     * really builds and all of which are <i>correctly</i> flat on top.
     */
    public double heightToSpan() {
        return span() == 0 ? 0 : (double) height() / span();
    }

    /**
     * The fraction of the building's footprint that is still covered at its very highest layer.
     *
     * <p>1.0 is a perfectly flat lid across the whole thing. A roof, a spire or a dome tapers, so it
     * comes out small. This is a <b>measurement, not a verdict</b> — see {@link #looksUnfinished()}.
     */
    public double topCoverage() {
        if (isEmpty()) {
            return 0;
        }
        Set<Long> footprint = new HashSet<>();
        Set<Long> top = new HashSet<>();
        for (long k : solid) {
            int x = (int) ((k >> 40) & 0xFFFFF) - 512;
            int y = (int) ((k >> 20) & 0xFFFFF) - 512;
            int z = (int) (k & 0xFFFFF) - 512;
            long column = ((long) (x + 512) << 20) | (z + 512);
            footprint.add(column);
            if (y == maxY) {
                top.add(column);
            }
        }
        return (double) top.size() / footprint.size();
    }

    /**
     * A tall building with a full flat lid: the "unfinished box" a child sees from the air.
     *
     * <p><b>Deliberately narrow, because the obvious rule is backwards.</b> Measured over the 19
     * real agent plans, a bare "the top must not be flat" fires on the battle arena (100% top
     * coverage), two ocean trenches (100%) and the crystal arena (90%) — every one of which is
     * meant to be flat, being a floor. That is the same anti-correlation that once penalised a
     * creature for being the purple blob it was asked to be: a metric that is merely noisy is
     * useless, and one that is backwards is worse than nothing.
     *
     * <p>What separates them is proportion, not the top. Nothing that is taller than it is wide has
     * any business being lidded, and no arena is taller than it is wide.
     */
    public boolean looksUnfinished() {
        return heightToSpan() > 1.0 && topCoverage() > 0.9;
    }

    /** Whether anything reaches the anchor plane. Says nothing about the anchor's own height. */
    public boolean touchesAnchorPlane() {
        return !isEmpty() && minY <= 0;
    }

    /** The rooms this plan intends, with what is left of each wall once everything is placed. */
    public List<Room> rooms() {
        return rooms;
    }

    /**
     * The worst-covered wall of any room, or 1.0 when the plan has no rooms.
     *
     * <p>This is the number {@code BUILDINGS_HAVE_FOUR_WALLS} always meant to be about.
     */
    public double weakestWall() {
        double worst = 1.0;
        for (Room r : rooms) {
            worst = Math.min(worst, r.weakestSide());
        }
        return worst;
    }

    /**
     * One enclosure the plan intends, and how much of each of its four walls survived.
     *
     * <p>Coverage is a fraction rather than a yes/no because a doorway is not a defect. Cutting a
     * door out of a wall with a later air shape is the intended way to make a building enterable,
     * and it takes a percent or two off one side; a wall that was never built, or that a later shape
     * flattened, takes off nearly all of it. Only the second is a bug, and only a number can tell
     * them apart.
     */
    public record Room(int shapeIndex, double north, double south, double west, double east,
                       int wallCells) {

        public double weakestSide() {
            return Math.min(Math.min(north, south), Math.min(west, east));
        }

        /** Sides that are essentially not there. The two-walled-shell defect, named. */
        public List<String> missingSides(double threshold) {
            List<String> out = new ArrayList<>();
            if (north < threshold) {
                out.add("north");
            }
            if (south < threshold) {
                out.add("south");
            }
            if (west < threshold) {
                out.add("west");
            }
            if (east < threshold) {
                out.add("east");
            }
            return out;
        }
    }

    /**
     * Work out a shape's four walls and how much of each is still standing.
     *
     * <p>The skin is derived from the shape itself rather than from its bounding box, so a round
     * tower is handled the same way as a square keep: at each interior height, the first and last
     * block along each axis <i>of that shape</i> are its walls. Only interior heights count — the
     * floor and the ceiling are not walls, and including them would let a solid lid disguise a
     * missing side.
     */
    private static Room roomOf(BuildShape s, int index, Set<Long> solid) {
        boolean roomish = s.hollow()
                && (s.kind() == BuildShape.Kind.BOX || s.kind() == BuildShape.Kind.CYLINDER)
                && s.width() >= 3 && s.depth() >= 3 && s.height() >= 3;
        if (!roomish) {
            return null;
        }
        Set<Long> own = new HashSet<>();
        s.forEachBlock((x, y, z) -> own.add(key(x, y, z)));

        int[] northHit = {0};
        int[] northAll = {0};
        int[] southHit = {0};
        int[] southAll = {0};
        int[] westHit = {0};
        int[] westAll = {0};
        int[] eastHit = {0};
        int[] eastAll = {0};

        for (int y = s.dy() + 1; y <= s.dy() + s.height() - 2; y++) {
            for (int x = s.originX(); x < s.originX() + s.width(); x++) {
                int lo = Integer.MAX_VALUE;
                int hi = Integer.MIN_VALUE;
                for (int z = s.originZ(); z < s.originZ() + s.depth(); z++) {
                    if (own.contains(key(x, y, z))) {
                        lo = Math.min(lo, z);
                        hi = Math.max(hi, z);
                    }
                }
                if (lo == Integer.MAX_VALUE) {
                    continue;
                }
                northAll[0]++;
                southAll[0]++;
                if (solid.contains(key(x, y, lo))) {
                    northHit[0]++;
                }
                if (solid.contains(key(x, y, hi))) {
                    southHit[0]++;
                }
            }
            for (int z = s.originZ(); z < s.originZ() + s.depth(); z++) {
                int lo = Integer.MAX_VALUE;
                int hi = Integer.MIN_VALUE;
                for (int x = s.originX(); x < s.originX() + s.width(); x++) {
                    if (own.contains(key(x, y, z))) {
                        lo = Math.min(lo, x);
                        hi = Math.max(hi, x);
                    }
                }
                if (lo == Integer.MAX_VALUE) {
                    continue;
                }
                westAll[0]++;
                eastAll[0]++;
                if (solid.contains(key(lo, y, z))) {
                    westHit[0]++;
                }
                if (solid.contains(key(hi, y, z))) {
                    eastHit[0]++;
                }
            }
        }
        int total = northAll[0] + southAll[0] + westAll[0] + eastAll[0];
        if (total == 0) {
            return null;
        }
        return new Room(index,
                ratio(northHit[0], northAll[0]), ratio(southHit[0], southAll[0]),
                ratio(westHit[0], westAll[0]), ratio(eastHit[0], eastAll[0]), total);
    }

    private static double ratio(int hit, int all) {
        return all == 0 ? 1.0 : (double) hit / all;
    }

    // ===================== the caps =====================

    /**
     * Dimensions and offsets sitting exactly on a limit.
     *
     * <p>Suggestive of a clamp and <b>not proof of one</b>: asking for exactly 96 is legal. Real
     * proof needs the request as the agent wrote it, which is what
     * {@link BuildPlan#clampNotes(List)} compares against. This is the signal available when only
     * the built plan survives — as it does for every plan recovered from a world save.
     */
    public static List<String> capBoundaries(BuildPlan plan) {
        List<String> hits = new ArrayList<>();
        for (int i = 0; i < plan.shapes().size(); i++) {
            BuildShape s = plan.shapes().get(i);
            if (s.width() == BuildShape.MAX_DIM || s.height() == BuildShape.MAX_DIM
                    || s.depth() == BuildShape.MAX_DIM) {
                hits.add("shape " + i + " is exactly " + BuildShape.MAX_DIM + " on a side");
            }
            if (Math.abs(s.originX()) == BuildShape.MAX_OFFSET
                    || Math.abs(s.dy()) == BuildShape.MAX_OFFSET
                    || Math.abs(s.originZ()) == BuildShape.MAX_OFFSET) {
                hits.add("shape " + i + " sits exactly " + BuildShape.MAX_OFFSET + " away");
            }
        }
        return hits;
    }
}
