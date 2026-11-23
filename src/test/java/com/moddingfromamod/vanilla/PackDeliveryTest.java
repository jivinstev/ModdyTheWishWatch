package com.moddingfromamod.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;

import org.junit.jupiter.api.Test;

/**
 * The two decisions in pack delivery that must not be wrong, tested without a server, a world or a
 * client — which is the point, because needing all three is how the delivery bug shipped
 * green.
 */
class PackDeliveryTest {

    private static final String SHA = "da39a3ee5e6b4b0d3255bfef95601890afd80709";

    // ---------------------------------------------------------------- routing

    @Test
    @DisplayName("a modded connection takes the direct route; a stock one takes the vanilla pack")
    void routingIsOnePredicate() {
        assertEquals(PackDelivery.Route.DIRECT, PackDelivery.routeFor(true));
        assertEquals(PackDelivery.Route.VANILLA_PACK, PackDelivery.routeFor(false));
    }

    @Test
    @DisplayName("D32: the item pack goes to a stock client, not to a modded one or the integrated server's host")
    void theHostIsNotAskedForThePack() {
        assertTrue(PackDelivery.itemPackNeeded(false, false), "a stock client (a LAN guest, a dedicated server's player)");
        assertFalse(PackDelivery.itemPackNeeded(false, true), "the host: their game already has the jar's art");
        assertFalse(PackDelivery.itemPackNeeded(true, false), "a modded client");
        assertFalse(PackDelivery.itemPackNeeded(true, true));
    }

    // ---------------------------------------------------------------- the URL

    @Test
    @DisplayName("the sha1 is in the PATH, so a changed pack is a different URL")
    void theHashIsInThePath() {
        // A vanilla client caches by URL. If the hash rode in a query string, new content would
        // arrive at a URL the client already had cached and it would render the old pack -- with
        // nothing anywhere reporting a problem.
        String a = PackDelivery.url("127.0.0.1", 25599, SHA);
        String b = PackDelivery.url("127.0.0.1", 25599,
                "0000000000000000000000000000000000000000");
        assertEquals("http://127.0.0.1:25599/pack/" + SHA + ".zip", a);
        assertFalse(a.equals(b), "two different packs must not share a URL");
    }

    @Test
    @DisplayName("the advertised host is an INPUT, and an empty one is refused loudly")
    void anEmptyHostIsRefused() {
        // The bind address and the advertised host are different questions -- a server may bind
        // 0.0.0.0 and still need to tell a client a name it can reach. Getting this wrong produces
        // a client that silently never fetches, so it throws where the mistake is made.
        assertThrows(IllegalArgumentException.class, () -> PackDelivery.url("", 80, SHA));
        assertThrows(IllegalArgumentException.class, () -> PackDelivery.url(null, 80, SHA));
    }

    @Test
    @DisplayName("a port outside the legal range is refused")
    void badPortsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> PackDelivery.url("h", 0, SHA));
        assertThrows(IllegalArgumentException.class, () -> PackDelivery.url("h", 70000, SHA));
    }

    @Test
    @DisplayName("a malformed hash throws HERE rather than being ignored by the client")
    void aMalformedHashThrows() {
        // Vanilla validates the hash and refuses a malformed one. Without this the symptom is
        // "the client ignored my pack", which looks nothing like "I built the hash wrongly".
        assertThrows(IllegalArgumentException.class, () -> PackDelivery.url("h", 80, "abc"));
        assertThrows(IllegalArgumentException.class, () -> PackDelivery.url("h", 80, null));
        assertThrows(IllegalArgumentException.class,
                () -> PackDelivery.url("h", 80, "zzz9a3ee5e6b4b0d3255bfef95601890afd80709"));
    }

    // ---------------------------------------------------------------- parsing a request

    @Test
    @DisplayName("the host recognises its own URLs and nothing else")
    void theHostParsesOnlyItsOwnPaths() {
        assertEquals(SHA, PackDelivery.sha1InPath("/pack/" + SHA + ".zip"));
        assertNull(PackDelivery.sha1InPath("/pack/" + SHA));
        assertNull(PackDelivery.sha1InPath("/" + SHA + ".zip"));
        assertNull(PackDelivery.sha1InPath("/pack/not-a-hash.zip"));
        assertNull(PackDelivery.sha1InPath(null));
    }

    @Test
    @DisplayName("a path that is not a hash can never name a file")
    void traversalCannotName() {
        // A served file is chosen BY HASH, so the only defence needed is that a path which is not a
        // hash resolves to nothing. Stated as a test because "we never concatenate the path" is the
        // kind of property a later refactor silently loses.
        assertNull(PackDelivery.sha1InPath("/pack/../../etc/passwd.zip"));
        assertNull(PackDelivery.sha1InPath("/pack/%2e%2e%2fsecret.zip"));
    }

    // ---------------------------------------------------------------- the cache

    @Test
    @DisplayName("sha1 recognition is exact about length and alphabet")
    void sha1Recognition() {
        assertTrue(PackDelivery.isSha1(SHA));
        assertTrue(PackDelivery.isSha1(SHA.toUpperCase(java.util.Locale.ROOT)));
        assertFalse(PackDelivery.isSha1(SHA + "0"));
        assertFalse(PackDelivery.isSha1(SHA.substring(1)));
        assertFalse(PackDelivery.isSha1(null));
    }
}
