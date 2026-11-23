package com.moddingfromamod.provenance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import com.moddingfromamod.runtime.RuntimeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>Gate A for the Provenance Contract.</b> For every publishing Kind, a recorded grant satisfies
 * the contract (a manifest with a non-empty intent); a grant that failed to capture the intent does
 * NOT — which is exactly the regression this contract guards against.
 */
class ProvenanceContractTest {

    private static final String PDIR = "moddingfromamod.persistDir";
    private static final String HOME = "moddingfromamod.home";

    @AfterEach
    void reset() {
        System.clearProperty(PDIR);
        System.clearProperty(HOME);
        RuntimeConfig.invalidate();
        ProvenanceContext.clear();
    }

    private void pointAt(Path repo) {
        System.setProperty(HOME, repo.resolve("home").toString());
        System.setProperty(PDIR, repo.toString());
        RuntimeConfig.invalidate();
    }

    /** Every declared publishing Kind is satisfiable: record with an intent → contract holds. */
    @Test
    void everyPublishingKindIsSatisfiedByARecordedGrant(@TempDir Path repo) {
        pointAt(repo);
        for (String kind : ProvenanceContract.PUBLISHING_KINDS) {
            String id = "contract_" + kind;
            assertFalse(ProvenanceContract.satisfied(kind, id),
                    kind + ": nothing published yet, must not be satisfied");
            ProvenancePublisher.record(kind, id, "ref", "a wish for a " + kind);
            assertTrue(ProvenanceContract.satisfied(kind, id),
                    kind + ": a recorded grant with an intent must satisfy the contract");
        }
    }

    /** The failure mode the contract catches: an artifact published WITHOUT capturing the intent. */
    @Test
    void aGrantThatDidNotCaptureIntentViolatesTheContract(@TempDir Path repo) {
        pointAt(repo);
        ProvenanceContext.clear(); // simulate the bug: nobody stamped the intent
        ProvenancePublisher.record("datapack", "no_intent", "ref"); // uses (empty) context intent
        assertFalse(ProvenanceContract.satisfied("datapack", "no_intent"),
                "a manifest with a blank intent must NOT satisfy the contract");
    }
}
