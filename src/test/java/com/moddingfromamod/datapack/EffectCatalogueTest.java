package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The substitution rules, which are where a catalogue quietly goes wrong.
 *
 * <p>Every failure mode tested here produces a datapack that <b>compiles perfectly and behaves
 * incorrectly</b>: a placeholder nobody filled in, a parameter name that does not exist, an effect
 * id that was never in the catalogue. None of them throw on their own. That is the whole reason
 * this class exists — see docs/EFFECT_CATALOGUE.md.
 */
class EffectCatalogueTest {

    @Test
    void theCatalogueLoadsAndDescribesItself() {
        EffectCatalogue cat = EffectCatalogue.load();
        assertFalse(cat.all().isEmpty(), "an empty catalogue means the agent finds nothing and "
                + "goes back to authoring from scratch, which is the thing this replaces");
        EffectCatalogue.Effect status = cat.get("inflict_nearby");
        assertTrue(status != null, "inflict_nearby is the shape the demand actually wants");
        assertFalse(status.summary().isBlank(),
                "the summary IS the selection surface — a model picks on it");
        assertFalse(status.wishes().isEmpty(),
                "example wishes let a model match a child's phrasing rather than a taxonomy word");
    }

    @Test
    void renderingFillsEveryPlaceholder() {
        String src = EffectCatalogue.load().render("inflict_nearby",
                Map.of("TAG", "dragon", "EFFECT", "minecraft:poison",
                        "SECONDS", "5", "AMPLIFIER", "1"));
        assertTrue(src.contains("tag=dragon"), "the creature must reach the source");
        assertTrue(src.contains("minecraft:poison"), "the effect must reach the source");
        assertFalse(src.contains("${"), "an unfilled placeholder compiles cleanly and does nothing");
    }

    @Test
    void defaultsApplySoTheSimplestWishIsTheShortestCall() {
        // A child saying "poison my snake" should not require four parameters.
        String src = EffectCatalogue.load().render("inflict_nearby", Map.of("TAG", "snake"));
        assertTrue(src.contains("tag=snake"));
        assertTrue(src.contains("minecraft:poison"), "the default effect should have applied");
        assertFalse(src.contains("${"));
    }

    @Test
    void anUnfilledPlaceholderIsAnError() {
        // TAG has no default deliberately: there is no sensible guess for WHICH creature.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> EffectCatalogue.load().render("inflict_nearby", Map.of("SECONDS", "3")));
        assertTrue(e.getMessage().contains("TAG"),
                "the error must name the missing placeholder: " + e.getMessage());
    }

    @Test
    void aMisspelledParameterIsAnErrorRatherThanASilentDefault() {
        // The nastiest case. DURATION is ignored, SECONDS falls back to its default, and the child
        // gets a five-second poison having asked for thirty -- with no error anywhere.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> EffectCatalogue.load().render("inflict_nearby",
                        Map.of("TAG", "dragon", "DURATION", "30")));
        assertTrue(e.getMessage().contains("DURATION"), e.getMessage());
    }

    @Test
    void anUnknownEffectNamesWhatIsAvailable() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> EffectCatalogue.load().templateSource("breathe_fire"));
        assertTrue(e.getMessage().contains("inflict_nearby"),
                "a miss should list what IS there, so the agent can pick instead of inventing: "
                        + e.getMessage());
    }

    @Test
    void theTriggerDocAnswersTheDoubtThatCostAWishAndNamesThePrecedence() {
        // item 1. On "make the giant PUNCH me so hard i fly backwards" the agent chose on_hit
        // THREE TIMES, then switched to proximity and said why: it had decided a summoned giant
        // might never swing, so on_hit would never fire and the child would see nothing, ever.
        //
        // That premise is FALSE and was measured false — TriggerBehaviourGameTest fires on_hit
        // beside a creature spawned with no AI at all. But the fact lived only in a GameTest, and
        // this repo's own rule is that fixing a fixture teaches nobody: the agent authors from the
        // catalogue, which said nothing about it. So the doubt was still there to be re-derived by
        // every future agent, and was.
        //
        // Note what is NOT asserted: the issue proposed telling the agent that on_hit means "the
        // tagged creature damaging anything". That is NARROWER than what the template does, and
        // would recreate the same doubt from the other side — an agent believing the creature must
        // land the hit has every reason to fall back to proximity, exactly as this one did.
        // Stated over EVERY entry that takes a TRIGGER rather than the one that does today.
        // `impulse` owns it now — and it is the very ability was about — but a second
        // trigger-taking entry inheriting the doubt is the failure this is guarding, so the rule
        // has to cover the entry that does not exist yet.
        // (My first cut asked on_hit, which is a TRIGGER VALUE rather than an entry that takes
        // one, and got null. The message named it, which is why the assertion carries one.)
        var withTrigger = EffectCatalogue.load().all().stream()
                .filter(e -> e.paramDocs().containsKey("TRIGGER"))
                .toList();
        assertFalse(withTrigger.isEmpty(),
                "no catalogue entry documents a TRIGGER parameter any more, so the trigger choice "
                + "is undocumented and #99's failure has nowhere to be prevented");
        for (var entry : withTrigger) {
            checkTriggerDoc(entry.id(), entry.paramDocs().get("TRIGGER"));
        }
    }

    /** The four things a TRIGGER doc has to say, each one a measured failure. */
    private static void checkTriggerDoc(String id, String doc) {
        assertNotNull(doc, id + " documents a TRIGGER parameter with no text");

        assertTrue(doc.contains("does NOT need the creature to attack"),
                id + ": #99 — the doc no longer answers the doubt, so an agent reasoning on its own reaches "
                + "the same wrong conclusion and trades the right trigger for a working one: "
                + doc);
        assertTrue(doc.contains("VICTIM") && doc.contains("who dealt the damage"),
                id + ": …and it has to say WHY — that it keys on the victim's own hurt state and never "
                + "asks who hit them. Without the mechanism, 'do not talk yourself out of it' is "
                + "an instruction to ignore its own reasoning, which is not something a model can "
                + "reliably do: " + doc);
        assertTrue(doc.contains("no AI at all"),
                id + ": …and that the strongest form was MEASURED, not argued. The agent's worry was a "
                + "creature that cannot attack; the answer is a creature that cannot act: " + doc);

        // THE PRECEDENCE RULE, which warns about explicitly: "the dragon knocks everyone back
        // every 5 seconds" contains a contact verb AND a time, and the time must win or the fix
        // for on_hit breaks the periodic case. Adding contact verbs was the tempting fix and the
        // issue rules it out — the example was already there and already chosen.
        assertTrue(doc.contains("TIME WINS"),
                id + ": a wish naming both a contact verb and a time has no stated answer, so telling the "
                + "agent to trust on_hit harder is exactly what breaks 'every 5 seconds': " + doc);
        assertTrue(doc.contains("every 5 seconds"),
                id + ": …and the precedence rule needs the child's own words beside it, not a taxonomy "
                + "sentence: " + doc);
    }

    @Test
    void theSixAbilitiesTheAgentNeverReachedForKeepTheirExamples() {
        //. These six were counted as GAPS in the ability table and were not: every one is a
        // single vanilla command the ${COMMAND} parameter already substitutes raw. What was
        // missing was not a capability, it was an EXAMPLE — the §4f rule 2 measurement, where the
        // agent scored 4/8 on impulse with ZERO wrong picks, i.e. it was never choosing badly, it
        // was never choosing at all.
        //
        // This test exists because those examples are permanent context on every call, so the
        // first "trim the catalogue" pass is exactly what would delete them — and deleting them
        // reverts a measured fix while every other test here stays green, since the catalogue
        // would still load, render and substitute perfectly.
        String doc = EffectCatalogue.load().get("on_hit").paramDocs().get("COMMAND");
        assertNotNull(doc, "COMMAND is the parameter that carries all six");
        for (String fragment : new String[]{
                "damage @s",                 // elemental_damage — the damage TYPE is a parameter
                "playsound",                 // sound_emote
                "attribute @s",              // transform_grow
                "tp @s ^ ^ ^-2",             // blink_strike — facing-relative, so no arithmetic
                "minecraft:invisibility",    // invisibility (really a status_effect)
                "clear @s"}) {               // item_steal — an approximation, see below
            assertTrue(doc.contains(fragment),
                    "#102: the COMMAND doc lost the example for '" + fragment + "'. It is not a "
                    + "new feature — it already works — but a category with no example in the "
                    + "child's own words is one the agent measurably does not reach for.");
        }
        // The issue's own "verify before closing" clause: `clear` REMOVES the item, it does not
        // transfer it. Shipping it as a steal would be a promise the command does not keep, so the
        // honest phrasing is part of the example rather than a note somewhere else.
        assertTrue(doc.contains("REMOVES"),
                "#102: item_steal is an APPROXIMATION and the doc must say so — otherwise the "
                + "agent tells the child it stole the sword and the child watches it vanish");
    }

    @Test
    void everyEntryDeclaresWhoAndWhen() {
        // WHO is the axis the demand varies along, and leaving it implicit is precisely how an
        // entry came to do the opposite of every wish it advertised. Prose in a summary is not a
        // field anybody can check.
        for (EffectCatalogue.Effect e : EffectCatalogue.load().all()) {
            assertFalse(e.who().isBlank(), e.id() + " does not say WHO the effect lands on");
            assertFalse(e.when().isBlank(), e.id() + " does not say WHEN it fires");
        }
    }

    @Test
    void everySubstitutableTokenIsARealParameter() {
        // The substitution manifest is what the compiled (L2) layer trusts. A token listed there
        // that is not a real parameter would be substituted into an artifact by name and match
        // nothing -- producing a datapack aimed at the wrong creature, which is exactly the
        // failure the manifest exists to prevent.
        for (EffectCatalogue.Effect e : EffectCatalogue.load().all()) {
            for (String token : e.substitutable()) {
                assertTrue(e.allParams().contains(token),
                        e.id() + " lists '" + token + "' as substitutable but has no such "
                                + "parameter; it takes " + e.params());
            }
        }
    }

    @Test
    void everyTemplatePlaceholderIsDeclaredAsAParameter() {
        // The other direction, and the one that bites later: a template using ${RADIUS} that the
        // catalogue never declares can never be filled, so the effect is permanently unusable --
        // and it fails at render time, long after the entry looked fine in review.
        EffectCatalogue cat = EffectCatalogue.load();
        for (EffectCatalogue.Effect e : cat.all()) {
            var m = java.util.regex.Pattern.compile("\\$\\{([A-Z_]+)}")
                    .matcher(cat.templateSource(e.id()));
            while (m.find()) {
                assertTrue(e.allParams().contains(m.group(1)),
                        e.id() + "'s template uses ${" + m.group(1) + "} but the catalogue does "
                                + "not declare it, so nothing can ever fill it");
            }
        }
        // Two, not three. A self_aura entry was written and then DROPPED: its only advertised wish
        // ("make the mobs come out already zapped") names no creature to tag and no vanilla
        // effect, so nothing could ever instantiate it. An entry the demand cannot reach makes the
        // agent's selection surface worse, not better.
        assertTrue(cat.all().size() >= 2, "the catalogue is keyed on WHO the effect lands on, and "
                + "the reachable evidence shows two: the victim, and the creature's allies");
    }

    /**
     * Every entry must carry a sentence a CHILD can act on, and it must name the creature.
     *
     * <p>"It happens when something takes damage near this creature" is the judge-facing `when`. Read
     * aloud to a seven-year-old it is not an instruction, and an instruction is the whole point: this
     * line is what he follows when nothing seems to be happening.
     */
    @Test
    void everyEntryTellsTheChildHowToSetItOff() {
        for (EffectCatalogue.Effect e : EffectCatalogue.load().all()) {
            String c = e.childWhen();
            assertNotNull(c, e.id() + " has no child_when");
            assertFalse(c.isBlank(), e.id() + " has a blank child_when");
            assertTrue(c.contains("${TAG}"),
                    e.id() + "'s child_when never names the creature, so the child is told THAT "
                            + "something happens but not what to do it to: " + c);
            // Parameter names are judge-facing. A child cannot read "every PERIOD_TICKS ticks".
            for (String p : e.params()) {
                assertFalse(c.contains(p) && !"TAG".equals(p),
                        e.id() + "'s child_when leaks the parameter name '" + p + "': " + c);
            }
        }
    }

    @Test
    void noTemplateNamesAPlaceholderInsideAComment() {
        // colour_cycle's header comment said "writes ${STEPS}". render() substitutes every
        // occurrence, comments included, so a multi-line value landed in a comment and its second
        // line was a bare command outside any block — "a bug in the catalogue", live, on the
        // first call. Every other check here was green, because the comment was well-formed text.
        EffectCatalogue cat = EffectCatalogue.load();
        for (EffectCatalogue.Effect e : cat.all()) {
            for (String line : cat.templateSource(e.id()).split("\n")) {
                if (line.strip().startsWith("#")) {
                    assertFalse(line.contains("${"), e.id() + " names a placeholder in a comment, "
                            + "which render() fills in too: " + line);
                }
            }
        }
    }
}
