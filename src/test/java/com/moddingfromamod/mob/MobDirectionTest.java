package com.moddingfromamod.mob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.mob.MobDirection.Way;
import org.junit.jupiter.api.Test;

/**
 * Gate A 's actual defect: a wish answered <b>backwards</b>.
 *
 * <p>{@code modify_mob} takes absolute numbers, so "make him faster" means inventing one — and the
 * issue's sentence is the whole point of this file: <i>"if it guesses low the mob gets SLOWER while
 * the wish said faster. The wish is not merely unanswered; it is answered backwards, and nothing in
 * the world or the log says so."</i>
 */
class MobDirectionTest {

    @Test
    void heAskedForMore() {
        for (String said : new String[] {
                "make him bigger", "make him faster", "make it huge", "make him stronger",
                "give him more hearts", "make him hit harder", "make that cow enormous",
                "make him super scary", "level up my dragon"}) {
            assertEquals(Way.MORE, MobDirection.of(said), said);
        }
    }

    @Test
    void heAskedForLess() {
        for (String said : new String[] {
                "make him smaller", "make it tiny", "make him slower", "make him weaker",
                "shrink my dragon", "make him back to normal"}) {
            assertEquals(Way.LESS, MobDirection.of(said), said);
        }
    }

    @Test
    void bothOrNeitherIsNoOpinion() {
        // "Bigger but slower" is a real wish, and enforcing either half of it would refuse the
        // other. A sentence with no direction in it has nothing to enforce.
        assertEquals(Way.NONE, MobDirection.of("make him bigger but slower"));
        assertEquals(Way.NONE, MobDirection.of("make him glow pink"));
        assertEquals(Way.NONE, MobDirection.of("call him Spikor"));
        assertEquals(Way.NONE, MobDirection.of(""));
        assertEquals(Way.NONE, MobDirection.of(null));
    }

    @Test
    void theDefectItself_askedFasterAndGivenSlower() {
        // 0.25 -> 0.2 on "make him faster". This is the exact case describes.
        assertTrue(MobDirection.backwards(Way.MORE, 0.25, 0.2));
        assertTrue(MobDirection.backwards(Way.MORE, 4.0, 2.0));
        // …and the mirror, which is just as wrong and much less discussed.
        assertTrue(MobDirection.backwards(Way.LESS, 1.0, 4.0));
    }

    @Test
    void goingTheRightWayIsNeverRefused() {
        assertFalse(MobDirection.backwards(Way.MORE, 0.25, 0.4));
        assertFalse(MobDirection.backwards(Way.LESS, 4.0, 1.0));
        assertFalse(MobDirection.backwards(Way.NONE, 4.0, 1.0), "no opinion, no refusal");
    }

    @Test
    void equalIsNotBackwards() {
        // A wish that did nothing is a different complaint, and not one worth refusing over —
        // several fields legitimately clamp to a ceiling the mob is already sitting at.
        assertFalse(MobDirection.backwards(Way.MORE, 8.0, 8.0));
        assertFalse(MobDirection.backwards(Way.LESS, 0.2, 0.2));
    }

    @Test
    void aFieldNobodyMentionedIsNotJudged() {
        // null current = the mob has no such attribute (a cow has no attack damage);
        // null wanted = the tool was not asked to change it. Neither can be backwards.
        assertFalse(MobDirection.backwards(Way.MORE, null, 2.0));
        assertFalse(MobDirection.backwards(Way.MORE, 2.0, null));
        assertFalse(MobDirection.backwards(Way.MORE, null, null));
    }
}
