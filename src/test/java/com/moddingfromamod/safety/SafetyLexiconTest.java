package com.moddingfromamod.safety;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The floor, and above all the thing the floor must NOT do.
 *
 * <p>The headline test here is {@link #everyWishAChildActuallyTypedComesBackClean()}. A denylist's
 * cost lands on the child, and the 84 wishes in {@code corpus/real-wishes.jsonl} are the only
 * direct evidence in this repo of what he asks for: killing bosses, grinding mobs into meat, a
 * liquid made of bottoms. If the floor fires on one of those, the layer is anti-correlated with
 * the feature it is protecting (EVAL_STRATEGY.md: "the obvious rule can be inverted, not merely
 * weak").
 */
class SafetyLexiconTest {

    private static SafetyLexicon of(String... lines) {
        return SafetyLexicon.load(new StringReader(String.join("\n", lines)));
    }

    private static SafetyLexicon shipped() {
        return SafetyLexicon.fromDiskOrJar(null);
    }

    // ---- the shipped list ----

    @Test
    void theShippedListLoadsAndIsSmall() {
        SafetyLexicon l = shipped();
        assertTrue(l.size() > 0, "the bundled lexicon is not on the classpath — the floor is gone "
                + "and nothing would say so");
        assertTrue(l.size() < 100,
                "a list long enough to be a filter is a denylist, and a denylist's false "
                        + "positives land on the child. Everything nuanced belongs to the model.");
    }

    @Test
    void everyWishAChildActuallyTypedComesBackClean() throws Exception {
        // The real corpus is private: here it is read through CorpusSource; the public repo reads the
        // model-written fixture (corpus/fixtures/sample-wishes.jsonl) through the same code.
        SafetyLexicon l = shipped();
        List<String> fired = new ArrayList<>();
        int n = 0;
        for (String line : com.moddingfromamod.CorpusSource.lines("real-wishes.jsonl")) {
            if (line.isBlank()) {
                continue;
            }
            JsonObject o = JsonParser.parseString(line).getAsJsonObject();
            if (!o.has("prompt")) {
                continue;   // the generated header comment
            }
            n++;
            String prompt = o.get("prompt").getAsString();
            l.check(prompt).ifPresent(v -> fired.add(prompt + "  ->  " + v.overallLabel()
                    + " wellbeing=" + v.wellbeing() + " " + v.reasonNames()));
        }
        assertTrue(n >= 50, "the " + (com.moddingfromamod.CorpusSource.real() ? "real-wish corpus" : "public fixture")
                + " should not have shrunk to " + n + " rows");
        assertTrue(fired.isEmpty(),
                "the lexicon fired on " + fired.size() + " of " + n + " wishes a CHILD ACTUALLY "
                        + "TYPED. Every one of these is a wish he wanted:\n  "
                        + String.join("\n  ", fired));
    }

    @Test
    void theShippedListRefusesTheThingsItIsFor() {
        SafetyLexicon l = shipped();
        Optional<SafetyVerdict> v = l.check("make me some hardcore PORN please");
        assertTrue(v.isPresent());
        assertEquals(SafetyVerdict.Band.CLEARLY, v.get().sexual());
        assertEquals(SafetyVerdict.Provenance.LEXICON, v.get().provenance());
        assertTrue(v.get().reasonNames().contains("SEXUAL"));

        assertEquals(SafetyVerdict.Band.CLEARLY,
                l.check("a villager that sells cocaine").orElseThrow().danger());
    }

    @Test
    void selfHarmIsAWellbeingNoteAndNotABlock() {
        // §1: a wish that reads as real distress is NEVER blocked. Blocking a child in distress is
        // the worst possible answer, so WELLBEING deliberately sets no band at all — a caller that
        // read "the lexicon returned something" as "refuse" would get this exactly backwards.
        SafetyVerdict v = shipped().check("make me a knife to hurt myself").orElseThrow();
        assertTrue(v.wellbeing());
        assertEquals(SafetyVerdict.Band.FINE, v.overall());
        assertTrue(v.reasonNames().contains("SELF_HARM"));
    }

    @Test
    void aRespawnRequestIsNotADistressSignal() {
        // "kill me" is in the real corpus. In Minecraft it means "put me back at my bed", and a
        // grown-up note that fires on every respawn is a note nobody reads.
        for (String wish : List.of("kill me", "kill myself", "kill me please", "how do i kill me")) {
            assertTrue(shipped().check(wish).isEmpty(), "the floor fired on: " + wish);
        }
    }

    // ---- matching ----

    @Test
    void matchingIsWholeWordAndNeverASubstring() {
        SafetyLexicon l = of("SEXUAL\trape\tSEXUAL", "DANGER\tmeth\tDRUGS", "HATE\tass\tSLUR");
        for (String innocent : List.of(
                "a grape farm with grapes",
                "make an assassin creature with a hood",
                "build me Scunthorpe with a castle",
                "a drape over the window",
                "a methane gas machine that goes bang",
                "a bass guitar that shoots notes",
                "put a classy hat on the pig")) {
            assertTrue(l.check(innocent).isEmpty(),
                    "substring matching is back, and it fires on: " + innocent);
        }
        assertTrue(l.check("the word rape on its own").isPresent(),
                "and the term itself must still match, or the rule above has rotted into a no-op");
    }

    @Test
    void punctuationAndCaseAndLookalikesDoNotDodgeIt() {
        SafetyLexicon l = of("SEXUAL\tporn\tSEXUAL");
        for (String dodge : List.of("PORN", "Porn!", "make (porn) now", "a porn, please",
                "ＰＯＲＮ")) {          // fullwidth: NFKC folds it, exactly as WishText.sanitize does
            assertTrue(l.check(dodge).isPresent(), "this got past the fold: " + dodge);
        }
    }

    @Test
    void aMultiWordTermMatchesAcrossAnyRunOfWhitespace() {
        SafetyLexicon l = of("SEXUAL\thave sex\tSEXUAL");
        assertTrue(l.check("two mobs that have sex").isPresent());
        assertTrue(l.check("two mobs that   have\tsex").isPresent());
        assertTrue(l.check("i have a sex-free castle").isEmpty(),
                "the words must still be adjacent — 'have' and 'sex' apart are not the term");
    }

    @Test
    void severalHitsCombineIntoOneVerdict() {
        SafetyLexicon l = of("SEXUAL\tporn\tSEXUAL", "DANGER\theroin\tDRUGS",
                "WELLBEING\tcut myself\tSELF_HARM");
        SafetyVerdict v = l.check("porn and heroin and cut myself").orElseThrow();
        assertEquals(SafetyVerdict.Band.CLEARLY, v.sexual());
        assertEquals(SafetyVerdict.Band.CLEARLY, v.danger());
        assertEquals(SafetyVerdict.Band.FINE, v.violence());
        assertTrue(v.wellbeing());
        assertTrue(v.redirect().isEmpty(),
                "the floor never proposes a redirect: §2 gives one to LIKELY alone, and this list "
                        + "only ever produces CLEARLY");
    }

    // ---- the file format ----

    @Test
    void commentsBlanksAndBrokenLinesAreIgnoredRatherThanFatal() {
        SafetyLexicon l = of("# a comment", "", "   ", "NOTANAXIS\tfoo", "SEXUAL",
                "SEXUAL\tporn\tSEXUAL", "SEXUAL\thentai\tNOTAREASON");
        assertEquals(2, l.size(), "only the two well-formed entries should have loaded");
        assertEquals(List.of("OTHER"), l.check("hentai").orElseThrow().reasonNames(),
                "an unknown reason degrades to OTHER rather than dropping the term");
    }

    @Test
    void aMissingListIsEmptyRatherThanAnException() {
        SafetyLexicon l = SafetyLexicon.load(new StringReader(""));
        assertEquals(0, l.size());
        assertTrue(l.check("anything at all").isEmpty());
        assertTrue(l.check(null).isEmpty());
        assertTrue(l.check("").isEmpty());
    }

    // ---- the override ----

    @Test
    void anOverrideOnDiskWinsOverTheBundledList(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("lexicon.txt");
        Files.writeString(f, "# a parent's own list\nVIOLENCE\tsquelch\tGORE\n",
                StandardCharsets.UTF_8);
        SafetyLexicon l = SafetyLexicon.fromDiskOrJar(f);
        assertEquals(1, l.size(), "the override should have replaced the bundled list entirely");
        assertTrue(l.check("a squelch monster").isPresent());
        assertTrue(l.check("make me some PORN").isEmpty(),
                "the bundled terms must be gone — this is a replacement, not a merge, so a term "
                        + "that fires on a real wish can be removed tonight");
    }

    @Test
    void anAbsentOrUnreadableOverrideFallsBackToTheBundledList(@TempDir Path dir) {
        assertTrue(SafetyLexicon.fromDiskOrJar(dir.resolve("not-there.txt")).size() > 0);
        // A directory where a file should be: unreadable, and must not stop a wish.
        assertTrue(SafetyLexicon.fromDiskOrJar(dir).size() > 0);
    }

    @Test
    void noSlurListIsCheckedIn() throws Exception {
        // Deliberate (§12, and stated in the file's own header): which words those are is a
        // maintainer's decision and not a thing to improvise into a checked-in file; the model covers them
        // meanwhile. Asserting the DECISION means writing one is a visible edit to this test
        // rather than a quiet drift, and it stops a well-meaning session filling the axis in.
        String file = Files.readString(
                Path.of("src/main/resources/safety/lexicon.txt"), StandardCharsets.UTF_8);
        List<String> hate = file.lines().filter(l -> l.startsWith("HATE\t")).toList();
        assertTrue(hate.isEmpty(),
                "the HATE axis has acquired terms: " + hate + ". If that is intended, say so here "
                        + "and in the file header — it is a policy decision, not a code change.");
    }

    @Test
    void aMalformedReasonFieldCostsThatLineAndNotEveryLineAfterIt() {
        // ⚠ A REGRESSION TEST FOR A SILENT TRUNCATION, not for a parse nicety. `parseLine` read
        // the reason with `parts[2].split("#")[0]`, and String.split DROPS trailing empty
        // strings — so "#".split("#") is a ZERO-length array and [0] threw
        // ArrayIndexOutOfBoundsException. `load` catches that under "a truncated floor beats
        // none", so ONE operator typo in an override silently ended the floor at that line and
        // every term below it stopped existing. The override is documented as a file edited by
        // hand tonight, which is exactly when a stray `#` happens.
        SafetyLexicon l = of(
                "SEXUAL\tporn\tSEXUAL",
                "DANGER\tcocaine\t#",          // the offending line: a bare comment marker
                "DANGER\theroin\tDRUGS",
                "WELLBEING\tcut myself\tSELF_HARM");
        assertEquals(4, l.size(),
                "every line must survive: the bad field costs its own REASON, never the rest of "
                        + "the file");
        assertTrue(l.check("give me heroin").isPresent(),
                "a term BELOW the malformed line must still fire — this is the bug");
        assertTrue(l.check("i want to cut myself").map(SafetyVerdict::wellbeing).orElse(false),
                "the wellbeing row was last in the file and is the one that matters most");
        assertEquals(SafetyVerdict.Reason.OTHER,
                l.check("some cocaine").orElseThrow().reasons().get(0),
                "the line itself keeps its band and degrades to OTHER, which is what an "
                        + "unreadable reason already did");
    }

    @Test
    void aTrailingCommentStillLeavesTheReasonReadable() {
        // The half the `indexOf` fix must not break: a reason WITH a comment after it.
        SafetyLexicon l = of("DANGER\tcocaine\tDRUGS  # named hard drug");
        assertEquals(SafetyVerdict.Reason.DRUGS,
                l.check("some cocaine").orElseThrow().reasons().get(0));
    }
}
