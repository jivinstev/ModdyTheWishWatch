package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/**
 * The owner stamp: how a key is forked, and whose things come first.
 *
 * <p>The rule under test throughout is <b>absent means SHARED</b>, not "owned by nobody" and not an
 * error. Every artifact in every world that exists today carries no stamp, so any rule that treats
 * a missing one as special fires on all of them at once.
 */
class ArtifactOwnerTest {

    private static final String ALICE = "1f2e3d4c-0000-0000-0000-000000000001";
    private static final String BOB = "9a8b7c6d-0000-0000-0000-000000000002";

    @Test
    void aForkKeyIsTheBaseAndEightHexOfTheOwnersUuid() {
        assertEquals("spikor~1f2e3d4c", ArtifactOwner.forkKey("spikor", ALICE));
        assertEquals("spikor", ArtifactOwner.baseOf("spikor~1f2e3d4c"));
        assertEquals("1f2e3d4c", ArtifactOwner.suffixOf("spikor~1f2e3d4c"));
        assertTrue(ArtifactOwner.isFork("spikor~1f2e3d4c"));
        assertFalse(ArtifactOwner.isFork("spikor"));
    }

    /**
     * 🔴 The reason {@code ~} had to be let into two alphabets. If a fork key is spelled with a
     * character {@code Species.sanitizeKey} drops, the species id and its model directory become
     * two different strings — silently, and with both of them valid.
     */
    @Test
    void theForkSeparatorSurvivesTheModelKeyAlphabet() {
        String key = ArtifactOwner.forkKey("spikor", ALICE);
        assertTrue(com.moddingfromamod.runtime.ModelRepository.isValidKey(key),
                "a fork key must be a directory name the repository will serve, or a fork's art "
                        + "lands somewhere nothing looks: " + key);
    }

    /**
     * the SAME alphabet on the tool's side. {@code sculpt_creature} sanitises the id it is
     * handed through {@code ModelRepository.sanitizeKey}, which dropped the {@code ~} — so every
     * sculpted fork was stored as {@code pet_dragon1f2e3d4c}, a key no fork rule recognises.
     */
    @Test
    void aForkKeySurvivesTheToolsIdSanitiser() {
        String key = ArtifactOwner.forkKey("pet_dragon", ALICE);
        assertEquals(key, com.moddingfromamod.runtime.ModelRepository.sanitizeKey(key));
        assertEquals(key, com.moddingfromamod.runtime.ModelRepository.sanitizeKey(" " + key.toUpperCase() + " "));
        assertEquals("spikor_jr", com.moddingfromamod.runtime.ModelRepository.sanitizeKey("Spikor Jr!"));
        assertEquals("spikorx", com.moddingfromamod.runtime.ModelRepository.sanitizeKey("spikor~x!"),
                "a malformed key is sanitised as before");
    }

    /** Forking a fork re-bases: a key is a name, not a chain of everyone who has touched it. */
    @Test
    void forkingAnotherChildsForkRebasesRatherThanChaining() {
        String hers = ArtifactOwner.forkKey("spikor", ALICE);
        String his = ArtifactOwner.forkKey(hers, BOB);
        assertEquals("spikor~9a8b7c6d", his);
    }

    @Test
    void thereIsNoForkWithoutAnOwner() {
        assertEquals("", ArtifactOwner.forkKey("spikor", ""));
        assertEquals("", ArtifactOwner.forkKey("spikor", null));
        assertEquals("", ArtifactOwner.forkKey("", ALICE));
    }

    @Test
    void anAbsentStampIsSharedAndAnUnknownAskerSeesEverythingAsShared() {
        assertEquals(ArtifactOwner.Scope.SHARED, ArtifactOwner.scopeOf(ALICE, ""));
        assertEquals(ArtifactOwner.Scope.SHARED, ArtifactOwner.scopeOf("", BOB));
        assertEquals(ArtifactOwner.Scope.OWN, ArtifactOwner.scopeOf(ALICE, ALICE));
        assertEquals(ArtifactOwner.Scope.OTHER, ArtifactOwner.scopeOf(ALICE, BOB));
    }

    @Test
    void anOwnerIdIsComparedCaseInsensitivelyBecauseAUuidIsPrintedBothWays() {
        assertEquals(ArtifactOwner.Scope.OWN,
                ArtifactOwner.scopeOf(ALICE.toUpperCase(java.util.Locale.ROOT), ALICE));
    }

    /** A real {@link UUID}, because that is what every call site actually passes. */
    @Test
    void aRealUuidShortensToItsFirstGroup() {
        UUID id = UUID.fromString(ALICE);
        assertEquals("1f2e3d4c", ArtifactOwner.shortIdOf(id.toString()));
    }

    // ── preferOwn: a NARROWING, and it falls through ─────────────────────────────────────────

    private record Thing(String id, String ownerId) {
    }

    private static List<Thing> pick(String asker, Thing... things) {
        return ArtifactOwner.preferOwn(asker, List.of(things), Thing::ownerId);
    }

    @Test
    void hisOwnWinsOverSharedAndOverABrothers() {
        var out = pick(ALICE, new Thing("lib", ""), new Thing("mine", ALICE),
                new Thing("his", BOB));
        assertEquals(1, out.size());
        assertEquals("mine", out.get(0).id());
    }

    /** With none of his own, the SHARED ones — never straight past them to a brother's. */
    @Test
    void withNothingOfHisOwnItFallsToShared() {
        var out = pick(ALICE, new Thing("lib", ""), new Thing("his", BOB));
        assertEquals(List.of("lib"), out.stream().map(Thing::id).toList());
    }

    /** And with nothing shared either, anyone's — nothing becomes unreachable. */
    @Test
    void withNothingSharedEitherItFallsAllTheWayToAnyones() {
        var out = pick(ALICE, new Thing("his", BOB), new Thing("hers", "c0ffee00-0000-0000-0000-0"));
        assertEquals(2, out.size(), "preferring must never empty the list");
    }

    @Test
    void withNoAskerNothingIsPreferredAtAll() {
        var out = pick("", new Thing("lib", ""), new Thing("his", BOB));
        assertEquals(2, out.size());
    }

    @Test
    void aSingletonComesBackUntouchedAndAnEmptyListDoesNotThrow() {
        assertEquals(1, pick(ALICE, new Thing("his", BOB)).size());
        assertEquals(List.of(),
                ArtifactOwner.preferOwn(ALICE, List.<Thing>of(), Thing::ownerId));
    }

    // ── the JSON half ────────────────────────────────────────────────────────────────────────

    @Test
    void anUnstampedRecordStaysByteIdentical() {
        JsonObject o = new JsonObject();
        o.addProperty("id", "spikor");
        ArtifactOwner.stamp(o, "");
        assertEquals("{\"id\":\"spikor\"}", o.toString(),
                "stamping nothing must not add a field — every record on disk is in this state");
        assertEquals("", ArtifactOwner.of(o));
    }

    @Test
    void aStampRoundTrips() {
        JsonObject o = new JsonObject();
        ArtifactOwner.stamp(o, ALICE);
        assertEquals(ALICE, ArtifactOwner.of(o));
    }

    @Test
    void aDamagedStampReadsAsSharedRatherThanThrowing() {
        JsonObject o = new JsonObject();
        o.add(ArtifactOwner.FIELD, new com.google.gson.JsonArray());
        assertEquals("", ArtifactOwner.of(o));
        assertEquals("", ArtifactOwner.of(null));
    }
}
