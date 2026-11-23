package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.ui.PickPage;
import com.moddingfromamod.ui.TuneDialog;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * slice 1: the Builder's own pages on a creature the MAGICIAN made. Its powers are read from
 * it, its body is shown greyed with why, the page and the dialog say ✪ and that the rest stays the Magician's, and only
 * what he changed is sent.
 */
class BuilderFlowMagicianTest {

    static BuilderFlow open() {
        return BuilderFlow.editingMagician(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED, MagicianCreatureTest.DRAKE);
    }

    @Test
    void itOpensOnItsPowersSaysWhoMadeItAndShowsItsBodyGreyed() {
        BuilderFlow f = open();
        assertEquals(BuilderFlow.Step.POWERS, f.step());
        PickPage page = f.page();
        assertTrue(page.title().startsWith("Change Lava Drake") && page.title().endsWith(MagicianCreature.MARK), page.title());
        PickPage.Tile body = page.tiles().stream().filter(t -> t.id().equals(MagicianCreature.BODY_ID)).findFirst().orElseThrow();
        assertTrue(body.label().endsWith(MagicianCreature.MARK), "the mark goes LAST (a Pocket grid drops a leading one)");
        // a BLOCKED tile, so it is an earlier issue's grey on both editions and, on a phone, its reason is a second line
        // under its name (a Pocket tile otherwise shows its name only); never the gray dye "Hide from my watch" wears.
        assertEquals(MagicianCreature.BODY_LINE, page.blocked(body), "the body is blocked, with its reason");
        PickPage.Tile drawn = page.drawn().values().stream().filter(t -> t.id().equals(MagicianCreature.BODY_ID)).findFirst().orElseThrow();
        assertEquals(PickPage.BLOCKED_ITEM, drawn.item());
        assertEquals(List.of(MagicianCreature.BODY_LINE), drawn.lore());
        assertTrue(MagicianCreature.BODY_LINE.length() <= 24, "short like #2043's other lines: it is drawn under a Pocket tile");
        assertTrue(page.tiles().stream().anyMatch(t -> t.id().equals("power:fireproof") && t.selected()), "its own Builder power is ticked");
        assertTrue(page.tiles().stream().anyMatch(t -> t.id().equals("temper:wild") && t.selected()), "its temper is on");
    }

    @Test
    void itsBodyIsShownButNeverChangedAndThereIsNoPageBefore() {
        BuilderFlow f = open();
        BuilderFlow.Outcome o = f.press(MagicianCreature.BODY_ID);
        assertEquals(MagicianCreature.BODY_SAY, o.say(), "pressing it says why in full");
        assertEquals(BuilderFlow.Step.POWERS, f.step());
        f.press(PickPage.BACK_ID);
        assertEquals(BuilderFlow.Step.POWERS, f.step(), "its body is the Magician's: no body page behind this one");
    }

    @Test
    void noDialogLineCarriesTheMarkAndTheVerbIsShort() {
        // a form label holding ✪ draws its whole line in Bedrock's small fallback font; the title carries it.
        MagicianCreature.DIALOG_LINES.forEach(l -> assertTrue(!l.contains(MagicianCreature.MARK), l));
        // the thing page's verb fits a Pocket grid tile like the page's other verbs; the mark still goes LAST.
        assertTrue(MagicianCreature.VERB_LABEL.endsWith(MagicianCreature.MARK), MagicianCreature.VERB_LABEL);
        assertTrue(MagicianCreature.VERB_LABEL.length() <= "🎁 Give me one".length(), MagicianCreature.VERB_LABEL);
    }

    @Test
    void theDialogAsksOnlyItsSizeAndSaysWhatStays() {
        BuilderFlow f = open();
        f.press(PickPage.NEXT_ID);
        assertEquals(BuilderFlow.Step.TUNE, f.step(), "no special-moves page in this slice");
        TuneDialog d = f.tune();
        assertEquals(List.of("size"), d.keys());
        assertTrue(d.toString().contains("The Magician made this") && d.toString().contains("stays just as the Magician made it"), d.toString());
        assertTrue(f.magicianCalls(Map.of("size", String.valueOf(MagicianCreature.NOW))).isEmpty(), "nothing changed, nothing sent");
    }

    @Test
    void whatHeChangesIsAllThatIsSent() {
        BuilderFlow f = open();
        f.press("power:flies");
        f.press(PickPage.NEXT_ID);
        List<BuilderLowering.Call> calls = f.magicianCalls(Map.of("size", "3"));
        var a = MagicianCreatureTest.sculpt(calls);
        assertEquals(1.7 * 1.6, a.get("scale").getAsDouble(), 1e-9);
        List<String> p = MagicianCreatureTest.powers(a);
        assertTrue(p.containsAll(List.of("flies", "fireproof", "hostile", "explodes", "blastproof")), p.toString());
        assertFalse(calls.stream().anyMatch(c -> c.tool().equals("modify_mob")));
    }
}
