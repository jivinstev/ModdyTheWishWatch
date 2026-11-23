package com.moddingfromamod.species;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.CorpusSource;
import com.moddingfromamod.wishbuilder.BuilderLowering;
import com.moddingfromamod.wishbuilder.BuilderSpec;
import com.moddingfromamod.wishbuilder.LibraryPresets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import com.moddingfromamod.UsesCorpus;

/**
 * the Builder's behaviour chooser is the Python reference's, on every creature Sonnet labelled
 * ({@code corpus/behaviour-choice/expected.jsonl}, written by {@code tools/behaviour-train.py}), through the REAL
 * lowering, so how a body's facts are read (does it fly, swim, park) is held as well as the choice itself.
 */
@UsesCorpus
class BehaviourChoiceTest {

    /**
     * Every labelled creature's rule and the reference's answer, through {@link CorpusSource}: the real corpus
     * here; in the public repo, {@code corpus/fixtures/behaviour-choice/}, 80 creatures drawn by
     * {@code tools/behaviour-specs.py} with a fresh seed (none of the real specs) and answered by the same reference.
     * Counts measured on the real corpus hold only there; the fixture's floor is its own.
     */
    private static Path expected() {
        return CorpusSource.path("behaviour-choice/expected.jsonl");
    }

    private static List<JsonObject> rows() throws IOException {
        List<JsonObject> out = new ArrayList<>();
        for (String l : Files.readAllLines(expected())) {
            if (!l.isBlank()) {
                out.add(JsonParser.parseString(l).getAsJsonObject());
            }
        }
        return out;
    }

    private static String ownLife(String from) throws IOException {
        String[] kn = from.split("/", 2);
        Path p = Path.of("src/main/resources", kn[0].equals("species") ? "starter-library/species" : "builder-bodies/species",
                kn[1], "sculpt.json");
        if (!Files.exists(p)) {
            return null;
        }
        JsonObject j = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
        return j.has("behaves_like") ? j.get("behaves_like").getAsString() : null;
    }

    private static BehaviourChoice.Pick pick(JsonObject s) throws IOException {
        Set<String> powers = new HashSet<>();
        s.getAsJsonArray("powers").forEach(e -> powers.add(e.getAsString()));
        return new BehaviourChoice.Pick(s.get("from").getAsString(), ownLife(s.get("from").getAsString()),
                s.get("body_flies").getAsBoolean(), s.get("body_swims").getAsBoolean(), s.get("vehicle").getAsBoolean(),
                s.get("kind").getAsString().equals("ride"), powers, s.get("temper").getAsString(),
                s.get("size").getAsInt(), s.getAsJsonArray("abilities").size() > 0);
    }

    @Test
    void theModelIsInTheJar() {
        assertTrue(BehaviourChoice.modelLoaded(), "src/main/resources/behaviour/choice.json did not load");
    }

    @Test
    void theJavaChoosesWhatThePythonReferenceChose() throws IOException {
        List<String> wrong = new ArrayList<>();
        List<JsonObject> rows = rows();
        assertTrue(rows.size() >= (CorpusSource.real() ? 700 : 60), "expected.jsonl holds " + rows.size() + " creatures");
        for (JsonObject r : rows) {
            JsonObject s = r.getAsJsonObject("spec");
            BehaviourChoice.Pick p = pick(s);
            String rule = BehaviourChoice.rule(p);
            String got = BehaviourChoice.choose(p).key();
            if (!rule.equals(r.get("rule").getAsString()) || !got.equals(r.get("choice").getAsString())) {
                wrong.add(r.get("id").getAsString() + " " + s.get("from").getAsString() + ": rule " + rule + " (want "
                        + r.get("rule").getAsString() + "), choice " + got + " (want " + r.get("choice").getAsString() + ")");
            }
        }
        assertTrue(wrong.isEmpty(), wrong.size() + " differ:\n" + String.join("\n", wrong.subList(0, Math.min(20, wrong.size()))));
    }

    @Test
    void theHardRulesHoldOnEveryCreature() throws IOException {
        List<String> broken = new ArrayList<>();
        for (JsonObject r : rows()) {
            BehaviourChoice.Pick p = pick(r.getAsJsonObject("spec"));
            String b = BehaviourChoice.choose(p).key();
            String id = r.get("id").getAsString() + " " + p.from() + " → " + b;
            if (p.flies() && !BehaviourChoice.FLYING.contains(b)) {
                broken.add(id + ": it flies and its life does not");
            }
            if (!p.flies() && (b.equals("buzzer") || b.equals("dragon"))) {
                broken.add(id + ": it does not fly and its life does");
            }
            if (p.vehicle() != b.equals("vehicle")) {
                broken.add(id + ": vehicle mismatch");
            }
            if (p.bodySwims() && !p.flies() && !b.equals("swimmer")) {
                broken.add(id + ": a sea body that does not swim");
            }
            if (p.ownLife() != null && p.temper().equals("friendly") && !p.ride() && p.powers().isEmpty()
                    && !b.equals(p.ownLife())) {
                broken.add(id + ": its own life (" + p.ownLife() + ") was changed with nothing changed");
            }
        }
        assertTrue(broken.isEmpty(), String.join("\n", broken));
    }

    /**
     * The quality bar (corpus/behaviour-choice/floor.json, written by tools/behaviour-train.py --record): the committed
     * model's agreement with Sonnet on the FINAL sealed set and on the Apprentice set is what the floor records, and the
     * model is within gap_max points of Sonnet's own consistency. A retrain that does not re-record, or a floor edited by
     * hand, fails here by name.
     */
    @Test
    void theCommittedModelMeetsItsFloor() throws IOException {
        JsonObject floor = JsonParser.parseString(Files.readString(CorpusSource.path("behaviour-choice/floor.json"))).getAsJsonObject();
        for (String tag : List.of("final", "apprentice")) {
            if (!floor.has(tag)) {
                continue;
            }
            JsonObject f = floor.getAsJsonObject(tag);
            String set = f.get("set").getAsString();
            java.util.Map<String, Set<String>> gold = new java.util.HashMap<>();
            java.util.Map<String, List<String>> runs = new java.util.HashMap<>();
            for (String l : Files.readAllLines(CorpusSource.path("behaviour-choice/labels-" + set + ".jsonl"))) {
                if (l.isBlank()) {
                    continue;
                }
                JsonObject r = JsonParser.parseString(l).getAsJsonObject();
                String b = r.get("behaviour").getAsString();
                runs.computeIfAbsent(r.get("id").getAsString(), k -> new ArrayList<>()).add(b);
                if (!b.equals("unsure")) {
                    gold.computeIfAbsent(r.get("id").getAsString(), k -> new HashSet<>()).add(b);
                }
            }
            int agree = 0;
            int n = 0;
            for (JsonObject r : rows()) {
                if (!r.get("set").getAsString().equals(set)) {
                    continue;
                }
                Set<String> g = gold.get(r.get("id").getAsString());
                if (g == null || g.isEmpty()) {
                    continue;
                }
                n++;
                if (g.contains(BehaviourChoice.choose(pick(r.getAsJsonObject("spec"))).key())) {
                    agree++;
                }
            }
            assertEquals(f.get("specs").getAsInt(), n, tag + ": the floor was recorded on another set. Re-run tools/behaviour-train.py --record");
            assertEquals(f.get("agree").getAsInt(), agree, tag + ": the committed model agrees on " + agree + "/" + n
                    + ", the floor says " + f.get("agree").getAsInt() + ". Re-run tools/behaviour-train.py --record (the floor rule decides)");
            if (tag.equals("final")) {
                long twice = runs.values().stream().filter(v -> v.size() >= 2).count();
                long same = runs.values().stream().filter(v -> v.size() >= 2 && new HashSet<>(v).size() == 1).count();
                double gap = 100.0 * same / twice - 100.0 * agree / n;
                assertTrue(gap <= floor.get("gap_max").getAsDouble(), "the model is " + gap + " points under Sonnet's own consistency");
            }
        }
    }

    /** What the owner asked for by name: a cat comes to you; a flying cat flies. */
    @Test
    void theCatStaysAPackHunterAndAFlyingCatFlies() {
        var cat = new BehaviourChoice.Pick("species/cat", null, false, false, false, false, Set.of(), "friendly", 2, false);
        assertEquals(Archetype.PACK_HUNTER, BehaviourChoice.choose(cat));
        var flying = new BehaviourChoice.Pick("species/cat", null, false, false, false, false, Set.of("power:flies"),
                "friendly", 2, false);
        assertTrue(BehaviourChoice.FLYING.contains(BehaviourChoice.choose(flying).key()), BehaviourChoice.choose(flying).key());
    }

    /** The lowering puts the chosen behaviour on the sculpt call, for every creature the Builder can lower. */
    @Test
    void theBuilderLoweringNamesTheChosenBehaviour() throws IOException {
        List<String> wrong = new ArrayList<>();
        int lowered = 0;
        int refused = 0;
        for (JsonObject r : rows()) {
            JsonObject s = r.getAsJsonObject("spec");
            JsonObject spec = new JsonObject();
            spec.addProperty("builder", 1);
            spec.addProperty("kind", s.get("kind").getAsString());
            spec.addProperty("from", "preset:" + s.get("from").getAsString());
            spec.addProperty("size", s.get("size").getAsInt());
            spec.addProperty("temper", s.get("temper").getAsString());
            JsonArray powers = new JsonArray();
            s.getAsJsonArray("powers").forEach(powers::add);
            s.getAsJsonArray("abilities").forEach(powers::add);
            spec.add("powers", powers);
            List<BuilderLowering.Call> calls;
            try {
                calls = BuilderLowering.lower(BuilderSpec.read(spec), "t_1", LibraryPresets.SHIPPED);
            } catch (IllegalArgumentException e) {
                refused++;
                continue;
            }
            lowered++;
            String got = calls.stream().filter(c -> c.tool().equals("sculpt_creature")).map(c -> c.args())
                    .filter(a -> a.has("behaves_like")).map(a -> a.get("behaves_like").getAsString()).findFirst().orElse("(none)");
            if (!got.equals(r.get("choice").getAsString())) {
                wrong.add(r.get("id").getAsString() + " " + s + " → " + got + ", want " + r.get("choice").getAsString());
            }
        }
        assertTrue(lowered >= (CorpusSource.real() ? 700 : 60) && refused <= lowered / 20, lowered + " lowered, " + refused + " refused");
        assertTrue(wrong.isEmpty(), wrong.size() + " differ:\n" + String.join("\n", wrong.subList(0, Math.min(15, wrong.size()))));
    }
}
