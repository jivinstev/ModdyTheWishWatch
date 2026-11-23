package com.moddingfromamod.agent.tools;

import net.minecraft.network.chat.Component;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.datapack.DatapackStore;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Do {@code verify_datapack}'s two extra warnings actually reach the agent?</b>
 *
 * <p>{@code CounterfactualProbeTest} proves the time probe is right and
 * {@code SelectorTags} has its own tests; neither can tell you the tool ever consults them. That is
 * the rule this repo has already paid for twice — an earlier issue's melter gate was green for months while
 * hand-dropping past the tool the agent calls, and an earlier issue's note rule had 14 green unit tests before
 * the first one that drove the real {@code finish_wish} went red immediately.
 *
 * <p><b>The dead-selector warning had NO wiring test of any kind</b> when the time probe was added
 * beside it — it could have been deleted with the whole suite green. Both are covered here rather
 * than copying that gap.
 *
 * <p><b>Why the addenda and not {@code call()}.</b> Both are assembled inside
 * {@code AgentBridge.onServer}, and a GameTest body already IS the server thread, so the real
 * {@code call()} hits the deadlock guard and throws before returning anything to assert on. What is
 * driven here is everything after the tool has a {@code feature_id}: the store lookup, the source
 * read and the warning — on a real server, with a real pack on disk. The one link left is the
 * concatenation, and {@link #bothAddendaAreConcatenatedIntoTheVerdict} reads the source for it,
 * which is the same technique {@code AgentKnowsTheTrapsTest} uses because there is no other way to
 * check what the agent is told.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class VerifyDatapackAddendaGameTest {

    /** Stage a pack on disk exactly as {@code author_datapack} leaves one. */
    private static boolean stage(GameTestHelper helper, String id, String source) {
        try {
            java.nio.file.Files.createDirectories(DatapackStore.featureDir(id));
            java.nio.file.Files.writeString(DatapackStore.sourceFor(id), source);
            return true;
        } catch (java.io.IOException e) {
            helper.fail(Component.literal("could not stage the pack: " + e));
            return false;
        }
    }

    /**
     * <b>A function Minecraft refused is named, with Minecraft's own reason</b> (the phoenix).
     * With no {@code entries} the verifier used to compare the server against ITSELF, so a pack
     * whose rebirth function never loaded read as WORKING. A line nothing can auto-fix (an item that
     * does not exist) must come back as MISSING, carrying the parse error and its line.
     */
    @GameTest(template = "empty_test", batch = "verifyAddenda")
    public static void aFunctionMinecraftRefusedIsNamedWithItsReason(GameTestHelper helper) {
        String id = "gt_refused_" + helper.getLevel().getGameTime();
        try {
            java.nio.file.Files.createDirectories(DatapackStore.featureDir(id));
            try (var zip = new java.util.zip.ZipOutputStream(
                    java.nio.file.Files.newOutputStream(DatapackStore.zipFor(id)))) {
                zip.putNextEntry(new java.util.zip.ZipEntry("data/" + id + "/function/fine.mcfunction"));
                zip.write("say hello\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.putNextEntry(new java.util.zip.ZipEntry("data/" + id + "/function/broken.mcfunction"));
                zip.write("say first\ngive @s minecraft:not_an_item_gt\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (java.io.IOException e) {
            helper.fail(Component.literal("could not stage the pack: " + e));
            return;
        }
        var report = com.moddingfromamod.datapack.DatapackVerifier.verify(
                helper.getLevel().getServer(), id, java.util.List.of(), false);
        String missing = String.join(" | ", report.missing());
        helper.assertTrue(report.missing().size() == 2,
                Component.literal("a pack the server never loaded declares BOTH its functions and has neither: " + missing));
        helper.assertTrue(missing.contains(id + ":broken — ") && missing.contains("line 2"),
                Component.literal("the refused function does not carry Minecraft's own reason and line: " + missing));
        helper.assertFalse(missing.contains(id + ":fine — "),
                Component.literal("a function that parses was given a reason it was refused: " + missing));
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = "verifyAddenda")
    public static void aTimeGatedPackIsToldToCheckTheOtherHalfOfTheClock(GameTestHelper helper) {
        String id = "gt_clock_" + helper.getLevel().getGameTime();
        // Verbatim the gate from the script that leaked: cod at night as asked, and again
        // at midday, with the source reading correctly and the cell scoring 1.000.
        if (!stage(helper, id, "clock tick\n"
                + "    /execute store result score sky codsky run time query daytime\n"
                + "    /execute if score sky codsky matches 13000..22999 run function "
                + "cod_rain:downpour\n"
                + "end\n")) {
            return;
        }
        String said = VerifyDatapackTool.counterfactual(id);
        helper.assertFalse(said.isBlank(),
                Component.literal("verify_datapack said nothing about the clock on the very pack that leaked. The "
                + "agent then tells the child it works, and it also rains cod at lunchtime (#167)."));
        helper.assertTrue(said.contains("/time set noon"),
                Component.literal("the warning carries no command to run — 'go and check' with nothing to run is the "
                + "guidance that was already measured and reverted: " + said));
        helper.assertTrue(said.contains("/time set midnight"),
                Component.literal("nothing puts the clock back, so the check leaves his world in the wrong state: "
                + said));
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = "verifyAddenda")
    public static void anOrdinaryPackIsNotSentOnAWildGooseChase(GameTestHelper helper) {
        String id = "gt_noclock_" + helper.getLevel().getGameTime();
        // A warning that fires on everything gets ignored, and then it is not there for the one
        // pack that needed it. This program compares a counter against a range on every tick and
        // has nothing to do with the clock.
        if (!stage(helper, id, "reset\n    /scoreboard objectives add c dummy\nend\n"
                + "clock tick\n    /scoreboard players add #tick c 1\n"
                + "    /execute if score #tick c matches 300.. run say ping\n"
                + "    /execute if score #tick c matches 300.. run scoreboard players set "
                + "#tick c 0\nend\n")) {
            return;
        }
        helper.assertTrue(VerifyDatapackTool.counterfactual(id).isBlank(),
                Component.literal("a plain tick counter was reported as a time gate: "
                + VerifyDatapackTool.counterfactual(id)));
        // A pack that does not exist must be quiet rather than throw — the verdict stands on its
        // own and these are extras, not gates.
        helper.assertTrue(VerifyDatapackTool.counterfactual("gt_no_such_pack_at_all").isBlank(),
                Component.literal("a missing pack produced a clock warning instead of nothing"));
        helper.assertTrue(VerifyDatapackTool.deadSelectors("gt_no_such_pack_at_all").isBlank(),
                Component.literal("a missing pack produced a selector warning instead of nothing"));
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = "verifyAddenda")
    public static void aPackWatchingATagNobodyWearsIsSaidToSelectNobody(GameTestHelper helper) {
        String id = "gt_deadsel_" + helper.getLevel().getGameTime();
        // The other addendum, which had no wiring test at all. A selector matching nothing is a
        // SUCCESSFUL no-op, so this pack passes every other check here and is inert for ever.
        if (!stage(helper, id, "clock tick\n"
                + "    /execute as @e[tag=gtnobodywearsthis] at @s run "
                + "effect give @s minecraft:glowing 3 0\n"
                + "end\n")) {
            return;
        }
        String said = VerifyDatapackTool.deadSelectors(id);
        helper.assertFalse(said.isBlank(),
                Component.literal("nothing in the world wears 'gtnobodywearsthis' and the pack never applies it, so "
                + "this pack can never fire — and the agent was told nothing"));
        helper.assertTrue(said.contains("gtnobodywearsthis"),
                Component.literal("…and it must name the tag, or the agent cannot fix it: " + said));
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = "verifyAddenda")
    public static void aTickSelectorWithNoTagIsSaidToCheckEveryEntity(GameTestHelper helper) {
        // the tag index answers @e[tag=…]; an @e with no tag and no position still walks every
        // entity in the world each time it runs, and a creature is dozens of them.
        String id = "gt_costsel_" + helper.getLevel().getGameTime();
        if (!stage(helper, id, "clock tick\n"
                + "    /execute as @e[scores={gtcostsel=1..}] run scoreboard players remove @s gtcostsel 1\n"
                + "    /execute as @e[tag=gtcostseltagged] run say fine\n"
                + "end\n")) {
            return;
        }
        String said = VerifyDatapackTool.costlySelectors(id);
        helper.assertTrue(said.contains("@e[scores={gtcostsel=1..}]"),
                Component.literal("a tick selector with no tag and no position was not named: " + said));
        helper.assertFalse(said.contains("gtcostseltagged"),
                Component.literal("a TAGGED selector was flagged as checking every entity: " + said));
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = "verifyAddenda")
    public static void bothAddendaAreConcatenatedIntoTheVerdict(GameTestHelper helper) {
        // The one link the tests above cannot reach: whether the report the agent actually receives
        // carries them. Read from source, the same way AgentKnowsTheTrapsTest reads a tool schema —
        // there is no other way to check what the agent is told.
        java.nio.file.Path src = java.nio.file.Path.of(
                "src/main/java/com/moddingfromamod/agent/tools/VerifyDatapackTool.java");
        if (!java.nio.file.Files.isRegularFile(src)) {
            helper.succeed();   // running from a jar rather than a checkout; nothing to read
            return;
        }
        String toolSource;
        try {
            toolSource = java.nio.file.Files.readString(src);
        } catch (java.io.IOException e) {
            helper.fail(Component.literal("could not read VerifyDatapackTool.java: " + e));
            return;
        }
        int verdict = toolSource.indexOf("report.describe()");
        helper.assertTrue(verdict >= 0, Component.literal("verify_datapack no longer builds a verdict from a report"));
        String tail = toolSource.substring(
                verdict, Math.min(toolSource.length(), verdict + 400));
        helper.assertTrue(tail.contains("deadSelectors(") && tail.contains("counterfactual(")
                        && tail.contains("costlySelectors("),
                Component.literal("an addendum is computed but never appended to the verdict, so it reaches nobody "
                + "while its own tests stay green: " + tail));
        helper.succeed();
    }
}
