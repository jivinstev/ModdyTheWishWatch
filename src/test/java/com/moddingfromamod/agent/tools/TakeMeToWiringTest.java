package com.moddingfromamod.agent.tools;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.WishAgent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Is {@code take_me_to} actually OFFERED to the agent?</b>
 *
 * <p>Separate from {@code ArtifactLocatorTest}, which tests the matching and cannot tell whether
 * the tool exists in the roster at all. A tool class that compiles and is never registered is the
 * same silent no-op as an unwired gate — and routing here is emergent, decided from the system
 * prompt, the wish, and <b>the tool list in the order it is offered</b>, so being in the list is
 * the whole mechanism.
 */
class TakeMeToWiringTest {

    private static List<String> roster() {
        List<String> names = new ArrayList<>();
        for (McpTool t : WishAgent.buildTools().all()) {
            names.add(t.name());
        }
        return names;
    }

    @Test
    @DisplayName("take_me_to is offered to the agent")
    void itIsOffered() {
        assertTrue(roster().contains("take_me_to"),
                "the tool exists but is never registered, so the agent still has only the "
                        + "manifesting verbs it reached for when it built a second McTrain: " + roster());
    }

    @Test
    @DisplayName("it is offered BEFORE the building verbs it is meant to be chosen instead of")
    void itComesBeforeTheBuildVerbs() {
        List<String> names = roster();
        int nav = names.indexOf("take_me_to");
        for (String builder : new String[] {"build_structure", "sculpt_creature", "make_item"}) {
            int b = names.indexOf(builder);
            if (b >= 0) {
                assertTrue(nav < b, "take_me_to must be seen before " + builder
                        + "; routing is emergent from the order the tools are offered in");
            }
        }
    }

    @Test
    @DisplayName("its description tells the agent it BUILDS NOTHING")
    void itSaysItBuildsNothing() {
        String desc = WishAgent.buildTools().all().stream()
                .filter(t -> t.name().equals("take_me_to")).findFirst()
                .orElseThrow().description().toLowerCase();
        assertTrue(desc.contains("builds nothing"),
                "the bug was answering 'take me to it' by building another one — the description "
                        + "has to say that is not the answer");
        assertTrue(desc.contains("already"), desc);
    }
}
