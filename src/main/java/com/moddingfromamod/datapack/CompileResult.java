package com.moddingfromamod.datapack;

import java.nio.file.Path;
import java.util.List;

/**
 * The outcome of compiling one CBScript source into a datapack.
 *
 * <p>Deliberately shaped for an AI author to act on: on failure {@link #error} is the single most
 * useful line and {@link #log} is the full compiler output, so the agent can read the mistake and
 * try again. This is the whole point of the iterate-on-error loop — a compile error is a normal,
 * recoverable step, not a dead end.
 */
public record CompileResult(boolean ok, String namespace, Path zip, String error, List<String> log) {

    public static CompileResult ok(String namespace, Path zip, List<String> log) {
        return new CompileResult(true, namespace, zip, null, log);
    }

    public static CompileResult failure(String namespace, String error, List<String> log) {
        return new CompileResult(false, namespace, null, error, log);
    }

    /** A compact, agent-readable summary of a failure (error line + the tail of the log). */
    public String failureSummary() {
        StringBuilder sb = new StringBuilder();
        if (error != null && !error.isBlank()) {
            sb.append(error.strip());
        }
        if (log != null && !log.isEmpty()) {
            // The last few lines usually name the offending construct.
            int from = Math.max(0, log.size() - 12);
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(String.join("\n", log.subList(from, log.size())));
        }
        return sb.length() == 0 ? "Compile failed (no detail)." : sb.toString();
    }
}
