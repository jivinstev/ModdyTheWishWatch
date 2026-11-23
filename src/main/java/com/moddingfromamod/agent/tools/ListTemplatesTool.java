package com.moddingfromamod.agent.tools;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.wish.LearnedTemplates;
import com.moddingfromamod.wish.WishCatalog;

/**
 * What the watch already knows how to do on its own.
 *
 * <p><b>Why this exists.</b> The agent's best output is a <i>template</i>: teach the watch once and
 * the same wish is instant and offline forever after. But it had no way to read back what it had
 * already taught, which caused two quiet failures — it re-taught wishes the watch could already
 * grant (piling up near-duplicate templates that then compete to match), and it rebuilt things from
 * scratch that it could simply have re-run by id.
 *
 * <p>It also matters now that the watch escalates far more readily. Since the cache only answers
 * when it is <em>confident</em>, a wish can arrive here that a perfectly good template would have
 * handled if only it had been phrased differently. Seeing the list lets the agent notice that,
 * grant it by running the existing template, and — if the child's phrasing deserves to work — widen
 * that template's terms rather than adding a rival one.
 */
public final class ListTemplatesTool implements McpTool {

    @Override
    public String name() {
        return "list_templates";
    }

    @Override
    public String description() {
        return "List the wishes the watch can already grant instantly by itself: the "
                + "grown-ups' library and the learned ones. Check here before teaching anything: "
                + "if one of these is already the right answer, just run it (or widen its terms with add_template using the SAME "
                + "id) instead of teaching a near-duplicate that will compete with it.";
    }

    @Override
    public JsonObject inputSchema() {
        return Schemas.object();
    }

    @Override
    public ToolResult call(JsonObject args) {
        try {
            return ToolResult.ok(AgentBridge.onServer(() -> {
                StringBuilder sb = new StringBuilder();
                sb.append("The watch grants these instantly, with no help from you:\n");
                for (String id : WishCatalog.templates().ids()) {
                    // TWO sources since the built-ins were deleted, and the agent is told
                    // which: a library recipe is the operator's curated answer and re-teaching its id
                    // would be overridden on the next reload.
                    String source = com.moddingfromamod.library.WishLibrary.snapshot().hasTemplate(id)
                            ? "  (library — the grown-ups' curated set; do not overwrite this id)"
                            : "  (learned)";
                    sb.append("- ").append(id).append(source).append('\n');
                }
                sb.append("\nThe big buttons on the watch right now:\n");
                for (WishCatalog.Preset p : com.moddingfromamod.wish.WishPresets.forScreen(
                        com.moddingfromamod.library.WishLibrary.asPresets(),
                        LearnedTemplates.asPresets())) {
                    sb.append("- \"").append(p.label()).append("\" → wishes: \"")
                      .append(p.wishText()).append("\"\n");
                }
                return sb.toString();
            }));
        } catch (AgentBridge.Unavailable e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
