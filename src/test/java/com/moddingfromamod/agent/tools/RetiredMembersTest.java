package com.moddingfromamod.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.item.ItemSpecStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Which members an edit RETIRED —.</b>
 *
 * <p>The design and the datapack already do what he asked: <i>"actually make the grape one of them
 * BUTT CHEEK WATER flavor"</i> swaps the member. The Grape stack he was holding does not know that,
 * so he ends up holding six flavours and one of them is the thing he just replaced. Measured across
 * two runs of the same rung the agent tidied it up in one and not the other, which is
 * probabilistic where the invariant is binary — hence a mechanism rather than a lesson.
 *
 * <p>Everything here is about being NARROW. Clearing "the family" would be one line shorter and
 * would take away flavours he still has; clearing only the difference cannot.
 */
class RetiredMembersTest {

    private static JsonArray variants(String... names) {
        JsonArray a = new JsonArray();
        for (String n : names) {
            JsonObject o = new JsonObject();
            o.addProperty("name", n);
            o.addProperty("colour", "red");
            a.add(o);
        }
        return a;
    }

    private static ItemSpecStore.Spec design(JsonArray family) {
        return new ItemSpecStore.Spec("fizz", "Cherry Fizzlepop Slushie", "cup", "red", "held",
                4, List.of("heal"), new JsonObject(), family);
    }

    private static List<MakeItemTool.Variant> family(String... names) {
        return java.util.Arrays.stream(names)
                .map(n -> new MakeItemTool.Variant(n, 0x112233)).toList();
    }

    @Test
    @DisplayName("a swap retires exactly the member that was swapped out")
    void aSwapRetiresOne() {
        List<String> gone = MakeItemTool.retiredMembers(
                design(variants("Cherry", "Lime", "Grape", "Blueberry")),
                family("Cherry", "Lime", "BUTT CHEEK WATER", "Blueberry"));
        assertEquals(List.of("Grape"), gone);
    }

    @Test
    @DisplayName("ADDING a member retires nothing")
    void addingRetiresNothing() {
        // "can you add a watermelon one too" must never cost him a flavour.
        assertEquals(List.of(), MakeItemTool.retiredMembers(
                design(variants("Cherry", "Lime")),
                family("Cherry", "Lime", "Watermelon")));
    }

    @Test
    @DisplayName("an edit that mentions no family at all retires nothing")
    void anUnmentionedFamilyRetiresNothing() {
        // an earlier issue's fix means `inherit` hands the old family straight back, so before == after. If
        // this ever returned members, "make them all give you way more speed" would silently empty
        // his inventory — the same wish that deleted three flavours the first time round.
        assertEquals(List.of(), MakeItemTool.retiredMembers(
                design(variants("Cherry", "Lime", "Grape", "Blueberry")),
                family("Cherry", "Lime", "Grape", "Blueberry")));
    }

    @Test
    @DisplayName("a one-of-a-kind item is never touched")
    void noFamilyMeansNothingToRetire() {
        // Every item made before families existed, and every ordinary wand made since. A rename of
        // one of these must not clear it: there is no family, so there is nothing retired.
        assertEquals(List.of(), MakeItemTool.retiredMembers(
                design(new JsonArray()), family("Zonkbolt MEGA")));
        assertEquals(List.of(), MakeItemTool.retiredMembers(null, family("Zonkbolt")));
    }

    @Test
    @DisplayName("renaming the whole set retires every old name")
    void aWholeSetRenameRetiresAllOfThem() {
        // Correct rather than surprising: under any reading those stacks are stale, and the new
        // ones arrive on the next give.
        List<String> gone = MakeItemTool.retiredMembers(
                design(variants("Cherry Fizzlepop", "Lime Fizzlepop")),
                family("Cherry Zapfizz", "Lime Zapfizz"));
        assertEquals(List.of("Cherry Fizzlepop", "Lime Fizzlepop"), gone);
    }

    @Test
    @DisplayName("matching ignores case and stray spaces, because the agent's does not")
    void theComparisonIsForgiving() {
        assertEquals(List.of(), MakeItemTool.retiredMembers(
                design(variants(" Cherry Fizzlepop Slushie ")),
                family("cherry fizzlepop slushie")));
    }

    @Test
    @DisplayName("shrinking a set retires all the members it dropped")
    void shrinkingRetiresTheDifference() {
        List<String> gone = MakeItemTool.retiredMembers(
                design(variants("A", "B", "C", "D", "E")), family("A", "C"));
        assertEquals(List.of("B", "D", "E"), gone);
    }

    @Test
    @DisplayName("a damaged member is not evidence that anything retired")
    void aDamagedRecordRetiresNothing() {
        // A half-written store entry must not become a reason to take things out of his inventory.
        JsonArray bent = JsonParser.parseString("[\"Grape\", {\"colour\":\"red\"}, {}]")
                .getAsJsonArray();
        assertTrue(MakeItemTool.retiredMembers(design(bent), family("Cherry")).isEmpty());
    }
}
