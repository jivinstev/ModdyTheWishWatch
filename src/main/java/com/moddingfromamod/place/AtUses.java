package com.moddingfromamod.place;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * <b>Which places a tool call put something at</b> — read back out of the action log's
 * {@code tool_call} records, for the placement check that photographs and measures what a real
 * grant actually did. Every form: a tool's own {@code at} argument, and a {@code wishspot <at> run …}
 * or {@code wishmob spawn <id> at <at>} the agent typed in {@code run_command}. Pure: {@code AtUsesTest}.
 */
public final class AtUses {

    private static final Pattern WISHSPOT = Pattern.compile("(?i)\\bwishspot\\s+(\\S+)\\s+run\\b");
    /** {@code wishmob spawn <id> [n] at <at>}: a creature put at a place by command (measured, at-c2). */
    private static final Pattern SPAWN_AT =
            Pattern.compile("(?i)\\bwishmob\\s+spawn\\s+\\S+(?:\\s+\\d+)?\\s+at\\s+([^\\s\"\\\\]+)");
    private static final Pattern AT_FIELD = Pattern.compile("\"at\"\\s*:\\s*\"([^\"]+)\"");

    private AtUses() {
    }

    /** One place a call used: the tool and the {@code at} as written. */
    public record Use(String tool, String at) {
    }

    /** Every {@code at} in one call's arguments (which may be clipped JSON), in order, without repeats. */
    public static List<Use> from(String tool, String argsJson) {
        Set<String> ats = new LinkedHashSet<>();
        String a = argsJson == null ? "" : argsJson;
        try {
            JsonElement el = JsonParser.parseString(a);
            if (el.isJsonObject()) {
                JsonObject o = el.getAsJsonObject();
                if (o.has("at") && o.get("at").isJsonPrimitive() && !o.get("at").getAsString().isBlank()) {
                    ats.add(o.get("at").getAsString().trim());
                }
            }
        } catch (RuntimeException clipped) {
            // A clipped record is not JSON; the field is still there as text.
            Matcher m = AT_FIELD.matcher(a);
            while (m.find()) {
                ats.add(m.group(1).trim());
            }
        }
        Matcher w = WISHSPOT.matcher(a);
        while (w.find()) {
            ats.add(w.group(1).replace("\\\"", "").replace("\"", "").trim());
        }
        Matcher sp = SPAWN_AT.matcher(a);
        while (sp.find()) {
            ats.add(sp.group(1).trim());
        }
        List<Use> out = new ArrayList<>();
        for (String s : ats) {
            if (At.parse(s) != null) {
                out.add(new Use(tool, s));
            }
        }
        return out;
    }
}
