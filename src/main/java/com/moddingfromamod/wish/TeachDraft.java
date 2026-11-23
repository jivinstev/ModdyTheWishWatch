package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * <b>An {@code add_template} draft, filled in from what a command-granted wish actually ran</b>
 *. Pure, so the folding rule is a unit test.
 *
 * <h2>Why</h2>
 *
 * <p>Measured on a live server (2026-09-24 → 10-02): 38% of granted wishes could not be saved, 62% in one
 * play session — and about half of those were granted with commands a template accepts (night
 * vision, a torch beside him, an army of zombies, 100 creepers). The agent simply never called
 * {@code add_template}: creatures, machines and trains each get a "THEN TEACH IT" rule, command
 * wishes got "if you can". So {@code finish_wish} refuses such a wish ONCE and hands back this draft;
 * the agent supplies what needs judgement — keywords, an icon, the line he hears — and teaches it.
 *
 * <h2>The rule</h2>
 *
 * <ul>
 *   <li>Only when EVERY successful line passes {@link CommandGuard}: a draft the guard would refuse
 *       is not a draft, it is a second refusal.</li>
 *   <li>Chat lines ({@code say}, {@code tellraw}) are left out — the template's own {@code say} is
 *       what he hears when he presses the button.</li>
 *   <li>A list that is one block run N times (twenty identical summons) is folded to that block
 *       with {@code repeat: N}, and {@code countable} when it summons, so "spawn 12" sets N.</li>
 *   <li>{@code take_me_to} becomes {@code wishvisit <id>}, which finds the build each time.</li>
 *   <li>Absolute coordinates are flagged, never rewritten: whether the thing belongs THERE or
 *       beside him is exactly the positional judgement the agent keeps.</li>
 * </ul>
 */
public final class TeachDraft {

    private TeachDraft() {
    }

    /** The draft: commands in order, how many times, and what to look at before teaching it. */
    public record Draft(List<String> commands, int repeat, boolean countable, List<String> warnings) {

        public Draft {
            commands = List.copyOf(commands);
            warnings = List.copyOf(warnings);
        }

        /** The {@code add_template} arguments the server can fill in; the agent adds the rest. */
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            JsonArray cmds = new JsonArray();
            commands.forEach(cmds::add);
            o.add("commands", cmds);
            if (repeat > 1) {
                o.addProperty("repeat", repeat);
            }
            if (countable) {
                o.addProperty("countable", true);
                o.addProperty("maxRepeat", Math.min(WishTemplates.HARD_MAX_REPEAT, Math.max(repeat, 50)));
            }
            return o;
        }
    }

    /** Three numbers in a row, none relative: a fixed spot in the world. */
    private static final Pattern ABSOLUTE = Pattern.compile(
            "(?<![~^\\w.])-?\\d+(\\.\\d+)?\\s+-?\\d+(\\.\\d+)?\\s+-?\\d+(\\.\\d+)?(?![\\w.])");

    /**
     * @param ran     the run's successful {@code run_command} lines, in order
     * @param wentTo  the artifact {@code take_me_to} took him to, or null
     * @param allowed {@link CommandGuard#isAllowed} — a seam so the rule is testable on its own
     * @return the draft, or null when there is nothing a template could hold
     */
    public static Draft of(List<String> ran, String wentTo, Predicate<String> allowed) {
        return of(ran, wentTo, allowed, List.of());
    }

    /**
     * {@link #of(List, String, Predicate)}, with the run's successful {@code modify_mob} changes
     * folded into the summon each one changed: {@code summon minecraft:snow_golem ~ ~ ~}
     * then {@code modify_mob follow} drafts {@code wishsummon minecraft:snow_golem {"follow":true}},
     * so the button's mob comes back WITH its change. A change that cannot be folded (no summon of
     * its mob, or summon NBT wishsummon cannot carry) is a warning naming the line to teach instead.
     */
    public static Draft of(List<String> ran, String wentTo, Predicate<String> allowed,
                           List<ModifyMobFold.Change> mobChanges) {
        List<String> kept = new ArrayList<>();
        if (wentTo != null && !wentTo.isBlank()) {
            kept.add("wishvisit " + wentTo.strip());
        }
        for (String raw : ran == null ? List.<String>of() : ran) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String c = raw.strip();
            if (c.startsWith("/")) {
                c = c.substring(1);
            }
            if (allowed != null && !allowed.test(c)) {
                return null;
            }
            String root = c.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
            if (root.equals("say") || root.equals("tellraw")) {
                continue;
            }
            kept.add(c);
        }
        if (kept.isEmpty()) {
            return null;
        }
        List<String> foldWarnings = new ArrayList<>();
        if (mobChanges != null && !mobChanges.isEmpty()) {
            ModifyMobFold.Result folded = ModifyMobFold.fold(kept, mobChanges);
            if (folded.refusal() != null) {
                foldWarnings.add(folded.refusal().replaceFirst("^NOT SAVED — ", ""));
            } else {
                kept = new ArrayList<>(folded.commands());
                for (String n : folded.notes()) {
                    if (n.contains("had no summon")) {
                        foldWarnings.add(n);
                    }
                }
            }
        }
        List<String> block = kept;
        int repeat = 1;
        for (int p = 1; p <= kept.size() / 2; p++) {
            if (kept.size() % p == 0 && periodic(kept, p)) {
                block = kept.subList(0, p);
                repeat = kept.size() / p;
                break;
            }
        }
        repeat = Math.min(repeat, WishTemplates.HARD_MAX_REPEAT);
        boolean summons = false;
        List<String> warnings = new ArrayList<>(foldWarnings);
        for (String c : block) {
            if (c.toLowerCase(Locale.ROOT).matches("^(summon|wishsummon|wishmob spawn)\\b.*")) {
                summons = true;
            }
            if (ABSOLUTE.matcher(c).find()) {
                warnings.add("`" + c + "` uses FIXED coordinates — the button would put it back in "
                        + "exactly that spot. Use ~ ~ ~ (beside him) unless that spot is the point.");
            }
        }
        return new Draft(block, repeat, summons && repeat > 1, warnings);
    }

    private static boolean periodic(List<String> list, int p) {
        for (int i = p; i < list.size(); i++) {
            if (!list.get(i).equals(list.get(i - p))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The refusal {@code finish_wish} returns ONCE: the draft, and what is still the agent's to
     * decide. Worded so that teaching is the short path, and finishing anyway is always possible.
     */
    public static String refusal(Draft d) {
        StringBuilder sb = new StringBuilder("Not yet — teach this one first. You granted it with "
                + "commands and it is not a button yet, so next time he wishes for it you would "
                + "have to work it all out again; he would be told \"I can't save it\". Here is an "
                + "add_template draft from the commands that worked:\n")
                .append(d.toJson()).append('\n')
                .append("Add `id`, the words he'd say (`any`/`all`), an `icon` he'd recognise and "
                        + "the `say` line, fix anything that only made sense once (a test, a "
                        + "clean-up), then call add_template and finish_wish again.");
        for (String w : d.warnings()) {
            sb.append("\n⚠ ").append(w);
        }
        sb.append("\nIf it really is a one-time thing (it depends on where he was, or on something "
                + "that is gone now), just call finish_wish again — this asks only once.");
        return sb.toString();
    }
}
