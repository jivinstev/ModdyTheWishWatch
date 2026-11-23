package com.moddingfromamod.vanilla;

import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>A rig's body turns at a creature's pace, and not for every twitch</b>.
 *
 * <p>Two phone films: a hovering dragon whose body pointed a new way several times a
 * second, and a small one spinning under him. See {@link RigTurn}.
 */
class RigTurnTest {

    private static float apart(float a, float b) {
        return Math.abs(RigHeadYaw.wrap(a - b));
    }

    @Test
    @DisplayName("a half turn asked in one tick is drawn over many, never more than MAX a tick, and arrives")
    void aHalfTurnIsSpread() {
        RigTurn t = new RigTurn();
        t.snap(0f);
        float last = 0f;
        int ticks = 0;
        while (apart(last, 170f) > 0.01f && ticks < 200) {
            float now = t.step(170f, false);
            assertTrue(apart(now, last) <= RigTurn.MAX + 1e-3f, "turned " + apart(now, last) + " in a tick");
            last = now;
            ticks++;
        }
        assertEquals(170f, last, 0.01f, "it must arrive");
        assertTrue(ticks >= 17 && ticks <= 40, "a half turn took " + ticks + " ticks");
    }

    @Test
    @DisplayName("it slows as it arrives: the last steps are smaller than the first")
    void itEasesIn() {
        RigTurn t = new RigTurn();
        t.snap(0f);
        float first = t.step(90f, false);
        float prev = first;
        float lastStep = first;
        for (int i = 0; i < 60 && apart(prev, 90f) > 0.01f; i++) {
            float now = t.step(90f, false);
            lastStep = apart(now, prev);
            prev = now;
        }
        assertTrue(lastStep < first / 2, "first step " + first + ", last " + lastStep);
    }

    @Test
    @DisplayName("standing, a wanted yaw under the threshold does not turn it at all")
    void aTwitchIsIgnored() {
        RigTurn t = new RigTurn();
        t.snap(40f);
        Random r = new Random(7);
        for (int i = 0; i < 400; i++) {
            float want = 40f + (r.nextFloat() * 2 - 1) * (RigTurn.START - 0.5f);
            assertEquals(40f, t.step(want, false), 0f, "it turned for a twitch");
        }
    }

    @Test
    @DisplayName("once past the threshold it goes all the way, not to a threshold short")
    void aTurnFinishes() {
        RigTurn t = new RigTurn();
        t.snap(0f);
        float y = 0f;
        for (int i = 0; i < 100; i++) {
            y = t.step(RigTurn.START + 5f, false);
        }
        assertEquals(RigTurn.START + 5f, y, 0.01f);
    }

    @Test
    @DisplayName("travelling, it faces its way within the small threshold")
    void movingFollowsClosely() {
        RigTurn t = new RigTurn();
        t.snap(0f);
        float y = 0f;
        for (int i = 0; i < 100; i++) {
            y = t.step(8f, true);
        }
        assertEquals(8f, y, 0.01f);
    }

    @Test
    @DisplayName("travelling, a turn back along its path is done in about half a second, never over MAX_MOVING a tick")
    void aWalkerTurnsBackQuickly() {
        RigTurn t = new RigTurn();
        t.snap(-90f);
        float last = -90f;
        int ticks = 0;
        while (apart(last, 90f) > 0.01f && ticks < 100) {
            float now = t.step(90f, true);
            assertTrue(apart(now, last) <= RigTurn.MAX_MOVING + 1e-3f);
            last = now;
            ticks++;
        }
        assertTrue(ticks >= 8 && ticks <= 14, "a half turn on the move took " + ticks + " ticks");
    }

    @Test
    @DisplayName("a heading that is noise (a new random way every tick) barely moves the body")
    void noiseIsNotFollowed() {
        RigTurn raw = new RigTurn();
        raw.snap(0f);
        Random r = new Random(3);
        float turned = 0f;
        float rawTurned = 0f;
        float last = 0f;
        float lastWant = 0f;
        for (int i = 0; i < 200; i++) {
            float want = r.nextFloat() * 360f - 180f;
            float now = raw.step(want, false);
            turned += apart(now, last);
            rawTurned += apart(want, lastWant);
            last = now;
            lastWant = want;
        }
        assertTrue(turned < rawTurned / 8, "drawn " + turned + " degrees against " + rawTurned + " asked");
    }

    @Test
    @DisplayName("it turns the short way across the wrap")
    void theShortWayRound() {
        RigTurn t = new RigTurn();
        t.snap(170f);
        float now = t.step(-170f, false);
        assertTrue(now > 170f || now < -170f, "went the long way: " + now);
    }

    @Test
    @DisplayName("held, it stays; snapped, it is there at once; the first step is a snap")
    void holdAndSnap() {
        RigTurn t = new RigTurn();
        assertEquals(33f, t.step(33f, false), 0f, "the first frame is not a turn");
        assertEquals(33f, t.hold(120f), 0f);
        assertEquals(-90f, t.snap(270f), 0f);
    }

    @Test
    @DisplayName("#2120: a look within 25 degrees of straight up or down has no direction; a level one has")
    void steepLooks() {
        assertTrue(RigTurn.steep(0.3, 6.0, -0.2));
        assertTrue(RigTurn.steep(0.3, -6.0, -0.2));
        assertFalse(RigTurn.steep(6.0, 1.0, 0.0));
        assertFalse(RigTurn.steep(3.0, 3.0, 0.0), "45 degrees up is still a direction");
        assertFalse(RigTurn.steep(0.0, 0.0, 0.0));
    }
}
