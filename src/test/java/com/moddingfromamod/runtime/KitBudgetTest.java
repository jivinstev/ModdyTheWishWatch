package com.moddingfromamod.runtime;

import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.agent.tools.SculptCreatureTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two kit defects a human found by eye, and the budget that stops them recurring.
 *
 * <p>Both fixtures are the <b>real</b> arguments from the run the human rejected, not invented
 * ones. Gates calibrated against hand-made fixtures once caught 0 of 14 real creatures, because the
 * fixtures had been built to the thresholds.
 */
class KitBudgetTest {

    /** A long-bodied creature: deeper (Z) than it is wide (X). A lizard. */
    private static final List<CreatureSculpt.Part> LIZARD = CreatureSculpt.parseParts(
            JsonParser.parseString("""
                    [{"name":"body","from":[-6,10,-18],"to":[6,22,14],"colour":"orange"},
                     {"name":"head","from":[-5,14,14],"to":[5,24,22],"colour":"orange"}]""")
                    .getAsJsonArray());

    /** An upright creature: wider than it is deep. A Spikor. */
    private static final List<CreatureSculpt.Part> UPRIGHT = CreatureSculpt.parseParts(
            JsonParser.parseString("""
                    [{"name":"body","from":[-14,10,-6],"to":[14,40,6],"colour":"green"}]""")
                    .getAsJsonArray());

    private static JsonObject args(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /**
     * The real request from the rejected run: nine large spikes along the top of a lizard's body.
     * They must follow the spine (Z), not run across the shoulders (X).
     */
    @Test
    void aSpikeRowOnALongBodyFollowsTheSpineNotTheShoulders() {
        var made = SculptKits.expand(
                args("{\"kits\":[{\"kit\":\"spike_row\",\"on\":\"body\",\"count\":9,"
                        + "\"size\":\"L\",\"along\":\"top\"}]}").getAsJsonArray("kits"), LIZARD);
        assertTrue(made.size() >= 4, "should have built a row");
        double spreadX = spread(made, 0);
        double spreadZ = spread(made, 2);
        assertTrue(spreadZ > spreadX * 2,
                "on a long body the row must run along Z (spine): spreadZ=" + spreadZ
                        + " spreadX=" + spreadX);
    }

    @Test
    void aSpikeRowOnAnUprightBodyStillRunsAcrossIt() {
        // The original behaviour, which was right for Spikor and must not regress.
        var made = SculptKits.expand(
                args("{\"kits\":[{\"kit\":\"spike_row\",\"on\":\"body\",\"count\":7,"
                        + "\"along\":\"top\"}]}").getAsJsonArray("kits"), UPRIGHT);
        assertTrue(spread(made, 0) > spread(made, 2),
                "on an upright body the row should still run across the width");
    }

    /** count is not the same thing as boxes: a spike is two, so count 9 was quietly 18 parts. */
    @Test
    void oneKitCannotEmitMoreBoxesThanItsCap() {
        var made = SculptKits.expand(
                args("{\"kits\":[{\"kit\":\"spike_row\",\"on\":\"body\",\"count\":12}]}")
                        .getAsJsonArray("kits"), LIZARD);
        assertTrue(made.size() <= SculptKits.MAX_BOXES_PER_KIT,
                "emitted " + made.size() + " boxes, cap is " + SculptKits.MAX_BOXES_PER_KIT);
    }

    /**
     * The whole-creature budget. Reproduces the rejected creature's shape: 26 hand-written parts
     * asking for four kits that together wanted 30 more.
     */
    @Test
    void kitsCannotBecomeMostOfTheCreature() {
        var body = new java.util.ArrayList<>(LIZARD);
        for (int i = 0; i < 24; i++) {
            body.addAll(CreatureSculpt.parseParts(JsonParser.parseString(
                    "[{\"name\":\"bit" + i + "\",\"from\":[0," + i + ",0],\"to\":[2,"
                            + (i + 2) + ",2],\"colour\":\"orange\"}]").getAsJsonArray()));
        }
        assertEquals(26, body.size());
        var all = SculptCreatureTool.withKits(body, args("""
                {"kits":[{"kit":"spike_row","on":"body","count":9,"size":"L","along":"top"},
                         {"kit":"horn","on":"head","count":2},
                         {"kit":"claw","on":"body","count":3},
                         {"kit":"claw","on":"head","count":3}]}"""));
        int kitParts = SculptKits.usedBy(all);
        double share = (double) kitParts / all.size();
        assertTrue(share <= SculptCreatureTool.MAX_KIT_SHARE + 1e-6,
                "kits took " + share + " of the creature (was 0.54 on the rejected run)");
        assertTrue(kitParts > 0, "the budget must trim kits, not abolish them");
    }

    /**
     * Kits stay USABLE. The cap is a guardrail, not a ban — and kit share is not monotonic: 0.54
     * was rejected by the human, 0.38 preferred, and 0.00 was NOT preferred over 0.38. A version of
     * this that drove kit use to zero would be as wrong as the flood it replaced.
     */
    @Test
    void theCapTrimsKitsWithoutAbolishingThem() {
        var kitted = SculptCreatureTool.withKits(LIZARD, args(
                "{\"kits\":[{\"kit\":\"spike_row\",\"on\":\"body\",\"count\":9}]}"));
        assertTrue(SculptKits.usedBy(kitted) > 0, "a kit must still build something");
        assertTrue(SculptKits.usedBy(kitted) <= SculptKits.MAX_BOXES_PER_KIT);
    }

    private static double spread(List<CreatureSculpt.Part> parts, int axis) {
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        for (CreatureSculpt.Part p : parts) {
            lo = Math.min(lo, p.from()[axis]);
            hi = Math.max(hi, p.to()[axis]);
        }
        return hi - lo;
    }
}
