package com.moddingfromamod.wish.stress;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildRegistry;
import com.moddingfromamod.builder.BuildSpot;
import com.moddingfromamod.builder.BuildSpots;
import com.moddingfromamod.builder.Builder;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.vanilla.DisplayRig;
import com.moddingfromamod.vanilla.SpotSeat;
import com.moddingfromamod.wish.WishCorpus;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

/**
 * Stresses {@code kind=placement}: <b>a building and a creature from one wish, the creature put on
 * one of the building's named spots</b> — a boss on his throne, a dragon on the castle
 * roof, a T-Rex guarding the garage ramp, a shopkeeper behind the counter, a pet in its kennel, a
 * knight at the gate.
 *
 * <p>Each case is the case's own building (its shapes and spots, from the corpus) and a plain
 * creature, put through the REAL path a replayed wish takes: the building is queued with
 * {@link Builder#submit} and NOT drained, so the creature has to wait for it
 * ({@link Builder#whenBuilt}, which is what {@code wishmob spawn … at} does); then
 * {@link SpotSeat#spawnOne} — choose the spot, spawn, keep it home, SafeSpot — exactly as the
 * command's own loop does. The claims, from the issue:
 * <ul>
 *   <li>once the building is finished, the creature stands ON the named spot, within one block;</li>
 *   <li>it is not in a wall;</li>
 *   <li>sixty seconds (1200 ticks) later it is still alive and still not in a wall;</li>
 *   <li>and the building is intact — every block the plan placed is still there.</li>
 * </ul>
 * The six-at-a-time staged runner puts these 40 blocks apart in the air above the test plot, so
 * every building carries its own floor; a {@code front} spot outside it stands over air and gets
 * an earlier issue's footing, which is the rule working rather than a fixture fault.
 */
public final class PlacementHarness implements StressHarness.Staged {

    /** Sixty seconds of the creature living in the building. */
    static final int ALIVE_TICKS = 1200;

    @Override
    public String kind() {
        return "placement";
    }

    @Override
    public int settleTicks() {
        return 20;      // the building drains in a tick or two; the seat waits on it
    }

    @Override
    public int stageTicks(WishCorpus.Case c) {
        return 1;
    }

    @Override
    public int processTicks(WishCorpus.Case c) {
        return ALIVE_TICKS;
    }

    /** What the phases share. */
    static final class State {
        String speciesId;
        String buildId;
        BuildPlan plan;
        BlockPos anchor;
        BlockPos spot;
        LivingEntity carrier;
        boolean deferred;
        final List<String> problems = new ArrayList<>();
        final List<String> checked = new ArrayList<>();
    }

    @Override
    public Object place(Context ctx, WishCorpus.Case c) {
        State st = new State();
        var server = ctx.level().getServer();
        JsonObject spec = c.spec();
        String id = "stress_" + c.id();
        st.buildId = id + "_b";
        st.speciesId = id + "_c";
        JsonObject build = spec.getAsJsonObject("build").deepCopy();
        build.addProperty("id", st.buildId);
        BuildPlan plan = BuildPlan.fromJson(build);
        if (plan == null) {
            st.problems.add("the case's building is not a readable plan");
            return st;
        }
        List<String> notes = new ArrayList<>();
        plan = plan.withSpots(BuildSpots.checked(plan, plan.spots(), notes));
        BuildRegistry.put(server, plan);
        st.plan = plan;
        st.anchor = ctx.anchor();
        Species species = Species.fromJson(creatureJson(st.speciesId,
                spec.has("scale") ? spec.get("scale").getAsDouble() : 1.0));
        SpeciesRegistry.put(server, species);

        String spotName = spec.has("at") ? spec.get("at").getAsString() : BuildSpots.FRONT;
        String at = st.buildId + ":" + spotName;
        BuildSpot spot = BuildSpots.spot(plan, spotName, 0, 0);
        if (spot == null) {
            st.problems.add("the case names spot '" + spotName + "' and the building has only "
                    + BuildSpots.names(plan));
            return st;
        }
        String queued = Builder.submit(ctx.level(), ctx.anchor(), plan);
        if (!Builder.isBuilding(st.buildId)) {
            st.problems.add("the building did not go up: " + queued);
            return st;
        }
        int[] toward = BuildRegistry.towardOf(st.buildId);
        spot = BuildSpots.spot(plan, spotName, toward[0], toward[1]);
        st.spot = ctx.anchor().offset(spot.dx(), spot.dy(), spot.dz());
        // THE ORDER A REPLAY HAS: the building is still a queue of blocks when the seat is asked
        // for, so the seat must wait — as `wishmob spawn … at` does.
        st.deferred = Builder.whenBuilt(st.buildId, () -> {
            var placed = SpotSeat.spawnOne(server, null, st.speciesId, at, 0, true);
            st.carrier = placed == null ? null : placed.carrier();
        });
        return st;
    }

    @Override
    public void stage(Context ctx, WishCorpus.Case c, Object state) {
        State st = (State) state;
        if (st == null || !st.problems.isEmpty()) {
            return;
        }
        st.checked.add("a seat waits for its building");
        if (!st.deferred) {
            st.problems.add("the seat did not wait for the building — it was placed while the "
                    + "building was still a queue of blocks");
        }
        st.checked.add(Invariants.MATERIALISES);
        if (st.carrier == null || !st.carrier.isAlive()) {
            st.problems.add("nothing is standing on " + st.buildId + " once it is finished — the "
                    + "seat never ran, or the spawn failed");
            return;
        }
        st.checked.add("the creature stands on the named spot");
        double dx = st.carrier.getX() - (st.spot.getX() + 0.5);
        double dy = st.carrier.getY() - st.spot.getY();
        double dz = st.carrier.getZ() - (st.spot.getZ() + 0.5);
        if (Math.abs(dx) > 1.0 || Math.abs(dy) > 1.0 || Math.abs(dz) > 1.0) {
            st.problems.add(String.format(java.util.Locale.ROOT, "the creature is at %.1f %.1f %.1f, "
                    + "not on the spot %s (off by %.1f %.1f %.1f) — it was put somewhere else",
                    st.carrier.getX(), st.carrier.getY(), st.carrier.getZ(), st.spot.toShortString(), dx, dy, dz));
        }
        st.checked.add("the creature is not in a wall");
        if (st.carrier.isInWall()) {
            st.problems.add("the creature is INSIDE a block the moment it is seated");
        }
    }

    @Override
    public Outcome assertSettled(Context ctx, WishCorpus.Case c, Object state) {
        State st = (State) state;
        Outcome.Builder out = new Outcome.Builder();
        if (st == null) {
            return out.problem("placing the case failed").build();
        }
        st.checked.forEach(out::checked);
        st.problems.forEach(out::problem);
        if (st.carrier == null || st.plan == null) {
            return out.build();
        }
        out.require("the creature is alive after sixty seconds", st.carrier.isAlive() && !st.carrier.isRemoved(),
                "the creature seated on " + st.buildId + " died within " + ALIVE_TICKS + " ticks ("
                        + (st.carrier.isAlive() ? "removed" : "health " + st.carrier.getHealth()) + ")");
        out.require("the creature is still not in a wall", !st.carrier.isInWall(),
                "sixty seconds later the creature is inside a block");
        // The FINISHED building: a later shape overwrites an earlier one (an air door carves a wall).
        java.util.Map<BlockPos, String> want = new java.util.LinkedHashMap<>();
        st.plan.forEachBlock((x, y, z, block) -> want.put(new BlockPos(x, y, z), block));
        int missing = 0, total = 0;
        List<String> where = new ArrayList<>();
        // What BuildRepair opened as it was placed (a doorway made tall enough to walk) is not "gone".
        java.util.Set<Long> repaired = com.moddingfromamod.builder.BuildRepair.of(st.plan).cells().keySet();
        for (var e : want.entrySet()) {
            if (BuildSpots.isAir(e.getValue()) || repaired.contains(com.moddingfromamod.builder.BuildRepair.key(
                    e.getKey().getX(), e.getKey().getY(), e.getKey().getZ()))) {
                continue;
            }
            total++;
            var now = ctx.level().getBlockState(st.anchor.offset(e.getKey()));
            if (now.isAir()) {
                missing++;
                if (where.size() < 4) {
                    where.add(e.getKey().toShortString() + " " + e.getValue());
                }
            }
        }
        out.require("the building is intact", missing == 0,
                missing + " of the building's " + total + " blocks are gone once the creature "
                        + "has lived in it for a minute (e.g. " + where + "; the creature rides a "
                        + st.carrier.getType().toShortString() + ")");
        return out.build();
    }

    @Override
    public void cleanUp(Context ctx, WishCorpus.Case c, Object state) {
        State st = (State) state;
        if (st == null) {
            return;
        }
        if (st.carrier != null) {
            DisplayRig.reap(ctx.level(), st.carrier.getUUID());
            st.carrier.discard();
        }
        if (st.plan != null && st.anchor != null) {
            Builder.clearBuild(ctx.level(), st.anchor, st.plan);
        }
        // and any footing a front spot got in mid-air
        if (st.spot != null) {
            for (BlockPos p : BlockPos.betweenClosed(st.spot.offset(-3, -1, -3), st.spot.offset(3, -1, 3))) {
                if (ctx.level().getBlockState(p).is(net.minecraft.world.level.block.Blocks.STONE)) {
                    ctx.level().setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    /** A plain walker that wanders (speed 0.25), so "stays on its spot" means something. */
    private static JsonObject creatureJson(String id, double scale) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", id);
        o.addProperty("body", "HUMANOID");
        o.addProperty("texture", Species.DEFAULT_TEXTURE);
        o.addProperty("scale", scale);
        o.addProperty("health", 20.0);
        o.addProperty("speed", 0.25);
        o.addProperty("base", "WILD");
        // A friendly creature: a stress case is about where it stands, not whom it bites.
        com.google.gson.JsonArray powers = new com.google.gson.JsonArray();
        powers.add("friendly");
        o.add("powers", powers);
        return o;
    }
}
