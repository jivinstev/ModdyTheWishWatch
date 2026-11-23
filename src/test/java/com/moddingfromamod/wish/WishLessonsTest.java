package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The lessons mechanism: bundled rules load, and the rules that cost us wishes are in them. */
class WishLessonsTest {

    @Test
    @DisplayName("the bundled building lessons load")
    void buildingLessonsLoad() {
        // Buildings had NO lesson file at all, despite being the largest wish class in
        // the corpus. A lesson mechanism with a gap in it looks exactly like one without.
        assertFalse(WishLessons.text("building").isBlank(),
                "no bundled lessons for 'building' — the largest wish class is getting no learned "
                        + "guidance at all");
    }

    @Test
    @DisplayName("the building lessons still carry the corner rule that broke real builds")
    void buildingLessonsCarryTheAnchorRule() {
        String text = WishLessons.text("building").toLowerCase(java.util.Locale.ROOT);
        assertTrue(text.contains("corner"),
                "the corner rule is gone — two real builds lost two walls each because dx/dz were "
                        + "read as a middle (#52)");
        assertTrue(text.contains("anchor") && text.contains("centre"),
                "the way to say 'centre' is gone, so the rule states a trap with no way out of it");
        assertTrue(text.contains("overwrite"),
                "the later-shapes-overwrite rule is gone, and it is the mechanism by which a wall "
                        + "goes missing in the first place");
    }

    @Test
    /**
     *. 13 of the 19 buildings really built for a child enclose a room with no way in, and one
     * of them sealed 28,228 blocks of air. The tool reports it after the fact; this is the
     * level-1 half — an INPUT to generation, which costs no extra turn.
     *
     * <p>Pinned because a sealed building is the one defect where every other signal reads
     * healthier: four walls, a solid lid, and the lighting check fires precisely BECAUSE it
     * encloses a space, so it gets lit and reported as improved. A trim that dropped this
     * paragraph would leave every other test in this file green.
     */
    void buildingLessonsSayToLeaveAWayIn() {
        String text = WishLessons.text("building");
        assertTrue(text.contains("way IN") || text.contains("way in"),
                "the leave-a-way-in rule is gone — 13 of 19 real buildings sealed their own "
                + "interior, so this is the common case rather than a rare slip");
        assertTrue(text.contains("AFTER the wall"),
                "the ORDER is the whole technique and it is the opposite of the intuitive one: "
                + "later shapes overwrite earlier ones, so a doorway sent BEFORE its wall is "
                + "simply built over and the building is sealed again");
        // The commonest shape in the whole archive, and the one a doorway does NOT fix.
        assertTrue(text.contains("FLOOR"),
                "the band-across-a-tower rule is gone. Nine of the fourteen sealed real buildings "
                + "are towers whose trapped rooms are the gaps between decorative bands — a "
                + "doorway in the outside wall of storey three leaves the other seven sealed");
        assertTrue(text.contains("ladder"),
                "the rule must name the REMEDY (P2). 'Do not seal your tower' is a complaint; "
                + "'cut a hole through each floor and put a ladder in it' is an instruction");
    }

    @Test
    @DisplayName("a kind with no lessons is silent, not an error")
    void unknownKindsAreSilent() {
        assertEquals("", WishLessons.text("no_such_kind"));
        assertEquals("", WishLessons.appendTo("no_such_kind"));
        assertEquals("", WishLessons.text(null));
        assertEquals("", WishLessons.text("  "));
    }

    @Test
    @DisplayName("a kind name cannot escape the lessons directory")
    void kindNamesAreSanitised() {
        // The kind reaches a file path, so a traversal attempt must resolve to nothing rather
        // than to some other file on disk.
        assertEquals("", WishLessons.text("../../secrets"));
        assertEquals("", WishLessons.text("/etc/passwd"));
    }

    @Test
    @DisplayName("appendTo frames the lessons so they read as rules, not as more listing")
    void appendToIsFramed() {
        String appended = WishLessons.appendTo("building");
        assertTrue(appended.contains("LESSONS FROM REAL FAILURES"),
                "the lessons must be visibly separated from the tool's own output, or they read "
                        + "as more of the list and get skimmed");
        String lower = appended.toLowerCase(java.util.Locale.ROOT);
        assertTrue(lower.indexOf("lessons from real failures") < lower.indexOf("corner"),
                "the header must come before the rules");
    }
}
