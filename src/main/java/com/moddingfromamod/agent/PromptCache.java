package com.moddingfromamod.agent;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * <b>Make every worker in the pool ask the model the same question, byte for byte, so the second
 * and third read the prefix the first one paid to write.</b>
 *
 * <p>A cache entry is keyed on a hash of the exact prefix — tools, then system prompt, then
 * messages — plus model and effort, scoped to the org. Two processes that agree on all of that
 * share the entry; anything that differs by one byte does not. So sharing across the pool is
 * entirely a question of (a) making the prefix identical and (b) letting one worker write it
 * before the others ask.
 *
 * <h2>WHAT THIS ACTUALLY BUYS, MEASURED: about 1,734 tokens per worker. Do not inflate it</h2>
 *
 * <p>Measured against the real prompt and the real tool list, one worker per run, repeated across
 * two different ports (i.e. two different working directories, as two games would get):
 *
 * <pre>
 *   without the flag   write 18,358   read 12,461
 *   with the flag      write 16,449   read 14,195
 * </pre>
 *
 * <p>The flag moves <b>1,734 tokens</b> out of the per-worker write and into the shared read, by
 * taking the working directory, git state and memory paths out of the system prompt so the static
 * preset in front of them becomes reusable. At three workers that is ~5,200 tokens a boot, or about
 * five cents. Real, stable, free — and small. It is not the answer to.
 *
 * <p><b>What it does NOT buy, and this was the expensive thing to learn.</b> The pool does not need
 * it to share: every worker already reads a common ~24,366-token head, and each still writes a
 * ~19,653-token tail with the flag on. The tail is written per conversation and survives everything
 * tried against it — a byte-identical first turn, one shared {@code -n} name across the pool, and a
 * worker 1 whose response finished ten seconds before the others asked. Treat the tail as the price
 * of a worker.
 *
 * <p>⚠ <b>Two of the numbers on the way here were ordering artifacts</b>, both flattering, so
 * measure arms cold and in both orders. A first pass read 36% cheaper because the second arm was
 * reading what the first had warmed; a later pass read 20,874 tokens of cross-directory sharing for
 * the same reason. Each arm now gets its own MCP server name so its prefix is cold from the first
 * byte, and every claim above survived running the arms in reverse.
 *
 * <h2>Why the obvious checks all said the prefix was already identical</h2>
 *
 * <p>{@link AgentSession#prompt()} takes no arguments and returns the same string for every worker;
 * the first user message was diffed across workers and was byte-identical at 31,233 chars.
 * {@link ToolRegistry} hands out a {@code LinkedHashMap}, so the tool block is insertion-ordered and
 * identical too. Every worker gets the same model, effort and working directory. Diffing two workers'
 * transcripts record by record, everything before the first assistant turn matched — prompt, tool
 * deltas, skill listing, token reminder. All of that was true, and the pool was still writing a tail
 * each. The tail is simply not a divergence.
 *
 * <h2>The TTL is set explicitly because the default depends on how the machine authenticates</h2>
 *
 * <p>Every session observed so far billed at {@code ephemeral_1h_input_tokens}, so on this machine
 * the default is already an hour. On an API key or credits it is <b>five minutes</b>, which will not
 * survive the gap between one child's wish and the next — the pool would re-write the prefix all
 * evening while appearing to be cached. Setting it here makes the TTL a property of the mod rather
 * than of the billing arrangement. The subagent TTL is a separate bucket that does not inherit the
 * main one, so it is set too.
 *
 * <p>{@code DISABLE_AUTOUPDATER} is the quiet one: a CLI that updates itself mid-session ships a
 * new system prompt or tool block, which silently splits the pool's cache in half. Pinning it means
 * the workers that started together stay comparable to each other.
 */
public final class PromptCache {

    private PromptCache() {
    }

    /**
     * Moves the CLI's per-machine system-prompt sections into the first user message.
     *
     * <p>Only applies with the default system prompt — it is ignored with {@code --system-prompt},
     * which we do not use.
     */
    static final String EXCLUDE_DYNAMIC_FLAG = "--exclude-dynamic-system-prompt-sections";

    /**
     * Five minutes, on both buckets — <b>because a parked worker refreshes its own entry</b>.
     *
     * <p>This was {@code 1h}, defended by the reasoning that <i>"the 5m TTL would expire between
     * wishes and turn every wish into a fresh write"</i>. That is true of the gap between WISHES
     * and irrelevant, because a wish is not what refreshes the entry — <b>the poll is.</b>
     *
     * <p>Three facts settle it, checked against the caching reference rather than reasoned about:
     *
     * <ol>
     *   <li><b>A cache read refreshes the entry's timer, free, on either TTL.</b> Every
     *       {@code await_wish} poll is a read.</li>
     *   <li>The lifetime is measured from the <b>start</b> of the request, so what matters is the
     *       start-to-start gap between requests sharing the prefix — not idle time.</li>
     *   <li>A write bills <b>1.25x at 5m against 2x at 1h</b>.</li>
     * </ol>
     *
     * <p>{@link com.moddingfromamod.agent.tools.AwaitWishTool} holds for {@link #POLL_MS} — 110
     * seconds — so a parked worker re-reads its prefix comfortably inside five minutes and the
     * entry never expires. The 1h TTL bought nothing that the polling was not already providing,
     * and charged double for every write to do it. The reference says exactly this: <i>"requests
     * that share a prefix and start less than 5 minutes apart keep the 5-minute cache warm
     * indefinitely — the 1-hour TTL buys nothing there except the doubled write price."</i>
     *
     * <p><b>This is not an independent setting.</b> It is a consequence of how long a worker goes
     * between requests, which is why {@code PromptCacheTest} asserts the two together rather than
     * asserting this string: raise the poll above five minutes, or add reaping that leaves a real
     * gap (an earlier issue's step 2), and 1h becomes correct again. The test fails and says so.
     */
    static final String TTL = "5m";

    /**
     * The parked worker's start-to-start gap, mirrored from {@code AwaitWishTool}'s hold.
     *
     * <p>Here so the TTL choice above can be checked against it by a test rather than by somebody
     * remembering that the two are related. They have been out of step once already.
     */
    static final long POLL_MS = 110_000L;

    /**
     * <b>One hour for the SUBAGENT bucket, and the split is the point</b>.
     *
     * <p> treats the TTL as one setting. It is not — it is a consequence of how often requests
     * sharing a prefix START, and the two buckets have nothing like the same cadence:
     *
     * <ul>
     *   <li>the main bucket is a worker polling {@code await_wish} every {@link #POLL_MS}, so it
     *       refreshes itself and 5m is strictly cheaper;</li>
     *   <li>a subagent has <b>no poll at all</b>. The reference researcher runs once per wish that
     *       names a character, so the gap between two of its requests is the gap between two
     *       WISHES — minutes to hours. A 5m entry would be cold every time, and the 1.25x write it
     *       saves is a write it would then have to pay on every single call.</li>
     * </ul>
     *
     * <p>So the reasoning that lowers the main bucket is the same reasoning that keeps this one at
     * an hour, and flipping both together — which is what a single constant invites — would have
     * been half right. That was the state before: one constant, one number, applied to two things
     * whose only shared property is the word TTL.
     */
    static final String SUBAGENT_TTL = "1h";

    /** Does this CLI know {@link #EXCLUDE_DYNAMIC_FLAG}? Asked of its own {@code --help}. */
    static boolean advertises(String helpText) {
        return helpText != null && helpText.contains(EXCLUDE_DYNAMIC_FLAG);
    }

    /**
     * The cache flags to add to a worker's command line, given a way to read the CLI's help.
     *
     * <p>The supplier is called at most once per pool start and may be memoised by the caller; it
     * returns an empty list rather than throwing if the help cannot be read, because a wish that
     * costs too much is better than a wish that does not happen.
     */
    static List<String> flags(Supplier<String> helpText) {
        try {
            return advertises(helpText.get()) ? List.of(EXCLUDE_DYNAMIC_FLAG) : List.of();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * Pin the cache TTL and the CLI version for a worker's environment.
     *
     * <p>Applied to every worker identically — an environment that differed between workers would
     * be one more way to split the prefix.
     *
     * @param anthropicEndpoint whether this worker talks to Anthropic. A worker redirected to
     *     another provider must NOT be given the TTL — see below.
     */
    static void applyTo(Map<String, String> env, boolean anthropicEndpoint) {
        // ⚠ THE TTL IS ANTHROPIC-ONLY, AND PINNING IT ELSEWHERE KILLS THE WISH SILENTLY.
        //
        // `CLAUDE_CODE_PROMPT_CACHE_TTL=1h` makes the CLI send `cache_control.ttl: "1h"`. Meta's
        // endpoint rejects that outright — `API Error: 400 cache_control.ttl: 1h is not supported`
        // — on EVERY turn, so a redirected worker makes zero tool calls and then sits there. What
        // reaches the log is a wish that granted nothing after 900 s, i.e. it presents as a slow
        // model rather than a request the endpoint refused, which is the same disguise the 401
        // credential bug wore in docs/MUSE_SPARK_ARM.md.
        //
        // Measured 2026-09-02: with the TTL pinned, muse-spark-1.3 made 0 tool calls; the cause was
        // only visible in the worker's own transcript, never in the server log.
        //
        // Ordering is why this needs a parameter rather than a reorder. WishModel.applyTo runs
        // FIRST and strips CLAUDE*, so anything set here lands after that strip and survives it.
        // Swapping the two would fix the TTL and break something else — the strip would also take
        // CLAUDE_AUTO_BACKGROUND_TASKS=0, which is the one flag that makes a parked worker
        // possible at all.
        if (anthropicEndpoint) {
            env.put("CLAUDE_CODE_PROMPT_CACHE_TTL", TTL);
            env.put("CLAUDE_CODE_SUBAGENT_PROMPT_CACHE_TTL", SUBAGENT_TTL);
        } else {
            // Leave the provider's own default in place rather than guessing a value it supports.
            env.remove("CLAUDE_CODE_PROMPT_CACHE_TTL");
            env.remove("CLAUDE_CODE_SUBAGENT_PROMPT_CACHE_TTL");
        }
        env.put("DISABLE_AUTOUPDATER", "1");
        // ⚠ THE ONE THAT MAKES A PARKED WORKER POSSIBLE AT ALL.
        //
        // Handed a long-running tool call, the CLI moves it to a background task and ENDS the
        // agent's turn -- "The tool call is still running - it was moved to the background". For a
        // worker whose whole job is to sit in await_wish, that is not a convenience, it is the deaf
        // state: the turn is over and nobody is listening. Measured: with this off, calls
        // were held to completion at 200 s and again at 400 s; with it on, a long await never
        // survives. Every other tool the wish agent has is ours and returns quickly, so nothing
        // else here wants backgrounding.
        env.put("CLAUDE_AUTO_BACKGROUND_TASKS", "0");
    }
}
