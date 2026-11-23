package com.moddingfromamod.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The thermometer: which authored abilities name a mod entity type, and what would the
 * child lose?</b>
 *
 * <p>This measures rather than gates the agent, and that split is deliberate. The hazard —
 * a {@code type=moddingfromamod:…} filter that parses, runs and matches nothing once the thing it
 * names is shown some other way — <b>cannot happen today</b>, because no route substitutes a
 * creature yet. Refusing or rewriting the selector now would reject code that is correct on the
 * shipping path. So the classifier is proven here, the census is reported here, and the decision to
 * wire it into {@code author_datapack} is recorded in {@code docs/VANILLA_BEHAVIOUR_SPIKE_RESULTS.md}
 * as owed to the creature route rather than taken early.
 *
 * <p>What IS a gate, and belongs here rather than in a document: the shipped catalogue must never
 * start naming a mod entity type. That is what keeps the exposure agent-authored and bounded.
 */
class SelectorTypesTest {

    // ── The three shapes, on fixtures rather than on the corpus, so they hold when it is remined ──

    @Test
    @DisplayName("a type filter beside a tag is redundant today and removable")
    void aTypeFilterBesideATagIsRemovable() {
        List<SelectorTypes.ModTypeUse> uses = SelectorTypes.modTypeUses(
                "/execute as @e[tag=wishmob_spikor,type=moddingfromamod:wish_mob] at @s run say hi");
        assertEquals(1, uses.size(), "one mod type filter expected");
        assertFalse(uses.get(0).negated());
        assertTrue(uses.get(0).narrowedByTag(),
                "tag=wishmob_spikor in the same selector still identifies the creature without "
                        + "the type filter, so this one is removable");
        assertTrue(uses.get(0).symptom().contains("nothing happens"));
    }

    @Test
    @DisplayName("a type filter with no tag is the only thing narrowing the selector")
    void aTypeFilterWithNoTagIsLoadBearing() {
        // The reason this class classifies instead of rewriting. Delete the conjunct here and
        // `kill @e[type=moddingfromamod:wish_mob]` becomes `kill @e` — every entity in the world.
        List<SelectorTypes.ModTypeUse> uses = SelectorTypes.modTypeUses(
                "/kill @e[type=moddingfromamod:wish_mob]");
        assertEquals(1, uses.size());
        assertFalse(uses.get(0).narrowedByTag(),
                "nothing else narrows this selector, so the filter cannot simply be dropped");
        assertTrue(uses.get(0).symptom().contains("every entity in the world"));
    }

    @Test
    @DisplayName("a negated type filter fails the OTHER way: it stops excluding")
    void aNegatedTypeFilterStopsExcluding() {
        // Spikor's fire breath spares wish creatures from its own damage cone. If the exclusion
        // stops excluding, the ability still fires — on Spikor himself.
        List<SelectorTypes.ModTypeUse> uses = SelectorTypes.modTypeUses(
                "/execute positioned ^ ^ ^2 as @e[distance=..3,type=!moddingfromamod:wish_mob] "
                        + "run damage @s 4 minecraft:on_fire");
        assertEquals(1, uses.size());
        assertTrue(uses.get(0).negated(), "type=! is an exclusion, not a search");
        assertTrue(uses.get(0).symptom().contains("stops excluding"));
        assertTrue(uses.get(0).symptom().contains("on the wrong things"),
                "the two polarities must not report the same symptom: one does nothing, the other "
                        + "does something to the thing it was written to spare");
    }

    @Test
    @DisplayName("a tag AFTER a nested list is still seen — the depth scan, not a lazy regex")
    void aTagAfterANestedListIsStillSeen() {
        // `[^\]]*` stops at the inner ] and truncates the body, losing the tag that follows and
        // misreporting a removable filter as load-bearing. Zero cases in today's corpus nest, which
        // is exactly why writing it the cheap way would never have been caught.
        List<SelectorTypes.ModTypeUse> uses = SelectorTypes.modTypeUses(
                "/execute as @e[nbt={Tags:[\"x\"]},type=moddingfromamod:wish_mob,tag=wishmob] run say hi");
        assertEquals(1, uses.size(), "the selector body must be read past its nested list");
        assertTrue(uses.get(0).narrowedByTag(),
                "the tag sits after the nested list; a non-greedy bracket match never reaches it");
    }

    @Test
    @DisplayName("a comment ABOUT a selector is not an instance of one")
    void aCommentAboutASelectorIsNotOne() {
        // Verbatim from the mined corpus. A rule that cannot tell an instruction from an
        // explanation of one reports a false hazard and gets switched off within the week.
        assertTrue(SelectorTypes.modTypeUses(
                "# a define selector can't parse a namespaced type (the colon in "
                        + "@e[type=moddingfromamod:wish_mob]), so we tag trolls instead").isEmpty(),
                "a whole-line comment must not count as a selector");
    }

    @Test
    @DisplayName("a vanilla type filter is not a hazard — only the mod's own namespace substitutes")
    void aVanillaTypeFilterIsNotAHazard() {
        assertTrue(SelectorTypes.modTypeUses(
                "/execute as @e[type=minecraft:zombie,type=!minecraft:item] run say hi").isEmpty(),
                "vanilla entity types mean the same thing however a wished thing is rendered");
    }

    // ── The gate: the shipped catalogue stays path-independent ────────────────────────────────

    @Test
    @DisplayName("no shipped ability template names a mod entity type")
    void noShippedTemplateNamesAModEntityType() throws IOException {
        Path root = Path.of("src/main/resources");
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".cbs")).toList()) {
                scanned++;
                String src = Files.readString(p, StandardCharsets.UTF_8);
                for (SelectorTypes.ModTypeUse u : SelectorTypes.modTypeUses(src)) {
                    offenders.add(p.getFileName() + " selects " + u.entityType() + " — "
                            + u.symptom());
                }
            }
        }
        // Guard the scope, not just the finding: "no offenders" and "no files looked at" print the
        // same word, and this whole check is worthless if the templates ever move.
        assertTrue(scanned >= 30, "expected the shipped .cbs catalogue, scanned " + scanned);
        assertTrue(offenders.isEmpty(),
                "a shipped template must identify a wish creature by its tag, never by its entity "
                        + "type — the tag is applied by the mod and survives however the creature is "
                        + "shown. Offenders: " + offenders);
    }

    // ── The census: self-consistent, so a parser regression is visible ────────────────────────

    @Test
    @DisplayName("the classifier accounts for every mod entity type in the real corpus")
    void theClassifierAccountsForEveryModTypeInTheCorpus() throws IOException {
        // A raw count taken a completely different way. If the two disagree the parser has started
        // dropping cases — which is the failure that would make this whole measurement read as
        // "the corpus is clean" while it is not.
        Pattern raw = Pattern.compile("type=!?" + Pattern.quote(SelectorTypes.MOD_NS));
        int rawCount = 0;
        int classified = 0;
        int narrowed = 0;
        int loadBearing = 0;
        int negated = 0;
        int packs = 0;
        for (String source : corpusSources()) {
            String body = withoutComments(source);
            Matcher m = raw.matcher(body);
            while (m.find()) {
                rawCount++;
            }
            List<SelectorTypes.ModTypeUse> uses = SelectorTypes.modTypeUses(source);
            if (!uses.isEmpty()) {
                packs++;
            }
            for (SelectorTypes.ModTypeUse u : uses) {
                classified++;
                if (u.negated()) {
                    negated++;
                } else if (u.narrowedByTag()) {
                    narrowed++;
                } else {
                    loadBearing++;
                }
            }
        }
        assertTrue(rawCount > 0,
                "the mined corpus is expected to contain real agent-authored mod-type selectors; "
                        + "finding none means the corpus was not read, not that the hazard is gone");
        assertEquals(rawCount, classified,
                "every mod entity type in a selector must be classified — a silent miss reads as "
                        + "a clean corpus. census: packs=" + packs + " removable=" + narrowed
                        + " load-bearing=" + loadBearing + " exclusions=" + negated);
    }

    private static String withoutComments(String source) {
        return source.lines().filter(l -> !l.stripLeading().startsWith("#"))
                .reduce("", (a, b) -> a + "\n" + b);
    }

    private static List<String> corpusSources() throws IOException {
        List<String> out = new ArrayList<>();
        try (InputStream in = SelectorTypesTest.class.getResourceAsStream("/corpus/datapacks.jsonl")) {
            if (in == null) {
                return out;
            }
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String t = line.strip();
                if (t.isEmpty() || t.startsWith("//")) {
                    continue;
                }
                JsonObject o = JsonParser.parseString(t).getAsJsonObject();
                out.add(o.get("source").getAsString());
            }
        }
        return out;
    }
}
