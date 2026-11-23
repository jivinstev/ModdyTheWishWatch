package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import com.moddingfromamod.wish.Wish;
import com.moddingfromamod.wish.WishSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * step 4: every wish handed to the workers wakes a pool stopped for being quiet.
 *
 * <p>The pool may be stopped after twenty quiet minutes while children play. {@code WishService}
 * wakes it for a new wish, but three other paths hand a wish to the workers without passing there
 * — a refinement reopened by {@code /moddy no}, a wish resumed on join — and a wish offered to a
 * stopped pool is heard by nobody. So the wake lives in {@code WishQueue.offer}, the one door they
 * all use, and this pins it there.
 *
 * <p>Run with the agent switched off, so the wake only touches the clock and never tries to launch
 * a CLI from a unit test.
 */
class WishQueueWakesThePoolTest {

    private static final String ENABLE = "moddingfromamod.agent";
    private String prior;

    @BeforeEach
    void agentOff() {
        prior = System.getProperty(ENABLE);
        System.setProperty(ENABLE, "off");
    }

    @AfterEach
    void restore() {
        WishQueue.global().clear();
        if (prior == null) {
            System.clearProperty(ENABLE);
        } else {
            System.setProperty(ENABLE, prior);
        }
    }

    private static Wish aWish() {
        return Wish.create("a quiet test tower", WishSource.TYPED, System.currentTimeMillis(),
                UUID.randomUUID(), "kid", "minecraft:overworld", 0, 64, 0);
    }

    @Test
    void offeringToTheGlobalQueueCountsAsATouch() {
        AgentSession.lastActivityForTest(System.currentTimeMillis() - 3_600_000L);
        WishQueue.global().offer(aWish());
        assertTrue(AgentSession.msSinceActivity() < 60_000L,
                "a wish offered to the workers must wake the pool — a reopened or resumed wish "
                        + "reaches a stopped pool through this door and no other");
    }

    @Test
    void aPrivateQueueHasNoPoolBehindIt() {
        long old = System.currentTimeMillis() - 3_600_000L;
        AgentSession.lastActivityForTest(old);
        new WishQueue().offer(aWish());
        assertTrue(AgentSession.msSinceActivity() > 3_000_000L,
                "a gate's private queue must not touch the real pool's clock");
    }
}
