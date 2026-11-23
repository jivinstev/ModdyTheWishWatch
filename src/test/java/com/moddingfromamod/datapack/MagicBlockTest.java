package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The magic block's parts: the dials, their caps, pull strength, the surprise table's
 * feel rules, and the one rule that decides whether a thing can be a body at all.
 */
class MagicBlockTest {

    private static Map<String, String> dials(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static MagicBlock.Spec ok(Map<String, String> raw, String... effects) {
        MagicBlock.Parsed p = MagicBlock.parse(raw, List.of(effects));
        assertNull(p.refusal(), () -> "refused: " + p.refusal());
        return p.spec();
    }

    private static String no(Map<String, String> raw, String... effects) {
        MagicBlock.Parsed p = MagicBlock.parse(raw, List.of(effects));
        assertNotNull(p.refusal(), "should have been refused: " + raw);
        return p.refusal();
    }

    @Test
    void leftOutDialsGetWhatAChildExpects() {
        MagicBlock.Spec s = ok(dials(), "heal");
        assertEquals(MagicBlock.When.EVERY, s.when());
        assertEquals(40, s.periodTicks());
        assertEquals(MagicBlock.Who.EVERYONE, s.who());
        assertEquals(MagicBlock.DEFAULT_RADIUS, s.radius());
        assertEquals(MagicBlock.Where.HERE, s.where());
    }

    @Test
    void anythingThatHurtsReachesMonstersUnlessHeSaidOtherwise() {
        assertEquals(MagicBlock.Who.HOSTILE, ok(dials(), "lightning").who());
        assertEquals(MagicBlock.Who.HOSTILE, ok(dials(), "heal", "ignite").who());
        assertEquals(MagicBlock.Who.EVERYONE, ok(dials("who", "everyone"), "lightning").who(),
                "he may still ask for it to hit everything — the default is what changes");
        // THE COW RULE, in the selector itself: a hostile block's reach names the monsters' tag.
        String sel = ok(dials(), "lightning").selector();
        assertTrue(sel.contains("type=#moddingfromamod:hostile"), sel);
    }

    @Test
    void everyDialIsCapped() {
        no(dials("radius", "17"), "pull");
        no(dials("radius", "0.5"), "pull");
        no(dials("radius", "lots"), "pull");
        no(dials("seconds", "0.1"), "pull");
        no(dials("seconds", "61"), "pull");
        no(dials("when", "sometimes"), "pull");
        no(dials("who", "the moon"), "pull");
        no(dials("where", "everywhere"), "pull");
        assertEquals(16, ok(dials("radius", "16"), "pull").radius());
        assertEquals(5, ok(dials("seconds", "0.25"), "pull").periodTicks());
    }

    @Test
    void theChildsOwnWordsForWhenAreUnderstood() {
        assertEquals(MagicBlock.When.STEPPED_ON, ok(dials("when", "landed on"), "fly").when());
        assertEquals(MagicBlock.When.BROKEN, ok(dials("when", "break"), "outcome_table").when());
        assertEquals(MagicBlock.When.NEAR, ok(dials("when", "comes near"), "hurt").when());
        assertEquals(MagicBlock.Who.HOSTILE, ok(dials("who", "monsters"), "heal").who());
    }

    @Test
    void aPullOrAFillOnlyHappensAtTheBlock() {
        no(dials("where", "on_each"), "pull");
        no(dials("where", "on_each"), "fill_blocks");
        assertEquals(MagicBlock.Where.ON_EACH, ok(dials("where", "on_each"), "lightning").where());
        assertEquals("@s", ok(dials("where", "on_each"), "lightning").target());
    }

    @Test
    void aBlockWithNoPowerNeverGoesOffAndBreakingItHandsItBack() {
        // a new block of his own, no effect. Even `when: broken` must not try to fire.
        for (String when : new String[] {"every", "near", "stepped_on", "broken"}) {
            Map<String, String> a = MagicBlock.shapeArgs(ok(dials("when", when)), "f", "minecraft:stone",
                    false, false);
            assertEquals("", a.get("FIRE"), when);
            assertEquals("", a.get("TRIGGER"), when);
            assertTrue(a.get("ON_BROKEN").contains("refund"), when + ": " + a.get("ON_BROKEN"));
        }
    }

    @Test
    void aBrokenBlockThatNeverRollsSaysItIsTheSameEveryTime() {
        MagicBlock.Spec broken = ok(dials("when", "broken"), "rich");
        assertTrue(MagicBlock.sameEveryTimeNote(broken, java.util.List.of("rich")).contains("outcome_table"));
        assertEquals("", MagicBlock.sameEveryTimeNote(
                ok(dials("when", "broken"), "outcome_table"), java.util.List.of("outcome_table")));
        assertEquals("", MagicBlock.sameEveryTimeNote(ok(dials("when", "every"), "rich"),
                java.util.List.of("rich")));
        assertEquals("", MagicBlock.sameEveryTimeNote(null, java.util.List.of("rich")));
    }

    @Test
    void onEachOnAStepIsSaidTwiceSoItIsAcceptedNotRefused() {
        // A refusal for a harmless redundancy is a wasted turn on every wish of this shape.
        assertEquals(MagicBlock.Where.HERE,
                ok(dials("when", "stepped_on", "where", "on_each"), "launch").where());
    }

    @Test
    void steppedOnReachesOnlyWhatIsOnTopAndWhatIsAboutToLand() {
        MagicBlock.Spec s = ok(dials("when", "stepped_on"), "fly");
        String sel = s.selector();
        assertTrue(sel.startsWith("@e[dx=0,dy=3,dz=0,"), sel);
        String trigger = MagicBlock.shapeArgs(s, "f", "minecraft:white_wool", false).get("TRIGGER");
        assertTrue(trigger.contains("positioned ~-0.5 ~1 ~-0.5 if entity " + sel), trigger);
    }

    // ── pull ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void pullIsStrongestCloseInAndNothingAtTheEdge() {
        List<MagicBlock.PullBand> bands = MagicBlock.pullBands(12, 1.0);
        assertFalse(bands.isEmpty());
        for (int i = 1; i < bands.size(); i++) {
            assertTrue(bands.get(i).step() < bands.get(i - 1).step(),
                    "each ring out pulls less: " + bands);
            assertEquals(bands.get(i - 1).to(), bands.get(i).from(), "rings meet: " + bands);
        }
        assertEquals(12.0, bands.get(bands.size() - 1).to(), "the outermost ring stops AT the reach");
        assertTrue(bands.get(bands.size() - 1).step() <= 0.25,
                "and pulls only a quarter as hard there: " + bands);
        assertTrue(bands.get(0).from() >= MagicBlock.PULL_STOP,
                "it stops short of the centre, so nothing is pulled INTO the block");
    }

    @Test
    void pullStrengthIsWordsNeverANumber() {
        assertNotNull(MagicBlock.pullStep("gentle"));
        assertTrue(MagicBlock.pullStep("huge") > MagicBlock.pullStep("gentle"));
        assertNull(MagicBlock.pullStep("7"));
    }

    // ── the surprise table ───────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static List<MagicBlock.Outcome> table(List<String> presents, List<String> surprises, boolean boom) {
        Object t = MagicBlock.table(presents, surprises, boom);
        assertTrue(t instanceof List, () -> "refused: " + t);
        return (List<MagicBlock.Outcome>) t;
    }

    private static int share(List<MagicBlock.Outcome> t, String kind) {
        return t.stream().filter(o -> o.kind().equals(kind)).mapToInt(MagicBlock.Outcome::weight).sum();
    }

    @Test
    void theSurpriseTableAlwaysFeelsLucky() {
        List<List<Object>> cases = List.of(
                List.of(List.of("minecraft:diamond"), List.of("minecraft:chicken"), false),
                List.of(List.of("minecraft:diamond"), List.of(), true),
                List.of(List.of("minecraft:diamond*3", "minecraft:cake", "minecraft:emerald"),
                        List.of("minecraft:chicken", "minecraft:zombie"), true),
                List.of(List.of("a:1", "a:2", "a:3", "a:4", "a:5", "a:6", "a:7", "a:8"),
                        List.of("b:1", "b:2", "b:3", "b:4"), true));
        for (List<Object> c : cases) {
            @SuppressWarnings("unchecked")
            List<MagicBlock.Outcome> t = table((List<String>) c.get(0), (List<String>) c.get(1),
                    (Boolean) c.get(2));
            assertEquals(100, t.stream().mapToInt(MagicBlock.Outcome::weight).sum(), "adds to 100: " + t);
            assertTrue(share(t, "present") >= 75, "at least three in four are presents: " + t);
            assertTrue(share(t, "present") < 100, "never completely safe: " + t);
            assertTrue(share(t, "boom") < 5, "a bang is under one in twenty: " + t);
            assertTrue(t.stream().allMatch(o -> o.weight() > 0), "every outcome can come up: " + t);
        }
    }

    @Test
    void aTableThatCannotFeelLuckyIsRefused() {
        assertTrue(MagicBlock.table(List.of("minecraft:diamond"), List.of(), false) instanceof String,
                "all presents is a vending machine");
        assertTrue(MagicBlock.table(List.of(), List.of("minecraft:zombie"), true) instanceof String,
                "no presents is a trap");
        assertTrue(MagicBlock.table(List.of("minecraft:diamond*0"), List.of("minecraft:pig"), false)
                instanceof String);
    }

    // ── what a body may be ───────────────────────────────────────────────────────────────────

    @Test
    void aWalkThroughBodyOnBedrockIsRefused() {
        BlockLook.Facts bedrock = new BlockLook.Facts(true, false, false, false, true);
        assertNotNull(BlockLook.refusal("block.look", "minecraft:bedrock", bedrock, true, true));
        assertNotNull(BlockLook.refusal("block.look", "minecraft:bedrock", bedrock, true, false),
                "a solid one can never be broken, so it can never be picked back up");
        BlockLook.Facts stone = new BlockLook.Facts(true, false, false, false, false);
        assertNull(BlockLook.refusal("block.look", "minecraft:stone", stone, true, true));
    }

    @Test
    void aBodyNeverHoldsThingsAndIsNeverALiquid() {
        BlockLook.Facts chest = new BlockLook.Facts(true, false, false, true, false);
        assertNotNull(BlockLook.refusal("block.look", "minecraft:chest", chest, true, false));
        assertNotNull(BlockLook.refusal("fill_blocks's BLOCK", "minecraft:chest", chest, false, false));
        BlockLook.Facts water = new BlockLook.Facts(true, false, true, false, false);
        assertNotNull(BlockLook.refusal("block.look", "minecraft:water", water, true, false));
        assertNull(BlockLook.refusal("fill_blocks's BLOCK", "minecraft:water", water, false, false),
                "filling with water is exactly what fill_blocks is for");
    }

    @Test
    void aWalkThroughBlockCannotGoOffWhenBroken() {
        assertNotNull(MagicBlock.walkThroughRefusal(MagicBlock.When.BROKEN));
        assertNull(MagicBlock.walkThroughRefusal(MagicBlock.When.EVERY));
        Map<String, String> args = MagicBlock.shapeArgs(ok(dials(), "heal"), "f", "minecraft:glass", true);
        assertTrue(args.get("BUILD_LOOK").contains("block_display"), args.get("BUILD_LOOK"));
        assertFalse(args.get("BUILD_LOOK").contains("setblock"), "a walk-through look places no block");
    }

    @Test
    void breakingIsWhatSetsOffABrokenBlockAndNothingElseDoes() {
        Map<String, String> broken = MagicBlock.shapeArgs(ok(dials("when", "broken"), "outcome_table"),
                "f", "minecraft:gold_block", false);
        assertEquals("", broken.get("TRIGGER"), "no beat at all");
        assertTrue(broken.get("ON_BROKEN").contains("function $namespace:fire"));
        Map<String, String> every = MagicBlock.shapeArgs(ok(dials(), "pull"), "f", "minecraft:obsidian", false);
        assertTrue(every.get("ON_BROKEN").contains("refund"), "anything else is handed back");
        assertTrue(every.get("CLEAR_LOOK").contains("kill @e[type=minecraft:item"),
                "and its own drop is taken, so breaking it never makes a free block");
    }

    // ── the design check: a mod repo's cool blocks are settings, not products ───────────────────────

    /**
     * A real mod repo's seven (plus the blue lava), each as nothing but dials and existing effects. If one of
     * these ever needs a new effect or new code, the class has stopped being made of parts.
     */
    @Test
    void everyModRepoCoolBlockIsJustSettings() {
        ItemCatalogue catalogue = ItemCatalogue.load();
        record Case(String what, Map<String, String> dials, List<String> effects, boolean walk) {
        }
        List<Case> cases = List.of(
                new Case("lucky", dials("when", "broken"), List.of("outcome_table"), false),
                new Case("black hole", dials("when", "every", "seconds", "0.25", "who", "everyone",
                        "radius", "10"), List.of("pull"), false),
                new Case("anti-gravity", dials("radius", "8"), List.of("fly"), false),
                new Case("storm", dials("who", "hostile", "where", "on_each", "radius", "12"),
                        List.of("lightning"), false),
                new Case("volcano", dials("seconds", "4"), List.of("summon", "ignite"), false),
                new Case("cloud", dials("when", "stepped_on"), List.of("fly", "protect"), false),
                new Case("ghost", dials("when", "every", "seconds", "60"), List.of("status"), true),
                new Case("burning lava", dials("when", "stepped_on"), List.of("ignite"), false));
        for (Case c : cases) {
            MagicBlock.Spec spec = ok(c.dials(), c.effects().toArray(String[]::new));
            if (c.walk()) {
                assertNull(MagicBlock.walkThroughRefusal(spec.when()), c.what());
            }
            Map<String, Map<String, String>> args = new LinkedHashMap<>();
            for (String id : c.effects()) {
                ItemCatalogue.Entry e = catalogue.get(id);
                assertNotNull(e, c.what() + " needs an effect that does not exist: " + id);
                Map<String, String> a = ItemCatalogueTestSupport.argsFor(e, MagicBlock.SHAPE);
                a.put("TARGET", spec.target());
                args.put(id, a);
            }
            Map<String, String> shape = ItemCatalogueTestSupport.shapeArgs("cool_" + c.effects().get(0));
            shape.putAll(MagicBlock.shapeArgs(spec, "cool_" + c.effects().get(0), "minecraft:gold_block",
                    c.walk()));
            String source = catalogue.render(c.effects(), MagicBlock.SHAPE, args, shape);
            assertFalse(source.contains("${"), c.what() + " left a placeholder:\n" + source);
        }
    }
}
