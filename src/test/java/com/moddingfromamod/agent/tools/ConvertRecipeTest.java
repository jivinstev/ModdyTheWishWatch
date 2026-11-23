package com.moddingfromamod.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.moddingfromamod.item.ItemSpecStore;

import com.moddingfromamod.datapack.ItemBase;
import com.moddingfromamod.datapack.ItemCatalogue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Both ends of a recipe must name something the machine can actually tell apart.</b>
 *
 * <p>{@code convert} is what makes a machine a <i>maker</i> rather than a dispenser: he holds the
 * thing that goes in, works the handle, and gets the thing that comes out. The whole of that
 * contract is two item ids, and both of them fail in ways that produce a machine which looks
 * completely fine and does nothing — which is precisely the failure the effect was added to end.
 *
 * <h2>The id that matches everything</h2>
 * Every item made from scratch is {@code moddingfromamod:wish_item}. The name, the colour and the
 * artwork are <b>components on it</b>, not part of its id. So a recipe naming that id does not mean
 * "his Coffee Beans" — it means <i>any wish item at all</i>, and the machine eats whichever one
 * {@code /clear} reaches first. A child would watch his wand vanish and a bowl of stew appear, and
 * every gate would be green.
 *
 * <p>The route that does work is the one the parameter's own {@code ask} names: build the invented
 * item on a {@code base_item}, so it has an id of its own to be named here.
 */
class ConvertRecipeTest {

    /** convert is turn_into on a machine now — the same recipe, FROM and TO. */
    private static ItemCatalogue.Entry convert() {
        return ItemCatalogue.load().get("convert");
    }

    /** What the machine takes, as the rendered {@code clear} line names it. */
    static String take(Map<String, String> p) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("clear @s (.+) 1$", java.util.regex.Pattern.MULTILINE).matcher(p.get("HELPERS"));
        return m.find() ? m.group(1) : "";
    }

    /** What the machine hands over, as the rendered {@code give} line names it. */
    static String give(Map<String, String> p) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/give @s (.+) 1$", java.util.regex.Pattern.MULTILINE).matcher(p.get("HELPERS"));
        return m.find() ? m.group(1) : "";
    }

    static String inName(Map<String, String> p) {
        return p.get(MakeItemTool.RECIPE).split(" → ")[0];
    }

    static String outName(Map<String, String> p) {
        return p.get(MakeItemTool.RECIPE).split(" → ")[1];
    }

    private static String derive(String in, String out) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("FROM", in);
        p.put("TO", out);
        return MakeItemTool.derive(convert(), ItemCatalogue.MACHINE, p);
    }

    @Test
    @DisplayName("a real recipe is accepted and names both ends out loud")
    void aRealRecipeIsAccepted() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("FROM", "minecraft:cocoa_beans");
        p.put("TO", "minecraft:mushroom_stew");
        assertNull(MakeItemTool.derive(convert(), ItemCatalogue.MACHINE, p));
        assertTrue(inName(p).contains("Cocoa Beans"), p.toString());
        assertTrue(outName(p).contains("Mushroom Stew"), p.toString());
    }

    @Test
    @DisplayName("a bare wish_item on EITHER end is refused, because it matches every wish item")
    void theAmbiguousIdIsRefused() {
        String in = derive(ItemBase.WISH_ITEM, "minecraft:mushroom_stew");
        assertNotNull(in, "a machine that eats any wish item he is carrying is not a coffee machine");
        assertTrue(in.contains("make_item"),
                "the refusal has to say what to do instead, or it is a wall: " + in);

        assertNotNull(derive("minecraft:cocoa_beans", ItemBase.WISH_ITEM),
                "the OUTPUT end is the same bug — it would hand back an arbitrary wish item");
    }

    @Test
    @DisplayName("a bare name and a recipe that gives back what it took are both refused")
    void theTwoSilentMachines() {
        assertNotNull(derive("cocoa_beans", "minecraft:mushroom_stew"),
                "an id with no namespace does not resolve, and neither /clear nor /give says so");
        assertNotNull(derive("minecraft:cocoa_beans", "minecraft:cocoa_beans"),
                "handing back exactly what it took is a machine with nothing to do");
    }

    /**
     * <b>The failure a real run produced, in 115 seconds, reporting PASS.</b>
     *
     * <p>Gate C½, 2026-08-31, wish <i>"i want a coffee machine that turns coffee beans into
     * coffee"</i>. The agent did the right thing twice: it read "coffee beans" as
     * {@code minecraft:cocoa_beans}, and — answering the question this test was written to ask — it
     * <b>did invent the missing item</b>, making a {@code plain} "Hot Coffee" with a cup icon.
     * Then it named the OUTPUT {@code moddingfromamod:hot_coffee_plain}, which is not an item.
     * The rendered pack was:
     *
     * <pre>
     * clear @s minecraft:cocoa_beans 1
     * give @s moddingfromamod:hot_coffee_plain 1        &lt;- fails; nothing is given
     * tellraw @s [... "Hot Coffee Plain" green bold]    &lt;- says it worked
     * </pre>
     *
     * <p>A machine that eats his beans, hands back nothing, and congratulates him — which is the
     * exact silent-machine failure {@code convert} was added to end.
     */
    @Test
    @DisplayName("an OUTPUT naming an item he MADE hands over that item, components and all")
    void anInventedOutputIsResolvedRatherThanRefused() {
        ItemSpecStore.put(new ItemSpecStore.Spec("hot_coffee_plain", "Hot Coffee", "cup",
                "#4a2c14", "plain", 1, List.of(), new JsonObject()));
        try {
            for (String named : List.of("hot_coffee_plain", "moddingfromamod:hot_coffee_plain")) {
                Map<String, String> p = new LinkedHashMap<>();
                p.put("FROM", "minecraft:cocoa_beans");
                p.put("TO", named);
                assertNull(MakeItemTool.derive(convert(), ItemCatalogue.MACHINE, p),
                        "he made the coffee; the machine has to be able to hand it over: " + named);

                String give = give(p);
                // OUR OWN ITEM, whichever id that currently resolves to — the claim being made
                // is "a real, resolvable item plus its components", and that is true on both
                // routes. ItemBase.id(null) is how the give line itself answers the question.
                assertTrue(give.startsWith(ItemBase.id(null) + "["),
                        "it must be given as the real item plus its components, not as an id that "
                                + "does not resolve: " + give);
                assertTrue(give.contains("Hot Coffee"), give);
                assertTrue(give.contains("custom_model_data="), "his icon: " + give);
                assertEquals("Hot Coffee", outName(p),
                        "and it is called what he called it — 'Hot Coffee Plain' is the feature id "
                                + "read out loud, which is what the broken run said");
            }
        } finally {
            ItemSpecStore.clear();
        }
    }

    @Test
    @DisplayName("an OUTPUT in our namespace that was never made is refused, not rendered")
    void anIdWeNeverMadeIsRefused() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("FROM", "minecraft:cocoa_beans");
        p.put("TO", "moddingfromamod:coffee_that_was_never_made");
        String no = MakeItemTool.derive(convert(), ItemCatalogue.MACHINE, p);
        assertNotNull(no, "an id nothing registered gives nothing, silently");
        assertTrue(no.toLowerCase(java.util.Locale.ROOT).contains("make it first"), no);
    }

    /**
     * <b> reversed this, and the reversal is the point.</b>
     *
     * <p>Until an item could say WHICH one it was, a made item as an INPUT had to be refused: the
     * machine finds what he is carrying by item id, every item made from scratch shares one, and
     * naming it would have eaten his wand. So the honest answer was a refusal pointing at
     * {@code base_item} — which worked, and leaked, because "Coffee Beans" built on cocoa beans
     * means the machine also accepts cocoa beans dug out of a jungle.
     *
     * <p>With the identity component the predicate can name that one item, so a CHAIN is
     * expressible for the first time: <i>"coffee beans you roast then grind"</i> is two machines
     * whose middle item is invented, and it was the last unexpressible item wish of its shape.
     */
    @Test
    @DisplayName("an INPUT naming an item he MADE is matched by its own components, not by a shared id")
    void anInventedInputIsNowMatchedRatherThanRefused() {
        ItemSpecStore.put(new ItemSpecStore.Spec("roasted_beans_plain", "Roasted Coffee Beans",
                "seed", "brown", "plain", 1, List.of(), new JsonObject()));
        try {
            for (String named : List.of("roasted_beans_plain",
                    "moddingfromamod:roasted_beans_plain", "Roasted Coffee Beans")) {
                Map<String, String> p = new LinkedHashMap<>();
                p.put("FROM", named);
                p.put("TO", "minecraft:mushroom_stew");
                assertNull(MakeItemTool.derive(convert(), ItemCatalogue.MACHINE, p),
                        "every spelling of his own item has to work as an input: " + named);

                String pred = take(p);
                assertTrue(pred.startsWith(ItemBase.id(null) + "[minecraft:custom_data~{"),
                        "it must match on components, or it matches every wish item: " + pred);
                assertTrue(pred.contains("WishItem:\"roasted_beans_plain\""), pred);
                assertEquals("Roasted Coffee Beans", inName(p),
                        "and the machine says his name for it, not the feature id read aloud");
            }
        } finally {
            ItemSpecStore.clear();
        }
    }

    @Test
    @DisplayName("an INPUT he never made is still refused — a predicate for nothing matches nothing")
    void anInputThatWasNeverMadeIsRefused() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("FROM", "beans_that_do_not_exist");
        p.put("TO", "minecraft:mushroom_stew");
        String no = MakeItemTool.derive(convert(), ItemCatalogue.MACHINE, p);
        assertNotNull(no, "a machine waiting for an item nothing makes never does anything");
        assertTrue(no.contains("make_item"), no);
    }


    /**
     * <b>The chain, and the bug Gate C½ found that no other gate could.</b>
     *
     * <p>A real run of <i>"coffee beans you roast then grind"</i> built three items and two
     * machines, and the chain did not join: the roaster handed over a {@code wish_item} while the
     * grinder was waiting for {@code minecraft:dried_kelp}. The agent had built the middle item on
     * a {@code base_item} — a Roasted Coffee Beans that IS dried kelp wearing a name — and
     * {@code giveArg} rendered every made item as a wish item regardless.
     *
     * <p>Invisible to every gate before it, because all of them used items made from scratch, where
     * the base and the wish item are the same thing.
     */
    @Test
    @DisplayName("a machine hands over the item's own BASE, so the next machine can take it")
    void theChainJoins() {
        ItemSpecStore.put(new ItemSpecStore.Spec("roasted_plain", "Roasted Coffee Beans", "seed",
                "brown", "plain", 1, List.of(), new JsonObject(), new com.google.gson.JsonArray(),
                new JsonObject(), new com.google.gson.JsonArray(), "minecraft:dried_kelp"));
        try {
            Map<String, String> roaster = new LinkedHashMap<>();
            roaster.put("FROM", "minecraft:cocoa_beans");
            roaster.put("TO", "roasted_plain");
            assertNull(MakeItemTool.derive(convert(), ItemCatalogue.MACHINE, roaster));

            Map<String, String> grinder = new LinkedHashMap<>();
            grinder.put("FROM", "roasted_plain");
            grinder.put("TO", "minecraft:cookie");
            assertNull(MakeItemTool.derive(convert(), ItemCatalogue.MACHINE, grinder));

            assertTrue(give(roaster).startsWith("minecraft:dried_kelp["),
                    "the roaster must hand over the item's own base, or nothing downstream can "
                            + "take it: " + give(roaster));
            assertTrue(take(grinder).startsWith("minecraft:dried_kelp["),
                    "and the grinder must look for that same base: " + take(grinder));
            // STRICTER THAN THE BASE ALONE. Plain dried kelp out of the sea must NOT work -- that
            // is the leak the base_item workaround has, and the whole reason the identity exists.
            assertTrue(take(grinder).contains("WishItem:\"roasted_plain\""),
                    "his roasted beans, not any dried kelp: " + take(grinder));
        } finally {
            ItemSpecStore.clear();
        }
    }

}
