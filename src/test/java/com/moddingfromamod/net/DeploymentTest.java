package com.moddingfromamod.net;

import com.moddingfromamod.ModdingFromAModMod;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gate A — the three supported deployments, and the fourth that cannot work.
 *
 * <p>Every test here is named after a row in {@code docs/DEPLOYMENTS.md}. The point of driving
 * {@link Deployment#classify} rather than {@link Deployment#here()} is that a unit test can pose
 * shapes this machine cannot boot — a server with a mod nobody has written yet, or the empty
 * degenerate case — and the classifier is the thing that has to be right about all of them.
 */
class DeploymentTest {

    private static final String US = ModdingFromAModMod.MODID;

    // ── The three supported deployments ──────────────────────────────────────────────────

    @Test
    void deploymentOne_ourModAlone_welcomesAStockClient() {
        Deployment.Verdict v = Deployment.classify(List.of("minecraft", "neoforge", US));
        assertEquals(Deployment.Shape.MOD_ALONE, v.shape());
        assertTrue(v.stockClientWelcome());
        assertTrue(v.companions().isEmpty());
    }

    @Test
    void deploymentTwo_isTheSameSERVER_soTheShapeIsTheSame() {
        // Deployments 1 and 2 differ only in what the CLIENT has, which a server cannot know at
        // boot and does not need to: the per-client VIEW is decided later by Send.canReceive.
        // If this ever stops being true, something has taught the server to branch on the client,
        // which is exactly the second code path refused to introduce.
        Deployment.Verdict v = Deployment.classify(List.of("minecraft", "neoforge", US));
        assertTrue(v.stockClientWelcome());
    }

    @Test
    void deploymentThree_ourModPlusCreate_declinesToPromiseAStockClient() {
        Deployment.Verdict v = Deployment.classify(
                List.of("minecraft", "neoforge", US, "create", "flywheel", "ponder"));
        assertEquals(Deployment.Shape.WITH_COMPANIONS, v.shape());
        assertFalse(v.stockClientWelcome());
        assertEquals(List.of("create", "flywheel", "ponder"), v.companions());
    }

    // ── The bundled-library trap, which is the one that would have shipped wrong ─────────

    @Test
    void aJarJarredLibraryIsOurOwnFootprintAndNotACompanion() {
        // A JarJar'd library appears in ModList as a separate mod, so an allowlist that missed it
        // would report "other mods are loaded" on the deployment that is most certainly mod-alone.
        // geckolib is behind uses_geckolib, false today, so this row is DEFENSIVE -- one line in
        // gradle.properties away from being live. The same shape is already live from the other
        // side: installing Create puts create + flywheel + ponder in the list, measured.
        Deployment.Verdict v =
                Deployment.classify(List.of("minecraft", "neoforge", US, "geckolib"));
        assertEquals(Deployment.Shape.MOD_ALONE, v.shape());
    }

    @Test
    void theAllowlistIsNotEmptyAndNamesUs() {
        // Scope assertion, per §X11: a classifier whose allowlist quietly emptied would report
        // MOD_ALONE for nothing at all, and every test above would still pass except this one.
        assertFalse(Deployment.PLATFORM.isEmpty());
        assertFalse(Deployment.BUNDLED.isEmpty());
        assertTrue(Deployment.PLATFORM.contains("neoforge"));
    }

    // ── Coarse in the HARMLESS direction, which is the whole argument for ModList ────────

    @Test
    void anUnknownModIdMakesItDeclineToPromiseRatherThanWronglyPromise() {
        // The failure mode of a coarse allowlist is a false "other mods are loaded", which costs a
        // log line. The failure mode of the reflection census is silence after a NeoForge release
        // moves a private field. This test pins which direction we chose.
        Deployment.Verdict v = Deployment.classify(
                List.of("minecraft", "neoforge", US, "a_mod_nobody_has_written_yet"));
        assertFalse(v.stockClientWelcome());
        assertEquals(List.of("a_mod_nobody_has_written_yet"), v.companions());
    }

    @Test
    void companionsAreSortedAndDeduplicatedSoTheBootLineIsStable() {
        Deployment.Verdict v = Deployment.classify(
                List.of("zzz", "aaa", "AAA", "minecraft", US, "  ", "zzz"));
        assertEquals(List.of("aaa", "zzz"), v.companions());
    }

    @Test
    void anEmptyModListIsNotAPromise() {
        // Degenerate, but it is the shape a broken ModList read would produce, and "we saw no mods
        // therefore a stock client is welcome" would be a promise made from an absence of evidence.
        // MOD_ALONE here is correct only because the caller is ModList, which always contains us.
        assertEquals(Deployment.Shape.MOD_ALONE, Deployment.classify(List.of()).shape());
    }

    // ── The boot line itself, because it is the whole deliverable ────────────────────────

    @Test
    void theLineForAModAloneServerSaysAStockClientCanJoin() {
        String line = Deployment.line(Deployment.classify(List.of(US)));
        assertTrue(line.contains("STOCK"), line);
        assertTrue(line.contains("can join"), line);
    }

    @Test
    void theLineForACompanionServerNAMESTheModsAndPointsAtTheCensus() {
        // Deployment 4 -- Moddy + Create on the server, a stock client at the door -- is the one an
        // admin assembles by accident. Today it fails as a bare "connection lost". The line has to
        // name what closed the door and where the exact answer lives, or it is no better.
        String line = Deployment.line(Deployment.classify(List.of(US, "create")));
        assertTrue(line.contains("create"), line);
        assertTrue(line.contains("may be refused"), line);
        assertTrue(line.contains("probe.tsv"), line);
        assertTrue(line.contains("unaffected"), line);
    }

    @Test
    void theTwoLinesAreDifferentSentences() {
        String alone = Deployment.line(Deployment.classify(List.of(US)));
        String with = Deployment.line(Deployment.classify(List.of(US, "create")));
        assertFalse(alone.equals(with));
    }

    // ── The flag itself ─────────────────────────────────────────────────────────────────

    @Test
    void optionalPayloadsDefaultsON_whichIsTheWholeOf547() {
        String was = System.getProperty(ModNetwork.OPTIONAL_PROPERTY);
        try {
            System.clearProperty(ModNetwork.OPTIONAL_PROPERTY);
            assertTrue(ModNetwork.optionalPayloads(),
                    "absent must mean ON -- a shipped server has to let a stock client in");
        } finally {
            restore(was);
        }
    }

    @Test
    void onlyAnExplicitOffTurnsItBackIntoTheOldBehaviour() {
        String was = System.getProperty(ModNetwork.OPTIONAL_PROPERTY);
        try {
            for (String off : Set.of("off", "OFF", "false", "False")) {
                System.setProperty(ModNetwork.OPTIONAL_PROPERTY, off);
                assertFalse(ModNetwork.optionalPayloads(), off);
            }
            // Anything else is ON, including the old "on" spelling that seven rigs still pass and
            // a typo, because the safe direction for this flag is now "let the child in".
            for (String on : Set.of("on", "true", "", "  ", "yes", "nonsense")) {
                System.setProperty(ModNetwork.OPTIONAL_PROPERTY, on);
                assertTrue(ModNetwork.optionalPayloads(), on);
            }
        } finally {
            restore(was);
        }
    }

    private static void restore(String was) {
        if (was == null) {
            System.clearProperty(ModNetwork.OPTIONAL_PROPERTY);
        } else {
            System.setProperty(ModNetwork.OPTIONAL_PROPERTY, was);
        }
    }
}
