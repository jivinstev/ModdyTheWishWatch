package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The fixes the R1 ratings asked for: how many by the world's spawn rules, amounts said with no
 * number, how a real creature acts, any creature for a rain, and what he means to ride, wear or
 * be. Each through the real Apprentice and Builder, as a child's words.
 */
class R1FixesTest {

    private static final BuilderTree TREE = BuilderTree.of(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
    private static final Apprentice APPRENTICE = new Apprentice(TREE, Apprentice.english());

    private static Apprentice.Pick top(String text) {
        Apprentice.Answer a = APPRENTICE.match(text);
        assertFalse(a.offers().isEmpty(), "no tile for '" + text + "'");
        return a.offers().get(0);
    }

    private static Apprentice.Pick first(String text, String kind) {
        return APPRENTICE.match(text).offers().stream().filter(p -> p.kind().equals(kind)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + kind + " tile for '" + text + "'"));
    }

    // ----: the world's spawn rules, not a fixed five

    @Test
    void howManyIsTheWorldsSpawnRuleForThatCreature() {
        assertEquals(25, BuilderCounts.most(BuilderAllowlist.REVIEWED, "mob:minecraft:bee"), "flyers: 25 a wish");
        assertEquals(100, BuilderCounts.most(BuilderAllowlist.REVIEWED, "mob:minecraft:cow"));
        assertEquals(50, BuilderCounts.most(BuilderAllowlist.REVIEWED, "mob:minecraft:creeper"));
        assertEquals(1, BuilderCounts.most(BuilderAllowlist.REVIEWED, "mob:minecraft:warden"));
        assertEquals(50, BuilderCounts.most(BuilderAllowlist.REVIEWED, "preset:species/pet_dragon"));
        assertEquals(BuilderCounts.OTHER, BuilderCounts.most(BuilderAllowlist.REVIEWED, BuilderSpec.Kind.ITEM, ""));
        var three = new BuilderAllowlist(BuilderAllowlist.REVIEWED.kinds(), BuilderAllowlist.REVIEWED.ids(),
                BuilderAllowlist.REVIEWED.tempers(), 3);
        assertEquals(3, BuilderCounts.most(three, "mob:minecraft:cow"), "a world's own Builder number only lowers it");
    }

    @Test
    void fiftyBeesAreTheMostFlyersWithTheReasonOnTheTile() {
        Apprentice.Pick bees = top("please make 50 bees");
        assertEquals("25", bees.first("count"), bees.toString());
        assertEquals("25 flyers is the most at once here (for 50)",
                ApprenticeWords.mostOr(TREE, bees, List.of("50", "25")));
    }

    @Test
    void anArmyIsAsManyAsThereMayBe() {
        Apprentice.Pick army = top("a velociraptor army");
        assertTrue(Integer.parseInt(army.first("count")) > 5, army.toString());
    }

    @Test
    void theRealMobLoweringSummonsInBatchesTheWorldThenFits() {
        BuilderFlow f = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
        f.press("creature");
        f.press("mob:minecraft:cow");
        BuilderSpec cows = f.spec(java.util.Map.of("count", "100"));
        assertEquals(100, cows.count());
        var calls = BuilderLowering.lower(cows, "c_1", LibraryPresets.SHIPPED);
        long batches = calls.stream().filter(c -> c.tool().equals("run_command")).count();
        assertEquals(4, batches, "32 commands a run_command: 100 cows are four");
    }

    // ----: amounts with no number

    @Test
    void aPluralIsAHandfulAndLotsIsAStack() {
        assertEquals("16", first("cookies", "supplies").first("amount"), "a plural with no number is a handful");
        assertEquals("64", first("lots and lots of cookies", "supplies").first("amount"));
        assertEquals("", first("a cookie", "supplies").first("amount"), "one is one");
    }

    // ----: how a real creature acts

    @Test
    void aRealCreatureCanBeFriendlyAndItIsLoweredByModifyMob() {
        BuilderFlow f = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
        f.press("creature");
        f.press("mob:minecraft:cow");
        BuilderSpec cow = f.spec(java.util.Map.of(BuilderActs.SLOT, "pet"));
        assertEquals("pet", cow.opt(BuilderActs.SLOT));
        var calls = BuilderLowering.lower(cow, "c_2", LibraryPresets.SHIPPED);
        var mm = calls.stream().filter(c -> c.tool().equals("modify_mob")).findFirst().orElseThrow();
        assertTrue(mm.args().get("friendly").getAsBoolean() && mm.args().get("follow").getAsBoolean(), mm.args().toString());
        BuilderSpec plain = f.spec(java.util.Map.of());
        assertEquals("", plain.opt(BuilderActs.SLOT), "as the game makes it is never stored");
    }

    // ----: any creature for a rain

    @Test
    void itRainsSlimes() {
        assertNotNull(BuilderEvents.found("minecraft:slime"));
        assertNull(BuilderEvents.found("minecraft:wither"), "never a rain of bosses");
        Apprentice.Pick rain = first("make it rain slimes", "event");
        assertEquals("what:minecraft:slime", rain.first("what"), rain.toString());
        assertTrue(BuilderEvents.title("rain", "minecraft:slime").toLowerCase().contains("slimes"));
    }

    // ----: what he means to ride, wear or be

    @Test
    void aThingToRideIsNeverSureAsAnOrnament() {
        Apprentice.Answer a = APPRENTICE.match("skateboard");
        assertNotEquals(Apprentice.Band.SURE, APPRENTICE.confidence("skateboard", a).band());
    }

    @Test
    void aPictureOfMeIsNeverAPerson() {
        assertTrue(APPRENTICE.match("a picture of me as a pirate").offers().stream()
                .noneMatch(p -> p.first("from").equals("bodies/person")));
    }

    @Test
    void aCrownHeWearsComesFirst() {
        Apprentice.Pick crown = top("a gold crown i can ware");
        assertEquals("item", crown.kind(), crown.toString());
        assertTrue(ApprenticeWords.label(TREE, crown).contains("(wear it)"), ApprenticeWords.label(TREE, crown));
    }
}
