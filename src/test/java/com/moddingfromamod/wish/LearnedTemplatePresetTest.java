package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * <b>Every learned recipe gets a button, whichever shape the agent wrote it in.</b>
 *
 * <p>🔴 The regression this pins is the one Gate D found on the shipping path: a template written
 * with {@code any} was silently given no button at all, while {@code Moddy} told the child
 * <i>"next time just tap its button and it appears in a flash!"</i>. Both shapes are offered by
 * {@code AddTemplateTool}'s own schema — <i>"[all|any REQUIRED — give at least one]"</i> — so which
 * one the agent happened to pick decided whether the child could ever get back to his own wish.
 * Measured over six consecutive Gate D runs on the same wish: three {@code all}, three {@code any}.
 *
 * <p>Drives {@link LearnedTemplates#asPresets()} rather than the pure helper, because the pure
 * helper is new and the old rule lived here: this is the test that goes red on the pre-fix code.
 */
class LearnedTemplatePresetTest {

    private static String textFor(String templateJson, String id) {
        List<String> before = LearnedTemplates.savedJsonForTest();
        try {
            LearnedTemplates.restoreForTest(List.of(templateJson), null);
            for (WishCatalog.Preset p : LearnedTemplates.asPresets()) {
                if (id.equals(p.templateId())) {
                    return p.wishText();
                }
            }
            return null;
        } finally {
            LearnedTemplates.restoreForTest(before, null);
        }
    }

    @Test
    void aRecipeTaughtWithANYIsStillOfferedAsAButton() {
        String text = textFor("{\"id\":\"purple_shining_lighthouse\","
                + "\"any\":[\"lighthouse\",\"light house\"],"
                + "\"commands\":[\"wishbuild purple_shining_lighthouse\"],"
                + "\"say\":\"up it goes\"}", "purple_shining_lighthouse");
        assertEquals("lighthouse", text,
                "an `any` recipe must get a button — this is the real template from the Gate D run");
    }

    @Test
    void aRecipeTaughtWithALLIsUnchanged() {
        String text = textFor("{\"id\":\"purple_lighthouse\",\"all\":[\"lighthouse\"],"
                + "\"commands\":[\"wishbuild purple_lighthouse\"],"
                + "\"say\":\"up it goes\"}", "purple_lighthouse");
        assertEquals("lighthouse", text, "the shape that always worked still works");
    }

    /**
     * The button's own text has to REACH its recipe. It is not what the press sends — that carries
     * the recipe id — but it is the seed a child sees, edits and re-wishes from, so a text the
     * matcher would refuse is a button that lies about what it is.
     */
    @Test
    void theTextOfferedActuallyMatchesTheRecipeItNames() {
        String json = "{\"id\":\"red_dragon\",\"all\":[\"dragon\"],"
                + "\"any\":[\"red\",\"crimson\"],\"none\":[\"blue\"],"
                + "\"commands\":[\"wishbuild red_dragon\"],\"say\":\"roar\"}";
        String text = textFor(json, "red_dragon");
        assertTrue(text != null && text.contains("dragon") && text.contains("red"),
                "every `all` term and one `any` term: " + text);

        List<String> before = LearnedTemplates.savedJsonForTest();
        try {
            LearnedTemplates.restoreForTest(List.of(json), null);
            assertTrue(WishCatalog.templates().match(WishText.sanitize(text)).isPresent(),
                    "the real matcher must accept the text the button offers: " + text);
        } finally {
            LearnedTemplates.restoreForTest(before, null);
        }
    }
}
