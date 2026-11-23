package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Every spelling a real agent has written, and what each one means.</b>
 *
 * <p>Not invented cases: the WISHED rows are the three forms that actually turn up — the namespaced
 * one the agent invents unprompted, the bare feature id {@code make_item} hands back, and the name
 * a child says. The BLANK row is the one a registry check waves through.
 */
class ItemRefTest {

    @Test
    @DisplayName("a vanilla or modded id is REGISTERED, whichever namespace it is in")
    void realItemsParseAsRegistered() {
        for (String id : new String[]{"minecraft:cocoa_beans", "create:cogwheel",
                                      "some_mod:a/nested_path", "a-mod.x:thing"}) {
            ItemRef r = ItemRef.parse(id);
            assertEquals(ItemRef.Kind.REGISTERED, r.kind(), id);
            assertEquals(id, r.text());
            assertNull(r.refusal("x"), "a well-shaped id is refused only by the registry: " + id);
        }
    }

    @Test
    @DisplayName("all three spellings of a made item mean the same feature")
    void everySpellingOfAMadeItemAgrees() {
        for (String written : new String[]{"moddingfromamod:hot_coffee_plain", "hot_coffee_plain",
                                           "  Hot_Coffee_Plain  "}) {
            ItemRef r = ItemRef.parse(written);
            assertEquals(ItemRef.Kind.WISHED, r.kind(), written);
            assertEquals("hot_coffee_plain", r.feature(),
                    "the agent invents the namespaced form and make_item hands back the bare one; "
                            + "both have to land on the same item: " + written);
            assertNull(r.refusal("x"));
        }
    }

    @Test
    @DisplayName("the blank wish item is its own case — it RESOLVES, which is what makes it unsafe")
    void theBlankIsRefusedByShapeRatherThanByTheRegistry() {
        ItemRef r = ItemRef.parse(ItemBase.WISH_ITEM);
        assertEquals(ItemRef.Kind.BLANK, r.kind(),
                "a registry check answers 'yes, that exists' — which is exactly the wrong answer");
        String no = r.refusal("turn_into's FROM");
        assertNotNull(no);
        assertTrue(no.contains("any wish item at all"), no);
        assertTrue(no.contains("make_item"), "say the route that works: " + no);
    }

    @Test
    @DisplayName("an id-shaped string that is not an id is MALFORMED")
    void aBrokenIdIsMalformed() {
        for (String bad : new String[]{null, "", "   ", "minecraft:", ":cobblestone",
                                       "moddingfromamod:Not A Feature", "MOD:Bad Path"}) {
            ItemRef r = ItemRef.parse(bad);
            assertEquals(ItemRef.Kind.MALFORMED, r.kind(), String.valueOf(bad));
            String no = r.refusal("rich's ITEM");
            assertNotNull(no, String.valueOf(bad));
            assertTrue(no.contains("minecraft:cocoa_beans") && no.contains("make_item"),
                    "a refusal that does not say what a good answer looks like is a wall: " + no);
        }
    }

    /**
     * A string with no namespace cannot be a real item, so the only useful reading is <i>"he is
     * trying to name something we made"</i> — and that makes the refusal <b>"nothing was made under
     * that name"</b>, which says what to do next, rather than <i>"that is not an id"</i>, which
     * does not.
     */
    @Test
    @DisplayName("anything without a namespace is read as naming something we made — typos included")
    void aBareStringIsAlwaysAnAttemptToNameOurOwn() {
        for (String bare : new String[]{"hot_coffee_plain", "Hot Coffee", "two words",
                                        "zonkbolt MEGA"}) {
            ItemRef r = ItemRef.parse(bare);
            assertEquals(ItemRef.Kind.WISHED, r.kind(), bare);
            assertNull(r.refusal("x"), "the SHAPE is fine; whether it names anything is the "
                    + "store's question, and asking it here would need a populated store: " + bare);
        }
    }

    @Test
    @DisplayName("a typo in a real namespace is SHAPE-VALID — only the registry can catch it")
    void aTypoIsNotThisClassesJob() {
        ItemRef r = ItemRef.parse("minecraft:cobblestne");
        assertEquals(ItemRef.Kind.REGISTERED, r.kind());
        assertNull(r.refusal("x"),
                "parsing is pure; existence needs the live registry and belongs at Gate B — "
                        + "claiming it here would make Gate A look like it covers something it cannot");
    }

    @Test
    @DisplayName("case and surrounding space never change the answer")
    void theAgentsWhitespaceIsNotTheChildsProblem() {
        assertEquals(ItemRef.Kind.REGISTERED, ItemRef.parse("  MINECRAFT:Cocoa_Beans ").kind());
        assertEquals("minecraft:cocoa_beans", ItemRef.parse("  MINECRAFT:Cocoa_Beans ").text());
        assertEquals(ItemRef.Kind.BLANK, ItemRef.parse("  MODDINGFROMAMOD:WISH_ITEM ").kind());
    }
}
