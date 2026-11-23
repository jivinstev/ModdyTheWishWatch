package com.moddingfromamod.vanilla;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildRegistry;
import com.moddingfromamod.builder.BuildSpots;
import com.moddingfromamod.builder.Builder;
import com.moddingfromamod.builder.ReplaySeat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Put a creature on a building's named spot</b> — the boss on his throne, the dragon on
 * the roof. The one place {@code sculpt_creature}, {@code define_creature} and {@code wishmob spawn
 * … at <build>:<spot>} ask where a creature goes.
 *
 * <p>The rule, in order:
 * <ul>
 *   <li>{@code at} names a spot of a building STANDING this run → that spot, in the building's
 *   own world, facing the spot's way;</li>
 *   <li>{@code at} names something that is not standing (no such building, never put up since the
 *   server started, no such spot) → where he aimed, as before, and the agent is told why;</li>
 *   <li>no {@code at}, in a wish that BUILT something → that building's {@code front} — never its
 *   anchor, which is its foundations (the Hot Wheels T-Rex);</li>
 *   <li>otherwise → where he aimed ({@link CreatureSpawn#spotFor}), unchanged.</li>
 * </ul>
 * Wherever it lands, {@link SafePlacement#settleSpawn} then checks it 's {@code SafeSpot}
 * rule: a throne is a solid block with air above, so it passes and the boss stays put.
 */
public final class SpotSeat {

    private static final Logger LOG = LoggerFactory.getLogger("mfam-spots");

    private SpotSeat() {
    }

    /**
     * Where one creature of a spawn goes.
     *
     * @param link  {@code "<build>:<spot>"} when it is on a spot, else null
     * @param note  a clause for the agent ({@code ""} when there is nothing to say)
     * @param stays whether it should stay there (a building's throne or roof) rather than roam
     */
    public record Seat(ServerLevel level, double x, double y, double z, float yaw, String link, String note,
                       boolean stays) {

        public boolean onSpot() {
            return link != null;
        }
    }

    /**
     * Where a named spot is in the world, for {@code wishspot}: the spot's block centre at
     * its feet, its world, its yaw (or 0), and {@code "<build>:<spot>"} — or {@code why} it is not
     * standing. No default here: {@code wishspot} always names its spot.
     */
    public record Place(ServerLevel level, double x, double y, double z, float yaw, String link, String why,
                        String note) {

        Place(ServerLevel level, double x, double y, double z, float yaw, String link, String why) {
            this(level, x, y, z, yaw, link, why, "");
        }
    }

    public static Place place(MinecraftServer server, String at) {
        return place(server, null, at, true);
    }

    /**
     * {@link #place(MinecraftServer, String)} for {@code wishspot}, with option C: a replay
     * nobody is in the loop for takes the spot of that name on the building {@code player} aims at
     * or stands in, and runs where he aims when there is none ({@code why} says so). {@code
     * trustLink} — the same replay just put the building up again — keeps the link as written.
     */
    public static Place place(MinecraftServer server, ServerPlayer player, String at, boolean trustLink) {
        com.moddingfromamod.place.At t = com.moddingfromamod.place.At.parse(at);
        if (t == null) {
            return new Place(null, 0, 0, 0, 0, null,
                    "no place was named (it reads <thing>:<where> — a building, a creature or me)");
        }
        ReplaySeat.Decision d = replay(player, t, trustLink);
        if (d != null && !d.onBuilding()) {
            BlockPos aim = aimOf(player);
            return new Place(player.level(), aim.getX() + 0.5, aim.getY(), aim.getZ() + 0.5,
                    player.getYRot() + 180.0f, null, null, d.note());
        }
        if (d != null) {
            t = new com.moddingfromamod.place.At(d.buildId(), t.where());
        }
        // through the shared finder, so `wishspot` takes anything an `at` can name — a
        // building's own or automatic spot, a creature, or him.
        ServerPlayer child = player != null ? player : com.moddingfromamod.agent.ActingChild.of(server);
        var found = com.moddingfromamod.place.PlaceFinder.find(server, child, t, BuildSpots.FRONT);
        if (!found.ok()) {
            return new Place(null, 0, 0, 0, 0, null, found.why());
        }
        var r = found.r();
        // A ring (`around`) is not a point: one command runs on it on HIS side, never at its centre,
        // which is the building's foundations.
        double start = child == null ? 0
                : Math.toDegrees(Math.atan2(-(child.getX() - r.x()), child.getZ() - r.z()));
        double[] p = com.moddingfromamod.place.PlaceResolver.onRing(r, 0, 1, start, 1.0);
        return new Place(found.level(), p[0], found.feetAt(p[0], p[1]), p[1], Double.isNaN(p[2]) ? 0f : (float) p[2],
                r.link(), null, d == null ? "" : d.note());
    }

    /** The building a seat waits for, or null — what {@link #awaitBuilt} and the command defer on. */
    public static String buildOf(String at) {
        com.moddingfromamod.place.At t = targetFor(at);
        if (t == null || t.isMe()) {
            return null;
        }
        // A building going up is not standing yet, so its id is looked up as written (or his fork).
        for (String n : t.lookups()) {
            if (Builder.isBuilding(n) || BuildRegistry.get(n) != null) {
                return n;
            }
        }
        return t.thing();
    }

    /** {@code at}, or this wish's own building's {@code front} when it names none. */
    static com.moddingfromamod.place.At targetFor(String at) {
        com.moddingfromamod.place.At t = com.moddingfromamod.place.At.parse(at);
        if (t != null) {
            return t;
        }
        var run = com.moddingfromamod.agent.AgentContext.currentRun();
        String built = run == null ? null : run.lastBuild();
        if (built == null || !BuildRegistry.standing(built)) {
            return null;
        }
        return new com.moddingfromamod.place.At(built, BuildSpots.FRONT);
    }

    /**
     * <b>Where the {@code index}th creature goes.</b> Null only when there is no spot to use AND
     * nobody to put it in front of.
     */
    public static Seat choose(MinecraftServer server, ServerPlayer player, String at, String speciesId,
                              int index) {
        return choose(server, player, at, speciesId, index, 0, false);
    }

    /**
     * @param trustLink the same replay has just put the linked building up again, so the link is
     *                  exactly the building he is watching rise: seat it there
     */
    public static Seat choose(MinecraftServer server, ServerPlayer player, String at, String speciesId,
                              int index, boolean trustLink) {
        return choose(server, player, at, speciesId, index, 0, trustLink);
    }

    /**
     * The same, knowing how many come ({@code count}, 0 when unknown): a herd {@code around} a
     * building is spread evenly round it rather than piled at one side.
     */
    public static Seat choose(MinecraftServer server, ServerPlayer player, String at, String speciesId,
                              int index, int count) {
        return choose(server, player, at, speciesId, index, count, false);
    }

    /** {@link #choose} with both the herd's size and {@code trustLink}. */
    public static Seat choose(MinecraftServer server, ServerPlayer player, String at, String speciesId,
                              int index, int count, boolean trustLink) {
        com.moddingfromamod.place.At t = targetFor(at);
        String asked = at == null || at.isBlank() ? "" : at.trim();
        String why = "";
        String replayNote = "";
        //, option C: a REPLAYED seat follows his aim, not the castle the recipe remembers.
        ReplaySeat.Decision d = asked.isEmpty() ? null : replay(player, t, trustLink);
        if (d != null) {
            replayNote = d.note();
            if (!d.onBuilding()) {
                LOG.info("[spots] {} replayed {}: {}", speciesId, t, d.note());
                var spot = CreatureSpawn.spotFor(player, speciesId, index);
                return new Seat(player.level(), spot.x, spot.y, spot.z, player.getYRot() + 180.0f,
                        null, replayNote, false);
            }
            t = new com.moddingfromamod.place.At(d.buildId(), t.where());
        }
        if (t != null) {
            var found = com.moddingfromamod.place.PlaceFinder.find(server, player, t, BuildSpots.FRONT);
            if (found.ok()) {
                var r = found.r();
                double start = player != null
                        ? Math.toDegrees(Math.atan2(-(player.getX() - r.x()), player.getZ() - r.z())) : 0;
                double[] at2 = com.moddingfromamod.place.PlaceResolver.onRing(r, index,
                        count > 0 ? count : 8, start, gap(speciesId));
                float y = !Double.isNaN(at2[2]) ? (float) at2[2]
                        : player != null ? player.getYRot() + 180.0f : 0f;
                if (index == 0) {
                    LOG.info("[spots] {} -> {} at {} {} {}", speciesId, r.link(), (int) Math.floor(r.x()),
                            (int) Math.floor(r.y()), (int) Math.floor(r.z()));
                }
                // A place the agent NAMED is where it belongs: "a robot dog guarding the front of my
                // house" must still be at the front a minute later (measured — the first real
                // grant's guard dog had wandered off by the check). Only the IMPLICIT front of a wish
                // that built something and named no place is somewhere to stand and roam from.
                boolean stays = "building".equals(found.kind())
                        && (!asked.isEmpty() || BuildSpots.keepsItsSeat(r.spot()));
                String note = asked.isEmpty() && found.buildId() != null
                        ? " (This wish built '" + found.buildId() + "', so it stands at its front — pass at:\""
                                + found.buildId() + ":<spot>\" to put it somewhere in it: top, inside, a side, "
                                + "around, or a spot it marked.)"
                        : replayNote;
                return new Seat(found.level(), at2[0], found.feetAt(at2[0], at2[1]), at2[1], y, r.link(), note, stays);
            }
            why = found.why();
            LOG.info("[spots] {} asked for {}: {} -> where he aimed", speciesId, t, why);
        }
        if (player == null) {
            return null;
        }
        var spot = CreatureSpawn.spotFor(player, speciesId, index);
        String note = why.isEmpty() || asked.isEmpty() ? ""
                : " (⚠ It could not take " + asked + ": " + why + ", so it stands where he aimed "
                        + "instead.)";
        return new Seat(player.level(), spot.x, spot.y, spot.z, player.getYRot() + 180.0f, null, note, false);
    }

    /**
     * Option C's decision for a seat, or null when it does not apply: no spot named, nobody to aim,
     * or the agent is in the loop — it read his words, and they win.
     */
    static ReplaySeat.Decision replay(ServerPlayer player, com.moddingfromamod.place.At t, boolean trustLink) {
        if (t == null || player == null || t.isMe() || agentInLoop()) {
            return null;
        }
        // Option C is about a recipe's BUILDING link; a creature or him is found where it is now.
        String linked = knownBuilding(t);
        if (linked == null) {
            return null;
        }
        if (trustLink) {
            return new ReplaySeat.Decision(linked, "");
        }
        String spotName = t.where().isEmpty() ? BuildSpots.FRONT : t.where();
        List<ReplaySeat.Point> aims = new ArrayList<>();
        // His look NOW (getLookAngle), not Entity.pick: on 1.21.1 pick reads last tick's rotation,
        // so one turned or moved this tick looks where he was facing before (measured).
        var eye = player.getEyePosition();
        var hit = player.level().clip(new net.minecraft.world.level.ClipContext(eye,
                eye.add(player.getLookAngle().scale(LOOK_REACH)),
                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        if (hit instanceof net.minecraft.world.phys.BlockHitResult b
                && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            aims.add(new ReplaySeat.Point(b.getBlockPos().getX() + 0.5, b.getBlockPos().getY() + 0.5,
                    b.getBlockPos().getZ() + 0.5));
        }
        BlockPos served = com.moddingfromamod.wish.WishProgress.servedSpot();
        if (served != null) {
            aims.add(new ReplaySeat.Point(served.getX() + 0.5, served.getY() + 0.5, served.getZ() + 0.5));
        }
        var feet = new ReplaySeat.Point(player.getX(), player.getY(), player.getZ());
        return ReplaySeat.decide(linked, spotName, false, standingIn(player.level(), spotName),
                aims, feet);
    }

    /** The building {@code t} names as the recipe wrote it (an id he has, or his fork), else null. */
    static String knownBuilding(com.moddingfromamod.place.At t) {
        for (String n : t.lookups()) {
            if (BuildRegistry.get(n) != null) {
                return n;
            }
        }
        return null;
    }

    /** How far a replay looks along his gaze for the building he means — vanilla's render reach. */
    static final double LOOK_REACH = 128.0;

    /** Is a worker granting a wish on this thread — i.e. the agent read his words? Never the ambient fallback. */
    static boolean agentInLoop() {
        String worker = com.moddingfromamod.agent.AgentContext.workerId();
        return !worker.isEmpty() && com.moddingfromamod.marshal.Marshal.global().runForWorker(worker) != null;
    }

    /** Every building standing in {@code level}, with its world box and whether it has {@code spotName}. */
    static List<ReplaySeat.Candidate> standingIn(ServerLevel level, String spotName) {
        List<ReplaySeat.Candidate> out = new ArrayList<>();
        for (String id : BuildRegistry.ids()) {
            if (!BuildRegistry.standing(id)) {
                continue;
            }
            var dim = BuildRegistry.dimensionOf(id);
            if (!(dim == null ? level.dimension() == net.minecraft.world.level.Level.OVERWORLD
                    : dim.equals(level.dimension()))) {
                continue;
            }
            BuildPlan plan = BuildRegistry.get(id);
            int[] b = plan == null ? null : BuildSpots.bounds(plan);
            if (b == null) {
                continue;
            }
            BlockPos a = BlockPos.of(BuildRegistry.anchorOf(id));
            int[] toward = BuildRegistry.towardOf(id);
            out.add(new ReplaySeat.Candidate(plan.id(), new ReplaySeat.Box(a.getX() + b[0], a.getY() + b[1],
                    a.getZ() + b[2], a.getX() + b[3], a.getY() + b[4], a.getZ() + b[5]),
                    BuildSpots.spot(plan, spotName, toward[0], toward[1]) != null));
        }
        return out;
    }

    /** Where a replay with no building to sit on runs: his served spot, else in front of him. */
    private static BlockPos aimOf(ServerPlayer player) {
        BlockPos served = com.moddingfromamod.wish.WishProgress.servedSpot();
        return served != null ? served : Builder.anchorInFrontOf(player, 3, 0);
    }

    /** How far apart a herd on one spot stands: a block, or its own width. */
    static double gap(String speciesId) {
        var sp = com.moddingfromamod.species.SpeciesRegistry.get(speciesId);
        return Math.max(1.0, sp == null ? 1.0 : Math.ceil(sp.scale()));
    }

    /**
     * <b>Wait, off the server thread, until the building this seat names has finished going
     * up</b> — so a tool's spawn happens after the blocks it sits on, and its reply can still name
     * the creature standing there. Returns at once when nothing is going up; gives up after
     * {@code maxMs} (the creature is then placed anyway, and SafeSpot keeps it out of the walls).
     */
    public static void awaitBuilt(String at, long maxMs) {
        if (net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer() != null
                && net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer().isSameThread()) {
            return;     // never on the server thread: the build cannot finish while we wait
        }
        String build = buildOf(at);
        long until = System.currentTimeMillis() + maxMs;
        while (build != null && Builder.isBuilding(build) && System.currentTimeMillis() < until) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** What one placement did: the creature (null when nothing stands), where, and SafeSpot's word. */
    public record Placed(net.minecraft.world.entity.LivingEntity carrier, Seat seat,
                         SafePlacement.Settled settled) {
    }

    /**
     * <b>Choose, spawn, keep and settle ONE creature</b> — {@link #choose}, then
     * {@link CreatureSpawn#spawn}, then {@link #remember}, then {@link SafePlacement#settleSpawn}.
     * The body of {@code wishmob spawn}'s loop, and what the stress corpus drives. Null when there
     * is nowhere to put it (no spot and nobody to put it in front of).
     */
    public static Placed spawnOne(MinecraftServer server, ServerPlayer player, String speciesId, String at,
                                  int index, boolean first) {
        return spawnOne(server, player, speciesId, at, index, 0, first, false);
    }

    /**
     * The same, knowing the herd's size ({@code count}; 0 when unknown) — see {@link #choose} — and
     * {@code trustLink}, when the same replay has just put the linked building up.
     */
    public static Placed spawnOne(MinecraftServer server, ServerPlayer player, String speciesId, String at,
                                  int index, int count, boolean first, boolean trustLink) {
        Seat seat = choose(server, player, at, speciesId, index, count, trustLink);
        if (seat == null) {
            return null;
        }
        var carrier = CreatureSpawn.spawn(seat.level(), speciesId, seat.x(), seat.y(), seat.z(), seat.yaw(),
                net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        if (carrier == null) {
            return new Placed(null, seat, null);
        }
        remember(carrier, speciesId, seat);
        return new Placed(carrier, seat, SafePlacement.settleSpawn(player, carrier, first));
    }

    /** How far a creature on a named spot strolls from it: it stays on its throne. */
    public static final int HOME_RADIUS = 2;

    /**
     * Record on the wish in flight that this creature sat on this spot (the parts' link), and keep
     * it there: a boss on a NAMED spot strolls at most {@value #HOME_RADIUS} blocks from it
     * (vanilla's home restriction, which every stroll goal asks). The default front is only a safe
     * place to stand, so a creature put there is free to roam.
     */
    public static void remember(net.minecraft.world.entity.LivingEntity carrier, String speciesId, Seat seat) {
        if (seat == null || !seat.onSpot()) {
            return;
        }
        if (carrier instanceof net.minecraft.world.entity.Mob mob && seat.stays()) {
            mob.setHomeTo(BlockPos.containing(seat.x(), seat.y(), seat.z()), HOME_RADIUS);
        }
        var run = com.moddingfromamod.agent.AgentContext.currentRun();
        if (run != null) {
            run.seated(speciesId, seat.link());
        }
    }
}
