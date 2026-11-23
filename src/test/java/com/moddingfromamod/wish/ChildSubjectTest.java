package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.moddingfromamod.datapack.EffectCatalogue;
import com.moddingfromamod.datapack.TriggerShape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * the 👉 "how to set it off" line named the creature by its internal id —
 * {@code "ember_the_flying_dragon_24a470 breathes it out of its MOUTH"} — because the catalogue's
 * {@code ${TAG}} was filled with the selector tag. A child knows the creature by its NAME.
 */
class ChildSubjectTest {

    /** What an internal id looks like: a Builder/Apprentice make is {@code <slug>_<6 hex>}. */
    static final Pattern INTERNAL_ID = Pattern.compile("_[0-9a-f]{6}\\b");

    static final String ID = "ember_the_flying_dragon_24a470";
    static final Function<String, String> REGISTRY =
            Map.of(ID, "Ember the Flying Dragon")::get;
    static final Function<String, String> EMPTY = id -> null;

    @Test
    @DisplayName("the issue's own line names the dragon, not its id")
    void theIssueLine() {
        String line = ChildSubject.say(
                "${TAG} breathes it out of its MOUTH — stand in front of it to see.", ID, REGISTRY);
        assertEquals("Ember the Flying Dragon breathes it out of its MOUTH — stand in front of it to see.",
                line);
    }

    @Test
    @DisplayName("every effect's child_when, filled from an internal id, names no internal id")
    void everyCatalogueSentence() {
        for (EffectCatalogue.Effect e : EffectCatalogue.load().all()) {
            if (e.childWhen() == null || e.childWhen().isBlank()) {
                continue;
            }
            for (Function<String, String> names : java.util.List.of(REGISTRY, EMPTY)) {
                for (String tag : new String[] {ID, "wishmob_" + ID}) {
                    String line = ChildSubject.say(e.childWhen(), tag, names);
                    assertFalse(INTERNAL_ID.matcher(line).find(),
                            e.id() + ": an internal id reached the child: " + line);
                    assertFalse(line.contains("${"), e.id() + ": a placeholder reached the child: " + line);
                    assertFalse(line.contains("wishmob"), e.id() + ": machinery reached the child: " + line);
                }
            }
        }
    }

    @Test
    @DisplayName("the creature's name when known; its words, not its id, when not")
    void names() {
        assertEquals("Ember the Flying Dragon", ChildSubject.name(ID, REGISTRY));
        assertEquals("Ember the Flying Dragon", ChildSubject.name("wishmob_" + ID, REGISTRY));
        assertEquals("ember the flying dragon", ChildSubject.name(ID, EMPTY));
        // A plain agent-chosen tag is already the child's word; its case is kept (TriggerShapeTest).
        assertEquals("dragon", ChildSubject.name("dragon", EMPTY));
        assertEquals("Spikor", ChildSubject.name("Spikor", EMPTY));
        // A word that merely LOOKS hex is not an id suffix.
        assertEquals("spikor facade", ChildSubject.name("spikor_facade", EMPTY));
        // The tag every wish creature wears names no one creature, and our own machinery is no name.
        assertNull(ChildSubject.name("wishmob", EMPTY));
        assertNull(ChildSubject.name("mfam_dw_spider", EMPTY));
        assertNull(ChildSubject.name(null, EMPTY));
        assertNull(ChildSubject.name("  ", EMPTY));
    }

    @Test
    @DisplayName("no name → 'it', and a sentence that starts with its subject starts with a capital")
    void fallbackAndCapital() {
        assertEquals("It happens when it HITS something.",
                ChildSubject.say("It happens when ${TAG} HITS something.", "wishmob", EMPTY));
        assertEquals("It does it over and over all by itself.",
                ChildSubject.say("${TAG} does it over and over all by itself.", null, EMPTY));
        assertEquals("Dragon leaves a patch behind.",
                ChildSubject.say("${TAG} leaves a patch behind.", "dragon", EMPTY));
        assertEquals("It happens to Ember the Flying Dragon's friends, the whole time they stay close to "
                + "Ember the Flying Dragon.",
                ChildSubject.say("It happens to ${TAG}'s friends, the whole time they stay close to ${TAG}.",
                        ID, REGISTRY));
    }

    @Test
    @DisplayName("an authored pack's sentence names the creature too, not the tag in its selector")
    void authoredPack() {
        Function<String, String> cat = id -> "on_hit".equals(id) ? "It happens when ${TAG} HITS something." : null;
        String src = "clock tick\n    /execute as @e[tag=wishmob_" + ID + "] at @s as "
                + "@e[nbt={HurtTime:10s},distance=..5] run damage @s 4 minecraft:magic\nend\n";
        assertEquals("It happens when Ember the Flying Dragon HITS something.",
                TriggerShape.sentenceFor(src, cat, REGISTRY));
        String noRegistry = TriggerShape.sentenceFor(src, cat);
        assertFalse(INTERNAL_ID.matcher(noRegistry).find(), noRegistry);
    }

    @Test
    @DisplayName("a thing with no name of its own is said as its id's words, without the id suffix")
    void words() {
        assertEquals("cozy house", ChildSubject.words("cozy_house_3fa2b1"));
        assertEquals("sky castle", ChildSubject.words("sky_castle"));
        assertEquals("spikor facade", ChildSubject.words("spikor_facade"));
        // A six-digit NUMBER is a suffix too (it is hex): an id built from a reused world's game time loses it at
        // 100000, and a five-digit one does not — the trap ReferentByAgentGameTest fell into.
        assertEquals("gt ref tower", ChildSubject.words("gt_ref_tower_106230"));
        assertEquals("gt ref tower 99999", ChildSubject.words("gt_ref_tower_99999"));
        assertEquals("gt ref tower t106230", ChildSubject.words("gt_ref_tower_t106230"));
        assertEquals("", ChildSubject.words(null));
    }
}
