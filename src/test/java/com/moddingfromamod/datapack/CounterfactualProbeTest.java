package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * the probe is calibrated on the REAL leaking script, not on a fixture written to pass.
 *
 * <p>The two lines in {@link #theRealLeakingScriptIsTheCase()} are quoted from the issue: the pack
 * that rained cod at night as asked and again at midday, while the source read correctly, the model
 * grader said yes and the cell scored 1.000.
 */
class CounterfactualProbeTest {

    /** The gate, verbatim from the script that leaked. */
    private static final String LEAKED =
            "clock tick\n"
            + "    /execute store result score sky codsky run time query daytime\n"
            + "    /execute if score sky codsky matches 13000..22999 run function "
            + "cod_rain:downpour\n"
            + "end\n";

    private static String template(String id) throws Exception {
        try (InputStream in = CounterfactualProbeTest.class
                .getResourceAsStream("/effects/" + id + ".cbs")) {
            assertNotNull(in, id + ".cbs is not on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("the real leaking script asks for the noon check")
    void theRealLeakingScriptIsTheCase() {
        CounterfactualProbe.Probe p = CounterfactualProbe.of(LEAKED);
        assertNotNull(p, "the script that actually leaked is not recognised as a time gate, so the "
                + "one case this exists for gets no probe");
        assertEquals("only at NIGHT", p.gate());
        assertEquals("/time set noon", p.setup(),
                "the counterfactual for a night gate has to be DAY — setting it to night again "
                + "would confirm the thing that already works");
        assertEquals("/time set midnight", p.restore(),
                "the child's world must be put back the way he asked for it");

        String w = CounterfactualProbe.warning(LEAKED);
        assertTrue(w.contains("/time set noon") && w.contains("/time set midnight"),
                "the warning must carry the actual commands — 'go and check' with no command is "
                + "the guidance that already failed: " + w);
        assertTrue(w.contains("NOTHING"),
                "it has to say what the agent should SEE, or there is no yes/no answer: " + w);
    }

    @Test
    @DisplayName("a day gate is probed at midnight, and the open-ended forms both work")
    void bothDirectionsAndBothOpenBounds() {
        assertEquals("/time set midnight", CounterfactualProbe.of(
                "clock tick\n    /execute store result score s d run time query daytime\n"
                + "    /execute if score s d matches ..12999 run function x:go\nend\n").setup());
        assertEquals("only during the DAY", CounterfactualProbe.of(
                "clock tick\n    /execute store result score s d run time query daytime\n"
                + "    /execute if score s d matches 0..11000 run function x:go\nend\n").gate());
        // `13000..` with the upper bound left implicit is a night gate too.
        assertEquals("only at NIGHT", CounterfactualProbe.of(
                "clock tick\n    /execute store result score s d run time query daytime\n"
                + "    /execute if score s d matches 13000.. run function x:go\nend\n").gate());
    }

    @Test
    @DisplayName("a tick counter is not a time gate — the periodic template must stay quiet")
    void itDoesNotCryWolfOverEveryNumericRange() throws Exception {
        // P6 in the linter doctrine: a warning that fires on everything is switched off, and then
        // it is not there for the one pack that needed it. `periodic.cbs` compares a counter
        // against a range on every single tick and has nothing to do with the clock.
        assertNull(CounterfactualProbe.of(template("periodic")),
                "the periodic TEMPLATE trips the time probe");
        assertEquals("", CounterfactualProbe.warning(
                "reset\n    /scoreboard objectives add c dummy\nend\n"
                + "clock tick\n    /scoreboard players add #tick c 1\n"
                + "    /execute if score #tick c matches 300.. run say ping\n"
                + "    /execute if score #tick c matches 300.. run scoreboard players set "
                + "#tick c 0\nend\n"),
                "a RENDERED periodic pack (a real number, not ${PERIOD_TICKS}) trips it");
        // Every other shipped trigger, for the same reason.
        for (String id : java.util.List.of("on_hit", "on_death", "on_low_health", "proximity")) {
            assertEquals("", CounterfactualProbe.warning(template(id)),
                    id + ".cbs was told to go and check the clock, and it does not read the clock");
        }
    }

    @Test
    @DisplayName("reading the time is not gating on it")
    void aReadoutIsNotAGate() {
        // Both halves are required. A pack may show the time without acting on it, and a numeric
        // range on its own is any counter at all.
        assertNull(CounterfactualProbe.of(
                "clock tick\n    /execute store result score s d run time query daytime\n"
                + "    /title @a actionbar [{\"score\":{\"name\":\"s\",\"objective\":\"d\"}}]\nend\n"),
                "showing the time was taken as gating on it");
        assertNull(CounterfactualProbe.of(
                "clock tick\n    /execute if score s c matches 13000..22999 run say hi\nend\n"),
                "a range with no daytime query is a counter, not a clock");
        assertNull(CounterfactualProbe.of(null));
        assertNull(CounterfactualProbe.of(""));
    }

    @Test
    @DisplayName("a time gate is found even when a tick counter sits in front of it")
    void aCounterInTheSameFileDoesNotHideTheGate() {
        // The realistic leaking shape, and the reason the search does not stop at the first range
        // it sees: the phase counter blames for the leak is exactly such a range, and it comes
        // FIRST. Stopping there would report no gate on the very pack that has one.
        String both = "clock tick\n"
                + "    /scoreboard players add #phase codsky 1\n"
                + "    /execute if score #phase codsky matches 40.. run summon minecraft:cod\n"
                + "    /execute store result score sky codsky run time query daytime\n"
                + "    /execute if score sky codsky matches 13000..22999 run function c:downpour\n"
                + "end\n";
        assertEquals("only at NIGHT", CounterfactualProbe.of(both).gate(),
                "the counter shadowed the gate, so the pack most likely to leak gets no probe");
    }

    @Test
    @DisplayName("a comment saying 'only at night' is not a gate")
    void itReadsTheCodeNotTheProse() {
        // Same reason TriggerShape strips comments: every generated pack carries a header. Sending
        // the agent to verify a condition the program never implemented wastes a turn and, worse,
        // ends with it confirming that nothing happens at noon — which is true and means nothing.
        assertNull(CounterfactualProbe.of(
                "# only at night: gates on daytime matches 13000..22999\n"
                + "clock tick\n    /summon minecraft:cod ~ ~40 ~\nend\n"),
                "the gate was found in the comment, so the pack that summons unconditionally is "
                + "reported as gated — the exact inversion of the defect");
    }
}
