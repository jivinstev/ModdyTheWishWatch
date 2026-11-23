package com.moddingfromamod.wish;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * <b>Every creature the wish engine should be able to make.</b>
 *
 * <p>Deliberately broad. Earlier evaluations used three creatures and kept reaching conclusions
 * that did not survive a fourth — a reference that inverted, an occlusion defect that turned out to
 * be a rendering bug. Twenty-three cases across four tiers costs more to run and is the only way to
 * tell a rule that works from a rule that happened to fit.
 *
 * <p>The tiers are about <b>where correctness comes from</b>, which is what decides how a creature
 * can be graded at all:
 *
 * <ul>
 *   <li><b>named</b> — a real subject exists, so a reference can be sourced. Sourcing difficulty
 *       varies enormously and is recorded per case: Spikor is trivially documented, Tralalelo
 *       Tralalala is a meme with no official art, and Omega Lad may have no canonical design at
 *       all — a case included precisely to see whether the agent notices.</li>
 *   <li><b>archetype</b> — a troll, a T-Rex, a fire ghost. Convention, not a reference.</li>
 *   <li><b>variant</b> — a vanilla mob altered ("a teeny baby wither").</li>
 *   <li><b>invented</b> — the child's own words are the entire spec, and are checkable.</li>
 * </ul>
 */
public final class CreatureCorpus {

    public static final String RESOURCE = "/corpus/creatures.jsonl";

    /**
     * The rows whose prompt is a child's own sentence, kept in a private file of the same
     * schema and read after {@link #RESOURCE}. The public cut has no copy, so it loads only the public
     * rows; a test that needs one of these ids skips there by name.
     */
    public static final String OVERLAY = "/corpus/verbatim-creatures.jsonl";

    /**
     * @param named the properties the child actually stated — checkable with no reference at all
     * @param reference the real-world subject, or null when there is none to find
     */
    public record Case(String id, String prompt, String tier, String sourcing,
                       List<String> named, String reference) {

        public boolean needsAReference() {
            return reference != null && !reference.isBlank();
        }
    }

    private CreatureCorpus() {
    }

    public static List<Case> load() {
        List<Case> all = new ArrayList<>(read(RESOURCE));
        all.addAll(read(OVERLAY));   // absent in the public cut: then only the public rows
        return List.copyOf(all);
    }

    private static List<Case> read(String resource) {
        try (InputStream in = CreatureCorpus.class.getResourceAsStream(resource)) {
            return in == null ? List.of()
                    : parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return List.of();
        }
    }

    public static List<Case> parse(String text) {
        List<Case> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            String t = line.strip();
            if (t.isEmpty()) {
                continue;
            }
            try {
                JsonElement el = JsonParser.parseString(t);
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject o = el.getAsJsonObject();
                if (o.has("_comment") || !o.has("id")) {
                    continue;
                }
                List<String> named = new ArrayList<>();
                if (o.has("named") && o.get("named").isJsonArray()) {
                    o.getAsJsonArray("named").forEach(e -> named.add(e.getAsString()));
                }
                out.add(new Case(o.get("id").getAsString(), str(o, "prompt"), str(o, "tier"),
                        str(o, "sourcing"), List.copyOf(named), str(o, "reference")));
            } catch (RuntimeException ignored) {
                // one bad line must not cost the rest
            }
        }
        return List.copyOf(out);
    }

    /**
     * The cases to run: all of them, or a comma-separated subset, capped.
     *
     * <p>A run that covers less than the whole corpus says so in its own report — a silently
     * sampled eval is how "we tested it" comes to mean less than it sounds.
     */
    public static List<Case> select(String only, int limit) {
        List<Case> all = load();
        if (only != null && !only.isBlank()) {
            // SPLIT ON + AS WELL AS COMMA. The client harness passes knobs as
            // -Ptestprops="a=1,b=2" and splits that on commas, so a comma-separated list inside
            // one value is silently truncated to its first element -- "only=sparky,zizzle" ran
            // ONE creature and reported [1/1], which looks like a correct small run rather than a
            // swallowed argument.
            List<String> want = List.of(
                    only.toLowerCase(java.util.Locale.ROOT).split("[,+]"));
            all = all.stream().filter(c -> want.contains(c.id())).toList();
        }
        return all.size() > limit ? all.subList(0, limit) : all;
    }

    public static List<Case> ofTier(String tier) {
        return load().stream().filter(c -> tier.equals(c.tier())).toList();
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }
}
