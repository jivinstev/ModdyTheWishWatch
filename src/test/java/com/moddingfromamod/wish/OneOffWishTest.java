package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.moddingfromamod.marshal.MadeKind;
import com.moddingfromamod.marshal.WishRun;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Can the child have this again tomorrow? —. */
class OneOffWishTest {

    private static WishRun.Made made(String kind, String id) {
        return new WishRun.Made(kind, id);
    }

    private static final java.util.function.Predicate<String> ON_DISK = id -> true;
    private static final java.util.function.Predicate<String> MISSING = id -> false;

    @Test
    @DisplayName("a reshaped creature is said with the one rule (#2634)")
    void aCreatureIsSaidWithTheOneRule() {
        assertEquals("an Owl", OneOffWish.article("Owl"));
        assertEquals("a Snake", OneOffWish.article("Snake"));
        assertEquals("The Grinch", OneOffWish.article("The Grinch"));
        assertEquals("a Drowned", OneOffWish.article("Drowned"), "a creature is never 'some'");
    }

    @Test
    @DisplayName("a wish that made nothing at all is one-off")
    void nothingMade() {
        // The hand-built fallback: the agent ran commands, the effect appeared, nothing was saved.
        assertTrue(OneOffWish.isOneOff(List.of(), ON_DISK));
        assertTrue(OneOffWish.isOneOff(null, ON_DISK));
    }

    @Test
    @DisplayName("an artifact the child can re-make is not one-off")
    void persistedKinds() {
        for (String kind : new String[]{MadeKind.BUILD, MadeKind.CREATURE, MadeKind.ITEM,
                MadeKind.PAINTING}) {
            assertFalse(OneOffWish.isOneOff(List.of(made(kind, "x")), ON_DISK), kind);
        }
    }

    @Test
    @DisplayName("a live mob is not a recipe")
    void aMobIsNotPersistence() {
        // A MOB handle names one entity, which dies, despawns, or is left in a world he stops
        // playing. Counting it would call the commonest hand-built grant persistent.
        assertTrue(OneOffWish.isOneOff(List.of(made(MadeKind.MOB, "@zombie")), ON_DISK));
    }

    @Test
    @DisplayName("THE SPIKOR FIREBALL: a datapack id whose zip was never written")
    void failedCompileIsOneOff() {
        // DatapackForge only writes the zip on the success branch. The run records the id either
        // way, so anything reading the run alone cannot tell a compiled pack from a failed one.
        assertTrue(OneOffWish.isOneOff(List.of(made(MadeKind.DATAPACK, "spikor_fireball")), MISSING),
                "a datapack that never reached disk is not something he can have again");
        assertFalse(OneOffWish.isOneOff(List.of(made(MadeKind.DATAPACK, "freeze_bomb")), ON_DISK),
                "a datapack that IS on disk is exactly what persistence looks like");
    }

    @Test
    @DisplayName("one re-runnable thing is enough — a mixed wish is not one-off")
    void mixedIsNotOneOff() {
        assertFalse(OneOffWish.isOneOff(
                List.of(made(MadeKind.MOB, "@z"), made(MadeKind.BUILD, "castle")), ON_DISK));
    }

    @Test
    @DisplayName("a damaged record is not counted as persistence")
    void nullsAreNotPersistence() {
        assertTrue(OneOffWish.isOneOff(List.of(made(null, "x")), ON_DISK));
        assertFalse(OneOffWish.rerunnable(null, ON_DISK));
    }

    @Test
    @DisplayName("what the child hears is not a failure")
    void theMessageReadsAsSpecialNotBroken() {
        String m = OneOffWish.TELL_THE_CHILD;
        assertEquals(WishWaitWords.ONE_OF_A_KIND, m, "the approved words, #1373");
        assertTrue(m.contains("one of a kind") && m.contains("enjoy it now"), m);
        assertFalse(m.toLowerCase(java.util.Locale.ROOT).contains("tricky"),
                "#1373: no difficulty claim: " + m);
        for (String bad : new String[]{"error", "failed", "sorry", "couldn't make", "broken"}) {
            assertFalse(m.toLowerCase(java.util.Locale.ROOT).contains(bad),
                    "the wish WORKED and he is watching it work; this reads as a failure: " + m);
        }
    }

    @Test
    @DisplayName("the log line joins to the other logs, and omits rather than says null")
    void theRecordIsJoinable() {
        var line = OneOffWishLog.record("w1", "make spikor shoot fireballs",
                List.of(made(MadeKind.MOB, "@spikor")), "modwatch-daddus", "s-abc", 1234L);
        assertEquals(1234L, line.get("at").getAsLong());
        assertEquals("modwatch-daddus", line.get("world").getAsString());
        assertEquals("s-abc", line.get("session").getAsString());
        assertEquals(1, line.getAsJsonArray("made").size());

        var titleScreen = OneOffWishLog.record("w2", "x", List.of(), null, "  ", 1L);
        assertFalse(titleScreen.has("world"), titleScreen.toString());
        assertFalse(titleScreen.has("session"), titleScreen.toString());
        // Recorded even when empty: a wish that made NOTHING is the case this exists to count.
        assertTrue(titleScreen.has("made"), titleScreen.toString());
        assertEquals(0, titleScreen.getAsJsonArray("made").size());
    }

    /** "I'll remember it, tap its button" must not be followed by "I can't save it". */
    @Test
    void aTaughtButtonIsSomethingHeCanHaveAgain() {
        assertFalse(OneOffWish.isOneOff(List.of(), List.of("run_command", "add_template"), ON_DISK),
                "a wish granted by commands and then TAUGHT is not one-off");
        assertTrue(OneOffWish.isOneOff(List.of(), List.of("run_command"), ON_DISK),
                "commands alone, nothing taught, still is");
        assertTrue(OneOffWish.isOneOff(List.of(), null, ON_DISK));
    }

    /**, D3: a help wish says nothing about saving. */
    @org.junit.jupiter.api.Test
    void helpIsAQuestionAFollowupOrNoChange() {
        org.junit.jupiter.api.Assertions.assertTrue(OneOffWish.isHelp(true, false, true));
        org.junit.jupiter.api.Assertions.assertTrue(OneOffWish.isHelp(false, true, true));
        org.junit.jupiter.api.Assertions.assertTrue(OneOffWish.isHelp(false, false, false));
        org.junit.jupiter.api.Assertions.assertFalse(OneOffWish.isHelp(false, false, true),
                "a new thing that changed the world is not help");
    }

    // ──: an edit of a creature he keeps is SAVED ─────────────────────────────────────────

    private static WishRun.CreatureEdit edit(String id, String before, String after, boolean forked,
                                             boolean reshaped) {
        return new WishRun.CreatureEdit(id, before, after, forked, reshaped);
    }

    @Test
    @DisplayName("#1628: modify_mob on a stored creature (a mob handle only) is NOT one-off once the edit is recorded")
    void aModifyMobEditOfAStoredCreatureIsNotOneOff() {
        List<WishRun.Made> handleOnly = List.of(made(MadeKind.MOB, "@snake"));
        // what it was: the handle alone read as "nothing saved"
        assertTrue(OneOffWish.isOneOff(handleOnly, List.of("modify_mob"), ON_DISK));
        assertFalse(OneOffWish.isOneOff(handleOnly, List.of("modify_mob"),
                List.of(edit("rp_snake", "Snake", "Snake", false, false)), ON_DISK));
        // paint_creature records nothing in made() at all
        assertFalse(OneOffWish.isOneOff(List.of(), List.of("paint_creature"),
                List.of(edit("rp_snake", "Snake", "Snake", false, false)), ON_DISK));
    }

    @Test
    @DisplayName("#1628: a hand-built effect is still one-off — the line keeps the case it was written for")
    void aHandBuiltEffectIsStillOneOff() {
        assertTrue(OneOffWish.isOneOff(List.of(), List.of("run_command"), List.of(), ON_DISK));
        assertTrue(OneOffWish.isOneOff(List.of(made(MadeKind.DATAPACK, "fireball")),
                List.of("author_datapack"), List.of(), MISSING));
        assertTrue(OneOffWish.isOneOff(List.of(made(MadeKind.MOB, "@cow")), List.of("modify_mob"),
                null, ON_DISK), "a vanilla cow made huge is still a live animal, not a recipe");
    }

    @Test
    @DisplayName("#1628: the saved line says what is true — became, renamed, kept, his own copy")
    void theSavedLineSaysWhatIsTrue() {
        assertEquals(null, OneOffWish.savedEditLine(List.of()));
        assertEquals("Your Troll is now a Snake — saved, so it'll be like this next time too!",
                OneOffWish.savedEditLine(List.of(edit("rp_troll", "Troll", "Snake", false, true))));
        assertEquals("Your Troll is now an Owl — saved, so it'll be like this next time too!",
                OneOffWish.savedEditLine(List.of(edit("rp_troll", "Troll", "Owl", false, true))));
        assertEquals("Your Wolf is now called Sky — saved, so it'll be like this next time too!",
                OneOffWish.savedEditLine(List.of(edit("rp_wolf", "Wolf", "Sky", false, false))));
        assertEquals("Your Snake is saved like this — it'll be like this next time too!",
                OneOffWish.savedEditLine(List.of(edit("rp_snake", "Snake", "Snake", false, false))));
        assertEquals("This Spikor is your very own now — saved, so it'll be like this next time too!",
                OneOffWish.savedEditLine(List.of(edit("spikor~ab12", "Spikor", "Spikor", true, false))));
        assertEquals("Your Seahorse and Crab are saved like this — they'll be like this next time too!",
                OneOffWish.savedEditLine(List.of(edit("sh", "Seahorse", "Seahorse", false, false),
                        edit("cr", "Crab", "Crab", false, false))));
        assertEquals("Your creature is saved like this — it'll be like this next time too!",
                OneOffWish.savedEditLine(List.of(edit("x", "", " ", false, false))));
    }

    @Test
    @DisplayName("#1628: several edits of one creature in a wish keep its FIRST name and its LAST")
    void severalEditsKeepTheFirstNameAndTheLast() {
        WishRun run = WishRun.detachedForTest(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "turn my troll into a snake");
        run.creatureEdited("rp_troll", "Troll", "Swamp Snake", false, true);
        run.creatureEdited("rp_troll", "Swamp Snake", "Snake", false, false);
        assertEquals(List.of(edit("rp_troll", "Troll", "Snake", false, true)), run.creaturesEdited());
    }
}
