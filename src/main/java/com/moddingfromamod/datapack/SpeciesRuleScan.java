package com.moddingfromamod.datapack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>What kinds of mob does this CBScript program have opinions about, and what does it do to
 * them?</b>
 *
 * <p>The reading half of standing species rules. A {@code clock tick} rule over
 * {@code @e[type=zombie]} is how "make zombies bigger" is kept <i>forever</i>, including for zombies
 * that do not exist yet — but once that mechanism exists, two things need to be able to see it:
 *
 * <ul>
 *   <li><b>Conflict detection, at authoring time.</b> A second wish about zombies must EDIT the
 *       existing rule, not run a second datapack beside it. Two packs both selecting every zombie
 *       every tick is a race, and which one wins depends on pack order — a runtime check could only
 *       ever report the symptom after the fact, and inconsistently.</li>
 *   <li><b>Telling the child's next agent what is already true.</b> {@code look_around} listed rules
 *       as an id and a character count, which says a rule exists and nothing about what it does.</li>
 * </ul>
 *
 * <h2>What it is honest about</h2>
 *
 * The <b>species</b> are read reliably: a selector names its type in one place and this finds every
 * one of them. What each rule <i>does</i> is a best-effort summary — when a program touches more
 * than one species this deliberately does NOT claim which change belongs to which, because pairing
 * them needs the block structure and a confident wrong pairing is worse than an honest list. The
 * conflict check keys on the species alone, so the part that gates behaviour is the part that is
 * reliable.
 *
 * <p>Pure — no Minecraft, no I/O — so every program shape below is a unit test.
 */
public final class SpeciesRuleScan {

    /**
     * A selector's type. Deliberately tolerant of the namespace even though CBScript's own parser
     * rejects one inside a selector: the raw {@code /execute} commands in a program may carry it,
     * and a scan that missed those would report "no conflict" on a program that has one.
     */
    private static final Pattern TYPE = Pattern.compile(
            "type\\s*=\\s*(!?)\\s*(?:minecraft:)?([a-z0-9_]+)");

    /** What a program is doing, in the child's words rather than the attribute's. */
    private static final String[][] ASPECTS = {
        {"minecraft:scale", "size"},
        {"generic.scale", "size"},
        {"max_health", "health"},
        {"attack_damage", "how hard it hits"},
        {"movement_speed", "speed"},
        {"knockback_resistance", "how far it gets knocked back"},
        {"effect give", "a potion effect"},
        {"set_glowing", "glow"},
        {"glowing", "glow"},
        {"/summon", "spawning something"},
        {"/damage", "hurting things"},
        {"/tp", "moving things"},
        {"/particle", "particles"},
        {"/playsound", "a sound"},
    };

    private SpeciesRuleScan() {
    }

    /** One thing a program says about one kind of mob. */
    public record Rule(String species, String does) {

        /** How it reads in {@code look_around}: {@code "every zombie: size"}. */
        public String describe() {
            return "every " + species.replace('_', ' ') + ": " + does;
        }
    }

    /**
     * The kinds of mob this program selects.
     *
     * <p><b>Negated types are skipped.</b> {@code type=!player} and {@code type=!item} are how a
     * program says "anything but", and reading those as "this rule is about players" would make
     * almost every program collide with almost every other one — a conflict check that fires
     * constantly gets switched off, and then it protects nothing.
     */
    public static Set<String> speciesIn(String cbscript) {
        Set<String> out = new LinkedHashSet<>();
        if (cbscript == null) {
            return out;
        }
        Matcher m = TYPE.matcher(cbscript.toLowerCase(Locale.ROOT));
        while (m.find()) {
            if (!m.group(1).isEmpty()) {
                continue;             // type=!something — an exclusion, not a subject
            }
            out.add(m.group(2));
        }
        return out;
    }

    /**
     * Only the kinds that are <b>standing rules</b> — selected from a tick clock, so they apply to
     * mobs that do not exist yet.
     *
     * <p>This is the distinction that matters for a conflict. A program that summons a zombie once,
     * or gives the child a zombie egg, mentions zombies and rules nothing; refusing a later "make
     * zombies bigger" because of it would be the check firing on the wrong thing.
     */
    public static Set<String> standingSpeciesIn(String cbscript) {
        return speciesIn(tickSectionOf(cbscript));
    }

    /** Everything this program says about mobs, for a report a person can read. */
    public static List<Rule> rules(String cbscript) {
        List<Rule> out = new ArrayList<>();
        Set<String> species = standingSpeciesIn(cbscript);
        if (species.isEmpty()) {
            return out;
        }
        String does = String.join(", ", aspectsIn(cbscript));
        if (does.isEmpty()) {
            does = "something (it runs every tick)";
        }
        if (species.size() > 1) {
            // Honest rather than confident: pairing a change to a species needs the block structure,
            // and a wrong pairing reads exactly as authoritatively as a right one.
            does = does + " (across " + species.size() + " kinds — which does which is not read here)";
        }
        for (String s : species) {
            out.add(new Rule(s, does));
        }
        return out;
    }

    /** The words for what a program changes, in the order they are found. */
    static List<String> aspectsIn(String cbscript) {
        List<String> out = new ArrayList<>();
        if (cbscript == null) {
            return out;
        }
        String lower = cbscript.toLowerCase(Locale.ROOT);
        for (String[] pair : ASPECTS) {
            if (lower.contains(pair[0]) && !out.contains(pair[1])) {
                out.add(pair[1]);
            }
        }
        return out;
    }

    /**
     * The part of the program that runs every tick.
     *
     * <p>Everything from a {@code clock} line to the end of the program. Deliberately generous: a
     * clock is nearly always the last block, and over-reading costs a false conflict — which is
     * loud, once, and redirects to editing one datapack — while under-reading costs two rules
     * racing every tick forever, with no error anywhere. When the two errors are that asymmetric,
     * take the loud one.
     */
    static String tickSectionOf(String cbscript) {
        if (cbscript == null) {
            return "";
        }
        int at = -1;
        for (String line : cbscript.split("\n")) {
            if (line.strip().toLowerCase(Locale.ROOT).startsWith("clock ")) {
                at = cbscript.indexOf(line);
                break;
            }
        }
        return at < 0 ? "" : cbscript.substring(at);
    }
}
