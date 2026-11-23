package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>An alias is a LOOKUP, never a guess.</b>
 *
 * <p>Why they exist at all, measured rather than supposed. At opus/low x8 on <i>"a pearl that
 * makes the zombie attack the cow"</i> the agent chose the real {@code aggro} <b>once</b> and
 * guessed {@code anger} five times; {@code MakeItemTool}'s own source already records it inventing
 * {@code give} for {@code rich}. Every one of those calls is refused today, and two runs in eight
 * answered the refusal by abandoning the catalogue for {@code author_datapack} — <i>after</i> being
 * shown every effect and what each of them does.
 *
 * <p><b>What was tried instead, and why it is not what shipped.</b> Naming all 18 effects in the
 * {@code effect} schema field fixes that wish completely (1/8 → 8/8, no probing) and breaks the
 * branch beside it: <i>"syrup bottles for all the flavors"</i> goes {@code plain} 8/8 → 1/8 and
 * <i>"wipped cream and a shot of espresso"</i> 8/8 → 0/8. Three framings of that list were measured
 * and all three traded about two wishes away for every one they won. An enumeration inside a field
 * makes the field feel obligatory; aliases change only what happens to calls that are ERRORS today,
 * so they cannot move a wish that never names an effect at all.
 *
 * <p>The invariants below are the ones that make "lookup, never guess" true, and each is a way this
 * could quietly become a wish silently turning into a different wish.
 */
final class EffectAliasesTest {

    private static final ItemCatalogue CATALOGUE = ItemCatalogue.load();

    @Test
    @DisplayName("every alias names exactly one effect, and never shadows a real id")
    void aliasesAreUnambiguous() {
        Map<String, String> owner = new LinkedHashMap<>();
        for (ItemCatalogue.Entry e : CATALOGUE.all()) {
            owner.put(e.id(), e.id());
        }
        List<String> clashes = new ArrayList<>();
        for (ItemCatalogue.Entry e : CATALOGUE.all()) {
            for (String a : e.aka()) {
                String prior = owner.putIfAbsent(a, e.id());
                if (prior != null) {
                    clashes.add("'" + a + "' is claimed by both " + prior + " and " + e.id());
                }
            }
        }
        assertTrue(clashes.isEmpty(), "an alias must resolve to one effect: " + clashes);
    }

    @Test
    @DisplayName("an alias resolves to its effect, and canonical() reports the real id")
    void aliasesResolveAndCanonicalise() {
        for (ItemCatalogue.Entry e : CATALOGUE.all()) {
            for (String a : e.aka()) {
                assertNotNull(CATALOGUE.get(a), "alias '" + a + "' resolves to nothing");
                assertEquals(e.id(), CATALOGUE.canonical(a),
                        "'" + a + "' must canonicalise to " + e.id() + ", or the datapack, the "
                                + "stored spec and inspect_item will disagree about what the "
                                + "child's item does and no later wish can refine it");
            }
            assertEquals(e.id(), CATALOGUE.canonical(e.id()), "an id must canonicalise to itself");
        }
    }

    @Test
    @DisplayName("every effect carries at least one alias — a set with holes is worse than none")
    void everyEffectHasOne() {
        List<String> bare = CATALOGUE.all().stream()
                .filter(e -> e.aka().isEmpty()).map(ItemCatalogue.Entry::id).toList();
        assertTrue(bare.isEmpty(),
                "no alias for: " + bare + ". A new effect inherits the failure this fixes — the "
                        + "agent guesses a name for it and is refused — so the entry is not "
                        + "finished until it says what a child would call it.");
    }

    @Test
    @DisplayName("`attack` is claimed by nobody, because it means two different effects")
    void theAmbiguousWordIsLeftAlone() {
        assertNull(CATALOGUE.get("attack"),
                "said of a sword `attack` means hurt and said of a pearl it means aggro. A lookup "
                        + "that has to guess between two effects is not a lookup — it is a default "
                        + "wearing an alias, and this catalogue has none.");
    }

    @Test
    @DisplayName("an alias can never shadow an effect's own name")
    void realIdsWinOverAliases() {
        for (ItemCatalogue.Entry e : CATALOGUE.all()) {
            assertEquals(e.id(), CATALOGUE.get(e.id()).id(),
                    "looking up '" + e.id() + "' must find the effect called that, whatever any "
                            + "other entry lists as an alias");
        }
    }

    @Test
    @DisplayName("a name nobody claims is still refused")
    void nonsenseIsStillRefused() {
        for (String junk : List.of("zzz_not_a_real_effect", "list_effects_please", "")) {
            assertNull(CATALOGUE.get(junk), "'" + junk + "' must not resolve to anything");
        }
    }
}
