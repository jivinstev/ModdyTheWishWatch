package com.moddingfromamod.agent;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.moddingfromamod.world.Landing;

/**
 * <b>A Magician wish this world keeps</b> (#70, {@code WorldMakes.landing}): decided ONCE, at the wish's first
 * {@code plan_wish}, and enforced in {@code McpProtocol}'s one dispatch. The wish still does all its work (plan, design,
 * datapack, every safety layer); only the placing is held, and finishing hands him the recipe that makes it again as a
 * hotbar wish. Pure: the rule is a unit test ({@code KeptWishTest}).
 */
public final class KeptWish {

    private KeptWish() {
    }

    /**
     * The kinds a Magician result can be kept as: each has a replay command a taught recipe carries
     * ({@code wish/ReplayContract}): a creature ({@code wishmob spawn}), a building ({@code wishbuild}), an event
     * ({@code moddyevent stage}) and an item ({@code wishdatapack <id> give}). A picture lands (not yet kept), and
     * anything else (a rule, a command, an effect on him) has no recipe that places it, so it lands.
     */
    public static final Set<String> KEEPABLE = Set.of("creature", "building", "event", "item");

    /** Commands that put something in the world or in his hands: never run while a wish is kept. */
    static final Set<String> PLACING = Set.of("summon", "setblock", "fill", "clone", "place", "give", "item", "loot",
            "function", "wishmob", "wishsummon", "wishbuild", "wishpaint", "moddyevent", "wishdatapack");

    /**
     * What this wish does, from what the world asked: {@code asked} itself, or {@link Landing#LANDS} when it is not a
     * new thing ({@code about} is not "new": an edit of his thing lands where it stands) or its kind has no replay
     * recipe (the caller warns). A creature, building or event asked {@code KEPT_AS_ITEM} is kept as {@code KEPT}.
     */
    public static Landing decide(String kind, boolean isNew, Landing asked) {
        if (asked == null || asked == Landing.LANDS || !isNew || kind == null) {
            return Landing.LANDS;
        }
        String k = kind.strip().toLowerCase(Locale.ROOT);
        if (!KEEPABLE.contains(k)) {
            return Landing.LANDS;
        }
        return asked == Landing.KEPT_AS_ITEM && !k.equals("item") ? Landing.KEPT : asked;
    }

    /** What the agent is told on its plan, once: do the work, place nothing, teach it, finish. */
    public static String brief(Landing l) {
        if (l == null || l == Landing.LANDS) {
            return "";
        }
        return "\n\nTHIS WISH IS KEPT BY THIS WORLD: do all the work (design it, make it, save it) but PLACE NOTHING. "
                + "sculpt_creature and define_creature with spawn:false, build_structure with build:false, stage_event "
                + "with now:false, make_item with no `at`, and no run_command that summons, sets blocks or gives"
                + (l == Landing.KEPT_AS_ITEM ? " (an item you made may be given to him with its own "
                + "`wishdatapack <id> give`)" : "") + ". Then teach it with add_template (its replay command, e.g. "
                + "`wishmob spawn <id>`, `wishbuild <id>`, `moddyevent stage <id>` or `wishdatapack <id> give`) and "
                + "call finish_wish: finishing puts that recipe on his hotbar as a wish he fires to make it appear.";
    }

    /** The refusal for one tool call of a kept wish, or null when it places nothing. */
    public static String refusal(String tool, JsonObject args, Landing l) {
        if (l == null || l == Landing.LANDS || tool == null) {
            return null;
        }
        JsonObject a = args == null ? new JsonObject() : args;
        String why = switch (tool) {
            case "sculpt_creature", "define_creature" -> isFalse(a, "spawn") ? null : "spawn:false";
            case "build_structure" -> isFalse(a, "build") ? null : "build:false";
            case "stage_event" -> isFalse(a, "now") ? null : "now:false";
            case "make_item" -> a.has("at") ? "no `at`" : null;
            case "paint_surface" -> "no picture";
            case "give_items" -> l == Landing.KEPT_AS_ITEM ? null : "nothing given";
            case "run_command" -> placingCommand(a, l);
            default -> null;
        };
        return why == null ? null : "THIS WISH IS KEPT BY THIS WORLD: nothing is placed, so " + tool + " needs " + why
                + ". Do the work without placing it, teach its replay command with add_template, then finish_wish:"
                + " he gets it as a hotbar wish and it appears where he aims it.";
    }

    private static boolean isFalse(JsonObject a, String key) {
        JsonElement e = a.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && !e.getAsBoolean();
    }

    /** "no placing commands" when any command in {@code command} places something, else null. */
    private static String placingCommand(JsonObject a, Landing l) {
        JsonElement c = a.get("command");
        List<String> cmds = new java.util.ArrayList<>();
        if (c instanceof JsonArray arr) {
            for (JsonElement e : arr) {
                cmds.add(e.isJsonPrimitive() ? e.getAsString() : "");
            }
        } else if (c != null && c.isJsonPrimitive()) {
            cmds.add(c.getAsString());
        }
        for (String cmd : cmds) {
            if (places(cmd, l)) {
                return "no command that summons, sets blocks or gives (`" + cmd.strip() + "` would)";
            }
        }
        return null;
    }

    /** Does this one command place something (an {@code execute … run} is read through to what it runs)? */
    static boolean places(String command, Landing l) {
        String[] w = command == null ? new String[0] : command.strip().replaceFirst("^/", "").split("\\s+");
        for (int i = 0; i < w.length; i++) {
            String word = w[i].toLowerCase(Locale.ROOT);
            boolean head = i == 0 || w[i - 1].equalsIgnoreCase("run");
            if (!head || !PLACING.contains(word)) {
                continue;
            }
            // the one exception: an item kept as the real item is handed over by its own give
            if (word.equals("wishdatapack") && l == Landing.KEPT_AS_ITEM && i + 2 < w.length
                    && w[i + 2].equalsIgnoreCase("give")) {
                continue;
            }
            return true;
        }
        return false;
    }
}
