package com.moddingfromamod.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.event.design.DayBank;
import com.moddingfromamod.event.design.DesignSizes;
import com.moddingfromamod.event.design.EventDesign;
import com.moddingfromamod.event.shape.LayerShape;
import com.moddingfromamod.event.shape.StampShape;
import org.junit.jupiter.api.Test;

/** An event written down, and the clock that brings it back every N days (E4). */
class EventDesignTest {

    static final String METEOR = """
            {"name":"Rock Fall","motion":"falling_body","size":"big","material":"shroomlight",
             "then":[{"if":"water_near","design":"sea_wave"},{"if":"on_land","design":"rock_crater"}]}""";

    @Test
    void aGoodDesignParsesAndRoundTrips() {
        EventDesign.Parsed p = EventDesign.parse(METEOR);
        assertTrue(p.ok(), String.valueOf(p.errors()));
        EventDesign d = p.design();
        assertEquals("rock_fall", d.name(), "a name is a safe key");
        assertEquals("minecraft:shroomlight", d.material(), "a bare id gets its namespace");
        assertTrue(d.restore(), "a falling body puts the world back by itself (D1)");
        EventDesign again = EventDesign.parse(d.toJson()).design();
        assertEquals(d, again, "what is saved is exactly what comes back");
    }

    @Test
    void aCraterStaysUntilUndoUnlessItSaysOtherwise() {
        EventDesign c = EventDesign.parse("{\"name\":\"c\",\"motion\":\"impact_stamp\",\"size\":\"little\","
                + "\"material\":\"stone\"}").design();
        assertFalse(c.restore());
        assertEquals(StampShape.Form.CRATER, c.form(), "a stamp is a crater unless it says cone or mound");
        EventDesign back = EventDesign.parse("{\"name\":\"c\",\"motion\":\"impact_stamp\",\"size\":\"little\","
                + "\"material\":\"stone\",\"afterwards\":\"restore\",\"form\":\"cone\"}").design();
        assertTrue(back.restore());
        assertEquals(StampShape.Form.CONE, back.form());
    }

    @Test
    void aBadDesignIsRefusedWithEveryReasonNotRepaired() {
        EventDesign.Parsed p = EventDesign.parse("""
                {"name":"x","motion":"volcano","size":"enormous","material":"not an id!",
                 "catch":[{"where":"everywhere","do":["explode"]}],"then":[{"design":"x"}]}""");
        assertFalse(p.ok());
        String all = String.join(" | ", p.errors());
        for (String want : new String[] {"motion", "size", "material", "catch[0].where", "catch[0].do[0]",
                "cannot follow itself"}) {
            assertTrue(all.contains(want), "should say what is wrong with " + want + ": " + all);
        }
    }

    @Test
    void numbersAreBoundedAndOnlyKindStatusNamesReachTheBuilder() {
        EventDesign d = EventDesign.parse("""
                {"name":"q","motion":"none","size":"little",
                 "catch":[{"where":"near","reach":9999,"every":0,"do":["shake","hurt:500","status:glowing:99999"]}]}""")
                .design();
        EventDesign.CatchSpec c = d.catches().get(0);
        assertEquals(EventDesign.MAX_REACH, c.reach());
        assertEquals(1, c.every());
        assertEquals(EventDesign.MAX_HURT, c.does().get(1).amount());
        assertEquals(600, c.does().get(2).amount());
    }

    @Test
    void aFollowOnIsChosenByTheSpot() {
        EventDesign d = EventDesign.parse(METEOR).design();
        assertEquals("sea_wave", d.follow(true, false), "a meteor in the sea raises a wave");
        assertEquals("rock_crater", d.follow(false, false), "on land it leaves a crater");
        EventDesign quake = EventDesign.parse("""
                {"name":"q","motion":"spreading_cracks","size":"medium",
                 "then":[{"if":"slope_near","design":"slide"}]}""").design();
        assertNull(quake.follow(false, false), "no hill, no slide");
        assertEquals("slide", quake.follow(false, true));
    }

    @Test
    void aCountdownNeedsWhatHappensAtZero() {
        assertFalse(EventDesign.parse("{\"name\":\"c\",\"motion\":\"countdown\",\"size\":\"little\"}").ok());
        EventDesign c = EventDesign.parse("{\"name\":\"c\",\"motion\":\"countdown\",\"size\":\"little\","
                + "\"seconds\":2,\"at_zero\":\"boom\"}").design();
        assertEquals(5, c.seconds(), "never less than five seconds to get out");
        assertTrue(c.describe().contains("after 5 seconds, boom"), c.describe());
    }

    @Test
    void lastingLongerIsSecondsAndAnUnsaidOneChangesNothing() {
        EventDesign quick = EventDesign.parse("{\"name\":\"q\",\"motion\":\"spreading_cracks\",\"size\":\"big\"}").design();
        assertEquals(0, quick.seconds(), "unsaid on a quake: its own length stands");
        assertFalse(quick.toJson().has("seconds"), "and nothing is saved that would change it later");
        EventDesign longer = EventDesign.parse("{\"name\":\"q\",\"motion\":\"spreading_cracks\",\"size\":\"big\","
                + "\"seconds\":60}").design();
        assertEquals(60, longer.seconds());
        assertTrue(longer.describe().contains("at least 60 seconds"), longer.describe());
        assertEquals(60, EventDesign.parse(longer.toJson().toString()).design().seconds(), "it survives a save");
        EventDesign c = EventDesign.parse("{\"name\":\"c\",\"motion\":\"countdown\",\"size\":\"little\","
                + "\"at_zero\":\"boom\"}").design();
        assertEquals(30, c.seconds(), "a countdown still defaults to thirty");
    }

    @Test
    void anEventWithSecondsOutlastsItsShapeAndStopsSteppingIt() {
        int[] steps = {0};
        com.moddingfromamod.event.EventMotion shape = (w, age) -> {
            steps[0]++;
            return age >= 10;
        };
        var held = com.moddingfromamod.event.design.EventBuild.atLeast(shape, 100);
        int age = 0;
        while (!held.step(null, age)) {
            age++;
        }
        assertEquals(100, age, "held until the seconds ran out, not until the shape finished");
        assertEquals(11, steps[0], "the finished shape is not stepped again");
    }

    @Test
    void theClockOnlyGoesForward() {
        long b = DayBank.bank(0, -1, 50_000);
        assertEquals(0, b, "the first look after a load only re-bases");
        b = DayBank.bank(b, 1_000, 13_000);
        assertEquals(12_000, b, "/time set night banks half a day");
        long rewound = DayBank.bank(b, 13_000, 0);
        assertEquals(b, rewound, "/time set 0 banks nothing");
        assertFalse(DayBank.due(DayBank.period(2) - 1, 2));
        assertTrue(DayBank.due(DayBank.period(2), 2));
        assertTrue(DayBank.spend(DayBank.period(2) * 10, 2) < DayBank.period(2),
                "a world shut for a week does not come back to a week of events");
    }

    @Test
    void everySizeFitsItsBudget() {
        for (EventScale s : EventScale.values()) {
            assertTrue(DesignSizes.wave(s, true).peakCells() <= s.totalBlocks, "wave " + s);
            LayerShape flood = LayerShape.fit(DesignSizes.floodDepth(s), s.radius, s.totalBlocks);
            assertTrue((long) flood.cellsPerLayer() * flood.depth <= s.totalBlocks, "flood " + s);
            for (StampShape.Form f : StampShape.Form.values()) {
                assertTrue(DesignSizes.stampCells(f, s) <= s.totalBlocks,
                        f + " " + s + ": " + DesignSizes.stampCells(f, s));
            }
            assertTrue(DesignSizes.skyRadius(s) <= s.radius, "sky " + s);
            assertTrue((long) DesignSizes.cracks(s) * DesignSizes.crackLength(s) * DesignSizes.crackDepth(s)
                    <= s.totalBlocks, "cracks " + s);
        }
    }

    @Test
    @org.junit.jupiter.api.DisplayName("#1047: what an event LEAVES stays, what only PASSES goes")
    void whatItLeavesStaysWhatPassesGoes() {
        for (EventDesign.Motion m : EventDesign.Motion.values()) {
            boolean leaves = switch (m) {
                case IMPACT_STAMP, SPREADING_CRACKS, SLIDE, FROM_THE_SKY, TRANSFORM, CROWD -> true;
                default -> false;
            };
            org.junit.jupiter.api.Assertions.assertEquals(!leaves, m.restoresByDefault(),
                    m + (leaves ? " leaves something behind: it stays until undo"
                            : " only passes: it puts the world back by itself"));
        }
    }

    @Test
    void aMeteorWithNothingToLeaveSaysSoAndOneWithACraterDoesNot() {
        EventDesign bare = EventDesign.parse("{\"name\":\"m\",\"motion\":\"falling_body\",\"size\":\"big\","
                + "\"material\":\"minecraft:obsidian\"}").design();
        assertTrue(bare.describe().contains("leaves NOTHING"), bare.describe());
        EventDesign full = EventDesign.parse(METEOR).design();
        assertFalse(full.describe().contains("leaves NOTHING"), full.describe());
    }

    @Test
    void aKeptDesignStagedAgainUnderANewHeadlineIsTheSameEvent() {
        String base = "{\"name\":\"v\",\"motion\":\"layer\",\"size\":\"big\",\"material\":\"minecraft:lava\"";
        EventDesign kept = EventDesign.parse(base + ",\"title\":\"BIG VOLCANO\"}").design();
        EventDesign retitled = EventDesign.parse(base + ",\"title\":\"VOLCANO AGAIN!\"}").design();
        EventDesign bigger = EventDesign.parse(base.replace("big", "medium") + ",\"title\":\"BIG VOLCANO\"}")
                .design();
        assertTrue(retitled.sameEventAs(kept), "only the words changed: not filmed again");
        assertFalse(bigger.sameEventAs(kept), "a different size is a different event");
        assertFalse(kept.sameEventAs(null));
    }

    /** a transform changes what is there; what it changes is named, and it stays. */
    @Test
    void aTransformNamesWhatChangesAndStays() {
        EventDesign g = EventDesign.parse("{\"name\":\"gold_ground\",\"motion\":\"transform\","
                + "\"size\":\"little\",\"material\":\"gold_block\"}").design();
        assertEquals(EventDesign.Motion.TRANSFORM, g.motion());
        assertNull(g.replaces(), "left out means the ground");
        assertFalse(g.restore(), "a transform stays until he undoes it");
        EventDesign t = EventDesign.parse("{\"name\":\"pink\",\"motion\":\"transform\",\"size\":\"medium\","
                + "\"material\":\"pink_wool\",\"replaces\":\"#leaves\"}").design();
        assertEquals("#minecraft:leaves", t.replaces(), "a tag gets its namespace and keeps its #");
        assertEquals(t.replaces(), EventDesign.parse(t.toJson()).design().replaces(), "it round-trips");
        assertFalse(EventDesign.parse("{\"name\":\"x\",\"motion\":\"transform\",\"size\":\"little\"}").ok(),
                "a transform with nothing to turn into is refused");
        assertFalse(EventDesign.parse("{\"name\":\"x\",\"motion\":\"transform\",\"size\":\"little\","
                + "\"material\":\"stone\",\"replaces\":\"the trees!\"}").ok());
        assertFalse(EventDesign.parse("{\"name\":\"x\",\"motion\":\"transform\",\"size\":\"big\","
                + "\"material\":\"cave_vines\",\"replaces\":\"air\"}").ok(),
                "filling the air is a layer, not a transform (measured: 'fill this cave', 1 of 8 runs)");
    }

    /** The ripple reaches the edge exactly at its spread time and never overshoots. */
    @Test
    void aTransformRipplesOutToItsEdge() {
        assertEquals(0.5, com.moddingfromamod.event.motion.TransformMotion.reached(0, 10, 20), 1e-9);
        assertEquals(10.0, com.moddingfromamod.event.motion.TransformMotion.reached(19, 10, 20), 1e-9);
        assertEquals(10.0, com.moddingfromamod.event.motion.TransformMotion.reached(500, 10, 20), 1e-9);
    }

    /** a crowd names what, how many and how it stands; what it brings stays until undo. */
    @Test
    void aCrowdNamesWhatHowManyAndHowItStands() {
        EventDesign h = EventDesign.parse("{\"name\":\"herd\",\"motion\":\"crowd\",\"size\":\"medium\","
                + "\"drop\":\"horse\",\"count\":10}").design();
        assertEquals(EventDesign.Motion.CROWD, h.motion());
        assertEquals("minecraft:horse", h.drop());
        assertEquals(10, h.count());
        assertEquals(EventDesign.Crowd.CLUSTER, h.pattern(), "bunched at the spot unless he said otherwise");
        assertFalse(h.restore(), "a crowd is what he asked for: it stays");
        assertTrue(h.describe().contains("10 minecraft:horse"), h.describe());
        EventDesign r = EventDesign.parse(h.toJson().toString().replace("cluster", "around_me")).design();
        assertEquals(EventDesign.Crowd.AROUND_ME, r.pattern());
        assertEquals(10, r.count(), "it round-trips");
        EventDesign young = EventDesign.parse("{\"name\":\"y\",\"motion\":\"crowd\",\"size\":\"little\","
                + "\"drop\":\"cow\",\"baby\":true}").design();
        assertTrue(young.baby() && EventDesign.parse(young.toJson()).design().baby(), "baby round-trips");
        assertEquals(EventDesign.MAX_CROWD, EventDesign.parse("{\"name\":\"b\",\"motion\":\"crowd\","
                + "\"size\":\"big\",\"drop\":\"bat\",\"count\":5000}").design().count(), "bounded");
        assertFalse(EventDesign.parse("{\"name\":\"x\",\"motion\":\"crowd\",\"size\":\"little\"}").ok(),
                "a crowd of nothing is refused");
        assertFalse(EventDesign.parse("{\"name\":\"x\",\"motion\":\"crowd\",\"size\":\"little\","
                + "\"drop\":\"cat\",\"pattern\":\"square\"}").ok());
    }

    /** Members of a crowd never stand in one another, a ring is a ring, and all arrive. */
    @Test
    void aCrowdStandsApartInItsShapeAndAllArrive() {
        for (boolean ring : new boolean[] {false, true}) {
            for (int n : new int[] {1, 3, 10, 64}) {
                var spots = new java.util.ArrayList<com.moddingfromamod.event.shape.CrowdShape.Spot>();
                for (int i = 0; i < n; i++) {
                    spots.add(com.moddingfromamod.event.shape.CrowdShape.spot(i, n, ring));
                }
                double reach = com.moddingfromamod.event.shape.CrowdShape.radius(n, ring);
                for (int i = 0; i < n; i++) {
                    var a = spots.get(i);
                    assertTrue(Math.hypot(a.dx(), a.dz()) <= reach + 1e-6, "inside the camera's radius");
                    if (ring) {
                        assertEquals(reach, Math.hypot(a.dx(), a.dz()), 1e-6, "a ring is a circle");
                    }
                    for (int j = i + 1; j < n && reach < com.moddingfromamod.event.shape.CrowdShape.MAX_RADIUS; j++) {
                        var b = spots.get(j);
                        assertTrue(Math.hypot(a.dx() - b.dx(), a.dz() - b.dz()) >= 1.0,
                                "n=" + n + (ring ? " ring" : " cluster") + ": " + i + " and " + j + " overlap");
                    }
                }
                assertTrue(ring ? reach >= com.moddingfromamod.event.shape.CrowdShape.MIN_RING : reach > 0);
                assertEquals(n, com.moddingfromamod.event.shape.CrowdShape.dueBy(19, n, 20), "all by the end");
                assertTrue(com.moddingfromamod.event.shape.CrowdShape.dueBy(0, n, 20) >= 1, "one at once");
            }
        }
    }

    // ──: a crowd's creatures arrive already what he asked for ─────────────────────────────

    private static final String ARMY = "{\"name\":\"enderman_army\",\"motion\":\"crowd\",\"size\":\"medium\","
            + "\"drop\":\"minecraft:enderman\",\"count\":12,\"pattern\":\"cluster\",\"traits\":{"
            + "\"friendly\":true,\"follow\":true,\"base\":\"guardian\",\"name\":\"Enderman Soldier\"}}";

    @Test
    void aCrowdCarriesTraitsAndTheyRoundTrip() {
        EventDesign.Parsed p = EventDesign.parse(ARMY);
        assertTrue(p.errors().isEmpty(), p.errors().toString());
        EventDesign d = p.design();
        assertTrue(d.traits().contains("\"base\":\"guardian\""), d.traits());
        EventDesign again = EventDesign.parse(d.toJson()).design();
        assertEquals(d, again, "a button replaying the army replays its soldiers' traits");
    }

    @Test
    void traitsAreModifyMobsWordsAndNothingElse() {
        assertFalse(EventDesign.parse(ARMY.replace("\"base\":\"guardian\"", "\"base\":\"fights\""))
                .errors().isEmpty(), "an unknown temperament is refused by name");
        assertFalse(EventDesign.parse(ARMY.replace("\"friendly\":true", "\"target\":\"zombie\""))
                .errors().isEmpty(), "WHICH mob is not a trait");
        assertFalse(EventDesign.parse(ARMY.replace("\"traits\":{", "\"traits\":[").replace("}}", "]}"))
                .errors().isEmpty(), "traits is an object");
        EventDesign asString = EventDesign.parse("{\"name\":\"a\",\"motion\":\"crowd\",\"size\":\"medium\","
                + "\"drop\":\"minecraft:fox\",\"count\":4,\"traits\":\"{\\\"base\\\":\\\"guardian\\\"}\"}").design();
        assertTrue(asString != null && asString.traits().contains("guardian"),
                "the same object sent as a JSON string is read, not refused");
        assertFalse(EventDesign.parse("{\"name\":\"a\",\"motion\":\"crowd\",\"size\":\"medium\","
                + "\"drop\":\"minecraft:fox\",\"count\":4,\"traits\":\"guardian please\"}").errors().isEmpty(),
                "a string that is not an object is still refused");
        EventDesign plain = EventDesign.parse("{\"name\":\"herd\",\"motion\":\"crowd\",\"size\":\"medium\","
                + "\"drop\":\"minecraft:cow\",\"count\":4}").design();
        assertEquals("", plain.traits(), "a crowd with no traits arrives as vanilla makes it");
        assertFalse(plain.toJson().has("traits"));
    }
}
