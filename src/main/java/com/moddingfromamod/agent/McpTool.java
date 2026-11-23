package com.moddingfromamod.agent;

import com.google.gson.JsonObject;

/**
 * One capability exposed to the agent.
 *
 * <p>Implementations that touch the world MUST do so inside {@link AgentBridge#onServer} — tool
 * bodies are invoked on an HTTP thread, and touching level/entity state from there corrupts the game.
 */
public interface McpTool {

    /** Tool name as the agent sees it (snake_case, stable — it appears in prompts). */
    String name();

    /** What it does, written for the agent to read. Be specific: this is the whole API doc. */
    String description();

    /** JSON Schema for the arguments object. */
    JsonObject inputSchema();

    /** Run it. Never throw — return {@link ToolResult#error} so the agent can self-correct. */
    ToolResult call(JsonObject args);

    /**
     * The id of the thing the last {@link #call} made, when that id is DERIVED rather than passed.
     *
     * <p>The dispatch seam records what a wish made by reading an argument, which covers every tool
     * whose id the agent chooses. An item's id is computed once from its first name and then kept
     * for life — deliberately, so that renaming it does not silently produce a second item — so
     * there is no argument to read and undo recorded nothing at all.
     *
     * <p>Opt-in on purpose: the default is null, so a tool added later that forgets this simply
     * gets no undo record, which is the safe direction and the same rule {@code MadeKind} follows.
     */
    default String lastMadeId() {
        return null;
    }

    /**
     * What the child will call "it" after this call, when that differs from what undo removes
     * (C3). A staged event's undo record is the running INSTANCE ({@code gold_meteor_1_743});
     * "make it ice instead" means the DESIGN ({@code gold_meteor}), which is what the resolver can
     * find again. Defaults to {@link #lastMadeId()}, so every other tool is unchanged.
     */
    default String lastSubjectId() {
        return lastMadeId();
    }
}
