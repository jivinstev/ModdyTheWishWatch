package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.agent.tools.FinishWishTool;
import com.moddingfromamod.agent.tools.RunCommandTool;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.testing.MockPlayerNetwork;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Creeper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>A child's 100 creepers survive the wish's own tidy-up,.</b> Since the
 * wish's wave of them ({@code SpawnBudget.EXPLOSIVE_PER_WISH}) is what stands, and is what is asked.
 *
 * <p>Production, 2026-09-28: <i>"make 100 creepers"</i> summoned them through four
 * {@code run_command} batches, the reveal said so, and 3.5 s later {@code finish_wish}'s sweep
 * removed all 100 as test props — unnamed, unregistered, not ridden, so every keep rule missed. The
 * fix is one keep rule: an alive mob with its AI on is never a prop.
 *
 * <p>Driven through the REAL {@code run_command} and {@code finish_wish}, never
 * {@link WishProps#sweep} directly: the bracket is stamped inside one tool and read inside the
 * other, and a rule tested only where it is defined is not tested where it runs. Both directions in
 * one wish: the creepers stay AND a NoAI armour stand prop from the same wish still comes down,
 * because a fix that simply stopped sweeping would pass the first half.
 *
 * <p>Its own batch: it binds a wish run on {@code Marshal.global()}, and {@code AgentContext}
 * falls back to "the single wish in flight", which a concurrent test's wish would make ambiguous.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class LivingMobIsNotAPropGameTest {

    private static final String TAG = "gt_t213";
    /** What the child asked for, summoned in full as production's agent did. */
    private static final int ASKED = 100;
    /**
     * What stands: one wish's wave of explosives. The rest are never made, so the sweep
     * question is asked of the wave — and the four batches prove ONE wish meets ONE wave.
     */
    private static final int CREEPERS = Math.min(ASKED,
            com.moddingfromamod.mob.SpawnBudget.defaultFor(com.moddingfromamod.mob.SpawnBudget.Kind.EXPLOSIVE, false));
    /** How long they must still stand after finish_wish — the sweep ran 3.5 s after the reveal. */
    private static final int STAND_TICKS = 200;

    @GameTest(template = "empty_test", batch = "t213_living", timeoutTicks = 1200)
    public static void aHundredSummonedCreepersSurviveFinishWishAndThePropDoesNot(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer mock = MockPlayerNetwork.inLevel(helper);   // creative: no creeper lights up
        standOnACleanPad(helper, mock);
        UUID player = mock.getUUID();
        Wish wish = WishBook.get(level.getServer()).add(Wish.create("make 100 creepers around me",
                WishSource.TYPED, System.currentTimeMillis(), player, "Steve",
                "minecraft:overworld", 0, 64, 0));
        Marshal.global().submit(wish.id(), player, wish.text());
        String worker = "gt-t213";
        Marshal.global().assign(worker, wish.id());
        BuildGate.startWish(wish.id());
        // put back on EVERY ending -- a timeout used to leave this wish WORKING for every later test to read.
        MockPlayerNetwork.afterTest(helper, () -> tidy(level, wish, mock));

        AtomicReference<String> finished = new AtomicReference<>();
        AtomicReference<String> lastBatch = new AtomicReference<>("");
        AtomicReference<String> failed = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                AgentContext.with(worker, () -> {
                    // Four batches of 25, as production's wish did (run_command ×4), on a 10×10
                    // grid around the child.
                    for (int b = 0; b < 4; b++) {
                        JsonArray cmds = new JsonArray();
                        for (int i = b * (ASKED / 4); i < (b + 1) * (ASKED / 4); i++) {
                            cmds.add("summon minecraft:creeper ~" + (i % 10 - 5) + " ~ ~"
                                    + (i / 10 - 5) + " {Tags:[\"" + TAG + "\"]}");
                        }
                        lastBatch.set(call(new RunCommandTool(), "command", cmds));
                    }
                    // an earlier issue's real prop, in the same wish: an armour stand with its AI off.
                    JsonArray prop = new JsonArray();
                    prop.add("summon minecraft:armor_stand ~2 ~ ~2 {NoAI:1b,Tags:[\"" + TAG
                            + "\",\"firetest\"]}");
                    call(new RunCommandTool(), "command", prop);

                    // The reveal, as production's agent does it: finish_building (answered by a
                    // stand-in camera, as OneEndingGameTest does), THEN finish_wish.
                    String label = "gt_t213_" + System.nanoTime();
                    answerTheCamera(label);
                    JsonObject reveal = new JsonObject();
                    reveal.addProperty("say", "BOOM! 100 CREEPERS all around you!");
                    reveal.addProperty("label", label);
                    new com.moddingfromamod.agent.tools.FinishBuildingTool().call(reveal);
                    WishCapture.forgetForTest(player);

                    JsonObject done = new JsonObject();
                    done.addProperty("id", wish.shortId());
                    done.addProperty("verdict", "done");
                    done.addProperty("note", "100 creepers are all around you!");
                    var r = new FinishWishTool().call(done);
                    // a wish granted with commands and never taught is asked ONCE to teach
                    // itself; this one is the shape that motivated it (100 creepers). The agent may
                    // decline by finishing again, which is what this test's subject needs.
                    if (r != null && r.isError() && r.text().contains("teach this one first")) {
                        r = new FinishWishTool().call(done);
                    }
                    finished.set(r == null ? "" : r.text());
                    return null;
                });
            } catch (Throwable e) {
                failed.set(String.valueOf(e));
            }
        }, "t213-agent");
        t.setDaemon(true);
        t.start();

        AtomicLong finishedAt = new AtomicLong(-1);
        // ONE verdict, taken once at STAND_TICKS and kept: the entities are tidied the moment it
        // is taken, so a retry that recounted would report 0 creepers for the wrong reason.
        AtomicReference<String> verdict = new AtomicReference<>();
        // Every creeper this wish summoned, by REFERENCE, gathered each tick until finish_wish
        // returns: once the sweep has run, a creeper it removed is gone from the level and could
        // only be counted as missing. Holding the entity is what lets the verdict ask HOW it went.
        java.util.Set<Creeper> seen = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        helper.onEachTick(() -> {
            if (finished.get() == null) {
                for (Entity e : level.getEntities().getAll()) {
                    if (e instanceof Creeper c && e.entityTags().contains(TAG)) {
                        seen.add(c);
                    }
                }
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(failed.get() == null, Component.literal("the agent thread threw: " + failed.get()));
            String said = finished.get();
            helper.assertTrue(said != null, Component.literal("waiting for finish_wish"));
            if (finishedAt.get() < 0) {
                finishedAt.set(level.getGameTime());
            }
            if (verdict.get() == null) {
                helper.assertTrue(level.getGameTime() - finishedAt.get() >= STAND_TICKS,
                        Component.literal("waiting " + STAND_TICKS + " ticks after finish_wish"));
                verdict.set(judge(level, said, seen));
                if (verdict.get().isEmpty() && CREEPERS < ASKED
                        && !lastBatch.get().contains("Wish 'more'")) {
                    verdict.set("#1265: the wave stopped at " + CREEPERS + " of " + ASKED
                            + " and run_command did not hand the agent the wave words: "
                            + lastBatch.get());
                }
            }
            helper.assertTrue(verdict.get().isEmpty(), Component.literal(verdict.get()));
        });
    }

    /** "" when the wish's creepers stand and its prop is gone, else what is wrong. */
    private static String judge(ServerLevel level, String said, java.util.Set<Creeper> seen) {
        if (!said.contains("ended as")) {
            return "finish_wish did not end the wish: " + said;
        }
        if (seen.size() != CREEPERS) {
            return "the wish summoned " + seen.size() + " creepers, not " + CREEPERS
                    + " — the fixture, not the sweep. finish_wish said: " + said;
        }
        // HOW each one that is not standing went. is the sweep DISCARDING them; a creeper
        // that DIED (fell into something another batch left in this reused world — measured: 97
        // and 99 of 100 standing in two full-tier runs, 10/10 alone) is not that bug,
        // and counting it as the sweep made a green feature read red.
        int swept = 0;
        int died = 0;
        java.util.Map<String, Integer> causes = new java.util.TreeMap<>();
        for (Creeper c : seen) {
            if (c.isAlive()) {
                continue;
            }
            // A creeper whose health ran out DIED, whatever removed it afterwards; only one that
            // was still alive when it was taken away was swept.
            if (c.getHealth() > 0 && c.getRemovalReason() == Entity.RemovalReason.DISCARDED) {
                swept++;
            } else {
                died++;
                var src = c.getLastDamageSource();
                causes.merge(src == null ? "no damage source" : src.getMsgId(), 1, Integer::sum);
            }
        }
        if (swept != 0) {
            return "#1112: the child wished for " + CREEPERS + " creepers and " + swept
                    + " were REMOVED by " + STAND_TICKS + " ticks after finish_wish (" + died
                    + " more died: " + causes + ") — the #517 prop sweep took living mobs with their AI on for "
                    + "test props. finish_wish said: " + said;
        }
        if (died > CREEPERS / 10) {
            return died + " of " + CREEPERS + " creepers DIED (none were removed; " + causes
                    + ") — on their own clean pad nothing should kill them; look at what did. "
                    + "finish_wish said: " + said;
        }
        int stands = 0;
        for (Entity e : level.getEntities().getAll()) {
            if (e instanceof ArmorStand && e.entityTags().contains(TAG) && e.isAlive()) {
                stands++;
            }
        }
        if (stands != 0) {
            return "the NoAI armour stand was a test prop and is still standing — the fix must "
                    + "keep LIVING mobs, not stop sweeping. finish_wish said: " + said;
        }
        return "";
    }

    /**
     * <b>The child stands on this test's OWN ground.</b> A mock joins at WORLD SPAWN, and
     * {@code run_command}'s {@code ~} is the child — so the creepers used to be summoned at spawn,
     * the one place every test in this reused world shares. In full runs about half of them died
     * there (2 of 81 on 1.21.4, more on 1.21.8) and passed alone: what killed them was whatever an
     * earlier batch had left at spawn, not the sweep. The grid spans ten blocks by five north of
     * the child, wider than the 9-block structure, so the pad is laid fresh: a floor and clear air
     * over everything the creepers can reach in {@link #STAND_TICKS}.
     */
    private static void standOnACleanPad(GameTestHelper helper, ServerPlayer mock) {
        ServerLevel level = helper.getLevel();
        var floor = net.minecraft.world.level.block.Blocks.POLISHED_ANDESITE.defaultBlockState();
        var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        for (int x = -5; x <= 13; x++) {
            for (int z = -5; z <= 13; z++) {
                level.setBlock(helper.absolutePos(new net.minecraft.core.BlockPos(x, 0, z)), floor, 2);
                for (int y = 1; y <= 4; y++) {
                    level.setBlock(helper.absolutePos(new net.minecraft.core.BlockPos(x, y, z)), air, 2);
                }
            }
        }
        // The grid is x -5..+4, z -5..-1 from the child: centred on the pad with room to wander.
        var at = helper.absoluteVec(new net.minecraft.world.phys.Vec3(4.5, 1, 7.5));
        com.moddingfromamod.compat.Teleports.player(mock, level, at.x, at.y, at.z, 0f, 0f);
    }

    private static String call(com.moddingfromamod.agent.McpTool tool, String key, JsonArray value) {
        JsonObject args = new JsonObject();
        args.add(key, value);
        var r = tool.call(args);
        if (r == null || r.isError()) {
            throw new IllegalStateException("run_command failed: " + (r == null ? "null"
                    : r.text()));
        }
        return r.text();
    }

    /** Writes the photo finish_building waits for, as OneEndingGameTest does. */
    private static void answerTheCamera(String label) {
        com.moddingfromamod.testing.FakeCamera.answer(label);
    }

    private static void tidy(ServerLevel level, Wish wish, ServerPlayer mock) {
        // Copied first: discarding while walking the live entity list hands back nulls.
        java.util.List<Entity> ours = new java.util.ArrayList<>();
        for (Entity e : level.getEntities().getAll()) {
            if (e != null && e.entityTags().contains(TAG)) {
                ours.add(e);
            }
        }
        ours.forEach(Entity::discard);
        Marshal.global().finish(wish.id());
        BuildGate.settle(wish.id());
        MockPlayerNetwork.remove(mock);
    }
}
