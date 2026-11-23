package com.moddingfromamod.reference;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.moddingfromamod.wish.CreatureCorpus;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Guards on the resolver prompt, which took three attempts to get right.</b>
 *
 * <p>The scoring history, over the whole corpus:
 *
 * <pre>
 *   "is this a REAL character?"                     5/7 named
 *   + "do not judge whether you have heard of it"   6/7 named
 *   decide from the PHRASING, with worked examples  7/7 named
 * </pre>
 *
 * <p>The failure was always the same creature — Tralalelo Tralalala, an obscure meme — and always
 * for the same reason: to a model that knows neither, <b>an unknown real name and an invented name
 * are textually identical</b>. Only the phrasing separates them.
 *
 * <p>The fast tests here assert that the instructions which fixed it are still present, because a
 * well-meaning rewrite would drop them and the failure is silent — the feature simply stops firing
 * for the creatures it exists to serve. The full 7/7 check needs real model calls and is opt-in.
 */
class ResolverPromptTest {

    private static String prompt(String wish) throws Exception {
        Method m = CharacterResolver.class.getDeclaredMethod("prompt", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, wish);
    }

    @Test
    @DisplayName("the prompt asks about PHRASING, never about whether the model knows the name")
    void asksAboutPhrasingNotKnowledge() throws Exception {
        String p = prompt("make Tralalelo Tralalala the shark with the shoes");
        assertTrue(p.contains("HOW THE CHILD REFERS TO IT") || p.contains("how the child refers"),
                "the decision must key on phrasing: " + p);
        assertTrue(p.toLowerCase().contains("never from whether you recognise")
                        || p.toLowerCase().contains("not have heard of"),
                "must explicitly disclaim recognition: " + p);
    }

    @Test
    @DisplayName("the two failures that taught us the rule are still worked examples")
    void carriesTheWorkedExamples() throws Exception {
        String p = prompt("anything");
        // Tralalelo: an unknown REAL name that must resolve.
        assertTrue(p.contains("Tralalelo"),
                "the creature this rule exists for must stay as an example: " + p);
        // Grumbnar: an INVENTED name announced with "called", which must NOT resolve.
        assertTrue(p.contains("Grumbnar") || p.contains("called"),
                "the invented-name case must stay as an example: " + p);
    }

    @Test
    @DisplayName("the prompt does not ask the model to judge realness")
    void noRealnessQuestion() throws Exception {
        String p = prompt("x").toLowerCase();
        assertFalse(p.contains("is this a real character"),
                "this exact phrasing scored 5/7 and failed on every unfamiliar name");
    }

    @Test
    @DisplayName("LIVE: the resolver still names 7 of 7 corpus characters")
    void liveCorpusScore() {
        // Opt-in: 23 model calls. Skips LOUDLY, because a quiet skip here would report green over
        // the single check that gates every downstream measurement.
        Assumptions.assumeTrue("1".equals(System.getenv("MFAM_LIVE_RESOLVER")),
                "SKIPPED — set MFAM_LIVE_RESOLVER=1 to spend ~23 model calls on the real check");

        List<CreatureCorpus.Case> cases = CreatureCorpus.load();
        int named = 0;
        int hit = 0;
        StringBuilder missed = new StringBuilder();
        for (var c : cases) {
            if (!"named".equals(c.tier())) {
                continue;
            }
            named++;
            if (CharacterResolver.resolve(c.prompt()).isPresent()) {
                hit++;
            } else {
                missed.append(' ').append(c.id());
            }
        }
        assertTrue(hit == named,
                "a missed NAMED creature silently disables the feature for it. missed:" + missed);
    }

    @Test
    @DisplayName("the corpus scorer exists, since it is the cheap gate before any run")
    void scorerIsCheckedIn() {
        assertTrue(Files.isRegularFile(Path.of(
                        "src/main/java/com/moddingfromamod/reference/ResolveCorpus.java")),
                "resolveCorpus is the first thing to run before trusting any downstream result");
    }
}
