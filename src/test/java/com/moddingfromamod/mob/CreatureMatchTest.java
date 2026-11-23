package com.moddingfromamod.mob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.moddingfromamod.mob.CreatureMatch.Seen;
import com.moddingfromamod.mob.CreatureMatch.What;
import org.junit.jupiter.api.Test;

/**
 * Gate A: "which creature do you mean", over the three things that stand in a child's
 * world: a creature on its vanilla carrier, that creature's hit proxy, and a real mob
 * of the carrier's type standing next to it. The production messages are the fixtures:
 * <i>"No mob matching spikor … Nearby: minecraft:llama"</i>, and
 * {@code {target:"llama", scope:"group"}} reaching his real llamas.
 */
class CreatureMatchTest {

    /** Spikor as he stands in the world: an invisible llama wearing his tags. */
    private static final Seen SPIKOR = new Seen("minecraft:llama", "spikor", "§6SPIKOR, King",
            List.of("mfamrig", "mfamrigof_1234", "wishmob", "wishmob_spikor"), "§6SPIKOR, King");

    /** One of Spikor's hit proxies: an invisible mob with no creature tags of its own. */
    private static final Seen PROXY = new Seen("minecraft:llama", null, null,
            List.of("mfamrigpx", "mfampxof_1234"), null);

    /** The child's own llama. */
    private static final Seen LLAMA = new Seen("minecraft:llama", null, null, List.of(), null);

    // ──: a creature answers to its own names ───────────────────────────────────────────

    @Test
    void aCreatureAnswersToItsSpeciesNameTagAndSelector() {
        for (String q : new String[] {"spikor", "Spikor", "SPIKOR", "wishmob_spikor",
                "@e[tag=wishmob_spikor]", "@e[tag=wishmob_spikor,limit=1,sort=nearest]",
                "wishmob", "king", "spikor king"}) {
            assertTrue(CreatureMatch.matches(SPIKOR, q), "Spikor should answer to '" + q + "'");
        }
    }

    @Test
    void aSelectorIsNotAHandle() {
        // modify_mob resolves a handle directly; a selector must fall through to the matcher.
        assertTrue(CreatureMatch.isSelector("@e[tag=wishmob_spikor]"));
        assertFalse(CreatureMatch.isSelector("@spikor"));
        assertFalse(CreatureMatch.isSelector("spikor"));
    }

    @Test
    void aCreatureNeverAnswersToItsCarriersType() {
        for (String q : new String[] {"llama", "minecraft:llama", "wishmob_peach", "cow"}) {
            assertFalse(CreatureMatch.matches(SPIKOR, q), "Spikor must not answer to '" + q + "'");
        }
    }

    // ──: a real mob answers to its type, and never to a creature's name ────────────────

    @Test
    void aRealLlamaIsTheOnlyThingLlamaMeans() {
        assertTrue(CreatureMatch.matches(LLAMA, "llama"));
        assertTrue(CreatureMatch.matches(LLAMA, "minecraft:llama"));
        assertFalse(CreatureMatch.matches(LLAMA, "spikor"));
        assertFalse(CreatureMatch.matches(LLAMA, "wishmob"));
        assertFalse(CreatureMatch.matches(LLAMA, "wishmob_llama"),
                "a creature's tag is never a real mob's name, even one that contains its type");
    }

    @Test
    void aHitProxyIsNeverAnything() {
        assertEquals(What.INTERNAL, CreatureMatch.what(PROXY));
        for (String q : new String[] {"", "llama", "spikor", "wishmob", "wishmob_spikor"}) {
            assertFalse(CreatureMatch.matches(PROXY, q), "a proxy answered to '" + q + "'");
        }
        assertNull(CreatureMatch.kindId(PROXY), "a proxy must never be minted a handle");
    }

    @Test
    void aBlankQueryMeansAnythingButARigsInternals() {
        assertTrue(CreatureMatch.matches(SPIKOR, ""));
        assertTrue(CreatureMatch.matches(LLAMA, null));
        assertFalse(CreatureMatch.matches(PROXY, "  "));
    }

    // ── what a handle records, and what a list reads ────────────────────────────────────────

    @Test
    void aCreaturesKindIsItsSpeciesNotItsCarrier() {
        assertEquals("wishmob:spikor", CreatureMatch.kindId(SPIKOR));
        assertEquals("minecraft:llama", CreatureMatch.kindId(LLAMA));
        assertTrue(CreatureMatch.isCreatureKind("wishmob:spikor"));
        assertFalse(CreatureMatch.isCreatureKind("minecraft:llama"));
        assertEquals("spikor", CreatureMatch.speciesOfKind("wishmob:spikor"));
        // The referent pre-pass matches a handle's kind with ContentSearch: "llama" must miss.
        assertFalse(ContentSearch.matches(CreatureMatch.kindId(SPIKOR), "llama"));
        assertTrue(ContentSearch.matches(CreatureMatch.kindId(SPIKOR), "spikor"));
    }

    @Test
    void aCreatureIsReadAsItsSpeciesWordAndName() {
        assertEquals("spikor", CreatureMatch.word(SPIKOR));
        assertEquals("minecraft:llama", CreatureMatch.word(LLAMA));
        assertEquals("SPIKOR, King", CreatureMatch.label(SPIKOR), "colour codes are for the screen");
        assertNull(CreatureMatch.label(LLAMA));
    }

    @Test
    void aCarrierOfAnUnknownSpeciesIsStillACreature() {
        // A design deleted, or a world from another server: the carrier still wears its tags,
        // and matching it by its llama-ness would be all over again.
        Seen orphan = new Seen("minecraft:llama", null, null,
                List.of("mfamrig", "wishmob", "wishmob_peach"), null);
        assertEquals(What.CREATURE, CreatureMatch.what(orphan));
        assertFalse(CreatureMatch.matches(orphan, "llama"));
        assertTrue(CreatureMatch.matches(orphan, "peach"));
        assertEquals("wishmob:peach", CreatureMatch.kindId(orphan));
    }

    @Test
    void aNameTheChildGaveACreatureFindsIt() {
        Seen named = new Seen("minecraft:horse", "pet_dragon", "Pet Dragon",
                List.of("mfamrig", "wishmob", "wishmob_pet_dragon"), "Sparky");
        assertTrue(CreatureMatch.matches(named, "sparky"));
        assertTrue(CreatureMatch.matches(named, "dragon"));
        assertFalse(CreatureMatch.matches(named, "horse"));
        assertEquals("Sparky", CreatureMatch.label(named));
    }

    // ──: his own first ──

    private static final String ALICE = "a11ce000-0000-0000-0000-000000000001";
    private static final String BOB = "b0b00000-0000-0000-0000-000000000002";

    @Test
    void hisOwnComesFirstEvenWhenAnotherIsNearer() {
        List<java.util.Set<String>> nearestFirst = List.of(
                java.util.Set.of(CreatureMatch.spawnerTag(BOB)),
                java.util.Set.of(),
                java.util.Set.of(CreatureMatch.spawnerTag(ALICE)));
        assertEquals(List.of(nearestFirst.get(2)),
                CreatureMatch.hisFirst(nearestFirst, t -> t, ALICE));
    }

    @Test
    void withNoneOfHisOwnItIsTheNearest() {
        List<java.util.Set<String>> nearestFirst = List.of(
                java.util.Set.of(CreatureMatch.spawnerTag(BOB)), java.util.Set.of());
        assertEquals(nearestFirst, CreatureMatch.hisFirst(nearestFirst, t -> t, ALICE));
        assertEquals(nearestFirst, CreatureMatch.hisFirst(nearestFirst, t -> t, ""),
                "nobody asking narrows nothing");
    }

    @Test
    void theSpawnerTagIsAWordASelectorCanCarry() {
        String tag = CreatureMatch.spawnerTag(ALICE);
        assertTrue(tag.matches("mfamby_[a-z0-9]{8}"), tag);
        assertEquals("", CreatureMatch.spawnerTag(""));
        assertFalse(CreatureMatch.spawnedBy(java.util.Set.of(tag), BOB));
    }
}
