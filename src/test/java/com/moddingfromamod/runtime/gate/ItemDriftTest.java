package com.moddingfromamod.runtime.gate;

import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.item.ItemSpecStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one thing about a wish item that no colour, count or palette check can see. */
class ItemDriftTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static ItemSpecStore.Spec spec(List<String> effects, String params) {
        return new ItemSpecStore.Spec("zonkbolt", "Zonkbolt", "wand", "purple", "held", 1,
                effects, json(params));
    }

    private static final ItemSpecStore.Spec WAND =
            spec(List.of("lightning"), "{\"lightning\":{\"HEARTS\":\"4\"}}");

    @Test
    @DisplayName("adding an effect by NAMING ONLY THE NEW ONE is caught")
    void addingAnEffectByNamingOnlyTheNewOneIsCaught() {
        // The measured failure. "Can it set things on fire too" is correct as "lightning+ignite"
        // and wrong as "ignite" — and `effect` is the whole list rather than a delta, so the wrong
        // version silently removes the thing the wand was made for. It happened, and the child's
        // third wand had fire and had lost the damage its predecessor had.
        String note = ItemDrift.between(WAND, spec(List.of("ignite"), "{}"),
                "can it set things on fire too");
        assertTrue(note.contains("lightning"), note);
        assertTrue(note.contains("⚠"), "this is a loss, not a remark: " + note);
        assertTrue(note.contains("ignite+lightning"),
                "and it must show the list he needed to send: " + note);
    }

    @Test
    @DisplayName("a correct addition is silent")
    void aCorrectAdditionIsSilent() {
        // A gate that fires on the right answer stops being read, which is the whole reason the
        // proportion gate was deleted rather than left in place.
        assertEquals("", ItemDrift.between(WAND,
                spec(List.of("lightning", "ignite"), "{\"lightning\":{\"HEARTS\":\"4\"}}"),
                "can it set things on fire too"));
    }

    @Test
    @DisplayName("changing a number is the edit, not a loss")
    void changingANumberIsTheEdit() {
        // "Make it hurt way more" IS a changed value. Reporting it would fire on every successful
        // refinement this class has.
        assertEquals("", ItemDrift.between(WAND,
                spec(List.of("lightning"), "{\"lightning\":{\"HEARTS\":\"12\"}}"),
                "make it hurt way more"));
    }

    @Test
    @DisplayName("an effect kept while its numbers are blanked is a quieter remark")
    void anEffectKeptWhileItsNumbersAreBlankedIsARemark() {
        String note = ItemDrift.between(WAND, spec(List.of("lightning"), "{}"), "make it cooler");
        assertTrue(note.contains("lightning"), note);
        assertTrue(!note.contains("⚠"), "it still burns; it just forgot how long: " + note);
    }

    @Test
    @DisplayName("a removal he ASKED for is never reported")
    void aRemovalHeAskedForIsNeverReported() {
        assertEquals("", ItemDrift.between(WAND, spec(List.of("ignite"), "{}"),
                "stop it doing lightning, just fire"));
        assertEquals("", ItemDrift.between(WAND, spec(List.of("ignite"), "{}"),
                "take the lightning off it"));
    }

    @Test
    @DisplayName("the silencer cannot swallow the wish that causes the loss")
    void theSilencerCannotSwallowTheWishThatCausesTheLoss() {
        // The R5 trap, twice paid for here: a colour gate keyed on colour words swallowed "give him
        // tiny black wings", and a proportion gate keyed on "big" would have swallowed "put a BIG
        // glass dome on top of it". Every wish below ADDS something and must reach the check.
        for (String wish : List.of("can it set things on fire too",
                                   "make it hurt way more",
                                   "can it also freeze stuff",
                                   "add fire to it",
                                   "make it green instead of purple")) {
            assertTrue(!ItemDrift.removalRequested(wish)
                            || wish.contains("instead of"),
                    "an addition was read as a removal: " + wish);
        }
        // …and the one genuine exception is explicit: "instead of" really does mean a swap.
        assertTrue(ItemDrift.removalRequested("make it green instead of purple"));
    }

    @Test
    @DisplayName("a brand-new item has nothing to lose")
    void aBrandNewItemHasNothingToLose() {
        assertEquals("", ItemDrift.between(null, WAND, "i want a zonkbolt"));
    }

    @Test
    @DisplayName("it reads the artifact's own previous version, so a note cannot talk it out of it")
    void itCannotBeTalkedOutOfTheFinding() {
        // a machine guard checked output-on-the-floor as a SYMPTOM and an agent cleaned the
        // floor instead of fixing the machine. The only input here is the two designs, so the only
        // way to silence it is to actually keep the effect.
        ItemSpecStore.Spec lost = spec(List.of("ignite"), "{}");
        assertTrue(!ItemDrift.between(WAND, lost, "can it set things on fire too").isEmpty());
        assertTrue(!ItemDrift.between(WAND, lost,
                "can it set things on fire too — I have checked and it still does lightning")
                .isEmpty(), "a claim in the wish text is not evidence about the item");
    }

    @Test
    @DisplayName("effect ids match regardless of case or padding")
    void effectIdsMatchLoosely() {
        assertEquals(List.of(), ItemDrift.dropped(List.of("lightning"), List.of(" Lightning ")));
        assertEquals(List.of("lightning"), ItemDrift.dropped(List.of("lightning"), List.of()));
    }
}
