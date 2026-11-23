package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * the trigger is derived from the program, so the ground truth is the shipped programs.
 *
 * <p>The strong test here is the first one: it is a check against <b>real data we already have</b>
 * rather than against fixtures written to match the implementation. Every trigger template in the
 * catalogue must classify as its own id, and if a signature is wrong the template that disagrees
 * names itself.
 */
class TriggerShapeTest {

    private static String template(String id) throws Exception {
        try (InputStream in = TriggerShapeTest.class.getResourceAsStream("/effects/" + id + ".cbs")) {
            assertNotNull(in, id + ".cbs is not on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("every shipped trigger template classifies as its own id")
    void theShippedTemplatesAreTheGroundTruth() throws Exception {
        // Four of the five the catalogue exposes as TRIGGERS. proximity is deliberately absent
        // and has its own test below — its scaffold carries no signature, which is a finding
        // rather than an omission. The effect templates (inflict_nearby, buff_allies,
        // lingering_zone, …) are not triggers at all.
        List<String> wrong = new ArrayList<>();
        for (String id : List.of("on_hit", "on_death", "on_low_health", "periodic")) {
            String got = TriggerShape.of(template(id));
            if (!id.equals(got)) {
                wrong.add(id + " -> " + got);
            }
        }
        // Collected, so "one signature is wrong" and "the ordering broke every one" are
        // distinguishable — failing on the first cannot tell them apart.
        assertTrue(wrong.isEmpty(),
                "these templates did not classify as themselves: " + wrong + ". The signature has "
                + "to match what the PROGRAM does, and the shipped program is the only ground "
                + "truth there is.");
    }

    @Test
    @DisplayName("proximity has no signature of its own, and that is the answer")
    void proximityHasNoSignatureOfItsOwn() throws Exception {
        // ⚠ THIS ASSERTION IS DELIBERATE. Read proximity.cbs before changing it — the entire
        // scaffold is `clock tick / execute as @e[tag=X] at @s run ${COMMAND} / end`, and every
        // discriminating detail of "whatever is near this creature" lives inside ${COMMAND},
        // which is the child's ABILITY rather than the trigger.
        //
        // The first cut keyed proximity on `distance=` and was wrong twice over: proximity.cbs
        // does not contain it, while on_hit.cbs (the victim's radius) and on_death.cbs (the
        // shadow marker's 2.5-block check) both do. Asserted here so the mistake cannot recur.
        assertTrue(template("proximity").contains("clock tick"));
        assertFalse(template("proximity").contains("distance="),
                "proximity.cbs has grown a distance= selector, so it may now have a signature "
                + "of its own — re-derive it rather than leaving this test asserting a stale null");
        assertTrue(template("on_hit").contains("distance=")
                        && template("on_death").contains("distance="),
                "distance= is no longer in on_hit/on_death, which is why keying proximity on it "
                + "was wrong; if that changed, the reasoning in TriggerShape needs revisiting");

        assertNull(TriggerShape.of(template("proximity")),
                "what is left of proximity's shape is 'tick the tagged creature, gate on nothing' "
                + "— equally the shape of a permanent effect on the creature itself. Taking it as "
                + "proximity is a residual guess, and telling a child \"it happens when something "
                + "comes NEAR\" about an always-on ability is the confident-wrong sentence #84 "
                + "exists to remove.");
    }

    @Test
    @DisplayName("nothing recognisable means null, never a guess")
    void anUnknownProgramGetsNoSentence() {
        // BuiltAbility's own rule: "Empty, not a fallback sentence: a confident guess is exactly
        // the failure this class exists to remove." A wrong sentence about his own creature is
        // worse than no sentence, because he cannot tell it is wrong.
        assertNull(TriggerShape.of(null));
        assertNull(TriggerShape.of(""));
        assertNull(TriggerShape.of("clock tick\n    /say hello\nend\n"),
                "a program that just says something fires on nothing in particular");
        assertNull(TriggerShape.of("reset\n    /scoreboard objectives add x dummy\nend\n"),
                "declaring a scoreboard is not a trigger");
    }

    @Test
    @DisplayName("a comment cannot make a program look like a trigger it is not")
    void itReadsTheCodeNotTheProse() {
        // Every shipped template opens with a comment block, and on_hit.cbs's own header contains
        // the words HurtTime, scoreboard and damage_taken. Matching against that would classify a
        // program by what its author wrote ABOUT it — which is the declaration exists to stop
        // trusting.
        String prose = "# this one checks HurtTime and fires on a death, honestly it does\n"
                + "clock tick\n    /say nothing actually happens\nend\n";
        assertNull(TriggerShape.of(prose),
                "the signature was found in a comment, so the derivation is reading the claim "
                + "rather than the code — exactly the thing it replaces");
    }

    @Test
    @DisplayName("a fake-player sigil is code, not a comment")
    void theHashIsNotAlwaysAComment() {
        // CBScript writes fake players as `#tick`. A naive comment-strip that cut everything from
        // the first '#' would delete the counter line that IS the periodic signature, and periodic
        // would silently stop being detectable.
        String periodic = "reset\n    /scoreboard objectives add c dummy\nend\n"
                + "clock tick\n"
                + "    /scoreboard players add #tick c 1\n"
                + "    /execute if score #tick c matches 100.. run say ping\n"
                + "    /execute if score #tick c matches 100.. run scoreboard players set #tick c 0\n"
                + "end\n";
        assertEquals("periodic", TriggerShape.of(periodic),
                "the #tick counter was cut as a comment, so a hand-written periodic program is "
                + "undetectable");
    }

    @Test
    @DisplayName("hand-written programs are detected by semantics, not by our scoreboard names")
    void itWorksOnAProgramTheAgentWroteItself() {
        // The case this whole class is FOR. An authored pack uses its own names, so a signature
        // keyed on `mfam_clock` or `mfam_hp` would work on a rendered template and fail on the
        // only input that matters.
        assertEquals("on_hit", TriggerShape.of(
                "clock tick\n    /execute as @e[tag=dragon] at @s as "
                + "@e[nbt={HurtTime:10s},distance=..5] run damage @s 4 minecraft:magic\nend\n"),
                "HurtTime is the only way to know something was just damaged that works for mobs "
                + "too, so any correct on_hit contains it whoever wrote it");
        // And the honest limit of that, in the same test rather than a comment somewhere else:
        // a hand-written proximity ability is NOT recoverable. This program is a real one — poison
        // whatever is within four blocks of the snake — and it is byte-for-byte the shape of a
        // creature that simply glows forever, once you look only at the trigger.
        assertNull(TriggerShape.of(
                "clock tick\n    /execute as @e[tag=snake] at @s run "
                + "effect give @a[distance=..4] minecraft:poison 5 1\nend\n"),
                "a bare tick loop is not evidence of proximity, whoever wrote it");
    }

    @Test
    @DisplayName("the subject comes from the program's own selector, and three forms are refused")
    void theSentenceIsAboutWhoTheProgramSaysItIsAbout() {
        assertEquals("dragon", TriggerShape.subjectTag(
                "clock tick\n    /execute as @e[tag=dragon] at @s run say hi\nend\n"));
        // Case PRESERVED — a recovered tag is spoken to a child, so lowercasing renames his
        // creature. (The signature match is case-insensitive; this is why they are separate.)
        assertEquals("Spikor", TriggerShape.subjectTag(
                "clock tick\n    /execute as @e[tag=Spikor] at @s run say hi\nend\n"));
        // A NEGATED selector names who it is NOT. Using it inverts the sentence — on_hit's own
        // template contains `tag=!${TAG}` for the victim, so this is the common case, not a corner.
        assertNull(TriggerShape.subjectTag(
                "clock tick\n    /execute as @e[tag=!dragon] at @s run say hi\nend\n"),
                "a negated tag was taken as the subject, so the sentence is about the wrong thing");
        // An unsubstituted placeholder is a raw template, not a name.
        assertNull(TriggerShape.subjectTag(
                "clock tick\n    /execute as @e[tag=${TAG}] at @s run say hi\nend\n"));
        // Our own machinery. "It happens when mfam_dw_spider DIES" is worse than no name at all.
        assertNull(TriggerShape.subjectTag(
                "clock tick\n    /execute as @e[tag=mfam_dw_spider] at @s run say hi\nend\n"));
        assertNull(TriggerShape.subjectTag(null));
    }

    @Test
    @DisplayName("the sentence is the catalogue's own wording, with a subject that is never a guess")
    void theSentenceReusesTheCatalogueWording() {
        // The catalogue is the single definition of what a child is told, so an authored pack hears
        // the same wording as a ready-made one and cannot drift from it.
        java.util.function.Function<String, String> cat =
                id -> "on_hit".equals(id) ? "It happens when ${TAG} HITS something." : null;

        assertEquals("It happens when dragon HITS something.", TriggerShape.sentenceFor(
                "clock tick\n    /execute as @e[tag=dragon] at @s as "
                + "@e[nbt={HurtTime:10s},distance=..5] run damage @s 4 minecraft:magic\nend\n",
                cat));

        // No name recoverable → a subject true of anything. The trigger is still right; a GUESSED
        // creature would be the spider-bite failure, and a raw ${TAG} would reach the child.
        String noName = TriggerShape.sentenceFor(
                "clock tick\n    /execute as @e[nbt={HurtTime:10s}] at @s run "
                + "damage @s 4 minecraft:magic\nend\n", cat);
        assertEquals("It happens when it HITS something.", noName);
        assertFalse(noName.contains("${"), "a template placeholder reached a child-facing sentence");

        // Unrecognised program, or a trigger the catalogue has no sentence for: empty, not a guess.
        assertEquals("", TriggerShape.sentenceFor("clock tick\n    /say hello\nend\n", cat));
        assertEquals("", TriggerShape.sentenceFor(
                "clock tick\n    /execute as @e[nbt={HurtTime:10s}] run say ow\nend\n",
                id -> null),
                "the catalogue had no wording for it, so there is nothing honest to say");
    }

    @Test
    @DisplayName("a health readout is not an on-low-health trigger")
    void readingHealthIsNotTheSameAsFiringOnIt() {
        // Without the comparison, a program that merely displays health would be announced to the
        // child as "it happens when he is nearly beaten" — a sentence about an ability that fires
        // whenever, which is the wrong-sentence failure rather than the no-sentence one.
        assertNull(TriggerShape.of(
                "clock tick\n    /execute as @e[tag=boss] run scoreboard players add @s hp 0\nend\n"),
                "reading Health with no comparison is a readout");
    }
}
