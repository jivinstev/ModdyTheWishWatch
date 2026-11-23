package com.moddingfromamod.wishbuilder;

import static com.moddingfromamod.wishbuilder.BuilderSpecTest.j;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * step 5: an idea for changing his Builder make is one the editor REALLY accepts (so one sent as it is never
 * escalates), said in words a child reads, and varied (to show him the breadth of what Moddy can do).
 */
class EditIdeasTest {

    static final String CAT = "{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\",\"size\":2,\"name\":\"Sparky\"}";

    @Test
    void everyIdeaOfferedIsOneTheEditorAccepts() {
        List<String> wrong = new ArrayList<>();
        for (String example : BuilderExamples.ONE_PER_KIND) {
            BuilderSpec spec = BuilderSpec.read(j(example));
            if (Catalogue.prefix(spec.from()).equals("mob") || spec.kind() == BuilderSpec.Kind.SUPPLIES) {
                continue;
            }
            for (int seed = 0; seed < 8; seed++) {
                EditIdeas.Idea idea = EditIdeas.pick(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED, "x_1", spec, new Random(seed));
                if (idea != null && !BuilderEdit.apply(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED, "x_1", spec, idea.ops()).ok()) {
                    wrong.add(spec.kind().id() + ": " + idea.words());
                }
            }
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    @Test
    void ideasAreVariedAndNameHisThing() {
        Set<String> seen = new HashSet<>();
        for (int seed = 0; seed < 30; seed++) {
            EditIdeas.Idea idea = EditIdeas.pick(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED, "x_1",
                    BuilderSpec.read(j(CAT)), new Random(seed));
            assertNotNull(idea);
            assertTrue(idea.words().contains("Sparky"), idea.words());
            seen.add(idea.words());
        }
        assertTrue(seen.size() >= 8, "breadth, not one idea over and over: " + seen);
    }

    @Test
    void noIdeaRepeatsWhatItAlreadyIs() {
        BuilderSpec flier = BuilderSpec.read(j("{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\","
                + "\"powers\":[\"power:flies\"],\"temper\":\"pet\",\"scheme\":\"ocean\",\"name\":\"Zip\"}"));
        for (EditIdeas.Idea idea : EditIdeas.candidates(flier)) {
            assertFalse(idea.words().equals("Make Zip fly") || idea.words().equals("Make Zip follow me")
                    || idea.words().contains("ocean"), idea.words());
        }
    }

    /**
     * (first person, "make my"): every idea for every kind is said as he would type it — a Moddy name
     * is "my" and short, never two capitalised nouns glued together — and the re-wish reader is SURE it changes that
     * same thing, so an idea sent as it is still takes the instant path.
     */
    @Test
    void everyIdeaIsHisOwnSentenceAndTheReaderIsSureOfIt() {
        RewishReader reader = new RewishReader(BuilderTree.of(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED));
        List<String> wrong = new ArrayList<>();
        for (String example : BuilderExamples.ONE_PER_KIND) {
            BuilderSpec raw = BuilderSpec.read(j(example));
            if (Catalogue.prefix(raw.from()).equals("mob") || raw.kind() == BuilderSpec.Kind.SUPPLIES) {
                continue;
            }
            BuilderSpec spec = raw.withName(BuilderNames.name(raw, LibraryPresets.SHIPPED, List.of()));
            if (spec.name().isBlank()) {
                continue;   // a kept make always has a name
            }
            String what = switch (spec.kind()) {
                case CREATURE, RIDE -> "creature";
                case BUILDING -> "building";
                default -> "item";
            };
            for (EditIdeas.Idea idea : EditIdeas.candidates(spec, LibraryPresets.SHIPPED)) {
                String w = idea.words();
                if (!w.startsWith("Make ") && !w.startsWith("Give ") || w.contains(" your ")) {
                    wrong.add("not first person: " + w);
                }
                if (w.matches(".*[a-z] [A-Z][a-z]+ [A-Z].*")) {
                    wrong.add("two names glued: " + w);
                }
                if (!BuilderEdit.apply(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED, "x_1", spec, idea.ops()).ok()) {
                    continue;   // pick() never offers one the editor refuses
                }
                RewishReader.Answer a = reader.read(w, List.of(new RewishReader.Thing("x_1", what, spec.name())), List.of(), "x_1");
                if (a.band() != RewishReader.Band.SURE || a.intent() != RewishReader.Intent.EDIT || !"x_1".equals(a.label())) {
                    wrong.add("not sure: '" + w + "' about '" + spec.name() + "' -> " + a.intent() + " " + a.band() + " " + a.label());
                }
            }
        }
        assertTrue(wrong.isEmpty(), wrong.size() + "\n" + String.join("\n", wrong));
    }
}
