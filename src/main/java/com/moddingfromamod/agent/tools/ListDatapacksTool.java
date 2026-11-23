package com.moddingfromamod.agent.tools;

import java.util.List;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.datapack.DatapackReloader;
import com.moddingfromamod.datapack.DatapackStore;

/**
 * Lists the CBScript datapacks that already exist, so the agent extends or reuses one instead of
 * authoring a competing near-duplicate — the same "see what you already made" discipline as
 * list_builds / list_templates.
 */
public final class ListDatapacksTool implements McpTool {

    @Override
    public String name() {
        return "list_datapacks";
    }

    /**
     * How much script to hand back. Generous — a wish datapack is a few kilobytes — but bounded, so
     * one enormous feature cannot crowd out the rest of the turn.
     */
    private static final int MAX_SOURCE_CHARS = 12_000;

    @Override
    public String description() {
        return "List the custom CBScript datapacks already built (and whether each is loaded in the "
                + "open world), or pass feature_id to READ THE SCRIPT one is currently running. "
                + "Read it before changing a feature: `cbscript` is the WHOLE program, not a patch, "
                + "so to add something you send everything it already does plus the new part. "
                + "Re-authoring the same feature_id replaces that pack in place; a NEW id leaves the "
                + "old one loaded and firing as well, and nothing in the game shows the child there "
                + "are now two rules running.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.object();
        Schemas.prop(s, "feature_id", "string",
                "Optional. The feature to read — returns the CBScript it currently runs, which is "
                        + "what you must start from to change it.");
        return s;
    }

    @Override
    public ToolResult call(JsonObject args) {
        String want = args.has("feature_id") && !args.get("feature_id").isJsonNull()
                ? args.get("feature_id").getAsString().trim() : null;
        if (want != null && !want.isEmpty()) {
            return readOne(want);
        }
        List<String> ids = DatapackStore.listIds();
        if (ids.isEmpty()) {
            // A FIRST datapack is the one with no prior art to copy, so the lessons are the
            // only thing between it and a mistake already paid for. Same argument as
            // ListTrainsTool's empty case.
            return ToolResult.ok("No custom datapacks have been built yet."
                    + com.moddingfromamod.wish.WishLessons.appendTo("datapack"));
        }
        StringBuilder sb = new StringBuilder("Custom datapacks (" + ids.size() + "):\n");
        boolean worldOpen = AgentBridge.worldIsOpen();
        for (String id : ids) {
            String status;
            if (!worldOpen) {
                status = "saved";
            } else {
                try {
                    status = AgentBridge.onServer(() ->
                            DatapackReloader.isEnabled(AgentBridge.serverOrNull(), id))
                            ? "loaded" : "saved (not loaded)";
                } catch (AgentBridge.Unavailable e) {
                    status = "saved";
                }
            }
            sb.append("  ").append(id).append(" — ").append(status).append('\n');
        }
        sb.append("\nTo CHANGE one: read it first (list_datapacks with its feature_id), then "
                + "author_datapack with the SAME feature_id and the whole script — what it already "
                + "does plus the new part. Sending only the new part is how a rule he asked for "
                + "last week quietly stops happening.");
        return ToolResult.ok(sb.toString().strip());
    }

    /**
     * <b>The script a feature is actually running.</b>
     *
     * <p>MEASURED 2026-08-19, and it is the whole of this class's R6. Nothing could read a pack's
     * source — no tool called {@link DatapackStore#sourceFor} at all — so "edit the existing
     * feature" was an instruction with nothing behind it, and the only way to obey was to write the
     * program again from memory. On the ladder that is exactly what happened: stranger cells
     * re-authored and the script SHRANK, 2461 to 1378 characters losing the night condition, 2433
     * to 974 losing the explosion, while owner cells (which still had the conversation) grew every
     * rung and kept all four.
     *
     * <p>The same defect buildings had and fixed with {@code inspect_build}, recorded in
     * {@code Surroundings}: an instruction to change a thing, and nothing to change it FROM.
     */
    private ToolResult readOne(String id) {
        java.nio.file.Path src = DatapackStore.sourceFor(id);
        if (src == null || !java.nio.file.Files.isRegularFile(src)) {
            List<String> ids = DatapackStore.listIds();
            return ToolResult.error("There is no feature called '" + id + "'. Built so far: " + ids
                    + ". Do not invent an id — authoring under a new one leaves the old rule "
                    + "running as well, and the child cannot see that there are two.");
        }
        try {
            String text = java.nio.file.Files.readString(src);
            boolean clipped = text.length() > MAX_SOURCE_CHARS;
            if (clipped) {
                text = text.substring(0, MAX_SOURCE_CHARS);
            }
            return ToolResult.ok("Feature '" + id + "' currently runs this CBScript"
                    + (clipped ? " (clipped — it is longer than this)" : "") + ":\n\n" + text
                    + "\n\nThat is the WHOLE program. To change it, call author_datapack with "
                    + "feature_id \"" + id + "\" and a script that still does all of the above, "
                    + "plus whatever is new. Anything you leave out stops happening, and he will "
                    + "not find out until the night it does not rain.");
        } catch (java.io.IOException e) {
            return ToolResult.error("Could not read '" + id + "': " + e.getMessage());
        }
    }
}
