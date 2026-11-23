package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.moddingfromamod.builder.BuildGeometry;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildShape;
import com.moddingfromamod.builder.Builder;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Replay every building in the stress corpus and check it came out right.</b>
 *
 * <p>The corpus is mined from a real mod repo — a castle with a drawbridge, an obelisk, a bridge
 * deck, a flat-topped mesa, a ball pit, a runway. These are the shapes a child actually asks for,
 * and they exercise the builder far harder than the one demo box the existing gate uses.
 *
 * <p>Two invariants, both learned the expensive way by that mod repo and written into its
 * build-quality checklist:
 *
 * <ul>
 *   <li><b>BUILDINGS_HAVE_FOUR_WALLS</b> — a hollow box must be enclosed on all four sides. An
 *       off-by-one in lot-edge maths once left every skyscraper in a generated city an open
 *       two-walled shell, and it shipped, because nobody had asserted enclosure.</li>
 *   <li><b>Removal is complete and non-destructive</b> — guardrail P5. Whatever the builder placed,
 *       the remover clears; whatever it did not place, the remover leaves.</li>
 * </ul>
 */
@GameTestHolder("moddingfromamod")
public class BuildingStressGameTest {

    /** Cases are built side by side; wide enough that no footprint reaches its neighbour. */
    private static final int SPACING = 120;

    /**
     * <b>Every room in the corpus really has four walls once the whole plan is placed.</b>
     *
     * <p>This used to examine one {@link BuildShape} at a time and could therefore never fail. A
     * hollow box places a block wherever a coordinate is on a face, so at mid-height every wall cell
     * is present by construction and the middle is absent by construction — the predicate had no
     * reachable false branch. It reported "202 hollow shapes enclosed on all four sides ✓" on every
     * run since it was written, which is the same sentence it would have printed if the builder had
     * been broken the entire time. (A second, identical copy lived in {@code BuildingsHarness}; both
     * are now gone. A corrected falsehood surviving in a second copy is a failure mode this repo has
     * already recorded.)
     *
     * <p>{@link BuildGeometry} composes the plan the way {@link Builder} does — in order, later
     * shapes overwriting earlier ones, air carving — and then measures what is left of each wall.
     * That is what makes it capable of failing: the way a wall actually goes missing is that a later
     * shape removes it, which is invisible to anything looking at shapes one at a time.
     */
    @GameTest(template = "empty_test", timeoutTicks = 200, batch = "buildingstress")
    public void everyRoomInTheCorpusHasFourWalls(GameTestHelper helper) {
        List<WishCorpus.Case> cases = WishCorpus.ofKind(WishCorpus.load(), "buildings");
        if (cases.isEmpty()) {
            helper.fail(Component.literal("the corpus loaded no building cases, so this gate proves nothing"));
            return;
        }
        List<String> broken = new ArrayList<>();
        int roomsChecked = 0;

        for (WishCorpus.Case c : cases) {
            BuildPlan plan = planOf(c);
            if (plan == null) {
                continue;
            }
            BuildGeometry g = BuildGeometry.of(plan);
            for (BuildGeometry.Room room : g.rooms()) {
                roomsChecked++;
                List<String> missing = room.missingSides(BuildGeometry.WALL_MISSING);
                if (!missing.isEmpty()) {
                    broken.add(c.id() + ": shape " + room.shapeIndex() + " has no "
                            + String.join(" or ", missing) + " wall");
                }
            }
        }
        if (!broken.isEmpty()) {
            helper.fail(Component.literal("BUILDINGS_HAVE_FOUR_WALLS violated in " + broken.size() + " case(s):\n  - "
                    + String.join("\n  - ", broken)
                    + "\n\nAn open-sided building reads to a child as a broken build, and this "
                    + "exact defect (an off-by-one in the edge maths) shipped a whole city of "
                    + "two-walled skyscrapers in a real mod before anyone asserted enclosure."));
            return;
        }
        if (roomsChecked == 0) {
            helper.fail(Component.literal("no rooms were actually checked — the corpus has drifted and this gate is "
                    + "passing vacuously"));
            return;
        }
        System.out.println("BUILDING STRESS: " + roomsChecked + " room(s) across " + cases.size()
                + " corpus building(s) still have four walls after the whole plan is placed ✓");
        helper.succeed();
    }

    /**
     * <b>A representative sample really lands, and really comes back up.</b>
     *
     * <p>Guardrail P5 end to end, through the real time-sliced builder: build it, confirm the
     * blocks are there, remove it, confirm they are gone and the ground beneath is not.
     */
    @GameTest(template = "empty_test", timeoutTicks = 900, batch = "buildingstress")
    public void corpusBuildingsGoUpAndComeBackDown(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<WishCorpus.Case> all = WishCorpus.ofKind(WishCorpus.load(), "buildings");
        // The ones small enough to build several of inside one test's time budget.
        List<WishCorpus.Case> sample = all.stream()
                .filter(c -> planOf(c) != null && planOf(c).volume() > 0
                        && planOf(c).volume() < 6000)
                .limit(4).toList();
        if (sample.isEmpty()) {
            helper.fail(Component.literal("no small corpus buildings to exercise"));
            return;
        }

        List<String> problems = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < sample.size(); i++) {
            WishCorpus.Case c = sample.get(i);
            BuildPlan plan = planOf(c);
            BlockPos at = helper.absolutePos(new BlockPos(2, 2, 2)).offset(i * SPACING, 0, 0);
            // A witness block under the build: removal must not eat the ground.
            BlockPos ground = at.offset(0, -1, 0);

            // What stood there before: a take-down gives it back — here the test arena's own barrier wall
            // where a build crosses it — so a survivor is a block that differs from it, not any block.
            java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> before = new java.util.HashMap<>();
            helper.runAfterDelay(1 + i * 4, () -> {
                level.setBlock(ground, net.minecraft.world.level.block.Blocks.DIRT
                        .defaultBlockState(), 3);
                for (BuildShape s : plan.shapes()) {
                    s.forEachBlock((x, y, z) -> before.putIfAbsent(at.offset(x, y, z), level.getBlockState(at.offset(x, y, z))));
                }
                Builder.submit(level, at, plan);
            });

            helper.runAfterDelay(200, () -> {
                // "Did it build?" must be asked of the shape's OWN positions, not of the anchor
                // corner. A cylinder's local (0,0,0) is outside the circle, so the corner is
                // legitimately air -- checking it reported a perfectly good 40-block tower as
                // "did not build at all", and the early return then skipped removal and blamed
                // the 1354 surviving blocks on the product. A wrong presence check manufactures
                // a second, scarier symptom.
                int[] present = {0};
                for (BuildShape s : plan.shapes()) {
                    s.forEachBlock((x, y, z) -> {
                        if (!level.getBlockState(at.offset(x, y, z)).isAir()) {
                            present[0]++;
                        }
                    });
                }
                if (present[0] == 0) {
                    problems.add(c.id() + " (\"" + c.prompt() + "\") did not build at all");
                    return;
                }
                Builder.clearBuild(level, at, plan);
            });

            helper.runAfterDelay(320, () -> {
                int[] left = {0};
                for (BuildShape s : plan.shapes()) {
                    s.forEachBlock((x, y, z) -> {
                        var now = level.getBlockState(at.offset(x, y, z));
                        if (!now.isAir() && now != before.get(at.offset(x, y, z))) {
                            left[0]++;
                        }
                    });
                }
                if (left[0] > 0) {
                    problems.add(c.id() + ": " + left[0] + " block(s) survived removal — orphans "
                            + "accumulate forever (P5)");
                }
                if (!level.getBlockState(ground).is(net.minecraft.world.level.block.Blocks.DIRT)) {
                    problems.add(c.id() + ": removal destroyed the ground beneath it — P5 says "
                            + "removal clears only the creation's own blocks, never terrain");
                }
            });
        }

        helper.runAfterDelay(400, () -> {
            if (!problems.isEmpty()) {
                helper.fail(Component.literal("corpus buildings failed:\n  - " + String.join("\n  - ", problems)));
                return;
            }
            System.out.println("BUILDING STRESS: " + sample.size()
                    + " corpus building(s) built, removed cleanly, ground intact ✓");
            helper.succeed();
        });
    }

    /**
     * <b>The wishes we cannot grant, counted out loud.</b>
     *
     * <p>Not an assertion about the product — a standing report. 119 of the corpus's 160 cases are
     * wishes the engine cannot express, and printing the demand ranking every gate run is what
     * stops "we should build that one day" from decaying into silence. See
     * {@link WishCorpus#unsupported(java.util.List)} for why they live in the corpus at all.
     */
    @GameTest(template = "empty_test", timeoutTicks = 100, batch = "buildingstress")
    public void reportWhatWeCannotGrantYet(GameTestHelper helper) {
        List<WishCorpus.Case> all = WishCorpus.load();
        List<WishCorpus.Case> cannot = WishCorpus.unsupported(all);
        if (all.isEmpty()) {
            helper.fail(Component.literal("the stress corpus did not load at all"));
            return;
        }
        System.out.println("WISH COVERAGE: " + (all.size() - cannot.size()) + " of " + all.size()
                + " corpus wishes are expressible today; " + cannot.size() + " are not.");
        for (var e : WishCorpus.demandByWishClass(all)) {
            System.out.printf("   %-24s %3d wish(es) waiting%n", e.getKey(), e.getValue());
        }
        helper.succeed();
    }

    /** Rebuild a {@link BuildPlan} from a corpus case's spec. */
    private static BuildPlan planOf(WishCorpus.Case c) {
        JsonObject o = new JsonObject();
        o.addProperty("id", c.id());
        o.addProperty("name", c.id());
        if (!c.spec().has("shapes")) {
            return null;
        }
        o.add("shapes", c.spec().get("shapes"));
        return BuildPlan.fromJson(o);
    }
}
