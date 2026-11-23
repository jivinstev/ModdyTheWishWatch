package com.moddingfromamod.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.moddingfromamod.wish.RealBuilds;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * <b>The buildings oracle, run against buildings the agent really built.</b>
 *
 * <p>A gate tuned on hand-made fixtures caught <b>0 of 14</b> real creatures on its first live run,
 * because the fixtures had been built to the thresholds rather than the other way round
 * ({@code docs/EVAL_STRATEGY.md}). The buildings corpus is 84 cases and every one of them is a
 * fixture, so this runs the same checks over the 19 plans recovered from real worlds and asserts
 * the thing a fixture suite structurally cannot: <b>that the gate does not fire on correct work.</b>
 *
 * <p>It prints the full distribution whether it passes or fails, because the second question — is
 * there any headroom, or has this metric saturated like the last three did? — can only be answered
 * by looking at the numbers, and a green tick hides them.
 */
class RealBuildCalibrationTest {

    /** The one definition, so the harness, the corpus game test and this all agree. */
    static final double WALL_MISSING = BuildGeometry.WALL_MISSING;

    /** The corpus is private: in the public cut every test here is skipped by name, here it is required. */
    @BeforeAll
    static void theCorpusIsHere() {
        com.moddingfromamod.PrivateCorpora.require("real-builds.jsonl");
    }

    @Test
    void theRealCorpusIsActuallyPresent() {
        // A quiet skip here would report green over a calibration that never ran, which is the
        // failure this whole file exists to prevent.
        assertTrue(RealBuilds.load().size() >= 15,
                "the real-build corpus did not load (" + RealBuilds.load().size() + " plans). "
                        + "Every assertion below would be vacuous. Re-mine it with "
                        + "tools/mine-real-builds.py.");
    }

    /**
     * The two real builds that are genuinely broken, and what is wrong with them.
     *
     * <p><b>Found by this gate on its first run</b>, which is worth stating plainly: the last three
     * objective metrics this project built all saturated on real output, and the expectation going
     * in was that this one would too.
     *
     * <p>Both fail the same way, and it is arithmetic rather than opinion. {@code dx/dy/dz} is the
     * offset of a shape's <b>corner</b> — {@link BuildShape#forEachBlock} emits {@code dx + x} for
     * {@code x} in {@code [0, width)} — so two shapes that share {@code dx=0} and have different
     * widths are flush along their low edges and <b>not concentric</b>. The agent designed both of
     * these as though they were:
     *
     * <ul>
     *   <li>{@code crystal_arena}: a 44-wide wall cylinder at {@code dx=0} (x 0..43) hollowed out by
     *       a 40-wide air cylinder also at {@code dx=0} (x 0..39). That leaves four blocks of wall
     *       on the east and south and <b>eats the west and north walls</b>. Both come back at 0.43
     *       while their opposite sides are at 1.00 — a perfect diagonal split, which no doorway
     *       produces.</li>
     *   <li>{@code purple_crystal_lighthouse}: a 15-wide base room whose two crossing air corridors
     *       are placed at the rim rather than the middle, taking the west and north walls to 0.35
     *       against 0.85 opposite.</li>
     * </ul>
     *
     * <p>The same plan uses <i>centre</i> semantics elsewhere — the crystal arena's four corner
     * pillars sit at ±12 and ±14, around a centre the wall cylinder does not actually have — so the
     * agent is not consistently wrong, it is guessing. The tool schema says only "offset from the
     * child" and never says corner, so this is an ambiguous contract rather than a careless agent.
     * <b>Fixing that is a generator change and belongs on its own branch</b>, one at a time, or the
     * next measurement cannot be attributed. Recorded here so the gate is proven to fire on real
     * output, and so that silencing it becomes a visible edit.
     */
    private static final List<String> KNOWN_BROKEN =
            List.of("real_10_purple_crystal_lighthouse", "real_17_crystal_arena");

    @Test
    void theGateFiresOnTheRealBuildsThatAreActuallyBroken() {
        List<String> fired = new ArrayList<>();
        for (RealBuilds.Real r : RealBuilds.load()) {
            BuildGeometry g = BuildGeometry.of(r.plan());
            for (BuildGeometry.Room room : g.rooms()) {
                if (!room.missingSides(WALL_MISSING).isEmpty()) {
                    fired.add(r.id());
                    break;
                }
            }
        }
        assertEquals(KNOWN_BROKEN, fired,
                "the set of real builds this gate reports as broken changed. If a fix landed, "
                        + "shorten KNOWN_BROKEN and say so; if the gate went quiet, it has been "
                        + "silenced and the two off-centre carve-outs are back to shipping "
                        + "unnoticed.");
    }

    /**
     * <b>Re-describing the two broken builds with {@code anchor: centre} actually repairs them.</b>
     *
     * <p>This is the test that decides whether an earlier issue's fix is the right fix. Adding a field and
     * documenting it proves nothing on its own — the question is whether the thing the agent was
     * evidently *trying* to say, said properly, produces the building it meant. So this takes the
     * real broken plans, changes nothing but the anchor, and re-runs the oracle.
     *
     * <p>It is a strong check because it can fail: if centre-anchoring were the wrong model of the
     * agent's intent, the walls would stay broken or break differently.
     */
    @Test
    void anchoringTheBrokenRealBuildsFromTheirMiddlesRepairsThem() {
        for (RealBuilds.Real r : RealBuilds.load()) {
            if (!KNOWN_BROKEN.contains(r.id())) {
                continue;
            }
            double before = BuildGeometry.of(r.plan()).weakestWall();
            assertTrue(before < WALL_MISSING,
                    r.id() + " is supposed to be one of the broken ones but its weakest wall is "
                            + before);

            List<BuildShape> centred = new ArrayList<>();
            for (BuildShape s : r.plan().shapes()) {
                centred.add(new BuildShape(s.kind(), s.dx(), s.dy(), s.dz(), s.width(), s.height(),
                        s.depth(), s.block(), s.hollow(), BuildShape.Anchor.CENTRE));
            }
            BuildGeometry after = BuildGeometry.of(new BuildPlan(r.id(), r.id(), centred));

            System.out.printf(Locale.ROOT, "%n%s: weakest wall %.2f -> %.2f when every shape is "
                    + "anchored from its middle%n", r.id(), before, after.weakestWall());
            for (BuildGeometry.Room room : after.rooms()) {
                assertTrue(room.missingSides(WALL_MISSING).isEmpty(),
                        r.id() + " still has no " + room.missingSides(WALL_MISSING) + " wall on "
                                + "shape " + room.shapeIndex() + " even with centre anchoring — so "
                                + "centre-vs-corner is NOT what the agent got wrong here, and #52 "
                                + "has the wrong diagnosis rather than an incomplete fix");
            }
        }
    }

    @Test
    void noRealBuildTripsAnyGate() {
        List<RealBuilds.Real> corpus = RealBuilds.load();
        List<String> complaints = new ArrayList<>();
        List<Double> weakest = new ArrayList<>();

        System.out.printf("%n%-30s %7s %6s %5s %7s %8s %6s%n",
                "REAL BUILD", "blocks", "rooms", "wall", "h/span", "top", "flag");
        for (RealBuilds.Real r : corpus) {
            BuildGeometry g = BuildGeometry.of(r.plan());
            double wall = g.weakestWall();
            weakest.add(wall);

            List<String> missing = new ArrayList<>();
            for (BuildGeometry.Room room : g.rooms()) {
                for (String side : room.missingSides(WALL_MISSING)) {
                    missing.add("shape " + room.shapeIndex() + " " + side);
                }
            }
            boolean unfinished = g.looksUnfinished();
            System.out.printf(Locale.ROOT, "%-30s %7d %6d %5.2f %7.2f %8.3f %6s%n",
                    r.id().substring(0, Math.min(30, r.id().length())),
                    g.solidCount(), g.rooms().size(), wall, g.heightToSpan(), g.topCoverage(),
                    missing.isEmpty() && !unfinished ? "" : "**");

            if (!missing.isEmpty() && !KNOWN_BROKEN.contains(r.id())) {
                complaints.add(r.id() + ": walls reported missing on " + missing);
            }
            if (unfinished) {
                complaints.add(r.id() + ": reported as an unfinished box (h/span "
                        + g.heightToSpan() + ", top " + g.topCoverage() + ")");
            }
        }

        weakest.sort(Double::compare);
        System.out.printf(Locale.ROOT,
                "%nweakest wall over %d real builds: min %.3f, median %.3f, max %.3f "
                        + "(threshold %.2f)%n",
                weakest.size(), weakest.get(0), weakest.get(weakest.size() / 2),
                weakest.get(weakest.size() - 1), WALL_MISSING);

        assertTrue(complaints.isEmpty(),
                "the oracle fired on real agent output that is not broken. Either the threshold is "
                        + "wrong or the check is backwards — do NOT relax it without deciding "
                        + "which:\n  - " + String.join("\n  - ", complaints));
    }

    @Test
    void theFlatTopRuleWouldHaveBeenBackwardsAndTheNarrowOneIsNot() {
        // The finding that changed the design, kept as a test so it cannot be quietly undone.
        // "The top must not be flat" is not a weak rule on this corpus, it is an INVERTED one: it
        // fires only on the arenas and the trenches, which are floors and are correctly flat.
        List<String> naiveWouldFire = new ArrayList<>();
        List<String> narrowFires = new ArrayList<>();
        for (RealBuilds.Real r : RealBuilds.load()) {
            BuildGeometry g = BuildGeometry.of(r.plan());
            if (g.topCoverage() > 0.9) {
                naiveWouldFire.add(r.id() + String.format(Locale.ROOT, " (h/span %.2f)",
                        g.heightToSpan()));
            }
            if (g.looksUnfinished()) {
                narrowFires.add(r.id());
            }
        }
        System.out.println("\na bare 'tops must not be flat' rule would fire on "
                + naiveWouldFire.size() + " of 19 real builds: " + naiveWouldFire);
        assertFalse(naiveWouldFire.isEmpty(),
                "no real build has a flat top any more, so the anti-correlation this test records "
                        + "is no longer demonstrable — re-derive the roof rule rather than deleting "
                        + "this");
        assertTrue(narrowFires.isEmpty(),
                "the proportion-aware rule fired on real work: " + narrowFires);
    }

    @Test
    void noRealBuildWasSilentlyClamped() {
        // 0 of 19 sit on a limit today. If this ever fires it is a genuine finding, not a flake:
        // it means the agent asked for something the builder would not give it. Note it can only
        // ever SUSPECT — the proof needs the pre-clamp request, which is why ActionLog stopped
        // clipping arguments at 600 characters.
        List<String> suspects = new ArrayList<>();
        for (RealBuilds.Real r : RealBuilds.load()) {
            List<String> hits = BuildGeometry.capBoundaries(r.plan());
            if (!hits.isEmpty()) {
                suspects.add(r.id() + " " + hits);
            }
        }
        System.out.println("\ncap-boundary suspects: " + (suspects.isEmpty() ? "none" : suspects));
        assertTrue(suspects.isEmpty(),
                "a real build sits exactly on a builder limit, which usually means the wish was "
                        + "bigger than the limit and got quietly resized:\n  - "
                        + String.join("\n  - ", suspects));
    }

    @Test
    void theRealCorpusIsNearTheCapsAndThatIsWorthKnowing() {
        // Not a defect — a headroom report. Spikor's Castle is 63 shapes against a limit of 64 and
        // 192,000 blocks against a limit of 220,000. The next castle the agent designs is the one
        // that gets refused, so the caps are a live constraint rather than a theoretical one.
        int mostShapes = 0;
        int biggest = 0;
        String shapiest = "";
        String largest = "";
        for (RealBuilds.Real r : RealBuilds.load()) {
            if (r.plan().shapes().size() > mostShapes) {
                mostShapes = r.plan().shapes().size();
                shapiest = r.id();
            }
            int v = r.plan().volume();
            if (v > biggest) {
                biggest = v;
                largest = r.id();
            }
        }
        System.out.printf(Locale.ROOT,
                "%nheadroom: most shapes %d/%d (%s), biggest %d/%d blocks (%s)%n",
                mostShapes, BuildPlan.MAX_SHAPES, shapiest,
                biggest, BuildPlan.MAX_BLOCKS, largest);
        assertTrue(mostShapes <= BuildPlan.MAX_SHAPES);
        assertTrue(biggest <= BuildPlan.MAX_BLOCKS);
    }

    // ─────────────────: which real buildings you cannot get into ─────────────────

    /**
     * The real builds that enclose a room with no way in, measured rather than assumed.
     *
     * <p><b>13 of 19.</b> That is the finding, and it is about the generator, not about this list:
     * nothing in {@code build_structure} has ever asked whether a child can get inside, so nothing
     * ever stopped it shipping a sealed one. {@code real_08_spikor_castle} encloses <b>28,228</b>
     * blocks of room with no door.
     *
     * <p>Pinned so that the number moving is a VISIBLE edit in both directions — if the generator
     * starts leaving doorways this list shortens and someone should say so; if it goes quiet the
     * check has been silenced. Same contract as {@code KNOWN_BROKEN} above.
     *
     * <p>{@code real_16_gorilla_jungle} is deliberately absent: it seals <b>4</b> blocks, which is a
     * pocket in some scenery rather than a room, and is what
     * {@code BuildGeometry#SEALED_MIN_ROOM} exists to keep out of the note.
     *
     * <p>Note the two {@code KNOWN_BROKEN} builds do NOT both appear here, and that is consistent
     * rather than a contradiction: {@code real_17_crystal_arena} is broken because its west and
     * north walls were EATEN, so it is wide open — the wall gate catches it and this one correctly
     * says nothing. The two checks fail differently on purpose.
     */
    private static final List<String> SEALED_REAL_BUILDS = List.of(
            "real_00_giant_airplane",
            "real_01_sky_wizard_castle",
            "real_02_crystal_lighthouse",
            "real_03_crystal_lighthouse",
            "real_04_crystal_lighthouse",
            "real_05_purple_crystal_lighthouse",
            "real_06_crystal_lighthouse",
            "real_08_spikor_castle",
            "real_09_crystal_lighthouse",
            "real_10_purple_crystal_lighthouse",
            "real_11_purple_crystal_lighthouse",
            "real_12_crystal_lighthouse",
            "real_13_bloop_deep");
    // real_18_battle_arena left this list: an OPEN-AIR arena whose 1-tall hollow glowstone, concrete
    // and sea-lantern cylinders are trims on its walls. Under the old rule a hollow cylinder that short kept its
    // caps, so the 44-wide glowstone trim was a roof over the arena, and that roof was the sealed "room".

    /**
     * A real build's id is {@code real_NN_<its name>}; the lists above compare the number only. The private
     * corpus keeps the name the build was really given, and this public file names it with a stand-in
     *, so a name may differ while the build is the same one.
     */
    private static List<String> byNumber(List<String> ids) {
        return ids.stream().map(id -> id.replaceAll("^(real_\\d+)_.*$", "$1")).toList();
    }

    @Test
    void theSealedCheckFiresOnRealOutputAndNotOnDecorativePockets() {
        List<String> sealed = new ArrayList<>();
        for (RealBuilds.Real r : RealBuilds.load()) {
            BuildGeometry.Sealed s = BuildGeometry.of(r.plan()).sealedInterior();
            assertFalse(s.tooBig(),
                    r.id() + " was too big to search, so this calibration is vacuous for it");
            if (s.volume() >= BuildGeometry.SEALED_MIN_ROOM) {
                sealed.add(r.id());
            }
        }
        assertEquals(byNumber(SEALED_REAL_BUILDS), byNumber(sealed),
                "the set of real builds with an unreachable room changed. If the generator learned "
                        + "to leave a doorway, shorten this list and say so; if the check went "
                        + "quiet, it has been silenced and sealed buildings are shipping again.");
    }

    /**
     * How many of the shut real buildings are shut in <b>more than one place</b> —.
     *
     * <p>This is the number that decides whether the old single-breach note could ever have worked.
     * It told the agent <i>"a 1x2 hole there would open it"</i>, and on a multi-room build that is
     * false: cutting the named doorway opens one room and the note comes back about another. The
     * measured transcript is — a lighthouse went 742 sealed → agent cuts a 3x4x8 doorway
     * through exactly the named wall → 619 still sealed, six blocks up — and the wish then ended
     * with the building shut.
     *
     * <p><b>Measured: 11 of the 14</b> (9 of 13 — see below the list). A lighthouse is shut in <b>17</b> separate rooms, the
     * Spikor castle in 9 across 28,228 blocks. Following the old note to completion on that
     * lighthouse would have taken seventeen round trips, each one told it had found "the" thinnest
     * spot — which is why no agent on record ever finished.
     *
     * <p>The three single-room ones are {@code real_00_giant_airplane}, {@code real_13_bloop_deep}
     * and {@code real_18_battle_arena} — which are also, and this is not claimed as more than a
     * coincidence at n=3, exactly the builds named as the plausible DELIBERATE solid masses.
     *
     * <p>Pinned as a set rather than a count, so a build moving between the two groups is visible
     * rather than cancelling out against another that moved the other way. The assertion message
     * carries the per-build split, which is the number to quote.
     */
    private static final List<String> MULTI_ROOM_REAL_BUILDS = List.of(
            "real_01_sky_wizard_castle",         // 4096 blocks in 5 rooms
            "real_02_crystal_lighthouse",        //  741 in 5
            "real_03_crystal_lighthouse",        // 1548 in 9
            "real_05_purple_crystal_lighthouse", // 1809 in 17 — the worst on record
            "real_06_crystal_lighthouse",        // 3952 in 8
            "real_08_spikor_castle",             // 28228 in 9
            "real_10_purple_crystal_lighthouse", // 3515 in 2 (3164 in 8)
            "real_11_purple_crystal_lighthouse", // 1384 in 8 (1344 in 8)
            "real_12_crystal_lighthouse");       // 3077 in 3
    // moved real_04 and real_09 to ONE room each: their 1-tall hollow cylinders the width of the tower
    // wall (purple-glass window bands, amethyst bands every eight blocks) were solid disks under the old rule,
    // floors with no stairs that cut each tower into five and ten stacked "rooms". As rings they are bands.

    @Test
    void mostSealedRealBuildsAreShutInMoreThanOnePlace() {
        List<String> multi = new ArrayList<>();
        StringBuilder detail = new StringBuilder();
        for (RealBuilds.Real r : RealBuilds.load()) {
            BuildGeometry.Sealed s = BuildGeometry.of(r.plan()).sealedInterior();
            long rooms = s.rooms().stream()
                    .filter(x -> x.volume() >= BuildGeometry.SEALED_MIN_ROOM).count();
            if (s.volume() < BuildGeometry.SEALED_MIN_ROOM) {
                continue;
            }
            detail.append("\n  ").append(r.id()).append(": ").append(s.volume())
                  .append(" blocks in ").append(rooms).append(" room(s)");
            if (rooms > 1) {
                multi.add(r.id());
            }
        }
        assertEquals(byNumber(MULTI_ROOM_REAL_BUILDS), byNumber(multi),
                "the set of real builds shut in more than one place changed." + detail);
        // The claim this whole change rests on, asserted rather than asserted-about: a single
        // doorway was never going to be the fix for most of them.
        assertTrue(multi.size() * 2 > SEALED_REAL_BUILDS.size(),
                "if most sealed builds were single-room, the old one-breach note was adequate and "
                        + "this change is not carrying its weight" + detail);
    }
}
