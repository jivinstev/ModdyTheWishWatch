package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** every clickable link leaves a datapack's source, its words stay, and the JSON stays well-formed. */
class ChatLinkStripTest {

    /** The primer's old greeting, exactly as the agent copied it. */
    private static final String PRIMER_GREETING =
            "    /tellraw @s [\"\",{\"text\":\"Fireball pack loaded! \",\"color\":\"gold\"},{\"text\":\"[Click for Fireballs]\","
            + "\"color\":\"red\",\"underlined\":true,\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/function fireball:give\"}}]";

    @Test
    void thePrimersOldGreetingLosesItsClickAndKeepsItsWords() {
        var r = ChatLinkStrip.strip(PRIMER_GREETING);
        assertEquals(1, r.removed());
        assertFalse(r.source().contains("clickEvent"), r.source());
        assertTrue(r.source().contains("\"text\":\"Fireballs\""), "the link-only label becomes its words: " + r.source());
        assertTrue(r.source().contains("Fireball pack loaded!"), r.source());
        JsonParser.parseString(r.source().strip().substring("/tellraw @s ".length()));   // still valid JSON
    }

    @Test
    void aSummonWithNbtBracesInsideTheLinkIsSkippedWhole() {
        // plumo_face's links ran `/summon minecraft:marker … {Tags:[…]}`: braces inside the value's string.
        String line = "/tellraw @a {\"text\":\"[Click to spawn Plumo]\",\"clickEvent\":{\"action\":\"run_command\","
                + "\"value\":\"/summon minecraft:marker ~ ~ ~ {Tags:[\\\"plumo\\\"]}\"},\"color\":\"red\"}";
        var r = ChatLinkStrip.strip(line);
        assertEquals(1, r.removed());
        assertEquals("/tellraw @a {\"text\":\"Spawn Plumo\",\"color\":\"red\"}", r.source());
        JsonParser.parseString(r.source().substring("/tellraw @a ".length()));
    }

    @Test
    void theSnbtSpellingAndAClickFirstInItsObjectAreStrippedToo() {
        String line = "/tellraw @s {click_event:{action:\"run_command\",command:\"/function egg:give\"},text:\"[Click here for eggs]\"}";
        var r = ChatLinkStrip.strip(line);
        assertEquals(1, r.removed());
        assertEquals("/tellraw @s {text:\"Eggs\"}", r.source());
    }

    @Test
    void aSourceWithNoLinkIsReturnedUntouchedAndLabelsElsewhereAreLeftAlone() {
        String src = "function greet()\n    /tellraw @s {\"text\":\"Click for nothing, this is plain words\"}\nend\n";
        var r = ChatLinkStrip.strip(src);
        assertEquals(0, r.removed());
        assertEquals(src, r.source(), "no link on the line: its words are not rewritten, newlines kept exact");
    }

    @Test
    void severalLinksOnSeveralLinesAreAllTakenOut() {
        String src = PRIMER_GREETING + "\n" + PRIMER_GREETING.replace("Fireballs", "Snowballs") + "\n";
        var r = ChatLinkStrip.strip(src);
        assertEquals(2, r.removed());
        assertFalse(r.source().contains("clickEvent"));
        assertTrue(r.source().endsWith("\n"));
    }

    @Test
    void anUnbalancedLinkIsLeftAloneRatherThanGuessed() {
        String line = "/tellraw @s {\"text\":\"x\",\"clickEvent\":{\"action\":\"run_command\"";
        assertEquals(line, ChatLinkStrip.strip(line).source());
    }

    @Test
    void thePrimerNoLongerTeachesALink() {
        String primer = CbScriptPrimer.FULL;
        assertFalse(primer.contains("clickEvent") || primer.contains("click_event") || primer.contains("[Click"),
                "CbScriptPrimer's worked example teaches a chat link again (#1950)");
    }
}
