package com.moddingfromamod.testing;

import net.minecraft.gametest.framework.GameTestHelper;

/**
 * <b>An event a GameTest stages ends with that test</b> — passed, failed or timed out ().
 *
 * <p>The event store is per server, so an event a test leaves behind keeps ticking through every later batch: it
 * writes blocks, catches mobs, and an OWNERLESS one ({@code owner ""}, which is what a test stages) may be filmed by
 * any wish photograph ({@link com.moddingfromamod.event.EventDirector#filmableBy}). found a vortex doing exactly
 * that. A test's own success path usually waits for the event to end; a red or timed-out one did not.
 * {@link com.moddingfromamod.event.EventDirector#endForTest} stops it and gives its blocks back, and is a no-op for an
 * event that already finished, so registering it costs nothing on the happy path.
 */
public final class EventsForTest {
    private EventsForTest() {
    }

    /** Stop event {@code id} when this test ends; returns {@code id}, so it wraps a stage call. */
    public static String endsWithTheTest(GameTestHelper helper, String id) {
        MockPlayerNetwork.afterTest(helper,
                () -> com.moddingfromamod.event.EventDirector.endForTest(helper.getLevel().getServer(), id));
        return id;
    }
}
