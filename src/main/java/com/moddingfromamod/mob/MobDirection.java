package com.moddingfromamod.mob;

import java.util.List;
import java.util.Locale;

/**
 * <b>Did he ask for MORE of something, or less?</b> — and the guard that stops a wish being answered
 * backwards.
 *
 * <p>an earlier issue's actual defect, stated in the issue: {@code modify_mob} takes <b>absolute</b> numbers, so
 * "make him faster" requires inventing one — <i>"and if it guesses low the mob gets SLOWER while the
 * wish said faster. The wish is not merely unanswered; it is answered backwards, and nothing in the
 * world or the log says so."</i>
 *
 * <p>Half of that is now fixed by reading: {@code Surroundings} reports every field
 * {@code modify_mob} can write, so the agent can see 0.25 before it writes. This is the other half —
 * the agent can still read 0.25 and write 0.2, and nothing would notice. So the direction is checked
 * against his own words, which is what the issue says to assert on.
 *
 * <h2>The rule, and why it is coarse on purpose</h2>
 *
 * If his sentence asks for MORE and says nothing about less, then <b>nothing may go down</b>; if it
 * asks for LESS, nothing may go up; if it says both ("bigger but slower") or neither, there is no
 * opinion to enforce.
 *
 * <p>Not per-field, deliberately. Mapping "faster" to {@code speed} and "tougher" to {@code health}
 * is easy and wrong at the edges — "make him a monster" means all of them, and a child rarely names
 * the stat he means. What is never right is a wish that asks for more and delivers less of anything
 * he did not mention, so that is the thing forbidden.
 *
 * <p>Pure — no Minecraft — so every phrasing is a unit test.
 */
public final class MobDirection {

    /** Which way he asked. */
    public enum Way { MORE, LESS, NONE }

    /** Words that ask for more of something. */
    private static final List<String> MORE = List.of(
            "bigger", "biggest", "huge", "huger", "hugest", "giant", "gigantic", "enormous",
            "massive", "larger", "large", "grow", "faster", "fastest", "quicker", "speedier",
            "stronger", "strongest", "tougher", "toughest", "harder", "more health", "more hearts",
            "more damage", "more powerful", "powerful", "buff", "beef", "super", "mega", "ultra",
            "op ", "overpowered", "scarier", "deadlier", "meaner", "level up", "upgrade");

    /** Words that ask for less. */
    private static final List<String> LESS = List.of(
            "smaller", "smallest", "tiny", "tinier", "tiniest", "little", "shrink", "mini",
            "slower", "slowest", "weaker", "weakest", "nerf", "less health", "less damage",
            "fewer hearts", "gentler", "friendlier", "calmer", "normal size", "back to normal");

    private MobDirection() {
    }

    /** Read the child's own sentence. */
    public static Way of(String text) {
        if (text == null || text.isBlank()) {
            return Way.NONE;
        }
        String t = " " + text.toLowerCase(Locale.ROOT).strip() + " ";
        boolean more = containsAny(t, MORE);
        boolean less = containsAny(t, LESS);
        if (more == less) {
            // Both, or neither. "Bigger but slower" is a real wish and enforcing either half of it
            // would refuse the other; a sentence with no direction in it has nothing to enforce.
            return Way.NONE;
        }
        return more ? Way.MORE : Way.LESS;
    }

    /**
     * <b>Would this change go the wrong way?</b>
     *
     * @param way     what his words asked for
     * @param current what the mob has now, or null when the mob has no such attribute
     * @param wanted  what the tool was asked to set, or null when the field was not passed
     * @return true when the write contradicts the wish
     */
    public static boolean backwards(Way way, Double current, Double wanted) {
        if (way == Way.NONE || current == null || wanted == null) {
            return false;
        }
        // Equal is not backwards. Answering "make him faster" with the speed he already has is a
        // wish that did nothing, which is a different complaint and not one worth refusing over —
        // several fields legitimately clamp to a ceiling the mob is already at.
        return way == Way.MORE ? wanted < current : wanted > current;
    }

    private static boolean containsAny(String haystack, List<String> needles) {
        for (String n : needles) {
            if (haystack.contains(n)) {
                return true;
            }
        }
        return false;
    }
}
