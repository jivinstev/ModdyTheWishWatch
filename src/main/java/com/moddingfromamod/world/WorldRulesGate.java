package com.moddingfromamod.world;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * <b>What happens to a wish at pickup, given the world-rules grade</b> — pure and total,
 * so every row of the locked design is a Gate A test ({@code WorldRulesGateTest}) and
 * {@code AwaitWishTool} only carries the decision out.
 *
 * <pre>
 *   the world denies nothing                     → PASS, and the grader was never asked
 *   ALLOWED                                      → PASS
 *   UNSURE, or the grader unavailable            → UNSURE: granted, with the world's rules in the brief
 *   WORLD_WIDE, on an effect this world denies   → REFUSE at pickup, before a worker is spent
 *   MIXED, with a usable allowed part            → SPLIT: that part is granted, the rest explained
 *   HYPERBOLE, on an effect this world denies    → CLAMP: granted on his base, and he is told
 *   any verdict naming only effects allowed here → PASS
 * </pre>
 *
 * A refusal needs a DENIED effect, so a grader that answers {@code world_wide} with no effect, or
 * with only an effect the rules file allows, refuses nothing — the file, not the model, says what is
 * forbidden. A refusal never costs anything: it happens before any worker is assigned and before
 * any budget exists to charge.
 */
public final class WorldRulesGate {

    private WorldRulesGate() {
    }

    /** What to do. */
    public enum Act { PASS, UNSURE, REFUSE, SPLIT, CLAMP }

    /**
     * @param act          what happens
     * @param rule         the rule it is about (the first denied effect's), or null
     * @param denied       the effects the wish needs that this world denies
     * @param runText      what the agent is handed (the allowed part on a SPLIT, else the wish)
     * @param childMessage what the child reads now, or empty
     * @param briefNote    what the agent reads about THIS wish, or empty
     * @param verdict      the grade it was decided from
     */
    public record Decision(Act act, WorldRule rule, Set<WorldEffect> denied, String runText, String childMessage,
                           String briefNote, WorldRulesVerdict verdict) {

        public boolean refuses() {
            return act == Act.REFUSE;
        }

        /** The log's word: {@code REFUSE}, {@code SPLIT}, … */
        public String logAction() {
            return act.name();
        }
    }

    /** Nothing to decide (a world with no rules, or grading off). */
    public static Decision pass(String wishText) {
        return new Decision(Act.PASS, null, Set.of(), wishText, "", "", WorldRulesVerdict.unavailable());
    }

    /**
     * @param v                the grade
     * @param rules            the world's rules in force
     * @param wishText         the text the agent would otherwise get
     * @param safetyRedirected the safety layer already replaced the child's words: then a SPLIT would
     *                         substitute a part of the ORIGINAL for the redirect he was just shown,
     *                         so it becomes UNSURE instead
     * @param sweepOk          true when a line a child will read passes the text sweep
     */
    public static Decision decide(WorldRulesVerdict v, WorldRules rules, String wishText,
                                  boolean safetyRedirected, Predicate<String> sweepOk) {
        if (rules == null || !rules.deniesAnything()) {
            return pass(wishText);
        }
        WorldRulesVerdict g = v == null ? WorldRulesVerdict.unavailable() : v;
        Predicate<String> ok = sweepOk == null ? s -> true : sweepOk;
        Set<WorldEffect> denied = EnumSet.noneOf(WorldEffect.class);
        // HYPERBOLE MEANS "IT ONLY SOUNDS BIGGER THAN HIS BASE": measured, the model often names no
        // effect with it ("all leaves glow at night" → hyperbole, []), and a clamp that needed one
        // would silently become a plain pass with no word to the child. So none named = outside his base.
        Set<WorldEffect> asked = g.kind() == WorldRulesVerdict.Kind.HYPERBOLE && g.effects().isEmpty()
                ? EnumSet.of(WorldEffect.OUTSIDE_OWN_BASE) : g.effects();
        for (WorldEffect e : asked) {
            if (rules.denies(e)) {
                denied.add(e);
            }
        }
        WorldRule rule = null;
        for (WorldEffect e : WorldEffect.values()) {
            if (denied.contains(e)) {
                rule = rules.ruleFor(e);
                break;
            }
        }
        String suggestion = !g.suggestion().isEmpty() && ok.test(g.suggestion()) ? g.suggestion() : "";
        switch (g.kind()) {
            case ALLOWED -> {
                return new Decision(Act.PASS, null, Set.of(), wishText, "", "", g);
            }
            case WORLD_WIDE -> {
                if (denied.isEmpty()) {
                    return new Decision(Act.PASS, null, Set.of(), wishText, "", "", g);
                }
                return new Decision(Act.REFUSE, rule, denied, wishText,
                        WorldRulesWords.refusal(rule, rules, suggestion), "", g);
            }
            case MIXED -> {
                if (denied.isEmpty()) {
                    return new Decision(Act.PASS, null, Set.of(), wishText, "", "", g);
                }
                String part = g.allowedPart();
                if (safetyRedirected || part.isEmpty() || !ok.test(part)) {
                    return unsure(g, rules, wishText, denied, rule);
                }
                return new Decision(Act.SPLIT, rule, denied, part,
                        WorldRulesWords.split(part, rule, rules),
                        WorldRulesWords.splitNote(part, denied), g);
            }
            case HYPERBOLE -> {
                if (denied.isEmpty()) {
                    return new Decision(Act.PASS, null, Set.of(), wishText, "", "", g);
                }
                return new Decision(Act.CLAMP, rule, denied, wishText,
                        WorldRulesWords.clamp(rule, rules), WorldRulesWords.clampNote(denied), g);
            }
            default -> {
                return unsure(g, rules, wishText, denied, rule);
            }
        }
    }

    private static Decision unsure(WorldRulesVerdict g, WorldRules rules, String wishText,
                                   Set<WorldEffect> denied, WorldRule rule) {
        return new Decision(Act.UNSURE, rule, denied, wishText, "", WorldRulesWords.unsureNote(denied), g);
    }

    /**
     * The world's rules as the agent reads them on every wish here (a rules world's brief): what is
     * refused, the file's own brief lines, and a steer to base-local tools. Empty when the world
     * denies nothing.
     */
    public static String brief(WorldRules rules) {
        if (rules == null || !rules.deniesAnything()) {
            return "";
        }
        List<String> denied = new ArrayList<>();
        for (WorldEffect e : rules.denied()) {
            denied.add(e.words());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("THIS WORLD'S RULES").append(rules.world().isEmpty() ? "" : " (" + rules.world() + ")")
          .append(": a wish here may not change ").append(String.join(", ", denied))
          .append(". Grant every wish ON HIS OWN BASE with tools that place things there (build_structure, "
                  + "sculpt_creature, make_item, give_items, paint_surface, stage_event, "
                  + "apply_effect on his own creature). World-wide tools (an unscoped author_datapack or set_rule, "
                  + "change_drops) and time/weather/gamerule commands are refused here — "
                  + "do not reach for them. If a wish truly needs the whole world, tell him it works in ")
          .append(rules.workshop()).append('.');
        if (!rules.allowedScopes().isEmpty()) {
            sb.append(" A standing rule is fine ONLY when scoped to ")
              .append(String.join(" or ", rules.allowedScopes().stream()
                      .map(s -> "own_base".equals(s) ? "his own base" : "his own pets").toList()))
              .append(" (tag his creatures and act on the tag; bound any command by distance).");
            if (rules.baseReach()) {
                // scoped packs and rules are granted — say how, so the first draft is the scoped one.
                sb.append(" So author_datapack and set_rule DO work here when SCOPED: in a datapack every place must be on "
                        + "his base (absolute coordinates inside it, or `execute positioned <inside> run … @e[distance=..N]`) "
                        + "or on his own creatures (@e[tag=<his creature's tag>]); never a bare @a/@e, never another base, "
                        + "never anything that keeps hurting him. A set_rule needs where: \"his_island\", or who set to one "
                        + "of his creatures' tags. The pack is checked when it compiles and refused with the line to fix.");
                StandalonePacks.Lifetime l = StandalonePacks.of(rules);
                if (l != null) {
                    sb.append(" A standalone pack or rule here lasts ").append(l.millis() / 86_400_000L)
                      .append(" days").append(l.renewByPlay() ? ", renewed whenever he plays on his base" : "")
                      .append("; tell him so when you grant one.");
                }
            }
        }
        for (String line : rules.brief()) {
            sb.append("\n- ").append(line);
        }
        return sb.toString();
    }
}
