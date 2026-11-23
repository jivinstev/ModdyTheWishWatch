package com.moddingfromamod.datapack;

import java.util.Locale;

/**
 * <b>What does this CBScript actually fire on?</b>
 *
 * <h2>Why derive it instead of asking</h2>
 * {@code BuiltAbility} carries the child-facing sentence — <i>"It happens when spider HITS
 * something"</i> — from the tool that built an ability to the tool that announces it. Only the
 * {@code apply_effect} path records anything, because it knows which catalogue entry it
 * instantiated. An <b>authored</b> datapack reports nothing about the scaffold it emits, so
 * {@code describe()} returns empty for it and the child is told nothing at all about when his
 * ability goes off.
 *
 * <p>The alternative — asking the agent to declare its own trigger — is what established is
 * the wrong shape: a declaration is a claim, and this repo has an entire class of bug where a
 * claim and the artifact disagree and the claim is what reaches the child. The program itself
 * cannot be wrong about what it fires on.
 *
 * <h2>Keyed on SEMANTICS, not on our own template names</h2>
 * The obvious implementation greps for the scoreboard names our templates use
 * ({@code mfam_clock}, {@code mfam_hp}). That works on a rendered template and fails on the thing
 * this is FOR — CBScript the agent wrote itself, with its own names. So each signature below is a
 * marker that any correct implementation of that trigger must contain, whoever wrote it:
 *
 * <ul>
 *   <li><b>on_hit</b> — reads {@code HurtTime}. There is no other way to know something was just
 *       damaged that works for mobs as well as players; that is the whole finding recorded in
 *       {@code on_hit.cbs}, and a scoreboard criterion covers only the child.</li>
 *   <li><b>on_low_health</b> — compares {@code Health} against a fraction of its maximum.</li>
 *   <li><b>on_death</b> — reads a death count or the {@code deathCount} criterion.</li>
 *   <li><b>periodic</b> — keeps a tick counter and resets it, which is what "every N seconds" IS.</li>
 * </ul>
 *
 * <h2>⚠ PROXIMITY IS NOT DERIVABLE, and that was measured rather than assumed</h2>
 * There are five triggers in the catalogue and only four are listed above. The first cut keyed
 * proximity on {@code distance=} and was wrong twice over, which reading {@code proximity.cbs}
 * settles in four lines — the whole scaffold is:
 *
 * <pre>{@code clock tick
 *     /execute as @e[tag=${TAG}] at @s run ${COMMAND}
 * end}</pre>
 *
 * <p>There is <b>no {@code distance=} in it at all</b>. Every discriminating detail of "whatever
 * is near this creature" lives inside {@code ${COMMAND}} — which is the child's ABILITY, not the
 * trigger. Meanwhile {@code distance=} <i>does</i> appear in {@code on_hit.cbs} (the victim's
 * radius) and in {@code on_death.cbs} (the shadow marker's 2.5-block check), so the rule matched
 * two triggers it was not for and missed the one it was.
 *
 * <p>What is left of the shape is "tick the tagged creature, gate on nothing" — and taking that as
 * proximity would be a residual guess, not a derivation. A bare tick loop is equally the shape of a
 * permanent effect on the creature itself ({@code effect give @s glowing}), and telling a child
 * <i>"it happens when something comes NEAR"</i> about an ability that is simply always on is
 * exactly the confident-wrong sentence this class exists to remove. So proximity gets no sentence.
 * {@code TriggerShapeTest.proximityHasNoSignatureOfItsOwn} pins that as a deliberate answer, so a
 * later reader cannot quietly close the gap with a guess.
 *
 * <h2>It returns null rather than guessing, and that is load-bearing</h2>
 * {@code BuiltAbility}'s own rule: <i>"Empty, not a fallback sentence: a confident guess is exactly
 * the failure this class exists to remove."</i> An unrecognised program gets no sentence, which is
 * the honest answer — the child is told nothing rather than told something wrong about his own
 * creature.
 */
public final class TriggerShape {

    private TriggerShape() {
    }

    /**
     * The catalogue trigger id this program's shape corresponds to, or {@code null}.
     *
     * <p>Order matters where shapes nest, and the nesting is real rather than hypothetical:
     * {@code on_low_health} keeps a tick counter of its own and would read as {@code periodic},
     * and {@code on_death} compares a distance. So the conditions that name a specific mechanism
     * are tested before the ones that name a generic one.
     */
    public static String of(String cbscript) {
        if (cbscript == null || cbscript.isBlank()) {
            return null;
        }
        String src = strip(cbscript);
        if (src.contains("hurttime")) {
            return "on_hit";
        }
        if (src.contains("deathcount") || src.contains("mfam_death")) {
            return "on_death";
        }
        // Health AND a comparison — a program that merely reads Health (a status readout, a
        // display) is not an on-low-health trigger, and calling it one would put a sentence about
        // "when he is nearly beaten" on an ability that fires whenever.
        if (src.contains("health") && (src.contains("matches") || src.contains("mfam_pct")
                || src.contains("<") || src.contains(".."))) {
            return "on_low_health";
        }
        // A counter that is INCREMENTED and RESET. Incrementing alone is a tally; the reset is what
        // makes it a period.
        if ((src.contains("players add") || src.contains("players set"))
                && src.contains("matches") && src.contains("players set")) {
            return "periodic";
        }
        // No proximity branch, on purpose — see the class comment. Its scaffold has no signature.
        return null;
    }

    /**
     * <b>Who the sentence is ABOUT</b> — the creature name to put where the catalogue's
     * {@code child_when} has {@code ${TAG}}, or {@code null} if the program does not name one.
     *
     * <p>This is not a second-guess: {@code apply_effect} substitutes exactly the same kind of
     * value, an agent-chosen tag it was handed as an argument. An authored pack writes that same
     * name into its own selectors ({@code @e[tag=dragon]}), so recovering it gives a subject of
     * identical quality rather than a weaker one.
     *
     * <p>Three things are refused. A <b>negated</b> selector ({@code tag=!dragon}) names who it is
     * NOT — using it would invert the sentence. An unsubstituted <b>placeholder</b>
     * ({@code tag=${TAG}}) is a raw template, not a name. And our own <b>internal</b> tags
     * ({@code mfam_dw_…}, the death-marker scaffolding) are machinery the child has never heard of;
     * "it happens when mfam_dw_spider DIES" is a worse sentence than one with no name in it.
     */
    public static String subjectTag(String cbscript) {
        if (cbscript == null) {
            return null;
        }
        for (String line : strippedLines(cbscript)) {
            int at = 0;
            while ((at = line.indexOf("tag=", at)) >= 0) {
                int start = at + 4;
                at = start;
                if (start >= line.length() || line.charAt(start) == '!') {
                    continue;
                }
                int end = start;
                while (end < line.length() && (Character.isLetterOrDigit(line.charAt(end))
                        || line.charAt(end) == '_' || line.charAt(end) == '.'
                        || line.charAt(end) == '-')) {
                    end++;
                }
                String name = line.substring(start, end);
                if (name.isEmpty() || name.startsWith("mfam")) {
                    continue;
                }
                return name;
            }
        }
        return null;
    }

    /**
     * The child-facing sentence for a program, or empty — the whole point of the class, in one call.
     *
     * <p>The sentence text comes from the <b>catalogue</b> rather than from a copy kept here, so
     * the wording a child hears about an authored pack is the same wording they hear about a
     * ready-made one and cannot drift from it.
     *
     * @param childWhenFor resolves a trigger id to that entry's raw {@code child_when}, or null
     */
    public static String sentenceFor(String cbscript,
            java.util.function.Function<String, String> childWhenFor) {
        return sentenceFor(cbscript, childWhenFor, id -> null);
    }

    /**
     * {@link #sentenceFor(String, java.util.function.Function)}, naming the creature by its species
     * display name where {@code speciesName} knows the tag: the selector tag of a Builder
     * make is an id like {@code ember_the_flying_dragon_24a470}, never a word to say to a child.
     *
     * @param speciesName a species id → its display name, or null when unknown
     */
    public static String sentenceFor(String cbscript,
            java.util.function.Function<String, String> childWhenFor,
            java.util.function.Function<String, String> speciesName) {
        String trigger = of(cbscript);
        if (trigger == null) {
            return "";
        }
        String raw = childWhenFor.apply(trigger);
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String subject = subjectTag(cbscript);
        // No name recovered → a subject that is true of anything rather than a guessed name. A
        // sentence with "it" in it is still the right trigger; a sentence with the WRONG creature
        // in it is the spider-bite failure BuiltAbility was written for.
        // The subject is said as the CHILD knows it — never the raw tag.
        return com.moddingfromamod.wish.ChildSubject.say(raw, subject, speciesName);
    }

    /**
     * Comments removed and lowercased, so a signature cannot be matched inside prose.
     *
     * <p>Not cosmetic: every shipped template opens with a comment block explaining what it does,
     * and {@code on_hit.cbs}'s own header contains the words <i>"HurtTime"</i>, <i>"scoreboard"</i>
     * and <i>"damage_taken"</i>. Matching against that would classify a program by what its author
     * wrote ABOUT it rather than by what it does — which is the declaration this exists to replace.
     */
    private static String strip(String cbscript) {
        return String.join("\n", strippedLines(cbscript)).toLowerCase(Locale.ROOT);
    }

    /**
     * The program's code lines, comments dropped, case PRESERVED.
     *
     * <p>Case matters here and not in {@link #strip}: a signature match is case-insensitive, but a
     * recovered tag is spoken to a child, so lowercasing it would rename his creature.
     */
    private static java.util.List<String> strippedLines(String cbscript) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String line : cbscript.split("\n", -1)) {
            int hash = line.indexOf('#');
            // ⚠ A '#' is also CBScript's fake-player sigil (`#tick`), so only a hash that opens a
            // comment may be cut. Anything after non-space content is code.
            if (hash >= 0 && line.substring(0, hash).isBlank()) {
                continue;
            }
            out.add(line);
        }
        return out;
    }
}
