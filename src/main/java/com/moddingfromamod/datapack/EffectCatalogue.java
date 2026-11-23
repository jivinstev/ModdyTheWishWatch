package com.moddingfromamod.datapack;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * <b>Precomputed creature abilities the agent SELECTS instead of inventing.</b>
 *
 * <p>Design and evidence: docs/EFFECT_CATALOGUE.md. The short version is one measurement — a wish
 * costs a 112s median while compiling costs 881ms, so the expensive part is the agent deciding what
 * to write and writing it. A catalogue removes that turn.
 *
 * <p>This class is the L0/L1 half: read the index, render a template. It deliberately has <b>no
 * Minecraft imports</b>, so the substitution rules — the part that silently produces a datapack
 * aimed at the wrong creature when it is wrong — are unit-testable without booting a game.
 *
 * <p><b>Every failure here is loud.</b> An unknown effect, an unknown parameter, or a placeholder
 * nobody filled in are all exceptions rather than best-effort output. A template rendered with
 * {@code ${TAG}} still in it compiles perfectly well and does nothing, which is the exact class of
 * silent-inert failure this project keeps paying for.
 */
public final class EffectCatalogue {

    private static final String DIR = "/effects/";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z_]+)}");

    private final List<Effect> effects;

    /** One catalogue entry: what it does, what it takes, and which tokens may be substituted. */
    public record Effect(String id, String ability, String summary, String template,
                         Map<String, String> defaults, List<String> params,
                         List<String> substitutable, List<String> wishes,
                         String who, String when, List<String> derived, String childWhen,
                         Map<String, String> paramDocs) {

        /** Everything a template may be filled with: what the agent offers, plus what the tool computes. */
        public List<String> allParams() {
            List<String> out = new ArrayList<>(params);
            out.addAll(derived);
            return out;
        }
    }

    private EffectCatalogue(List<Effect> effects) {
        this.effects = effects;
    }

    public static EffectCatalogue load() {
        try (InputStream in = EffectCatalogue.class.getResourceAsStream(DIR + "catalogue.json")) {
            if (in == null) {
                throw new IllegalStateException("no " + DIR + "catalogue.json on the classpath");
            }
            JsonObject root = JsonParser.parseString(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            List<Effect> out = new ArrayList<>();
            for (var el : root.getAsJsonArray("effects")) {
                JsonObject o = el.getAsJsonObject();
                String id = require(o, "id", "<an entry with no id>");
                Map<String, String> defaults = new LinkedHashMap<>();
                Map<String, String> paramDocs = new LinkedHashMap<>();
                List<String> params = new ArrayList<>();
                JsonObject ps = o.getAsJsonObject("params");
                for (String name : ps.keySet()) {
                    params.add(name);
                    JsonObject spec = ps.getAsJsonObject(name);
                    if (spec.has("default")) {
                        defaults.put(name, spec.get("default").getAsString());
                    }
                    // The ALLOWED VALUES live here ("nudge | shove | launch | huge"). They were
                    // parsed by nothing and shown to nobody, so the agent probed the tool with
                    // FEEL="?" to make the error message tell it -- and when that probe failed it
                    // fell back to a DIFFERENT, WRONG trigger. Good documentation nobody can read
                    // is indistinguishable from none.
                    if (spec.has("doc")) {
                        paramDocs.put(name, spec.get("doc").getAsString());
                    }
                }
                out.add(new Effect(
                        id,
                        require(o, "ability", id),
                        require(o, "summary", id),
                        require(o, "template", id),
                        defaults, params,
                        strings(o.getAsJsonArray("substitutable")),
                        strings(o.getAsJsonArray("wishes")),
                        // WHO the effect lands on is the axis the demand varies along, so it is a
                        // first-class field rather than prose inside the summary. The version of
                        // this file that lacked it shipped an entry doing the OPPOSITE of every
                        // wish it listed.
                        require(o, "who", id),
                        require(o, "when", id),
                        // DERIVED params are filled by the tool, never by the agent. They must be
                        // accepted by render() and kept out of the agent-facing list -- offering a
                        // raw selector or a Motion vector would undo the point of asking for
                        // direction and strength in words.
                        strings(o.getAsJsonArray("derived")),
                        // The sentence the CHILD is told. Separate from `when`, which is
                        // judge-facing and names parameters a seven-year-old cannot read.
                        require(o, "child_when", id),
                        paramDocs));
            }
            return new EffectCatalogue(out);
        } catch (Exception e) {
            throw new IllegalStateException("could not read the effect catalogue: " + e, e);
        }
    }

    private static List<String> strings(JsonArray arr) {
        List<String> out = new ArrayList<>();
        if (arr != null) {
            arr.forEach(e -> out.add(e.getAsString()));
        }
        return out;
    }

    public List<Effect> all() {
        return List.copyOf(effects);
    }

    public Effect get(String id) {
        return effects.stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
    }

    /** The raw template source, placeholders intact. */
    public String templateSource(String id) {
        Effect effect = get(id);
        if (effect == null) {
            throw new IllegalArgumentException("no effect '" + id + "' in the catalogue; have: "
                    + effects.stream().map(Effect::id).toList());
        }
        try (InputStream in = EffectCatalogue.class.getResourceAsStream(
                DIR + effect.template())) {
            if (in == null) {
                throw new IllegalStateException("catalogue names template '" + effect.template()
                        + "' but it is not on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("could not read template for '" + id + "': " + e, e);
        }
    }

    /**
     * Fill a template in. Defaults apply; unknown parameters and unfilled placeholders both throw.
     *
     * <p>Rejecting an unknown parameter matters more than it looks: silently ignoring
     * {@code DURATION} when the template wants {@code SECONDS} produces a perfectly valid datapack
     * that lasts for the default time, and the child simply gets the wrong answer with no error
     * anywhere.
     */
    public String render(String id, Map<String, String> args) {
        return fill(templateSource(id), filled(id, args), id);
    }

    /**
     * The same render with the player's values taken out: the key of its precompiled pack. Its
     * {@link ReadyMadeSkeleton.Skeleton#fill()} is exactly {@link #render} of the same arguments.
     */
    public ReadyMadeSkeleton.Skeleton skeleton(String id, Map<String, String> args) {
        Map<String, String> filled = filled(id, args);
        String src = templateSource(id);
        fill(src, filled, id);   // the same refusals as render
        return ReadyMadeSkeleton.of(src, filled);
    }

    /** Every value a template is filled with: the defaults, the arguments, and what is derived from them. */
    private Map<String, String> filled(String id, Map<String, String> args) {
        Effect effect = get(id);
        if (effect == null) {
            throw new IllegalArgumentException("no effect '" + id + "' in the catalogue");
        }
        for (String given : args.keySet()) {
            if (!effect.allParams().contains(given)) {
                throw new IllegalArgumentException("effect '" + id + "' has no parameter '" + given
                        + "'; it takes " + effect.params());
            }
        }
        Map<String, String> filled = new LinkedHashMap<>(effect.defaults());
        filled.putAll(args);

        String src = templateSource(id);
        // WHO it lands on (chunk 2): the template's ${VICTIMS} is derived from the TARGET
        // word, here, so every caller — the tool, a test rendering a template directly — gets the
        // same rule and the same default (never the child unless asked).
        if (src.contains("${VICTIMS}") && !filled.containsKey("VICTIMS")) {
            String clause = EffectVictims.clause(filled.get("TARGET"));
            if (clause == null) {
                throw new IllegalArgumentException("effect '" + id + "' TARGET '" + filled.get("TARGET")
                        + "' is not a word it knows: say " + EffectVictims.WORDS);
            }
            filled.put("VICTIMS", clause);
        }
        // How far a creature walks between steps (on_step), in the tenths of a block it measures in.
        if (src.contains("${STRIDE_TENTHS}") && !filled.containsKey("STRIDE_TENTHS")) {
            double stride;
            try {
                stride = Double.parseDouble(filled.getOrDefault("STRIDE", "2"));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("effect '" + id + "' STRIDE must be a number of blocks, not '"
                        + filled.get("STRIDE") + "'");
            }
            filled.put("STRIDE_TENTHS", String.valueOf(Math.max(5, Math.round(stride * 10))));
        }
        return filled;
    }

    private static String fill(String src, Map<String, String> filled, String id) {
        Matcher m = PLACEHOLDER.matcher(src);
        StringBuilder sb = new StringBuilder();
        List<String> missing = new ArrayList<>();
        while (m.find()) {
            String name = m.group(1);
            String value = filled.get(name);
            if (value == null) {
                missing.add(name);
                value = m.group(0);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        if (!missing.isEmpty()) {
            // A template rendered with ${TAG} still in it COMPILES, and then does nothing at all.
            throw new IllegalArgumentException("effect '" + id + "' still has unfilled "
                    + "placeholder(s) " + missing + " — it would compile cleanly and do nothing");
        }
        return sb.toString();
    }

    /**
     * Read a required string, naming the entry when it is absent.
     *
     * <p>Not defensive decoration: omitting {@code template} from one appended entry made EVERY
     * caller of {@link #load()} throw, which surfaced as seventeen unrelated GameTests failing at
     * once with no mention of the catalogue. The failure was loud, which is right, but it pointed
     * everywhere except at the one line that caused it.
     */
    private static String require(com.google.gson.JsonObject o, String key, String id) {
        if (!o.has(key) || o.get(key).isJsonNull()) {
            throw new IllegalStateException("effect '" + id + "' in catalogue.json is missing the "
                    + "required key '" + key + "'");
        }
        return o.get(key).getAsString();
    }
}
