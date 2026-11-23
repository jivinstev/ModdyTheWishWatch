package com.moddingfromamod.marshal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The eligibility FILTER — is this player a child at all, as distinct from
 * {@link WishAudience}'s SELECTOR, which asks which of the children asked.
 *
 * <p>The two are complementary and the tests below are written to say so: the same two-child world
 * appears in both files and gets different answers, because they are different questions.
 */
class PlayerKindTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID CAMERA = UUID.fromString("00000000-0000-0000-0000-0000000000ca");

    @AfterEach
    void forgetTheCamera() {
        PlayerKind.forgetAllServiceClients();
    }

    @Test
    @DisplayName("with nothing registered, everybody is a child — the seam changes nothing by itself")
    void byDefaultEverybodyIsAChild() {
        assertTrue(PlayerKind.isChild(ALICE, Set.of()));
        assertEquals(List.of(ALICE, BOB), PlayerKind.children(List.of(ALICE, BOB), Set.of()));
        assertEquals(ALICE, PlayerKind.acting(null, List.of(ALICE, BOB), Set.of()));
    }

    @Test
    @DisplayName("a camera is never a child, and is never picked as one")
    void aCameraIsNotAChild() {
        Set<UUID> service = Set.of(CAMERA);
        assertFalse(PlayerKind.isChild(CAMERA, service));
        assertTrue(PlayerKind.isChild(ALICE, service));
        assertEquals(List.of(ALICE), PlayerKind.children(List.of(CAMERA, ALICE), service));
    }

    @Test
    @DisplayName("🔴 a camera that joined FIRST is still not the child — the bug this exists to stop")
    void aCameraThatJoinedFirstIsStillNotTheChild() {
        // Join order is what findFirst() answers, so a camera launched before the child is exactly
        // the case where the old code hands him a wish. Nothing about the camera is distinguishable
        // from a child at this point: it is a real connected client with a real channel.
        assertEquals(ALICE, PlayerKind.acting(null, List.of(CAMERA, ALICE), Set.of(CAMERA)));
    }

    @Test
    @DisplayName("the owner wins over join order")
    void theOwnerWinsOverJoinOrder() {
        assertEquals(BOB, PlayerKind.acting(BOB, List.of(ALICE, BOB), Set.of()));
    }

    @Test
    @DisplayName("an owner who has logged out falls back rather than returning a ghost")
    void anAbsentOwnerFallsBack() {
        assertEquals(ALICE, PlayerKind.acting(BOB, List.of(ALICE), Set.of()));
    }

    @Test
    @DisplayName("an owner who is somehow a service client is not honoured either")
    void anOwnerCannotBeACamera() {
        assertEquals(ALICE, PlayerKind.acting(CAMERA, List.of(CAMERA, ALICE), Set.of(CAMERA)));
    }

    @Test
    @DisplayName("a world of nothing but cameras has no child, and says so")
    void nobodyHomeMeansNull() {
        assertNull(PlayerKind.acting(null, List.of(CAMERA), Set.of(CAMERA)));
        assertNull(PlayerKind.acting(null, List.of(), Set.of()));
        assertNull(PlayerKind.acting(null, null, Set.of()));
    }

    @Test
    @DisplayName("⚠ two children and no identifiable owner still gets the FIRST — this is a seam, not a policy")
    void ambiguityKeepsTodaysAnswerOnPurpose() {
        // Refusing here is the right end state (WishAudience.actingPlayer already refuses for
        // narration) but it is a per-tool policy change, and shipping it inside the same edit that
        // installs the seam would move thirty-odd sites at once with nothing to attribute a red
        // gate to. This assertion is here so that change is a VISIBLE edit to a test rather than a
        // quiet drift.
        assertEquals(ALICE, PlayerKind.acting(null, List.of(ALICE, BOB), Set.of()));
    }

    @Test
    @DisplayName("the registry is a process fact, and the pure rule never reads it")
    void thePureRuleTakesItsExclusionsAsAnArgument() {
        PlayerKind.registerServiceClient(CAMERA);
        assertFalse(PlayerKind.isChild(CAMERA));
        assertTrue(PlayerKind.isChild(CAMERA, Set.of()), "the pure form must ignore the registry");
        assertEquals(Set.of(CAMERA), PlayerKind.serviceClients());
        PlayerKind.forgetServiceClient(CAMERA);
        assertTrue(PlayerKind.isChild(CAMERA));
    }

    @Test
    @DisplayName("a camera can be declared before it exists — the offline id is derived from the name")
    void anOfflineIdIsDerivedFromTheName() {
        // The whole point: registration must not wait for a handshake, or the camera is a child for
        // its first few ticks, which is exactly the window a tool asks in.
        assertEquals(PlayerKind.offlineId("Moddy-Camera"), PlayerKind.offlineId("Moddy-Camera"));
        assertTrue(PlayerKind.isChild(PlayerKind.offlineId("Steve"), Set.of()));
        assertFalse(PlayerKind.isChild(PlayerKind.offlineId("Moddy-Camera"),
                Set.of(PlayerKind.offlineId("Moddy-Camera"))));
        // Pinned against the value vanilla derives; a GameTest asserts the two agree for real.
        assertEquals(UUID.nameUUIDFromBytes(
                        "OfflinePlayer:Moddy-Camera".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                PlayerKind.offlineId("Moddy-Camera"));
    }

    private static final UUID PROBE = UUID.fromString("00000000-0000-0000-0000-0000000000fe");

    @Test
    @DisplayName("the headless probe's stand-in acts only in a world with no child in it")
    void theStandInActsOnlyWhenNoChildIs() {
        assertEquals(PROBE, PlayerKind.acting(PROBE, List.of(), Set.of(), PROBE),
                "the probe's own wish in an empty world acts for the probe — conjure_at no longer "
                        + "refuses 7 of 7");
        assertEquals(PROBE, PlayerKind.acting(null, List.of(), Set.of(), PROBE),
                "an unattributed call in an empty world, too");
        assertEquals(ALICE, PlayerKind.acting(PROBE, List.of(ALICE), Set.of(), PROBE),
                "a real child online always wins over the stand-in");
        assertNull(PlayerKind.acting(BOB, List.of(), Set.of(), PROBE),
                "a real child's wish, made before he left, never lands on the probe");
        assertNull(PlayerKind.acting(null, List.of(CAMERA), Set.of(CAMERA), null),
                "no stand-in registered: exactly today's answer");
    }

    // ── decision 1: the owner or nobody, for things done to a person ──────────────────

    @Test
    @DisplayName("an owner who LEFT is never replaced by the child who is still here")
    void anOwnerWhoLeftIsNeverReplaced() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        assertNull(PlayerKind.owned(alice, List.of(bob), Set.of(), null),
                "Alice's wish, Alice gone, Bob here: nobody — never Bob");
        assertEquals(bob, PlayerKind.acting(alice, List.of(bob), Set.of(), null),
                "the WORLD rule is unchanged: a build she asked for still resolves somewhere");
    }

    @Test
    void theOwnerWhenHeIsHere() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        assertEquals(alice, PlayerKind.owned(alice, List.of(bob, alice), Set.of(), null));
    }

    @Test
    void anUnnamedCallerKeepsTodaysAnswer() {
        UUID bob = UUID.randomUUID();
        assertEquals(bob, PlayerKind.owned(null, List.of(bob), Set.of(), null),
                "no owner named: the sole child, as before — a probe or console is unchanged");
    }

    @Test
    void theStandInAnswersOnlyForItsOwnWish() {
        UUID probe = UUID.randomUUID();
        UUID alice = UUID.randomUUID();
        assertEquals(probe, PlayerKind.owned(probe, List.of(), Set.of(), probe),
                "Gate C½: the stand-in wished, so it is the owner");
        assertNull(PlayerKind.owned(alice, List.of(), Set.of(), probe),
                "a real child's wish never lands on the probe");
    }

    @Test
    void aServiceClientIsNeverTheOwnerEvenIfNamed() {
        UUID cam = UUID.randomUUID();
        assertNull(PlayerKind.owned(cam, List.of(cam), Set.of(cam), null));
    }
}
