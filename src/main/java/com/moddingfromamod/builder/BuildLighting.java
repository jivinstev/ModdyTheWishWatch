package com.moddingfromamod.builder;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>A building a child can go inside should not be pitch dark.</b>
 *
 * <p>Nothing anywhere told the agent to light an interior — not the tool schema, not a single
 * lesson file. A real child had to spend one of his five wishes on it:
 *
 * <blockquote><i>"This is AMAZING! But it probably needs to be brighter inside this castle! Maybe
 * some candles or chandelier or lanterns"</i></blockquote>
 *
 * <p>Asked, the agent lights a castle beautifully. Unasked, it builds a beautiful dark box. That is
 * a default, not a capability, and the cost is not only aesthetic: <b>hostile mobs spawn at light
 * level 0</b>, so an unlit castle is a creeper farm in the middle of what a child is proudest of,
 * and he will never connect the two.
 *
 * <h2>Why it adds light rather than only asking for it</h2>
 * The lesson and the schema hint are the first line and they go in too. This is the backstop, for
 * the same reason the geometry critique exists: <b>the agent cannot see its own building</b>, so
 * "is it dark in there" is not a question it can answer by looking.
 *
 * <p>It is deliberately timid. It fires only when there is an enclosed room and <b>no light-emitting
 * block anywhere in the plan</b> — one lantern the agent placed itself means it was thinking about
 * light, and this stays out of the way. It never removes anything, never replaces a block, and
 * spends at most {@link #MAX_LIGHTS} of the shape budget. And it is <b>reported, never silent</b>:
 * this codebase's own rule is that a cap or an edit nobody is told about is how a child ends up
 * with a building nobody meant.
 *
 * <p>Pure and Minecraft-free, so all of it is a unit test.
 */
public final class BuildLighting {

    /**
     * The most lights it will add.
     *
     * <p>A shape each, out of a budget of {@link BuildPlan#MAX_SHAPES}. Eight is enough to light a
     * castle's rooms without crowding out the roof and the detail, which are authored last and are
     * the first things a shape cap eats.
     */
    public static final int MAX_LIGHTS = 8;

    /** One lantern per this much floor, so a big hall gets lit like a big hall. */
    public static final int AREA_PER_LIGHT = 120;

    /**
     * Even a scaled count stops here.
     *
     * <p>Not a taste limit — every lantern is a shape, and the 64-shape budget is what the flat cap
     * was protecting in the first place. Spending it all on light would refuse the building.
     */
    public static final int MAX_LIGHTS_CEILING = 24;

    /** How far apart to space them, so one lantern is not doing the work of a room. */
    public static final int SPACING = 9;

    /** What it hangs. A lantern reads as deliberate; glowstone reads as a mistake in a wall. */
    public static final String LIGHT_BLOCK = "minecraft:lantern";

    private BuildLighting() {
    }

    /**
     * Is there a light of the building's own within {@link #SPACING} of here?
     *
     * <p><b>Asked of the building as it will stand ({@link ComposedPlan}), never of the light
     * shapes as drawn.</b> A light shape a later shape overwrites gives no light: an earlier issue's
     * castle laid its island over most of its lava moat, this counted every moat cell anyway, so
     * every floor cell up to y=8 read as lit and the lanterns went on the lid one storey above the
     * throne room. Measured over every build with a light shape, 14 of 37 had light cells
     * overwritten. A light also has to be ON: a candle, a lamp or a copper bulb placed unlit is
     * not a light ({@link DarkInterior#emission}).
     *
     * <p>There is deliberately no overload taking a {@link BuildPlan}: it would compose the whole
     * building once per question, and the callers ask it per column.
     */
    public static boolean litNearby(ComposedPlan built, int x, int y, int z) {
        return built != null && built.lightWithin(x, y, z, SPACING);
    }

    /**
     * Lantern shapes to append, or empty when none are wanted.
     *
     * <p>Empty when nothing about the plan encloses a space, when there is no room in the shape
     * budget, or when every spot inside already has a light of the building's own near it.
     */
    public static List<BuildShape> lightsFor(BuildPlan plan) {
        return lightsFor(ComposedPlan.of(plan));
    }

    /** The same, from a building already composed — what {@code build_structure} passes. */
    public static List<BuildShape> lightsFor(ComposedPlan built) {
        List<BuildShape> out = new ArrayList<>();
        BuildPlan plan = built == null ? null : built.plan();
        if (plan == null || plan.shapes().isEmpty()) {
            return out;
        }
        // SCALED TO THE BUILDING, not a flat 8 —. A 52-wide keep got 8 lanterns, which is one
        // per ~350 square blocks, and photographed at brightness 3–10 out of 255. The flat cap
        // existed to protect the 64-shape budget, and THAT is the real constraint — so the count now
        // follows the floor area and is still clamped by the shapes actually left.
        BuildFraming.Bounds sized = BuildFraming.bounds(built);
        int wanted = MAX_LIGHTS;
        if (sized != null) {
            long area = (long) (sized.maxX() - sized.minX() + 1) * (sized.maxZ() - sized.minZ() + 1);
            wanted = (int) Math.max(MAX_LIGHTS, Math.min(MAX_LIGHTS_CEILING, area / AREA_PER_LIGHT));
        }
        int budget = Math.min(wanted, BuildPlan.MAX_SHAPES - plan.shapes().size());
        if (budget <= 0) {
            return out;                       // no room; the agent is told instead
        }
        BuildGeometry g = BuildGeometry.of(built);
        if (g.isEmpty() || g.rooms().isEmpty()) {
            return out;                       // nothing enclosed, so nothing to be dark inside
        }
        BuildFraming.Bounds b = sized;
        if (b == null) {
            return out;
        }
        // STAND THEM ON THE FLOOR, not the ceiling —, and this is the bigger half of it.
        //
        // Light falls off one level per block. A glowstone slab in the ceiling of a 27-block hall
        // arrives at head height at nothing, which is exactly what the buildshot gate photographed:
        // exteriors 170–202, interiors 3–10. Scanning UP from the floor puts the lantern where a
        // person actually stands, and a lantern resting on a solid floor is a legal placement,
        // where one floating mid-air is not.
        Set<String> taken = new LinkedHashSet<>();
        for (int y = b.minY() + 1; y <= b.maxY() - 1 && out.size() < budget; y++) {
            for (int x = b.minX() + SPACING / 2; x <= b.maxX() && out.size() < budget; x += SPACING) {
                for (int z = b.minZ() + SPACING / 2;
                        z <= b.maxZ() && out.size() < budget; z += SPACING) {
                    if (g.isSolid(x, y, z) || !g.isSolid(x, y - 1, z)) {
                        continue;             // needs air here and a floor to stand on
                    }
                    if (!enclosed(g, x, y, z, b)) {
                        continue;             // open air, not a room
                    }
                    if (litNearby(built, x, y, z)) {
                        continue;             // the agent lit this part itself; stay out of the way
                    }
                    if (taken.add(x + ":" + z)) {
                        out.add(new BuildShape(BuildShape.Kind.BOX, x, y, z, 1, 1, 1,
                                LIGHT_BLOCK, false, BuildShape.Anchor.CORNER, "light_" + out.size()));
                    }
                }
            }
        }
        return out;
    }

    /**
     * Is this pocket of air actually inside something?
     *
     * <p>Cheap ray test on the four sides: a cell with a wall in every horizontal direction is in a
     * room. Without it, a lantern gets hung under any overhang — a porch roof, a bridge — which is
     * not wrong exactly, but it is not what "light the inside" means, and it spends the budget.
     */
    private static boolean enclosed(BuildGeometry g, int x, int y, int z, BuildFraming.Bounds b) {
        return hits(g, x, y, z, 1, 0, b) && hits(g, x, y, z, -1, 0, b)
                && hits(g, x, y, z, 0, 1, b) && hits(g, x, y, z, 0, -1, b);
    }

    private static boolean hits(BuildGeometry g, int x, int y, int z, int dx, int dz,
                                BuildFraming.Bounds b) {
        int cx = x;
        int cz = z;
        for (int step = 0; step < 128; step++) {
            cx += dx;
            cz += dz;
            if (cx < b.minX() || cx > b.maxX() || cz < b.minZ() || cz > b.maxZ()) {
                return false;
            }
            if (g.isSolid(cx, y, cz)) {
                return true;
            }
        }
        return false;
    }

    /**
     * What to tell the agent, or "" when nothing was added. Never silent — that is the rule.
     *
     * <p><b>The plan is required, and the one-argument convenience that used to exist is gone
     *.</b> It took no plan, so it could not say how big the room was per light, and it could
     * not say whether anyone can get into the room it just lit. Both call sites in
     * {@code BuildStructureTool} used that overload and neither of the two facts had ever reached
     * an agent — the informative half was written, unit-tested, and dead. Deleting the overload is
     * the guard: there is no longer a quiet version to pick by accident, and a caller that drops
     * the plan is now a compile error rather than a silently poorer note.
     *
     * <h2>Why lighting has to mention a sealed room at all</h2>
     *
     * <p> measured 13 of 19 real buildings enclosing a room with no way in, and named the
     * reason it was never caught: <i>every other signal reports a sealed building as HEALTHIER.</i>
     * It has four walls, it has a solid lid — and this method fires <b>precisely</b> when a
     * building encloses a space, so it hangs lanterns in the unreachable room and then tells the
     * agent that a building <i>"a child walks into"</i> is now bright. That sentence is false about
     * a sealed building, and it is false in the flattering direction, which is the one that gets
     * believed. So when the space it lit has no way in, the note says so in the same breath rather
     * than leaving {@code build_structure}'s separate ⚠ SEALED line to argue with it.
     *
     * <p>It still lights the room. Removing the lanterns would make a deliberately sealed vault
     * worse and would make an accidental seal invisible the moment someone cuts a door; the defect
     * is the CLAIM, not the light — the same shape as, where the resolver's bug was never that
     * it guessed but that it announced the guess as fact.
     *
     * <p>Costs a second flood fill per call, alongside the one {@code geometrySuffix} does. Bounded
     * by {@code BuildGeometry.MAX_FLOOD_VOLUME}, which is what makes that acceptable rather than
     * merely convenient.
     */
    public static String note(List<BuildShape> added, BuildPlan plan) {
        if (added == null || added.isEmpty()) {
            return "";
        }
        String reach = "";
        if (plan != null) {
            BuildGeometry.Sealed sealed = BuildGeometry.of(plan).sealedInterior();
            if (sealed.volume() >= BuildGeometry.SEALED_MIN_ROOM) {
                // "Cut a doorway", singular, was the same overclaim build_structure's own SEALED
                // note made and measured: on a multi-room build one doorway is not the fix,
                // and an agent that does exactly as told is told the same thing again. Fixed in one
                // place and then grepped for in the other, which is what found this one.
                int n = sealed.rooms().size();
                reach = " ⚠ But " + sealed.volume() + " blocks of that inside have NO WAY IN"
                        + (n > 1 ? ", across " + n + " separate rooms," : "") + " so as "
                        + "it stands nobody can see those lanterns — a lit room and a reachable one "
                        + "are different things, and only the first of them is now true. Cut a "
                        + "doorway" + (n > 1 ? " into each" : "")
                        + ", or say plainly that it is meant to be sealed.";
            }
        }
        String scale = "";
        BuildFraming.Bounds b = plan == null ? null : BuildFraming.bounds(plan);
        if (b != null) {
            long area = (long) (b.maxX() - b.minX() + 1) * (b.maxZ() - b.minZ() + 1);
            long per = area / Math.max(1, added.size());
            scale = " That is one lantern per " + per + " square blocks of a "
                    + (b.maxX() - b.minX() + 1) + "-wide building"
                    + (per > AREA_PER_LIGHT * 2
                        ? " — still dim for its size, so add your own light if the room matters."
                        : ".");
        }
        return "\n\nI hung " + added.size() + " lantern" + (added.size() == 1 ? "" : "s")
                + " inside it, because it had no light of its own and a building a child walks into "
                + "should not be pitch dark — unlit rooms also spawn monsters. They are ordinary "
                + "shapes named light_0, light_1 …, so you can move or replace them with something "
                + "that suits it better (a chandelier, glowstone, sea lanterns) by sending shapes "
                + "with those names." + scale + reach;
    }

    // ── a ready-made building's dark rooms ───────────────────────────────────────────────

    /** The most lights {@link #forDark} sets into one building. */
    public static final int MAX_DARK_LIGHTS = 96;

    /**
     * <b>Lights for the rooms that are still dark</b>, for a building WE ship (a Builder ready-made at any size).
     *
     * <p>{@link #lightsFor} is timid on purpose: one light anywhere and it stays out of an agent's design. A
     * ready-made is ours, and a room in it that {@link DarkInterior} calls dark is a fault whatever size it was
     * grown to. So this asks {@link DarkInterior} where the dark floor is and sets a light INTO the floor at the
     * middle of each patch, again and again until nothing is dark. In the floor, because a light there blocks no
     * walk, hangs from nothing, and lights the room below it too. Each is a generated shape, outside the cap.
     */
    public static List<BuildShape> forDark(BuildPlan plan) {
        List<BuildShape> out = new ArrayList<>();
        if (plan == null || plan.shapes().isEmpty()) {
            return out;
        }
        Set<Long> taken = new java.util.HashSet<>();
        for (int round = 0; round < 30 && out.size() < MAX_DARK_LIGHTS; round++) {
            List<BuildShape> all = new ArrayList<>(plan.shapes());
            all.addAll(out);
            ComposedPlan built = ComposedPlan.of(plan.withShapes(all));
            DarkInterior.Report r = DarkInterior.of(built);
            if (r.tooBig() || !r.dark()) {
                break;
            }
            int before = out.size();
            List<int[]> at = new ArrayList<>();
            r.areas().forEach(a -> at.add(new int[] {a.x(), a.y(), a.z()}));
            r.spots().forEach(s -> at.add(new int[] {s.dx(), s.dy(), s.dz()}));
            for (int[] p : at) {
                p = plainFloorNear(built, p);
                if (p == null || out.size() >= MAX_DARK_LIGHTS || !taken.add(ComposedPlan.key(p[0], p[1] - 1, p[2]))) {
                    continue;
                }
                String floor = built.blockAt(p[0], p[1] - 1, p[2]);
                out.add(new BuildShape(BuildShape.Kind.BOX, p[0], p[1] - 1, p[2], 1, 1, 1, floorLight(floor), false,
                        BuildShape.Anchor.CORNER, "floor_light_" + out.size()).generated());
            }
            if (out.size() == before) {
                break;
            }
        }
        return out;
    }

    /**
     * The standing cell nearest {@code p}, on its level, whose floor is a plain building block. A light never takes
     * the place of a thing: a bed, a bookshelf, a chest, a pane, a block of gold. Null when there is none within 6.
     */
    private static int[] plainFloorNear(ComposedPlan built, int[] p) {
        int[] best = null;
        int bestD = Integer.MAX_VALUE;
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                int d = Math.abs(dx) + Math.abs(dz);
                if (d > 6 || d >= bestD) {
                    continue;
                }
                int x = p[0] + dx, z = p[2] + dz;
                String here = built.blockAt(x, p[1], z);
                if ((d > 0 && here != null && !BuildSupport.isAir(here)) || !plainFloor(built, x, p[1] - 1, z)) {
                    continue;
                }
                best = new int[] {x, p[1], z};
                bestD = d;
            }
        }
        return best;
    }

    /** Precious blocks are a thing when they stand alone and a floor when the floor is made of them. */
    private static final String[] PRECIOUS = {"gold", "diamond", "emerald", "lapis", "iron_block", "netherite", "amethyst"};

    private static boolean plainFloor(ComposedPlan built, int x, int y, int z) {
        String b = built.blockAt(x, y, z);
        if (!plainFloor(b)) {
            return false;
        }
        for (String w : PRECIOUS) {
            if (b.contains(w)) {
                return b.equals(built.blockAt(x + 1, y, z)) && b.equals(built.blockAt(x - 1, y, z))
                        && b.equals(built.blockAt(x, y, z + 1)) && b.equals(built.blockAt(x, y, z - 1));
            }
        }
        return true;
    }

    static boolean plainFloor(String block) {
        if (block == null || BuildSupport.isAir(block)) {
            return false;
        }
        String n = block.replaceFirst("\\[.*", "").replaceFirst("^minecraft:", "");
        for (String w : new String[] {"bed", "bookshelf", "chest", "table", "furnace", "barrel", "pane", "glass", "door",
                "stairs", "slab", "anvil", "cauldron", "redstone", "beacon", "spawner", "lectern", "loom", "smoker", "composter", "jukebox", "note_block", "tnt",
                "carpet", "fence", "wall", "ladder", "lantern", "torch", "shroomlight", "glowstone", "froglight", "campfire",
                "water", "lava", "ice", "farmland", "banner", "head", "skull", "pot", "bell", "candle", "cake", "hopper",
                "dispenser", "dropper", "piston", "observer", "rail", "command", "sponge", "melon", "pumpkin"}) {
            if (n.contains(w)) {
                return false;
            }
        }
        return true;
    }

    /** A light that sits well in this floor: warm in wood and earth, pale in pale stone, glowstone otherwise. */
    static String floorLight(String floor) {
        String n = floor == null ? "" : floor.replaceFirst("\\[.*", "").replaceFirst("^minecraft:", "");
        for (String w : new String[] {"planks", "log", "wood", "mushroom", "leaves", "hay", "dirt", "grass", "moss", "stem",
                "wart", "terracotta", "podzol", "mud"}) {
            if (n.contains(w)) {
                return "minecraft:shroomlight";
            }
        }
        for (String w : new String[] {"quartz", "prismarine", "snow", "white", "calcite", "diorite"}) {
            if (n.contains(w)) {
                return "minecraft:sea_lantern";
            }
        }
        return "minecraft:glowstone";
    }
}
