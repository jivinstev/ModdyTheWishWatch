package com.moddingfromamod.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The envelope every {@code <world>/wishes/*.json} store reads and writes through. */
class WishStoreFileTest {

    private static JsonObject specsPayload() {
        JsonObject o = new JsonObject();
        JsonArray specs = new JsonArray();
        JsonObject one = new JsonObject();
        one.addProperty("id", "bloop_slayer");
        specs.add(one);
        o.add("specs", specs);
        return o;
    }

    // ── the four outcomes, and the point is that none of them is "empty" ────────────────────────

    @Test
    @DisplayName("no file is ABSENT — the only case where an empty store is the right answer")
    void absent() {
        assertInstanceOf(WishStoreFile.Read.Absent.class, WishStoreFile.read(null, "item-specs", 1));
        assertInstanceOf(WishStoreFile.Read.Absent.class, WishStoreFile.read("   ", "item-specs", 1));
    }

    @Test
    @DisplayName("a stamped file this build understands is OK, and the payload is still inline")
    void ok() {
        String text = WishStoreFile.stamp("item-specs", 1, specsPayload()).toString();
        var read = WishStoreFile.read(text, "item-specs", 1);
        var ok = assertInstanceOf(WishStoreFile.Read.Ok.class, read);
        assertEquals(1, ok.version());
        assertTrue(ok.root().has("specs"), "the payload's own key survives the envelope");
    }

    @Test
    @DisplayName("an UNVERSIONED file is LEGACY and accepted — every file on disk today is one")
    void legacyIsAccepted() {
        var read = WishStoreFile.read(specsPayload().toString(), "item-specs", 1);
        var legacy = assertInstanceOf(WishStoreFile.Read.Legacy.class, read);
        assertTrue(legacy.root().has("specs"));
    }

    @Test
    @DisplayName("garbage is REFUSED, never silently empty — this is the whole defect")
    void garbageIsRefused() {
        assertInstanceOf(WishStoreFile.Read.Refused.class,
                WishStoreFile.read("{not json", "item-specs", 1));
        assertInstanceOf(WishStoreFile.Read.Refused.class,
                WishStoreFile.read("[1,2,3]", "item-specs", 1));
    }

    // ── itself ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("#585: the OTHER store's file is refused BY NAME, not read as empty")
    void anotherStoresFileIsRefusedByName() {
        // What WishItemCatalogue writes, read by ItemSpecStore — the collision, exactly.
        JsonObject catalogue = new JsonObject();
        catalogue.add("items", new JsonArray());
        String text = WishStoreFile.stamp("items", 1, catalogue).toString();

        var read = WishStoreFile.read(text, "item-specs", 1);
        var refused = assertInstanceOf(WishStoreFile.Read.Refused.class, read);
        assertTrue(refused.reason().contains("'items' store"),
                "the refusal names the store that owns the file: " + refused.reason());
        assertTrue(refused.reason().contains("item-specs"),
                "and the one that tried to read it: " + refused.reason());
    }

    @Test
    @DisplayName("the store name is checked BEFORE the version, so a collision never reads as a migration problem")
    void storeNameBeatsVersion() {
        JsonObject other = new JsonObject();
        other.addProperty("anything", 1);
        // Wrong store AND a version from the future: the name must be the reported reason.
        String text = WishStoreFile.stamp("items", 99, other).toString();
        var refused = assertInstanceOf(WishStoreFile.Read.Refused.class,
                WishStoreFile.read(text, "item-specs", 1));
        assertTrue(refused.reason().contains("store"), refused.reason());
        assertFalse(refused.reason().contains("newer Moddy"),
                "a wrong-store file must not be reported as a version problem: " + refused.reason());
    }

    // ── forwards and backwards ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a FUTURE version is refused and says so in words a grown-up can act on")
    void futureVersionRefused() {
        String text = WishStoreFile.stamp("item-specs", 7, specsPayload()).toString();
        var refused = assertInstanceOf(WishStoreFile.Read.Refused.class,
                WishStoreFile.read(text, "item-specs", 2));
        assertTrue(refused.reason().contains("newer Moddy"), refused.reason());
        assertTrue(refused.reason().contains("v7") && refused.reason().contains("v2"),
                "both versions are named: " + refused.reason());
    }

    @Test
    @DisplayName("⚠ ROLLBACK: an OLD reader still finds its own key in a NEW file")
    void theEnvelopeIsInlineSoADowngradeStillReads() {
        JsonObject stamped = WishStoreFile.stamp("item-specs", 3, specsPayload());
        // Exactly what ItemSpecStore.load() did before this framework existed.
        assertTrue(stamped.has("specs"),
                "a jar built before the envelope must still read a file written after it");
        assertEquals(1, stamped.getAsJsonArray("specs").size());
    }

    @Test
    @DisplayName("a store that already writes 'v' or 'store' is a build error, not a silent disagreement")
    void reservedKeysAreRefusedLoudly() {
        JsonObject clashes = new JsonObject();
        clashes.addProperty("v", 4);
        assertThrows(IllegalArgumentException.class,
                () -> WishStoreFile.stamp("item-specs", 1, clashes));
    }

    // ── rule 2: the latch, which is what actually stops the data loss ───────────────────────────

    @Test
    @DisplayName("a fresh guard permits writing")
    void guardStartsOpen() {
        assertTrue(new WishStoreFile.Guard().mayWrite());
    }

    @Test
    @DisplayName("⚠ once a read is REFUSED the guard blocks every later write — refusing to READ is useless on its own")
    void refusalBlocksWriting() {
        var guard = new WishStoreFile.Guard();
        guard.observe(WishStoreFile.read("{not json", "item-specs", 1));
        assertFalse(guard.mayWrite(), "save() must not overwrite bytes nobody could read");
        assertTrue(guard.refusalReason().contains("parsed"), guard.refusalReason());
    }

    @Test
    @DisplayName("a later GOOD read does not re-open the latch — only reset() does")
    void onlyResetClearsTheLatch() {
        var guard = new WishStoreFile.Guard();
        guard.observe(WishStoreFile.read("{not json", "item-specs", 1));
        guard.observe(WishStoreFile.read(
                WishStoreFile.stamp("item-specs", 1, specsPayload()).toString(), "item-specs", 1));
        assertFalse(guard.mayWrite(), "a retry inside one load cycle must not re-enable writing");

        guard.reset();
        assertTrue(guard.mayWrite(), "a new world load is a fresh judgement");
    }

    @Test
    @DisplayName("⚠ the latch is per WORLD LOAD, not per process — a damaged world must not block the next one")
    void theLatchIsPerLoadNotPerProcess() {
        // These stores are process-wide statics. Found by GateShardsTest: a latch that never reset
        // would mean one damaged world permanently blocked saving in every later world.
        var guard = new WishStoreFile.Guard();
        guard.observe(WishStoreFile.read("{not json", "item-specs", 1));
        assertFalse(guard.mayWrite());

        guard.reset();      // world 2 loads
        guard.observe(WishStoreFile.read(
                WishStoreFile.stamp("item-specs", 1, specsPayload()).toString(), "item-specs", 1));
        assertTrue(guard.mayWrite(), "a healthy world saves normally after a damaged one");
    }

    @Test
    @DisplayName("Absent, Ok and Legacy all leave the guard open")
    void onlyRefusalLatches() {
        for (String text : new String[] {
                null,
                specsPayload().toString(),
                WishStoreFile.stamp("item-specs", 1, specsPayload()).toString()}) {
            var guard = new WishStoreFile.Guard();
            guard.observe(WishStoreFile.read(text, "item-specs", 1));
            assertTrue(guard.mayWrite(), "text was: " + text);
        }
    }
}
