package com.moddingfromamod.wish.stress;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * <b>The guardrail assertions, written once.</b>
 *
 * <p>Every wish class owes the same handful of promises — {@code docs/WISH_BUILD_GUARDRAILS.md}
 * P1–P8 — and until now each gate re-implemented whichever ones its author remembered. That is why
 * coverage was uneven and why the same P5 hole (no removal path) shipped twice in one day, once for
 * machines and once for datapacks: each was checked by a different gate that happened not to look.
 *
 * <p>Naming them here does two things. Harnesses get the check for free, and — because
 * {@link StressHarness.Outcome} reports which invariants were <i>checked</i> — a harness that
 * quietly stops checking one becomes visible instead of looking like a pass.
 */
public final class Invariants {

    /** Guardrail names, so the run report and the failure messages agree. */
    public static final String P2_ONE_COPY = "P2 rebuilding leaves exactly one";
    public static final String P4_LOCKED_ANCHOR = "P4 lands at the anchor it was promised";
    public static final String P5_REMOVES_CLEAN = "P5 removal clears everything it placed";
    public static final String P5_NON_DESTRUCTIVE = "P5 removal spares what it did not place";
    public static final String P6_SURVIVES_RELOAD = "P6 design survives a reload";
    public static final String NO_SILENT_CAPS = "caps are reported, never silent";
    public static final String MATERIALISES = "the wish actually appears";
    /** what a building's ground-touching parts stand on. */
    public static final String GROUND_PARTS_STAND_ON_SOMETHING = "a building's ground parts stand on something";
    public static final String GROUND_NOTHING_UNDER_HIGH_PARTS = "nothing is filled under a building's high parts";
    public static final String GROUND_SET_DOWN_WHEN_A_DROP = "a building over a long drop is set down, and only then";
    /** Pictures: a change painted on top of a full picture is the part that survives. */
    public static final String PICTURE_EDIT_KEEPS_CHANGE = "a picture edit keeps what he asked to change";
    /**
     * A room has four walls once the WHOLE plan is placed — not per shape.
     *
     * <p>Named here rather than as a bare string in one harness because the version of this that
     * lived there was a tautology: it examined one hollow box in isolation, where the walls are
     * present by construction, and so reported zero faults over all 4,913 expressible hollow boxes
     * while marking itself as checked on every buildings case. Real enclosure is a property of the
     * composed build, since later shapes overwrite earlier ones and air carves.
     */
    public static final String BUILDINGS_HAVE_FOUR_WALLS = "a room has four walls";
    /** A tall building is not a box with a lid on: it has a roof, a spire or a taper. */
    public static final String BUILDINGS_ARE_FINISHED = "a tower is finished, not a lidded box";
    // The Create machine invariants (a machine turns, a break is localised, the blocks became a
    // contraption, the machine does its job, fluid arrives, a parcel arrives, a block entity says
    // so, the network knows its stock, a composite is joined) went with Create. They are
    // parked on branch parked/create-trains-mc1.21.1.

    private Invariants() {
    }

    /**
     * Drop a witness block just outside a build's footprint and hand back a check for it.
     *
     * <p>P5 says removal is non-destructive — "clears only the creation's own blocks, never
     * terrain". The only way to prove that is to put something there that the creation does not
     * own and confirm it is still there afterwards. Every harness that removes something should
     * use this; it is two lines and it catches the difference between "cleared the footprint" and
     * "cleared the bounding box", which is the difference between tidying up and eating the
     * hillside a castle stood on.
     */
    public static Witness witness(ServerLevel level, BlockPos at) {
        level.setBlock(at, Blocks.DIRT.defaultBlockState(), 3);
        return new Witness(level, at);
    }

    /** A block placed to prove removal did not touch it. */
    public record Witness(ServerLevel level, BlockPos at) {

        public boolean survived() {
            return level.getBlockState(at).is(Blocks.DIRT);
        }

        public String complaint() {
            return "a block at " + at.toShortString() + " that the wish did not place was "
                    + "destroyed by removing it — that is terrain vandalism, not tidying up";
        }
    }

    /**
     * Count how many of a set of positions are still occupied.
     *
     * <p>The shared shape behind "did it build?" and "did it all go away?". Deliberately takes
     * positions rather than an anchor: asking whether the ANCHOR is solid is wrong for any shape
     * whose origin corner is outside itself, which reported a perfectly good cylinder tower as
     * never built and then blamed its own skipped cleanup on the product.
     */
    public static int occupied(ServerLevel level, Iterable<BlockPos> positions) {
        int n = 0;
        for (BlockPos p : positions) {
            if (!level.getBlockState(p).isAir()) {
                n++;
            }
        }
        return n;
    }

    /**
     * P6 in one call: put it in a store, wipe memory, load from disk, is it still there?
     *
     * @param save    persist the design
     * @param wipe    clear the in-memory store, as a world close does
     * @param reload  read it back from disk, as the next session does
     * @param present is it there now?
     */
    public static boolean survivesReload(Runnable save, Runnable wipe, Runnable reload,
                                         Supplier<Boolean> present) {
        save.run();
        wipe.run();
        if (present.get()) {
            return false;   // the wipe did nothing, so the reload proves nothing
        }
        reload.run();
        return present.get();
    }

    /**
     * P2 in one call: build it, build it again, and confirm there is one — not two.
     *
     * <p>The failure this catches is not subtle to a child and is invisible to a unit test: "make
     * it bigger" leaves the old one standing next to the new one, and the child now owns two, one
     * of which is the version they rejected.
     */
    public static List<String> rebuildLeavesOne(Runnable buildOnce, Runnable buildAgain,
                                                Supplier<Integer> countCopies) {
        List<String> problems = new ArrayList<>();
        buildOnce.run();
        buildAgain.run();
        int copies = countCopies.get();
        if (copies != 1) {
            problems.add(P2_ONE_COPY + ": found " + copies + " after building the same id twice");
        }
        return problems;
    }

    /** Run {@code work}, turning any throw into a problem rather than killing the whole run. */
    public static void guarded(String what, StressHarness.Outcome.Builder out, Consumer<Void> work) {
        try {
            work.accept(null);
        } catch (RuntimeException | AssertionError e) {
            // A harness that dies hides every case after it, so this is never allowed to propagate.
            out.problem(what + " threw: " + e);
        }
    }
}
