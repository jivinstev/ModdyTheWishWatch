package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.safety.SafetyLexicon;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Moddy names what the Builder makes, from the picks, so two of his things read differently. */
class BuilderNamesTest {

    static final LibraryPresets P = LibraryPresets.SHIPPED;

    static BuilderSpec spec(BuilderSpec.Kind kind, String from, String scheme, int size, List<String> powers,
                            String temper, Map<String, String> opts) {
        return new BuilderSpec(BuilderSpec.CURRENT, kind, from, scheme, size, powers, temper, 1, "", "", opts);
    }

    static BuilderSpec tower(int floors, String scheme, String roof, String windows, String outside) {
        return spec(BuilderSpec.Kind.BUILDING, "", scheme, 2, List.of(), "",
                BuilderHouses.opts("tower", floors, roof, "front", windows, outside));
    }

    @Test
    void aThreeStoreySunsetTowerIsNotJustATower() {
        // The example: a three-storey tower came back "Tower" however it was tuned.
        assertEquals("Tall Sunset Tower", BuilderNames.name(tower(3, "sunset", "flat", "some", "nothing"), P, List.of()));
        assertEquals("Tower", BuilderNames.name(tower(2, "", "flat", "some", "nothing"), P, List.of()),
                "nothing picked that sets it apart: its noun");
        assertTrue(BuilderNames.name(tower(3, "", "dome", "some", "nothing"), P, List.of()).contains("Domed"));
    }

    @Test
    void aRedFireBreathingDragonIsNamedForItsFire() {
        BuilderSpec dragon = spec(BuilderSpec.Kind.CREATURE, "preset:species/pet_dragon", "lava", 2,
                List.of("ability:fire_breath"), "friendly", Map.of());
        String n = BuilderNames.name(dragon, P, List.of());
        assertTrue(BuilderNames.PET_NAMES.get("fire").stream().anyMatch(n::startsWith), n);
        assertTrue(n.endsWith(" Dragon"), n);
    }

    @Test
    void theSamePicksAlwaysMakeTheSameName() {
        Random r = new Random(7);
        for (int i = 0; i < 200; i++) {
            BuilderRandomWalk.Walk w = BuilderRandomWalk.walk(r, BuilderAllowlist.REVIEWED, P);
            if (w != null) {
                assertEquals(w.spec().name(), BuilderNames.name(w.spec(), P, List.of()), w.picked().toString());
            }
        }
    }

    @Test
    void everyRandomMakeHasAValidNameAndNoKindIsLeftOnItsNoun() {
        Random r = new Random(2160);
        Map<BuilderSpec.Kind, Set<String>> byKind = new LinkedHashMap<>();
        int walks = 0;
        for (int i = 0; i < 2000 && walks < 600; i++) {
            BuilderRandomWalk.Walk w = BuilderRandomWalk.walk(r, BuilderAllowlist.REVIEWED, P);
            if (w == null) {
                continue;
            }
            walks++;
            String n = w.spec().name();
            if (w.kind() == BuilderSpec.Kind.RULE || w.kind() == BuilderSpec.Kind.EVENT || w.kind() == BuilderSpec.Kind.SUPPLIES) {
                assertEquals("", n, "a rule, an event and supplies (#2182) are called by their own words");
                continue;
            }
            assertTrue(BuilderNames.valid(n), w.kind() + " " + w.picked() + ": '" + n + "'");
            byKind.computeIfAbsent(w.kind(), k -> new HashSet<>()).add(n);
        }
        // The old picker's failure, as a number: a kind whose every make had one of a handful of names.
        byKind.forEach((k, names) -> assertTrue(names.size() >= 10, k + " has only " + names));
    }

    @Test
    void aNameOnHisShelfMovesTheNewOneOn() {
        BuilderSpec t = tower(3, "sunset", "flat", "some", "nothing");
        List<String> shelf = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 12; i++) {
            String n = BuilderNames.name(t, P, shelf);
            assertTrue(BuilderNames.valid(n), n);
            assertTrue(seen.add(n.toLowerCase()), "a name he already has: " + n + " in " + shelf);
            shelf.add(n);
        }
        assertEquals(List.of("Tall Sunset Tower", "Tall Tower", "Sunset Tower"), shelf.subList(0, 3),
                "the second and third take the next best, as the sample sheet says");
        assertTrue(shelf.stream().anyMatch(n -> n.matches(".* [0-9]+")), "a number is the last resort: " + shelf);
        assertNotEquals("tall sunset tower", BuilderNames.name(t, P, List.of("TALL SUNSET TOWER")).toLowerCase(),
                "case is ignored");
    }

    @Test
    void anEditKeepsAPetsNameButABuildingFollowsItsPicks() {
        BuilderSpec cat = new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.CREATURE, "preset:species/cat", "lava", 2,
                List.of(), "friendly", 1, "", "Ember the Cat", Map.of());
        BuilderFlow f = BuilderFlow.editing(BuilderAllowlist.REVIEWED, P, "cat_1", cat);
        assertEquals("Ember the Cat", f.spec(Map.of("scheme", "ice")).name(), "a pet keeps its name");

        BuilderSpec was = tower(3, "sunset", "flat", "some", "nothing");
        BuilderSpec named = new BuilderSpec(was.version(), was.kind(), was.from(), was.scheme(), was.size(), was.powers(),
                was.temper(), was.count(), was.at(), BuilderNames.name(was, P, List.of()), was.opts());
        BuilderFlow g = BuilderFlow.editing(BuilderAllowlist.REVIEWED, P, "tower_1", named);
        assertEquals("Tall Icy Tower", g.spec(Map.of("scheme", "ice", "floors", "3")).name(),
                "Moddy's name for the old picks follows the new ones");
    }

    @Test
    void everyWordInTheTablesIsCleanAndAName() {
        SafetyLexicon lex = SafetyLexicon.shared();
        List<String> words = new ArrayList<>();
        BuilderNames.PET_NAMES.values().forEach(words::addAll);
        BuilderNames.SCHEME_WORDS.values().forEach(w -> words.addAll(List.of(w)));
        words.addAll(BuilderNames.KIT_WORDS.values());
        words.addAll(BuilderNames.PICK_WORDS.values());
        words.addAll(BuilderNames.BODY_NOUNS.values());
        words.addAll(BuilderNames.TREAT_WORDS.values());
        words.addAll(BuilderNames.ROOF_WORDS.values());
        words.addAll(BuilderNames.WINDOW_WORDS.values());
        words.addAll(BuilderNames.OUTSIDE_WORDS.values());
        words.addAll(BuilderNames.BACKGROUND_WORDS.values());
        BuilderNames.HOUSE_NOUNS.values().forEach(w -> words.addAll(List.of(w)));
        for (String w : words) {
            assertTrue(w.matches("[A-Za-z][A-Za-z ]*"), "letters only: '" + w + "'");
            assertTrue(lex.check(w).isEmpty(), "the lexicon flags a name word: " + w);
        }
        for (Map.Entry<String, java.util.List<String>> e : BuilderNames.PET_NAMES.entrySet()) {
            assertTrue(e.getValue().size() >= 6, e.getKey() + ": a theme needs room for his second and third");
            assertEquals(e.getValue().size(), new HashSet<>(e.getValue()).size(), e.getKey() + " repeats a name");
        }
    }

    @Test
    void namesFromRandomMakesPassTheLexicon() {
        SafetyLexicon lex = SafetyLexicon.shared();
        Random r = new Random(99);
        for (int i = 0; i < 400; i++) {
            BuilderRandomWalk.Walk w = BuilderRandomWalk.walk(r, BuilderAllowlist.REVIEWED, P);
            if (w != null && !w.spec().name().isEmpty()) {
                assertTrue(lex.check(w.spec().name()).isEmpty(), "the lexicon flags " + w.spec().name());
            }
        }
    }

    @Test
    void theTuneDialogAsksNoName() {
        Random r = new Random(3);
        for (int i = 0; i < 100; i++) {
            BuilderRandomWalk.Walk w = BuilderRandomWalk.walk(r, BuilderAllowlist.REVIEWED, P);
            assertFalse(w != null && w.tuned().containsKey("name"), "no name control");
        }
    }
}
