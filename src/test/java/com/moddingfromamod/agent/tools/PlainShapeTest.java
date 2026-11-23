package com.moddingfromamod.agent.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.moddingfromamod.datapack.ItemCatalogue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>An item that does nothing, on purpose — and the pair that must be refused.</b>
 *
 * <p>The shape with no trigger. Around any machine most of what a child names is stuff to put IN it
 * or stuff to collect, and an ingredient is not supposed to do anything when you right-click it.
 * Before this, every item had to carry an effect, so an ingredient came back with a behaviour
 * nobody asked for.
 *
 * <p>Two rules make it honest rather than merely permissive, and both are refusals:
 * {@code plain} + an effect is rejected, because the effect would be compiled and then never
 * reachable; and every other shape still demands one, because <i>"he did not say what it does"</i>
 * and <i>"it does nothing on purpose"</i> are different answers and a default cannot tell them
 * apart.
 */
class PlainShapeTest {

    private static Map<String, String> shapeArgs() {
        return new java.util.LinkedHashMap<>(Map.of(
                "FEATURE", "bag_of_flour", "ICON", "3", "COLOUR", "16777215", "HEX", "#FFFFFF",
                "NAME", "Bag of Flour", "COUNT", "4", "HINT", "It's yours to keep.",
                "GIVE_MESSAGE", "You got a Bag of Flour!",
                "GIVES", "    /give @s moddingfromamod:wish_item[minecraft:custom_data={}] 4"));
    }

    @Test
    @DisplayName("plain renders with no effect at all")
    void plainRendersWithoutAnEffect() {
        String src = ItemCatalogue.load().render(List.of(), ItemCatalogue.PLAIN, Map.of(),
                shapeArgs());
        assertTrue(src.contains("function give()"), "he still has to be given the thing");
        assertTrue(src.contains("/give @s moddingfromamod:wish_item"), src);
    }

    @Test
    @DisplayName("...and declares NO act, so a right-click has nothing to reach")
    void plainDeclaresNoBehaviour() {
        // The reason this is a shape and not a no-op effect. A no-op `act` would still be wired to
        // the click, so the item would answer it by doing nothing — which reads as broken. Here the
        // click is simply not something the item takes part in.
        String src = ItemCatalogue.load().render(List.of(), ItemCatalogue.PLAIN, Map.of(),
                shapeArgs());
        assertTrue(!src.contains("function act()"),
                "a plain item must not declare a behaviour anything can call:\n" + src);
    }

    @Test
    @DisplayName("plain + an effect is REFUSED, not quietly built")
    void plainWithAnEffectIsRefused() {
        // It would compile and then never run: a behaviour nothing can set off. Refusing says so;
        // building it would hand him an item that silently is not what the wish described.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ItemCatalogue.load().render(List.of("heal"), ItemCatalogue.PLAIN,
                        Map.of("heal", Map.of("SECONDS", "6", "TARGET", "@s")), shapeArgs()));
        assertTrue(e.getMessage().contains("never run"), e.getMessage());
    }

    @Test
    @DisplayName("every OTHER shape still demands an effect")
    void theOtherShapesStillRequireOne() {
        for (String shape : ItemCatalogue.EFFECT_SHAPES) {
            assertThrows(IllegalArgumentException.class,
                    () -> ItemCatalogue.load().render(List.of(), shape, Map.of(), shapeArgs()),
                    shape + " accepted an item with no effect — only `plain` may");
        }
    }

    @Test
    @DisplayName("the TOOL demands an effect for a firing shape, and not for plain")
    void theToolAsksForAnEffectOnlyWhereOneCanRun() {
        // The render layer's rule again, one level up, where the agent actually meets it. Both
        // layers hold it because they fail differently: the tool says what is missing in the
        // child's terms, and the catalogue refuses to build something incoherent.
        for (String shape : ItemCatalogue.EFFECT_SHAPES) {
            if (shape.equals(com.moddingfromamod.datapack.MagicBlock.SHAPE)) {
                // A magic_block is a BLOCK before it is a power: with no effect it is still a
                // block he places and breaks to get back, which is a wish of its own.
                assertFalse(MakeItemTool.missingFields(args(shape)).contains("effect"));
                continue;
            }
            assertTrue(MakeItemTool.missingFields(args(shape)).contains("effect"),
                    shape + " did not ask for an effect, and one can actually run on it");
        }
        assertTrue(!MakeItemTool.missingFields(args(ItemCatalogue.PLAIN)).contains("effect"),
                "plain asked for an effect it would refuse to build");
    }

    private static com.google.gson.JsonObject args(String shape) {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("name", "Bag of Flour");
        o.addProperty("icon", "bottle");
        o.addProperty("colour", "white");
        o.addProperty("shape", shape);
        o.addProperty("count", 4);
        return o;
    }

    @Test
    @DisplayName("plain is a shape, and it is not in the effect matrix")
    void plainIsOutsideTheMatrix() {
        // The matrix is a product over the shapes that FIRE. Including this one would add a row of
        // combinations that cannot exist, and the count would stop being arithmetic anybody trusts.
        assertTrue(ItemCatalogue.SHAPES.contains(ItemCatalogue.PLAIN));
        assertTrue(!ItemCatalogue.EFFECT_SHAPES.contains(ItemCatalogue.PLAIN));
    }
}
