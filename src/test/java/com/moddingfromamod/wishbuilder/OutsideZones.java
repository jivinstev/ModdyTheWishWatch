package com.moddingfromamod.wishbuilder;

import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildSupport;
import com.moddingfromamod.builder.WalkProbe;
import com.moddingfromamod.builder.WalkThrough;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * <b>What goes outside, by ZONE</b>. Instead of building every SUBSET of the outside options round every building
 * (a product that doubles with each new option), each option is built ALONE and its ZONE measured: the cells whose block
 * it changes, compared with the same building with nothing outside. Then a pure check proves the zones independent:
 * no two share a cell or stand next to each other, none is on the way in (the cells a child walks through from the door
 * out to the end of the path, at the ground and the two above it), and none is drawn over the building. Options whose
 * zones are independent cannot interfere in any combination, so each one alone being walkable and standing is a proof
 * for every combination, not a sample. A pair that does interact is DECLARED by the test that uses this, and built and
 * checked together.
 *
 * <p>Why that is enough: {@link BuilderBuildings#shapes} lays each option from the body's footprint and front door only,
 * never from another option, so a combination's shapes are the options' shapes concatenated. The global steps after it
 * (the dark-room lights; what goes outside is generated, outside the shape cap) are covered by "everything on", which is checked to equal the bare building
 * overlaid with every option's own change.
 */
final class OutsideZones {

    private OutsideZones() {
    }

    /** Every option but "nothing", in {@link BuilderBuildings#OUTSIDE}'s order. */
    static final List<String> OPTIONS = BuilderBuildings.OUTSIDE.stream().map(BuilderBuildings.Outside::id)
            .filter(id -> !id.equals(BuilderBuildings.NOTHING)).toList();

    /** One building measured: bare, each option alone, and each option's zone. */
    record Measured(String what, BuildPlan bare, Map<Long, String> bareCells, Map<String, BuildPlan> alone,
                    Map<String, Map<Long, String>> aloneCells, Map<String, Set<Long>> zones, Set<Long> lights,
                    Set<String> relit) {
    }

    /** {@code planFor} lowers the building with the given outside (a stored outside string). */
    static Measured measure(String what, Function<String, BuildPlan> planFor) {
        BuildPlan bare = planFor.apply(BuilderBuildings.NOTHING);
        Map<Long, String> bareCells = cells(bare);
        Map<String, BuildPlan> alone = new LinkedHashMap<>();
        Map<String, Map<Long, String>> aloneCells = new LinkedHashMap<>();
        Map<String, Set<Long>> zones = new LinkedHashMap<>();
        Set<Long> lights = new HashSet<>(lights(bare));
        Set<String> relit = new TreeSet<>();
        for (String o : OPTIONS) {
            BuildPlan p = planFor.apply(o);
            Map<Long, String> c = cells(p);
            alone.put(o, p);
            aloneCells.put(o, c);
            Set<Long> mine = lights(p);
            lights.addAll(mine);
            Set<Long> changed = diff(bareCells, c);
            Set<Long> zone = new HashSet<>(changed);
            zone.removeAll(mine);
            zone.removeAll(lights(bare));
            if (zone.size() < changed.size()) {
                relit.add(o);
            }
            zones.put(o, zone);
        }
        return new Measured(what, bare, bareCells, alone, aloneCells, zones, lights, relit);
    }

    /**
     * The dark-room lights {@code BuilderLowering.light} set into the floor after everything else ({@code floor_light_*}):
     * where they go depends on the whole building, the things outside included (a lamp post's lantern lights a room
     * through its door), so they are not part of any option's zone. An option that moves them is RELIT, and says so.
     */
    static Set<Long> lights(BuildPlan p) {
        Set<Long> out = new HashSet<>();
        for (var s : p.shapes()) {
            if (s.name().startsWith("floor_light_")) {
                s.forEachBlock((x, y, z) -> out.add(WalkProbe.key(x, y, z)));
            }
        }
        return out;
    }

    /** The cells {@code everything} differs from the bare building plus each option's own change, the lights aside. */
    static Set<Long> notTheSum(Measured m, BuildPlan everything) {
        Set<Long> off = diff(overlay(m, OPTIONS), cells(everything));
        off.removeAll(m.lights());
        off.removeAll(lights(everything));
        return off;
    }

    /** How many WRITTEN shapes the lowering made: the count {@link BuildPlan#MAX_SHAPES} is about (what goes outside is not one). */
    static int written(com.google.gson.JsonObject args) {
        int n = 0;
        for (var e : args.getAsJsonArray("shapes")) {
            var o = e.getAsJsonObject();
            if (!(o.has("gen") && o.get("gen").getAsBoolean())) {
                n++;
            }
        }
        return n;
    }

    /** The blocks the plan ends up as (the builder's own composition), keyed as {@link WalkProbe#key}. */
    static Map<Long, String> cells(BuildPlan p) {
        return WalkProbe.cells(p);
    }

    /** The cells whose block differs between {@code a} and {@code b} (added, changed or removed). */
    static Set<Long> diff(Map<Long, String> a, Map<Long, String> b) {
        Set<Long> out = new HashSet<>();
        for (Map.Entry<Long, String> e : b.entrySet()) {
            if (!Objects.equals(a.get(e.getKey()), e.getValue())) {
                out.add(e.getKey());
            }
        }
        for (Long k : a.keySet()) {
            if (!b.containsKey(k)) {
                out.add(k);
            }
        }
        return out;
    }

    /**
     * The way in: every column the path lays its ground in, at the ground and the two cells above (y 0..2), measured
     * from the path option's own zone. It is the way in whether or not the path is picked: nothing else may stand on it.
     */
    static Set<Long> entry(Map<String, Set<Long>> zones) {
        Set<Long> out = new HashSet<>();
        for (long k : zones.getOrDefault("path", Set.of())) {
            for (int y = 0; y <= 2; y++) {
                out.add(WalkProbe.key(WalkProbe.kx(k), WalkProbe.ky(k) + 1 + y, WalkProbe.kz(k)));
            }
        }
        return out;
    }

    /** One way two things interfere: {@code a} and {@code b} (another option, "the way in" or "the building"), and how. */
    record Finding(String a, String b, String how) {
        String pair() {
            return a + "+" + b;
        }

        @Override
        public String toString() {
            return a + " and " + b + ": " + how;
        }
    }

    static final String WAY_IN = "the way in";
    static final String BUILDING = "the building";

    /**
     * The pure independence check: for every pair of zones, do they share a cell ("overlap") or stand next to each other
     * ("touch": a cell of one in the 26 around a cell of the other)? Does any but the path's own stand on the way in? Is
     * any drawn over a block of the building? Milliseconds: no building, no walk.
     */
    static List<Finding> interactions(Map<String, Set<Long>> zones, Set<Long> entry, Set<Long> building) {
        List<Finding> out = new ArrayList<>();
        List<String> ids = new ArrayList<>(zones.keySet());
        for (int i = 0; i < ids.size(); i++) {
            Set<Long> a = zones.get(ids.get(i));
            for (int j = i + 1; j < ids.size(); j++) {
                Set<Long> b = zones.get(ids.get(j));
                String how = overlap(a, b) ? "overlap" : touch(a, b) ? "touch" : null;
                if (how != null) {
                    out.add(new Finding(ids.get(i), ids.get(j), how));
                }
            }
            if (!ids.get(i).equals("path") && overlap(a, entry)) {
                out.add(new Finding(ids.get(i), WAY_IN, "overlap"));
            }
            if (overlap(a, building)) {
                out.add(new Finding(ids.get(i), BUILDING, "overlap"));
            }
        }
        return out;
    }

    static boolean overlap(Set<Long> a, Set<Long> b) {
        Set<Long> small = a.size() <= b.size() ? a : b;
        Set<Long> big = small == a ? b : a;
        for (long k : small) {
            if (big.contains(k)) {
                return true;
            }
        }
        return false;
    }

    static boolean touch(Set<Long> a, Set<Long> b) {
        for (long k : a) {
            int x = WalkProbe.kx(k);
            int y = WalkProbe.ky(k);
            int z = WalkProbe.kz(k);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (b.contains(WalkProbe.key(x + dx, y + dy, z + dz))) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** The bare building with each of {@code on}'s own changes laid over it, in order. */
    static Map<Long, String> overlay(Measured m, List<String> on) {
        Map<Long, String> out = new HashMap<>(m.bareCells());
        for (String o : on) {
            Map<Long, String> c = m.aloneCells().get(o);
            for (long k : m.zones().get(o)) {
                String b = c.get(k);
                if (b == null) {
                    out.remove(k);
                } else {
                    out.put(k, b);
                }
            }
        }
        return out;
    }

    /**
     * What the old subset sweep asked of every combination, of one: it added shapes, it costs no room he could walk into
     * (the reachable floor never shrinks and it is never too big to walk when the bare building was not), and it adds
     * nothing that cannot stand. Empty when all hold.
     */
    static List<String> noWorseThanBare(String what, BuildPlan bare, BuildPlan with) {
        List<String> bad = new ArrayList<>();
        if (with.shapes().size() <= bare.shapes().size()) {
            bad.add(what + ": added nothing (bare " + bare.shapes().size() + "/" + BuildPlan.written(bare.shapes()) + " with "
                    + with.shapes().size() + "/" + BuildPlan.written(with.shapes()) + ")");
        }
        WalkThrough.Report before = walk(bare);
        WalkThrough.Report after = WalkThrough.of(with);
        long reachedBefore = reached(before);
        long reached = reached(after);
        if (after.tooBig() != before.tooBig() || reached < reachedBefore) {
            bad.add(what + ": reachable floor " + reachedBefore + " -> " + reached + (after.tooBig() ? " (too big to walk)" : ""));
        }
        int problemsBefore = stand(bare).size();
        var problems = BuildSupport.check(with);
        if (problems.size() > problemsBefore) {
            bad.add(what + ": cannot stand: " + problems.get(problems.size() - 1).describe());
        }
        return bad;
    }

    /** What the old houses sweep asked of every combination: walkable from outside, every room reached, and it stands. */
    static List<String> walkableAndStands(String what, BuildPlan p) {
        List<String> bad = new ArrayList<>();
        WalkThrough.Report r = WalkThrough.of(p);
        if (r.tooBig()) {
            bad.add(what + ": too big to walk");
            return bad;
        }
        for (WalkThrough.Room room : r.unreachable()) {
            bad.add(what + ": " + room.describe() + " — " + room.cause());
        }
        if (r.main() == null || !r.main().reached()) {
            bad.add(what + ": the main room cannot be walked into");
        }
        for (BuildSupport.Problem pr : BuildSupport.check(p)) {
            bad.add(what + ": cannot stand: " + pr.describe());
        }
        return bad;
    }

    private static long reached(WalkThrough.Report r) {
        return r.rooms().stream().filter(WalkThrough.Room::reached).mapToInt(WalkThrough.Room::columns).sum();
    }

    // The bare building is compared against five or six times: walk it and check it once.
    private static final Map<BuildPlan, WalkThrough.Report> WALKED = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<BuildPlan, List<BuildSupport.Problem>> STOOD = new java.util.concurrent.ConcurrentHashMap<>();

    private static WalkThrough.Report walk(BuildPlan p) {
        return WALKED.computeIfAbsent(p, WalkThrough::of);
    }

    private static List<BuildSupport.Problem> stand(BuildPlan p) {
        return STOOD.computeIfAbsent(p, BuildSupport::check);
    }

    /** The pairs found, as "a+b", for comparing with a declared list. */
    static Set<String> pairs(List<Finding> found) {
        Set<String> out = new TreeSet<>();
        found.forEach(f -> out.add(f.pair()));
        return out;
    }
}
