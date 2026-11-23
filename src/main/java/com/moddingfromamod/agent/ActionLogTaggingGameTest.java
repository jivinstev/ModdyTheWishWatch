package com.moddingfromamod.agent;

import net.minecraft.network.chat.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.RunContext;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * Gate B for keeping the improvement loop's signal clean.
 *
 * <p>The gates write to the same action log the child's real play does, and for a long time the
 * report could not tell them apart — deliberate failure probes were being counted as real agent
 * failures, 13 of 17 in one report. The fix tags every record at the source, and this test is what
 * stops that tag silently regressing.
 *
 * <p>Like {@code AgentSessionGameTest}, it is running inside the exact situation it guards against,
 * so it can simply check its own output.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class ActionLogTaggingGameTest {

    /** A GameTest is a test run, and the log must say so on every record it writes. */
    @GameTest(template = "empty_test")
    public static void everythingWrittenDuringATestIsTaggedAsATest(GameTestHelper helper) {
        helper.assertTrue(RunContext.isTestRun(),
                Component.literal("a GameTest must be recognised as a test run, or its traffic pollutes the report"));

        String marker = "gametest-tagging-marker-" + helper.getLevel().getGameTime();
        ActionLog.wish(marker, "PENDING_ESCALATION", "COMMAND");

        JsonObject written = lastRecordMentioning(helper, marker);
        helper.assertTrue(written != null, Component.literal("the record should have been written to " + ActionLog.path()));
        helper.assertTrue(written.has("test"),
                Component.literal("every record needs a `test` tag or the report cannot separate play from gates"));
        helper.assertTrue(written.get("test").getAsBoolean(),
                Component.literal("a record written during a GameTest must be tagged test=true"));
        helper.succeed();
    }

    /** Tool calls are the noisy half — they must carry the tag too. */
    @GameTest(template = "empty_test")
    public static void toolCallsAreTaggedToo(GameTestHelper helper) {
        String marker = "tool-tagging-marker-" + helper.getLevel().getGameTime();
        ActionLog.toolCall(marker, new JsonObject(), ToolResult.ok("fine"), 1L);

        JsonObject written = lastRecordMentioning(helper, marker);
        helper.assertTrue(written != null, Component.literal("the tool call should have been logged"));
        helper.assertTrue(written.has("test") && written.get("test").getAsBoolean(),
                Component.literal("a tool call during a GameTest must be tagged test=true"));
        helper.succeed();
    }

    /**
     * <b>Every record says which world and which sitting it came from.</b>
     *
     * <p>A server is running, so this exercises the real path: the fields are written by
     * {@code ActionLog.stampLineage} off {@code WorldPaths}, not handed in by the test.
     *
     * <p>Guards the fix, where seven sculpts of Spikor were read as seven successive
     * revisions that got worse. They were four independent creations in four worlds — flat within
     * each, changing only at the boundaries — and the agent could never have iterated on the
     * earlier ones, because species are per-world. That reading was not careless; it was the only
     * one the data supported, because nothing recorded WHERE an action happened, so "revised it"
     * and "started over" were indistinguishable. A unit test on {@code WorldPaths} proves the values
     * exist; only this proves they reach the log.
     */
    @GameTest(template = "empty_test")
    public static void everyRecordSaysWhichWorldAndWhichSitting(GameTestHelper helper) {
        String marker = "lineage-marker-" + helper.getLevel().getGameTime();
        ActionLog.toolCall(marker, new JsonObject(), ToolResult.ok("fine"), 1L);

        JsonObject written = lastRecordMentioning(helper, marker);
        helper.assertTrue(written != null, Component.literal("the tool call should have been logged"));
        helper.assertTrue(written.has("world"),
                Component.literal("no `world` on the record — an analysis still cannot tell a revision from someone "
                        + "starting over in another world, which is how #19 came to be misfiled"));
        helper.assertTrue(written.has("session"),
                Component.literal("no `session` on the record — two sittings hours apart in one world would still "
                        + "read as one continuous revision series"));
        helper.assertTrue(!written.get("world").getAsString().isBlank(),
                Component.literal("the world stamp is blank, which is worse than absent: it looks like an answer"));
        helper.succeed();
    }

    private static JsonObject lastRecordMentioning(GameTestHelper helper, String marker) {
        try {
            List<String> lines = Files.readAllLines(ActionLog.path(), StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 0; i--) {
                String line = lines.get(i);
                if (!line.contains(marker)) {
                    continue;
                }
                return JsonParser.parseString(line).getAsJsonObject();
            }
        } catch (Exception e) {
            helper.fail(Component.literal("could not read the action log: " + e));
        }
        return null;
    }
}
