package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The item catalogue's substitution rules, off a running game.
 *
 * <p>These are the rules that, when wrong, produce a datapack that compiles perfectly and hands the
 * child something other than what they asked for — so they are tested where they can be tested
 * cheaply and exhaustively, rather than only through a 15-minute client run.
 *
 * <p>Set {@code MFAM_ITEM_DUMP=<dir>} to also write every rendered cell out — for eyeballing, or for
 * putting through the real CBScript compiler. An env var rather than a system property because
 * Gradle's test JVM does not inherit {@code -D} from the command line.
 */
class ItemCatalogueTest {

    private static Map<String, String> shapeArgs(String feature) {
        return ItemCatalogueTestSupport.shapeArgs(feature);
    }

    private static Map<String, String> effectArgs(ItemCatalogue.Entry e, String shape) {
        return ItemCatalogueTestSupport.argsFor(e, shape);
    }

    @Test
    void everyEffectInEveryShapeRendersWithNothingLeftUnfilled() throws Exception {
        ItemCatalogue c = ItemCatalogue.load();
        String dump = System.getenv("MFAM_ITEM_DUMP");
        int cells = 0;
        for (ItemCatalogue.Entry e : c.all()) {
            for (String shape : ItemCatalogue.EFFECT_SHAPES) {
                String src = c.render(e.id(), shape, effectArgs(e, shape),
                        shapeArgs(e.id() + "_" + shape));
                // A surviving placeholder compiles cleanly and does nothing. render() is supposed to
                // throw on one; this asserts the OUTPUT too, because a placeholder can also arrive
                // inside a substituted VALUE, where the matcher never looks. That is not
                // hypothetical: targetFor() returned `distance=..${RADIUS}` in its first cut.
                assertFalse(src.contains("${"),
                        e.id() + "/" + shape + " still contains a placeholder:\n" + src);
                assertTrue(src.contains("function give()"),
                        e.id() + "/" + shape + " has no give function, so nothing can hand it over");
                assertTrue(src.contains("function effect()"),
                        e.id() + "/" + shape + " has no effect function");
                if (dump != null) {
                    Path out = Path.of(dump);
                    Files.createDirectories(out);
                    Files.writeString(out.resolve(e.id() + "_" + shape + ".cbscript"), src);
                }
                cells++;
            }
        }
        // DERIVED, not pinned. A hardcoded 51 is the same rot the gap tool's docstring records --
        // "a column read off a schema once, which then rotted" -- and it fired the first time a
        // shape was added: every one of the 68 cells rendered correctly and the only thing red was
        // a number nobody had re-counted.
        int expected = ItemCatalogue.load().all().size() * ItemCatalogue.EFFECT_SHAPES.size();
        assertEquals(expected, cells,
                ItemCatalogue.load().all().size() + " effects x " + ItemCatalogue.EFFECT_SHAPES.size()
                        + " shapes");
    }

    /**
     * <b>No template may name a placeholder in a COMMENT.</b>
     *
     * <p>Two separate bugs, both from this one rule, and the second is why it is a test rather than
     * a note:
     *
     * <ul>
     *   <li>{@code shape_held.cbs} described the reach as {@code ${REACH}} in its prose. Nothing
     *       supplied it, so rendering refused outright — loud, and fixed in a minute.</li>
     *   <li>{@code summon.cbs} described its own {@code ${SUMMONS}} the same way, and that value is
     *       <b>multi-line</b>. Substituting three summons into a comment left lines 2 and 3 outside
     *       it, as bare commands at the top level of the file. Silent in every unit test, because
     *       the stub was one line; caught only by the runtime matrix, which passes a real COUNT.</li>
     * </ul>
     *
     * <p>So the rule is not "fill your placeholders", it is "prose is rendered too". Explain the
     * values in words and let the code below show them.
     */
    @Test
    void noTemplateNamesAPlaceholderInAComment() throws Exception {
        Path dir = Path.of("src/main/resources/items");
        try (var files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".cbs")).toList()) {
                int n = 0;
                for (String line : Files.readAllLines(f)) {
                    n++;
                    // Only a line that IS a comment. `#minecraft:replaceable` mid-command is a block
                    // tag, not a comment, and flagging it would make this guard cry wolf.
                    if (line.stripLeading().startsWith("#") && line.contains("${")) {
                        throw new AssertionError(f.getFileName() + ":" + n + " names a placeholder "
                                + "inside a comment:\n  " + line.strip()
                                + "\nProse is rendered like everything else. A single-line value "
                                + "gets substituted into your sentence; a MULTI-LINE one breaks out "
                                + "of the comment and becomes stray commands. Describe it in words.");
                    }
                }
            }
        }
    }

    @Test
    void noParameterMayEverHaveADefault() {
        // The load() guard, exercised. This is the discipline the whole tool exists to hold, and a
        // default added later would be invisible in every other test: it would simply start
        // building a different item for one wish in three.
        assertTrue(ItemCatalogue.load().all().stream().allMatch(e -> !e.params().isEmpty()
                || e.params().isEmpty()));
        for (ItemCatalogue.Entry e : ItemCatalogue.load().all()) {
            for (String p : e.params()) {
                assertNotNull(e.paramAsks().get(p),
                        e.id() + "." + p + " has no 'ask' — a category documented only by its "
                                + "definition is one the agent will not reach for (playbook 4f)");
                assertFalse(e.paramAsks().get(p).isBlank(), e.id() + "." + p + " has a blank ask");
            }
        }
    }

    @Test
    void anUnknownParameterIsRefusedRatherThanIgnored() {
        ItemCatalogue c = ItemCatalogue.load();
        Map<String, String> bad = effectArgs(c.get("hurt"), "held");
        bad.put("DURATION", "30");
        // Silently ignoring DURATION where the template wants something else produces a perfectly
        // valid datapack that does the wrong thing, with no error anywhere.
        var ex = assertThrows(IllegalArgumentException.class,
                () -> c.render("hurt", "held", bad, shapeArgs("x")));
        assertTrue(ex.getMessage().contains("DURATION"), ex.getMessage());
    }

    @Test
    void anUnfilledPlaceholderThrowsRatherThanShipping() {
        ItemCatalogue c = ItemCatalogue.load();
        Map<String, String> args = effectArgs(c.get("hurt"), "held");
        args.remove("DAMAGE");
        var ex = assertThrows(IllegalArgumentException.class,
                () -> c.render("hurt", "held", args, shapeArgs("x")));
        assertTrue(ex.getMessage().contains("DAMAGE"), ex.getMessage());
    }

    @Test
    void anUnknownEffectOrShapeIsNamedNotSubstituted() {
        ItemCatalogue c = ItemCatalogue.load();
        assertNull(c.get("teleprot"));
        assertThrows(IllegalArgumentException.class,
                () -> c.render("teleprot", "held", Map.of(), shapeArgs("x")));
        assertThrows(IllegalArgumentException.class,
                () -> c.render("hurt", "chucked", effectArgs(c.get("hurt"), "held"), shapeArgs("x")));
    }

    @Test
    void heldSelfHitsTheChildAndHeldTargetNeverDoes() {
        // The quietest possible bug in this catalogue: a "make me GIANT" potion that enlarges the
        // zombie in front of you, or a fire wand that sets its own owner alight. Both build, run,
        // and say nothing.
        assertEquals("@s", ItemCatalogue.targetFor("held", "self", 6));
        String target = ItemCatalogue.targetFor("held", "target", 6);
        assertTrue(target.contains("type=!minecraft:player"),
                "a held effect aimed AWAY from the child must not come back and hit them: " + target);
        assertFalse(ItemCatalogue.targetFor("placed", "self", 6).contains("@s"),
                "@s in the placed shape is the invisible MARKER, not a person");
    }

    /**
     * <b>A thrown weapon CAN hurt the thrower, and that is a decision (§5).</b>
     *
     * <p>Pinned because it looks exactly like the bug the held branch avoids. {@code held + target}
     * excludes the player on purpose — a fire wand must not torch its owner — so the obvious reading
     * of {@code thrown} not doing the same is that somebody forgot. Somebody did not: standing in
     * your own explosion hurting you is how the rest of the game works, and it is a consequence a
     * child can see and avoid.
     *
     * <p>If this is ever revisited, the way is a required parameter answered from his own words —
     * the shape {@code explode}'s {@code BREAKS_BLOCKS} already uses — and never a silent default.
     * Deleting the guard on {@code held} to "make them consistent" is the wrong direction and this
     * test says so in both directions.
     */
    @Test
    void aThrownWeaponMayHurtTheThrowerAndAHeldOneMayNot() {
        String thrown = ItemCatalogue.targetFor("thrown", "target", 6);
        assertFalse(thrown.contains("type=!minecraft:player"),
                "thrown deliberately includes the thrower (#123 §5) — a bomb at your own feet "
                        + "hurting you is the game working, not a bug. If this is being changed, "
                        + "it must become a required question, never a silent default.");
        String held = ItemCatalogue.targetFor("held", "target", 6);
        assertTrue(held.contains("type=!minecraft:player"),
                "held MUST still exclude the player: he is holding it and pointing it away, so a "
                        + "fire wand that torches its own owner is a bug, not a consequence");
    }

    @Test
    void everySelectorExcludesLooseItemsAndTheThrownSnowballItself() {
        for (String shape : ItemCatalogue.EFFECT_SHAPES) {
            for (String applies : List.of("self", "target", "world")) {
                String t = ItemCatalogue.targetFor(shape, applies, 6);
                if (t.equals("@s")) {
                    continue;
                }
                assertTrue(t.contains("type=!minecraft:item"),
                        shape + "/" + applies + " would treat dropped items as victims: " + t);
                assertTrue(t.contains("type=!minecraft:snowball"),
                        shape + "/" + applies + " lets a thrown item freeze itself mid-flight: " + t);
            }
        }
    }

    @Test
    void theRadiusIsAValueNotAPlaceholder() {
        // Regression: the first cut left ${RADIUS} in the placed selector, where nothing would ever
        // have filled it — the effect body is substituted into the wrapper as a VALUE, and values
        // are inserted literally.
        assertFalse(ItemCatalogue.targetFor("placed", "target", 6).contains("${"));
        assertTrue(ItemCatalogue.targetFor("placed", "target", 6).contains("distance=..6"));
    }

    /**
     * <b>An effect that happens WHERE HE IS POINTING must actually be stepped forward.</b>
     *
     * <p>This is a shipped bug, caught only by asking what two composed effects would do about the
     * one AIM line they have to share. {@code teleport} lands on SELF, and the held wrapper read
     * {@code applies_to} to decide where to run — so it called the effect at the player, and
     * {@code tp @s ~ ~ ~} teleported him to exactly where he already stood. It compiled, it ran, it
     * played the sound, and it did nothing.
     *
     * <p>The lesson is the field split, not the fix: WHO an effect lands on and WHERE it happens are
     * different questions, and one field answering both is a bug generator. An effect can land on
     * the child and still need to happen five blocks in front of him.
     */
    @Test
    void anEffectAimedAheadIsActuallySteppedForward() {
        ItemCatalogue c = ItemCatalogue.load();
        for (ItemCatalogue.Entry e : c.all()) {
            String aim = "self".equals(e.aim())
                    ? "/function $namespace:effect"
                    : "/execute at @s anchored eyes positioned ^ ^ ^5 run function $namespace:effect";
            Map<String, String> sa = shapeArgs(e.id());
            sa.put("AIM", aim);
            String src = c.render(e.id(), "held", effectArgs(e, "held"), sa);
            if ("ahead".equals(e.aim())) {
                assertTrue(src.contains("positioned ^ ^ ^"),
                        e.id() + " is aimed AHEAD but its held wrapper never steps forward, so it "
                                + "happens at the child's feet instead of where he is pointing");
            }
        }
        // The specific one that shipped broken, pinned by name so the reason survives.
        assertEquals("ahead", c.get("teleport").aim(),
                "teleport lands on SELF but must happen where he is POINTING — with aim=self it "
                        + "teleports him to where he already is, silently");
        assertEquals("self", c.get("teleport").appliesTo(),
                "...and it still lands on him, which is why one field could not answer both");
        // And the effects that really are about the child stay at the child.
        for (String id : List.of("fly", "heal", "grow", "speed", "protect", "food")) {
            assertEquals("self", c.get(id).aim(),
                    id + " happens to the child himself; stepping it forward would aim it at "
                            + "whatever he happens to be looking at");
        }
    }

    /**
     * <b> — two effects on one item, and the halves must both survive.</b>
     *
     * <p>The javelin is the case: "lands butt cheek water" AND "super powerful" is place + hurt, and
     * with one effect per item the agent had to author a second datapack by hand to finish the job.
     */
    @Test
    void twoEffectsBothLandOnOneItem() {
        ItemCatalogue c = ItemCatalogue.load();
        Map<String, Map<String, String>> args = new LinkedHashMap<>();
        args.put("hurt", effectArgs(c.get("hurt"), "thrown"));
        args.put("place", effectArgs(c.get("place"), "thrown"));
        String src = c.render(List.of("hurt", "place"), "thrown", args, shapeArgs("javelin"));

        assertFalse(src.contains("${"), "a composed item still has a placeholder:\n" + src);
        // Each body kept its own commands...
        assertTrue(src.contains("damage @s"), "the hurt half vanished");
        assertTrue(src.contains("setblock"), "the place half vanished");
        // ...under its own name, because two `function effect()` in one file is one function.
        assertTrue(src.contains("function effect_hurt()") && src.contains("function effect_place()"),
                "the bodies were not renamed, so one silently replaced the other:\n" + src);
        // ...and something calls both.
        assertTrue(src.contains("/function $namespace:effect_hurt")
                        && src.contains("/function $namespace:effect_place"),
                "nothing dispatches to the composed halves, so the item does nothing:\n" + src);
        assertEquals(1, src.split("function effect\\(\\)", -1).length - 1,
                "there must be exactly one dispatcher named effect()");
    }

    @Test
    void aComposedItemRefusesWhatCannotWork() {
        ItemCatalogue c = ItemCatalogue.load();
        Map<String, Map<String, String>> a = Map.of("hurt", effectArgs(c.get("hurt"), "held"));
        // The same effect twice shares its scoreboards and its function name, so the second copy
        // silently replaces the first — and no wish is served by it either.
        assertThrows(IllegalArgumentException.class,
                () -> c.render(List.of("hurt", "hurt"), "held", a, shapeArgs("x")));
        assertThrows(IllegalArgumentException.class,
                () -> c.render(List.of(), "held", Map.of(), shapeArgs("x")));
        assertThrows(IllegalArgumentException.class,
                () -> c.render(List.of("hurt", "ignite", "freeze", "heal"), "held", a,
                        shapeArgs("x")));
    }

    /** Composition renames this literal, so every template must declare exactly one. */
    @Test
    void everyTemplateDeclaresExactlyOneEffectFunction() throws Exception {
        ItemCatalogue c = ItemCatalogue.load();
        for (ItemCatalogue.Entry e : c.all()) {
            String src = ItemCatalogue.source(e.template());
            assertEquals(1, src.split("function effect\\(\\)", -1).length - 1,
                    e.id() + " must declare exactly one 'function effect()' — composition renames "
                            + "that literal, so none means it cannot be composed and two means the "
                            + "rename would hit the wrong one");
        }
    }

    @Test
    void everyIconIsNamedAndTheNumbersMatchThePrimer() {
        assertEquals(24 + com.moddingfromamod.item.IconSprites.drawn().size(), ItemCatalogue.ICONS.size(),
                "the 24 core icons plus the #1052 drawn library");
        assertEquals(1, ItemCatalogue.icon("machine"));
        assertEquals(10, ItemCatalogue.icon("wand"));
        assertEquals(19, ItemCatalogue.icon("spear"));
        assertEquals(24, ItemCatalogue.icon("key"));
        assertEquals(13, ItemCatalogue.icon("COIN"), "an icon name should not be case-sensitive");
        assertNull(ItemCatalogue.icon("trident"),
                "an unlisted icon must be refused, never silently given the default artwork");
    }

    @Test
    void everyEntryCarriesTheThingsTheAgentIsShown() {
        for (ItemCatalogue.Entry e : ItemCatalogue.load().all()) {
            assertFalse(e.summary().isBlank(), e.id() + " has no summary");
            assertFalse(e.who().isBlank(), e.id() + " does not say what it lands on");
            assertFalse(e.childWhen().isBlank(), e.id() + " has no sentence for the child");
            assertTrue(List.of("self", "target", "world").contains(e.appliesTo()));
            assertTrue(Files.exists(Path.of("src/main/resources/items", e.template()))
                            || ItemCatalogue.source(e.template()) != null,
                    e.id() + " names a template that is not there");
        }
        assertEquals(33, ItemCatalogue.load().all().size(),
                "13 mined CORE effects, plus `fly` (#122), plus `turn_into` (was `convert`, and "
                        + "`change_block` since #2216), plus `jump`, "
                        + "plus 4 coverage ones, `status`, `reflect`, and `call_pets` and `teleport_home` (#1015, #1016), and `locate` (#1024), and `sort_inventory` and `drop_junk` (#1021), and `grapple` (#1020), and `load_and_pour` (#1043), and `pull`, `fill_blocks` and `outcome_table` (#1044), and `event` (#1047)");
        // `load_and_pour` is core too: 21 machines a mod repo built for a child plus ~30 wishes.
        // `pull`, `fill_blocks` and `outcome_table`: the black hole, the fill-it-with-water
        // wishes and the lucky block — each asked for by him, and each in the effect-blocks holdout.
        assertEquals(20, ItemCatalogue.load().all().stream().filter(ItemCatalogue.Entry::core)
                .count(),
                // `aggro` is the 13th mined core effect. It is core rather than coverage
                // because it has demand behind it -- the two noob pearls -- and the whole point of
                // the core/coverage split is that a coverage effect is one nobody has asked for.
                "core is MINED demand; adding one here without a wish behind it is how the "
                        + "catalogue stops meaning anything");
    }
}
