package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>A planner change must bump {@link HitProxyPlan#VERSION}</b>, because plans are saved with
 * creatures and a saved plan's key says nothing about the code that made it.
 *
 * <p>Plans every committed creature (the rig-fit corpus and the starters) and fingerprints the
 * answers. The fingerprint is recorded per version below: if this fails, the planner now answers
 * differently for the same creature — bump {@code HitProxyPlan.VERSION} and add the printed line
 * here. Every world's saved plans are then re-planned on their next load; nothing else is needed.
 *
 * <p>Numbers are rounded to 1e-6 before hashing, so the fingerprint is about the planner's
 * decisions (carrier, proxies, where they stand), not about a last-bit difference in {@code Math}
 * between two JVMs.
 */
class HitProxyPlanVersionTest {

    /** One line per planner version that has shipped. Never edit a line; add one. */
    private static final Map<Integer, String> FINGERPRINTS = Map.of(
            1, "752ecb61adce407c7cec82fc1b41927e1510acacb0b211a21b7e9d9dd0ca6637",
            2, "290b627d42daa399b0a01c0ff35cacf1454e0130e8b893ba6fab7ce6227fbf0b");

    /**
     * The same, over the public stand-ins: where the private tier-experiment corpus is absent
     * ({@link SpikeFixture#PRIVATE_CORPUS} false, as in the public repo) the creatures planned are their stand-ins,
     * so the fingerprint is its own. Recorded from version 2, when the stand-ins arrived. A bump adds a line to both.
     */
    private static final Map<Integer, String> STAND_IN_FINGERPRINTS = Map.of(
            2, "8dbf2099d4d8a8b7bf1c1d4f4d460c1f5c0e329c269b530f7390ed35a1e23a26");

    static String fingerprint() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String name : RigSeparationTest.all()) {
            HitProxyPlan.Plan p = HitProxyPlanTest.plan(RigSeparationTest.load(name),
                    RigSeparationTest.scaleOf(name), CarrierPool.Move.WALK);
            sb.append(name).append('=');
            if (p == null) {
                sb.append("null\n");
                continue;
            }
            RigBase.Choice c = p.core();
            sb.append(c.id()).append(' ').append(r(c.scale())).append(' ').append(r(c.actualWidth()))
                    .append(' ').append(r(c.height())).append(" met=").append(p.met());
            for (HitProxyPlan.Proxy x : p.proxies()) {
                sb.append(" [").append(x.id()).append(' ').append(r(x.scale())).append(' ')
                        .append(r(x.x())).append(' ').append(r(x.y())).append(' ').append(r(x.z()))
                        .append(']');
            }
            sb.append('\n');
        }
        byte[] d = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(d);
    }

    private static String r(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }

    @Test
    @DisplayName("the planner's answers over the committed creatures match its VERSION")
    void aPlannerChangeBumpsTheVersion() throws Exception {
        String now = fingerprint();
        String table = SpikeFixture.PRIVATE_CORPUS ? "FINGERPRINTS" : "STAND_IN_FINGERPRINTS";
        String recorded = (SpikeFixture.PRIVATE_CORPUS ? FINGERPRINTS : STAND_IN_FINGERPRINTS).get(HitProxyPlan.VERSION);
        assertTrue(recorded != null, "HitProxyPlan.VERSION " + HitProxyPlan.VERSION + " has no fingerprint: add "
                + HitProxyPlan.VERSION + ", \"" + now + "\" to " + table);
        assertEquals(recorded, now, "HitProxyPlan now plans the committed creatures differently than VERSION "
                + HitProxyPlan.VERSION + " did, so every plan saved with a creature would be reused although "
                + "this code would answer otherwise. Bump HitProxyPlan.VERSION to " + (HitProxyPlan.VERSION + 1)
                + " and add " + (HitProxyPlan.VERSION + 1) + ", \"" + now + "\" to " + table + " (and the other "
                + "table's line, from a run on the other corpus).");
    }
}
