package com.moddingfromamod.agent;

import java.util.OptionalInt;

/**
 * Which background wish-granter sessions are ours to stop, and when.
 *
 * <p>Pure — no CLI, no sockets, no Minecraft — so the predicate that decides whether to terminate
 * somebody's running agent is unit-testable. It got both of its rules wrong for months precisely
 * because it was three inline booleans nobody could test.
 */
final class ReapRule {

    private ReapRule() {}

    /**
     * Is this one of ours?
     *
     * <p>Accepts the bare name <b>and</b> the numbered form. {@code AgentSession} names its workers
     * {@code AGENT_NAME + "-" + worker} whenever more than one runs, so an exact-equality test — which
     * is what this was — cannot see a granter on any multi-worker session at all. Derived from the
     * base name rather than hardcoded so it cannot drift from the launcher a second time.
     */
    static boolean isOurs(String agentName, String baseName) {
        if (agentName == null || baseName == null) {
            return false;
        }
        if (agentName.equals(baseName)) {
            return true;
        }
        if (!agentName.startsWith(baseName + "-")) {
            return false;
        }
        String suffix = agentName.substring(baseName.length() + 1);
        return !suffix.isEmpty() && suffix.chars().allMatch(Character::isDigit);
    }

    /**
     * Should this session be stopped?
     *
     * <p>{@code done} is the ordinary case. {@code blocked} is the one that stranded four agents, one
     * of them for ten days: when the game dies UNCLEANLY the orphan guard tells the agent to stop, it
     * correctly stops looping, and a session that has stopped looping is simply <i>awaiting input</i>
     * — which the daemon reports as {@code blocked}, not {@code done}. Stopping the loop was never
     * the same thing as ending the session.
     *
     * <p>A blocked session is only reaped when its client is <b>gone</b>. That gate is what makes this
     * safe to run at world load: a granter genuinely waiting on a live game is never touched, however
     * long it waits.
     */
    static boolean shouldStop(String state, boolean clientAlive) {
        if ("done".equals(state)) {
            return true;
        }
        return "blocked".equals(state) && !clientAlive;
    }

    /**
     * The MCP port a session belongs to, read from its working directory ({@code .../session-<port>}).
     *
     * <p>The port IS the client's identity here — the session directory is named after it and the
     * agent's own {@code mcp.json} points at {@code 127.0.0.1:<port>} — so "is the client alive" is
     * answerable by asking whether anything is still listening.
     */
    static OptionalInt portOf(String cwd) {
        if (cwd == null) {
            return OptionalInt.empty();
        }
        int at = cwd.lastIndexOf("session-");
        if (at < 0) {
            return OptionalInt.empty();
        }
        String tail = cwd.substring(at + "session-".length());
        int end = 0;
        while (end < tail.length() && Character.isDigit(tail.charAt(end))) {
            end++;
        }
        if (end == 0 || end != tail.length()) {
            return OptionalInt.empty();   // "session-50223-old" is not a port
        }
        try {
            int port = Integer.parseInt(tail);
            return port > 0 && port <= 65535 ? OptionalInt.of(port) : OptionalInt.empty();
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }
}
