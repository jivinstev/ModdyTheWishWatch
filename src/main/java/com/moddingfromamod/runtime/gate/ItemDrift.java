package com.moddingfromamod.runtime.gate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.google.gson.JsonObject;
import com.moddingfromamod.item.ItemSpecStore;

/**
 * <b>What an edit to a wish item quietly took away.</b>
 *
 * <p>The items class's {@link BuildDrift}, and it exists because an item is the first artifact whose
 * identity is <b>half behaviour</b>. A castle that loses a tower looks different; a wand that has
 * stopped doing lightning looks exactly the same. Every other check this repo has — colour, count,
 * palette, survival — reads what the thing IS, and none of them can see what it no longer DOES.
 *
 * <h2>The failure it is aimed at, which was measured</h2>
 * <i>"Can it set things on fire too"</i> is answered correctly as {@code effect: "lightning+ignite"}
 * and incorrectly as {@code effect: "ignite"} — the second silently drops the lightning the wand was
 * made for. In the first ladder run before the edit path existed, exactly that happened: the child's
 * third wand had fire and had lost the extra damage its predecessor had, and every gate was green
 * because {@code effect} is the whole list rather than a delta and nothing compared it to what came
 * before.
 *
 * <p>It also catches the quieter half: an effect kept in the list while its parameters are reset, so
 * the item still claims to burn and burns for the default time rather than the six seconds he asked
 * for.
 *
 * <h2>It informs; it does not command</h2>
 * The same rule {@link BuildDrift} states and for the same reason: five mechanisms in this codebase
 * have been caught instructing the agent in an authoritative voice and being obeyed into a worse
 * outcome. There is no "put it back" here — an agent told to put it back would re-send the whole
 * item, which is the restatement that wrote a wand's colour back to purple and undid a wish.
 *
 * <h2>Unsatisfiable by tidying</h2>
 * [] is the standing warning: a machine guard once checked output-on-the-floor as a symptom and
 * an agent cleaned the floor instead of fixing the machine. This gate reads the artifact's own
 * previous version, so nothing the agent writes in a note or a status can talk it out of the
 * finding, and the only way to make it silent is to actually keep the effect.
 *
 * <p>Pure and Minecraft-free, so the whole rule is a unit test.
 */
public final class ItemDrift {

    /** Words that mean he asked for something to be taken OFF, rather than added to. */
    private static final String[] REMOVAL = {
        "remove", "take away", "get rid", "stop", "no longer", "without",
        "delete", "undo", "instead of", "not any more", "no more", "cancel",
    };

    private ItemDrift() {
    }

    /**
     * What this edit lost, in the child's terms, or {@code ""} when nothing worth saying did.
     *
     * @param before     the item's design before this call; null for a brand-new item
     * @param after      the RESOLVED design — inherited fields merged in, not the fields sent
     * @param childWords the wish being granted; something he asked to remove is never reported
     */
    public static String between(ItemSpecStore.Spec before, ItemSpecStore.Spec after,
                                 String childWords) {
        if (before == null || after == null) {
            return "";                        // a new item has nothing to lose
        }
        if (removalRequested(childWords)) {
            return "";
        }
        List<String> lost = dropped(before.effects(), after.effects());
        if (!lost.isEmpty()) {
            return "⚠ THIS TOOK SOMETHING OFF THE ITEM — it no longer does " + join(lost)
                    + ", and he did not ask for that to stop. `effect` is the WHOLE list, not the "
                    + "new part: to add one, send every effect it already had as well ("
                    + String.join("+", after.effects()) + "+" + String.join("+", lost)
                    + "). He will not see this by looking at it — it is the same wand — he will "
                    + "find out when he tries. Say in your note whether it still does " + join(lost)
                    + ".";
        }
        List<String> blanked = blanked(before, after);
        if (!blanked.isEmpty()) {
            return "This kept " + join(blanked) + " but dropped the numbers he chose for it, so it "
                    + "goes back to the default. Parameters are merged per effect, so you only need "
                    + "to send the ones that change. Worth a line in your note if it matters.";
        }
        return "";
    }

    /** Effects the item used to have and no longer does, in the order it had them. */
    static List<String> dropped(List<String> before, List<String> after) {
        Set<String> now = new LinkedHashSet<>();
        for (String s : after == null ? List.<String>of() : after) {
            now.add(key(s));
        }
        List<String> out = new ArrayList<>();
        for (String s : before == null ? List.<String>of() : before) {
            if (!now.contains(key(s))) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * Effects that survived while the numbers he chose for them did not.
     *
     * <p>Only counts a parameter block going from something to NOTHING. A changed value is the
     * edit — <i>"make it hurt way more"</i> is precisely a changed value — and reporting that would
     * fire on every successful refinement, which is how a gate becomes noise and stops being read.
     */
    static List<String> blanked(ItemSpecStore.Spec before, ItemSpecStore.Spec after) {
        List<String> out = new ArrayList<>();
        JsonObject was = before.params();
        JsonObject now = after.params();
        if (was == null) {
            return out;
        }
        for (String effect : after.effects()) {
            if (!was.has(effect) || !was.get(effect).isJsonObject()) {
                continue;
            }
            if (was.getAsJsonObject(effect).keySet().isEmpty()) {
                continue;
            }
            boolean gone = now == null || !now.has(effect) || !now.get(effect).isJsonObject()
                    || now.getAsJsonObject(effect).keySet().isEmpty();
            if (gone) {
                out.add(effect);
            }
        }
        return out;
    }

    /**
     * Did he ask for something to come off?
     *
     * <p>Narrow on purpose, and the reason is the R5 trap this repo has already paid for twice: a
     * silencer keyed on the words of the wish can mute the exact case it exists for. The colour gate
     * keyed on "colour words" swallowed <i>"give him tiny black wings"</i>; a proportion gate keyed
     * on "big" would have swallowed <i>"put a BIG glass dome on top of it"</i>. So this matches only
     * words that mean subtraction, and never a word that could appear in an addition —
     * <i>"can it set things on fire TOO"</i> must reach the check, because that is the wish that
     * caused the loss.
     */
    static boolean removalRequested(String childWords) {
        if (childWords == null || childWords.isBlank()) {
            return false;
        }
        String w = childWords.toLowerCase(Locale.ROOT);
        for (String phrase : REMOVAL) {
            if (w.contains(phrase)) {
                return true;
            }
        }
        // "take … off" as a PAIR rather than a phrase. A child says "take the lightning off it",
        // and the contiguous string "take off" is nowhere in that. Matching the two words together
        // rather than "off" alone is what keeps "it goes off where it lands" out of the net.
        return w.contains("take") && w.contains("off");
    }

    private static String key(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String join(List<String> xs) {
        if (xs.size() == 1) {
            return xs.get(0);
        }
        return String.join(", ", xs.subList(0, xs.size() - 1)) + " and " + xs.get(xs.size() - 1);
    }
}
