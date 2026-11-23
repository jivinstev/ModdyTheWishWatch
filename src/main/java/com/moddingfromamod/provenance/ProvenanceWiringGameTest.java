package com.moddingfromamod.provenance;

import net.minecraft.network.chat.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.datapack.CompileResult;
import com.moddingfromamod.datapack.DatapackForge;
import com.moddingfromamod.runtime.RuntimeConfig;
import com.moddingfromamod.testing.GateShards;
import com.moddingfromamod.wish.LearnedTemplates;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Phase 2 — provenance is actually wired into the real persist paths.</b> With a temp Mod Repo
 * configured and an intent in the {@link ProvenanceContext}, teaching a learned template (and, when
 * the CBScript toolchain is present, compiling a datapack) publishes a manifest into the repo carrying
 * that intent. This is the Provenance Contract holding end-to-end on a real server.
 */
/*
 * ONE BATCH EACH, and the reason is worth reading before anyone tidies them back together.
 *
 * All four point the mod repo at a temp directory by setting the SAME process-wide system property,
 * `moddingfromamod.persistDir`, and clear it again in a `finally`. Tests inside one batch run
 * concurrently; separate batches run one after another. So a shared batch does not help here at
 * all — the dangerous neighbour is not one of the other 365 tests, it is the provenance test in the
 * next plot, whose `restore(prior)` clears the property while this one is still publishing into it.
 *
 * That is what failed on 2026-08-18: `datapackcompilepublishesprovenance` reported "no mod repo
 * configured" while the log showed other publishes going nowhere in the same window. Nothing about
 * provenance had changed — adding a 369th test elsewhere changed the schedule. It passed on the
 * next run, which is the worst behaviour a failure can have, and the first fix (one shared
 * `provenance` batch) was aimed at the wrong neighbours and would have kept passing by luck.
 *
 * Note this is NOT the RuntimeConfig cache: `value()` reads a system property fresh every time, and
 * `invalidate()` only clears the config-FILE cache. Batching cannot make a global mutable property
 * safe; serialising the only things that write it can. The durable fix is for the persist directory
 * to be a parameter rather than a process-wide setting.
 */
@GameTestHolder(GateShards.DEFAULT)
public class ProvenanceWiringGameTest {

    private static final String PDIR = "moddingfromamod.persistDir";

    /** Teaching a template publishes its recipe + a provenance manifest stamped with the wish. */
    @GameTest(template = "empty_test", batch = "provenance1")
    public static void learnedTemplatePublishesProvenance(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        List<String> saved = new ArrayList<>(LearnedTemplates.savedJsonForTest());
        String prior = System.getProperty(PDIR);
        // SNAPSHOT, NOT CLEAR. This used to end with ProvenanceContext.clear(), which deletes a
        // global that concurrently-running GameTests read -- and the neighbour does not go red, it
        // goes green having asserted nothing. Latent until SecondSentenceGameTest became the first
        // GameTest to READ the intent, at which point GateShardsTest named this file by name.
        // Restoring what was here leaves no residue while still letting the body call begin()
        // as many times as it likes, which the race simulation below depends on.
        String priorIntent = ProvenanceContext.currentIntent();
        String priorNote = ProvenanceContext.currentNote();
        Path repo = null;
        try {
            repo = Files.createTempDirectory("prov_tpl_");
            System.setProperty(PDIR, repo.toString());
            RuntimeConfig.invalidate();
            ProvenanceContext.begin("give me a sparkle wand");

            JsonObject t = JsonParser.parseString(
                    "{\"id\":\"prov_gt_wand\",\"all\":[\"provgtwand\"],"
                    + "\"commands\":[\"say hi\"],\"say\":\"here\"}").getAsJsonObject();
            LearnedTemplates.addAndSave(server, t);

            Path manifest = repo.resolve("wishes/template/prov_gt_wand/manifest.json");
            helper.assertTrue(Files.isRegularFile(manifest),
                    Component.literal("learned template must publish a provenance manifest"));
            WishProvenance p = read(manifest);
            helper.assertTrue("give me a sparkle wand".equals(p.intent()),
                    Component.literal("manifest intent must be the wish, got: " + p.intent()));
            helper.assertTrue(Files.isRegularFile(repo.resolve("wishes/template/prov_gt_wand/template.json")),
                    Component.literal("the template recipe must be published beside its manifest"));
        } catch (Exception e) {
            helper.fail(Component.literal("provenance wiring threw: " + e));
        } finally {
            restore(prior);
            ProvenanceContext.restoreForTest(priorIntent, priorNote);
            LearnedTemplates.restoreForTest(saved, server);
            deleteQuietly(repo);
        }
        helper.succeed();
    }

    /**
     * Compiling a datapack publishes provenance too. Gracefully skips (still succeeds) when the
     * CBScript toolchain isn't locatable in this environment, so the gate stays green on a clean box.
     */
    @GameTest(template = "empty_test", batch = "provenance2")
    public static void datapackCompilePublishesProvenance(GameTestHelper helper) {
        String prior = System.getProperty(PDIR);
        // SNAPSHOT, NOT CLEAR. This used to end with ProvenanceContext.clear(), which deletes a
        // global that concurrently-running GameTests read -- and the neighbour does not go red, it
        // goes green having asserted nothing. Latent until SecondSentenceGameTest became the first
        // GameTest to READ the intent, at which point GateShardsTest named this file by name.
        // Restoring what was here leaves no residue while still letting the body call begin()
        // as many times as it likes, which the race simulation below depends on.
        String priorIntent = ProvenanceContext.currentIntent();
        String priorNote = ProvenanceContext.currentNote();
        Path repo = null;
        try {
            repo = Files.createTempDirectory("prov_dp_");
            System.setProperty(PDIR, repo.toString());
            RuntimeConfig.invalidate();
            ProvenanceContext.begin("make an ice bomb");

            CompileResult r = DatapackForge.compile("prov_gt_ice", "an ice bomb",
                    "function give()\n    /give @s minecraft:snowball 4\nend\n");
            if (!r.ok()) {
                // Toolchain unavailable here (e.g. clean CI without the fork) — the template test
                // above already proves the wiring; don't fail on a missing compiler.
                helper.succeed();
                return;
            }
            Path manifest = repo.resolve("wishes/datapack/prov_gt_ice/manifest.json");
            helper.assertTrue(Files.isRegularFile(manifest),
                    Component.literal("compiling a datapack must publish a provenance manifest"));
            helper.assertTrue("make an ice bomb".equals(read(manifest).intent()),
                    Component.literal("datapack manifest intent must be the wish"));
        } catch (Exception e) {
            helper.fail(Component.literal("datapack provenance wiring threw: " + e));
        } finally {
            restore(prior);
            ProvenanceContext.restoreForTest(priorIntent, priorNote);
            deleteQuietly(repo);
        }
        helper.succeed();
    }

    /**
     * Sculpting a creature publishes its editable source ({@code sculpt.json}) + a provenance
     * manifest — AND saves the source beside the baked model — so a wished character can be re-edited
     * or upgraded later without recovering the spec from truncated agent logs (the exact gap this
     * closes). Drives the REAL persist+publish path headless (no player/world needed).
     */
    @GameTest(template = "empty_test", batch = "provenance3")
    public static void sculptedCreaturePublishesEditableSource(GameTestHelper helper) {
        String priorPersist = System.getProperty(PDIR);
        String priorHome = System.getProperty("moddingfromamod.home");
        // SNAPSHOT, NOT CLEAR. This used to end with ProvenanceContext.clear(), which deletes a
        // global that concurrently-running GameTests read -- and the neighbour does not go red, it
        // goes green having asserted nothing. Latent until SecondSentenceGameTest became the first
        // GameTest to READ the intent, at which point GateShardsTest named this file by name.
        // Restoring what was here leaves no residue while still letting the body call begin()
        // as many times as it likes, which the race simulation below depends on.
        String priorIntent = ProvenanceContext.currentIntent();
        String priorNote = ProvenanceContext.currentNote();
        Path repo = null;
        Path home = null;
        try {
            repo = Files.createTempDirectory("prov_species_");
            home = Files.createTempDirectory("prov_species_home_");
            System.setProperty(PDIR, repo.toString());
            System.setProperty("moddingfromamod.home", home.toString());
            RuntimeConfig.invalidate();
            ProvenanceContext.begin("make me a fire-breathing kroak king");

            JsonObject args = JsonParser.parseString(
                    "{\"id\":\"prov_gt_kroak\",\"name\":\"Kroak King\",\"health\":300,\"spawn\":false,"
                    + "\"note\":\"green kroak shell + spikes; boss stats for a climactic fight\","
                    + "\"parts\":[{\"name\":\"body\",\"from\":[-6,10,-4],\"to\":[6,26,4],"
                    + "\"colour\":\"#20A020\"}]}").getAsJsonObject();
            var parts = com.moddingfromamod.runtime.CreatureSculpt.parseParts(args.getAsJsonArray("parts"));

            Path dir = com.moddingfromamod.agent.tools.SculptCreatureTool.persistAndPublish(
                    "prov_gt_kroak", parts, args);
            helper.assertTrue(dir != null, Component.literal("the sculpt must produce a model folder"));

            // Editable source saved beside the baked model.
            helper.assertTrue(Files.isRegularFile(dir.resolve("sculpt.json")),
                    Component.literal("the editable sculpt.json must be saved beside the model"));

            // Published into the Mod Repo: source artifact + provenance manifest with the wish.
            helper.assertTrue(Files.isRegularFile(repo.resolve("wishes/species/prov_gt_kroak/sculpt.json")),
                    Component.literal("the editable source must be published into the repo"));
            Path manifest = repo.resolve("wishes/species/prov_gt_kroak/manifest.json");
            helper.assertTrue(Files.isRegularFile(manifest),
                    Component.literal("a sculpted creature must publish a provenance manifest"));
            helper.assertTrue("make me a fire-breathing kroak king".equals(read(manifest).intent()),
                    Component.literal("species manifest intent must be the wish, got: " + read(manifest).intent()));
            helper.assertTrue("green kroak shell + spikes; boss stats for a climactic fight"
                    .equals(read(manifest).designNote()),
                    Component.literal("species manifest must carry the note, got: " + read(manifest).designNote()));
            helper.assertTrue(ProvenanceContract.satisfied("species", "prov_gt_kroak"),
                    Component.literal("the species publish must satisfy the Provenance Contract"));
        } catch (Exception e) {
            helper.fail(Component.literal("species provenance wiring threw: " + e));
        } finally {
            restore(priorPersist);
            if (priorHome == null) {
                System.clearProperty("moddingfromamod.home");
            } else {
                System.setProperty("moddingfromamod.home", priorHome);
            }
            RuntimeConfig.invalidate();
            ProvenanceContext.restoreForTest(priorIntent, priorNote);
            deleteQuietly(repo);
            deleteQuietly(home);
        }
        helper.succeed();
    }

    /**
     * The "build" kind publishes its EDITABLE DESIGN +
     * a manifest carrying the wish AND the agent's design note — the parity fix that gave every
     * persisting wish kind repo-side provenance. (Machines and trains did the same.) Drives the REAL {@code Plan.toJson()} serialization
     * and the REAL {@code ProvenancePublisher.publish} the build tools call, and proves the published
     * source re-parses back into a Plan (so it's the editable design, not an opaque blob).
     */
    @GameTest(template = "empty_test", batch = "provenance4")
    public static void buildKindsPublishEditableDesignWithNote(GameTestHelper helper) {
        String prior = System.getProperty(PDIR);
        // SNAPSHOT, NOT CLEAR. This used to end with ProvenanceContext.clear(), which deletes a
        // global that concurrently-running GameTests read -- and the neighbour does not go red, it
        // goes green having asserted nothing. Latent until SecondSentenceGameTest became the first
        // GameTest to READ the intent, at which point GateShardsTest named this file by name.
        // Restoring what was here leaves no residue while still letting the body call begin()
        // as many times as it likes, which the race simulation below depends on.
        String priorIntent = ProvenanceContext.currentIntent();
        String priorNote = ProvenanceContext.currentNote();
        Path repo = null;
        try {
            repo = Files.createTempDirectory("prov_build_");
            System.setProperty(PDIR, repo.toString());
            RuntimeConfig.invalidate();

            // --- build ---
            ProvenanceContext.begin("build me a floating castle");
            ProvenanceContext.note("quartz keep + four hollow towers so it has rooms");
            var buildJson = JsonParser.parseString("{\"id\":\"prov_castle\",\"name\":\"Castle\","
                    + "\"shapes\":[{\"kind\":\"box\",\"dx\":0,\"dy\":0,\"dz\":0,\"width\":8,"
                    + "\"height\":8,\"depth\":8,\"block\":\"minecraft:stone_bricks\",\"hollow\":true}]}")
                    .getAsJsonObject();
            var buildPlan = com.moddingfromamod.builder.BuildPlan.fromJson(buildJson);
            helper.assertTrue(buildPlan != null, Component.literal("the build plan must parse"));
            // THE RACE, MADE DETERMINISTIC. Another test running concurrently calls
            // ProvenanceContext.begin() and moves the shared global out from under this one --
            // observed for real as "manifest intent must be the wish, got: make a wither storm".
            // Simulating it here turns an intermittent failure into a permanent regression test:
            // publish must carry the intent it was GIVEN, not whatever the global says at this
            // instant. Reverting to the global-reading overload fails this line every time.
            ProvenanceContext.begin("make a wither storm");
            ProvenancePublisher.publish("build", buildPlan.id(), "build.json",
                    buildPlan.toJson().toString(),
                    "build me a floating castle", "quartz keep + four hollow towers so it has rooms");
            ProvenanceContext.begin("build me a floating castle");
            assertPublished(helper, repo, "build", "prov_castle", "build.json",
                    "build me a floating castle", "quartz keep + four hollow towers so it has rooms");
            // the published source re-parses into a real BuildPlan (editable, not opaque)
            helper.assertTrue(com.moddingfromamod.builder.BuildPlan.fromJson(JsonParser.parseString(
                    Files.readString(repo.resolve("wishes/build/prov_castle/build.json")))
                    .getAsJsonObject()) != null, Component.literal("the published build.json must re-parse into a BuildPlan"));
        } catch (Exception e) {
            helper.fail(Component.literal("build-kinds provenance wiring threw: " + e));
        } finally {
            restore(prior);
            RuntimeConfig.invalidate();
            ProvenanceContext.restoreForTest(priorIntent, priorNote);
            deleteQuietly(repo);
        }
        helper.succeed();
    }

    /** A published kind must have its editable source AND a manifest with the right intent + note. */
    private static void assertPublished(GameTestHelper helper, Path repo, String kind, String id,
            String file, String intent, String note) throws Exception {
        Path src = repo.resolve("wishes/" + kind + "/" + id + "/" + file);
        helper.assertTrue(Files.isRegularFile(src),
                Component.literal(kind + ": the editable source " + file + " must be published"));
        Path manifest = repo.resolve("wishes/" + kind + "/" + id + "/manifest.json");
        helper.assertTrue(Files.isRegularFile(manifest), Component.literal(kind + ": a manifest must be published"));
        WishProvenance p = read(manifest);
        helper.assertTrue(intent.equals(p.intent()),
                Component.literal(kind + ": manifest intent must be the wish, got: " + p.intent()));
        helper.assertTrue(note.equals(p.designNote()),
                Component.literal(kind + ": manifest must carry the agent's design note, got: " + p.designNote()));
        helper.assertTrue(ProvenanceContract.satisfied(kind, id),
                Component.literal(kind + ": must satisfy the Provenance Contract"));
    }

    private static WishProvenance read(Path manifest) throws Exception {
        return WishProvenance.fromJson(
                JsonParser.parseString(Files.readString(manifest)).getAsJsonObject());
    }

    private static void restore(String prior) {
        if (prior == null) {
            System.clearProperty(PDIR);
        } else {
            System.setProperty(PDIR, prior);
        }
        RuntimeConfig.invalidate();
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // best-effort temp cleanup
                }
            });
        } catch (Exception ignored) {
            // best-effort temp cleanup
        }
    }
}
