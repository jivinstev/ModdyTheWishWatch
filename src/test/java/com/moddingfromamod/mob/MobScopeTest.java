package com.moddingfromamod.mob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moddingfromamod.mob.MobScope.Scope;
import org.junit.jupiter.api.Test;

/**
 * Gate A, over the phrasings a seven-year-old actually uses.
 *
 * <p>Every case here is either a sentence quoted or the obvious neighbour of one.
 * The rule the whole file is really testing is the asymmetry: <b>when it could honestly be either,
 * take the narrow reading and ask</b>, because reading one pet as a category makes every zombie in
 * the world invincible forever and nothing shows him why.
 */
class MobScopeTest {

    private static Scope scopeOf(String text) {
        return MobScope.of(text).scope();
    }

    // ── one animal ──────────────────────────────────────────────────────────────────────────

    @Test
    void aPronounIsOneAnimal() {
        for (String said : new String[] {
                "make him bigger", "make her bigger", "make him even huger",
                "make it bigger", "make that one glow", "make him invincible"}) {
            assertEquals(Scope.ONE, scopeOf(said), said);
            assertFalse(MobScope.of(said).ask(), said + " needs no question");
        }
    }

    @Test
    void pointingAtOneIsOneAnimal() {
        assertEquals(Scope.ONE, scopeOf("make that cow huge"));
        assertEquals(Scope.ONE, scopeOf("make the zombie super strong"));
        assertEquals(Scope.ONE, scopeOf("make that skeleton fast"));
    }

    @Test
    void aHandleSettlesItWithNoQuestion() {
        assertEquals(Scope.ONE, scopeOf("make @wither_storm bigger"));
        assertFalse(MobScope.of("make @wither_storm bigger").ask());
    }

    // ── the category ────────────────────────────────────────────────────────────────────────

    @Test
    void aBarePluralMeansTheKind() {
        // The grammar, not a word list: "zombies" is generic, "the zombies" is some particular ones.
        assertEquals(Scope.CATEGORY, scopeOf("make zombies bigger"));
        assertEquals(Scope.CATEGORY, scopeOf("make all zombies bigger"));
        assertEquals(Scope.CATEGORY, scopeOf("make creepers survive their explosions"));
        for (String said : new String[] {"make zombies bigger", "make all zombies bigger"}) {
            assertFalse(MobScope.of(said).ask(), said + " is not ambiguous");
        }
    }

    @Test
    void itWorksForAModdedCreatureItHasNeverHeardOf() {
        // A classifier that only knew vanilla would read this as one wither storm. New mobs arrive
        // with every mod he installs, so this is a shape rule rather than a species list.
        assertEquals(Scope.CATEGORY, scopeOf("make wither storms bigger"));
        assertEquals(Scope.CATEGORY, scopeOf("make gigaillagers slower"));
    }

    // ── a group standing over there ─────────────────────────────────────────────────────────

    @Test
    void sayingWhereTheyAreMeansTheOnesThere() {
        assertEquals(Scope.GROUP, scopeOf("make all the zombies near me glow"));
        assertEquals(Scope.GROUP, scopeOf("make the cows around here big"));
        assertEquals(Scope.GROUP, scopeOf("make these zombies glow"));
        assertFalse(MobScope.of("make all the zombies near me glow").ask());
    }

    // ── the tie-break, which is the point of the whole class ────────────────────────────────

    @Test
    void aDefinitePluralIsGenuinelyEitherSoItAsks() {
        MobScope.Reading r = MobScope.of("make the zombies bigger");
        assertEquals(Scope.GROUP, r.scope(), "the narrow of the two readings");
        assertTrue(r.ask(), "…and he is asked, because it could have been the kind");
    }

    @Test
    void moreThanOneButNotWhichOnesAsks() {
        // "No, ALL of them" — an earlier issue's natural refinement, which today produces the identical
        // decaying sweep and gives him no way to tell the difference.
        for (String said : new String[] {"no, all of them", "make them all invincible"}) {
            assertEquals(Scope.GROUP, scopeOf(said), said);
            assertTrue(MobScope.of(said).ask(), said + " must ask");
        }
    }

    @Test
    void knowingNothingIsNarrowAndAsks() {
        // Silence is not a licence to change the world forever.
        for (String said : new String[] {"", "   ", null, "make it awesome"}) {
            assertEquals(Scope.ONE, scopeOf(said), String.valueOf(said));
        }
        assertTrue(MobScope.of("").ask());
        assertTrue(MobScope.of(null).ask());
    }

    // ── the guard ───────────────────────────────────────────────────────────────────────────

    @Test
    void claimingTheWholeSpeciesAboutOnePetIsRefused() {
        // an earlier issue's headline: "make him invincible" read as a category makes EVERY zombie invincible
        // forever. This is the one mistake worth refusing rather than merely narrating.
        assertTrue(MobScope.overreaches(Scope.CATEGORY, MobScope.of("make him invincible")));
        assertTrue(MobScope.overreaches(Scope.CATEGORY, MobScope.of("make that cow huge")));
        assertTrue(MobScope.overreaches(Scope.GROUP, MobScope.of("make him bigger")));
    }

    @Test
    void readingTooNarrowIsAllowedThroughBecauseItOnlyDecays() {
        // Deliberately asymmetric. An agent that answers "make zombies bigger" with one zombie
        // under-delivers; the child sees something and can ask again. There is no permanent harm to
        // refuse, and refusing both directions would double the questions for no safety.
        assertFalse(MobScope.overreaches(Scope.ONE, MobScope.of("make zombies bigger")));
        assertFalse(MobScope.overreaches(Scope.GROUP, MobScope.of("make zombies bigger")));
    }

    @Test
    void anAmbiguousSentenceIsNeverRefused() {
        // It is the ask rung's business. Refusing here would turn "I am not sure" into a dead end.
        assertFalse(MobScope.overreaches(Scope.CATEGORY, MobScope.of("make the zombies bigger")));
        assertFalse(MobScope.overreaches(Scope.CATEGORY, MobScope.of("no, all of them")));
    }

    @Test
    void agreeingIsNeverRefused() {
        assertFalse(MobScope.overreaches(Scope.ONE, MobScope.of("make him bigger")));
        assertFalse(MobScope.overreaches(Scope.CATEGORY, MobScope.of("make zombies bigger")));
        assertFalse(MobScope.overreaches(Scope.GROUP, MobScope.of("make these zombies glow")));
        assertFalse(MobScope.overreaches(null, MobScope.of("make him bigger")));
    }

    // ── what the agent may pass ─────────────────────────────────────────────────────────────

    @Test
    void theArgumentIsParsedAndNeverGuessed() {
        assertEquals(Scope.ONE, Scope.of("one"));
        assertEquals(Scope.GROUP, Scope.of("GROUP"));
        assertEquals(Scope.CATEGORY, Scope.of(" category "));
        assertEquals(Scope.CATEGORY, Scope.of("species"));
        // Null, not a default. A scope we do not recognise must become a question to the agent,
        // because the whole point of requiring it is that it cannot be skipped by accident.
        assertEquals(null, Scope.of("sort of all of them"));
        assertEquals(null, Scope.of(""));
        assertEquals(null, Scope.of(null));
    }

    @Test
    void theWordsThatDecidedItAreReportedSoAGuessCanBeAudited() {
        for (String said : new String[] {"make him bigger", "make zombies bigger",
                "make these zombies glow", "make the zombies bigger"}) {
            assertFalse(MobScope.of(said).why().isBlank(), said + " should say what decided it");
        }
    }
}
