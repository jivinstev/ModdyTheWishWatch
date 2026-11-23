package com.moddingfromamod.runtime.gate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.google.gson.JsonObject;
import com.moddingfromamod.item.ItemSpecStore;

/**
 * <b>A number he chose, quietly rewritten on a wish about something else.</b>
 *
 * <p>The item-effect class's R4/R5 gate, and the half {@link ItemDrift} deliberately cannot see.
 * ItemDrift grades the effect <i>list</i> — a wand that has stopped doing lightning — and a
 * parameter block going from something to nothing. It says so in its own doc, and it is right to:
 * a <i>changed</i> value is what a refinement IS, and a gate that fired on every successful edit
 * would be noise inside a week.
 *
 * <h2>The failure this is aimed at</h2>
 * <i>"Make its lightning way stronger"</i> is answered correctly as {@code lightning:{STRIKES:8}}
 * and incorrectly as {@code lightning:{STRIKES:8}, ignite:{SECONDS:8}} — where the burn was six
 * seconds because he asked for six, three rungs ago, and 8 is what the tool's own description
 * suggests to a model filling a field from memory. The item still lists three effects. It still
 * looks identical. Every check the items class has scores it perfectly. He finds out the next time
 * he sets something on fire, and by then the wish that chose six is four rungs back.
 *
 * <p>{@code make_item} INHERITS anything not mentioned, so a parameter can only move when the agent
 * deliberately sent a different number for it. This gate therefore fires on exactly one thing: a
 * value rewritten on an effect the child was not talking about.
 *
 * <h2>Why the suppression is keyed this narrowly</h2>
 * An effect is excused only when the child's words contain <b>its own id as a whole word</b>, or
 * when it is the <b>only</b> effect that moved — in which case it is plainly the edit, whatever he
 * called it ("make it stronger"). Nothing else is excused.
 *
 * <p>That is deliberately biased toward reporting, and the bias is the lesson of two R5 traps this
 * repo has already paid for: a colour gate keyed on "colour words" swallowed <i>"give him tiny
 * black wings"</i>, and a proportion gate keyed on "big" would have swallowed <i>"put a BIG glass
 * dome on top of it"</i>. A silencer built out of the child's vocabulary mutes the exact case it
 * exists for. Here a missed suppression costs one extra sentence in a tool result; a missed
 * REPORT costs him the number he asked for.
 *
 * <h2>Unsatisfiable by tidying</h2>
 * [] is the standing warning — a machine guard once checked output-on-the-floor as a symptom
 * and an agent cleaned the floor instead of fixing the machine. This reads the artifact's own
 * previous version, so nothing the agent writes in a note can talk it out of the finding, and the
 * only way to silence it is to actually keep the number.
 *
 * <p>Pure and Minecraft-free, so the whole rule is a unit test.
 */
public final class EffectDrift {

    private EffectDrift() {
    }

    /**
     * What this edit rewrote that he did not ask about, in his terms, or {@code ""}.
     *
     * @param before     the item's design before this call; null for a brand-new item
     * @param after      the RESOLVED design — inherited values merged in, not the fields sent
     * @param childWords the wish being granted
     */
    public static String between(ItemSpecStore.Spec before, ItemSpecStore.Spec after,
                                 String childWords) {
        if (before == null || after == null) {
            return "";                        // a new item has no earlier number to lose
        }
        List<Moved> moved = moved(before.params(), after.params(), after.effects());
        if (moved.isEmpty()) {
            return "";
        }
        Set<String> effectsMoved = new LinkedHashSet<>();
        for (Moved m : moved) {
            effectsMoved.add(m.effect());
        }
        // ...AND ONLY WHEN NOTHING ELSE HAPPENED. The excuse below says "one number moved, so that
        // is plainly what he meant" — which is true of a wish that only tuned a number, and false
        // the moment the same edit also added or removed an effect. Then the wish was evidently
        // about the LIST, and a number moving alongside it is exactly the collateral this gate is
        // for. Found by the wiring test, which built an edit that dropped `freeze` and rewrote
        // `ignite`'s burn: the first version excused the burn because it was the only number that
        // moved, and reported a lost effect while staying silent about a lost wish.
        boolean listChanged = !new LinkedHashSet<>(lower(before.effects()))
                .equals(new LinkedHashSet<>(lower(after.effects())));
        List<Moved> unasked = new ArrayList<>();
        for (Moved m : moved) {
            boolean named = mentions(childWords, m.effect());
            // THE ONLY ONE THAT MOVED IS THE EDIT, whatever he called it. "Make it stronger" names
            // no effect at all, and refusing to excuse it would report every such wish as drift —
            // which is how a gate stops being read.
            boolean theOnlyOne = effectsMoved.size() == 1 && !listChanged;
            if (!named && !theOnlyOne) {
                unasked.add(m);
            }
        }
        if (unasked.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(
                "⚠ THIS CHANGED A NUMBER HE DID NOT ASK ABOUT — ");
        for (int i = 0; i < unasked.size(); i++) {
            Moved m = unasked.get(i);
            if (i > 0) {
                sb.append(i == unasked.size() - 1 ? " and " : ", ");
            }
            sb.append(m.effect()).append("'s ").append(m.key())
              .append(" was ").append(m.was()).append(" and is now ").append(m.now());
        }
        sb.append(". He chose that in an earlier wish and this one was not about it. `params` "
                + "INHERITS anything you leave out, so the fix is to leave it out — sending it "
                + "again from memory is how it moved. He will not see this by looking at the item; "
                + "he will find out when he uses it.");
        return sb.toString();
    }

    private static List<String> lower(List<String> xs) {
        List<String> out = new ArrayList<>();
        for (String x : xs == null ? List.<String>of() : xs) {
            out.add(x == null ? "" : x.trim().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** One parameter that changed value between two resolved designs. */
    record Moved(String effect, String key, String was, String now) {
    }

    /**
     * Every parameter whose value changed, on effects the item still has.
     *
     * <p>Only effects present in {@code after}: one that was removed entirely is
     * {@link ItemDrift}'s finding, and reporting it here too would say the same thing twice in a
     * voice that suggests two different problems.
     */
    static List<Moved> moved(JsonObject was, JsonObject now, List<String> effects) {
        List<Moved> out = new ArrayList<>();
        if (was == null || now == null) {
            return out;
        }
        for (String effect : effects) {
            if (!was.has(effect) || !was.get(effect).isJsonObject()
                    || !now.has(effect) || !now.get(effect).isJsonObject()) {
                continue;
            }
            JsonObject a = was.getAsJsonObject(effect);
            JsonObject b = now.getAsJsonObject(effect);
            for (String key : a.keySet()) {
                if (!b.has(key)) {
                    continue;      // blanked, which is ItemDrift's finding rather than this one
                }
                String left = a.get(key).getAsString();
                String right = b.get(key).getAsString();
                if (!left.equals(right)) {
                    out.add(new Moved(effect, key, left, right));
                }
            }
        }
        return out;
    }

    /**
     * Does the wish name this effect?
     *
     * <p>Whole word, and the effect's own id only. Without the whole-word rule "ignite" would be
     * found inside nothing useful but "hurt" would match "hurtling" and "place" would match
     * "fireplace" — the same class of bug {@code MeatFilter}'s whole-word rule exists for, where
     * {@code cod} matched {@code cocoa_beans}.
     */
    static boolean mentions(String childWords, String effect) {
        if (childWords == null || childWords.isBlank() || effect == null || effect.isBlank()) {
            return false;
        }
        String text = childWords.toLowerCase(Locale.ROOT);
        String want = effect.toLowerCase(Locale.ROOT);
        int from = 0;
        while (true) {
            int at = text.indexOf(want, from);
            if (at < 0) {
                return false;
            }
            boolean leftOk = at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1));
            int end = at + want.length();
            boolean rightOk = end >= text.length() || !Character.isLetterOrDigit(text.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            from = at + 1;
        }
    }
}
