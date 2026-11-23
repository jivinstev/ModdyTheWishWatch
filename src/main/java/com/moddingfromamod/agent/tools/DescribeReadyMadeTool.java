package com.moddingfromamod.agent.tools;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.readymade.ReadyMadeAnswers;
import com.moddingfromamod.readymade.ReadyMadeConfig;
import com.moddingfromamod.readymade.ReadyMadeHouse;

/**
 * One ready-made thing in full: the parameters and their "from the wish" hints that, with
 * the search arm on, no longer ride in every turn's {@code make_item} and {@code apply_effect}.
 */
public final class DescribeReadyMadeTool implements McpTool {

    @Override
    public String name() {
        return "describe_ready_made";
    }

    @Override
    public String description() {
        return "Show one ready-made thing in full — what it does, every parameter, and what to read "
                + "off his words for each — before you use it.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.object();
        Schemas.prop(s, "id", "string", "Its id, e.g. 'aggro', 'breath_cone', 'pet_dragon'.");
        Schemas.prop(s, "kind", "string", ReadyMadeAnswers.KIND_HELP);
        return Schemas.required(s, "id");
    }

    @Override
    public ToolResult call(JsonObject args) {
        return ToolResult.ok(ReadyMadeAnswers.describe(ReadyMadeHouse.index(),
                ReadyMadeConfig.searches(), FindReadyMadeTool.str(args, "id"),
                FindReadyMadeTool.str(args, "kind")));
    }
}
