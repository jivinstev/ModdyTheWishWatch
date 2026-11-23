package com.moddingfromamod.agent.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>, pinned.</b>
 *
 * <p>{@code subject} said "the id of a <i>creature</i> to point the camera at", and
 * {@code PhotographTool} has framing paths for buildings, trains, machines and held items that were
 * therefore <b>skipped for years</b> — the agent read the field as creature-only and left it blank,
 * so every other kind of artifact was photographed from wherever the child happened to be standing.
 *
 * <p>A defect with no failing test anywhere, in code that was entirely correct. The only thing wrong
 * was the sentence, which is why the sentence now has a test.
 */
class PhotographSubjectTest {

    private static final PhotographTool TOOL = new PhotographTool();

    private static final List<String> KINDS =
            List.of("creature", "building", "machine", "item");

    @Test
    @DisplayName("subject names every kind it can frame, not just creatures")
    void subjectIsNotCreatureOnly() {
        String field = TOOL.inputSchema().getAsJsonObject("properties")
                .getAsJsonObject("subject").get("description").toString().toLowerCase(Locale.ROOT);
        List<String> missing = KINDS.stream().filter(k -> !field.contains(k)).toList();
        assertTrue(missing.isEmpty(),
                "the subject field never mentions " + missing + ", so the agent will read it as "
                        + "applying only to what it does mention and leave it blank for the rest — "
                        + "which is #219 exactly. Field says: " + field);
    }

    @Test
    @DisplayName("the tool description tells the agent to pass it for anything it just made")
    void theDescriptionSaysItIsNotCreatureOnlyEither() {
        // The schema field is not the only thing the agent reads, and the description is the part
        // it reads FIRST. Fixing one and not the other leaves the same impression.
        String d = TOOL.description().toLowerCase(Locale.ROOT);
        long named = KINDS.stream().filter(d::contains).count();
        assertTrue(named >= 3,
                "the description names only " + named + " kind(s) of artifact, so it still reads as "
                        + "a creature tool: " + TOOL.description());
    }
}
