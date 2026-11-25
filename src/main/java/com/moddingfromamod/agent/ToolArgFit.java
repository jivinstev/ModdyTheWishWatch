package com.moddingfromamod.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.moddingfromamod.wishbuilder.MakeFacts;

/**
 * <b>The Magician's tool arguments, fitted to this world's limits</b> (#69, stage 3 of #14). Each argument a tool takes
 * that maps to a make fact ({@link #MAPPED}) and is over this world's limit is fitted, and the agent is told in a short
 * note the CORE writes from the fact and the limit alone, never from anything a world wrote, so it can tell the player
 * in its own words (which the text sweep reads as it reads everything it says):
 * <ul>
 *   <li>a fact the core fits on the LIVE creature ({@code MakeFit.LIVE}, and a creature's scale, fitted at every spawn)
 *       is NOT lowered in the arguments: the creature is made with what was asked and held to the limit where it
 *       stands, so its record keeps its own numbers and a later round that raises the limit lets it out ("health is held
 *       to 40 where it stands: this world's limit"). Lowering it here would write this world's limit into the
 *       species for good;</li>
 *   <li>any other mapped fact is set to the limit in the arguments ("… was set to …: this world's limit").</li>
 * </ul> An argument no fact measures (a custom datapack power) is kept as it is: the
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
        m.put("sculpt_creature", java.util.Collections.unmodifiableMap(creature));
        Map<String, String> species = new LinkedHashMap<>(creature);
        species.put("count", MakeFacts.COUNT_MADE);   // how many spawn at once: no live fit, set to the limit
        m.put("define_species", java.util.Collections.unmodifiableMap(species));
        m.put("stage_event", Map.of("count", MakeFacts.COUNT_MADE));
        m.put("give_items", Map.of("count", MakeFacts.AMOUNT_ITEMS));
        m.put("make_item", Map.of("count", MakeFacts.AMOUNT_ITEMS));
        return java.util.Collections.unmodifiableMap(m);
    }

    /**
     * One argument fitted: which, from what, to what (the limit), under which fact, and whether it is held where the
     * creature stands ({@code live}: the argument keeps what was asked) or was set to the limit in the arguments.
     */
    public record Lowered(String arg, String fact, double from, double to, boolean live) {
        /** The core's note to the agent: from the argument and the limit alone. */
        public String note() {
            return live ? arg + " is held to " + number(to) + " where it stands: this world's limit"
                    : arg + " was set to " + number(to) + ": this world's limit";
        }
    }

    /** Facts the core fits where a creature stands, at every spawn: never lowered in a tool's arguments. */
    static final java.util.Set<String> HELD_WHERE_IT_STANDS = held();

    private static java.util.Set<String> held() {
        java.util.Set<String> s = new java.util.HashSet<>(com.moddingfromamod.wishbuilder.MakeFit.LIVE);
        s.add(MakeFacts.SCALE_TIMES);   // LiveFit.drawnScale holds any creature to a scaleTimes limit at every spawn
        return java.util.Set.copyOf(s);
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
                boolean live = HELD_WHERE_IT_STANDS.contains(e.getValue());
                if (!live) {
                    args.addProperty(e.getKey(), most);
                }
                out.add(new Lowered(e.getKey(), e.getValue(), asked, most, live));
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
