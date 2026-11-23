package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Gate A 's join: which taught recipe is the one for which of his things. */
class TaughtJoinTest {

    private static TaughtJoin.Taught t(String id, String... commands) {
        return new TaughtJoin.Taught(id, List.of(commands));
    }

    @Test
    void everyReplayCommandNamesItsThing() {
        assertEquals(List.of("castle_1", "dragon_2", "heart_3", "zap_wand"),
                TaughtJoin.artifactsNamed(List.of(
                        "wishbuild castle_1",
                        "wishmob spawn Dragon_2",
                        "wishpaint heart_3",
                        // wishtrain / wishmachine went with Create: an old recipe that
                        // says one names nothing.
                        "wishtrain train_3 ~ ~ ~",
                        "wishmachine grinder_4",
                        "wishdatapack zap_wand give")));
    }

    @Test
    void plainCommandsNameNothing() {
        assertEquals(List.of(), TaughtJoin.artifactsNamed(List.of(
                "summon minecraft:chicken ~ ~14 ~",
                "wishsummon minecraft:cow {\"scale\":4}",
                "say wishbuild castle_1",
                "wishmob reload dragon_2",
                "wishbuild")));
    }

    @Test
    void aReplayAfterExecuteRunStillCounts() {
        assertEquals(List.of("castle_1"), TaughtJoin.artifactsNamed(
                List.of("execute at @p run wishbuild castle_1")));
    }

    @Test
    void anItemIsJoinedThroughItsCatalogueKey() {
        var wand = t("zap_wand_tpl", "wishdatapack zap_wand give");
        assertTrue(TaughtJoin.names(wand, "id:zap_wand"), "an item's key is id:<feature id>");
        assertTrue(TaughtJoin.names(wand, "zap_wand"));
        assertFalse(TaughtJoin.names(wand, "id:zap_wand_2"));
    }

    @Test
    void noFuzzyMatchingOnNames() {
        // The words match perfectly; the commands name a different thing. No join.
        var dragon = t("fire_dragon", "wishmob spawn dragon_7");
        assertFalse(TaughtJoin.names(dragon, "fire_dragon"));
        assertFalse(TaughtJoin.names(dragon, "dragon"));
        assertTrue(TaughtJoin.names(dragon, "DRAGON_7"), "ids compare without case");
    }

    @Test
    void theNewestRecipeForAThingWins() {
        var taught = List.of(t("new_one", "wishbuild castle_1"), t("old_one", "wishbuild castle_1"),
                t("other", "wishbuild tower_2"));
        assertEquals("new_one", TaughtJoin.recipeFor("castle_1", taught));
        assertEquals("other", TaughtJoin.recipeFor("tower_2", taught));
        assertEquals("", TaughtJoin.recipeFor("nothing_9", taught));
        assertEquals("", TaughtJoin.recipeFor("castle_1", null));
    }

    @Test
    void joinedIsEveryRecipeWithAThingOnThePanelAndNoOrphan() {
        var taught = List.of(t("new_one", "wishbuild castle_1"), t("old_one", "wishbuild castle_1"),
                t("gone", "wishbuild removed_3"), t("rain", "summon minecraft:chicken ~ ~9 ~"));
        assertEquals(Set.of("new_one", "old_one"),
                TaughtJoin.joined(List.of("castle_1", "id:zap"), taught));
        assertEquals(Set.of(), TaughtJoin.joined(List.of(), taught));
    }

    @Test
    void aBrothersRecipeNeverJoinsThisChildsTile() {
        String steve = "11111111-1111-1111-1111-111111111111";
        String brother = "22222222-2222-2222-2222-222222222222";
        // A building made before shelves has no owner, so it is on BOTH panels. Each brother taught
        // a recipe for it.
        var taught = List.of(
                new TaughtJoin.Taught("brothers_mill", List.of("wishbuild mill_1"), brother),
                new TaughtJoin.Taught("ezras_mill", List.of("wishbuild mill_1"), steve),
                new TaughtJoin.Taught("old_shared", List.of("wishbuild hut_2"), ""));

        var mine = TaughtJoin.visibleTo(steve, taught);
        assertEquals("ezras_mill", TaughtJoin.recipeFor("mill_1", mine),
                "the newest recipe OVERALL is the brother's; his own must win on his panel");
        assertEquals("old_shared", TaughtJoin.recipeFor("hut_2", mine),
                "an unowned (pre-shelf) recipe is still everyone's");
        assertEquals(Set.of("ezras_mill", "old_shared"),
                TaughtJoin.joined(List.of("mill_1", "hut_2"), mine),
                "the brother's recipe is not hidden from Steve's panel — it was never on it");

        var theirs = TaughtJoin.visibleTo(brother, taught);
        assertEquals("brothers_mill", TaughtJoin.recipeFor("mill_1", theirs));

        assertEquals("", TaughtJoin.recipeFor("mill_1",
                TaughtJoin.visibleTo(steve, List.of(taught.get(0)))),
                "with only a brother's recipe, his tile offers none rather than the brother's");
    }

    // ──: a wish and its parts ──────────────────────────────────────────────────────────

    /** The oasis: a building, a machine (an item) and a drink, and one raw give beside them. */
    private static TaughtJoin.Taught oasis(List<String> parts) {
        return new TaughtJoin.Taught("tropical_oasis", List.of(
                "wishbuild oasis_1",
                "wishdatapack oasis_bar give",
                "wishdatapack coconut_drink give",
                "give @p minecraft:melon 4"), "", parts);
    }

    @Test
    void theWishEntryRemakesAllAndEachPartOnlyItself() {
        var wish = oasis(List.of());
        assertTrue(TaughtJoin.isWish(wish), "three things made: a wish, not one thing's recipe");
        assertEquals(TaughtJoin.partRecipe("tropical_oasis", "oasis_bar"),
                TaughtJoin.recipeFor("id:oasis_bar", List.of(wish)),
                "the bar's tile must re-wish the BAR, not rebuild the whole oasis");
        assertEquals(List.of("wishdatapack oasis_bar give"),
                TaughtJoin.commandsFor(wish.commands(), "id:oasis_bar"),
                "a part replays only its own commands — a raw give belongs to the whole wish");
        assertEquals(Set.of(), TaughtJoin.joined(List.of("oasis_1", "id:oasis_bar"), List.of(wish)),
                "the wish keeps its own button: it is the entry that re-wishes all of it");
        assertEquals(List.of("tropical_oasis"), TaughtJoin.wishesContaining("oasis_1", List.of(wish))
                .stream().map(TaughtJoin.Taught::id).toList());
    }

    @Test
    void aOneThingRecipeStillLivesOnItsTile() {
        var castle = t("castle_tpl", "wishbuild castle_1");
        assertFalse(TaughtJoin.isWish(castle));
        assertEquals("castle_tpl", TaughtJoin.recipeFor("castle_1", List.of(castle)));
        assertEquals(Set.of("castle_tpl"), TaughtJoin.joined(List.of("castle_1"), List.of(castle)));
        assertEquals(List.of(), TaughtJoin.wishesContaining("castle_1", List.of(castle)),
                "a thing's own recipe is not a wish it is a PART of");
    }

    @Test
    void thePartsRecordedAtTheGrantWinOverWhatTheCommandsName() {
        // Recorded: the wish made the building and the bar; the drink command is a leftover.
        var wish = oasis(List.of("oasis_1", "id:oasis_bar"));
        assertEquals(List.of("oasis_1", "oasis_bar"), TaughtJoin.partsOf(wish));
        assertFalse(TaughtJoin.names(wish, "id:coconut_drink"),
                "with parts recorded, the record says what the wish made");
        // Not recorded (taught, no backfill): the commands still say it.
        assertEquals(List.of("oasis_1", "oasis_bar", "coconut_drink"),
                TaughtJoin.partsOf(oasis(List.of())));
    }

    @Test
    void aPartMayBelongToMoreThanOneWish() {
        var garage = new TaughtJoin.Taught("dino_garage", List.of("wishbuild garage_1",
                "wishmob spawn t_rex"), "", List.of());
        var park = new TaughtJoin.Taught("dino_park", List.of("wishbuild park_2",
                "wishmob spawn t_rex", "wishmob spawn raptor"), "", List.of());
        assertEquals(List.of("dino_garage", "dino_park"),
                TaughtJoin.wishesContaining("t_rex", List.of(garage, park)).stream()
                        .map(TaughtJoin.Taught::id).toList());
        assertEquals(TaughtJoin.partRecipe("dino_garage", "t_rex"),
                TaughtJoin.recipeFor("t_rex", List.of(garage, park)), "the newest wish's part recipe");
    }

    @Test
    void aPartNoCommandCanRemakeGetsNoButtonRatherThanTheWholeWish() {
        var wish = new TaughtJoin.Taught("oasis", List.of("wishbuild oasis_1",
                "wishdatapack oasis_bar give"), "", List.of("oasis_1", "oasis_bar", "palm_3"));
        assertEquals("", TaughtJoin.recipeFor("palm_3", List.of(wish)));
    }

    @Test
    void aPartRecipeIdSplitsBackIntoItsWishAndPart() {
        String id = TaughtJoin.partRecipe("oasis", "id:Bar~1F2E3D4C");
        assertEquals("oasis@bar~1f2e3d4c", id);
        assertEquals(List.of("oasis", "bar~1f2e3d4c"), List.of(TaughtJoin.splitPart(id)));
        assertEquals(null, TaughtJoin.splitPart("plain_recipe"));
        assertEquals(null, TaughtJoin.splitPart("@nothing"));
    }
}
