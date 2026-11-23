package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.moddingfromamod.agent.tools.ApplyEffectTool;
import org.junit.jupiter.api.Test;

/**
 * <b>The agent's selection surface: asserted, and written down so it can be tested offline.</b>
 *
 * <p>Every defect in trigger selection so far has been a defect in this ONE string. It was built
 * from id/summary/params/wishes and silently dropped {@code when} (so the agent chose a trigger
 * without ever reading what it means) and every parameter's {@code doc} (so the agent probed the
 * tool with {@code FEEL="?"} to recover documentation that was already written, and a failed probe
 * pushed it onto the wrong trigger).
 *
 * <p>So this test does two jobs. It asserts the surface carries what it must, and it DUMPS the real
 * text to {@code build/effect-tool-description.txt} for {@code tools/selection-check.py} — tier 3.5,
 * which asks a model to pick an ability from this exact text. Dumping rather than reconstructing is
 * the point: a Python copy of the format would drift, and the drift would be invisible precisely
 * when it mattered.
 */
class SelectionSurfaceTest {

    static final Path DUMP = Path.of("build", "effect-tool-description.txt");
    static final Path ITEM_DUMP = Path.of("build", "item-tool-description.txt");

    @Test
    void theSurfaceCarriesEverythingNeededToChooseWell() throws Exception {
        String d = new ApplyEffectTool().description();
        for (EffectCatalogue.Effect e : EffectCatalogue.load().all()) {
            assertTrue(d.contains(e.id()), "no mention of '" + e.id() + "'");
            assertTrue(d.contains(e.when()),
                    "the agent is never told WHEN '" + e.id() + "' fires, so it picks a trigger "
                            + "from the effect name alone");
            for (String p : e.params()) {
                assertTrue(d.contains(p), e.id() + " hides its parameter '" + p + "'");
                String doc = e.paramDocs().get(p);
                if (doc != null) {
                    assertTrue(d.contains(doc),
                            e.id() + "." + p + " has documented values that are not shown, which "
                                    + "is what sent the agent probing with '?' values: " + doc);
                }
            }
        }
        Files.createDirectories(DUMP.getParent());
        Files.writeString(DUMP, d);
    }

    /**
     * The same job for {@code make_item}: assert the surface, then dump it for tier 3.5.
     *
     * <p>Every parameter of every entry must be visible <b>with its `ask`</b> — the child's-words
     * prompt. That is not decoration: the ability catalogue's TRIGGER documented its categories
     * abstractly, the agent omitted the parameter anyway, and adding the child's phrasings is what
     * moved the same wish set 4/8 to 6/8. A surface that hides the asks is a surface the agent
     * cannot choose from, and it would show up as the model's mistake rather than ours.
     */
    @Test
    void theItemSurfaceCarriesEverythingNeededToChooseWell() throws Exception {
        String d = new com.moddingfromamod.agent.tools.MakeItemTool().description();
        for (ItemCatalogue.Entry e : ItemCatalogue.load().all()) {
            assertTrue(d.contains(e.id()), "no mention of the '" + e.id() + "' effect");
            assertTrue(d.contains(e.who()),
                    "the agent is never told what '" + e.id() + "' LANDS ON, so it cannot tell a "
                            + "wish about the child from a wish about what he points at");
            for (String p : e.params()) {
                assertTrue(d.contains(p), e.id() + " hides its parameter '" + p + "'");
                assertTrue(d.contains(e.paramAsks().get(p)),
                        e.id() + "." + p + " hides its 'ask' — the agent is offered a choice with "
                                + "no way to make it from the wish text");
            }
        }
        for (String shape : ItemCatalogue.EFFECT_SHAPES) {
            assertTrue(d.contains(shape), "the surface never mentions the '" + shape + "' shape");
        }
        for (String icon : ItemCatalogue.ICONS.keySet()) {
            assertTrue(d.contains(icon), "icon '" + icon + "' is not offered by name");
        }
        Files.createDirectories(ITEM_DUMP.getParent());
        Files.writeString(ITEM_DUMP, d);
    }
}
