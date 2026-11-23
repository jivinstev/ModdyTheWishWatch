package com.moddingfromamod.player;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The vocabulary of what the child can ask to be — and the feel rules inside it. */
class PlayerPowersTest {

    @Test
    @DisplayName("every player wish the corpus actually contains resolves to a power")
    void theCorpusWishesAreCovered() {
        // The real ones, from the action log and. If a future edit drops one of these the
        // class stops answering a wish he has genuinely made.
        for (String asked : List.of("giant", "fast", "super_fast", "jump", "strong",
                "invincible", "water breathing", "never_die", "tiny", "normal size")) {
            assertNotNull(PlayerPowers.find(asked), "no power for '" + asked + "'");
        }
    }

    @Test
    @DisplayName("only an INFINITE effect outlives his death")
    void onlyAForeverEffectSurvivesDeath() {
        assertTrue(PlayerPowers.lastsThroughDeath(PlayerPowers.INFINITE),
                "a power granted forever must come back when he respawns");
        assertFalse(PlayerPowers.lastsThroughDeath(600),
                "a thirty-second effect must still end on death, as vanilla has it");
        assertFalse(PlayerPowers.lastsThroughDeath(0));
        assertFalse(PlayerPowers.lastsThroughDeath(Integer.MAX_VALUE),
                "a very long effect is still not a forever one");
    }

    @Test
    @DisplayName("a power is refused, not approximated, when we do not know it")
    void unknownIsRefused() {
        assertNull(PlayerPowers.find("teleportation"));
        String msg = PlayerPowers.unknown("teleportation");
        assertTrue(msg.contains("teleportation"), "the refusal must quote what was asked");
        assertTrue(msg.contains("giant"), "the refusal must list the real options");
    }

    @Test
    @DisplayName("the child's spelling is not a puzzle")
    void aliasesAndSpacingResolve() {
        assertEquals("water_breathing", PlayerPowers.find("water breathing").id());
        assertEquals("water_breathing", PlayerPowers.find("water-breathing").id());
        assertEquals("giant", PlayerPowers.find("HUGE").id());
        assertEquals("invincible", PlayerPowers.find("invinsible").id(),
                "a seven-year-old's spelling of the word he most wants");
    }

    @Test
    @DisplayName("'never die' means he still gets hurt — it is NOT invincibility")
    void neverDieIsNotInvincible() {
        // "let me get hurt but NEVER die" is a real wish, and it ASKS to be hurt. Answering it with
        // invincibility would grant something he did not want and quietly remove the part he did.
        PlayerPowers.Spec neverDie = PlayerPowers.find("never_die");
        assertTrue(neverDie.neverDie());
        assertTrue(neverDie.buffs().isEmpty(),
                "never_die must not add damage resistance — the hurting is the point");

        PlayerPowers.Spec invincible = PlayerPowers.find("invincible");
        assertTrue(invincible.neverDie());
        assertFalse(invincible.buffs().isEmpty(), "invincible should actually stop the damage");
    }

    @Test
    @DisplayName("a big jump lands softly, because being hurt by your own wish is the thing to avoid")
    void jumpCarriesItsOwnLanding() {
        List<PlayerPowers.Buff> buffs = PlayerPowers.find("jump").buffs();
        assertTrue(buffs.stream().anyMatch(b -> b.effectId().endsWith("jump_boost")));
        assertTrue(buffs.stream().anyMatch(b -> b.effectId().endsWith("slow_falling")),
                "a jump high enough to be fun is high enough to hurt on the way down");
    }

    @Test
    @DisplayName("sizes are real sizes, and reversible")
    void sizesAreSaneAndUndoable() {
        assertTrue(PlayerPowers.find("giant").scale() > 1.0);
        assertTrue(PlayerPowers.find("tiny").scale() < 1.0);
        assertEquals(1.0, PlayerPowers.find("normal_size").scale(),
                "there must be a way back to his own size");
        for (String id : List.of("giant", "tiny", "normal_size")) {
            assertTrue(PlayerPowers.scaleAllowed(PlayerPowers.find(id).scale()),
                    id + " is outside what vanilla accepts");
        }
    }

    @Test
    @DisplayName("no attribute is named as a string anywhere in the vocabulary")
    void noAttributeIdsHere() {
        // The measured defect this class exists to make impossible: the agent writing
        // `attribute … minecraft:scale`, which does not exist, and retrying as `generic.scale`.
        // Buffs are potion effects; sizes are a NUMBER, applied through a typed handle by the tool.
        for (String id : PlayerPowers.names()) {
            for (PlayerPowers.Buff b : PlayerPowers.find(id).buffs()) {
                assertFalse(b.effectId().contains("generic."),
                        id + " names an attribute; powers carry effects and a scale number only");
                assertTrue(b.effectId().startsWith("minecraft:"),
                        id + " has an unqualified effect id: " + b.effectId());
            }
        }
    }

    @Test
    @DisplayName("ids and aliases are unique, so one word cannot mean two powers")
    void nothingIsAmbiguous() {
        Set<String> seen = new HashSet<>();
        for (String id : PlayerPowers.names()) {
            assertTrue(seen.add(id), "duplicate power id: " + id);
            for (String alias : PlayerPowers.find(id).aliases()) {
                assertTrue(seen.add(alias), "'" + alias + "' means two different powers");
            }
        }
    }

    @Test
    @DisplayName("every power says what it does, in words a child would hear")
    void everyPowerIsExplainable() {
        for (String id : PlayerPowers.names()) {
            String blurb = PlayerPowers.find(id).blurb();
            assertNotNull(blurb);
            assertFalse(blurb.isBlank(), id + " has no blurb");
            assertFalse(blurb.contains("minecraft:"), id + "'s blurb leaks a registry id");
        }
    }

    @Test
    @DisplayName("a level makes an effect stronger and a size bigger — one argument for both")
    void levelIsGeneralNotJumpSpecific() {
        assertEquals(0, PlayerPowers.Level.NORMAL.bonus());
        assertTrue(PlayerPowers.Level.SUPER.bonus() > PlayerPowers.Level.EXTRA.bonus(),
                "super must beat extra or the word means nothing");

        double giant = PlayerPowers.find("giant").scale();
        assertTrue(PlayerPowers.Level.SUPER.stretch(giant) > giant, "super giant is not bigger");
        assertTrue(PlayerPowers.scaleAllowed(PlayerPowers.Level.SUPER.stretch(giant)),
                "super giant went past what vanilla accepts");
    }

    @Test
    @DisplayName("super TINY is smaller, not bigger — the level follows the power's direction")
    void levelRespectsDirection() {
        double tiny = PlayerPowers.find("tiny").scale();
        assertTrue(PlayerPowers.Level.SUPER.stretch(tiny) < tiny, "super tiny made him larger");
        assertTrue(PlayerPowers.scaleAllowed(PlayerPowers.Level.SUPER.stretch(tiny)));
    }

    @Test
    @DisplayName("an unknown level is normal, not a refusal")
    void unknownLevelIsHarmless() {
        // Unlike an unknown POWER, which would grant the wrong thing, an unrecognised intensity is
        // not worth failing a wish over.
        assertEquals(PlayerPowers.Level.NORMAL, PlayerPowers.level("banana"));
        assertEquals(PlayerPowers.Level.NORMAL, PlayerPowers.level(null));
        assertEquals(PlayerPowers.Level.SUPER, PlayerPowers.level("SUPER"));
        assertEquals(PlayerPowers.Level.SUPER, PlayerPowers.level("mega"));
        assertEquals(PlayerPowers.Level.EXTRA, PlayerPowers.level("very"));
    }

    @Test
    @DisplayName("#1417/#1422: the corpus's player wishes that had no power now resolve to one")
    void theAttributePowersAnswerTheCorpus() {
        // Words from the synthetic corpus (syn-1-060, nm-026, syn-5-060, syn-4-061, syn-3-060,
        // syn-3-063), as an agent would name them.
        assertEquals("no_fall_damage", PlayerPowers.find("fallproof").id());
        assertEquals("heavy_falls", PlayerPowers.find("double fall damage").id());
        assertEquals("no_knockback", PlayerPowers.find("cant be knocked back").id());
        assertEquals("never_hungry", PlayerPowers.find("no hunger").id());
        assertEquals("swim_fast", PlayerPowers.find("super swimmer").id());
        assertEquals("fast_mining", PlayerPowers.find("mine fast").id());
    }

    @Test
    @DisplayName("#1422: an attribute power sets a typed kind, and taking it away restores vanilla's default")
    void attributePowersAreTypedAndUndoable() {
        for (String id : List.of("no_fall_damage", "heavy_falls", "no_knockback", "fast_mining", "swim_fast")) {
            PlayerPowers.Spec s = PlayerPowers.find(id);
            assertFalse(s.stats().isEmpty(), id + " sets no attribute");
            for (PlayerPowers.Stat st : s.stats()) {
                assertNotEquals(st.kind().vanillaDefault(), st.value(), 1e-9,
                        id + " sets " + st.kind() + " to its own default, so it does nothing");
            }
            assertNull(s.scale(), id + " must not touch size (giant owns size, step and safe-fall)");
        }
        assertEquals(1.0, PlayerPowers.StatKind.FALL_DAMAGE.vanillaDefault());
        assertEquals(0.0, PlayerPowers.StatKind.KNOCKBACK_RESISTANCE.vanillaDefault());
        assertTrue(PlayerPowers.find("giant").stats().isEmpty(),
                "the pre-#1422 powers keep their shape: no attribute stats");
    }
}
