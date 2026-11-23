package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.moddingfromamod.wishbuilder.BuilderEdit;
import java.util.List;
import org.junit.jupiter.api.Test;

/** what he hears while an item's look or power changes, and what an offer tile says. */
class RewishWordsTest {

    @Test
    void anItemsNewLookAndPowerAreNamed() {
        assertEquals("✨ Turning your Crown into a Wizard Hat…",
                Rewish.changing("Crown", List.of(BuilderEdit.Op.set("itemlook", "look:wizard_hat"))));
        assertEquals("✨ Giving your Speedy Wand Super speed…",
                Rewish.changing("Speedy Wand", List.of(BuilderEdit.Op.set("power", "itempower:speed"))));
        assertEquals("✨ Turning your Crown into a Cape with Heal me…", Rewish.changing("Crown",
                List.of(BuilderEdit.Op.set("itemlook", "look:cape"), BuilderEdit.Op.set("power", "itempower:heal"))));
        assertEquals("✨ Taking the power off your Crown…",
                Rewish.changing("Crown", List.of(BuilderEdit.Op.set("power", "itempower:show"))));
        assertEquals("✨ Turning your Sunglasses into a pair of Headphones…",
                Rewish.changing("Sunglasses", List.of(BuilderEdit.Op.set("itemlook", "look:headphones"))));
        assertEquals("✨ Changing your Sparky…", Rewish.changing("Sparky", List.of(BuilderEdit.Op.step("size", 1))));
    }

    @Test
    void aRenamedThingSaysItsNewName() {   //
        assertEquals("✨ Turning your Healing Cupcake into a Donut… it's your Healing Donut now", Rewish.changing("Healing Cupcake",
                List.of(BuilderEdit.Op.set("itemlook", "look:donut")), "Healing Donut"));
        assertEquals("✨ Turning your Sprinkles into a Donut…", Rewish.changing("Sprinkles",
                List.of(BuilderEdit.Op.set("itemlook", "look:donut")), "Sprinkles"));
    }

    @Test
    void aLookIsSaidWithTheOneRule() {   //: English.withArticle, as the Builder and stack vitals say a thing
        assertEquals("a Crown", Rewish.withA("Crown"));
        assertEquals("an Ice Pop", Rewish.withA("Ice Pop"));
        assertEquals("an Octopus", Rewish.withA("Octopus"));
        assertEquals("a pair of Headphones", Rewish.withA("Headphones"));
        assertEquals("a pair of Sunglasses", Rewish.withA("Sunglasses"));
        assertEquals("a pair of Binoculars", Rewish.withA("Binoculars"));
        assertEquals("some Fries", Rewish.withA("Fries"));
        assertEquals("a Unicorn", Rewish.withA("Unicorn"));
        assertEquals("a UFO", Rewish.withA("UFO"));
    }
}
