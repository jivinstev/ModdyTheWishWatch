package com.moddingfromamod.safety;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * <b>The agent's own machinery never reaches a child's chat</b>.
 *
 * <p>Live, 2026-11-16 (a real Opus-low grant, filming the how-to-wish film): he asked a question,
 * the agent answered it, and his chat then read <i>"Question answered; nothing built, so no
 * finish_building."</i> — the agent explaining its own tool use to a seven-year-old. A question
 * builds nothing, so there is no {@code finish_building} and no celebration, and on that path
 * {@code finish_wish}'s {@code note} on a {@code done} IS read out; the note's schema had told the
 * agent the opposite. {@link SafetyTextSweep} read that very field, but for unsafe WORDS, which is a
 * different question: nothing asked whether a line was the agent talking to itself.
 *
 * <p>This asks it, on the same table of child-visible fields ({@link SafetyTextSweep#FIELDS}), so a
 * field added there for safety is covered here by the same edit. Two kinds of line count:
 *
 * <ul>
 *   <li><b>one that names a tool</b> — any tool the MCP server serves whose name has an underscore
 *       ({@code finish_building}, {@code plan_wish}, …). A one-word tool name ({@code photograph})
 *       is an ordinary word and is not matched. The names are the REAL registry's
 *       ({@link #know}, called by every {@code McpProtocol}), so a new tool is covered with no
 *       edit here;</li>
 *   <li><b>one that narrates the agent's process</b> — {@link #PROCESS}, a short list of phrases no
 *       line in Moddy's voice has a reason to use ("tool call", "nothing built", "question
 *       answered", "the agent", "MCP").</li>
 * </ul>
 *
 * <p><b>Coerced, never refused</b>, for {@code FinishWishTool}'s reason: it asks the agent to SAY
 * something different, which is always possible, so a refusal could only risk a wish that cannot
 * end. {@link #scrub} blanks the field before the tool runs (a blank {@code note} falls back to the
 * mod's own words; a blank {@code notify_player} message is simply not sent), and {@link #notice}
 * tells the agent, in the result it reads anyway, that the line was NOT shown and why — so it does
 * not believe he read it, and does not do it again. {@code WishFeedback} and
 * {@code NotifyPlayerTool} check again as the backstop, for a line that reaches them another way.
 *
 * <p>Minecraft-free; {@code AgentInternalsTest} pins it, {@code NoInternalsInChatGameTest} drives it
 * through the real dispatch.
 */
public final class AgentInternals {

    private AgentInternals() {
    }

    /**
     * The phrases that describe the agent's own process rather than anything he can see. Kept short
     * on purpose: each one is something a line in Moddy's voice never needs, so a match is never a
     * real sentence about his world.
     */
    static final List<String> PROCESS = List.of(
            "tool call", "tool calls", "tool result", "tool results", "mcp",
            "the agent", "nothing built", "nothing was built", "question answered");

    /** Every tool name any MCP server in this process serves (lower case). */
    private static final Set<String> KNOWN = ConcurrentHashMap.newKeySet();

    /** Learn the tools a server serves. Idempotent; called by every {@code McpProtocol}. */
    public static void know(Collection<String> toolNames) {
        if (toolNames == null) {
            return;
        }
        for (String n : toolNames) {
            if (n != null && n.indexOf('_') > 0) {
                KNOWN.add(n.toLowerCase(Locale.ROOT));
            }
        }
    }

    /** The tool names this check matches (a copy). */
    static Set<String> known() {
        return Set.copyOf(KNOWN);
    }

    /**
     * What makes {@code text} the agent's machinery rather than words for him, or null when it is
     * fine: the tool name or process phrase found, as written in the text's own case-folded form.
     */
    public static String found(String text) {
        return found(text, KNOWN);
    }

    /** {@link #found(String)} against an explicit set of tool names — the pure form for tests. */
    static String found(String text, Collection<String> toolNames) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String low = text.toLowerCase(Locale.ROOT);
        for (String name : toolNames) {
            if (name.indexOf('_') > 0 && wordAt(low, name)) {
                return name;
            }
        }
        for (String phrase : PROCESS) {
            if (wordAt(low, phrase)) {
                return phrase;
            }
        }
        return null;
    }

    /** {@code needle} in {@code low}, not as part of a longer identifier or word. */
    private static boolean wordAt(String low, String needle) {
        Matcher m = PATTERNS.computeIfAbsent(needle, n -> Pattern.compile(
                "(?<![a-z0-9_])" + Pattern.quote(n) + "(?![a-z0-9_])")).matcher(low);
        return m.find();
    }

    private static final java.util.Map<String, Pattern> PATTERNS = new ConcurrentHashMap<>();

    /** One child-visible line that was held back: which field, and what gave it away. */
    public record Held(String field, String marker) {
    }

    /**
     * Blank every child-visible field of this call that names the agent's machinery, IN PLACE, and
     * say which. Commands are left alone (a command is not edited into a different command; what a
     * {@code tellraw} says is the lexicon sweep's, and an edit there would change what runs).
     *
     * @param tool the tool being called
     * @param args its arguments, after {@code ArgAliases.normalise}; modified in place
     * @return what was held back, in field order; empty when nothing was
     */
    public static List<Held> scrub(String tool, JsonObject args) {
        List<Held> held = new ArrayList<>();
        if (tool == null || args == null) {
            return held;
        }
        for (SafetyTextSweep.Field f : SafetyTextSweep.fieldsFor(tool)) {
            if (!args.has(f.arg())) {
                continue;
            }
            JsonElement el = args.get(f.arg());
            switch (f.shape()) {
                case STRING -> {
                    if (el.isJsonPrimitive()) {
                        String hit = found(el.getAsString());
                        if (hit != null) {
                            args.addProperty(f.arg(), "");
                            held.add(new Held(f.label(), hit));
                        }
                    }
                }
                case STRING_ARRAY -> {
                    if (el.isJsonArray()) {
                        JsonArray kept = new JsonArray();
                        for (JsonElement e : el.getAsJsonArray()) {
                            String hit = e != null && e.isJsonPrimitive() ? found(e.getAsString()) : null;
                            if (hit == null) {
                                kept.add(e);
                            } else {
                                held.add(new Held(f.label(), hit));
                            }
                        }
                        args.add(f.arg(), kept);
                    }
                }
                case OBJECT_ARRAY_MEMBER -> {
                    if (el.isJsonArray()) {
                        for (JsonElement e : el.getAsJsonArray()) {
                            if (e != null && e.isJsonObject()) {
                                JsonObject o = e.getAsJsonObject();
                                if (o.has(f.member()) && o.get(f.member()).isJsonPrimitive()) {
                                    String hit = found(o.get(f.member()).getAsString());
                                    if (hit != null) {
                                        o.addProperty(f.member(), "");
                                        held.add(new Held(f.label(), hit));
                                    }
                                }
                            }
                        }
                    }
                }
                case COMMANDS -> {
                    // not edited; see the method note
                }
            }
        }
        return held;
    }

    /**
     * What the agent reads about it, appended to the tool's result. Says the line was NOT shown (so
     * it does not believe he read it), what gave it away, and what to do instead — and not to tell
     * him about any of this.
     */
    public static String notice(String tool, List<Held> held) {
        if (held == null || held.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n⚠ NOT shown to the child: ");
        for (int i = 0; i < held.size(); i++) {
            Held h = held.get(i);
            sb.append(i == 0 ? "" : "; ").append('`').append(h.field()).append("` (it said \"")
                    .append(h.marker()).append("\")");
        }
        sb.append(". Every word of ").append(tool).append("'s child-facing fields is read by a "
                + "7-year-old in his chat, so it must never name one of your tools or describe your "
                + "own steps (what you called, what you did not build, that a question was "
                + "answered). Those lines were left out" + ("finish_wish".equals(tool)
                        ? " and Moddy's own words were used instead"
                        : "") + ". Next time say it to HIM in kid words, or leave the field out — "
                + "and do not mention this to him.");
        return sb.toString();
    }
}
