package com.moddingfromamod.agent;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>The model did not forget the field. It called it something else.</b>
 *
 * <p>Twenty argument sets were refused across the real Spikor runs, and reading the tool errors
 * alone gives the wrong diagnosis every time — {@code "No command given."} reads as an omission. The
 * logged {@code argKeys} say otherwise:
 *
 * <table>
 *   <caption>what was actually sent</caption>
 *   <tr><th>tool</th><th>schema wants</th><th>model sent</th><th>n</th></tr>
 *   <tr><td>{@code run_command}</td><td>{@code command}</td><td>{@code commands}</td><td>7</td></tr>
 *   <tr><td>{@code set_status}</td><td>{@code doing}</td><td>{@code message}</td><td>6</td></tr>
 *   <tr><td>{@code add_template}</td><td>{@code all}/{@code any}</td><td>{@code terms}</td><td>4</td></tr>
 * </table>
 *
 * <p><b>17 of 20 were a naming near-miss; only 3 were a genuinely absent field.</b> That matters
 * because it rules out the obvious fix: no amount of marking a field REQUIRED — in prose, in
 * {@code required}, in any notation — reaches a caller who believes it has already supplied it.
 * {@code command} was in {@code required} for all seven of those calls.
 *
 * <p>And the pressure is partly ours. {@code run_command}'s parameter was made <em>list-only</em>
 * because the string form produced zero batches in 752 calls (see {@code Schemas.list}) — and then
 * kept its singular name. A list called {@code command} invites {@code commands}; the model's guess
 * was arguably the better name.
 *
 * <h2>Two rules, and why this cannot misfire</h2>
 *
 * <p>A key is moved only when <b>all three</b> hold: the schema DECLARES the target, the args LACK
 * it, and the source key is one the schema does NOT declare. So a call that was going to be accepted
 * is never touched — the only reachable transition is refusal → acceptance.
 *
 * <p>That third condition is also what scopes the vocabulary for free. {@code message} means the
 * kid-facing line in {@code notify_player}, which declares it; the alias therefore never fires
 * there. No per-tool table is needed to prevent the collision.
 *
 * <ol>
 *   <li><b>Plural/singular.</b> Mechanical, needs no vocabulary, and generalises to fields nobody
 *       has seen guessed yet. This alone covers the largest group.
 *   <li><b>A short synonym list</b> for the semantic misses, which no rule can derive.
 * </ol>
 *
 * <p>Every rewrite is recorded on the call ({@code ActionLog}'s {@code aliased} field) rather than
 * being silently absorbed. A forgiving boundary that hides its own use would turn a measurable
 * naming problem into an invisible one, and the rate is the evidence for whether a field should
 * simply be RENAMED to the thing the model keeps reaching for.
 */
public final class ArgAliases {

    private ArgAliases() {
    }

    /**
     * Semantic guesses, keyed by the schema's name. Only ever consulted for a property the schema
     * declares, so these words are free to mean something else in a tool that declares them.
     */
    private static final Map<String, List<String>> SYNONYMS = Map.of(
            "doing", List.of("message", "text", "status", "activity"),
            // EVERY WORD BELOW IS ONE A REAL RUN SENT -- none is invented. `id` for `tag` came from
            // apply_effect, whose own description said "the same id you gave sculpt_creature".
            // `target` and `source` came from the run where ONE malformed sculpt_creature took the
            // whole wish down with it: with no creature, inspect_creature, apply_effect,
            // author_datapack x2, verify_datapack, run_command and add_template then failed in
            // sequence -- and it still reported DONE.
            //
            // Safe by the same rule as everything else here: a tool that means something different
            // by one of these words DECLARES it, and the alias never fires there.
            "tag", List.of("id", "name", "creature", "target"),
            "cbscript", List.of("source", "script", "code", "cbs"),
            "name", List.of("display_name", "title", "label"),
            "any", List.of("terms", "keywords", "words", "triggers"),
            "verdict", List.of("state", "outcome", "status"),
            "say", List.of("message", "text", "reply"),
            "note", List.of("description", "why", "reason"));

    /**
     * Fill any declared-but-absent property from a near-miss key. Returns the names rewritten, in
     * {@code sent->wanted} form, for logging; empty when nothing was touched.
     */
    public static List<String> normalise(JsonObject schema, JsonObject args) {
        if (schema == null || args == null || !schema.has("properties")) {
            return List.of();
        }
        JsonObject props = schema.getAsJsonObject("properties");
        List<String> rewrites = new java.util.ArrayList<>();
        for (String wanted : props.keySet()) {
            if (args.has(wanted)) {
                continue;
            }
            String sent = findSource(props, args, wanted);
            if (sent != null) {
                args.add(wanted, args.get(sent));
                args.remove(sent);
                rewrites.add(sent + "->" + wanted);
            }
        }
        return rewrites;
    }

    /** A key present in the args, absent from the schema, and plausibly meaning {@code wanted}. */
    private static String findSource(JsonObject props, JsonObject args, String wanted) {
        for (String candidate : candidates(wanted)) {
            // Never take a key the schema declares: that key has its own meaning here, and moving
            // it would be inventing an argument rather than repairing a typo.
            if (args.has(candidate) && !props.has(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static List<String> candidates(String wanted) {
        List<String> out = new java.util.ArrayList<>(plurals(wanted));
        out.addAll(SYNONYMS.getOrDefault(wanted.toLowerCase(Locale.ROOT), List.of()));
        return out;
    }

    /** English-enough for identifiers: {@code command↔commands}, {@code part↔parts}. */
    private static List<String> plurals(String w) {
        if (w.endsWith("s")) {
            return List.of(w.substring(0, w.length() - 1));
        }
        return List.of(w + "s");
    }
}
