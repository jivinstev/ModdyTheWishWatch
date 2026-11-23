package com.moddingfromamod.agent.tools;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.datapack.CbScriptLessons;
import com.moddingfromamod.datapack.CbScriptPrimer;

/**
 * Returns the full CBScript reference + a worked example. CBScript is niche enough that the model
 * benefits from pulling the syntax on demand before (or while) using {@code author_datapack}, rather
 * than relying on training data it doesn't have.
 */
public final class CbScriptHelpTool implements McpTool {

    @Override
    public String name() {
        return "cbscript_help";
    }

    @Override
    public String description() {
        return "Get the full CBScript syntax reference plus a complete worked example (and the "
                + "display-entity recipe for moving/deformable 3D models). Read this before writing "
                + "an author_datapack script, or when a compile error confuses you.";
    }

    @Override
    public JsonObject inputSchema() {
        return Schemas.object();
    }

    @Override
    public ToolResult call(JsonObject args) {
        // Base reference (static) + the distilled lessons mined from real compile failures
        // (data, updated by the outer loop without rebuilding the mod). The lessons come LAST so
        // the freshest hard-won rules are the last thing the model reads before writing.
        String lessons = CbScriptLessons.text();
        if (lessons.isEmpty()) {
            return ToolResult.ok(CbScriptPrimer.FULL);
        }
        return ToolResult.ok(CbScriptPrimer.FULL
                + "\n\n══════════════════════════════════════════════════════════════════════\n"
                + "LESSONS FROM REAL FAILURES (avoid these — they cost compile loops):\n\n"
                + lessons);
    }
}
