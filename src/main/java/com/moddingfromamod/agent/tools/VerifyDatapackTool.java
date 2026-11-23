package com.moddingfromamod.agent.tools;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.datapack.DatapackStore;
import com.moddingfromamod.datapack.DatapackVerifier;
import com.moddingfromamod.datapack.SelectorTags;

/**
 * Check a datapack you authored is actually alive —.
 *
 * <p>Every other wish class proves itself; this one could not. A pack can compile, load, report
 * success and be silently dead (a bad particle id, a mangled selector, a function Minecraft dropped
 * at parse time), and the only mitigation was a sentence asking the agent to go and check by hand.
 *
 * <p>Datapacks are the escape hatch that mob abilities, throwable items and staged encounters all
 * compile down to, so an unverifiable datapack is an unverifiable wish in three future classes.
 */
public final class VerifyDatapackTool implements McpTool {

    @Override
    public String name() {
        return "verify_datapack";
    }

    @Override
    public String description() {
        return "Check a datapack you authored actually LOADED and its functions are real. Call "
                + "this after author_datapack, BEFORE telling the child it worked — a pack can "
                + "compile and load and still be completely dead, and that failure is invisible "
                + "otherwise. Pass the feature_id, and the entry function names if you know them.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.object();
        Schemas.prop(s, "feature_id", "string", "The id you passed to author_datapack.");
        Schemas.prop(s, "entries", "array",
                "Function names the pack should have defined (without the namespace), e.g. "
                        + "[\"tick\",\"give\"]. Optional but much stronger: without it, a pack that "
                        + "registered NO functions at all still looks loaded.");
        Schemas.prop(s, "run", "boolean",
                "Actually execute the functions to see if they error (default false). They have "
                        + "real effects in the world, so only do this when a dry check passed and "
                        + "you still suspect something.");
        return Schemas.required(s, "feature_id");
    }

    @Override
    public ToolResult call(JsonObject args) {
        String id = args.has("feature_id") ? args.get("feature_id").getAsString() : null;
        if (id == null || id.isBlank()) {
            return ToolResult.error("Which datapack? Pass the feature_id you authored it with.");
        }
        String clean = id.trim().toLowerCase(java.util.Locale.ROOT);
        if (!DatapackStore.exists(clean)) {
            return ToolResult.error("I have no record of a datapack called '" + clean
                    + "'. Use list_datapacks to see what exists.");
        }
        List<String> entries = new ArrayList<>();
        if (args.has("entries") && args.get("entries").isJsonArray()) {
            args.getAsJsonArray("entries").forEach(e -> entries.add(e.getAsString()));
        }
        boolean run = args.has("run") && args.get("run").getAsBoolean();
        try {
            return AgentBridge.onServer(() -> {
                var report = DatapackVerifier.verify(AgentBridge.serverOrNull(), clean, entries, run);
                return ToolResult.ok(report.describe() + deadSelectors(clean)
                        + costlySelectors(clean) + counterfactual(clean) + madeGives(clean));
            }, 20_000L);
        } catch (AgentBridge.Unavailable e) {
            return ToolResult.error(e.getMessage());
        }
    }

    /**
     * <b>Does anything in the world match what this pack is watching for?</b>
     *
     * <p>The gap this closes is the one this tool's own description already gestures at — "a pack
     * can compile and load and still be completely dead" — and then does not check. Loading proves
     * the functions exist and running them proves they do not error, but a selector that matches
     * nothing is not an error: {@code execute as @e[tag=spikor] run …} with no matches is a
     * SUCCESSFUL no-op. So a pack whose every clock watches a tag nothing wears passes every check
     * here and is inert for ever.
     *
     * <p>That is not a hypothetical shape. A whole catalogue of creature abilities was aimed at the
     * bare species id while wish creatures wear {@code wishmob_<species>}, and it took filming a
     * Spikor and counting flame-coloured pixels to notice.
     *
     * <p>Reported rather than failed, for two honest reasons: a pack may legitimately be verified
     * before the thing it targets is summoned, and the tags it introduces itself are subtracted but
     * a selector can still be conditional in ways no static read can settle. The agent is told, in
     * the moment before it tells the child.
     */
    // Package-private, not private: a GameTest on a real server drives these with a real
    // staged pack. See VerifyDatapackAddendaGameTest — before it, NEITHER addendum had any
    // test of its wiring, so both could have been deleted with the whole suite green.
    /**
     * <b>Which of its selectors check every entity in the world each time they run</b> —
     * {@code @e} with no tag and no position, which the tag index cannot answer. Advisory, like
     * {@link #deadSelectors}: only the pack knows whether such a line runs once or every tick.
     */
    static String costlySelectors(String featureId) {
        try {
            java.nio.file.Path src = DatapackStore.sourceFor(featureId);
            if (src == null || !java.nio.file.Files.isRegularFile(src)) {
                return "";
            }
            return com.moddingfromamod.selector.SelectorCost.advice(java.nio.file.Files.readString(src));
        } catch (java.io.IOException | RuntimeException e) {
            return "";
        }
    }

    static String deadSelectors(String featureId) {
        try {
            java.nio.file.Path src = DatapackStore.sourceFor(featureId);
            if (src == null || !java.nio.file.Files.isRegularFile(src)) {
                return "";
            }
            String source = java.nio.file.Files.readString(src);
            java.util.Set<String> candidates =
                    SelectorTags.dead(source, java.util.Set.of());
            if (candidates.isEmpty()) {
                return "";
            }
            java.util.Set<String> alive = WorldTags.worn(candidates);
            List<String> dead = new ArrayList<>(candidates);
            dead.removeAll(alive);
            if (dead.isEmpty()) {
                return "";
            }
            return "\n⚠ SELECTS NOBODY: nothing in the world is wearing " + dead
                    + ", and this pack never puts "
                    + (dead.size() == 1 ? "that tag" : "those tags")
                    + " on anything itself — so whatever watches for "
                    + (dead.size() == 1 ? "it" : "them")
                    + " will never fire. If the creature is already out there, check what it is "
                    + "really tagged with (a wish creature wears 'wishmob_<species>'). If you have "
                    + "not summoned it yet, this is expected.";
        } catch (java.io.IOException | RuntimeException e) {
            return "";   // the verdict above stands on its own; this is an extra, not a gate
        }
    }

    /**
     * <b>Does it ALSO happen the rest of the time?</b>
     *
     * <p>The one defect class in this project that source inspection cannot see, and it sits
     * exactly here. A pack asked for cod "only at night" rained cod at night as asked and again at
     * midday, because a second path summoned whether or not the gated function ran. The script
     * contained a correct time gate, {@code source_has:night} passed, a model grader said yes, and
     * the cell scored 1.000. Measured at <b>one script in five</b>.
     *
     * <p>So the agent is asked to look at the world instead — {@code /time set noon}, watch, then
     * put it back. See {@link com.moddingfromamod.datapack.CounterfactualProbe} for why this is not
     * the static analyser rejects, and for the guidance attempt that failed first.
     *
     * <p>Reported, not failed, for the same reason as the dead-selector warning above: a time gate
     * is not a defect, and nothing here can tell a leaking pack from a sound one. Only the
     * observation can, which is why the observation is what is asked for.
     */
    static String counterfactual(String featureId) {
        try {
            java.nio.file.Path src = DatapackStore.sourceFor(featureId);
            if (src == null || !java.nio.file.Files.isRegularFile(src)) {
                return "";
            }
            return com.moddingfromamod.datapack.CounterfactualProbe.warning(
                    java.nio.file.Files.readString(src));
        } catch (java.io.IOException | RuntimeException e) {
            return "";
        }
    }

    /**
     * the same finding author_datapack refuses once, said again here, because a
     * pack authored with the look-alike on purpose (the second call) still hands out a thing that
     * only clicks — and verify is where "is this pack what he asked for" is answered.
     */
    private static String madeGives(String id) {
        try {
            java.nio.file.Path p = DatapackStore.sourceFor(id);
            if (p == null || !java.nio.file.Files.isRegularFile(p)) {
                return "";
            }
            var found = com.moddingfromamod.datapack.DatapackItemGives.scan(java.nio.file.Files.readString(p));
            return found.isEmpty() ? "" : "\n⚠ " + com.moddingfromamod.datapack.DatapackItemGives.refusal(found);
        } catch (java.io.IOException e) {
            return "";
        }
    }
}
