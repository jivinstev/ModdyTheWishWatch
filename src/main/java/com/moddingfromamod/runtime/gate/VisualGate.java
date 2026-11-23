package com.moddingfromamod.runtime.gate;

/**
 * <b>The rubric for "may this check block a wish?", as a type rather than a document.</b>
 *
 * <p>Deciding block-vs-advise one check at a time does not scale and pushes a judgement call onto
 * whoever is reviewing. So the criteria are declared per check, and {@link Severity#BLOCK} is
 * <b>rejected at construction</b> unless every one of them holds. The rubric cannot be skipped,
 * argued with, or quietly forgotten when the next check is added.
 *
 * <h2>The six criteria</h2>
 *
 * <ol>
 *   <li><b>Objective</b> — computable from the artifact alone. No external reference, no taste.
 *       "Spikor is mostly tan" is knowledge about the world; it can inform, it cannot block.</li>
 *   <li><b>Deterministic</b> — same input, same verdict, every time. A flaky gate gets switched
 *       off, and then it protects nothing.</li>
 *   <li><b>Actionable</b> — the message names the exact part or number to change. "It looks wrong"
 *       costs a turn and buys nothing.</li>
 *   <li><b>Proven non-trigger</b> — ships with a test showing the LEGITIMATE case does not fire.
 *       This is the load-bearing one: a check without it is how you get a gate that punishes good
 *       work.</li>
 *   <li><b>Child-visible</b> — the defect is one a seven-year-old would notice and mind. Internal
 *       tidiness is not worth a turn of his attention.</li>
 *   <li><b>Intent-aware</b> — if the check fires on a <i>change</i>, it must distinguish a change
 *       that was ASKED FOR from a side effect. Otherwise "make Spikor all green" gets refused for
 *       doing exactly what the child wanted. A change-check that cannot tell the difference may
 *       only warn.</li>
 * </ol>
 *
 * <p>Criterion 6 exists because the first draft of the revision-regression gate did not have it,
 * and would have blocked a correctly-obeyed wish.
 */
public record VisualGate(String name, Severity severity, String rationale,
                         boolean objective, boolean deterministic, boolean actionable,
                         boolean provenNonTrigger, boolean childVisible, boolean intentAware) {

    public enum Severity {
        /** Refuses ONCE, then yields. Never traps the agent mid-wish. */
        BLOCK,
        /** Surfaced prominently; the agent may proceed. */
        WARN,
        /** Plain facts, no verdict. */
        INFORM
    }

    public VisualGate {
        if (severity == Severity.BLOCK) {
            StringBuilder missing = new StringBuilder();
            if (!objective) {
                missing.append("\n  - NOT OBJECTIVE: it needs knowledge the artifact does not "
                        + "contain, so it is a judgement, and judgements advise");
            }
            if (!deterministic) {
                missing.append("\n  - NOT DETERMINISTIC: a gate that sometimes fires gets disabled");
            }
            if (!actionable) {
                missing.append("\n  - NOT ACTIONABLE: blocking without saying what to change just "
                        + "costs a turn");
            }
            if (!provenNonTrigger) {
                missing.append("\n  - NO PROVEN NON-TRIGGER: ship a test showing the legitimate "
                        + "case does NOT fire, or this will punish good work");
            }
            if (!childVisible) {
                missing.append("\n  - NOT CHILD-VISIBLE: not worth a turn of his attention");
            }
            if (!intentAware) {
                missing.append("\n  - NOT INTENT-AWARE: a change-check that cannot tell a "
                        + "requested change from a side effect would refuse a correctly-obeyed "
                        + "wish (\"make the frog knight all green\")");
            }
            if (missing.length() > 0) {
                throw new IllegalArgumentException("the check '" + name + "' asks to BLOCK but does "
                        + "not meet the rubric:" + missing + "\n\nUse WARN instead, or fix the "
                        + "check. See VisualGate's javadoc.");
            }
        }
    }

    /** A check that meets every criterion. */
    public static VisualGate blocking(String name, String rationale) {
        return new VisualGate(name, Severity.BLOCK, rationale, true, true, true, true, true, true);
    }

    /** A check that is worth surfacing but must not refuse. {@code why} says which criterion fails. */
    public static VisualGate warning(String name, String rationale, String whyNotBlocking) {
        return new VisualGate(name, Severity.WARN, rationale + " (advisory: " + whyNotBlocking + ")",
                false, true, true, false, true, false);
    }

    /** Facts with no verdict. */
    public static VisualGate informational(String name, String rationale) {
        return new VisualGate(name, Severity.INFORM, rationale,
                true, true, false, false, true, false);
    }
}
