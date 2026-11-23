package com.moddingfromamod.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.moddingfromamod.wish.CommandGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>Gate A — pins the ACTION-vs-RULE reuse decision as concrete outcomes.</b>
 *
 * <p>The agent's <i>judgment</i> (is this a rule or an action?) is guided by the prompt + docs; this
 * pins that once it has decided, the DECLARATION maps to the right template — deterministically, with
 * no game. The two crux rows from docs/DATAPACKS.md are the fixtures: "give me fireballs" (row 2)
 * declares an action and becomes a button; "the smash also drops a shield" (row 14) declares nothing
 * and gets no button, even though it involves an item.
 */
class AuthorDatapackReuseTest {

    /** Row 2: an ACTION datapack becomes a servable, allowlisted button. */
    @Test
    void actionDatapackBecomesAServableAllowlistedButton() {
        JsonObject args = new JsonObject();
        args.addProperty("reuse_action", "give");
        JsonArray words = new JsonArray();
        words.add("fireball");
        args.add("reuse_words", words);

        JsonObject t = AuthorDatapackTool.reuseTemplate("fireball", args);
        assertNotNull(t, "an ACTION datapack must be taught a button");

        String cmd = t.getAsJsonArray("commands").get(0).getAsString();
        assertEquals("wishdatapack fireball give", cmd);
        // Obligation 3: the replay command must be allowlisted, or add_template refuses it at teach
        // time and the design stays saved-but-unreachable.
        assertTrue(CommandGuard.isAllowed(cmd),
                "the replay command must be on CommandGuard's allowlist");
        // A plain noun, not a back-reference — so the button is findable and servable.
        assertEquals("fireball", t.getAsJsonArray("all").get(0).getAsString());
    }

    /** Row 14 + grumbnar_smash: a RULE datapack gets NO button — auto-load is its reuse. */
    @Test
    void ruleDatapackGetsNoButton() {
        assertNull(AuthorDatapackTool.reuseTemplate("grumbnar_smash", new JsonObject()),
                "a RULE datapack (no reuse_action) must NOT be taught a button");
        // Row 14: the agent correctly omits reuse_action because the shield is granted BY the rule,
        // not by the child pressing something — so still no button, even though an item is produced.
        JsonObject shieldRuleUpdate = new JsonObject();
        assertNull(AuthorDatapackTool.reuseTemplate("grumbnar_smash", shieldRuleUpdate));
    }

    /** A malformed action is rejected loudly; missing words fall back to the feature id (robust). */
    @Test
    void malformedActionIsRejectedButMissingWordsFallBackToTheId() {
        JsonObject namespaced = new JsonObject();
        namespaced.addProperty("reuse_action", "fireball:give");   // must be a BARE name
        JsonArray w = new JsonArray();
        w.add("fireball");
        namespaced.add("reuse_words", w);
        assertThrows(IllegalArgumentException.class,
                () -> AuthorDatapackTool.reuseTemplate("fireball", namespaced));

        // No reuse_words: don't fail — derive keywords from the feature id so the button still works.
        JsonObject noWords = new JsonObject();
        noWords.addProperty("reuse_action", "give");
        JsonObject t = AuthorDatapackTool.reuseTemplate("sparkle_bomb", noWords);
        assertNotNull(t);
        assertEquals("[\"sparkle\",\"bomb\"]", t.getAsJsonArray("all").toString());
    }

    /**
     * The Gate D finding: the agent passed phrases as reuse_words ("sparkle bomb", "sparkle bombs",
     * "sparklebomb"), which the whole-word matcher can't match, so typing them escalated instead of
     * serving offline. Normalisation must reduce them to clean single words.
     */
    @Test
    void phraseKeywordsAreNormalisedToSingleWords() {
        var cleaned = AuthorDatapackTool.cleanKeywords(
                java.util.List.of("sparkle bomb", "sparkle bombs", "sparklebomb"), "sparkle_bomb");
        assertEquals(java.util.List.of("sparkle", "bomb"), cleaned,
                "phrases/plurals/concatenations must collapse to [sparkle, bomb]");

        JsonObject args = new JsonObject();
        args.addProperty("reuse_action", "give");
        JsonArray phrases = new JsonArray();
        phrases.add("sparkle bomb");
        phrases.add("sparklebomb");
        args.add("reuse_words", phrases);
        JsonObject t = AuthorDatapackTool.reuseTemplate("sparkle_bomb", args);
        assertEquals("[\"sparkle\",\"bomb\"]", t.getAsJsonArray("all").toString(),
                "the taught button matches when the child types 'sparkle bomb'");
    }

    /**
     * The second Gate D finding: the agent taught the button with a raw {@code function <id>:give},
     * which threw "This function should not run" mid-reload. add_template must rewrite datapack
     * function calls to the guarded {@code wishdatapack} — and only for KNOWN datapacks.
     */
    @Test
    void rawDatapackFunctionCallsAreRewrittenToWishdatapack(@TempDir Path store) throws Exception {
        System.setProperty("moddingfromamod.datapacks.dir", store.toString());
        try {
            Files.createDirectories(store.resolve("icebomb"));
            Files.writeString(store.resolve("icebomb").resolve("icebomb.zip"), "x");

            assertEquals("wishdatapack icebomb give",
                    AddTemplateTool.rewriteDatapackFunction("function icebomb:give"));
            // Unknown namespace (not a saved datapack) — left alone.
            assertEquals("function minecraft:foo",
                    AddTemplateTool.rewriteDatapackFunction("function minecraft:foo"));
            // A real macro call (function … with …) — left alone.
            assertEquals("function icebomb:give with storage x",
                    AddTemplateTool.rewriteDatapackFunction("function icebomb:give with storage x"));
            // Not a function command — untouched.
            assertEquals("give @s minecraft:diamond",
                    AddTemplateTool.rewriteDatapackFunction("give @s minecraft:diamond"));
        } finally {
            System.clearProperty("moddingfromamod.datapacks.dir");
        }
    }
}
