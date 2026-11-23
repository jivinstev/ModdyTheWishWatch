package com.moddingfromamod.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The set of tools offered to the agent. Order is stable so {@code tools/list} reads predictably. */
public final class ToolRegistry {

    private final Map<String, McpTool> tools = new LinkedHashMap<>();

    /**
     * Register a tool only under a condition — the seam an A/B arm needs.
     *
     * <p>Exists so the effect catalogue can be switched OFF for a control run. Comparing "with the
     * catalogue" against "with the catalogue" is the easiest way to measure nothing and believe
     * something, and without a real off-switch that is exactly the experiment you get.
     */
    public ToolRegistry addIf(boolean condition, McpTool tool) {
        return condition ? add(tool) : this;
    }

    public ToolRegistry add(McpTool tool) {
        tools.put(tool.name(), tool);
        return this;
    }

    public List<McpTool> all() {
        return new ArrayList<>(tools.values());
    }

    public McpTool byName(String name) {
        return name == null ? null : tools.get(name);
    }

    public int size() {
        return tools.size();
    }
}
