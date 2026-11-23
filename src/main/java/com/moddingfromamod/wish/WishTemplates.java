package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The offline wish engine: turns a kid's words into a {@link CommandPlan} with no AI, no network and
 * no latency. This is what makes the watch feel instant, and what keeps it working when Claude is
 * slow, offline, or rate-limited.
 *
 * <p>Templates are <b>data</b>, loaded from a JSON string (never a file here, so tests can feed
 * fixtures and this class stays Minecraft-free and pure). That matters beyond testability: when
 * Claude fulfils a wish the templates missed, its <em>preferred</em> output is a <b>new template</b>
 * appended to this list — so the second time the kid asks, the answer is instant and free. The
 * engine teaches itself.
 *
 * <p>Template shape:
 * <pre>{@code
 * {"templates":[{
 *   "id": "rain_chickens",
 *   "all": ["chicken"],                  // every term must appear
 *   "any": ["rain", "sky", "falling"],   // at least one must appear (optional)
 *   "none": ["stop"],                    // none may appear (optional)
 *   "commands": ["summon minecraft:chicken ~ ~12 ~"],
 *   "repeat": 20,                        // run the command list N times
 *   "countable": true,                   // let "spawn 30 chickens" override repeat
 *   "maxRepeat": 60,
 *   "say": "It's raining chickens!"
 * }]}
 * }</pre>
 * {@code {count}} inside a command is replaced with the resolved repeat count.
 */
public final class WishTemplates {

    /** Ceiling on repeats regardless of what a template or the kid asks for. */
    // one wish's wave of ordinary mobs — a countable template ("spawn 30 chickens") is a
    // summon repeated, and the world guard stops an explosive or flying one at its own, smaller wave.
    public static final int HARD_MAX_REPEAT = com.moddingfromamod.mob.SpawnBudget.VANILLA_PER_WISH;

    private final List<Template> templates;

    private WishTemplates(List<Template> templates) {
        this.templates = List.copyOf(templates);
    }

    public int size() {
        return templates.size();
    }

    public List<String> ids() {
        return templates.stream().map(t -> t.id).toList();
    }

    /**
     * Parse templates from JSON. Malformed entries are skipped rather than throwing — one bad
     * template written by an agent must never break every other wish.
     */
    public static WishTemplates fromJson(String json) {
        List<Template> out = new ArrayList<>();
        try {
            JsonElement root = JsonParser.parseString(json == null ? "" : json);
            if (root != null && root.isJsonObject()) {
                JsonElement arr = root.getAsJsonObject().get("templates");
                if (arr != null && arr.isJsonArray()) {
                    for (JsonElement e : arr.getAsJsonArray()) {
                        Template t = Template.parse(e);
                        if (t != null) {
                            out.add(t);
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Unparseable blob → no templates. The caller falls back to "I don't know that one yet".
        }
        return new WishTemplates(out);
    }

    public static WishTemplates empty() {
        return new WishTemplates(List.of());
    }

    /** A matched template: what to run, and <b>which</b> template said so. */
    public record Match(String templateId, CommandPlan plan) {
    }

    /**
     * A template that matches the wish <b>well enough to serve without asking the agent</b>.
     *
     * <p>The distinction is the whole point of the confidence routing: {@link #matchDetailed} is
     * generous and will happily claim "Spikor's airship flying in the sky" for the {@code fly}
     * template, which is exactly how a child ends up flying instead of getting an airship. A
     * confident match additionally proves that nothing meaningful in the wish is left unexplained.
     *
     * @param unexplained the meaningful words the template could not account for; always empty for
     *                    a confident match, and worth telling the agent about otherwise
     */
    public record Confident(String templateId, CommandPlan plan, List<String> unexplained) {
    }

    /**
     * The best template for this wish, <b>only if it is safe to grant offline</b>.
     *
     * <p>Requires whole-word term matches (so "knight" is not "night") and no meaningful leftover
     * words (so "a treasure map to a secret island" is not the treasure wish). Anything short of
     * that returns empty and the wish goes to the agent — the cheap failure, a few seconds, versus
     * a wrong wish that gets cached and served wrongly forever.
     */
    public Optional<Confident> matchConfident(String sanitizedWish) {
        List<String> tokens = WishWords.tokenize(sanitizedWish);
        if (tokens.isEmpty()) {
            return Optional.empty();
        }
        OptionalInt asked = WishText.firstNumber(sanitizedWish);
        for (Template t : templates) {
            if (t.matchesStrict(tokens) && t.unexplained(tokens).isEmpty()) {
                return Optional.of(new Confident(t.id, t.plan(asked), List.of()));
            }
        }
        return Optional.empty();
    }

    /**
     * What the loose matcher <i>would</i> have said, and why it wasn't trusted.
     *
     * <p>Handed to the agent as a hint, so when the guess happens to be right it can reuse that
     * template instead of inventing a duplicate. A hint, never a bypass.
     */
    public Optional<Confident> hint(String sanitizedWish) {
        List<String> tokens = WishWords.tokenize(sanitizedWish);
        String hay = WishText.matchable(sanitizedWish);
        if (tokens.isEmpty() || hay.isEmpty()) {
            return Optional.empty();
        }
        OptionalInt asked = WishText.firstNumber(sanitizedWish);
        for (Template t : templates) {
            if (t.matches(hay)) {
                return Optional.of(new Confident(t.id, t.plan(asked), t.unexplained(tokens)));
            }
        }
        return Optional.empty();
    }

    /**
     * First template whose terms match the wish. Order matters: earlier templates win, so a specific
     * template can be listed above a general one.
     */
    public Optional<CommandPlan> match(String sanitizedWish) {
        return matchDetailed(sanitizedWish).map(Match::plan);
    }

    /**
     * As {@link #match}, but also reports the id of the template that won.
     *
     * <p>The id is what makes a rejection actionable: when the child says a wish came out wrong, we
     * can delete the exact learned template that produced it instead of guessing.
     */
    public Optional<Match> matchDetailed(String sanitizedWish) {
        String hay = WishText.matchable(sanitizedWish);
        if (hay.isEmpty()) {
            return Optional.empty();
        }
        OptionalInt asked = WishText.firstNumber(sanitizedWish);
        for (Template t : templates) {
            if (t.matches(hay)) {
                return Optional.of(new Match(t.id, t.plan(asked)));
            }
        }
        return Optional.empty();
    }

    /**
     * The template with exactly this id — <b>no matching involved</b>.
     *
     * <p>This is what a button press uses. A button is not a guess at what the child meant: it IS
     * the recipe, so re-deriving it from text can only ever lose information. Text matching stays
     * exactly as strict as it was for everything a child actually types.
     */
    public Optional<Match> byId(String templateId, String sanitizedWish) {
        if (templateId == null || templateId.isBlank()) {
            return Optional.empty();
        }
        OptionalInt asked = WishText.firstNumber(sanitizedWish == null ? "" : sanitizedWish);
        // ONE PART of a wish — "<wish>@<part>", the commands of that wish that make only
        // that part. Still verified against the live set: the wish must exist, and so must a
        // command of it that names the part, or there is no recipe (never the whole wish instead).
        String[] part = com.moddingfromamod.marshal.TaughtJoin.splitPart(templateId);
        if (part != null) {
            for (Template t : templates) {
                if (t.id.equals(part[0])) {
                    List<String> only = com.moddingfromamod.marshal.TaughtJoin.commandsFor(
                            t.commands, part[1]);
                    return only.isEmpty() ? Optional.empty()
                            : Optional.of(new Match(templateId, t.plan(asked, only)));
                }
            }
            return Optional.empty();
        }
        for (Template t : templates) {
            if (t.id.equals(templateId)) {
                return Optional.of(new Match(t.id, t.plan(asked)));
            }
        }
        return Optional.empty();
    }

    /** Whether any template claims this wish (used by the tier router). */
    public boolean canHandle(String sanitizedWish) {
        return match(sanitizedWish).isPresent();
    }

    /**
     * One recipe, described the way a child would ask for it.
     *
     * @param phrase the template's own canonical {@code phrase}, or {@code ""} when it has none —
     *               deliberately not defaulted to the id, because "has a phrase" is a real fact
     *               about a curated entry and a caller that wants words can make them itself
     */
    public record Known(String id, String phrase) {
    }

    /**
     * <b>Every recipe this set can serve, as id + phrase.</b>
     *
     * <p>For anything that has to describe the recipes to something outside them (the starter
     * replays and the tired-watch checks read it). {@link #ids()}
     * alone was not enough: an id is a key, not a description, and asking a model to match a
     * child's sentence against {@code night_spooky} is asking it to guess at our spelling. The
     * phrase is already there and already written for exactly this job — it is the confidence
     * matcher's own vocabulary for what the recipe is about.
     *
     * <p>In the set's own order, which is the precedence: library, then learned.
     */
    public List<Known> known() {
        List<Known> out = new ArrayList<>(templates.size());
        for (Template t : templates) {
            out.add(new Known(t.id, t.phrase == null ? "" : t.phrase));
        }
        return List.copyOf(out);
    }

    // ===================== internals =====================

    private record Template(String id, List<String> all, List<String> any, List<String> none,
                            List<String> commands, int repeat, boolean countable, int maxRepeat,
                            String say, String phrase) {

        static Template parse(JsonElement element) {
            if (element == null || !element.isJsonObject()) {
                return null;
            }
            JsonObject o = element.getAsJsonObject();
            String id = str(o, "id");
            List<String> commands = lower(list(o, "commands"), false);
            if (id.isEmpty() || commands.isEmpty()) {
                return null; // a template with nothing to run is not a template
            }
            List<String> all = lower(list(o, "all"), true);
            List<String> any = lower(list(o, "any"), true);
            if (all.isEmpty() && any.isEmpty()) {
                return null; // would match every wish — refuse it
            }
            int repeat = Math.max(1, Math.min(HARD_MAX_REPEAT, intOr(o, "repeat", 1)));
            int maxRepeat = Math.max(1, Math.min(HARD_MAX_REPEAT, intOr(o, "maxRepeat", repeat)));
            return new Template(id, all, any, lower(list(o, "none"), true),
                    list(o, "commands"), repeat, boolOr(o, "countable", false), maxRepeat,
                    str(o, "say"), str(o, "phrase"));
        }

        /**
         * Whole-word matching — the strict half of a confident match.
         *
         * <p>Same shape as {@link #matches}, but every term must appear as an actual word (plurals
         * tolerated) rather than anywhere inside the sentence. This alone is what stops "knight"
         * being night, "breakfast" being fast and "cathedral" being cats.
         */
        boolean matchesStrict(List<String> tokens) {
            for (String term : all) {
                if (!WishWords.hasWord(tokens, term)) {
                    return false;
                }
            }
            for (String term : none) {
                if (WishWords.hasWord(tokens, term)) {
                    return false;
                }
            }
            if (any.isEmpty()) {
                return true;
            }
            for (String term : any) {
                if (WishWords.hasWord(tokens, term)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Meaningful words in the wish this template cannot account for.
         *
         * <p>Its vocabulary is its own terms plus its optional canonical {@code phrase} — the way
         * a child would normally say it. The phrase is what lets "make it rain chickens" be
         * confident (both "rain" and "chickens" are known) while "a treasure map to a secret
         * island" is not (three words the treasure template has never heard of).
         */
        List<String> unexplained(List<String> tokens) {
            return WishWords.unexplained(tokens,
                    WishWords.vocabulary(all, any, List.of(phrase == null ? "" : phrase)));
        }

        boolean matches(String hay) {
            for (String term : all) {
                if (!hay.contains(term)) {
                    return false;
                }
            }
            for (String term : none) {
                if (hay.contains(term)) {
                    return false;
                }
            }
            if (any.isEmpty()) {
                return true;
            }
            for (String term : any) {
                if (hay.contains(term)) {
                    return true;
                }
            }
            return false;
        }

        CommandPlan plan(OptionalInt asked) {
            return plan(asked, commands);
        }

        /** The plan, running only {@code commands} (a part of this wish). */
        CommandPlan plan(OptionalInt asked, List<String> commands) {
            int n = repeat;
            if (countable && asked.isPresent()) {
                n = Math.max(1, Math.min(maxRepeat, asked.getAsInt()));
            }
            n = Math.max(1, Math.min(HARD_MAX_REPEAT, n));
            List<String> out = new ArrayList<>();
            for (int i = 0; i < n && out.size() < CommandPlan.MAX_COMMANDS; i++) {
                for (String c : commands) {
                    if (out.size() >= CommandPlan.MAX_COMMANDS) {
                        break;
                    }
                    out.add(c.replace("{count}", Integer.toString(n)).trim());
                }
            }
            // A part's replay does not speak the whole wish's line ("here is your oasis!").
            return new CommandPlan(out, commands == this.commands ? say : "", out.size());
        }

        private static String str(JsonObject o, String key) {
            JsonElement e = o.get(key);
            return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
        }

        private static int intOr(JsonObject o, String key, int fallback) {
            try {
                JsonElement e = o.get(key);
                return e != null && e.isJsonPrimitive() ? e.getAsInt() : fallback;
            } catch (RuntimeException e) {
                return fallback;
            }
        }

        private static boolean boolOr(JsonObject o, String key, boolean fallback) {
            try {
                JsonElement e = o.get(key);
                return e != null && e.isJsonPrimitive() ? e.getAsBoolean() : fallback;
            } catch (RuntimeException e) {
                return fallback;
            }
        }

        private static List<String> list(JsonObject o, String key) {
            List<String> out = new ArrayList<>();
            JsonElement e = o.get(key);
            if (e != null && e.isJsonArray()) {
                JsonArray arr = e.getAsJsonArray();
                for (JsonElement item : arr) {
                    if (item != null && item.isJsonPrimitive()) {
                        String s = item.getAsString().trim();
                        if (!s.isEmpty()) {
                            out.add(s);
                        }
                    }
                }
            }
            return out;
        }

        private static List<String> lower(List<String> in, boolean lowercase) {
            if (!lowercase) {
                return List.copyOf(in);
            }
            return in.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        }
    }
}
