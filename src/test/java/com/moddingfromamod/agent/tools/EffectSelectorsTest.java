package com.moddingfromamod.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.moddingfromamod.datapack.EffectCatalogue;
import com.moddingfromamod.datapack.SelectorTags;
import com.moddingfromamod.entity.WishMobTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Every ready-made ability must select the creature it was given to.</b>
 *
 * <p>This is the gate for a failure that survived every other one for months, on all eleven
 * templates at once. {@code apply_effect} substituted the bare species id into each ability's
 * selectors, so a creature tagged {@code wishmob_spikor} got a clock watching
 * {@code @e[tag=spikor]}. Nothing in the stack could see it: the CBScript compiled, the datapack
 * loaded, {@code verify_datapack} confirmed the functions were real and ran without erroring —
 * which they do, because {@code execute as @e[tag=spikor]} with no matches is a successful no-op —
 * and the agent reported to the child that the ability had been granted.
 *
 * <p>It was found by filming the creature and counting flame-coloured pixels, which is not a gate
 * anyone should have to run. This one is a millisecond and runs on every commit.
 *
 * <p>It deliberately goes through {@link ApplyEffectTool#selectorTagFor} rather than substituting a
 * known-good tag, because <b>the template was never wrong</b> — the value handed to it was. A test
 * that rendered with the right tag would have passed throughout the entire period the bug was live.
 */
class EffectSelectorsTest {

    private static final String SPECIES = "spikor";

    /** What WishMobEntity really puts on a creature — the host's promise, and all of it. */
    private static final Set<String> WORN =
            Set.of(WishMobTags.ALL, WishMobTags.forSpecies(SPECIES));

    /**
     * Fill every parameter a template declares, so the render is complete.
     *
     * <p>Values are deliberately inert: this test reads the SELECTORS of the result, and a
     * parameter carrying its own {@code tag=} would be measuring the fixture.
     */
    private static Map<String, String> paramsFor(EffectCatalogue.Effect e, String tagValue) {
        Map<String, String> args = new LinkedHashMap<>();
        // allParams(), not params(): several templates also take values the TOOL derives
        // (seconds to ticks, the impulse physics), and render() rightly refuses a half-filled
        // template — "it would compile cleanly and do nothing", which is this bug's own family.
        for (String p : e.allParams()) {
            if ("VICTIMS".equals(p)) {
                continue;   // derived from TARGET by render() itself, the way the tool gets it
            }
            if ("TARGET".equals(p)) {
                continue;   // its default: everything but players
            }
            if ("TAG".equals(p)) {
                args.put(p, tagValue);
            } else if (p.endsWith("TAG")) {
                // An agent-chosen tag (buff_allies' ALLY_TAG). Nothing static can know whether the
                // agent also put it on anything, so it is declared rather than checked here; the
                // runtime guard in apply_effect is what covers it.
                args.put(p, "mfam_test_ally");
            } else {
                args.put(p, "1");
            }
        }
        return args;
    }

    private static Set<String> provided() {
        Set<String> out = new LinkedHashSet<>(WORN);
        out.add("mfam_test_ally");
        // ReachFromBodyFix's marker: every on_step/on_land render carries tag=!mfam_flank so
        // an installed pack is never widened twice. Nothing wears it by design; a NEGATED marker
        // matches everything, so it is not a dead selector.
        out.add(com.moddingfromamod.datapack.ReachFromBodyFix.MARKER.substring("tag=!".length()));
        return out;
    }

    @Test
    @DisplayName("every effect in the catalogue selects a tag the creature actually wears")
    void noEffectSelectsADeadTag() {
        EffectCatalogue catalogue = EffectCatalogue.load();
        assertTrue(catalogue.all().size() >= 10,
                "the catalogue looks empty; this sweep would pass by doing nothing");

        // THE REAL PATH: the agent passes the species id, and the tool decides the selector tag.
        // Substituting a known-good tag here would test the templates, which were never wrong.
        String tag = ApplyEffectTool.selectorTagFor(SPECIES, WORN::contains);

        List<String> broken = new ArrayList<>();
        for (EffectCatalogue.Effect e : catalogue.all()) {
            Set<String> dead = SelectorTags.dead(
                    catalogue.render(e.id(), paramsFor(e, tag)), provided());
            if (!dead.isEmpty()) {
                broken.add(e.id() + " selects " + dead);
            }
        }
        assertTrue(broken.isEmpty(),
                "these abilities install a clock watching a tag nothing will ever wear, so they "
                + "compile, load, verify and do nothing at all: " + broken);
    }

    @Test
    @DisplayName("...and the sweep really fires — the original bug is caught")
    void theSweepCatchesTheBugItWasWrittenFor() {
        EffectCatalogue catalogue = EffectCatalogue.load();
        // What apply_effect used to substitute: the bare species id, straight from the agent.
        EffectCatalogue.Effect breath = catalogue.get("breath_cone");
        Set<String> dead = SelectorTags.dead(
                catalogue.render("breath_cone", paramsFor(breath, SPECIES)), provided());
        assertEquals(Set.of(SPECIES), dead,
                "rendered with the bare species id, breath_cone must be reported as selecting a "
                + "tag no creature wears — if this passes, the sweep above proves nothing");
    }

    @Test
    @DisplayName("a selector discussed in a COMMENT is not a selector")
    void commentsAreProse() {
        // on_death's header explains a trap using `@e[tag=X,nbt={DeathTime:1s}]` as its example.
        // The first run of this sweep duly reported on_death as selecting a dead tag `X`.
        assertTrue(SelectorTags.dead(
                "# the obvious @e[tag=X] does not work\n/execute as @e[tag=real] run say hi",
                Set.of("real")).isEmpty(),
                "a rule that cannot tell an instruction from an explanation of one cries wolf on "
                + "the best-documented file in the catalogue, and gets switched off");
    }

    @Test
    @DisplayName("a tag the pack puts on something itself is not dead")
    void packOwnTagsAreFine() {
        assertTrue(SelectorTags.dead(
                "/execute as @e[tag=x] run tag @s add mine\n/kill @e[tag=mine]", Set.of("x"))
                .isEmpty(), "a tag the program adds and then selects is alive");
        assertTrue(SelectorTags.dead(
                "/summon armor_stand ~ ~ ~ {Tags:[\"marker\"]}\n/kill @e[tag=marker]", Set.of())
                .isEmpty(), "and so is one introduced through a summon's NBT — lingering_zone "
                + "does exactly that, and a rule that missed it would cry wolf on a good pack");
    }
}
