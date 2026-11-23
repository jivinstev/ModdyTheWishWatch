package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.testing.ledger.GateLedger;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * <b>Each thing outside a building, alone, walked and stood</b>: the half of {@code BuilderBuildingComposableTest}
 * that builds and WALKS, in a class of its own so the test forks share it out (JUnit spreads work by class). Together
 * with that class's zone check ({@code theZonesOutsideAreIndependent}) it proves every combination of things outside;
 * the declared pairs whose zones meet are built together here. Each (type, size) is a fingerprinted variant (
 * phase 2, {@link GateLedger}): one whose preset and code are unchanged since it passed is not walked again.
 */
class BuilderOutsideAloneTest {

    /**
     * What goes outside never costs a room he could walk into, and adds nothing that cannot stand: every grounded type,
     * every size, each option ALONE, against the same type with nothing outside.
     */
    @Test
    void eachThingOutsideAloneIsWalkableAndStands() {
        List<String> bad = new ArrayList<>();
        GateLedger.Check check = BuilderBuildingComposableTest.ledger(BuilderOutsideAloneTest.class, "eachThingOutsideAloneIsWalkableAndStands");
        for (BuilderBuildings.Type t : BuilderBuildingComposableTest.grounded()) {
            for (int size = 1; size <= 4; size++) {
                GateLedger.Variant v = BuilderBuildingComposableTest.variant(check, t, size);
                if (v.skip()) {
                    continue;
                }
                List<String> mine = new ArrayList<>();
                OutsideZones.Measured m = BuilderBuildingComposableTest.measured(t, size);
                for (String o : OutsideZones.OPTIONS) {
                    mine.addAll(OutsideZones.noWorseThanBare(m.what() + " + " + o, m.bare(), m.alone().get(o)));
                }
                v.done(mine);
                bad.addAll(mine);
            }
        }
        bad.addAll(check.finish());
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }

    /**
     * phase 2, THE NIGHTLY ({@code -Pgate.nightly}; skipped in Gate A): a building type, a size and a SUBSET of the
     * things outside, built together and checked against the bare type — the old exhaustive sweep, which the zones make
     * unnecessary in Gate A, kept as the nightly's cross-check of that argument within a fixed time
     * ({@link com.moddingfromamod.testing.ledger.NightlySampler}; three factors, so all-triples is every combination).
     */
    @Test
    void nightlyCombinationsOutsideSampled() {
        org.junit.jupiter.api.Assumptions.assumeTrue(com.moddingfromamod.testing.GateTier.nightly(), "the nightly's sample (-Pgate.nightly)");
        List<String> types = BuilderBuildingComposableTest.grounded().stream().map(BuilderBuildings.Type::id).toList();
        java.util.Map<String, com.moddingfromamod.builder.BuildPlan> bare = new java.util.concurrent.ConcurrentHashMap<>();
        var o = com.moddingfromamod.testing.ledger.NightlySampler.of(BuilderOutsideAloneTest.class, "nightlyCombinationsOutsideSampled",
                List.of(types, List.of("1", "2", "3", "4"), BuilderHousesTest.outsideSubsets(false)), 240).run(row -> {
                    BuilderBuildings.Type t = BuilderBuildings.type(row.get(0));
                    int size = Integer.parseInt(row.get(1));
                    com.moddingfromamod.builder.BuildPlan b = bare.computeIfAbsent(row.get(0) + size, k -> BuilderBuildingComposableTest.plan(
                            BuilderBuildingComposableTest.composed(t.preset(), BuilderBuildings.NOTHING, "", size, "X")));
                    return OutsideZones.noWorseThanBare(t.id() + " size " + size + " + " + row.get(2), b,
                            BuilderBuildingComposableTest.plan(BuilderBuildingComposableTest.composed(t.preset(), row.get(2), "", size, "X")));
                });
        assertTrue(o.failures().isEmpty(), String.join("\n", o.failures()));
    }

    /** each declared interacting pair, built TOGETHER on every grounded type at every size. */
    @Test
    void theDeclaredPairsTogether() {
        List<String> bad = new ArrayList<>();
        GateLedger.Check check = BuilderBuildingComposableTest.ledger(BuilderOutsideAloneTest.class, "theDeclaredPairsTogether");
        for (BuilderBuildings.Type t : BuilderBuildingComposableTest.grounded()) {
            for (int size = 1; size <= 4; size++) {
                GateLedger.Variant v = BuilderBuildingComposableTest.variant(check, t, size);
                if (v.skip()) {
                    continue;
                }
                List<String> mine = new ArrayList<>();
                OutsideZones.Measured m = BuilderBuildingComposableTest.measured(t, size);
                for (String pair : BuilderBuildingComposableTest.INTERACTING.keySet()) {
                    String on = BuilderBuildings.join(List.of(pair.split("\\+")));
                    mine.addAll(OutsideZones.noWorseThanBare(m.what() + " + " + on, m.bare(),
                            BuilderBuildingComposableTest.plan(BuilderBuildingComposableTest.composed(t.preset(), on, "", size, "X"))));
                }
                v.done(mine);
                bad.addAll(mine);
            }
        }
        bad.addAll(check.finish());
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }
}
