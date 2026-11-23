package com.moddingfromamod.marshal;

import java.util.Set;

/**
 * <b>Does a stop actually stop?</b> — the refusal at the one seam every tool call passes through.
 *
 * <p>Stopping the worker is cheap precisely because there is a single point: {@code McpProtocol}
 * already wraps every tool for the narrator and the action log, so one check there covers all
 * forty-four tools now and every tool added later.
 *
 * <p><b>It refuses; it does not merely inform.</b> An agent that reads "this was cancelled" and
 * keeps calling tools is invisible except as blocks appearing after the bar went away, which is the
 * worst shape of bug this design can produce: the child pressed stop, was told it stopped, and it
 * did not. So the tool body never runs.
 *
 * <p>Inference cannot be interrupted, so the worst-case wind-down is one model turn (~4s median).
 * That is fine, because the visible stop and the actual stop are deliberately decoupled — the bar
 * and beam are gone within a tick, server-side, with no agent involved.
 *
 * <p>Pure and Minecraft-free: the marshal is handed in, so the whole refusal policy is a unit test.
 */
public final class CancelGate {

    /**
     * The only tool still allowed after a stop.
     *
     * <p>Deliberately just this one. Anything else — even something that looks harmless like
     * {@code list_wishes} — is the agent continuing to work on a wish the child ended, and the way
     * back to being useful is to park and wait for the next one. A generous allow-list is how "stop"
     * quietly becomes "slow down".
     */
    private static final Set<String> ALLOWED_AFTER_STOP = Set.of("await_wish");

    /**
     * What a worker may still call once its wish is FINISHED and it has not yet asked for the next
     * one — {@code await_wish}, and look-ups that neither change his world nor speak to him.
     *
     * <p>An allow-list rather than a list of the tools that speak, because the defect it closes is
     * a SECOND ENDING: on the live server a finished "make it daytime forever" re-planned, re-built
     * and was announced granted a second time, and the second cycle also changed the world under a
     * wish that had already been recorded, released its leases and closed its undo. Every tool that
     * could do either is refused, including ones added later; a read is harmless and refusing it
     * would only buy a confused agent.
     */
    private static final Set<String> ALLOWED_AFTER_FINISH = Set.of(
            "await_wish", "find_content", "cbscript_help", "look_up_character");

    private static final java.util.List<String> READ_ONLY_PREFIXES =
            java.util.List.of("list_", "inspect_");

    /** True for a tool a worker may call after its wish is finished — see ALLOWED_AFTER_FINISH. */
    public static boolean allowedAfterFinish(String tool) {
        if (tool == null) {
            return false;
        }
        if (ALLOWED_AFTER_FINISH.contains(tool)) {
            return true;
        }
        for (String prefix : READ_ONLY_PREFIXES) {
            if (tool.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private CancelGate() {
    }

    /**
     * What to tell the worker instead of running its tool, or null to let the call through.
     *
     * @param marshal  who is granting what
     * @param workerId the calling agent's MCP session id
     * @param tool     the tool it is trying to call
     */
    public static String refusalFor(Marshal marshal, String workerId, String tool) {
        if (marshal == null || workerId == null || workerId.isEmpty()) {
            return null;
        }
        WishRun stopped = marshal.windingDown(workerId);
        if (stopped == null) {
            // ── ONE WISH, ONE ENDING. Not a stop, but the same shape: the work is over and
            // the worker has not noticed. It is refused rather than informed for the reason the
            // class note gives — an agent told "that wish is finished" that goes on to call
            // finish_building is exactly how a child heard "granted" twice.
            WishRun finished = marshal.finishedFor(workerId);
            if (finished == null || allowedAfterFinish(tool)) {
                return null;
            }
            return finishedMessage(finished, tool);
        }
        if (tool != null && ALLOWED_AFTER_STOP.contains(tool)) {
            marshal.woundDown(workerId);
            return null;
        }
        return message(stopped);
    }

    /**
     * The refusal after an ordinary finish. Written for the agent. It names the ONE way
     * forward and says why there is no other, so the cheapest response is the right one and there
     * is nothing to retry: a second finish is refused however it is phrased.
     */
    public static String finishedMessage(WishRun finished, String tool) {
        return "Wish " + (finished == null ? "unknown" : finished.shortId())
                + " is already FINISHED — you called finish_wish for it, and the child has been "
                + "told how it went. This " + (tool == null ? "tool" : tool)
                + " call was REFUSED and did not run: nothing was built or changed, and nothing "
                + "was said to him.\n"
                + "A wish ends once. Do not re-plan, rebuild or re-announce it — if it needs "
                + "changing, he will make a new wish, and that arrives through await_wish. Call "
                + "await_wish now; it is the only tool that moves you on.";
    }

    /** The refusal text. Written for the agent, not for the child — it is a tool result. */
    public static String message(WishRun stopped) {
        StringBuilder sb = new StringBuilder();
        sb.append("STOP — the child cancelled this wish (")
          .append(stopped == null ? "unknown" : stopped.shortId())
          .append("). This tool call was REFUSED and did not run.\n");
        if (stopped != null && stopped.cancelMode() == CancelMode.REDIRECT) {
            sb.append("He did not want it abandoned — he wanted it aimed at something else");
            if (!stopped.redirectTo().isEmpty()) {
                sb.append(": \"").append(stopped.redirectTo()).append('"');
            }
            sb.append(". The re-aimed wish is already waiting for you.\n");
        }
        if (stopped != null && !stopped.cancelNote().isEmpty()) {
            sb.append("He said: \"").append(stopped.cancelNote()).append("\"\n");
        }
        sb.append("Do NOT do any more work on it, do not tidy up, do not explain. ")
          .append("Call await_wish immediately — that is the only tool that will answer you now.");
        return sb.toString();
    }
}
