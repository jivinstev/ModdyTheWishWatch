package com.moddingfromamod.provenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The World/Session trailer on a published wish —. */
class ProvenanceCommitMessageTest {

    @Test
    @DisplayName("the subject still reads as it always did")
    void subjectUnchanged() {
        String m = ProvenanceCommitMessage.of("creature", "spikor", "modwatch-daddus", "s-abc");
        assertEquals("Moddy wish (creature): spikor", m.lines().findFirst().orElseThrow());
    }

    @Test
    @DisplayName("world and sitting ride as git TRAILERS, after a blank line")
    void trailersAreRealTrailers() {
        // A real trailer so `git log --format=%(trailers:key=World)` can read it back without
        // parsing prose. The blank line is what makes it one.
        String m = ProvenanceCommitMessage.of("creature", "spikor", "modwatch-daddus", "s-abc");
        assertTrue(m.contains("\n\nWorld: modwatch-daddus\n"), m);
        assertTrue(m.contains("Session: s-abc"), m);
    }

    @Test
    @DisplayName("no world means no trailer — never the word null")
    void absentIsOmittedNotNull() {
        // At the title screen there is no world. A literal "null" in a trailer is a value every
        // later reader has to learn to distrust; ActionLog already omits rather than writes it.
        for (String[] pair : new String[][]{{null, null}, {"", ""}, {"  ", "  "}}) {
            String m = ProvenanceCommitMessage.of("creature", "spikor", pair[0], pair[1]);
            assertEquals("Moddy wish (creature): spikor", m);
            assertFalse(m.contains("null"), m);
        }
    }

    @Test
    @DisplayName("one of the two present is still worth recording")
    void partialIsKept() {
        String m = ProvenanceCommitMessage.of("build", "castle", "w1", null);
        assertTrue(m.contains("World: w1"), m);
        assertFalse(m.contains("Session:"), m);
    }

    @Test
    @DisplayName("two worlds produce two DIFFERENT commit messages, which is the whole point")
    void twoWorldsAreDistinguishable() {
        // The failure this closes: in a git log with no world, four creations in four worlds are
        // one linear history and read as iteration by construction.
        String a = ProvenanceCommitMessage.of("creature", "spikor", "world-one", "s-1");
        String b = ProvenanceCommitMessage.of("creature", "spikor", "world-two", "s-2");
        assertFalse(a.equals(b), "a reader of the log cannot tell these two creations apart");
    }
}
