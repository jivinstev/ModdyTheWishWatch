package com.moddingfromamod.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * <b>Every on-disk shape a wish store has ever written, replayed through the reader that
 * ships today.</b> Same idea as {@code CbScriptCorpusTest}, which replays the agent's real compile
 * attempts: the corpus is the memory, and a change that stops understanding an old file goes red
 * <b>by name</b> rather than by a count moving.
 *
 * <p>The corpus lives in {@code corpus/store-shapes/}. Its manifest says, per case, which reader
 * opens it, which of the four outcomes is expected, and what must still be readable afterwards.
 *
 * <h2>⚠ What this cannot see, said out loud</h2>
 *
 * The fixtures are <b>synthesised from what the writers produce</b>, not harvested from a world a
 * child has played. So it catches "a reader stopped understanding a shape the code still writes" and
 * it does <b>not</b> catch "a reader stopped understanding a shape only a real disk still holds".
 * The second is the one that actually costs a child his things, and it needs a real world —
 * a played one. Harvesting into this directory is the upgrade, and the manifest says so
 * rather than letting a green run imply coverage it does not have.
 */
class StoreShapeCorpusTest {

    private static final Path DIR = Path.of("corpus", "store-shapes");

    private record Case(String file, String reader, String expect, JsonObject mustHold,
                        String reasonContains, String why) {}

    /**
     * The newest schema each reader understands, from the manifest's {@code schemas}. A
     * reader it does not name is at v1 — which was every reader until {@code item-specs} moved to
     * v2 and its v1 fixtures still had to replay as {@code ok}.
     */
    static int newest(String reader) throws Exception {
        JsonObject manifest = JsonParser
                .parseString(Files.readString(DIR.resolve("manifest.json"), StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject schemas = manifest.has("schemas") ? manifest.getAsJsonObject("schemas") : null;
        return schemas != null && schemas.has(reader) ? schemas.get(reader).getAsInt() : 1;
    }

    private static List<Case> corpus() throws Exception {
        JsonObject manifest = JsonParser
                .parseString(Files.readString(DIR.resolve("manifest.json"), StandardCharsets.UTF_8))
                .getAsJsonObject();
        List<Case> out = new ArrayList<>();
        for (var el : manifest.getAsJsonArray("cases")) {
            JsonObject c = el.getAsJsonObject();
            out.add(new Case(
                    c.get("file").getAsString(),
                    c.get("reader").getAsString(),
                    c.get("expect").getAsString(),
                    c.has("mustHold") ? c.getAsJsonObject("mustHold") : null,
                    c.has("reasonContains") ? c.get("reasonContains").getAsString() : null,
                    c.get("why").getAsString()));
        }
        return out;
    }

    /**
     * The corpus must not be able to shrink to nothing and still report green — the failure
     * {@code StressRegistry.unclaimed()} exists to prevent, in the smallest possible form.
     */
    @Test
    @DisplayName("the corpus is not empty, and every file it names is on disk")
    void corpusIsWhole() throws Exception {
        List<Case> cases = corpus();
        assertTrue(cases.size() >= 9, "the corpus has shrunk: " + cases.size() + " cases");
        for (Case c : cases) {
            assertTrue(Files.isRegularFile(DIR.resolve(c.file())),
                    "manifest names a file that is not there: " + c.file());
        }
        // And nothing on disk is unclaimed, which is the other direction.
        try (var files = Files.list(DIR)) {
            for (Path p : files.toList()) {
                String n = p.getFileName().toString();
                if (n.equals("manifest.json")) {
                    continue;
                }
                assertTrue(cases.stream().anyMatch(c -> c.file().equals(n)),
                        "a fixture nobody replays: " + n + " — add it to the manifest or delete it");
            }
        }
    }

    @TestFactory
    @DisplayName("every recorded store shape still reads the way the manifest says")
    Iterable<DynamicTest> everyShapeStillReads() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        for (Case c : corpus()) {
            tests.add(DynamicTest.dynamicTest(c.file() + " → " + c.expect(), () -> {
                String text = Files.readString(DIR.resolve(c.file()), StandardCharsets.UTF_8);
                // The reader always understands up to the newest schema this build writes.
                WishStoreFile.Read read = WishStoreFile.read(text, c.reader(), newest(c.reader()));

                switch (c.expect()) {
                    case "ok" -> {
                        var ok = assertInstanceOf(WishStoreFile.Read.Ok.class, read, c.why());
                        holds(ok.root(), c);
                    }
                    case "legacy" -> {
                        var legacy = assertInstanceOf(WishStoreFile.Read.Legacy.class, read, c.why());
                        holds(legacy.root(), c);
                    }
                    case "refused" -> {
                        var refused =
                                assertInstanceOf(WishStoreFile.Read.Refused.class, read, c.why());
                        assertTrue(refused.reason().contains(c.reasonContains()),
                                "the refusal must say WHY in words a grown-up can act on. Wanted '"
                                        + c.reasonContains() + "', got: " + refused.reason());
                    }
                    default -> fail("unknown expectation '" + c.expect() + "' in the manifest");
                }
            }));
        }
        return tests;
    }

    /**
     * ⚠ The property the whole corpus exists for: <b>a refusal is never an empty store.</b> Before
     * the stores were versioned, every one of the refused fixtures produced an empty map and a quiet return, and the next
     * write then persisted that emptiness.
     */
    @Test
    @DisplayName("no recorded shape reads as a silently EMPTY store")
    void nothingReadsAsSilentlyEmpty() throws Exception {
        for (Case c : corpus()) {
            String text = Files.readString(DIR.resolve(c.file()), StandardCharsets.UTF_8);
            WishStoreFile.Read read = WishStoreFile.read(text, c.reader(), newest(c.reader()));
            assertFalse(read instanceof WishStoreFile.Read.Absent,
                    c.file() + " read as Absent — a file that exists is never Absent, and Absent is "
                            + "the only case where an empty store is the right answer");
        }
    }

    private static void holds(JsonObject root, Case c) {
        if (c.mustHold() == null) {
            return;
        }
        String key = c.mustHold().get("key").getAsString();
        assertTrue(root.has(key), c.file() + " lost its '" + key + "' key: " + c.why());
        JsonArray arr = root.getAsJsonArray(key);
        if (c.mustHold().has("count")) {
            assertEquals(c.mustHold().get("count").getAsInt(), arr.size(),
                    c.file() + " changed row count — this is data loss, not a refactor");
        }
        if (c.mustHold().has("contains")) {
            assertTrue(arr.toString().contains(c.mustHold().get("contains").getAsString()),
                    c.file() + " no longer carries '" + c.mustHold().get("contains").getAsString()
                            + "'");
        }
    }
}
