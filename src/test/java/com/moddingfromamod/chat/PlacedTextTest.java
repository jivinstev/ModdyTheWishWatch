package com.moddingfromamod.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.safety.SafetyLexicon;
import org.junit.jupiter.api.Test;

/** step 4: what a sign, a book or an anvil name may say on a world that sweeps placed text. */
class PlacedTextTest {

    @Test
    void waysToFindSomeoneElsewhereAreContact() {
        for (String s : new String[]{"add me on discord", "my snap is cool_kid", "https://example.com",
                "go to www.thing.net", "join funserver dot com", "@sparkyfan", "steve#1234",
                "call 555 123 4567", "text me later", "insta: bobby", "TikTok me", "whatsapp"}) {
            assertTrue(PlacedText.contact(s), s);
        }
    }

    @Test
    void ordinarySignsAreNot() {
        for (String s : new String[]{"Bob's farm", "Do not dig here!", "Cobble gen ->", "Welcome home",
                "Level 3 mine", "1 2 3 4", "I love my island", "redstone lab", "snapdragon flowers"}) {
            assertFalse(PlacedText.contact(s), s);
        }
    }

    @Test
    void checkKeepsAnOrdinarySignAndRefusesContactSayingWhy() {
        assertEquals(PlacedText.Act.KEEP, PlacedText.check("Welcome to my farm", SafetyLexicon.shared()).act());
        PlacedText.Verdict v = PlacedText.check("add me on discord", SafetyLexicon.shared());
        assertEquals(PlacedText.Act.REFUSE_CONTACT, v.act());
        assertTrue(PlacedText.say(v.act(), "sign").contains("links, usernames or numbers"));
        assertFalse(PlacedText.say(v.act(), "sign").contains("discord"), "never repeats the words");
    }

    @Test
    void blankIsKept() {
        assertFalse(PlacedText.check("   ", SafetyLexicon.shared()).refused());
        assertFalse(PlacedText.check(null, SafetyLexicon.shared()).refused());
    }
}
