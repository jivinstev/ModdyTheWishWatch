package com.moddingfromamod.wishbuilder;

import java.util.Locale;

import com.google.gson.JsonObject;

/**
 * <b>What Moddy says about a make</b>. Every line used to be "Making your " + name + "..." and
 * "Your " + name + " is ready!", which works for a thing he owns and breaks for anything named with a
 * sentence: an event's preset title "IT'S RAINING CHICKENS!" became "Your Its raining chickens is ready!".
 * So the words are chosen per kind: "your X" only for a thing that is his; an event is announced in its own
 * words; a rule is switched on. Pure: {@code BuilderWordsTest} reads every shipped preset through it.
 */
public final class BuilderWords {

    private BuilderWords() {
    }

    /** Said when Make is pressed. */
    public static String making(BuilderSpec spec, boolean edit) {
        if (spec.kind() == BuilderSpec.Kind.SUPPLIES) {
            return "Getting " + thing(spec) + "...";   //: "Getting 16 oak logs..."
        }
        if (spec.kind() == BuilderSpec.Kind.EVENT) {
            return (edit ? "Changing it: " : "Here it comes: ") + eventWords(spec);
        }
        return (edit ? "Changing your " : "Making your ") + thing(spec) + "...";
    }

    /** Said when it has arrived. */
    public static String ready(BuilderSpec spec) {
        return switch (spec.kind()) {
            case EVENT -> eventWords(spec);
            case RULE -> "Your " + thing(spec) + " is on!";
            // "Here are 16 oak logs!", "Here is a crafting table!"
            case SUPPLIES -> "Here " + (BuilderSupplies.many(spec) ? "are " : "is ") + thing(spec) + "!";
            default -> "Your " + thing(spec) + " is ready!";
        };
    }

    /** Said when it could not be made. */
    public static String failed(BuilderSpec spec) {
        if (spec.kind() == BuilderSpec.Kind.EVENT) {
            return "That one didn't happen this time: " + eventWords(spec);
        }
        if (spec.kind() == BuilderSpec.Kind.SUPPLIES) {
            return "Those supplies didn't come this time: " + thing(spec) + ".";
        }
        return "Your " + thing(spec) + " didn't work this time.";
    }

    /**
     * What was made, as the subject of a sentence about it ("Your Cozy House can't go there: …"): "your X" only for a
     * thing that is his, as {@link #failed} says it; an event or supplies are "That one" and "Those supplies".
     */
    public static String subject(BuilderSpec spec) {
        if (spec.kind() == BuilderSpec.Kind.EVENT) {
            return "That one";
        }
        if (spec.kind() == BuilderSpec.Kind.SUPPLIES) {
            return "Those supplies";
        }
        return "Your " + thing(spec);
    }

    /** The plan's one step, as a wish's plan reads ("make a Cozy House"). */
    public static String step(BuilderSpec spec, boolean edit) {
        String what = spec.kind() == BuilderSpec.Kind.EVENT ? eventWords(spec) : thing(spec);
        if (spec.kind() == BuilderSpec.Kind.SUPPLIES) {
            return "give him " + what;   //
        }
        return (edit ? "change " : "make ") + what;
    }

    /** The thing's name, for "your ___". */
    static String thing(BuilderSpec spec) {
        return BuilderLowering.label(spec);
    }

    /**
     * An event in its own words. Its tune name is its preset's title with the punctuation stripped
     * ("Its raining chickens"); while it still IS that title the title itself is used, in sentence case
     * ("It's raining chickens!"), so a typed name is used as typed and the stock one keeps its apostrophe.
     */
    static String eventWords(BuilderSpec spec) {
        String name = spec.name();
        String title = presetTitle(spec);
        if (!title.isEmpty() && (name.isEmpty() || letters(name).equals(letters(title)))) {
            return sentence(title);
        }
        return sentence(name.isEmpty() ? BuilderLowering.label(spec) : name);
    }

    static String presetTitle(BuilderSpec spec) {
        if (BuilderEvents.byId(spec.opt("event")) != null) {
            return BuilderEvents.title(spec.opt("event"), spec.opt("what"));   //: a made-up event's own title
        }
        String from = Catalogue.bare(spec.from());
        if (from == null || from.isEmpty()) {
            return "";
        }
        JsonObject j = LibraryPresets.SHIPPED.read(from);
        return j != null && j.has("title") ? j.get("title").getAsString().strip() : "";
    }

    static String letters(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** "IT'S RAINING CHICKENS!" → "It's raining chickens!"; ends in "!" unless it already ends a sentence. */
    static String sentence(String s) {
        String t = s.strip();
        if (t.isEmpty()) {
            return t;
        }
        boolean shouting = t.equals(t.toUpperCase(Locale.ROOT)) && !t.equals(t.toLowerCase(Locale.ROOT));
        if (shouting) {
            t = t.toLowerCase(Locale.ROOT);
        }
        t = Character.toUpperCase(t.charAt(0)) + t.substring(1);
        char last = t.charAt(t.length() - 1);
        return last == '!' || last == '.' || last == '?' ? t : t + "!";
    }
}
