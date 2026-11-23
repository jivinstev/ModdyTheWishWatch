package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.AddTemplateTool;

import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>, where it runs: a command Minecraft half-obeys is no longer silent.</b>
 *
 * <p>Steve wished for a giant shark and got one; the template the agent saved for it summoned the
 * shark with {@code attributes} Minecraft ignores, so every instant re-wish after that was an
 * ordinary shark announced as "Your Great Big Shark is here!". The only evidence was a WARN in the
 * server log. The attribute-NAME repair ({@link AttributeIds}) already turns the short names that
 * bit him into the right ones, so these tests use an id nothing can repair —
 * {@code minecraft:generic.not_a_real_attribute} — which takes exactly the same silent path.
 *
 * <p>Each assertion here was red before this change: {@code runOne} reported a clean success with
 * nothing in its output, and {@code add_template} saved the command without running a thing.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class CommandTrialGameTest {

    private static final String BOGUS = "minecraft:generic.not_a_real_attribute";

    private static String summon(BlockPos at, String tag, String attributeId) {
        return "summon minecraft:zombie " + at.getX() + " " + at.getY() + " " + at.getZ()
                + " {Tags:[\"" + tag + "\"],NoAI:1b,PersistenceRequired:1b,attributes:[{id:\""
                + attributeId + "\",base:40}]}";
    }

    private static List<Entity> tagged(GameTestHelper helper, BlockPos at, String tag) {
        return helper.getLevel().getEntities((Entity) null, new AABB(at).inflate(8),
                e -> e.getTags().contains(tag));
    }

    /** The shark's shape at run time: it SUCCEEDS, and now it says what it ignored. */
    @GameTest(template = "empty_test", timeoutTicks = 100, batch = "commandtrial")
    public void aSummonMinecraftHalfObeysSaysWhatItIgnored(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(2, 2, 2));
        String tag = "mfam_trial_run";
        WishExecutor.Result r = WishExecutor.runOne(level.getServer(), level,
                Vec3.atBottomCenterOf(at), null, summon(at, tag, BOGUS));
        List<Entity> made = tagged(helper, at, tag);
        made.forEach(Entity::discard);

        helper.assertTrue(WarnCapture.active(),
                Component.literal("the WARN capture never attached to the server's logger — every report below would be "
                        + "empty for the wrong reason"));
        helper.assertTrue(r.success(), Component.literal("the summon itself works (that is the trap): " + r.joined()));
        helper.assertTrue(made.size() == 1, Component.literal("one zombie, not " + made.size()));
        helper.assertTrue(r.ignored().stream().anyMatch(CommandTrialGameTest::namesTheBadAttribute),
                Component.literal("#572: Minecraft ignored the attribute and runOne did not say so — ignored="
                        + r.ignored() + " output=" + r.joined()));
        helper.assertTrue(r.joined().contains("IGNORED part of it"),
                Component.literal("the agent reads `output`, so the warning has to be in it: " + r.joined()));
        helper.succeed();
    }

    /** The trial finds the same thing WITHOUT changing the world, and passes what is fine. */
    @GameTest(template = "empty_test", timeoutTicks = 100, batch = "commandtrial")
    public void theTrialRefusesWhatMinecraftWouldDropAndSpawnsNothing(GameTestHelper helper) {
        var level = helper.getLevel();
        var server = level.getServer();
        BlockPos at = helper.absolutePos(new BlockPos(2, 2, 2));
        Vec3 where = Vec3.atBottomCenterOf(at);
        String tag = "mfam_trial_dry";

        List<String> bad = CommandTrial.problems(server, level, where, summon(at, tag, BOGUS));
        helper.assertTrue(bad.stream().anyMatch(CommandTrialGameTest::namesTheBadAttribute),
                Component.literal("the trial must name the attribute Minecraft would ignore: " + bad));
        helper.assertTrue(tagged(helper, at, tag).isEmpty(),
                Component.literal("a TRIAL must not summon anything — it found " + tagged(helper, at, tag).size()));

        List<String> good = CommandTrial.problems(server, level, where,
                summon(at, tag, com.moddingfromamod.compat.Attrs.generic("scale")));
        helper.assertTrue(good.isEmpty(), Component.literal("a correct attribute must pass: " + good));
        // The exact short name from Steve's shark. The executor repairs it before running, so the
        // trial must judge the repaired command — refusing it would refuse something that works.
        List<String> repaired = CommandTrial.problems(server, level, where,
                summon(at, tag, com.moddingfromamod.compat.Attrs.generic("scale")
                        .contains("generic.") ? "minecraft:scale" : "minecraft:generic.scale"));
        helper.assertTrue(repaired.isEmpty(), Component.literal("a name the executor repairs must pass: " + repaired));
        List<String> unreadable = CommandTrial.problems(server, level, where, "summon minecraft:zombie ~ ~ ~ {");
        helper.assertFalse(unreadable.isEmpty(), Component.literal("a command Minecraft cannot parse must be refused"));
        helper.succeed();
    }

    /**
     * No false refusals: every wish template we SHIP passes its own trial. Since the hard-coded
     * built-ins were deleted what ships is the starter library's recipes, read from the
     * jar exactly as {@code StarterLibrary} unpacks them.
     */
    @GameTest(template = "empty_test", timeoutTicks = 100, batch = "commandtrial")
    public void everyShippedTemplatePassesTheTrial(GameTestHelper helper) {
        var level = helper.getLevel();
        Vec3 where = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 2, 2)));
        String shipped;
        try (var in = CommandTrialGameTest.class.getClassLoader().getResourceAsStream(
                com.moddingfromamod.library.StarterLibrary.ROOT + "templates.json")) {
            helper.assertTrue(in != null, Component.literal("the jar ships no starter templates.json"));
            shipped = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read the starter templates: " + e, e);
        }
        JsonElement root = JsonParser.parseString(shipped);
        JsonArray templates = root.isJsonArray() ? root.getAsJsonArray()
                : root.getAsJsonObject().getAsJsonArray("templates");
        List<String> commands = new ArrayList<>();
        for (JsonElement t : templates) {
            for (JsonElement c : t.getAsJsonObject().getAsJsonArray("commands")) {
                commands.add(c.getAsString());
            }
        }
        helper.assertTrue(commands.size() > 10, Component.literal("expected the shipped templates, found " + commands.size()));
        List<String> problems = CommandTrial.problems(level.getServer(), level, where, commands);
        helper.assertTrue(problems.isEmpty(), Component.literal("a shipped template would be refused: " + problems));
        helper.succeed();
    }

    /**
     * an earlier issue's other half: the TYPED replay. A wish granted with modify_mob is taught as
     * {@code wishsummon <mob> <the same JSON>}, and replaying it produces what modify_mob produced —
     * by the same code, read back afterwards.
     */
    @GameTest(template = "empty_test", timeoutTicks = 100, batch = "commandtrial")
    public void wishsummonReplaysModifyMobsTypedChange(GameTestHelper helper) {
        var level = helper.getLevel();
        var server = level.getServer();
        BlockPos at = helper.absolutePos(new BlockPos(2, 2, 2));
        Vec3 where = Vec3.atBottomCenterOf(at);
        String cmd = "wishsummon minecraft:zombie {\"scale\":3,\"health\":80,\"name\":\"Big Zed\"}";

        helper.assertTrue(CommandTrial.problems(server, level, where, cmd).isEmpty(),
                Component.literal("the typed replay must pass the trial: " + CommandTrial.problems(server, level, where, cmd)));
        helper.assertFalse(CommandTrial.problems(server, level, where,
                        "wishsummon minecraft:zombie {scale:3").isEmpty(),
                Component.literal("a spec that is not modify_mob's JSON must be refused at teach time"));

        WishExecutor.Result r = WishExecutor.runOne(server, level, where, null, cmd);
        List<Entity> made = level.getEntities((Entity) null, new AABB(at).inflate(8),
                e -> e.hasCustomName() && "Big Zed".equals(e.getCustomName().getString()));
        helper.assertTrue(r.success() && r.ignored().isEmpty(), Component.literal("wishsummon failed: " + r.joined()));
        helper.assertTrue(made.size() == 1, Component.literal("one zombie called Big Zed, not " + made.size()));
        var zed = (net.minecraft.world.entity.LivingEntity) made.get(0);
        double scale = zed.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.SCALE);
        double health = zed.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        made.forEach(Entity::discard);
        helper.assertTrue(Math.abs(scale - 3) < 1e-3, Component.literal("the size did not take: " + scale));
        helper.assertTrue(Math.abs(health - 80) < 1e-3, Component.literal("the health did not take: " + health));

        WishExecutor.Result sculpted = WishExecutor.runOne(server, level, where, null,
                "wishsummon moddingfromamod:wish_mob {\"scale\":2}");
        helper.assertFalse(sculpted.success(), Component.literal("a sculpted creature must be sent to `wishmob spawn`"));
        helper.succeed();
    }

    /** And the door itself: add_template refuses it by name, and saves nothing. */
    @GameTest(template = "empty_test", timeoutTicks = 400, batch = "commandtrial")
    public void addTemplateRefusesATemplateMinecraftWouldHalfIgnore(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(new BlockPos(2, 2, 2));
        String id = "mfam_trial_big_shark";
        JsonObject args = new JsonObject();
        args.addProperty("id", id);
        JsonArray all = new JsonArray();
        all.add("trialshark");
        args.add("all", all);
        JsonArray cmds = new JsonArray();
        cmds.add(summon(at, "mfam_trial_tool", BOGUS));
        args.add("commands", cmds);
        args.addProperty("say", "Your Great Big Shark is here!");
        args.addProperty("note", "gametest");

        AtomicReference<ToolResult> result = new AtomicReference<>();
        // A tool hops to the server thread; calling it FROM the server thread would deadlock.
        Thread t = new Thread(() -> result.set(new AddTemplateTool().call(args)), "commandtrial-agent");
        t.setDaemon(true);
        t.start();
        helper.succeedWhen(() -> {
            helper.assertTrue(result.get() != null, Component.literal("waiting for add_template"));
            String text = result.get().text();
            helper.assertTrue(result.get().isError(), Component.literal("add_template must fail the call: " + text));
            helper.assertTrue(text.contains("NOT SAVED") && namesTheBadAttribute(text),
                    Component.literal("#572: add_template must refuse, naming what Minecraft would ignore: " + text));
            helper.assertFalse(LearnedTemplates.isLearned(id), Component.literal("a refused template must not be saved"));
            helper.assertTrue(tagged(helper, at, "mfam_trial_tool").isEmpty(),
                    Component.literal("teaching must not summon anything"));
        });
    }

    /**
     * Minecraft's words for "I dropped that attribute": 1.21.4's AttributeMap said "Ignoring unknown
     * attribute"; 1.21.8 collects it as "Serialization errors: … Unknown registry key … attribute".
     */
    private static boolean namesTheBadAttribute(String warning) {
        return warning.contains("Ignoring unknown attribute")
                || (warning.contains("Serialization errors") && warning.contains("attribute"));
    }
}
