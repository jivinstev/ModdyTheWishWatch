package com.moddingfromamod.wish;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * <b>The stress corpus — hundreds of wishes, across every class and across the seams between
 * them.</b>
 *
 * <p>A train corpus (removed with Create) proved the shape on one wish class and immediately found a bug the hand
 * sweep had missed. This generalises it, and widens it in the direction that matters: <b>the
 * cross-class cases</b>. Every existing gate builds one artifact in an empty plot. Real play does
 * not: a castle gets a train station, a dragon is asked to guard the tower that was built a minute
 * ago, a machine is put inside a building. Nothing tests two artifacts sharing a world, and that is
 * where anchor collisions, footprint overlap, registry key reuse and interleaved time-sliced builds
 * all live.
 *
 * <p><b>Why the wishes are mined from a mod repo.</b> Waiting for a child to type enough wishes
 * to find the bugs means shipping the bugs to him first. A mod repo built for a child holds
 * hundreds of commits of features he actually asked for over months. Read as wishes, that history
 * is the corpus we would otherwise have to wait a year to collect.
 *
 * <p>Pure and Minecraft-free, so the loader is unit-tested without a game. Each case's {@code spec}
 * is deliberately an opaque object: the harness for a given kind interprets it, so adding a new
 * wish class needs no change here.
 */
public final class WishCorpus {

    /**
     * Corpus files, read from {@code /corpus/manifest.json} rather than listed here.
     *
     * <p>A hand-kept list is how {@code creatures.jsonl} — 23 real creature wishes — sat outside
     * this loader for its whole life while nothing noticed, and how {@code datapacks.jsonl}, which
     * is 154 CBScript compiler regression records rather than wishes at all, got counted as demand
     * by a tool that globbed the directory instead. The manifest is the one declaration, read by
     * this and by {@code tools/rank-wishes.py}, so the two cannot drift.
     */
    public static final List<String> RESOURCES = manifestFiles();

    private static List<String> manifestFiles() {
        try (InputStream in = WishCorpus.class.getResourceAsStream("/corpus/manifest.json")) {
            if (in == null) {
                return List.of("/corpus/wishes.jsonl");   // never silently load nothing
            }
            JsonObject root = JsonParser.parseString(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            List<String> out = new ArrayList<>();
            for (JsonElement el : root.getAsJsonArray("files")) {
                JsonObject o = el.getAsJsonObject();
                if (o.has("loadIntoStress") && o.get("loadIntoStress").getAsBoolean()) {
                    out.add("/corpus/" + o.get("file").getAsString());
                }
            }
            return List.copyOf(out);
        } catch (Exception e) {
            // A damaged manifest must not silently empty the corpus, which would turn every gate
            // below it into a vacuous pass.
            return List.of("/corpus/wishes.jsonl");
        }
    }

    /**
     * One wish, as a replayable case.
     *
     * @param id      stable identifier, used in failure messages and to replay one case
     * @param prompt  what a child would type — misspellings included, because those are the real
     *                inputs and a cleaned-up paraphrase is a case we invented
     * @param source  where it came from (a mod repo commit, a real play session, or an axis
     *                we deliberately wanted covered)
     * @param kind    the wish class: buildings / creatures / datapacks /
     *                composite, or a kind we cannot yet grant (recorded anyway — see
     *                {@link #unsupported(List)})
     * @param axes    what makes this case different from its neighbours
     * @param spec    class-specific instructions for the harness; opaque here on purpose
     * @param expect  what must be true afterwards; opaque here for the same reason
     */
    public record Case(String id, String prompt, String source, String kind,
                       Map<String, String> axes, JsonObject spec, JsonObject expect,
                       WishProvenance provenance) {

        /**
         * May this case be counted when asking "what should we build next?".
         *
         * <p>Only a wish a child actually made, or one recovered from what he asked for in Battle
         * of Lord. A case written to exercise a subsystem is coverage, not demand — see
         * {@link WishProvenance}.
         */
        public boolean isDemandEvidence() {
            return provenance != null && provenance.isDemandEvidence();
        }

        /** True for a wish the engine cannot express today — carried as a coverage gap, not a test. */
        public boolean isUnsupported() {
            return "unsupported".equals(kind);
        }

        /** For an unsupported case, the wish class it is asking for. */
        public String wants() {
            return spec != null && spec.has("wants") ? spec.get("wants").getAsString() : "";
        }

        public boolean flag(String key) {
            return expect != null && expect.has(key) && expect.get(key).getAsBoolean();
        }

        public String str(JsonObject o, String key) {
            return o != null && o.has(key) && o.get(key).isJsonPrimitive()
                    ? o.get(key).getAsString() : null;
        }
    }

    private WishCorpus() {
    }

    /** Every case from every bundled corpus file. Never throws. */
    public static List<Case> load() {
        List<Case> all = new ArrayList<>();
        for (String res : RESOURCES) {
            try (InputStream in = WishCorpus.class.getResourceAsStream(res)) {
                if (in != null) {
                    all.addAll(parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
                }
            } catch (Exception ignored) {
                // A damaged corpus file must not take the others with it.
            }
        }
        return List.copyOf(all);
    }

    /**
     * Parse JSONL. Blank lines, {@code _comment} records and unparseable lines are skipped, so the
     * file can carry its own instructions and one mangled line cannot lose the rest.
     */
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
                Map<String, String> axes = new LinkedHashMap<>();
                if (o.has("axes") && o.get("axes").isJsonObject()) {
                    o.getAsJsonObject("axes").entrySet()
                            .forEach(e -> axes.put(e.getKey(), e.getValue().getAsString()));
                }
                out.add(new Case(
                        o.get("id").getAsString(),
                        text(o, "prompt"),
                        text(o, "source"),
                        text(o, "kind") == null ? "unknown" : text(o, "kind"),
                        Map.copyOf(axes),
                        // SPEC OR BUILD. The old train corpus (gone with Create) named its
                        // payload "build"; wishes.jsonl says "spec". Both are still read, so a
                        // corpus file in the older shape loads rather than looking like an orphan.
                        o.has("spec") && o.get("spec").isJsonObject()
                                ? o.getAsJsonObject("spec")
                                : o.has("build") && o.get("build").isJsonObject()
                                        ? o.getAsJsonObject("build") : new JsonObject(),
                        o.has("expect") && o.get("expect").isJsonObject()
                                ? o.getAsJsonObject("expect") : new JsonObject(),
                        // Declared per case. Falls back to reading the free-text source only for
                        // anything written before the field existed; new cases state it.
                        o.has("provenance")
                                ? WishProvenance.parse(text(o, "provenance"))
                                : WishProvenance.fromSource(text(o, "source"))));
            } catch (RuntimeException ignored) {
                // keep going
            }
        }
        return List.copyOf(out);
    }

    private static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    public static List<Case> ofKind(List<Case> all, String kind) {
        return all.stream().filter(c -> kind.equals(c.kind())).toList();
    }

    /**
     * The wishes we cannot grant yet.
     *
     * <p>Kept IN the corpus rather than in a separate wishlist, deliberately. A wish the engine
     * cannot express is a coverage gap with a real child behind it, and filing it next to the ones
     * that pass is what stops it being forgotten — the same reason the guardrail matrix records a
     * GAP rather than omitting the cell.
     */
    public static List<Case> unsupported(List<Case> all) {
        return all.stream().filter(Case::isUnsupported).toList();
    }

    /** Unsupported wishes grouped by the class they are asking for, commonest first. */
    public static List<Map.Entry<String, Long>> demandByWishClass(List<Case> all) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Case c : unsupported(all)) {
            counts.merge(c.wants(), 1L, Long::sum);
        }
        return counts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .toList();
    }
}
