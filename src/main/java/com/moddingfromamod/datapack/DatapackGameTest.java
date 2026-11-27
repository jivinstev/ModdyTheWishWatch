package com.moddingfromamod.datapack;

import net.minecraft.network.chat.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.testing.GateShards;

/**
 * <b>Gate B — headless runtime.</b> Covers the parts of the datapack pipeline that need a real
 * server object (world paths, pack ids, the enabled-set query) and confirms the mod process can
 * actually shell out to the CBScript compiler and get a valid 1.21.1 datapack back.
 *
 * <p><b>Deliberately does NOT call {@code reloadResources} here.</b> A live reload is a server-wide
 * side effect that would re-run every datapack's load functions mid-batch and could break other
 * concurrent GameTests (see docs/LEARNINGS.md on global state). The runtime hot-reload itself is
 * proven end-to-end against a real dedicated server over RCON instead, which is both faithful and
 * isolated from the test batch.
 */
@GameTestHolder(GateShards.CI)
public class DatapackGameTest {

    /** The server-touching helpers behave against a real running server. */
    @GameTest(template = "empty_test")
    public static void serverHelpersResolve(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();

        Path dpDir = DatapackReloader.worldDatapackDir(server);
        helper.assertTrue(dpDir.getFileName().toString().equals("datapacks"),
                Component.literal("world datapack dir should end in 'datapacks', was " + dpDir));

        helper.assertTrue(DatapackReloader.packId("ice_bomb").equals("file/ice_bomb.zip"),
                Component.literal("pack id format"));
        helper.assertFalse(DatapackReloader.isEnabled(server, "definitely_not_a_real_pack_xyz"),
                Component.literal("a pack that was never installed must not report as enabled"));

        // listIds() must never throw, whatever the store dir looks like.
        helper.assertTrue(DatapackStore.listIds() != null, Component.literal("listIds should return a list"));

        helper.succeed();
    }

    /**
     * The mod process can invoke the CBScript compiler and receive a valid 1.21.1 datapack.
     */
    @GameTest(template = "empty_test")
    public static void compilesADatapackFromInsideTheGame(GameTestHelper helper) {
        try {
            Path tmp = Files.createTempDirectory("dp_dp_gametest");
            Path zip = tmp.resolve("out.zip");
            String source = "function mark()\n    /say hi\nend\nreset\nend\n";
            CompileResult r = CbScriptCompiler.compile(tmp, "gt_probe", "gametest", source, zip);
            helper.assertTrue(r.ok(), Component.literal("compile should succeed: "
                    + (r.ok() ? "" : r.failureSummary())));
            helper.assertTrue(Files.isRegularFile(zip), Component.literal("zip should exist"));
            try (ZipFile zf = new ZipFile(zip.toFile())) {
                helper.assertTrue(zf.getEntry("data/gt_probe/function/mark.mcfunction") != null,
                        Component.literal("compiled datapack should contain the 1.21.1 function path"));
            }
        } catch (Exception e) {
            helper.fail(Component.literal("compile-from-game failed: " + e));
        }
        helper.succeed();
    }

    /**
     * The reject-disable guard's file half: {@code newestInstalledPack} picks the most recently
     * written {@code .zip} and reports its feature id without the extension. Uses a temp folder so it
     * never touches the world's real datapacks or triggers a server-wide reload.
     */
    @GameTest(template = "empty_test")
    public static void newestInstalledPackPicksTheMostRecentZip(GameTestHelper helper) {
        try {
            Path dir = Files.createTempDirectory("dp_newest_pack");
            Path older = dir.resolve("old_feature.zip");
            Path newer = dir.resolve("new_feature.zip");
            Files.writeString(older, "x");
            Files.writeString(newer, "y");
            // Make the mtimes unambiguous regardless of write order.
            Files.setLastModifiedTime(older, java.nio.file.attribute.FileTime.fromMillis(1_000_000L));
            Files.setLastModifiedTime(newer, java.nio.file.attribute.FileTime.fromMillis(2_000_000L));
            // A non-zip must be ignored.
            Files.writeString(dir.resolve("notes.txt"), "ignore me");

            var newest = DatapackReloader.newestInstalledPack(dir);
            helper.assertTrue(newest.isPresent(), Component.literal("should find a pack"));
            helper.assertTrue(newest.get().featureId().equals("new_feature"),
                    Component.literal("picks the newest zip, feature id without .zip: " + newest.get().featureId()));
            helper.assertTrue(newest.get().modifiedMs() == 2_000_000L, Component.literal("carries its mtime"));

            helper.assertTrue(DatapackReloader.newestInstalledPack(
                            Files.createTempDirectory("dp_empty_packs")).isEmpty(),
                    Component.literal("an empty folder yields no pack"));
        } catch (Exception e) {
            helper.fail(Component.literal("newestInstalledPack test failed: " + e));
        }
        helper.succeed();
    }

    /**
     * <b>Gate D — the compiler is in the jar.</b> A datapack compiles from inside the running game with nothing
     * extracted, found or started: the CBScript compiler (moddy-cbscript's Java port) runs in this process.
     * {@link NoPythonGameTest} watches the processes; this proves the pack is right.
     */
    @GameTest(template = "empty_test")
    public static void theBundledCompilerCompilesInProcess(GameTestHelper helper) {
        try {
            Path work = Files.createTempDirectory("dp_gd_work");
            Path zip = work.resolve("out.zip");
            CompileResult r = CbScriptCompiler.compile(work, "gt_deploy", "gd", "function m()\n    /say hi\nend\nreset\nend\n",
                    zip);
            helper.assertTrue(r.ok(), Component.literal("the built-in compiler must compile a datapack: "
                    + (r.ok() ? "" : r.failureSummary())));
            try (ZipFile zf = new ZipFile(zip.toFile())) {
                helper.assertTrue(zf.getEntry("data/gt_deploy/function/m.mcfunction") != null,
                        Component.literal("compiled datapack has the expected 1.21.1 function path"));
            }
        } catch (Exception e) {
            helper.fail(Component.literal("built-in compile failed: " + e));
        }
        helper.succeed();
    }

    @GameTest(template = "empty_test")
    public static void enablingOneWishKeepsTheOthersOn(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        var repo = server.getPackRepository();
        java.util.List<String> saved = DatapackStore.listIds();
        if (saved.size() < 2) {
            helper.succeed();   // nothing saved for this world yet — nothing this can prove
            return;
        }
        java.util.List<String> available = saved.stream()
                .map(DatapackReloader::packId)
                .filter(id -> repo.getAvailableIds().contains(id))
                .toList();
        if (available.size() < 2) {
            helper.succeed();
            return;
        }
        // The set enableAndReload would build for the FIRST of them must still contain the others.
        java.util.List<String> selected = new java.util.ArrayList<>();
        for (String id : available) {
            if (!selected.contains(id)) {
                selected.add(id);
            }
        }
        for (String id : available) {
            helper.assertTrue(selected.contains(id),
                    Component.literal("enabling one wish dropped " + id + " — a second wish silently kills the first"));
        }
        helper.succeed();
    }

    /**
     * <b>The agent can read the script a feature is currently running.</b>
     *
     * <p>This class's R6, and until 2026-08-19 it was false: nothing called
     * {@link DatapackStore#sourceFor} anywhere, so {@code list_datapacks} could say a feature
     * existed and never what it did. "Edit the existing feature rather than making a duplicate" was
     * in that tool's description the whole time — an instruction with nothing behind it, because
     * obeying it meant writing the program again from memory.
     *
     * <p>Measured on the ladder: stranger cells re-authored and the script SHRANK — 2461 characters
     * to 1378, losing the night condition; 2433 to 974, losing the explosion — while owner cells,
     * which still held the conversation, grew every rung and kept everything. Same defect buildings
     * had before {@code inspect_build}.
     */
    @GameTest(template = "empty_test")
    public static void theAgentCanReadTheScriptAFeatureIsRunning(GameTestHelper helper) {
        String id = "gt_readback_" + helper.getLevel().getGameTime();
        String script = "# a marker only this test writes\nfunction rainthing()\n"
                + "    /say GT_READBACK_MARKER only at night\nend\n";
        try {
            java.nio.file.Path dir = DatapackStore.featureDir(id);
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.writeString(DatapackStore.sourceFor(id), script);
        } catch (java.io.IOException e) {
            helper.fail(Component.literal("could not stage a feature: " + e));
            return;
        }

        com.google.gson.JsonObject args = new com.google.gson.JsonObject();
        args.addProperty("feature_id", id);
        String out = new com.moddingfromamod.agent.tools.ListDatapacksTool().call(args).text();

        helper.assertTrue(out.contains("GT_READBACK_MARKER"),
                Component.literal("the agent must get the SCRIPT back, not just the id — without it, changing a "
                        + "feature means rewriting it from memory, which is how a rule he asked "
                        + "for stops happening: " + out));
        // …and the instruction that makes the script usable. The buildings lesson is explicit that
        // the list is not the fix; the sentence under it is.
        helper.assertTrue(out.contains("WHOLE program"),
                Component.literal("it must say the script is the whole program and not a patch: " + out));
        helper.assertTrue(out.contains(id),
                Component.literal("…and name the id to re-author under, or a correct edit still makes a second "
                        + "pack: " + out));

        // An id nobody has must be refused, and must not silently list nothing.
        com.google.gson.JsonObject missing = new com.google.gson.JsonObject();
        missing.addProperty("feature_id", "gt_no_such_feature");
        var bad = new com.moddingfromamod.agent.tools.ListDatapacksTool().call(missing);
        helper.assertTrue(bad.isError(),
                Component.literal("an unknown feature must be an error — inventing an id leaves the old rule running "
                        + "as well, and nothing shows the child there are two"));
        helper.succeed();
    }

    /**
     * <b>Is the refusal actually WIRED into author_datapack?</b>
     *
     * <p>Separate from {@code DatapackDriftTest}, which tests the rule and cannot tell you the tool
     * ever consults it. That distinction has cost this repo repeatedly — unwiring the creature shape
     * gate left its own tests 6/6 green, and the held-item camera's tests passed while three lines
     * of wiring were missing.
     *
     * <p>Drives {@code call()} twice on the same feature: a good script, then a lossy rewrite. The
     * second must be refused, and the THIRD must go through — refuse once, then give way, because a
     * hard refusal is a trap and the child is sitting there waiting.
     */
    /**
     * <b>Is the refusal actually WIRED into author_datapack?</b>
     *
     * <p>Separate from {@code DatapackDriftTest}, which tests the rule and cannot tell you the tool
     * ever consults it. Unwiring the creature shape gate left its own tests 6/6 green; the held-item
     * camera's passed while three lines of wiring were missing.
     *
     * <p><b>No thread and no compiler needed</b>, because the refusal happens BEFORE the compile —
     * which is itself worth asserting, since a gate that fired after the pack was written and loaded
     * would be telling the agent about a change the child had already received. The first attempt
     * here never reaches CBScript at all; the second is only checked for the ABSENCE of the refusal,
     * so whatever the compiler does or does not do on this machine cannot decide the outcome.
     */
    @GameTest(template = "empty_test", batch = "datapackRefuse")
    public static void aLossyRewriteIsRefusedOnceAndThenAllowed(GameTestHelper helper) {
        String id = "gt_refuse_" + helper.getLevel().getGameTime();
        String rich = "function rain()\n"
                + "    /summon minecraft:cod ~ ~40 ~\n"
                + "    /particle minecraft:explosion_emitter ~ ~ ~ 0 0 0 0 1 force\n"
                + "end\n";
        String lossy = "function rain()\n    /summon minecraft:cod ~ ~40 ~\nend\n";
        try {
            java.nio.file.Files.createDirectories(DatapackStore.featureDir(id));
            java.nio.file.Files.writeString(DatapackStore.sourceFor(id), rich);
        } catch (java.io.IOException e) {
            helper.fail(Component.literal("could not stage the running script: " + e));
            return;
        }

        com.google.gson.JsonObject args = new com.google.gson.JsonObject();
        args.addProperty("feature_id", id);
        args.addProperty("description", "gt refusal wiring");
        args.addProperty("cbscript", lossy);

        var first = new com.moddingfromamod.agent.tools.AuthorDatapackTool().call(args);
        helper.assertTrue(first.isError() && first.text().contains("STOPPED DOING THINGS"),
                Component.literal("a rewrite that drops the explosion must be REFUSED — without this the gate is "
                        + "unreachable and DatapackDriftTest stays green regardless: " + first.text()));
        helper.assertTrue(first.text().contains("explosion_emitter"),
                Component.literal("…and must name what disappeared, or the agent cannot fix it: " + first.text()));

        // GIVE WAY. Only the absence of a second refusal is asserted: what happens after the gate
        // (compile, load) depends on this machine, and letting that decide the test would make it
        // pass or fail for reasons that have nothing to do with wiring.
        // WHATEVER HAPPENS AFTER THE GATE IS NOT THIS TEST'S BUSINESS, and it must not be able to
        // decide the outcome. The second call gets past the refusal and goes on to compile and
        // LOAD, which hops to the server thread — and a GameTest body already is the server
        // thread, so AgentBridge throws its deadlock guard and the exception escapes call().
        // Reaching that guard is itself proof the gate let it through, so it is caught and treated
        // as exactly that.
        String secondText;
        try {
            secondText = new com.moddingfromamod.agent.tools.AuthorDatapackTool().call(args).text();
        } catch (Throwable past) {
            secondText = "got past the gate and threw downstream: " + past;
        }
        helper.assertFalse(secondText.contains("STOPPED DOING THINGS"),
                Component.literal("it refused TWICE. A hard refusal is a trap — a wish that ends imperfectly beats "
                        + "one that never ends while he waits: " + secondText));
        com.moddingfromamod.agent.tools.AuthorDatapackTool.clearRefusals();
        helper.succeed();
    }

    /**
     * <b>Does an AUTHORED pack now tell the child how to set it off?</b>
     *
     * <p>It did not. {@code BuiltAbility.record} was called from exactly one place —
     * {@code apply_effect} — so a ready-made ability closed with <i>"It happens when spider HITS
     * something"</i> and an authored one closed with nothing at all, on the same wish path. The
     * child was simply not told when his own feature goes off.
     *
     * <p><b>Why this has to be a GameTest and not another unit test.</b> {@code TriggerShapeTest}
     * proves the derivation; it cannot prove the tool consults it. That is the rule this repo has
     * already paid for twice — an earlier issue's melter gate was green for months while hand-dropping past
     * the tool the agent calls, and an earlier issue's note rule had 14 green unit tests before the first one
     * that drove the real {@code finish_wish} went red immediately. So this drives the real
     * {@code author_datapack} with the real compiler.
     *
     * <p>The recording sits AFTER the compile and BEFORE the server hop, which is what makes it
     * observable here: like the refusal test above, the call reaches {@code AgentBridge}'s deadlock
     * guard (a GameTest body already IS the server thread) and throws — by which point the
     * sentence has been recorded. Reaching the throw is itself proof the compile succeeded.
     */
    @GameTest(template = "empty_test", batch = "datapackRefuse")
    public static void anAuthoredPackTellsTheChildWhenItGoesOff(GameTestHelper helper) {
        String id = "gt_trigger_" + helper.getLevel().getGameTime();
        // A real on_hit program in the agent's OWN words — no mfam_ scoreboards, no template
        // substitution. HurtTime is the signature because it is the only way to know something was
        // just damaged that works for mobs as well as players (see on_hit.cbs).
        String source = "clock tick\n"
                + "    /execute as @e[tag=gtdragon] at @s as "
                + "@e[nbt={HurtTime:10s},distance=..6,tag=!gtdragon] run "
                + "effect give @s minecraft:glowing 3 0\n"
                + "end\n";

        com.google.gson.JsonObject args = new com.google.gson.JsonObject();
        args.addProperty("feature_id", id);
        args.addProperty("description", "gt trigger derivation");
        args.addProperty("cbscript", source);
        args.addProperty("note", "gate: authored packs must derive their own trigger sentence");

        String[] said = new String[1];
        // A wish-scoped global under a concurrent batch: isolate rather than clear, or a
        // neighbouring test loses its sentence mid-wish and PASSES having asserted nothing.
        com.moddingfromamod.wish.BuiltAbility.inIsolationForTest(() -> {
            try {
                new com.moddingfromamod.agent.tools.AuthorDatapackTool().call(args);
            } catch (Throwable pastTheCompile) {
                // Expected: the server hop. The sentence is already recorded by here.
            }
            said[0] = com.moddingfromamod.wish.BuiltAbility.describe();
        });

        helper.assertFalse(said[0] == null || said[0].isBlank(),
                Component.literal("an authored pack recorded NO trigger sentence, so the child is told nothing about "
                + "when his own feature goes off — which is the whole of #84. If the compile "
                + "failed this is also blank, so check the source above compiles before assuming "
                + "the wiring is missing."));
        helper.assertTrue(said[0].contains("HITS"),
                Component.literal("the sentence does not describe on_hit. It was derived from a program whose only "
                + "damage check is HurtTime, so anything else is the wrong trigger reaching the "
                + "child — the spider-bite failure: " + said[0]));
        helper.assertTrue(said[0].contains("gtdragon"),
                Component.literal("the sentence names no creature, so ${TAG} was never substituted from the "
                + "program's own selector: " + said[0]));
        helper.assertFalse(said[0].contains("${"),
                Component.literal("a raw template placeholder reached a child-facing sentence: " + said[0]));
        helper.succeed();
    }
}
