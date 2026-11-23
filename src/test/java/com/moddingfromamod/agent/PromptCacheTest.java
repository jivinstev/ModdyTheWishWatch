package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The pool shares one cached prefix, or it pays to write the same 20k tokens N times.
 *
 * <p>These are the rules that decide it. The measurement lives in {@code tools/cache-arm.py}; this
 * is what stops the mechanism being removed by someone who has not read the measurement.
 */
class PromptCacheTest {

    /**
     * The flag is only passed to a CLI that advertises it.
     *
     * <p>This is the one that matters for a child's machine: an unknown option is a HARD ERROR on
     * this CLI, so passing it blind means every wish fails rather than merely costing too much.
     */
    @Test
    void theFlagIsOnlyPassedToACliThatKnowsIt() {
        assertTrue(PromptCache.advertises(
                "  --exclude-dynamic-system-prompt-sections\n      Move per-machine sections"),
                "a CLI that lists the flag should get it");
        assertFalse(PromptCache.advertises("  --model <model>   Model for the current session"),
                "an older CLI must NOT be handed a flag it will reject outright");
        assertFalse(PromptCache.advertises(null), "an unreadable --help reads as 'no'");
    }

    /** And that decision is what {@link PromptCache#flags} actually returns. */
    @Test
    void flagsFollowWhatTheCliAdvertises() {
        assertEquals(List.of(PromptCache.EXCLUDE_DYNAMIC_FLAG),
                PromptCache.flags(() -> "… " + PromptCache.EXCLUDE_DYNAMIC_FLAG + " …"));
        assertEquals(List.of(), PromptCache.flags(() -> "an old CLI"));
    }

    /**
     * A probe that throws must not take the wish down with it.
     *
     * <p>A wish that costs too much is strictly better than a wish that does not happen, so the
     * failure direction is "no flag", never "no agent".
     */
    @Test
    void aBrokenProbeCostsMoneyRatherThanTheWish() {
        assertEquals(List.of(), PromptCache.flags(() -> {
            throw new IllegalStateException("no such binary");
        }), "a failed probe degrades to an uncached worker, it does not throw");
    }

    /**
     * The TTL is pinned on BOTH buckets, and the CLI is pinned too.
     *
     * <p>The subagent bucket does not inherit the main one — it would default to five minutes even
     * where the main conversation gets an hour. And a CLI that updates itself mid-session ships a
     * different system prompt, which splits the pool's cache silently.
     */
    @Test
    void theTtlIsPinnedOnBothBucketsAndSoIsTheCliVersion() {
        Map<String, String> env = new HashMap<>();
        PromptCache.applyTo(env, true);
        // The property here is that BOTH are pinned, which is what this test has always been for.
        // The values now DIFFER, and that is an earlier issue's finding applied per bucket rather than
        // globally: a parked worker refreshes its own entry every 110s so 5m is strictly cheaper,
        // while a subagent has no poll and its gap is wish-to-wish, so an hour is what keeps it warm.
        assertEquals("5m", env.get("CLAUDE_CODE_PROMPT_CACHE_TTL"),
                "the worker's own await_wish poll refreshes this every 110s, so 1h paid the 2x "
                + "write multiplier for a longevity the polling already provided");
        assertEquals("1h", env.get("CLAUDE_CODE_SUBAGENT_PROMPT_CACHE_TTL"),
                "subagents are a separate bucket, do not inherit the main TTL, and — unlike the "
                + "worker — have nothing polling to keep them warm");
        assertEquals("1", env.get("DISABLE_AUTOUPDATER"),
                "an auto-update mid-session splits the pool's shared prefix");
    }

    /**
     * Every worker gets the SAME environment.
     *
     * <p>Applied twice it must produce the same map: a per-worker difference here would be one more
     * way to split the prefix, which is the exact bug this class exists to close.
     */
    @Test
    void everyWorkerGetsTheSameEnvironment() {
        Map<String, String> one = new HashMap<>();
        Map<String, String> two = new HashMap<>();
        PromptCache.applyTo(one, true);
        PromptCache.applyTo(two, true);
        assertEquals(one, two, "the pool's environment must not vary between workers");
    }

    /**
     * <b>A redirected worker must not be handed an Anthropic-only cache TTL.</b>
     *
     * <p>Meta's endpoint answers {@code cache_control.ttl: "1h"} with
     * {@code 400 ... is not supported} on every turn, so the worker makes zero tool calls and the
     * wish reads as a slow model rather than a refused request. Measured 2026-09-02 against
     * muse-spark-1.3, and invisible in the server log — only the worker's own transcript said so.
     */
    @Test
    void aRedirectedWorkerIsNotPinnedToTheAnthropicCacheTtl() {
        Map<String, String> env = new HashMap<>();
        env.put("CLAUDE_CODE_PROMPT_CACHE_TTL", "1h");
        env.put("CLAUDE_CODE_SUBAGENT_PROMPT_CACHE_TTL", "1h");

        PromptCache.applyTo(env, false);

        assertFalse(env.containsKey("CLAUDE_CODE_PROMPT_CACHE_TTL"),
                "a foreign endpoint rejects the 1h TTL and the wish then grants nothing");
        assertFalse(env.containsKey("CLAUDE_CODE_SUBAGENT_PROMPT_CACHE_TTL"),
                "the subagent bucket is a separate header and is refused just the same");
        // The flags that are NOT Anthropic-specific must survive: the parked worker depends on it.
        assertEquals("0", env.get("CLAUDE_AUTO_BACKGROUND_TASKS"),
                "backgrounding a long await_wish is what makes a worker go deaf (#352)");
        assertEquals("1", env.get("DISABLE_AUTOUPDATER"));
    }

    @Test
    @DisplayName("#364: the TTL matches how often a parked worker actually re-reads its prefix")
    void theTtlIsAConsequenceOfThePollIntervalNotAnIndependentSetting() {
        // THE BUG THIS PINS. The TTL was 1h, defended by "the 5m TTL would expire between wishes".
        // True of the gap between WISHES, and irrelevant — a wish is not what refreshes the entry,
        // the POLL is, and a cache read refreshes the timer free on either TTL. So the 1h TTL
        // bought a longevity the polling already provided and charged the 2x write multiplier
        // (against 1.25x at 5m) to do it. Nothing anywhere connected the two numbers.
        //
        // Asserted as a RELATION rather than as the string "5m", because the string is the
        // conclusion and the relation is the reason. Raise the poll past five minutes, or add
        // reaping that leaves a real gap (step 2), and 1h becomes correct again — at which
        // point this fails and says which way to move.
        long fiveMinutes = 5 * 60 * 1000L;
        if (PromptCache.POLL_MS < fiveMinutes) {
            assertEquals("5m", PromptCache.TTL,
                    "a worker re-reads its prefix every " + (PromptCache.POLL_MS / 1000)
                    + "s, which is inside the 5-minute window, so the entry never expires on its "
                    + "own — and the 1h TTL would pay 2x on every write for a longevity the "
                    + "polling already provides");
        } else {
            assertEquals("1h", PromptCache.TTL,
                    "a worker now goes " + (PromptCache.POLL_MS / 1000) + "s between requests, "
                    + "which is past the 5-minute window, so a 5m entry would expire between polls "
                    + "and turn every one into a fresh write. The doubled write is worth it here.");
        }
    }

    @Test
    @DisplayName("the poll interval this is derived from is the one the tool actually uses")
    void thePollIntervalIsNotACopyThatCanDrift() {
        // A mirrored constant that nobody checks is how the TTL and the poll got out of step in the
        // first place. AwaitWishTool's default is private, so this asserts the value it documents
        // in its own schema — the number an agent is told and the number this reasons from must be
        // the same one.
        assertEquals(110_000L, PromptCache.POLL_MS,
                "AwaitWishTool holds for 110s and says so in its schema; if that moves, this must "
                + "move with it or the TTL above is reasoning from a number that no longer exists");
    }
}
