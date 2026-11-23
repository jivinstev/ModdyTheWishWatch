package com.moddingfromamod.worker;

import java.util.Map;

/**
 * <b>Every wish worker is offered every wish tool up front</b> — Opus, Spark and Wonder alike
 *.
 *
 * <p>Until then only a REDIRECTED worker (Muse) had tool search off, set in {@code WishModel.applyTo}
 * after CLI 2.1.306 started deferring tools optimistically and Spark looped on {@code ToolSearch}
 * 51–98 times. Opus kept the CLI default: its wish tools deferred behind {@code ToolSearch}, which it
 * called on every wish. The routing arm settled it (40 stratified fixtures ×4,
 * {@code corpus/routing-arms/2026-10-26-t16-exposure/}): with every tool up front Opus routed the same
 * (79% vs 80%, within noise), planned first 99% vs 100%, took 5 turns instead of 7, ~31 s instead of
 * ~40 s, and cost $0.218 instead of $0.288 a run — the schemas sit in the shared cached prefix
 * instead of being loaded per wish. One rule for every model now, set at both launchers.
 */
public final class ToolExposure {

    /** The CLI's own switch; {@code false} lists every MCP tool at boot instead of deferring them. */
    public static final String VAR = "ENABLE_TOOL_SEARCH";

    private ToolExposure() {
    }

    /** Put every wish tool in the worker's tool list from its first turn. */
    public static void applyTo(Map<String, String> env) {
        env.put(VAR, "false");
    }
}
