package com.moddingfromamod.world;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>One record per world-rules event</b>, in {@code <MODDY_HOME>/world-rules.jsonl} —
 * the file an operator's tools count refusal rates from:
 * <ul>
 *   <li>{@code submit} — a wish was made on this world (every world, a no-rules one too: it is what
 *       "went to the no-rules world after a refusal" is counted from);</li>
 *   <li>{@code grade} — every grade, in every verdict, INCLUDING the passes: a refusal rate needs its
 *       denominator ({@code verdict}, {@code act}, {@code rule}, {@code effects}, latency, cost);</li>
 *   <li>{@code backstop} — the reach rule at {@code tools/call} refused what the agent tried: a wish
 *       the grader let through, counted as a GRADER MISS against the rule it broke.</li>
 *   <li>{@code hold} — a wish handed to its worker BEFORE its grades landed (an earlier issue's pickup ratchet),
 *       and how long after the hand-over they landed ({@code landedMs}) with what came of it
 *       ({@code outcome}: {@link #HOLD_OUTCOMES}) — the delay the grades add, counted.</li>
 * </ul>
 * Its own small file rather than a kind in the action log, so a minute-by-minute KPI pass never
 * reads the big one. <b>No wish text</b>: ids, the player's UUID, and closed vocabularies only, so
 * the numbers can travel to an operator's tools without carrying a child's words.
 */
public final class WorldRulesLog {

    private static final Logger LOGGER = LoggerFactory.getLogger("moddingfromamod/world-rules");
    private static final Object LOCK = new Object();
    private static volatile Path override;

    private WorldRulesLog() {
    }

    /** Where the records go. */
    public static Path file() {
        Path o = override;
        return o != null ? o : com.moddingfromamod.runtime.RuntimeConfig.home().resolve("world-rules.jsonl");
    }

    /** A wish was made here. */
    public static void submit(String wishId, String playerId) {
        JsonObject o = base("submit", wishId, playerId);
        write(o);
    }

    /** A grade, and what was done with it. */
    public static void grade(String wishId, String playerId, WorldRules rules, WorldRulesGate.Decision d,
                             long latencyMs, double costUsd, String modelId) {
        grade(wishId, playerId, rules, d, latencyMs, costUsd, modelId, "");
    }

    /** …and which arm of the grader chain answered (subscription / api / haiku), when known. */
    public static void grade(String wishId, String playerId, WorldRules rules, WorldRulesGate.Decision d,
                             long latencyMs, double costUsd, String modelId, String arm) {
        JsonObject o = base("grade", wishId, playerId);
        o.addProperty("rulesSha", shortSha(rules));
        o.addProperty("verdict", d.verdict().label());
        o.addProperty("provenance", d.verdict().provenance().name());
        o.addProperty("act", d.logAction());
        o.addProperty("rule", d.rule() == null ? "" : d.rule().id());
        o.add("effects", keys(d.denied()));
        o.add("asked", keys(d.verdict().effects()));
        o.addProperty("latencyMs", latencyMs);
        o.addProperty("costUsd", costUsd);
        o.addProperty("model", modelId == null ? "" : modelId);
        if (arm != null && !arm.isBlank()) {
            o.addProperty("arm", arm);
        }
        write(o);
    }

    /** The closed vocabulary of a {@code hold} record's {@code outcome}. */
    public static final List<String> HOLD_OUTCOMES = List.of("CLEARED", "REFUSED", "REWORDED", "FAILED");

    /**
     * A wish handed over before its grades landed, and what the verdicts did once they did (
     *). {@code outcome} is one of {@link #HOLD_OUTCOMES}; anything else is written as FAILED, so
     * the record never carries free text.
     */
    public static void hold(String wishId, String playerId, long landedMs, String outcome) {
        JsonObject o = base("hold", wishId, playerId);
        o.addProperty("landedMs", Math.max(0L, landedMs));
        o.addProperty("outcome", HOLD_OUTCOMES.contains(outcome) ? outcome : "FAILED");
        write(o);
    }

    /** The hold outcome for {@code AwaitWishTool.resolve}'s words: cleared, refused or reworded. */
    public static String holdOutcome(String resolved) {
        String r = resolved == null ? "" : resolved;
        if (r.startsWith("cleared")) {
            return "CLEARED";
        }
        if (r.startsWith("refused")) {
            return "REFUSED";
        }
        if (r.startsWith("reworded")) {
            return "REWORDED";
        }
        return "FAILED";
    }

    /** The reach backstop refused a call the agent made: a grader miss. */
    public static void backstop(String wishId, String playerId, String tool, WorldEffect effect, WorldRules rules) {
        WorldRules in = rules == null ? WorldRules.CLOSED : rules;
        JsonObject o = base("backstop", wishId, playerId);
        o.addProperty("tool", tool == null ? "" : tool);
        o.addProperty("effect", effect == null ? "" : effect.key());
        WorldRule r = in.ruleFor(effect);
        o.addProperty("rule", r == null ? "" : r.id());
        o.addProperty("rulesSha", shortSha(in));
        write(o);
    }

    private static JsonObject base(String kind, String wishId, String playerId) {
        JsonObject o = new JsonObject();
        o.addProperty("at", Instant.now().toString());
        o.addProperty("kind", kind);
        o.addProperty("world", WorldModuleHost.worldId());
        o.addProperty("wishId", wishId == null ? "" : wishId);
        o.addProperty("playerId", playerId == null ? "" : playerId);
        o.addProperty("test", com.moddingfromamod.RunContext.isTestRun());
        return o;
    }

    private static String shortSha(WorldRules r) {
        String s = r == null ? "" : r.sha();
        return s.length() > 8 ? s.substring(0, 8) : s;
    }

    private static JsonArray keys(Set<WorldEffect> effects) {
        JsonArray a = new JsonArray();
        List<WorldEffect> sorted = new ArrayList<>(effects);
        sorted.sort(null);
        for (WorldEffect e : sorted) {
            a.add(e.key());
        }
        return a;
    }

    private static void write(JsonObject o) {
        try {
            Path f = file();
            synchronized (LOCK) {
                Files.createDirectories(f.getParent());
                Files.writeString(f, o + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            }
        } catch (Exception e) {
            LOGGER.debug("could not write a world-rules record: {}", e.toString());
        }
    }

    /** Write somewhere else (a test's own file); null restores. */
    public static void fileForTest(Path p) {
        override = p;
    }
}
