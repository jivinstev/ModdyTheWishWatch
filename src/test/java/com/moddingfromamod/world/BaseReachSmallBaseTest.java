package com.moddingfromamod.world;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.marshal.Spot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * the reach rule on a SMALL base — the battle arena's shape, an area of 33 by 43 with a 31 by 31 reach. A
 * search round the child ("the creatures within 64 blocks") is asked whether he stands on his base, and the tool keeps
 * the search to it ({@link BaseReach#fence}); a placement is still asked whether it lands on his base.
 */
class BaseReachSmallBaseTest {

    private static final String OW = Bases.OVERWORLD;
    private final UUID him = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final Base base = new Base(him, OW, 100, 64, 200, 64,
            new Region(84, 116, 179, 221), new Region(85, 115, 185, 215));
    private Bases.Pos at = new Bases.Pos(OW, 100.5, 64, 200.5);

    private final Bases world = new Bases() {
        @Override public String noun() {
            return "base";
        }

        @Override public Base baseOf(UUID p) {
            return him.equals(p) ? base : null;
        }

        @Override public UUID ownerAt(String d, double x, double z) {
            return base.area().contains(x, z) ? him : null;
        }

        @Override public Bases.Pos positionOf(UUID p) {
            return him.equals(p) ? at : null;
        }
    };

    private static JsonObject args(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void aSearchWiderThanHisBaseRunsWhileHeStandsOnIt() {
        // What a Builder "Follows you" creature lowers to: the widest search modify_mob takes.
        assertNull(BaseReach.refusal("modify_mob",
                args("{\"scope\":\"group\",\"target\":\"pal_the_cat\",\"radius\":64,\"follow\":true}"), him, null, world));
        assertNull(BaseReach.refusal("modify_mob", args("{\"scope\":\"one\"}"), him, null, world));
    }

    @Test
    void theSearchIsStillRefusedFromOffHisBase() {
        at = new Bases.Pos(OW, 300, 64, 200);
        String r = BaseReach.refusal("modify_mob", args("{\"scope\":\"one\",\"radius\":4}"), him, null, world);
        assertNotNull(r);
        assertTrue(r.contains("off his base"), r);
        at = new Bases.Pos("minecraft:the_nether", 100, 64, 200);
        assertNotNull(BaseReach.refusal("modify_mob", args("{\"scope\":\"one\"}"), him, null, world));
        at = null;
        assertNotNull(BaseReach.refusal("modify_mob", args("{\"scope\":\"one\"}"), him, null, world));
    }

    @Test
    void everyKindOfThatMobEverywhereIsStillRefused() {
        assertNotNull(BaseReach.refusal("modify_mob", args("{\"scope\":\"category\",\"target\":\"cow\"}"), him, null, world));
    }

    @Test
    void aCreatureAimedOffHisBaseIsStillRefused() {
        assertNull(BaseReach.refusal("sculpt_creature", args("{\"spawn\":true}"), him, new Spot(OW, 102, 64, 204), world));
        String r = BaseReach.refusal("sculpt_creature", args("{\"spawn\":true}"), him, new Spot(OW, 140, 64, 204), world);
        assertNotNull(r);
        assertTrue(r.contains("is off his base"), r);
    }

    @Test
    void theFenceIsHisBaseOnlyWhereTheRulesFenceAWish() {
        Region f = BaseReach.fence(him, world, null);
        assertEquals(base.area(), f, "no rules read on a world with bases is CLOSED, which fences a wish to his base");
        assertTrue(f.contains(100, 200));
        assertFalse(f.contains(84 - 1, 200), "one block past his area's edge is outside the fence");
        assertNull(BaseReach.fence(him, null, null), "a world with no bases fences nothing");
        assertNull(BaseReach.fence(null, world, null), "a call with no wish bound is not judged");
        Region none = BaseReach.fence(other, world, null);
        assertNotNull(none, "a child with no base yet may search nowhere");
        assertFalse(none.contains(100, 200));
        assertFalse(none.contains(0, 0));
    }

    @Test
    void aHeightIsNotADistanceAcrossHisBase() {
        assertNull(BaseReach.refusal("conjure_at", args("{\"forward\":3,\"up\":20}"), him, null, world));
        assertNotNull(BaseReach.refusal("conjure_at", args("{\"forward\":40}"), him, null, world));
        assertNull(BaseReach.refusal("build_structure", args("{\"offsetForward\":2,\"offsetY\":30}"), him, null, world));
        assertNotNull(BaseReach.refusal("build_structure", args("{\"offsetForward\":40}"), him, null, world));
    }

    /** Every tool the rule trusts to keep its search to his base really reads the fence. */
    @Test
    void everyToolThatKeepsToHisBaseReadsTheFence() throws Exception {
        Map<String, String> source = Map.of("modify_mob", "src/main/java/com/moddingfromamod/agent/tools/ModifyMobTool.java");
        for (String tool : BaseReach.KEEPS_TO_BASE) {
            String file = source.get(tool);
            assertNotNull(file, tool + " is trusted to keep its search to his base: name its source here and make it "
                    + "read WorldModuleHost.fenceFor");
            assertTrue(Files.readString(Path.of(file)).contains("WorldModuleHost.fenceFor("),
                    tool + " (" + file + ") is in BaseReach.KEEPS_TO_BASE but never reads the fence");
        }
    }
}
