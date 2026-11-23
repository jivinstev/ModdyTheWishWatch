package com.moddingfromamod.builder;

import com.moddingfromamod.testing.SourceRoots;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonParser;
import com.moddingfromamod.agent.tools.BuildStructureTool;
import com.moddingfromamod.agent.tools.InspectBuildTool;
import com.moddingfromamod.agent.tools.ListBuildsTool;
import com.moddingfromamod.agent.tools.RemoveBuildTool;
import com.moddingfromamod.wish.RealBuilds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.moddingfromamod.UsesCorpus;

/**
 * <b>Buildings on a client with no mod: the parity claims that can be settled without a world.</b>
 *
 * <p>Brief:.
 * Procedure: {@code docs/VANILLA_COMPAT_METHODOLOGY.md}. Results:
 * {@code docs/VANILLA_BUILDING_SPIKE_RESULTS.md}.
 *
 * <p><b>Why this file exists at all, given the answer was expected to be yes.</b> The expectation —
 * <i>"a building is vanilla blocks all the way down"</i> — was established by a {@code grep} of what
 * the tree happens to contain, and §2 rule 1 says in as many words that a list assembled that way is
 * missing exactly the things nobody thought of. §2 rule 2 says a row with no test is not at parity,
 * it is <i>believed</i> to be, which is a different column. This file moves the rows it can from the
 * second column to the first; {@code VanillaBuildGameTest} moves the ones that need a world.
 *
 * <p><b>The one thing to understand before reading the assertions.</b> Buildings have no
 * <i>substitute</i>. Items borrow leather horse armour, pictures borrow maps in item frames, and both
 * spikes therefore had to enumerate what the borrowed thing already meant. A building is placed with
 * {@code ServerLevel.setBlock} and reaches every client through vanilla's own chunk and block-update
 * packets, so the question is not "does the substitute behave" but the narrower and more answerable
 * <b>"can anything non-vanilla get in?"</b> There are exactly three doors, and each has a test here:
 * the {@code block} field the agent fills in (unconstrained by the schema), the blocks the builder
 * injects on its own ({@link BuildLighting}), and the fallback used for an id that does not resolve.
 *
 * <p>Everything here is pure, so the whole corpus runs in milliseconds and can sit on the unit
 * suite — the same argument {@code RealBuildCalibrationTest} makes for the oracle it shares.
 */
@UsesCorpus
class VanillaBuildParityTest {

    /** The only namespace a stock 1.21.1 client can render. */
    private static final String VANILLA = "minecraft";

    /**
     * A block id's namespace, with the bare form resolved the way {@code Builder.resolve} does it.
     *
     * <p>Load-bearing: {@code Builder.resolve} prefixes {@code minecraft:} when there is no colon,
     * so {@code "stone"} is a vanilla block and a test that read the raw string would call it
     * unnamespaced and be unable to say anything about it.
     */
    private static String namespaceOf(String blockId) {
        if (blockId == null) {
            return VANILLA;
        }
        int colon = blockId.indexOf(':');
        return colon < 0 ? VANILLA : blockId.substring(0, colon);
    }

    private static BuildPlan plan(String json) {
        return BuildPlan.fromJson(JsonParser.parseString(json).getAsJsonObject());
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // DOOR 1 — the block ids the AGENT names.
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>Every block in 19 buildings the agent really built is vanilla.</b>
     *
     * <p>Run over {@code corpus/real-builds.jsonl} through {@link BuildPlan#fromJson} — the same
     * parse the tool performs — rather than over the raw JSON, so it measures what the builder would
     * be handed rather than what the file says. The census is printed pass or fail, because "no mod
     * blocks" and "no mod blocks, out of 64 distinct ids over 392 shapes" are different claims and
     * only the second one says the sample was worth taking.
     *
     * <p>⚠ <b>What this does NOT establish, stated so a green run cannot be over-read:</b> it is a
     * census of what the agent HAS named, not of what the contract PERMITS. The schema says "any
     * block id" and {@link Builder} resolves any registry key, so this is evidence about behaviour
     * and {@code theContractDoesNotConstrainTheBlockNamespace} below is the evidence about the
     * contract. They point in different directions and both belong in the inventory.
     */
    @Test
    @DisplayName("every block in the real-build corpus is vanilla")
    void everyBlockTheAgentReallyNamedIsVanilla() {
        List<RealBuilds.Real> corpus = com.moddingfromamod.CorpusSource.realBuilds();
        assertTrue(corpus.size() >= 15,
                "the real-build corpus did not load (" + corpus.size() + " plans), so every "
                        + "assertion here would be vacuous. Re-mine it with tools/mine-real-builds.py.");

        Set<String> distinct = new TreeSet<>();
        TreeMap<String, Integer> byNamespace = new TreeMap<>();
        List<String> offenders = new ArrayList<>();
        int shapes = 0;
        for (RealBuilds.Real r : corpus) {
            for (BuildShape s : r.plan().shapes()) {
                shapes++;
                distinct.add(s.block());
                String ns = namespaceOf(s.block());
                byNamespace.merge(ns, 1, Integer::sum);
                if (!VANILLA.equals(ns)) {
                    offenders.add(r.id() + " -> " + s.block());
                }
            }
        }
        System.out.println("[vanilla-buildings] real corpus: " + corpus.size() + " builds, "
                + shapes + " shapes, " + distinct.size() + " distinct block ids, namespaces "
                + byNamespace);

        assertTrue(offenders.isEmpty(),
                "a building the agent really built names a block a stock client cannot render:\n  "
                        + String.join("\n  ", offenders));
        // The sample has to be big enough for its own silence to mean anything.
        assertTrue(distinct.size() >= 40,
                "only " + distinct.size() + " distinct block ids in the corpus — too narrow a "
                        + "sample for 'no mod blocks' to be worth quoting");
    }

    /**
     * <b>Verify the oracle to fail (§3.C.11).</b> The check above is a green tick over a corpus that
     * happens to be clean; without this it is equally consistent with a predicate that can never
     * fire. Both mod-block spellings are exercised, because the only block this mod registers is a
     * real thing an agent could name.
     */
    @Test
    @DisplayName("the vanilla check fires on a mod block")
    void theVanillaCheckWouldFireOnAModBlock() {
        BuildPlan modded = plan("""
            {"id":"probe","name":"probe","shapes":[
              {"kind":"box","width":3,"height":3,"depth":3,"block":"minecraft:stone"},
              {"kind":"box","width":1,"height":1,"depth":1,"block":"moddingfromamod:paint_panel"}]}
            """);
        List<String> bad = modded.shapes().stream()
                .map(BuildShape::block)
                .filter(b -> !VANILLA.equals(namespaceOf(b)))
                .toList();
        assertEquals(List.of("moddingfromamod:paint_panel"), bad,
                "the namespace predicate must be able to see a mod block, or every green run "
                        + "above measures nothing");

        // ...and the bare form is NOT a false positive: Builder.resolve prefixes minecraft:.
        BuildPlan bare = plan("""
            {"id":"probe2","name":"probe2","shapes":[
              {"kind":"box","width":3,"height":3,"depth":3,"block":"stone"}]}
            """);
        assertTrue(bare.shapes().stream().allMatch(s -> VANILLA.equals(namespaceOf(s.block()))),
                "an unnamespaced id is minecraft: — resolving it any other way would report a "
                        + "false mod block on every plan that writes 'stone'");
    }

    /**
     * <b>The corpus exercises every shape kind the schema offers</b>, so its silence covers the
     * whole contract rather than the part of it the agent happened to use.
     *
     * <p>Without this, {@code everyBlockTheAgentReallyNamedIsVanilla} could be green over boxes
     * alone while three quarters of {@code build_structure}'s enumerated {@code kind} values had
     * never been near a real building.
     */
    @Test
    @DisplayName("the real corpus covers every shape kind in the schema")
    void theRealCorpusCoversEveryShapeKindTheSchemaOffers() {
        Set<BuildShape.Kind> seen = new LinkedHashSet<>();
        for (RealBuilds.Real r : com.moddingfromamod.CorpusSource.realBuilds()) {
            for (BuildShape s : r.plan().shapes()) {
                seen.add(s.kind());
            }
        }
        List<BuildShape.Kind> missing = new ArrayList<>();
        for (BuildShape.Kind k : BuildShape.Kind.values()) {
            if (!seen.contains(k)) {
                missing.add(k);
            }
        }
        assertTrue(missing.isEmpty(),
                "the corpus never exercises " + missing + ", so a clean run over it says nothing "
                        + "about those shapes. Either mine a build that uses them or narrow the "
                        + "claim the corpus is quoted for.");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // DOOR 2 — the blocks the BUILDER injects on its own, which no corpus can see.
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>A building can contain blocks the agent never named, and they are vanilla too.</b>
     *
     * <p>{@link BuildLighting} hangs lanterns in a room that encloses a space and has no light of
     * its own — so a census of the agent's own output is structurally blind to them. This is the
     * §3.B.10 move applied to the one thing here that behaves like a substitute: enumerate what the
     * builder adds by itself, before asking whether the corpus is clean.
     */
    @Test
    @DisplayName("the blocks the builder adds by itself are vanilla")
    void theBlocksTheBuilderInjectsItselfAreVanilla() {
        assertEquals(VANILLA, namespaceOf(BuildLighting.LIGHT_BLOCK),
                "the lantern the builder hangs in a dark room must be a block a stock client has");

        // Driven rather than read: a plan that encloses an unlit space really does come back with
        // lights, and every one of them is vanilla.
        BuildPlan dark = plan("""
            {"id":"gt_dark","name":"Dark","shapes":[
              {"kind":"box","width":12,"height":8,"depth":12,
               "block":"minecraft:stone_bricks","hollow":true}]}
            """);
        List<BuildShape> lights = BuildLighting.lightsFor(dark);
        assertFalse(lights.isEmpty(),
                "the fixture must actually trigger the lighting pass, or this test proves nothing "
                        + "about the blocks it injects");
        for (BuildShape s : lights) {
            assertEquals(VANILLA, namespaceOf(s.block()),
                    "the builder injected a non-vanilla block: " + s.block());
        }
        System.out.println("[vanilla-buildings] lighting injected " + lights.size()
                + " shape(s), all " + BuildLighting.LIGHT_BLOCK);
    }

    /**
     * <b>The Gate B fixture really does trigger the lighting backstop — asserted HERE, where it
     * costs milliseconds.</b>
     *
     * <p>{@code VanillaBuildGameTest}'s census is only a three-source measurement if the building it
     * scans contains a block the agent never named. That precondition is a property of the fixture,
     * not of the code under test, so proving it in a world is the wrong place: it turns a fixture
     * slip into a red Gate B whose message is about lighting and whose subject is namespaces.
     *
     * <p>It has already caught one. A 9×6×9 hall with the same doorway produces <b>zero</b>
     * lanterns — {@code BuildLighting.enclosed} rejects every sampled spot on the doorway's own ray,
     * and a small room has nothing else to sample — so the first version of the world census was
     * silently down to two sources and said so only after a fourteen-minute Gate B lap.
     */
    @Test
    @DisplayName("the Gate B fixture triggers the lighting backstop")
    void theSharedFixtureReallyTriggersTheLightingBackstop() {
        BuildPlan fixture = plan(
                com.moddingfromamod.builder.VanillaBuildGameTest.hut("probe", "minecraft:stone_bricks"));
        List<BuildShape> lights = BuildLighting.lightsFor(fixture);
        assertTrue(lights.size() >= 2,
                "the shared fixture must hang at least a couple of lanterns, or the world census in "
                        + "VanillaBuildGameTest covers only the blocks the agent named. Got "
                        + lights.size() + ". A 9x6x9 hall with a doorway gives 0.");
        for (BuildShape s : lights) {
            assertEquals(VANILLA, namespaceOf(s.block()), "injected " + s.block());
        }
        System.out.println("[vanilla-buildings] shared Gate B fixture hangs " + lights.size()
                + " lantern(s), all vanilla");
    }

    /**
     * <b>The fallback for an id that does not resolve is vanilla stone.</b>
     *
     * <p>The third door, and the least obvious: on a server where a named mod is absent,
     * {@code Builder.submit} substitutes {@code Blocks.STONE} and tells the agent. That is what
     * makes a build robust rather than full of holes — and it means a plan naming
     * {@code create:cogwheel} on a Create-less server produces a stone building rather than an
     * unrenderable one. Asserted from the constant rather than from the sentence, so a change to
     * either is caught.
     */
    @Test
    @DisplayName("an unresolvable block id falls back to vanilla stone")
    void theFallbackForAnUnknownBlockIsVanilla() throws IOException {
        String src = Files.readString(
                Path.of("src/main/java/com/moddingfromamod/builder/Builder.java"),
                StandardCharsets.UTF_8);
        assertTrue(src.contains("Blocks.STONE.defaultBlockState()"),
                "Builder must substitute a vanilla block for an id it cannot resolve; if this "
                        + "moved, the vanilla-compat claim in docs/VANILLA_BUILDING_SPIKE_RESULTS.md "
                        + "needs re-measuring, because the fallback is what a modless server does "
                        + "with a modded id");
        assertTrue(src.contains("I didn't recognise"),
                "the substitution must stay LOUD to the agent — a silent one would let a building "
                        + "come out as a stone lump with nobody told why");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // THE CONTRACT — §2 rule 1: enumerate from the schema, not from what exists.
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>Nothing the agent reads about buildings names a non-vanilla block.</b>
     *
     * <p>The agent-facing contract for this wish class is four tool descriptions, four schemas and
     * {@code lessons/building.md}. If any of them named a mod block the agent would have been
     * TAUGHT to reach outside vanilla, and every census of its past output would be measuring a
     * habit rather than a boundary.
     *
     * <p>Sibling of {@code SourceRuleSweepTest.theAgentContractNeverNamesAPath}, and deliberately a
     * different question: that one asks whether the contract names a compat PATH, this one whether
     * it names a non-vanilla BLOCK. Buildings add no entry to that test's banned list — there is
     * only one route to name — which is a fact about this class and not an omission.
     */
    @Test
    @DisplayName("the building contract names only vanilla blocks")
    void theBuildingContractNamesOnlyVanillaBlocks() throws IOException {
        StringBuilder contract = new StringBuilder();
        for (var tool : List.of(new BuildStructureTool(), new ListBuildsTool(),
                new InspectBuildTool(), new RemoveBuildTool())) {
            contract.append(tool.description()).append('\n')
                    .append(tool.inputSchema().toString()).append('\n');
        }
        contract.append(Files.readString(Path.of("src/main/resources/lessons/building.md"),
                StandardCharsets.UTF_8));

        // A block id as the agent would write one. Deliberately anchored on a word boundary and
        // restricted to id characters, so a URL or a sentence with a colon in it is not a hit.
        Pattern id = Pattern.compile("\\b([a-z][a-z0-9_]*):([a-z][a-z0-9_/]*)\\b");
        Matcher m = id.matcher(contract.toString().toLowerCase(Locale.ROOT));
        Set<String> foreign = new TreeSet<>();
        String text = contract.toString().toLowerCase(Locale.ROOT);
        while (m.find()) {
            // `at castle:throne` / `wishspot castle:roof` is a SPOT of a building
            // ("<building id>:<spot>"), not a block — the one other thing written with a colon.
            String before = text.substring(Math.max(0, m.start() - 9), m.start());
            boolean spot = before.endsWith("at ") || before.endsWith("at:\"") || before.endsWith("at: \"")
                    || before.endsWith("wishspot ");
            if (!VANILLA.equals(m.group(1)) && !spot) {
                foreign.add(m.group(0));
            }
        }
        assertTrue(foreign.isEmpty(),
                "the building contract names a non-vanilla id, so the agent is being taught to "
                        + "reach outside what a stock client can render: " + foreign);
    }

    /**
     * <b>...and the contract does not CONSTRAIN the namespace either — which is the honest finding,
     * not a defect to fix here.</b>
     *
     * <p>The {@code block} field is documented as "any block id" and {@code Builder} resolves any
     * key in {@code BuiltInRegistries.BLOCK}. So "no build has ever contained a mod block" is a
     * measurement, and "no build can" is not true. Pinned as an assertion rather than left as a
     * sentence in a doc, so that if someone later narrows the field to a vanilla-only enum this test
     * fails and the inventory row is re-read rather than silently strengthening.
     *
     * <p>⚠ Do not "fix" this by narrowing the schema. §0.5: the MCP contract may not diverge by
     * path, and there is only one contract — narrowing it would take a legitimate affordance away
     * from a modded server to serve a stock client, which is a product decision and not a spike's.
     */
    @Test
    @DisplayName("the block field is unconstrained, and that is recorded rather than assumed")
    void theContractDoesNotConstrainTheBlockNamespace() {
        String shapes = new BuildStructureTool().inputSchema()
                .getAsJsonObject("properties").getAsJsonObject("shapes")
                .get("description").getAsString();
        assertTrue(shapes.contains("any block id"),
                "build_structure's 'shapes' description no longer says the block field takes any "
                        + "block id. If it was narrowed on purpose, the buildings parity inventory "
                        + "(docs/VANILLA_BUILDING_SPIKE_RESULTS.md) has a row that just got "
                        + "stronger and should be updated; if by accident, this is the regression.");
        assertFalse(shapes.contains("enum"),
                "the block field is free text by design — an enum here would be a contract change");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // THE PAYLOAD BOUNDARY — the surface the Lite spike measured at 0 of 9 for a stock client.
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>Nothing on the build path sends a mod payload.</b>
     *
     * <p>This is the row that decides the classification, and it is asserted at the <b>call site</b>
     * rather than through behaviour on purpose. {@code VANILLA_LITE_SPIKE_RESULTS.md} measured
     * <b>0 of 9</b> clientbound payloads reaching a genuinely stock client, so any affordance built
     * on one is not degraded there, it is absent. The question "does buildings use one" is a
     * question about source, and a source rule answers it exactly, for every file, on every commit
     * — where a behavioural test could only ever cover the paths it happened to drive.
     *
     * <p><b>{@code FinishBuildingTool} is deliberately NOT swept, and that is the seam this spike
     * does not close.</b> It reaches {@code PhotographTool}, which is payload-backed
     * ({@code PhotoPayload} via {@code Send.to}) — so the picture of a building is a
     * surface,
     * not a buildings one, exactly as an earlier issue's coverage map assigns it. Naming the exclusion here
     * rather than quietly widening the glob is what stops a later reader believing this rule
     * covered the reveal.
     */
    @Test
    @DisplayName("the build path sends no mod payload")
    void theBuildPathSendsNoModPayload() throws IOException {
        List<Path> swept = new ArrayList<>();
        try (var walk = SourceRoots.walk(Path.of("src/main/java/com/moddingfromamod/builder"))) {
            walk.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".java"))
                    // A GameTest may legitimately reach for mod content; the SHIPPED path may not.
                    .filter(f -> !f.getFileName().toString().contains("GameTest"))
                    .forEach(swept::add);
        }
        for (String tool : List.of("BuildStructureTool", "ListBuildsTool", "InspectBuildTool",
                "RemoveBuildTool")) {
            swept.add(Path.of("src/main/java/com/moddingfromamod/agent/tools/" + tool + ".java"));
        }
        assertTrue(swept.size() >= 10,
                "the sweep found only " + swept.size() + " files, which is too few to be the build "
                        + "path — a green run would be measuring an empty glob");

        List<String> hits = new ArrayList<>();
        for (Path f : swept) {
            String src = Files.readString(f, StandardCharsets.UTF_8);
            if (src.contains("moddingfromamod.net") || src.contains("Send.to(")
                    || src.contains("PacketDistributor")) {
                hits.add(f.getFileName().toString());
            }
        }
        assertTrue(hits.isEmpty(),
                "a mod payload on the build path would be ABSENT rather than degraded on a stock "
                        + "client (0 of 9 deliverable, VANILLA_LITE_SPIKE_RESULTS.md), so a "
                        + "building would stop being path 1 the moment one of these sent one: "
                        + hits + ". If the payload is genuinely additive, send it beside content "
                        + "every client already gets and say so here.");
        System.out.println("[vanilla-buildings] payload sweep: " + swept.size()
                + " build-path files, 0 payload sites (finish_building excluded — #463's seam)");
    }
}
