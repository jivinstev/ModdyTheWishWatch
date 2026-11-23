package com.moddingfromamod.agent.tools;

import java.util.LinkedHashMap;
import java.util.Map;

import com.moddingfromamod.datapack.ItemCatalogue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>What the creatures are LIKE when they appear.</b>
 *
 * <p>Two wishes were declined for as long as this class has existed, and neither needed new
 * capability — vanilla {@code /summon} has taken an NBT tag all along:
 *
 * <ul>
 *   <li><i>"spawn eggs for all my baby mobs"</i> — <i>"No baby parameter exists"</i></li>
 *   <li><i>"eggs that pop out already-electric mobs"</i> — <i>"The 'already-electric' part isn't
 *       expressible; author_datapack"</i></li>
 * </ul>
 *
 * <p>Both quotes are the agent's own, recorded in
 * {@code tools/item-selection-verdicts-heldout-opus-low.json}. What was missing was a parameter.
 */
class SummonAsTest {

    private static ItemCatalogue.Entry summon() {
        return ItemCatalogue.load().all().stream()
                .filter(e -> e.id().equals("summon")).findFirst().orElseThrow();
    }

    private static Map<String, String> args(String as) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("MOB", "minecraft:zombie");
        m.put("COUNT", "2");
        m.put("AS", as);
        return m;
    }

    /** The word a child says, and the NBT it becomes. */
    @Test
    @DisplayName("baby and charged reach the summon command as real NBT")
    void theChildsWordBecomesNbt() {
        Map<String, String> baby = args("baby");
        assertNull(derive(baby), "'baby' should be accepted");
        assertTrue(baby.get("SUMMONS").contains("{IsBaby:1b}"),
                "'baby mobs' has to reach the command, or the egg pops out an adult: " + baby.get("SUMMONS"));

        Map<String, String> charged = args("charged");
        assertNull(derive(charged), "'charged' should be accepted");
        assertTrue(charged.get("SUMMONS").contains("{powered:1b}"),
                "'already-electric' has to reach the command: " + charged.get("SUMMONS"));
    }

    /**
     * …and plain really is plain. A stray tag on an ordinary spawn egg would be a silent behaviour
     * change on the single most common item wish there is.
     */
    @Test
    @DisplayName("plain adds nothing at all")
    void plainIsPlain() {
        Map<String, String> p = args("plain");
        assertNull(derive(p));
        assertFalse(p.get("SUMMONS").contains("{"),
                "a plain spawn egg must summon a plain creature: " + p.get("SUMMONS"));
        assertEquals("    /summon minecraft:zombie ~ ~ ~\n    /summon minecraft:zombie ~ ~ ~",
                p.get("SUMMONS"), "COUNT still governs how many lines there are");
    }

    /**
     * <b>The NBT is a LOOKUP, never interpolation.</b>
     *
     * <p>This is the test that matters. The rendered value is compiled into a datapack and loaded,
     * so if anything the agent writes could reach the command, a mistake upstream becomes arbitrary
     * commands running in a child's world. {@code docs/PATTERNS.md}: <i>"the security boundary is a
     * type system, not a filter."</i> An unrecognised word must be REFUSED, and the refusal must
     * name the values that work — a refusal the agent cannot act on costs a turn and gets retried.
     */
    @Test
    @DisplayName("anything not in the table is refused, and nothing it wrote can reach the command")
    void unknownIsRefusedRatherThanInterpolated() {
        for (String bad : new String[]{
                "", "  ", "BABY!", "electric", "powered", "1b",
                "plain} /kill @a {", "{IsBaby:1b}", "baby;/op @p"}) {
            Map<String, String> m = args(bad);
            String err = derive(m);
            assertTrue(err != null && err.contains("AS must be one of"),
                    "'" + bad + "' should have been refused, got: " + err);
            assertNull(m.get("SUMMONS"),
                    "a refused AS must leave no command behind: " + m.get("SUMMONS"));
            assertTrue(err.contains("plain") && err.contains("baby") && err.contains("charged"),
                    "the refusal has to say what DOES work, or the agent guesses again: " + err);
        }
    }

    /** Case and stray spaces are the child's, not a new value. */
    @Test
    @DisplayName("Baby, BABY and ' baby ' are all baby")
    void theWordIsForgivingAboutShape() {
        for (String v : new String[]{"Baby", "BABY", " baby ", "\tbaby"}) {
            Map<String, String> m = args(v);
            assertNull(derive(m), "'" + v + "' should be accepted");
            assertTrue(m.get("SUMMONS").contains("{IsBaby:1b}"), "'" + v + "' should mean baby");
        }
    }

    /** The catalogue has to OFFER it, or the agent never knows it can be asked for. */
    @Test
    @DisplayName("AS is declared with an ask in the child's words")
    void theSurfaceTeachesIt() {
        ItemCatalogue.Entry e = summon();
        assertTrue(e.params().contains("AS"), "summon must declare AS");
        String ask = e.paramAsks().get("AS");
        assertTrue(ask != null && ask.toLowerCase().contains("baby")
                        && ask.toLowerCase().contains("electric"),
                "the ask must carry the child's own words, per playbook 4f: " + ask);
        String d = new MakeItemTool().description();
        assertTrue(d.contains("AS"), "the agent is never told AS exists");
        for (String v : new String[]{"plain", "baby", "charged"}) {
            assertTrue(d.contains(v), "the surface hides the '" + v + "' option");
        }
    }

    private static String derive(Map<String, String> m) {
        return MakeItemTool.derive(summon(), "thrown", m);
    }
}
