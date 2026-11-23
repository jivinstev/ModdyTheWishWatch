package com.moddingfromamod.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.datapack.ItemCatalogue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Which item wishes the surface can express — asserted in BOTH directions.</b>
 *
 * <p>The ratchet described in {@code docs/ITEM_CAPABILITY_ROLLOUT.md}, and it exists because the
 * instrument it replaces cannot verify anything: {@code tools/item-gap-vocabulary.py}'s verdicts are
 * hand-written, so after building a capability the author edits the table that says whether the
 * capability worked. That is marking your own homework, and it is the same failure that tool's own
 * docstring records one level down — <i>"a column read off a schema once, which then rotted"</i>.
 *
 * <p>So {@code corpus/item-expressibility.jsonl} carries, per case, the {@code make_item} call that
 * SHOULD grant it — written for the cases that cannot work yet as much as for the ones that can,
 * which is what makes the file a <b>pre-registration</b> rather than a description of whatever got
 * built. This test asserts the recorded {@code expressible} flag against what the vocabulary
 * actually admits, and it fails in both directions:
 *
 * <ul>
 *   <li>a {@code false} row that has quietly started working — <b>an accidental unlock</b>, which is
 *       the "and nothing else moved" check every step of the rollout promises;
 *   <li>a {@code true} row that has stopped — a regression in a wish class that used to work.
 * </ul>
 *
 * <p><b>What "expressible" means here, exactly.</b> Not "the agent will reach for it" — that is
 * tier 3, a real-agent run, and the items A/B has prediction 1 on record that retrieval is the
 * likelier failure. Not "it compiles" — that is tier 2, Gate B, where the CBScript compiler is
 * guaranteed present rather than {@code Assumptions.assumeTrue}'d away. This tier asks the one
 * question a unit test can answer honestly and in milliseconds: <b>is there a call the vocabulary
 * admits?</b> Every axis in the rollout is a widening of that vocabulary, so every step moves this
 * number by construction.
 */
class ItemExpressibilityTest {

    /** One recorded case: the wish, the call that should grant it, and whether it can today. */
    record Case(String id, String wish, boolean expressible, JsonObject call,
                        List<String> blocks) { }

    static List<Case> corpus() {
        List<Case> out = new ArrayList<>();
        var in = ItemExpressibilityTest.class.getResourceAsStream(
                "/corpus/item-expressibility.jsonl");
        assertTrue(in != null, "the expressibility corpus is not on the classpath — a gate that "
                + "cannot find its own fixtures reports green over nothing");
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                if (!o.has("id")) {
                    continue;               // the header comment
                }
                JsonObject call = o.has("call") && o.get("call").isJsonObject()
                        ? o.getAsJsonObject("call") : null;
                List<String> blocks = new ArrayList<>();
                for (JsonElement b : o.getAsJsonArray("blocks")) {
                    blocks.add(b.getAsString());
                }
                out.add(new Case(o.get("id").getAsString(), o.get("wish").getAsString(),
                        o.get("expressible").getAsBoolean(), call, blocks));
            }
        } catch (Exception e) {
            throw new AssertionError("could not read the expressibility corpus: " + e, e);
        }
        return out;
    }

    /**
     * What one member of a family may carry today.
     *
     * <p>Read from {@code parseVariants}, which is the only thing that interprets them: it takes a
     * name and a colour and refuses anything without either. Kept beside the top-level vocabulary
     * because a wish can be blocked one level down — a family whose members differ by SIZE needs a
     * per-member property, and the size is the whole point of asking for three cups.
     */
    private static final Set<String> VARIANT_KEYS = Set.of("name", "colour", "properties");

    /** The keys {@code make_item} declares. A key outside this set is a capability we do not have. */
    private static Set<String> vocabulary() {
        JsonObject schema = new MakeItemTool().inputSchema();
        return new LinkedHashSet<>(schema.getAsJsonObject("properties").keySet());
    }

    /**
     * Why this call is not admissible today, or empty if it is.
     *
     * <p>Reasons rather than a boolean, deliberately: "this wish is blocked" is not actionable and
     * "this wish is blocked because there is no shape meaning `does nothing`" names the next axis to
     * build. It is also what lets the test check that the corpus's own {@code blocks} field is not
     * fiction.
     */
    static List<String> refusals(JsonObject call) {
        List<String> why = new ArrayList<>();
        if (call == null) {
            why.add("no make_item call can express this at all");
            return why;
        }
        Set<String> known = vocabulary();
        for (String key : call.keySet()) {
            if (key.endsWith("_note")) {
                continue;                   // prose for a human reading the row
            }
            if (!known.contains(key)) {
                why.add("make_item has no '" + key + "'");
            }
        }
        // AND INSIDE EACH FAMILY MEMBER, because a capability can be missing one level down and the
        // top-level check cannot see it. Caught by this test disagreeing with a prediction: the
        // `plain` shape was expected to unlock two rows and unlocked three, and the third was a row
        // whose members carry per-member `properties` -- an axis that does not exist. Reading it as
        // expressible would have recorded a wish as granted while the half that makes it mean
        // anything (a LARGE cup that is actually different from a small one) was still missing.
        if (call.has("variants") && call.get("variants").isJsonArray()) {
            for (JsonElement el : call.getAsJsonArray("variants")) {
                if (!el.isJsonObject()) {
                    continue;
                }
                for (String key : el.getAsJsonObject().keySet()) {
                    if (!VARIANT_KEYS.contains(key)) {
                        why.add("a family member has no '" + key + "'");
                    }
                }
            }
        }
        // An edit inherits the rest from the design it starts from, so the required-field rule is
        // only asked of a call that is making something new. Duplicating `inherit`'s knowledge here
        // is how the two would drift.
        if (!call.has("start_from")) {
            why.addAll(MakeItemTool.missingFields(call));
        } else {
            // ── TIER 1 CANNOT DECIDE A REFINEMENT, AND SAYS SO RATHER THAN GUESSING.
            //
            // This checker asks one question: does the VOCABULARY admit this call. A `start_from`
            // wish is not answerable that way -- it needs a design already in the store, which a
            // unit test has no notion of, and whether the thing the child means is one this engine
            // ever made is a fact about his world rather than about the schema.
            //
            // Recorded as a refusal because the alternative is worse and nearly happened: the
            // PROPERTIES axis made every key in `uns_items_1` known, so this test reported an
            // ACCIDENTAL UNLOCK for a wish that still cannot be granted -- the child's bloop slayer
            // is a mod's item, and start_from cannot name one. Refinement is verified where it can
            // be: VariantsSurviveRefinementTest, PropertiesSurviveRefinementTest, and the ladders.
            why.add("tier 1 has no store, so a start_from refinement cannot be checked here");
        }
        // ── COLOUR AND ICON ARE VALUES, AND A VALUE CAN BE INADMISSIBLE TOO.
        //
        // Found by a test written against this corpus rather than by the corpus itself: the
        // pre-registered call for the cups asks for `light_gray`, which is not a colour this tool
        // has, so the wish it claims to express would have been REFUSED. The checker read the row
        // as expressible because it only ever inspected key NAMES. A capability recorded as working
        // on a call that cannot run is the same failure as a gate that never fires.
        for (String key : List.of("name", "icon", "colour", "shape")) {
            if (call.has(key) && !call.get(key).isJsonPrimitive()) {
                why.add(key + " must be a plain value");
            }
        }
        if (call.has("icon") && !ItemCatalogue.ICONS.containsKey(call.get("icon").getAsString())) {
            why.add("no icon '" + call.get("icon").getAsString() + "'");
        }
        why.addAll(colourRefusals(call));
        // Properties, at both levels. The axis is a closed table of bounded values, so an
        // out-of-range one is as inexpressible as a misspelled key and must read the same.
        why.addAll(propertyRefusals(call));
        if (call.has("base_item")) {
            why.addAll(com.moddingfromamod.datapack.ItemBase.refusals(
                    call.get("base_item").getAsString()));
        }
        if (call.has("recipient")) {
            why.addAll(com.moddingfromamod.datapack.ItemRecipient.refusals(
                    call.get("recipient").getAsString()));
        }
        if (call.has("parts") && call.get("parts").isJsonArray()) {
            why.addAll(com.moddingfromamod.datapack.ItemParts.refusals(
                    call.getAsJsonArray("parts")));
        }
        if (call.has("yield") && call.get("yield").isJsonObject()) {
            why.addAll(com.moddingfromamod.datapack.ItemYield.refusals(
                    call.getAsJsonObject("yield"),
                    call.has("shape") ? call.get("shape").getAsString() : "",
                    call.has("variants") && call.get("variants").isJsonArray()
                            && !call.getAsJsonArray("variants").isEmpty()));
        }
        String shape = call.has("shape") ? call.get("shape").getAsString() : null;
        if (shape != null && !ItemCatalogue.SHAPES.contains(shape)) {
            why.add("no shape '" + shape + "'");
        }
        if (call.has("effect")) {
            ItemCatalogue cat = ItemCatalogue.load();
            for (String id : call.get("effect").getAsString().split("\\+")) {
                ItemCatalogue.Entry e = cat.get(id.trim());
                if (e == null) {
                    why.add("no effect '" + id.trim() + "'");
                    continue;
                }
                if (call.has("params") && call.get("params").isJsonObject()
                        && call.getAsJsonObject("params").has(id.trim())
                        && call.getAsJsonObject("params").get(id.trim()).isJsonObject()) {
                    for (String p : call.getAsJsonObject("params")
                            .getAsJsonObject(id.trim()).keySet()) {
                        if (!e.allParams().contains(p)) {
                            why.add("effect '" + id.trim() + "' has no parameter '" + p + "'");
                        }
                    }
                }
            }
        }
        return why;
    }

    /** Colour refusals, for the wish and for every member of its family. */
    private static List<String> colourRefusals(JsonObject call) {
        List<String> why = new ArrayList<>();
        if (call.has("colour") && MakeItemTool.colourOf(call.get("colour").getAsString()) == null) {
            why.add("no colour '" + call.get("colour").getAsString() + "'");
        }
        if (call.has("variants") && call.get("variants").isJsonArray()) {
            for (JsonElement el : call.getAsJsonArray("variants")) {
                if (!el.isJsonObject() || !el.getAsJsonObject().has("colour")) {
                    continue;
                }
                String c = el.getAsJsonObject().get("colour").getAsString();
                if (MakeItemTool.colourOf(c) == null) {
                    why.add("a family member asks for colour '" + c + "', which does not exist");
                }
            }
        }
        return why;
    }

    /** Property refusals from the real table, for the wish and for every member of its family. */
    private static List<String> propertyRefusals(JsonObject call) {
        List<String> why = new ArrayList<>();
        if (call.has("properties") && call.get("properties").isJsonObject()) {
            why.addAll(com.moddingfromamod.datapack.ItemProperties.refusals(
                    call.getAsJsonObject("properties")));
        }
        if (call.has("variants") && call.get("variants").isJsonArray()) {
            for (JsonElement el : call.getAsJsonArray("variants")) {
                if (el.isJsonObject() && el.getAsJsonObject().has("properties")
                        && el.getAsJsonObject().get("properties").isJsonObject()) {
                    why.addAll(com.moddingfromamod.datapack.ItemProperties.refusals(
                            el.getAsJsonObject().getAsJsonObject("properties")));
                }
            }
        }
        return why;
    }

    @Test
    @DisplayName("the recorded expressibility is what the vocabulary actually admits")
    void theCorpusMatchesReality() {
        List<String> wrong = new ArrayList<>();
        for (Case c : corpus()) {
            List<String> why = refusals(c.call());
            boolean actually = why.isEmpty();
            if (actually != c.expressible()) {
                wrong.add(c.expressible()
                        ? "[" + c.id() + "] recorded EXPRESSIBLE and is not: " + why
                          + "  — a wish class that used to work has stopped"
                        : "[" + c.id() + "] recorded NOT expressible and now IS"
                          + "  — an accidental unlock: flip the flag in the commit that caused it, "
                          + "or find out which capability leaked");
            }
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    @Test
    @DisplayName("today: 12 of 21 — the number the rollout moves")
    void theBaselineIsRecorded() {
        // A count, pinned, so a step that claims to unlock four and unlocks one is caught by
        // arithmetic rather than by reading. Raise it in the same commit as the capability.
        long yes = corpus().stream().filter(Case::expressible).count();
        assertEquals(19, yes, "the expressible count moved — if that was deliberate, this number "
                + "moves with it, and docs/ITEM_CAPABILITY_ROLLOUT.md records what unlocked it");
        assertEquals(21, corpus().size(), "the corpus changed size; the gap set it mirrors is "
                + "20 cases plus the one child-typed wish they miss");
    }

    @Test
    @DisplayName("every blocked case says WHY, in the vocabulary's own terms")
    void everyBlockedCaseNamesItsBlocker() {
        // A corpus that only says "no" is a list of complaints. Each row has to name the axis that
        // would unblock it, or the rollout cannot be ordered by what unlocks the most.
        for (Case c : corpus()) {
            if (!c.expressible()) {
                assertFalse(c.blocks().isEmpty(),
                        "[" + c.id() + "] is blocked and does not say by what");
            } else {
                assertTrue(c.blocks().isEmpty(),
                        "[" + c.id() + "] is expressible but still lists blockers: " + c.blocks());
            }
        }
    }

    @Test
    @DisplayName("the corpus mirrors the live gap set, so it cannot rot beside it")
    void itCoversTheGapSet() {
        // 20 uns_items_N rows, and the one child-typed wish the mined set misses. If the gap set
        // grows, tools/item-gap-vocabulary.py already fails loudly; this makes the same movement
        // fail on the Java side rather than leaving two instruments quietly disagreeing.
        Set<String> ids = new LinkedHashSet<>();
        corpus().forEach(c -> ids.add(c.id()));
        for (int i = 0; i < 20; i++) {
            assertTrue(ids.contains("uns_items_" + i), "the corpus is missing uns_items_" + i);
        }
        assertTrue(ids.contains("typed_sword"),
                "the one WISHED case must stay — every other row is INFERRED from what we built");
    }
}
