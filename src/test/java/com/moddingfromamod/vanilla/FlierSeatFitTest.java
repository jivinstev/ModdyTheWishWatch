package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonParser;
import com.moddingfromamod.runtime.CreatureSculpt;
import org.junit.jupiter.api.Test;

/**
 * Gate A: <b>a rider sits on a flying creature's back, not over it</b>, whatever its carrier was first
 * planned for. A flier's carrier is a bat. Planned for a creature he rides ({@code seatCap} = its back),
 * the bat stands under the back and a seat lifts him onto it. Planned before the creature was rideable,
 * the bat is fitted to the whole body, head and wings included, and seats him above the back, which no
 * seat can lower. {@link HitProxyPlan#fitUnderBack} is the refit climbing on uses
 * ({@code RideSeat.fitCarrier}), checked here over the starter creatures given wings.
 *
 * <p>A carrier seats a rider at the top of its box, as the cap takes it (the bat: measured live by
 * {@code RideFitProbeGameTest}, seat = carrier height).
 */
class FlierSeatFitTest {

    /** How far from the back he may sit, in blocks; the probe's own tolerance. */
    static final double TOLERANCE = 0.1;

    /** Starter creatures, as fliers, with the scale each species is made at. */
    static final String[] FLIERS = {"pet_dragon", "cat", "wolf", "sheep", "trex", "chicken", "turtle"};

    record Case(String name, List<RigFit.Atom> atoms, double back) {
    }

    static Case flier(String name) throws Exception {
        String sculpt = resource("/starter-library/species/" + name + "/sculpt.json");
        double scale = JsonParser.parseString(resource("/starter-library/species/" + name + "/species.json"))
                .getAsJsonObject().get("scale").getAsDouble();
        List<CreatureSculpt.Part> parts = CreatureSculpt.parseParts(
                JsonParser.parseString(sculpt).getAsJsonObject().getAsJsonArray("parts"));
        List<RigFit.Atom> atoms = RigFit.atoms(RigPlan.atoms(RigFit.intoRigSpace(parts)), scale);
        return new Case(name, atoms, RigFit.backTop(atoms, DisplayRig.BACK_COLUMN));
    }

    /** The carrier a flier gets when nobody says it is ridden: no seat cap (the old path). */
    static HitProxyPlan.Plan unfitted(Case c) {
        return HitProxyPlan.plan(c.atoms(), CarrierPool.candidates(),
                CarrierPool.allowed(CarrierPool.Move.FLY, false), HitProxyPlan.proxyPool(),
                HitProxyPlan.Bounds.VANILLA);
    }

    /** Where he sits on a carrier against the back: + floats, - sinks in (a seat then lifts him). */
    static double gap(HitProxyPlan.Plan p, double back) {
        return p.core().height() - back;
    }

    /**
     * The control: the old behaviour, a bat planned without the back, seats him above the pet
     * dragon's back by far more than the tolerance (0.85 blocks: bat x2.286, 2.06 tall, back 1.20).
     */
    @Test
    void withoutTheFitTheDragonsRiderFloatsOverItsBack() throws Exception {
        Case dragon = flier("pet_dragon");
        HitProxyPlan.Plan old = unfitted(dragon);
        assertEquals("bat", old.core().id());
        assertTrue(gap(old, dragon.back()) > 0.5, String.format(
                "the unfitted bat should stand well over the back: x%.3f, %.3f tall, back %.3f",
                old.core().scale(), old.core().height(), dragon.back()));
        assertTrue(HitProxyPlan.seatsAboveBack(old.core().height(), dragon.back()));
    }

    @Test
    void everyFlierWhoseBatSeatsHimAboveItsBackIsRefitOntoIt() throws Exception {
        List<String> rows = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        int refit = 0;
        for (String name : FLIERS) {
            Case c = flier(name);
            HitProxyPlan.Plan old = unfitted(c);
            assertEquals("bat", old.core().id(), name);
            double before = gap(old, c.back());
            HitProxyPlan.Plan fit = HitProxyPlan.fitUnderBack(c.atoms(), CarrierPool.candidates(),
                    old.core().id(), old.core().height(), c.back());
            HitProxyPlan.Plan rides = fit == null ? old : fit;
            double after = gap(rides, c.back());
            rows.add(String.format("%-10s back %.3f  bat x%.3f gap %+.3f -> x%.3f gap %+.3f%s", name, c.back(),
                    old.core().scale(), before, rides.core().scale(), after, fit == null ? "" : "  (refit)"));
            if (HitProxyPlan.seatsAboveBack(old.core().height(), c.back())) {
                refit++;
                if (fit == null) {
                    bad.add(name + ": its bat seats him " + before + " over its back and was not refit");
                    continue;
                }
                assertEquals("bat", fit.core().id(), name + " keeps its own kind of carrier");
                assertTrue(fit.click().hit() >= HitProxyPlan.Bounds.VANILLA.clickHit() - 0.05,
                        name + ": the refit's proxies still catch its clicks, " + fit.click().hit());
            } else {
                assertNull(fit, name + ": a bat already at or under the back is left as it is");
            }
            // He never sits above the back; under it, RideSeat's seat lifts him the rest of the way.
            if (after > TOLERANCE) {
                bad.add(String.format("%s: he still sits %.3f over its back", name, after));
            }
        }
        System.out.println("FLIER-SEAT-FIT:\n  " + String.join("\n  ", rows));
        assertTrue(bad.isEmpty(), String.join("; ", bad) + "\n" + String.join("\n", rows));
        assertTrue(refit >= 3, "the case list should hold several fliers that need the refit: " + refit);
    }

    @Test
    void aSeatAtOrUnderTheBackNeedsNoRefit() {
        assertFalse(HitProxyPlan.seatsAboveBack(1.20, 1.204));
        assertFalse(HitProxyPlan.seatsAboveBack(1.25, 1.204));
        assertTrue(HitProxyPlan.seatsAboveBack(2.057, 1.204));
        assertFalse(HitProxyPlan.seatsAboveBack(2.0, Double.NaN), "no back, nothing to fit to");
        assertFalse(HitProxyPlan.seatsAboveBack(Double.NaN, 1.0));
        assertNull(HitProxyPlan.fitUnderBack(List.of(), CarrierPool.candidates(), "bat", 1.0, 1.2));
    }

    @Test
    void theRefitIsWhatAFlierRideableFromTheStartIsGiven() throws Exception {
        Case dragon = flier("pet_dragon");
        HitProxyPlan.Plan fromStart = HitProxyPlan.plan(dragon.atoms(), CarrierPool.candidates(),
                CarrierPool.allowed(CarrierPool.Move.FLY, false, false, true), HitProxyPlan.proxyPool(true),
                HitProxyPlan.Bounds.VANILLA, dragon.back());
        HitProxyPlan.Plan fit = HitProxyPlan.fitUnderBack(dragon.atoms(), CarrierPool.candidates(),
                "bat", unfitted(dragon).core().height(), dragon.back());
        assertNotNull(fit);
        assertEquals(fromStart.core().scale(), fit.core().scale(), 1e-9);
        assertEquals(fromStart.proxies().size(), fit.proxies().size());
    }

    private static String resource(String path) throws java.io.IOException {
        try (InputStream in = FlierSeatFitTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new java.io.FileNotFoundException(path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
