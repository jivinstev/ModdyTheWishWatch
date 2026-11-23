package com.moddingfromamod.agent.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.moddingfromamod.datapack.ItemBase;
import com.moddingfromamod.datapack.ItemCatalogue;
import com.moddingfromamod.item.ItemSpecStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Every kind of item, through every path that names one.</b>
 *
 * <p> was not three bugs, it was one defect with eight front doors: an item id is a string, and
 * every place that took one was written separately, so they disagreed about what a valid one even
 * is. Measured before this class existed, by driving the real {@code derive}:
 *
 * <pre>
 * rich  {ITEM=moddingfromamod:hot_coffee_plain} -&gt; ACCEPTED    &lt;- /give fails, silently
 * rich  {ITEM=moddingfromamod:wish_item}        -&gt; ACCEPTED    &lt;- gives N BLANK items
 * place {BLOCK=not_an_id}                       -&gt; ACCEPTED    &lt;- no check at all
 * </pre>
 *
 * <p>So this is a <b>matrix</b> rather than a list of cases, on the same argument as
 * {@code EveryShapeSurvivesARefinementTest}: a ninth path has to be covered the day it exists, not
 * the next time somebody remembers. Each path declares what it does with each kind of reference,
 * and every cell is asserted.
 *
 * <p><b>What is deliberately NOT here:</b> whether {@code minecraft:cobblestne} is a real item.
 * That needs the live registry and is a Gate B question — see {@code ItemBase}'s own note. A unit
 * test claiming it would make Gate A look like it covers something it cannot.
 */
class ItemRefPathMatrixTest {

    /** What a path is allowed to do with a reference of a given kind. */
    private enum Verdict { ACCEPT, REFUSE }

    /** One place that names an item, and how to drive it. */
    private record Path(String name, String field, java.util.function.Function<String, String> call,
                        Verdict wished, Verdict blank, Verdict malformed) { }

    private static ItemCatalogue.Entry e(String id) {
        return java.util.Objects.requireNonNull(ItemCatalogue.load().get(id), id);   // an alias too (convert)
    }

    /**
     * A real item from a mod that is not ours, taken from the LIVE registry rather than named.
     *
     * <p>Hardcoding {@code create:} or the Mod Repo's namespace would both break the repo-agnostic
     * rule and make this gate a lie on a machine without that mod. There is no registry in a unit
     * test, so the modded row is asserted at Gate B; here it is a well-formed foreign id, which is
     * all a pure parser can be asked about.
     */
    private static final String MODDED_SHAPED = "some_other_mod:its_own_item";

    /**
     * A registry that knows only the ids this test names as real.
     *
     * <p>Stated out loud rather than defaulted to {@code ALWAYS}, because a matrix that silently
     * assumed everything exists would report the typo row as covered while covering nothing —
     * which is the failure shape {@code RegistryExists} was split out to prevent. Whether the LIVE
     * registry is actually the one {@code call} passes is a different question and a Gate B one.
     */
    private static final com.moddingfromamod.datapack.RegistryExists KNOWN =
            id -> java.util.Set.of("minecraft:cocoa_beans", "minecraft:wheat",
                    "minecraft:mushroom_stew", "minecraft:water", MODDED_SHAPED,
                    com.moddingfromamod.datapack.ItemBase.WISH_ITEM,
                    com.moddingfromamod.datapack.VanillaCarrier.ITEM_ID).contains(id);

    /** null when accepted; the refusal otherwise — the shape every path's driver returns. */
    private static String drive(String effect, String shape, Map<String, String> p) {
        try {
            return MakeItemTool.derive(e(effect), shape, p, KNOWN);
        } catch (RuntimeException ex) {
            return "threw: " + ex;
        }
    }

    private static final List<Path> PATHS = List.of(
            new Path("turn_into.FROM", "FROM", ref -> {
                Map<String, String> p = new LinkedHashMap<>();
                p.put("FROM", ref);
                p.put("TO", "minecraft:mushroom_stew");
                return drive("convert", ItemCatalogue.MACHINE, p);
            }, Verdict.ACCEPT, Verdict.REFUSE, Verdict.REFUSE),

            new Path("turn_into.TO", "TO", ref -> {
                Map<String, String> p = new LinkedHashMap<>();
                // NOT cocoa beans: the vanilla row below names those, and a recipe that hands
                // back what it took is refused — which would read as this path rejecting a
                // perfectly good vanilla OUTPUT.
                p.put("FROM", "minecraft:wheat");
                p.put("TO", ref);
                return drive("convert", ItemCatalogue.MACHINE, p);
            }, Verdict.ACCEPT, Verdict.REFUSE, Verdict.REFUSE),

            new Path("rich.ITEM", "ITEM", ref -> {
                Map<String, String> p = new LinkedHashMap<>();
                p.put("ITEM", ref);
                p.put("COUNT", "5");
                return drive("rich", "held", p);
            }, Verdict.ACCEPT, Verdict.REFUSE, Verdict.REFUSE),

            // A BLOCK IS NOT AN ITEM, and that is the useful half of its refusal: the id is
            // well-formed, the thing simply is not a block and never will be.
            new Path("place.BLOCK", "BLOCK", ref -> {
                Map<String, String> p = new LinkedHashMap<>();
                p.put("BLOCK", ref);
                return drive("place", "held", p);
            }, Verdict.REFUSE, Verdict.REFUSE, Verdict.REFUSE));


    private static void expect(List<String> bad, Path path, String kind, String ref, Verdict want) {
        String got = path.call().apply(ref);
        boolean accepted = got == null;
        if (accepted != (want == Verdict.ACCEPT)) {
            bad.add(path.name() + " / " + kind + " (" + ref + "): expected " + want
                    + " but " + (accepted ? "ACCEPTED it" : "refused: " + got.split("\n")[0]));
            return;
        }
        if (!accepted && !got.contains(path.field())) {
            bad.add(path.name() + " / " + kind + ": the refusal never names the parameter, so the "
                    + "agent cannot tell WHICH one it got wrong: " + got.split("\n")[0]);
        }
    }

    @Test
    @DisplayName("every path agrees about every kind of item reference")
    void thePathsAgree() {
        ItemSpecStore.put(new ItemSpecStore.Spec("hot_coffee_plain", "Hot Coffee", "cup",
                "#4a2c14", "plain", 1, List.of(), new JsonObject()));
        List<String> bad = new ArrayList<>();
        try {
            for (Path p : PATHS) {
                // The two kinds of real item. Both must be accepted everywhere, and a path that
                // treats a mod's item differently from a vanilla one is the failure shape.
                expect(bad, p, "vanilla", "minecraft:cocoa_beans", Verdict.ACCEPT);
                expect(bad, p, "modded", MODDED_SHAPED, Verdict.ACCEPT);

                // All three spellings of a made item, which must never disagree with each other.
                for (String spelling : List.of("hot_coffee_plain",
                        "moddingfromamod:hot_coffee_plain", "Hot Coffee")) {
                    expect(bad, p, "wished(" + spelling + ")", spelling, p.wished());
                }
                // Named but never made: a predicate for nothing matches nothing, silently.
                expect(bad, p, "wished-but-absent", "never_made_this", Verdict.REFUSE);

                // THE ONE THAT RESOLVES. A registry check says "yes, that exists", which is the
                // wrong answer for an id meaning every wish item at once.
                expect(bad, p, "blank", ItemBase.WISH_ITEM, p.blank());

                expect(bad, p, "malformed", "minecraft:", p.malformed());

                // A TYPO IS SHAPE-PERFECT. Nothing but the registry knows, and nothing at wish
                // time reports a miss: /give and /setblock quietly do nothing while the item still
                // plays its sound.
                expect(bad, p, "typo", "minecraft:cobblestne", Verdict.REFUSE);
            }
        } finally {
            ItemSpecStore.clear();
        }
        assertTrue(bad.isEmpty(), "the paths disagree about what an item reference means — which is "
                + "#353 itself, and the reason this is a matrix rather than three fixes:\n  "
                + String.join("\n  ", bad));
    }

    @Test
    @DisplayName("a made item reaches a give as components and a match as a predicate")
    void theTwoRenderingsAreDifferentStrings() {
        ItemSpecStore.put(new ItemSpecStore.Spec("hot_coffee_plain", "Hot Coffee", "cup",
                "#4a2c14", "plain", 1, List.of(), new JsonObject()));
        try {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("FROM", "hot_coffee_plain");
            p.put("TO", "hot_coffee_plain");
            String no = drive("convert", ItemCatalogue.MACHINE, p);
            assertTrue(no != null && no.contains("exactly what it took"),
                    "one item on both ends is a machine with nothing to do: " + no);

            p.put("TO", "minecraft:mushroom_stew");
            assertTrue(drive("convert", ItemCatalogue.MACHINE, p) == null);
            assertTrue(ConvertRecipeTest.take(p).startsWith(ItemBase.id(null) + "[minecraft:custom_data~{"),
                    "a FROM has to MATCH what is in his bag: " + ConvertRecipeTest.take(p));

            p.clear();
            p.put("FROM", "minecraft:cocoa_beans");
            p.put("TO", "hot_coffee_plain");
            assertTrue(drive("convert", ItemCatalogue.MACHINE, p) == null);
            assertTrue(ConvertRecipeTest.give(p).contains("minecraft:custom_name=")
                            && ConvertRecipeTest.give(p).contains("Hot Coffee"),
                    "an OUTPUT has to BUILD what arrives in it, name and icon and all: "
                            + ConvertRecipeTest.give(p));
        } finally {
            ItemSpecStore.clear();
        }
    }
}
