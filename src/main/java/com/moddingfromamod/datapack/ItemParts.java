package com.moddingfromamod.datapack;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

/**
 * <b>PAYLOAD — what a thing is ASSEMBLED FROM.</b>
 *
 * <p><i>"a cone with sprinkls and choclate sauce"</i>. The parts are the whole wish: a cone is a
 * cone, and what makes it his is what he put on it. Before this the only thing an item could carry
 * was its name and its colour, so the parts had to be folded into the name or thrown away.
 *
 * <p><b>Why a bounded list of plain words rather than a typed schema of toppings.</b> There is no
 * vocabulary of ice-cream toppings and inventing one would answer this wish and no other — the
 * parts a child names are open-ended by nature (sprinkles, sauce, a flake, marshmallows, "extra
 * chocolate"). What is bounded instead is the SHAPE: at most {@value #MAX} parts, each at most
 * {@value #MAX_LENGTH} characters, matched against {@link #PART} so nothing but letters, digits,
 * spaces, apostrophes and hyphens can survive. That is enough to keep it out of the command and
 * still let him say what he wants on his cone.
 *
 * <p><b>They have to read BACK, or the axis is decoration.</b> The corpus names two properties for
 * this wish — <i>assembled from chosen parts</i> and <i>the parts read back</i> — so they are
 * rendered into the item's lore, where he actually reads them, and stored on the design so
 * {@code inspect_item} can report them and a later wish can change one.
 */
public final class ItemParts {

    private ItemParts() {
    }

    /** At most this many. A cone with nine things on it is a wish that needs a conversation. */
    public static final int MAX = 8;

    /** At most this long. A part is a thing, not a sentence. */
    public static final int MAX_LENGTH = 24;

    /** What a part may be made of. Nothing here can end a string, a selector or a component. */
    private static final Pattern PART = Pattern.compile("[A-Za-z0-9 '\\-]{1,24}");

    /** Everything wrong with this {@code parts} list, in the agent's terms, or empty. */
    public static List<String> refusals(JsonArray parts) {
        List<String> bad = new ArrayList<>();
        if (parts == null) {
            return bad;
        }
        if (parts.size() > MAX) {
            bad.add("parts has " + parts.size() + " things on it and the most one wish can carry is "
                    + MAX + ". Ask him which " + MAX + " he wants most rather than choosing for him.");
        }
        for (int i = 0; i < parts.size(); i++) {
            JsonElement el = parts.get(i);
            String v = el.isJsonPrimitive() ? el.getAsString().trim() : "";
            if (v.isEmpty()) {
                bad.add("parts[" + i + "] is empty — every part is something he named.");
            } else if (v.length() > MAX_LENGTH) {
                bad.add("parts[" + i + "] is longer than " + MAX_LENGTH + " characters. A part is a "
                        + "thing on the item, not a description of it.");
            } else if (!PART.matcher(v).matches()) {
                bad.add("parts[" + i + "] ('" + v + "') has characters a part cannot have. Letters, "
                        + "numbers, spaces, apostrophes and hyphens only.");
            }
        }
        return bad;
    }

    /** The parts, trimmed, in the order he said them. Call {@link #refusals} first. */
    public static List<String> of(JsonArray parts) {
        List<String> out = new ArrayList<>();
        if (parts == null) {
            return out;
        }
        for (JsonElement el : parts) {
            out.add(el.getAsString().trim());
        }
        return out;
    }

    /** One lore line naming what is on it, or null when it is made of nothing in particular. */
    public static String loreLine(JsonArray parts) {
        List<String> p = of(parts);
        if (p.isEmpty()) {
            return null;
        }
        if (p.size() == 1) {
            return "With " + p.get(0);
        }
        // "With sprinkles, chocolate sauce and a flake" -- read out the way he would say it, because
        // this line is the only place the parts exist for him.
        return "With " + String.join(", ", p.subList(0, p.size() - 1)) + " and " + p.get(p.size() - 1);
    }
}
