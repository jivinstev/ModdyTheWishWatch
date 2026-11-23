package com.moddingfromamod.wishbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * the editor changes an item's LOOK (two pages deep: 🎨 More looks…, then its category) and its POWER, by the
 * Builder's own pages, and refuses what those pages would refuse.
 */
class BuilderEditLooksTest {

    /** A kept make: the look {@code look}, its power {@code power}, through the real Builder pages. */
    static BuilderSpec made(String look, String power) {
        BuilderFlow f = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
        BuilderLooks.Look l = BuilderLooks.look(look);
        for (String id : List.of("item", BuilderLooks.MORE_ID, BuilderLooks.GROUP_PREFIX + l.group(), BuilderLooks.PREFIX + look,
                "itempower:" + power)) {
            assertEquals("", f.press(id).say(), id);
        }
        return f.spec(f.values(""));
    }

    static BuilderEdit.Result edit(BuilderSpec prior, BuilderEdit.Op... ops) {
        return BuilderEdit.apply(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED, "x_1", prior, List.of(ops));
    }

    @Test
    void aLookChangesToALookInTheSameCategoryOrAnother() {
        BuilderSpec crown = made("crown", BuilderLooks.SHOW);
        BuilderEdit.Result r = edit(crown, BuilderEdit.Op.set(BuilderLooks.SLOT, BuilderLooks.PREFIX + "wizard_hat"));
        assertTrue(r.ok(), r.refusal());
        assertEquals("wizard_hat", r.spec().opt(BuilderLooks.SLOT));
        assertEquals(BuilderLooks.SHOW, r.spec().opt("power"), "the power is kept");
        // a cupcake (Sweets & toys) into a donut (Food & drinks): another category, the same stock item (a snack)
        BuilderSpec cupcake = made("cupcake", "speed");
        BuilderEdit.Result d = edit(cupcake, BuilderEdit.Op.set(BuilderLooks.SLOT, BuilderLooks.PREFIX + "donut"));
        assertTrue(d.ok(), d.refusal());
        assertEquals("donut", d.spec().opt(BuilderLooks.SLOT));
        assertEquals("speed", d.spec().opt("power"));
    }

    @Test
    void aLookOnAnotherStockItemKeepsThePowerWhereItFitsAndSaysWhyWhereNot() {
        BuilderSpec crown = made("crown", BuilderLooks.SHOW);
        BuilderEdit.Result r = edit(crown, BuilderEdit.Op.set(BuilderLooks.SLOT, BuilderLooks.PREFIX + "backpack"));
        assertTrue(r.ok(), r.refusal());   // a crown (head) into a backpack (back): Just for fun fits both
        assertEquals("backpack", r.spec().opt(BuilderLooks.SLOT));
        assertEquals("chestplate", r.spec().opt("stock"));
        BuilderSpec speedyCrown = made("crown", "speed");
        BuilderEdit.Result c = edit(speedyCrown, BuilderEdit.Op.set(BuilderLooks.SLOT, BuilderLooks.PREFIX + "cupcake"));
        assertTrue(c.ok(), c.refusal());   // super speed: worn or eaten
        assertEquals("speed", c.spec().opt("power"));
        BuilderEdit.Result both = edit(speedyCrown, BuilderEdit.Op.set(BuilderLooks.SLOT, BuilderLooks.PREFIX + "backpack"),
                BuilderEdit.Op.set("power", "itempower:heal"));
        assertTrue(both.ok(), both.refusal());
        assertEquals("heal", both.spec().opt("power"));
    }

    @Test
    void aStockItemTakesALook() {
        BuilderFlow f = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
        f.press("item");
        f.press(BuilderFlow.STOCK_PREFIX + "helmet");
        f.press("itempower:speed");
        BuilderSpec helmet = f.spec(f.values(""));
        BuilderEdit.Result r = edit(helmet, BuilderEdit.Op.set(BuilderLooks.SLOT, BuilderLooks.PREFIX + "crown"));
        assertTrue(r.ok(), r.refusal());
        assertEquals("crown", r.spec().opt(BuilderLooks.SLOT));
        assertEquals("speed", r.spec().opt("power"));
    }

    @Test
    void thePowerChangesAndAPowerTheLookCannotHaveIsRefused() {
        BuilderSpec crown = made("crown", BuilderLooks.SHOW);
        BuilderEdit.Result r = edit(crown, BuilderEdit.Op.set("power", "itempower:speed"));
        assertTrue(r.ok(), r.refusal());
        assertEquals("speed", r.spec().opt("power"));
        assertEquals("crown", r.spec().opt(BuilderLooks.SLOT), "the look is kept");
        BuilderEdit.Result no = edit(crown, BuilderEdit.Op.set("power", "itempower:lightning"));
        assertFalse(no.ok(), "a crown is worn: lightning is a sword's");
        BuilderEdit.Result none = edit(crown, BuilderEdit.Op.set(BuilderLooks.SLOT, BuilderLooks.PREFIX + "no_such_look"));
        assertFalse(none.ok());
    }

    /** Moddy's name for an item follows its new look and power; a name he chose is kept. */
    @Test
    void anItemsNameFollowsTheEditUnlessHeChoseIt() {
        BuilderSpec cupcake = made("cupcake", "heal");
        BuilderSpec named = cupcake.withName(BuilderNames.name(cupcake, LibraryPresets.SHIPPED, List.of()));
        assertEquals("Healing Cupcake", named.name());
        BuilderEdit.Result donut = edit(named, BuilderEdit.Op.set(BuilderLooks.SLOT, BuilderLooks.PREFIX + "donut"));
        assertTrue(donut.ok(), donut.refusal());
        assertEquals("Healing Donut", donut.spec().name());
        BuilderEdit.Result fly = edit(named, BuilderEdit.Op.set("power", "itempower:fly"));
        assertEquals("Rocket Cupcake", fly.spec().name());
        BuilderSpec sprinkles = cupcake.withName("Sprinkles");
        assertEquals("Sprinkles", edit(sprinkles, BuilderEdit.Op.set(BuilderLooks.SLOT, BuilderLooks.PREFIX + "donut")).spec().name(),
                "a name he chose is his");
    }
}
