package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonParser;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Does the narrator actually run?</b>
 *
 * <p>{@link ToolNarrationTest} proves the words are chosen correctly; it cannot prove the background
 * ticker ever fires inside the game. That distinction is not academic here: the first version used a
 * {@code ScheduledExecutorService}, which worked in a unit test and whose task <b>never ran once</b>
 * on a live server — no tick, no exception, nothing, because a scheduled task that dies takes its
 * reason into a Future nobody reads. It is a plain daemon thread now, and this is what says so.
 *
 * <p><b>Only the timing-free half lives here.</b> A GameTest server ticks as fast as it can rather
 * than at 20 per second, so its ticks have no fixed relationship to wall-clock — and the narrator's
 * beats are wall-clock, because a child waiting 40 seconds is waiting 40 real seconds. Asserting
 * "it spoke after 120 ticks" failed while the feature worked, twice, because 120 ticks went by in
 * about a fifth of a second. The beats are proven in {@code ToolNarratorTimingTest}, which can wait
 * in real time; what this proves is the wiring.
 *
 * <p><b>Each test hands the narrator its own clock and its own sink</b>, rather than watching the
 * singleton's statics. That is not tidiness: tested through the globals, these failed against each
 * other rather than against the code — GameTests in the same batch run interleaved on the one
 * server thread, so one test's
 * "the agent just spoke" and another's "forget what was said" landed inside a third's measurement,
 * and every failure read as a broken feature.
 */
@GameTestHolder(com.moddingfromamod.ModdingFromAModMod.MODID)
public class ToolNarratorGameTest {

    /** The agent last spoke long ago — the real case during a 40-second author_datapack. */
    private static final java.util.function.Supplier<Long> LONG_SILENT =
            () -> System.currentTimeMillis() - ToolNarration.AGENT_LINE_FRESH_MS - 5_000;

    /** The free half: a {@code status} riding along is delivered the instant the call arrives. */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void aStatusRidingAlongIsShownImmediately(GameTestHelper helper) {
        AtomicReference<String> heard = new AtomicReference<>("");
        var handle = ToolNarrator.start("author_datapack", JsonParser.parseString(
                        "{\"status\":\"Writing the fire-breath spell\"}").getAsJsonObject(),
                LONG_SILENT, (doing, phase) -> heard.set(doing));
        handle.stop();
        helper.assertTrue("Writing the fire-breath spell".equals(heard.get()),
                Component.literal("the ride-along never reached the status path, it said: '" + heard.get() + "'"));
        helper.succeed();
    }

    /** ...and a fast tool is left alone, so the bar does not flicker on every list_creatures. */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void aFastToolIsNotNarrated(GameTestHelper helper) {
        AtomicReference<String> heard = new AtomicReference<>("");
        ToolNarrator.start("list_creatures", null, LONG_SILENT,
                (doing, phase) -> heard.set(doing)).stop();
        helper.runAfterDelay(60, () -> {
            helper.assertTrue(heard.get().isEmpty(),
                    Component.literal("an instant tool narrated anyway: '" + heard.get() + "'"));
            helper.succeed();
        });
    }

}
