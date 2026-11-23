package com.moddingfromamod.builder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

/**
 * <b>Build spots: a boss on its throne</b>.
 *
 * <p>A building used to have exactly one place in it anything knew about: its ANCHOR, which is
 * its foundations. A creature wished for with a building was conjured there — inside the floor —
 * and the Hot Wheels T-Rex suffocated a minute after the reveal. Now a building carries
 * named {@link BuildSpot}s, a creature takes one with {@code at: "<build>:<spot>"}, and every
 * building has a {@link #FRONT} spot outside its entrance without anybody asking for one.
 *
 * <p>Pure — no Minecraft imports — so every rule here is a row in {@code BuildSpotsTest}.
 */
public final class BuildSpots {

    /** The spot every building has: outside its entrance. */
    public static final String FRONT = "front";

    /** Enough for a throne room, a gate, a roof and the towers; past this it is noise. */
    public static final int MAX_SPOTS = 16;

    /** How far outside the walls the default {@link #FRONT} stands. */
    static final int FRONT_GAP = 2;

    private BuildSpots() {
    }

    // ── "castle:throne" ───────────────────────────────────────────────────────────────────────

    /** A reference to one spot of one building. */
    public record Target(String buildId, String spot) {

        @Override
        public String toString() {
            return buildId + ":" + spot;
        }
    }

    /**
     * {@code "castle:throne"} → (castle, throne); {@code "castle"} alone → (castle, front). Null
     * for nothing at all. A building id keeps its {@code ~owner} fork suffix.
     */
    public static Target parseAt(String at) {
        if (at == null) {
            return null;
        }
        String t = at.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2) {
            t = t.substring(1, t.length() - 1).trim();
        }
        if (t.isEmpty()) {
            return null;
        }
        int colon = t.lastIndexOf(':');
        String build = colon < 0 ? t : t.substring(0, colon).trim();
        String spot = colon < 0 ? FRONT : BuildSpot.normalise(t.substring(colon + 1));
        build = build.replace(' ', '_');
        if (build.isEmpty()) {
            return null;
        }
        return new Target(build, spot.isEmpty() ? FRONT : spot);
    }

    // ── what the agent sent ──────────────────────────────────────────────────────────────────

    /** The spots in a {@code spots} array (bad rows dropped, later names win), at most {@link #MAX_SPOTS}. */
    public static List<BuildSpot> parse(JsonElement spots) {
        Map<String, BuildSpot> out = new LinkedHashMap<>();
        if (spots != null && spots.isJsonArray()) {
            for (JsonElement el : spots.getAsJsonArray()) {
                BuildSpot s = el.isJsonObject() ? BuildSpot.fromJson(el.getAsJsonObject()) : null;
                if (s != null) {
                    out.remove(s.name());
                    out.put(s.name(), s);
                }
            }
        }
        return cap(new ArrayList<>(out.values()));
    }

    /**
     * <b>Spots survive an edit</b> (spec 1): the building's spots, with the ones sent this time
     * replacing those of the same name and new names added, and the named ones removed.
     */
    public static List<BuildSpot> merge(List<BuildSpot> had, List<BuildSpot> sent, Set<String> remove) {
        Map<String, BuildSpot> out = new LinkedHashMap<>();
        for (BuildSpot s : had == null ? List.<BuildSpot>of() : had) {
            out.put(s.name(), s);
        }
        for (BuildSpot s : sent == null ? List.<BuildSpot>of() : sent) {
            out.remove(s.name());
            out.put(s.name(), s);
        }
        if (remove != null) {
            for (String r : remove) {
                out.remove(BuildSpot.normalise(r));
            }
        }
        return cap(new ArrayList<>(out.values()));
    }

    private static List<BuildSpot> cap(List<BuildSpot> l) {
        return l.size() > MAX_SPOTS ? List.copyOf(l.subList(0, MAX_SPOTS)) : List.copyOf(l);
    }

    // ── the building, as cells ───────────────────────────────────────────────────────────────

    /** The finished building's blocks (air carved away), and its bounds. */
    static final class Cells {
        final Map<Long, String> at = new HashMap<>();
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE;
        int y0 = Integer.MAX_VALUE, y1 = Integer.MIN_VALUE;
        int z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;

        static Cells of(BuildPlan plan) {
            Cells c = new Cells();
            plan.forEachBlock((x, y, z, block) -> {
                if (isAir(block)) {
                    c.at.remove(key(x, y, z));
                } else {
                    c.at.put(key(x, y, z), block);
                }
            });
            for (long k : c.at.keySet()) {
                int x = (int) (k >> 42), y = (int) (k << 22 >> 43), z = (int) (k << 43 >> 43);
                c.x0 = Math.min(c.x0, x); c.x1 = Math.max(c.x1, x);
                c.y0 = Math.min(c.y0, y); c.y1 = Math.max(c.y1, y);
                c.z0 = Math.min(c.z0, z); c.z1 = Math.max(c.z1, z);
            }
            return c;
        }

        boolean empty() {
            return at.isEmpty();
        }

        String block(int x, int y, int z) {
            return at.get(key(x, y, z));
        }

        boolean solid(int x, int y, int z) {
            String b = block(x, y, z);
            return b != null && !passable(b);
        }
    }

    static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFF) << 42 | ((long) y & 0x1FFFFF) << 21 | ((long) z & 0x1FFFFF);
    }

    /** Air, or a shape that carves air. */
    public static boolean isAir(String block) {
        String b = block == null ? "" : block.toLowerCase(Locale.ROOT);
        return b.equals("minecraft:air") || b.equals("air") || b.endsWith(":cave_air")
                || b.endsWith(":void_air");
    }

    /** Something a creature walks through: a door, a torch, a flower, a carpet, a sign. */
    static boolean passable(String block) {
        String b = block == null ? "" : block.toLowerCase(Locale.ROOT);
        return b.endsWith("_door") || b.endsWith("torch") || b.endsWith("_sign") || b.endsWith("carpet")
                || b.endsWith("_button") || b.endsWith("pressure_plate") || b.endsWith("_banner")
                || b.endsWith(":ladder") || b.endsWith(":vine") || b.endsWith("_flower")
                || b.endsWith(":short_grass") || b.endsWith(":tall_grass") || b.endsWith(":light");
    }

    /**
     * The finished building's bounds relative to its anchor, {@code {x0, y0, z0, x1, y1, z1}}
     * inclusive, or null when it has no blocks — what a camera fits when something happens TO the
     * building, and what a replay asks "is he looking at it" of.
     */
    public static int[] bounds(BuildPlan plan) {
        Cells c = Cells.of(plan);
        return c.empty() ? null : new int[]{c.x0, c.y0, c.z0, c.x1, c.y1, c.z1};
    }

    // ── the front door ───────────────────────────────────────────────────────────────────────

    /**
     * <b>The {@link #FRONT} spot every building has</b>: {@value #FRONT_GAP} blocks outside its
     * entrance, facing out.
     *
     * <p>The entrance is a DOOR when the building has one at its lowest storey; otherwise the
     * middle of the wall that faces where he stood when it went up ({@code towardDx/Dz}, relative
     * to the anchor — {@code 0,0} when nobody knows, and then the south wall). At ground level, so
     * a creature put there stands beside the building rather than inside its foundations.
     */
    public static BuildSpot defaultFront(BuildPlan plan, int towardDx, int towardDz) {
        Cells c = Cells.of(plan);
        if (c.empty()) {
            return new BuildSpot(FRONT, 0, 0, FRONT_GAP, "south");
        }
        // A door, lowest first — the one he walks in by, not a door to a balcony.
        int[] door = null;
        for (Map.Entry<Long, String> e : c.at.entrySet()) {
            if (!e.getValue().toLowerCase(Locale.ROOT).endsWith("_door")) {
                continue;
            }
            long k = e.getKey();
            int x = (int) (k >> 42), y = (int) (k << 22 >> 43), z = (int) (k << 43 >> 43);
            if (door == null || y < door[1] || (y == door[1] && (x < door[0] || (x == door[0] && z < door[2])))) {
                door = new int[]{x, y, z};
            }
        }
        if (door != null && door[1] <= c.y0 + 2) {
            // Out of the wall the door is nearest.
            int toW = door[0] - c.x0, toE = c.x1 - door[0], toN = door[2] - c.z0, toS = c.z1 - door[2];
            int min = Math.min(Math.min(toW, toE), Math.min(toN, toS));
            if (min == toS) {
                return new BuildSpot(FRONT, door[0], door[1], c.z1 + FRONT_GAP, "south");
            }
            if (min == toN) {
                return new BuildSpot(FRONT, door[0], door[1], c.z0 - FRONT_GAP, "north");
            }
            if (min == toE) {
                return new BuildSpot(FRONT, c.x1 + FRONT_GAP, door[1], door[2], "east");
            }
            return new BuildSpot(FRONT, c.x0 - FRONT_GAP, door[1], door[2], "west");
        }
        double cx = (c.x0 + c.x1) / 2.0, cz = (c.z0 + c.z1) / 2.0;
        double tx = towardDx - cx, tz = towardDz - cz;
        int midX = (int) Math.floor(cx), midZ = (int) Math.floor(cz);
        if (towardDx == 0 && towardDz == 0) {
            tx = 0;
            tz = 1;
        }
        if (Math.abs(tx) > Math.abs(tz)) {
            return tx > 0 ? new BuildSpot(FRONT, c.x1 + FRONT_GAP, c.y0, midZ, "east")
                    : new BuildSpot(FRONT, c.x0 - FRONT_GAP, c.y0, midZ, "west");
        }
        return tz >= 0 ? new BuildSpot(FRONT, midX, c.y0, c.z1 + FRONT_GAP, "south")
                : new BuildSpot(FRONT, midX, c.y0, c.z0 - FRONT_GAP, "north");
    }

    /**
     * Every spot this building has: its own named ones, then the automatic ones it has without
     * asking ({@link #FRONT}, and {@link BuildAutoSpots}' top, inside, the four sides and around)
     * unless it names one of its own the same — what {@code inspect_build} lists and {@code at} can
     * name.
     */
    public static List<BuildSpot> all(BuildPlan plan, int towardDx, int towardDz) {
        List<BuildSpot> out = new ArrayList<>(plan.spots());
        for (BuildSpot auto : automatic(plan, towardDx, towardDz)) {
            if (find(plan.spots(), auto.name()) == null) {
                out.add(auto);
            }
        }
        return out;
    }

    /** The spots this building has without anybody marking them, {@link #FRONT} first. */
    public static List<BuildSpot> automatic(BuildPlan plan, int towardDx, int towardDz) {
        List<BuildSpot> out = new ArrayList<>();
        out.add(defaultFront(plan, towardDx, towardDz));
        out.addAll(BuildAutoSpots.of(plan));
        return out;
    }

    /**
     * <b>The words a child uses for a spot, mapped onto the ones every building has</b>.
     * Only consulted when the building has no spot of that exact name — a castle that marked its
     * own {@code roof} keeps it. {@code beside} is the side turned clockwise from the front, so a
     * thing put next to a house never stands in its doorway; {@code behind} is the side opposite.
     */
    static String alias(String name) {
        return switch (name) {
            case "roof", "on_top", "on", "over", "above", "rooftop", "up_top" -> BuildAutoSpots.TOP;
            case "in", "into", "indoors", "interior", "room" -> BuildAutoSpots.INSIDE;
            case "next_to", "nextto", "by", "side", "near", "nearby", "alongside" -> BESIDE;
            case "back", "rear" -> BEHIND;
            case "door", "entrance", "outside", "doorstep" -> FRONT;
            case "ring", "round", "surrounding" -> BuildAutoSpots.AROUND;
            case "n" -> "north";
            case "e" -> "east";
            case "s" -> "south";
            case "w" -> "west";
            default -> name;
        };
    }

    /** "Next to it": the side turned clockwise from its front, so never across its doorway. */
    public static final String BESIDE = "beside";
    /** "Behind it": the side opposite its front. */
    public static final String BEHIND = "behind";

    /**
     * The spot called {@code name} in this building, or null — its own spot of that name first, then
     * the automatic ones ({@link #FRONT} is always there; see {@link #alias} for the other words).
     */
    public static BuildSpot spot(BuildPlan plan, String name, int towardDx, int towardDz) {
        String n = BuildSpot.normalise(name);
        BuildSpot own = find(plan.spots(), n);
        if (own != null) {
            return own;
        }
        String a = alias(n);
        if (!a.equals(n)) {
            own = find(plan.spots(), a);
            if (own != null) {
                return new BuildSpot(n, own.dx(), own.dy(), own.dz(), own.facing());
            }
        }
        if (FRONT.equals(a)) {
            return defaultFront(plan, towardDx, towardDz);
        }
        if (BESIDE.equals(a) || BEHIND.equals(a)) {
            BuildSpot front = spot(plan, FRONT, towardDx, towardDz);
            String side = BESIDE.equals(a) ? BuildAutoSpots.clockwise(front.facing())
                    : BuildAutoSpots.opposite(front.facing());
            BuildSpot s = find(BuildAutoSpots.sides(Cells.of(plan)), side);
            return s == null ? null : new BuildSpot(a, s.dx(), s.dy(), s.dz(), s.facing());
        }
        Cells c = Cells.of(plan);
        if (c.empty()) {
            return null;
        }
        return find(BuildAutoSpots.of(c), a);
    }

    static BuildSpot find(List<BuildSpot> spots, String name) {
        for (BuildSpot s : spots) {
            if (s.name().equals(name)) {
                return s;
            }
        }
        return null;
    }

    /**
     * The spot names a building offers, for a refusal: {@code throne, roof, front, top, inside, north,
     * east, south, west, around} — its own, then the automatic ones, then the words beside/behind.
     */
    public static String names(BuildPlan plan) {
        List<String> n = new ArrayList<>();
        for (BuildSpot s : all(plan, 0, 0)) {
            n.add(s.name());
        }
        n.add(BESIDE);
        n.add(BEHIND);
        return String.join(", ", n);
    }

    /**
     * Whether a creature put on this spot should STAY there (a boss on his throne, a dragon on the
     * roof) — false for the ones that are only a safe place to stand outside: the front, a side,
     * beside, behind, around.
     */
    public static boolean keepsItsSeat(String spotName) {
        String n = alias(BuildSpot.normalise(spotName));
        return !(FRONT.equals(n) || BESIDE.equals(n) || BEHIND.equals(n)
                || BuildAutoSpots.AROUND.equals(n) || BuildAutoSpots.SIDES.contains(n));
    }

    // ── checking what was sent ───────────────────────────────────────────────────────────────

    /**
     * <b>Spots a creature could not stand on, said before anything is put there.</b> Each spot is
     * read against the finished building: its feet inside a solid block (the commonest mistake —
     * {@code dy} is the seat's own height, not one above it) is LIFTED one block when the block
     * above is free, and said; a spot with nothing under it inside the building's walls, or far
     * outside the building, is a note. Returns the spots as they will be stored.
     */
    public static List<BuildSpot> checked(BuildPlan plan, List<BuildSpot> spots, List<String> notes) {
        Cells c = Cells.of(plan);
        List<BuildSpot> out = new ArrayList<>();
        for (BuildSpot s : spots) {
            BuildSpot at = s;
            if (!c.empty() && c.solid(s.dx(), s.dy(), s.dz())) {
                if (!c.solid(s.dx(), s.dy() + 1, s.dz()) && !c.solid(s.dx(), s.dy() + 2, s.dz())) {
                    at = new BuildSpot(s.name(), s.dx(), s.dy() + 1, s.dz(), s.facing());
                    notes.add("spot '" + s.name() + "' was INSIDE the " + c.block(s.dx(), s.dy(), s.dz())
                            + " at " + s.dx() + "," + s.dy() + "," + s.dz() + " — a spot is where the "
                            + "FEET go, one above the seat, so I moved it up to dy=" + at.dy());
                } else {
                    notes.add("spot '" + s.name() + "' is INSIDE solid " + c.block(s.dx(), s.dy(), s.dz())
                            + " with no room above it — anything put there is moved to the nearest "
                            + "safe place instead. Carve room (air) there, or move the spot");
                }
            } else if (!c.empty() && inside(c, s) && !c.solid(s.dx(), s.dy() - 1, s.dz())
                    && s.dy() > c.y0) {
                notes.add("spot '" + s.name() + "' at " + s.dx() + "," + s.dy() + "," + s.dz()
                        + " has nothing under it in the building — a walker put there falls (or gets a "
                        + "little stone footing). Put its dy one above a floor, seat or roof block");
            }
            if (!c.empty() && (s.dx() < c.x0 - 8 || s.dx() > c.x1 + 8 || s.dz() < c.z0 - 8
                    || s.dz() > c.z1 + 8 || s.dy() > c.y1 + 8 || s.dy() < c.y0 - 4)) {
                notes.add("spot '" + s.name() + "' is well outside the building (it spans "
                        + c.x0 + ".." + c.x1 + ", " + c.y0 + ".." + c.y1 + ", " + c.z0 + ".." + c.z1
                        + ") — spots use the SAME offsets as the shapes");
            }
            out.add(at);
        }
        return out;
    }

    private static boolean inside(Cells c, BuildSpot s) {
        return s.dx() >= c.x0 && s.dx() <= c.x1 && s.dz() >= c.z0 && s.dz() <= c.z1
                && s.dy() <= c.y1;
    }

    /** {@code spots} as the store writes them. */
    public static JsonArray toJson(List<BuildSpot> spots) {
        JsonArray arr = new JsonArray();
        for (BuildSpot s : spots) {
            arr.add(s.toJson());
        }
        return arr;
    }
}
