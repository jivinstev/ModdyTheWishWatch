package com.moddingfromamod.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.moddingfromamod.wishbuilder.MakeFacts;

/**
 * <b>The Magician's tool arguments, fitted to this world's limits</b> (#69, stage 3 of #14). Each argument a tool takes
 * that maps to a make fact ({@link #MAPPED}) is lowered to the world's limit for that fact when it is over, and the
 * agent is told in a short note the CORE writes from the fact and the limit alone ("health was set to 40: this
 * world's limit"), never from anything a world wrote, so it can tell the player in its own words (which the text sweep
 * reads as it reads everything it says). An argument no fact measures (a custom datapack power) is kept as it is: the
 * thing is made, and a world that cannot measure it shows it locked on his watch, never refused here. Runs after the
 * safety layers and the world's rules, so a refused call is never fitted into an allowed one. With no limits, nothing
 * changes. Pure.
 */
public final class ToolArgFit {

    private ToolArgFit() {
    }

    /** Each tool's arguments that a make fact measures: argument name → fact key. */
    public static final Map<String, Map<String, String>> MAPPED = mapped();

    private static Map<String, Map<String, String>> mapped() {
        Map<String, String> creature = new LinkedHashMap<>();
        creature.put("health", MakeFacts.HEALTH_HALF_HEARTS);
        creature.put("damage", MakeFacts.ATTACK_DAMAGE_HALF_HEARTS);
        creature.put("scale", MakeFacts.SCALE_TIMES);
        creature.put("armor", MakeFacts.ARMOR_POINTS);
        creature.put("knockbackResistance", MakeFacts.KNOCKBACK_RESISTANCE_FRACTION);
        Map<String, Map<String, String>> m = new LinkedHashMap<>();
        m.put("sculpt_creature", Map.copyOf(creature));
        return Map.copyOf(m);
    }

    /** One argument lowered: which, from what, to what (the limit), under which fact. */
    public record Lowered(String arg, String fact, double from, double to) {
        /** The core's note to the agent: from the argument and the limit alone. */
        public String note() {
            return arg + " was set to " + number(to) + ": this world's limit";
        }
    }

    /**
     * Fit {@code args} of tool {@code tool} to {@code limits} (fact key → most), in place: each mapped numeric argument
     * over its limit is set to the limit. Returns what was lowered; empty when nothing was (or no limits apply).
     */
    public static List<Lowered> fit(String tool, JsonObject args, Map<String, Double> limits) {
        List<Lowered> out = new ArrayList<>();
        Map<String, String> mapped = tool == null ? null : MAPPED.get(tool);
        if (mapped == null || args == null || limits == null || limits.isEmpty()) {
            return out;
        }
        for (var e : mapped.entrySet()) {
            Double most = limits.get(e.getValue());
            if (most == null || !args.has(e.getKey()) || !args.get(e.getKey()).isJsonPrimitive()
                    || !args.get(e.getKey()).getAsJsonPrimitive().isNumber()) {
                continue;
            }
            double asked = args.get(e.getKey()).getAsDouble();
            if (asked > most + 1e-9) {
                args.addProperty(e.getKey(), most);
                out.add(new Lowered(e.getKey(), e.getValue(), asked, most));
            }
        }
        return out;
    }

    /** The notes for {@code lowered}, as the tool result's last lines; empty when nothing was lowered. */
    public static String notes(List<Lowered> lowered) {
        if (lowered == null || lowered.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\nFITTED TO THIS WORLD'S LIMITS: ");
        for (int i = 0; i < lowered.size(); i++) {
            sb.append(i == 0 ? "" : "; ").append(lowered.get(i).note());
        }
        return sb.append(". Tell the player, in your own words, that this is the most this world allows right now.")
                .toString();
    }

    /**
     * This world's limits as the brief's data line: the fact keys and their numbers only, never a word a world wrote.
     * Empty when there are none. Keys outside the closed vocabulary are left out.
     */
    public static String brief(Map<String, Double> limits) {
        if (limits == null || limits.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String key : MakeFacts.NUMBERS) {
            Double v = limits.get(key);
            if (v != null) {
                sb.append(sb.length() == 0 ? "" : ", ").append(key).append('=').append(number(v));
            }
        }
        if (sb.length() == 0) {
            return "";
        }
        return "THIS WORLD'S LIMITS (numbers only, by make fact): " + sb
                + ". Make the strongest version within them: a tool that is given more sets it to the limit and tells "
                + "you.";
    }

    private static String number(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
