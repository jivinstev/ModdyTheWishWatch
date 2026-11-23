package com.moddingfromamod.wish.stress;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.moddingfromamod.wish.WishCorpus;

/**
 * <b>What the engine can actually verify yet — so a corpus can describe more than that.</b>
 *
 * <p>The machines corpus is written against the <i>whole</i> Create backlog (–), not against
 * what happens to work today. A corpus that only describes what already works cannot tell you what
 * is missing, and writing it after the fact means every subsystem lands with its cases invented by
 * whoever built it, to fit what they built. Writing them first makes the corpus the specification
 * and gives each subsystem a scoreboard it did not get to mark itself against.
 *
 * <p>The obvious way to do that is also the dangerous one. A case naming a capability nobody has
 * built has to be skipped, and <b>a quiet skip reports green over an evaluation that never ran</b> —
 * this repo's single most repeated lesson, and the reason a corpus that loaded 160 cases and ran 19
 * once reported success. So a gap here is:
 *
 * <ul>
 *   <li><b>counted</b>, per capability, in the run report;</li>
 *   <li><b>ratcheted</b> — {@link #MAX_GAPS} fails the build if the number of waiting cases goes
 *       UP, so a subsystem cannot be "deferred" by quietly adding cases nobody runs;</li>
 *   <li><b>self-closing</b> — the moment a capability is added to {@link #IMPLEMENTED}, every case
 *       waiting on it becomes live with no edit to the corpus. If they then fail, they were always
 *       failing.</li>
 * </ul>
 *
 * <p>Deliberately a hand-edited set rather than something inferred from the classpath. "Does a
 * fluid probe exist" is not a question about whether a class is present — it is a question about
 * whether it has been shown to work, which only a person adding a line here can answer.
 */
public final class Capabilities {

    /** A contraption-entity oracle: P1. Needed by every §4a/§4b/§4g case that assembles. */
    public static final String ENTITY_ORACLE = "entity-oracle";
    /** Block-entity NBT on a {@code ContraptionPart}: P2. Needed by all of §4e/§4f/copycat. */
    public static final String BE_NBT = "be-nbt";
    /** A probe that can read tank contents:. Rotation says nothing about fluid. */
    public static final String FLUID_PROBE = "fluid-probe";
    /** A probe that answers "the package posted at A arrived at B":. */
    public static final String ARRIVAL_PROBE = "arrival-probe";
    /**
     * <b>A boiler that actually drives its engine.</b> Was "every input reads healthy and the shaft
     * is dead" — and the missing input was the one nothing could see: a blaze burner's fuel is
     * block-entity NBT, and the machines harness was discarding the NBT of every part, so the
     * burners were never lit however seething they looked. Rightly its own name rather than a
     * variant of fluid-probe: it was a gap in what the harness could MAKE HAPPEN, not in what it
     * could observe.
     */
    public static final String BOILER_POWER = "boiler-power";
    /**
     * <b>Does the network KNOW what is in the chests?</b> A stock ticker, a requester and a factory
     * gauge do not move a parcel — they answer questions about stock — so {@link #ARRIVAL_PROBE}
     * cannot speak for them. Lumping them under it was a mistake the flip caught: they went live
     * asserting nothing at all.
     */
    public static final String STOCK_NETWORK = "stock-network";
    /**
     * <b>What a spout, a drain or a valve handle actually DID.</b> Not a missing probe — ItemProbe
     * and the fluid probe both exist — but a claim nobody has written. Named and counted rather
     * than left as four live cases that prove blocks were placed, which is the exact shape of the
     * defect this class was built to end.
     */
    public static final String SPOUT_OUTCOME = "spout-outcome";

    /**
     * What has been built AND shown to work.
     *
     * <p>Add a line here when a subsystem's oracle is real. That single edit switches its corpus
     * cases on, which is the point: it is a visible act in a diff, and it cannot be done by
     * accident.
     */
    public static final Set<String> IMPLEMENTED = Set.of(
            // KineticProbe (rpm + overstress) and ItemProbe (arrival of an item, not of a package).
            "kinetics",
            // THE ENTITY ORACLE. Held out of this list for four sessions while its cases were made
            // to pass, which is the rule working: 21 failed at the first flip and every one was a
            // real defect — three whole subsystems (elevators needed a redstone_contact, glue and
            // a click; gantries needed an end-on drive and the carriage on TOP of the rail), and
            // finally the probe's own `stranded` test, which asked "is something here" instead of
            // "is the PART here" and so read a hole full of water as a machine that never moved.
            "entity-oracle",
            // BE-NBT and the fluid probe were built and wired long before they were listed here,
            // and the list held 44 machine cases in backlog for no reason but the omission.
            // Flipping them surfaced 13 failures and every one was worth having: three pump
            // fixtures with an upside-down motor, a fan facing the wrong way, four known-broken
            // cases rotation cannot prove broken, and five steam cases (now needs=boiler-power).
            // The last thing holding it back was eight display fixtures pointed at a
            // minecraft:chest, which Create assigns no display source — so none of them could ever
            // have gathered..
            "be-nbt",
            // ARRIVAL_PROBE. The last of the four oracles. Held out while its cases were
            // made to pass, which found: packages do not teleport (only a chain conveyor or a
            // train station can carry one), a conveyor line is kinetic and must be joined with
            // ABSOLUTE positions, a port keeps what is addressed to it, and a delivery ends
            // UNWRAPPED. Proven both ways in ArrivalGameTest — the negative control has no line
            // between the ports and delivers nothing, which is what stops this being an item count.
            "arrival-probe",
            // SPOUT_OUTCOME. What a filling machine DID, which no rpm and no block list can answer:
            // an empty bucket has to come out full. Proven both ways in SpoutGameTest, the negative
            // being the same rig with the pump swapped for plain pipe. Flipping it found that none
            // of its seven cases could ever have worked, for four separate silent reasons — a depot
            // looks for its processor at pos.above(2) so a spout resting on it is not connected; a
            // pump is driven only by a cogwheel on the pump's own axis, so every shaft-on-top
            // fixture in this corpus has a pump reading STOPPED under a shaft spinning at 16 rpm; a
            // creative_fluid_tank accepts a fill, reads it back once and then supplies nothing; and
            // a copper_valve_handle is a hand crank, not a tap, so the "tap" case had no valve in
            // it at all.
            "spout-outcome",
            // BOILER_POWER. Held out because "every input reads healthy and the shaft is dead",
            // and the reason turns out to be the harness rather than Create: a blaze burner's FUEL
            // is block-entity NBT, and partsOf dropped the nbt of every part. The burners under
            // these boilers were never lit, however seething they looked -- which is requirement 4
            // of four, all of them silent, and the only one nothing could see. The other three
            // (four tank blocks per heat level, a powered_shaft two blocks out, the tank behind the
            // engine) were already worked out and are verified in SteamGameTest at 64 rpm.
            "boiler-power",
            "fluid-probe");

    /**
     * The most cases that may be waiting on an unbuilt capability.
     *
     * <p>A ceiling, not a target: it may fall freely, and raising it is a deliberate edit that shows
     * up in a diff. Without it, "add cases for a subsystem, never build the subsystem" is
     * indistinguishable in the report from progress.
     *
     * <p>Raised 64 → 96 when the corpus gained its composite tier. Every case in this corpus was
     * previously ONE subsystem in isolation, which is not how anyone wishes: a child asks for a
     * lemonade factory, not a spout, and the seams between subsystems are where the bugs are. The
     * cross-subsystem cases cannot go live until every capability they name exists, so they are
     * backlog by construction — which is exactly why the ceiling had to move rather than the rule.
     */
    // Lowered 96 -> 61 when be-nbt and fluid-probe went in. THIS is the ratchet that locks the
    // flip in: the FLOOR counts corpus cases INCLUDING those waiting on a capability, so it
    // guards the specification rather than the coverage, and would not have noticed a case
    // slipping back into the backlog. The ceiling notices.
    // Raised 61 -> 63 for two negative elevator cases, then DOWN to 34 when entity-oracle landed
    // and took 29 of its own cases live with it. The ceiling only ever moves up for a reason
    // written next to it, and it comes back down the moment the subsystem it was covering exists.
    // 30 -> 1 when arrival-probe landed. ONE case is still waiting on anything at all:
    // cmp_steam_scoreboard, parked on boiler-power for a runner problem rather than a Create one.
    // A ceiling this tight is the point — the next case that quietly stops running fails the build.
    // 30 -> 13. arrival-probe took 29 cases live and, in doing so, exposed 12 that had been
    // parked behind it while asserting nothing whatever. Those are now named for what they are
    // actually missing — stock-network (7) and spout-outcome (5) — plus cmp_steam_scoreboard on
    // boiler-power. Renaming a gap is not deferring it: it is the difference between "waiting for
    // an oracle" and "nobody wrote the claim", and only one of those is somebody's next task.
    // 13 -> 0. Nothing is waiting on anything any more: stock-network, spout-outcome and
    // boiler-power all landed, and the last of them took the final case live. A ceiling of zero is
    // the tightest this ratchet can be -- the next case that quietly stops running fails the build
    // the moment it is added.
    public static final int MAX_GAPS = 0;

    private Capabilities() {
    }

    /** Everything a case is waiting for that does not exist yet. Empty means the case runs. */
    public static List<String> missingFor(WishCorpus.Case c) {
        List<String> missing = new java.util.ArrayList<>();
        for (String need : needsOf(c)) {
            if (!IMPLEMENTED.contains(need)) {
                missing.add(need);
            }
        }
        return missing;
    }

    /** True when every capability this case names has been built. */
    public static boolean runnable(WishCorpus.Case c) {
        return missingFor(c).isEmpty();
    }

    /** What a case declares it needs, or empty. */
    public static List<String> needsOf(WishCorpus.Case c) {
        List<String> out = new java.util.ArrayList<>();
        if (c != null && c.spec() != null && c.spec().has("needs")
                && c.spec().get("needs").isJsonArray()) {
            c.spec().getAsJsonArray("needs").forEach(e -> {
                if (e != null && e.isJsonPrimitive()) {
                    out.add(e.getAsString());
                }
            });
        }
        return out;
    }

    /** How many cases are waiting on each unbuilt capability, for the run report. */
    public static Map<String, Integer> gapReport(List<WishCorpus.Case> all) {
        Map<String, Integer> gaps = new LinkedHashMap<>();
        for (WishCorpus.Case c : all) {
            for (String missing : missingFor(c)) {
                gaps.merge(missing, 1, Integer::sum);
            }
        }
        return gaps;
    }

    /** Every case blocked by something unbuilt. */
    public static List<WishCorpus.Case> waiting(List<WishCorpus.Case> all) {
        return all.stream().filter(c -> !runnable(c)).toList();
    }

    /**
     * Capability names used by the corpus that this class has never heard of.
     *
     * <p>A typo in a {@code needs} entry would otherwise park a case forever in a gap that can never
     * close, looking for all the world like honest backlog.
     */
    public static List<String> unknownNames(List<WishCorpus.Case> all) {
        Set<String> known = new LinkedHashSet<>(IMPLEMENTED);
        known.add(ENTITY_ORACLE);
        known.add(BE_NBT);
        known.add(FLUID_PROBE);
        known.add(ARRIVAL_PROBE);
        known.add(BOILER_POWER);
        known.add(STOCK_NETWORK);
        known.add(SPOUT_OUTCOME);
        Set<String> bad = new LinkedHashSet<>();
        for (WishCorpus.Case c : all) {
            for (String need : needsOf(c)) {
                if (!known.contains(need)) {
                    bad.add(need + " (in " + c.id() + ")");
                }
            }
        }
        return List.copyOf(bad);
    }
}
