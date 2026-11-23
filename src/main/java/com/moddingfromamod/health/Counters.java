package com.moddingfromamod.health;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * <b>The process's own counters</b> — what has happened since this JVM started, for {@code /metrics}
 * (HO-2).
 *
 * <p>Pure and Minecraft-free, so the arithmetic is unit-testable and so nothing here can reach a
 * world, a player or a wish's words.
 *
 * <h2>They are incremented from ONE place, and that is the design</h2>
 *
 * <p>{@code ActionLog.append} is the single funnel every record of interest already passes through,
 * so the counters are derived there rather than at each call site. That gives the property
 * {@code digest.py} has for money: <b>a number here and the records it counts cannot disagree</b>,
 * because they are written by the same call. Incrementing at the call sites instead would mean four
 * more places to keep in step, and the first one somebody forgot would be a metric that is quietly
 * low — which is worse than one that is missing, because it reads as good news.
 *
 * <h2>What may NEVER become a label</h2>
 *
 * <p>⚠ <b>No player name, no player id, no wish text, no artifact id, no file path.</b> Two reasons
 * and either is sufficient: a Prometheus label is unbounded cardinality, and this is a server for
 * children — {@code docs/CHILD_SAFETY.md} keeps the words in the digest, which goes to one person,
 * precisely so they are not in a metrics store. The digest is where words belong; this is where
 * counts belong. {@code MetricsExpositionTest} asserts the rendered page contains no player id.
 *
 * <p>Every label value is therefore drawn from a <b>closed vocabulary</b> and passed through
 * {@link #tidy}, which folds anything unrecognised to {@code other} rather than minting a new
 * series. A misspelled outcome becoming its own time series forever is how a metrics store quietly
 * fills up.
 *
 * <h2>Counters reset when the process does, which is normal</h2>
 *
 * <p>Prometheus handles a counter reset natively ({@code rate()} and friends detect it), and
 * {@code moddy_start_time_seconds} in the exposition says when it happened. No attempt is made to
 * persist them: a durable count belongs in the action log, which already has one.
 */
public final class Counters {

    private Counters() {
    }

    /** One counter series: a name plus its (ordered, closed-vocabulary) labels. */
    public record Counter(String name, Map<String, String> labels, long value) {
    }

    private static final Map<String, LongAdder> COUNTS = new ConcurrentHashMap<>();

    /** When this process started counting — rendered so a reset is legible. */
    private static final long STARTED_MS = System.currentTimeMillis();

    public static long startedMs() {
        return STARTED_MS;
    }

    // ===================== what ActionLog reports =====================

    /** A wish was recorded, with its {@code outcome} and how the child made it. */
    public static void wish(String outcome, String source) {
        bump("moddy_wishes_total",
                "outcome", tidy(outcome, WISH_OUTCOMES),
                "source", tidy(source, WISH_SOURCES));
    }

    /** A child-safety decision, by layer and action. {@code SHADOW_*} is its own action, not an ALLOW. */
    public static void safety(String layer, String action) {
        bump("moddy_safety_actions_total",
                "layer", tidy(layer, SAFETY_LAYERS),
                "action", tidy(action, SAFETY_ACTIONS));
    }

    /** One MCP tool call. Bounded: the tool list is fixed at ~61 names. */
    public static void toolCall(String tool, boolean ok) {
        bump("moddy_tool_calls_total", "tool", slug(tool), "ok", ok ? "true" : "false");
    }

    /** The child said no to what he was given — the highest-value line in the log. */
    public static void rejection() {
        bump("moddy_rejections_total");
    }

    // ===================== WAIT vs WORK (HO-14) =====================

    /**
     * <b>A wish waited, and this is what it waited FOR.</b>
     *
     * <p>⚠ <b>ONE FAMILY WITH A LABEL, NEVER FOUR METRIC NAMES</b>, and that is the whole design of
     * HO-14 rather than a formatting preference. {@code wish-latency.py} already answers <i>how long
     * did a wish take</i>; what nothing answers is <b>how much of that was queueing</b> — and
     * queueing is precisely what a cap manufactures. Cap the cameras at two and some wishes start
     * waiting for one; cap the workers and some wait for a slot; a cold worker makes every wish
     * behind it wait for a process to boot. Four separate names cannot be summed into "how much of
     * this wish was queueing"; {@code sum by (wish) (moddy_wish_wait_seconds_total)} can.
     *
     * <p>⚠ <b>And the honest limitation, stated here because it is invisible from a dashboard:</b>
     * this measures only the waits somebody thought to instrument. A wish that is slow for a reason
     * with no {@link WaitReason} reports zero queueing and looks reassuring. The total is therefore
     * reconciled against {@code wish-latency.py}'s elapsed — an unexplained remainder is a finding,
     * not a rounding error.
     *
     * @param reason why it waited; anything unrecognised folds to {@code other}, as every
     *               vocabulary here does, because a typo minting a permanent new series is a
     *               metrics store that does not fit months later with nothing naming the cause
     * @param ms     how long, in milliseconds — see {@link #MILLIS_SUFFIX} for why
     */
    public static void waited(String reason, long ms) {
        waited(reason, ms, null);
    }

    /**
     * The same, with one extra dimension.
     *
     * <p>⚠ <b>`cold` IS A DIMENSION OF `worker_slot`, NOT A SIBLING OF IT, and that correction is
     * the reason this overload exists.</b> {@code worker_coldstart} was a fifth {@link
     * #WAIT_REASONS} entry until somebody asked why it had no caller: {@code worker_slot} is
     * recorded as {@code now - wish.createdMs()}, the wish's ENTIRE time from submission to being
     * picked up, so a boot inside that window is already counted. A sibling reason would have
     * counted it twice and made {@code sum(moddy_wish_wait_seconds_total)} exceed the wish's real
     * queueing time — breaking the one claim this whole family is built on.
     *
     * <p>As a label it decomposes rather than duplicates: the sum stays honest and
     * {@code sum by (cold) (...)} still answers "how much of the waiting was a process booting".
     */
    public static void waited(String reason, long ms, Boolean cold) {
        String r = tidy(reason, WAIT_REASONS);
        if (cold == null) {
            bump("moddy_wish_waits_total", "reason", r);
            if (ms > 0) {
                add("moddy_wish_wait_seconds_total", ms, "reason", r);
            }
            return;
        }
        String c = cold ? "true" : "false";
        bump("moddy_wish_waits_total", "reason", r, "cold", c);
        if (ms > 0) {
            add("moddy_wish_wait_seconds_total", ms, "reason", r, "cold", c);
        }
    }

    /**
     * A photograph came back DEGRADED rather than framed.
     *
     * <p>⚠ <b>This counts a SILENT quality loss, which is why it earns a series of its own.</b>
     * {@code PhotographTool} retries a busy camera six times and then falls through to a single
     * unframed snapshot — its own comment records one ladder run producing no framed shot at all
     * while an identical build produced eight. The wish still succeeds. The child still gets a
     * card. It is the wrong picture, and nothing anywhere says so.
     *
     * <p>HO-14 caps the cameras at two, which makes that fallback MORE likely. A cap that costs
     * something should be arguable with evidence rather than with taste, and this is the evidence.
     */
    public static void photoDegraded(String reason) {
        bump("moddy_photo_degraded_total", "reason", tidy(reason, PHOTO_DEGRADED_REASONS));
    }

    // ===================== reading them =====================

    /** Every counter, name-then-label ordered so the exposition is byte-stable between scrapes. */
    public static List<Counter> snapshot() {
        List<Counter> out = new ArrayList<>();
        List<String> keys = new ArrayList<>(COUNTS.keySet());
        java.util.Collections.sort(keys);
        for (String key : keys) {
            out.add(parse(key, COUNTS.get(key).sum()));
        }
        return out;
    }

    /** For tests, and for nothing else — a counter that a test could not reset is a shared static. */
    public static void resetForTest() {
        COUNTS.clear();
    }

    // ===================== the machinery =====================

    /**
     * The suffix that means "this adder holds MILLISECONDS and renders as seconds".
     *
     * <p>Prometheus wants base units, so the series is {@code ..._seconds_total}; a {@link
     * LongAdder} holds longs, so whole seconds would round a 200 ms slot wait to nothing — and a
     * queueing metric that cannot see sub-second queueing is measuring the wrong end of its own
     * range. Milliseconds in, seconds out, by a RULE rather than a list: any counter whose name
     * ends in this suffix is divided by 1000 exactly once, in {@code MetricsExposition}.
     */
    public static final String MILLIS_SUFFIX = "_seconds_total";

    private static void bump(String name, String... labelPairs) {
        add(name, 1L, labelPairs);
    }

    /** Add {@code n} rather than one — for the duration counters, which accumulate. */
    private static void add(String name, long n, String... labelPairs) {
        COUNTS.computeIfAbsent(key(name, labelPairs), k -> new LongAdder()).add(n);
    }

    /**
     * The map key is the rendered series, so two calls with the same labels in the same order land
     * on one adder and {@link #parse} can take it apart again without a second data structure.
     */
    private static String key(String name, String... labelPairs) {
        StringBuilder sb = new StringBuilder(name);
        for (int i = 0; i + 1 < labelPairs.length; i += 2) {
            sb.append('\u0000').append(labelPairs[i]).append('\u0000').append(labelPairs[i + 1]);
        }
        return sb.toString();
    }

    private static Counter parse(String key, long value) {
        String[] parts = key.split("\u0000", -1);
        Map<String, String> labels = new LinkedHashMap<>();
        for (int i = 1; i + 1 < parts.length; i += 2) {
            labels.put(parts[i], parts[i + 1]);
        }
        return new Counter(parts[0], labels, value);
    }

    // ===================== the closed vocabularies =====================

    private static final List<String> WISH_OUTCOMES = List.of(
            "FULFILLING", "PENDING_ESCALATION", "THROTTLED", "EMPTY", "BLOCKED", "DRAINING",
            "DONE", "FAILED", "REJECTED", "STOPPED", "TIMED_OUT");
    private static final List<String> WISH_SOURCES = List.of(
            "PRESET", "TYPED", "COMMAND", "REDIRECT", "LIBRARY", "CACHE", "BUILDER");

    /**
     * Why a wish waited. <b>Adding a reason is adding a constant here</b>, never passing a string:
     * the label vocabulary is closed for the same reason every other one is, and an open one on a
     * per-wish path is how a metrics store stops fitting.
     */
    private static final List<String> WAIT_REASONS = List.of(
            // A camera was busy. HO-14 caps concurrent cameras at 2, so this is the label that says
            // what that cap cost.
            "camera",
            // Every wish slot was busy (WishSlots.slots()).
            "worker_slot"
            // ⚠ `worker_coldstart` WAS HERE AND IS NOT A REASON. It would have double-counted:
            // `worker_slot` is the wish's whole wait, so a boot inside that window is already in
            // it. It is a LABEL on worker_slot now (`cold`), which decomposes instead — see the
            // three-argument `waited`.
            //
            // ⚠ `pack_reload` WAS HERE, declared and deliberately never wired: the reveal's
            // 6654 ms pack install happened only on the pack rig (`rigatoms=off`), and the label
            // was to die with that arm. deleted the arm (2026-09-23), so it has.
            );

    /** Why a photograph came back unframed. */
    private static final List<String> PHOTO_DEGRADED_REASONS = List.of(
            // The camera never freed up and PhotographTool fell through to a bare snapshot.
            "camera_timeout",
            // There was no camera at all, so the child's own eyes took it.
            "no_camera");
    private static final List<String> SAFETY_LAYERS = List.of("input", "plan", "text", "datapack");
    private static final List<String> SAFETY_ACTIONS = List.of(
            "ALLOW", "REDIRECT", "BLOCK", "UNAVAILABLE_OPEN", "UNAVAILABLE_CLOSED",
            "SHADOW_ALLOW", "SHADOW_REDIRECT", "SHADOW_BLOCK");

    /**
     * Fold a value to its closed vocabulary, case-insensitively; anything else becomes
     * {@code other}.
     *
     * <p>⚠ The fold is the point. A typo, a new status somebody added without telling this file, or
     * a value that arrived from outside would otherwise mint a permanent new time series — and the
     * first symptom is a metrics store that will not fit, months later, with nothing naming the
     * cause. {@code other} being non-zero is itself the signal that this list needs a line.
     */
    static String tidy(String value, List<String> vocabulary) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        String v = value.trim();
        for (String known : vocabulary) {
            if (known.equalsIgnoreCase(v)) {
                return known;
            }
        }
        return "other";
    }

    /**
     * A tool name, reduced to what a label may contain.
     *
     * <p>Tool names are ours and already tame; this exists so a future one cannot break the
     * exposition's syntax, which would take the whole scrape down rather than one series.
     */
    public static String slug(String tool) {
        if (tool == null || tool.isBlank()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : tool.trim().toLowerCase(Locale.ROOT).toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) || c == '_' ? c : '_');
        }
        String s = sb.toString();
        return s.length() > 64 ? s.substring(0, 64) : s;
    }
}
