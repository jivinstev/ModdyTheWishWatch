package com.moddingfromamod.builder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A whole building, described as data: a name and an ordered list of {@link BuildShape}s.
 *
 * <p><b>Named and saveable on purpose.</b> A learned wish template is a list of commands, so
 * without a name the best castle the agent ever builds could never become <i>instant</i> — the
 * child would pay an agent round-trip for it every single time, and the self-improving loop that
 * justifies this whole project would simply not apply to buildings. A saved plan can be re-run by
 * id, so a template can say "build floating_castle" and the offline engine does the rest.
 *
 * <p>Order matters: shapes are placed in sequence, so later ones overwrite earlier ones. That is
 * how you carve a doorway (place the wall, then a small air box through it) and how a roof sits on
 * a tower.
 *
 * <p>Pure — no Minecraft imports — so composition, budgeting and clamping are unit-tested without a
 * game.
 */
public record BuildPlan(String id, String name, List<BuildShape> shapes, String ownerId,
                        List<BuildSpot> spots) {

    /**
     * A building with no named spots — every building written before them, and every
     * caller that only knows shapes. It still has its default {@code front} ({@link BuildSpots}).
     */
    public BuildPlan(String id, String name, List<BuildShape> shapes, String ownerId) {
        this(id, name, shapes, ownerId, List.of());
    }

    /**
     * Every building written before owner stamps, and every caller that does not know whose it is.
     *
     * <p>An extra constructor rather than a changed one: sixty-odd call sites build a plan and
     * exactly one production path knows whose wish it is. An empty stamp means SHARED
     * ({@link com.moddingfromamod.marshal.ArtifactOwner}), which is what every building on disk is.
     */
    public BuildPlan(String id, String name, List<BuildShape> shapes) {
        this(id, name, shapes, "");
    }

    /** The same building, stamped for a child. */
    public BuildPlan withOwner(String owner) {
        return new BuildPlan(id, name, shapes, owner, spots);
    }

    /** The same building with these shapes — its owner and its spots kept. */
    public BuildPlan withShapes(List<BuildShape> newShapes) {
        return new BuildPlan(id, name, newShapes, ownerId, spots);
    }

    /** The same building with these named spots. */
    public BuildPlan withSpots(List<BuildSpot> newSpots) {
        return new BuildPlan(id, name, shapes, ownerId, newSpots);
    }

    /** The same building under a new id — the child's fork of a shared or curated one. */
    public BuildPlan forkedAs(String newId, String owner) {
        return new BuildPlan(newId, name, shapes, owner, spots);
    }

    /** Enough for a detailed castle; beyond this an agent is describing noise, not a building. */
    public static final int MAX_SHAPES = 64;

    /**
     * The hard ceiling on one build.
     *
     * <p>Chosen from a real measurement in a sibling project: a monument-scale castle is ~550k
     * block placements, which in a single tick stalls the server for seconds and can OOM a modest
     * heap. This is comfortably under that even before the per-tick slicing, so a runaway plan is
     * refused outright rather than merely spread out.
     */
    public static final int MAX_BLOCKS = 220_000;

    /**
     * <b>At most this much TNT in one building</b>. A wish once built a volcano "PACKED with
     * TNT — over 47,000 blocks", the child lit it, and one server tick ran past 60 s: Minecraft's
     * watchdog stopped the server, the lit TNT was SAVED, and it crash-looped three times in six
     * minutes. 2,000 is what goes off IN FULL under the world's ceiling of lit TNT
     * ({@code TntBudget.MAX_LIVE} = 2,048, measured — a bigger pile would mostly just vanish, so there
     * is no point building it); the world's own backstop ({@code PrimedTntLimit}) covers TNT placed
     * by hand or by a command. The first cut (500) was a guess.
     */
    public static final int MAX_TNT = 2000;

    /** Block ids that ARE tnt (a modded "tnt" block is the same bomb). Pure. */
    static boolean isTnt(String block) {
        String b = block == null ? "" : block.toLowerCase(Locale.ROOT);
        int colon = b.indexOf(':');
        String path = colon >= 0 ? b.substring(colon + 1) : b;
        return path.equals("tnt") || path.endsWith("_tnt") || path.startsWith("tnt_");
    }

    /**
     * How many TNT blocks the FINISHED building holds — later shapes overwrite earlier ones, so an
     * air shape carved through a TNT core counts as carved. Counted exactly only when the shapes'
     * own sum could be over the budget, so an ordinary building never pays for it.
     */
    public int tntCount() {
        int upper = 0;
        for (BuildShape s : shapes) {
            if (isTnt(s.block())) {
                upper += s.volume();
            }
        }
        if (upper <= MAX_TNT) {
            return upper;
        }
        java.util.Map<Long, Boolean> at = new java.util.HashMap<>();
        forEachBlock((x, y, z, block) ->
                at.put(((long) x & 0x1FFFFF) << 42 | ((long) y & 0x1FFFFF) << 21 | ((long) z & 0x1FFFFF), isTnt(block)));
        int n = 0;
        for (boolean t : at.values()) {
            if (t) {
                n++;
            }
        }
        return n;
    }

    public boolean fitsTntBudget() {
        return tntCount() <= MAX_TNT;
    }

    /** The refusal, in words the agent can pass straight on to a child. */
    public String tntRefusal() {
        return "That building has " + tntCount() + " TNT blocks and the limit is " + MAX_TNT
                + ". Thousands of TNT going off at once freezes the whole server — it stops, "
                + "everybody is thrown out, and the lit TNT is saved and goes off again when it comes "
                + "back. Build it with at most " + MAX_TNT + " TNT (a layer or a core, not a solid "
                + "fill — hollow the TNT shapes) and tell him it is still a HUGE bang.";
    }

    /** Generated shapes (a re-walked stair's treads) a plan may carry besides the {@link #MAX_SHAPES} written ones. */
    public static final int MAX_GENERATED = 448;

    /** How many of these somebody wrote: the count {@link #MAX_SHAPES} is about. */
    public static int written(List<BuildShape> shapes) {
        return shapes == null ? 0 : (int) shapes.stream().filter(s -> !s.gen()).count();
    }

    /** At most {@link #MAX_SHAPES} written shapes (the first ones) and {@link #MAX_GENERATED} generated, in order. */
    private static List<BuildShape> capped(List<BuildShape> shapes) {
        if (shapes == null) {
            return List.of();
        }
        if (shapes.size() <= MAX_SHAPES) {
            return List.copyOf(shapes);
        }
        List<BuildShape> out = new ArrayList<>();
        int wrote = 0;
        int made = 0;
        for (BuildShape s : shapes) {
            if (s.gen() ? made++ < MAX_GENERATED : wrote++ < MAX_SHAPES) {
                out.add(s);
            }
        }
        return List.copyOf(out);
    }

    public BuildPlan {
        id = id == null ? "" : id.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        // a building the agent did not name is called what its id says ("Giant Volcano"),
        // never the word "Building" -- and a name that IS that old placeholder, saved before this,
        // is treated as unnamed, so the two volcanoes already on a shelf read properly too.
        name = (name == null || name.isBlank() || name.trim().equals(UNNAMED))
                ? nameFromId(id) : name.trim();
        if (name.length() > 48) {
            name = name.substring(0, 48);
        }
        shapes = capped(shapes);
        ownerId = com.moddingfromamod.marshal.ArtifactOwner.normalise(ownerId);
        List<BuildSpot> sp = new ArrayList<>();
        for (BuildSpot s : spots == null ? List.<BuildSpot>of() : spots) {
            if (s != null && s.isValid() && sp.size() < BuildSpots.MAX_SPOTS
                    && sp.stream().noneMatch(o -> o.name().equals(s.name()))) {
                sp.add(s);
            }
        }
        spots = List.copyOf(sp);
    }

    /** What an unnamed building used to be called, kept only so a saved one is recognised. */
    static final String UNNAMED = "Building";

    /**
     * a readable name from an id — {@code giant_ocean_volcano} → "Giant Ocean Volcano". A
     * fork's {@code ~owner} suffix is not part of the name; an id with no words at all falls back
     * to the placeholder.
     */
    static String nameFromId(String id) {
        String base = com.moddingfromamod.marshal.ArtifactOwner.baseOf(id == null ? "" : id);
        StringBuilder out = new StringBuilder();
        for (String word : base.split("[_\\-\\s]+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.length() == 0 ? UNNAMED : out.toString();
    }

    public boolean isValid() {
        return !id.isEmpty() && !shapes.isEmpty();
    }

    /** Exactly how many blocks this will place — counted, never estimated. */
    public int volume() {
        int total = 0;
        for (BuildShape s : shapes) {
            total += s.volume();
            if (total > MAX_BLOCKS) {
                return total;   // no point counting further; the caller only needs "too big"
            }
        }
        return total;
    }

    public boolean fitsBudget() {
        return volume() <= MAX_BLOCKS;
    }

    /**
     * The REAL total, with no early exit — what a refusal must quote.
     *
     * <p>{@link #volume()} stops counting the moment it is over the ceiling, which is right for
     * {@link #fitsBudget()} and wrong for anything a reader acts on: a plan of 200k + 30k + 500k
     * reports 230k and hides half a million blocks behind the shape that crossed the line. That
     * understates "you need to lose N" by however much came after it, so the agent shaves the
     * number it was given, is refused again, and is back 's eight-call oscillation through
     * a different door.
     *
     * <p>Costs a full pass over every shape ({@link BuildShape#volume()} walks its blocks), which
     * is exactly why the two are separate — but the refusal path already pays it to sort the
     * breakdown, so it is free where it is needed and never charged on the hot path.
     */
    public int exactVolume() {
        int total = 0;
        for (BuildShape s : shapes) {
            total += s.volume();
        }
        return total;
    }

    /**
     * <b>Which shapes are eating the budget</b>, biggest first —.
     *
     * <p>Measured on the buildings ladder: eight consecutive refusals on ONE rung went
     * 222,182 → 220,862 → 220,750 → 221,374 → 220,102 → 220,582 → 220,046 → 220,158 against a
     * 220,000 ceiling — oscillating, never getting under, because the refusal said only the total.
     * The agent was shaving blind. Twenty-three size refusals across a six-cell matrix.
     *
     * <p>Naming the biggest shapes and what they would save turns that into one edit. Reports the
     * few that matter rather than all 64: a list nobody reads is the same as no list.
     */
    public String budgetBreakdown() {
        List<BuildShape> biggest = new java.util.ArrayList<>(shapes);
        biggest.sort((a, b) -> Integer.compare(b.volume(), a.volume()));
        StringBuilder sb = new StringBuilder();
        int over = exactVolume() - MAX_BLOCKS;
        int shown = Math.min(3, biggest.size());
        for (int i = 0; i < shown; i++) {
            BuildShape s = biggest.get(i);
            sb.append(i == 0 ? " Biggest: " : "; ")
              .append('\'').append(s.name() == null || s.name().isBlank() ? s.kind() : s.name())
              .append('\'').append(' ').append(s.volume()).append(" blocks")
              .append(s.hollow() ? " (already hollow)" : " (SOLID — hollow it and it nearly vanishes)");
        }
        if (over > 0) {
            sb.append(". You need to lose ").append(over).append(" more.");
        }
        return sb.toString();
    }

    /** Every block of the whole building, in shape order so later shapes overwrite earlier ones. */
    public void forEachBlock(BlockSink sink) {
        for (BuildShape s : shapes) {
            s.forEachBlock((x, y, z) -> sink.at(x, y, z, s.block()));
        }
    }

    /** Receives a position and which block goes there. */
    public interface BlockSink {
        void at(int x, int y, int z, String block);
    }

    /** Every distinct block id used, so they can be validated against the registry before building. */
    public List<String> blockIds() {
        List<String> out = new ArrayList<>();
        for (BuildShape s : shapes) {
            if (!out.contains(s.block())) {
                out.add(s.block());
            }
        }
        return out;
    }

    // ===================== JSON =====================

    /**
     * One shape exactly as the agent asked for it, BEFORE {@link BuildShape}'s compact constructor
     * clamps it. Plain ints on purpose: a {@code BuildShape} cannot hold an out-of-range value,
     * which is precisely what we need to observe.
     */
    public record Requested(int dx, int dy, int dz, int width, int height, int depth, String block) {
    }

    /**
     * <b>What we quietly changed about what was asked for.</b>
     *
     * <p>Three of this class's four caps were SILENT. {@code MAX_BLOCKS} refuses with a sentence,
     * but {@code MAX_SHAPES} dropped shapes 65+ on the floor and {@code MAX_DIM} /
     * {@code MAX_OFFSET} clamped sizes and offsets, all without telling anyone. Ask for a
     * 150-block tower and you got 96; ask for an eighty-part castle and the last sixteen parts —
     * which is where the roof and the detail are, because those get authored last — simply never
     * appeared. To a child that is not a cap, it is a broken build; and the agent had no idea
     * anything had happened, so it confidently said "done".
     *
     * <p>This is the repo's own "no silent caps" rule, which the block budget already keeps.
     *
     * @return human-readable notes, empty when the plan came out exactly as asked
     */
    public List<String> clampNotes(List<Requested> requested) {
        List<String> notes = new ArrayList<>();
        if (requested == null) {
            return notes;
        }
        if (requested.size() > shapes.size()) {
            notes.add((requested.size() - shapes.size()) + " of your " + requested.size()
                    + " shapes were DROPPED — a building can have at most " + MAX_SHAPES
                    + ". The ones dropped are the LAST ones, which is usually the roof and the "
                    + "detail, so the building comes out looking unfinished.");
        }
        for (int i = 0; i < shapes.size() && i < requested.size(); i++) {
            Requested want = requested.get(i);
            BuildShape got = shapes.get(i);
            if (want.width() != got.width() || want.height() != got.height()
                    || want.depth() != got.depth()) {
                notes.add("shape " + i + " (" + got.block() + ") was made smaller: asked for "
                        + want.width() + "x" + want.height() + "x" + want.depth() + ", built "
                        + got.width() + "x" + got.height() + "x" + got.depth()
                        + " — nothing may exceed " + BuildShape.MAX_DIM + " per side");
            }
            if (want.dx() != got.dx() || want.dy() != got.dy() || want.dz() != got.dz()) {
                notes.add("shape " + i + " (" + got.block() + ") was moved closer: asked to sit at "
                        + want.dx() + "," + want.dy() + "," + want.dz() + ", placed at "
                        + got.dx() + "," + got.dy() + "," + got.dz()
                        + " — nothing may sit more than " + BuildShape.MAX_OFFSET + " away");
            }
        }
        return notes;
    }

    /** True when shapes were dropped outright — a different severity from being resized. */
    public boolean droppedShapes(List<Requested> requested) {
        return requested != null && requested.size() > shapes.size();
    }

    /** The shapes exactly as asked for, unclamped, so they can be compared with what was built. */
    public static List<Requested> shapesAsRequested(JsonObject o) {
        List<Requested> raw = new ArrayList<>();
        if (o != null && o.has("shapes") && o.get("shapes").isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray("shapes")) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject so = el.getAsJsonObject();
                raw.add(new Requested(
                        intOr(so, "dx", 0), intOr(so, "dy", 0), intOr(so, "dz", 0),
                        intOr(so, "width", 1), intOr(so, "height", 1), intOr(so, "depth", 1),
                        str(so, "block")));
            }
        }
        return raw;
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "minecraft:stone";
    }

    private static int intOr(JsonObject o, String k, int fallback) {
        try {
            JsonElement e = o.get(k);
            return e != null && e.isJsonPrimitive() ? e.getAsInt() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public static BuildPlan fromJson(JsonObject o) {
        if (o == null) {
            return null;
        }
        List<BuildShape> shapes = new ArrayList<>();
        if (o.has("shapes") && o.get("shapes").isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray("shapes")) {
                if (el.isJsonObject()) {
                    BuildShape s = BuildShape.fromJson(el.getAsJsonObject());
                    if (s != null) {
                        shapes.add(s);
                    }
                }
            }
        }
        BuildPlan plan = new BuildPlan(
                o.has("id") && o.get("id").isJsonPrimitive() ? o.get("id").getAsString() : "",
                o.has("name") && o.get("name").isJsonPrimitive() ? o.get("name").getAsString() : "",
                shapes,
                com.moddingfromamod.marshal.ArtifactOwner.of(o),
                BuildSpots.parse(o.get("spots")));
        return plan.isValid() ? plan : null;
    }

    /**
     * A plan read back from a STORE (a saved building, a library {@code build.json}): its block ids upgraded for the
     * Minecraft this build runs ({@link com.moddingfromamod.compat.BlockRenames}, by the record's {@code mc} marker),
     * then parsed as {@link #fromJson} does. Throws {@link IllegalArgumentException} when the marker is not a version.
     * Arguments a tool was handed are not stored and go through {@link #fromJson}.
     */
    public static BuildPlan fromStored(JsonObject o) {
        return fromJson(com.moddingfromamod.compat.BlockRenames.plan(o, com.moddingfromamod.compat.Target.MINECRAFT));
    }

    /** {@link #toJson} with the marker a store writes: the Minecraft version this plan's block ids are spelled for. */
    public JsonObject toStoredJson() {
        return com.moddingfromamod.compat.BlockRenames.stamp(toJson(), com.moddingfromamod.compat.Target.MINECRAFT);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", name);
        JsonArray arr = new JsonArray();
        for (BuildShape s : shapes) {
            arr.add(s.toJson());
        }
        o.add("shapes", arr);
        // only when it has any, so a building without named spots stays byte-identical.
        if (!spots.isEmpty()) {
            o.add("spots", BuildSpots.toJson(spots));
        }
        // Only when there is one, so a building written before owner stamps stays byte-identical.
        com.moddingfromamod.marshal.ArtifactOwner.stamp(o, ownerId);
        return o;
    }
}
