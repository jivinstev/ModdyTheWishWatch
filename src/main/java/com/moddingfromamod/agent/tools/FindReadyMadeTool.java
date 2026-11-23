package com.moddingfromamod.agent.tools;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.readymade.ReadyMadeAnswers;
import com.moddingfromamod.readymade.ReadyMadeConfig;
import com.moddingfromamod.readymade.ReadyMadeHouse;

/**
 * <b>The pull half of the matrix: the worker searches the ready-made library itself.</b>
 * Registered only when {@code readymade.search} names a kind, and it searches only those kinds, so
 * an arm's tool list says exactly which cells it is measuring.
 */
public final class FindReadyMadeTool implements McpTool {

    @Override
    public String name() {
        return "find_ready_made";
    }

    @Override
    public String description() {
        return "Search the READY-MADE library before you build something yourself: finished "
                + "creatures to start from, ready-made abilities, ready-made item effects, and what "
                + "this house already made. Each answer names the exact call that uses it. Search by "
                + "what it must DO, in his own words ('makes mobs fight', 'breathes ice', 'a snail'). "
                + "A match is an option, not an order: use it only if it really does what he asked.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.object();
        Schemas.prop(s, "need", "string", "What it has to do or be, in the child's words.");
        Schemas.prop(s, "kind", "string", ReadyMadeAnswers.KIND_HELP);
        return Schemas.required(s, "need");
    }

    @Override
    public ToolResult call(JsonObject args) {
        // The SAME Haiku matcher the nudges use, over the whole library, reading the search beside
        // the wish — never keywords alone (a keyword search cannot see a tiger in "a cat with orange
        // stripes"). Keywords answer only when no model could, and the reply says so.
        var index = ReadyMadeHouse.index();
        var enabled = ReadyMadeConfig.searches();
        String need = str(args, "need");
        String kind = str(args, "kind");
        var run = com.moddingfromamod.agent.AgentContext.currentRun();
        var semantic = need.isBlank() ? null : com.moddingfromamod.readymade.ReadyMadeNudge.search(
                com.moddingfromamod.readymade.ReadyMadeLive.transport(), index,
                ReadyMadeAnswers.scope(enabled, kind), run == null ? "" : run.text(), need);
        return ToolResult.ok(ReadyMadeAnswers.find(index, enabled, need, kind, semantic));
    }

    static String str(JsonObject a, String k) {
        return a != null && a.has(k) && a.get(k).isJsonPrimitive() ? a.get(k).getAsString() : "";
    }
}
