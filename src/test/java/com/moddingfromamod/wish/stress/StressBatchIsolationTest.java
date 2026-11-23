package com.moddingfromamod.wish.stress;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The two stress tests that drive the corpus harnesses must never share a GameTest batch —.
 *
 * <p>Both run the same harnesses over the same corpus, so they register the same
 * {@code stress_<case-id>} keys into process-wide registries, each at its own anchor. Tests in one
 * batch run CONCURRENTLY, so they overwrote each other's anchors and whichever asserted second
 * failed {@code P4_LOCKED_ANCHOR} — "recorded at a different place from where it was built", which
 * was true, and was the other test's doing. Found on the Create machines harness (3 failures in 17
 * runs); the machines went with Create, but every harness that registers an id still
 * shares the hazard.
 *
 * <p>Read from the source rather than from annotations because these unit tests are deliberately
 * Minecraft-free and {@code @GameTest} is a Minecraft class.
 */
class StressBatchIsolationTest {

    private static final Path CORPUS =
            Path.of("src/main/java/com/moddingfromamod/wish/stress/StressCorpusGameTest.java");
    private static final String CORPUS_METHOD = "everyCorpusCaseWithoutCreateHoldsItsInvariants";
    private static final Path GUARDRAIL =
            Path.of("src/main/java/com/moddingfromamod/wish/stress/StressGameTest.java");
    private static final String GUARDRAIL_METHOD = "theSharedGuardrailInvariantsAreActuallyChecked";

    /** {@code batch = "x"} on the {@code @GameTest} immediately above the named method. */
    private static String batchOf(Path file, String method) throws IOException {
        String source = Files.readString(file);
        int at = source.indexOf(" " + method + "(");
        assertTrue(at > 0, "no method called " + method + " in " + file
                + " — if it was renamed, rename it here too rather than deleting this check");
        String before = source.substring(0, at);
        int ann = before.lastIndexOf("@GameTest");
        assertTrue(ann > 0, method + " has no @GameTest annotation");
        Matcher m = Pattern.compile("batch\\s*=\\s*\"([^\"]+)\"")
                .matcher(before.substring(ann));
        return m.find() ? m.group(1) : "defaultBatch";
    }

    @Test
    void theTwoCorpusDrivingStressTestsAreInDifferentBatches() throws IOException {
        String corpus = batchOf(CORPUS, CORPUS_METHOD);
        String guardrail = batchOf(GUARDRAIL, GUARDRAIL_METHOD);
        assertNotEquals(corpus, guardrail,
                CORPUS_METHOD + " and " + GUARDRAIL_METHOD + " are both in batch '" + corpus
                        + "'. GameTests in one batch run CONCURRENTLY, and both register the same "
                        + "stress_<case-id> ids at different positions — so they overwrite each "
                        + "other and one fails P4_LOCKED_ANCHOR intermittently. That is #168. "
                        + "Batches run sequentially: keep them apart.");
    }
}
