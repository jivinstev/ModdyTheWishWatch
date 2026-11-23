package com.moddingfromamod.world;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.GiveItemsTool;
import com.moddingfromamod.agent.tools.ModifyPlayerTool;
import com.moddingfromamod.agent.tools.RunCommandTool;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.player.PowerTimers;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.wish.WishExecutor;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * <b> where it RUNS: on a world with a clamp rule, what a wish hands HIM is a short boost and a modest gift —
 * and on a world without one, nothing changes.</b>
 *
 * <p>The real tools ({@code run_command}, {@code give_items}, {@code modify_player}) on a wish bound to a mock
 * child, the way the agent calls them, and a learned button through {@link WishExecutor#runOne}. Every clamp is
 * read off the child afterwards — the effect's duration, the diamonds in his bag, his XP level, his size after a
 * death — never off the tool's words. Each test has its control in the same run: under the cap runs unchanged,
 * his creature is never clamped, and the no-rules arm gets the forever power and the 640 diamonds.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class ClampRuleGameTest {

    private static final int T = 4_000;

    private ClampRuleGameTest() {
    }

    static WorldRules rules(int powerSeconds) {
        WorldRules.Parsed p = WorldRules.parse("{\"store\":\"world-rules\",\"v\":1,\"world\":\"closed\",\"rules\":["
                + "{\"id\":\"short-boosts-modest-gifts\",\"type\":\"clamp\",\"params\":{\"powerSeconds\":" + powerSeconds
                + ",\"stacksPerKind\":1,\"kindsPerWish\":3,\"xpLevels\":5}}]}", "closed");
        if (!p.ok()) {
            throw new IllegalStateException(p.errors().toString());
        }
        return p.rules();
    }

    private static JsonObject cmd(String c) {
        JsonObject o = new JsonObject();
        JsonArray a = new JsonArray();
        a.add(c);
        o.add("command", a);
        return o;
    }

    private static JsonObject obj(String... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1].matches("-?\\d+")) {
                o.addProperty(kv[i], Integer.parseInt(kv[i + 1]));
            } else {
                o.addProperty(kv[i], kv[i + 1]);
            }
        }
        return o;
    }

    /** Run {@code body} as a wish of {@code child}'s, off the server thread (the tools hop onto it). */
    private static void asWish(ServerPlayer child, String tag, Runnable body, AtomicReference<Throwable> err,
                               AtomicReference<Boolean> done) {
        UUID wish = UUID.randomUUID();
        String worker = "gt-clamprule-" + tag + "-" + wish;
        Marshal.global().submit(wish, child.getUUID(), "gt clamp rule " + tag);
        Marshal.global().assign(worker, wish);
        Thread t = new Thread(() -> {
            AgentContext.bind(worker);
            try {
                body.run();
            } catch (Throwable e) {
                err.set(e);
            } finally {
                AgentContext.unbind();
                Marshal.global().finish(wish);
                done.set(true);
            }
        }, "gametest-clamprule-" + tag);
        t.setDaemon(true);
        t.start();
    }

    private static int count(ServerPlayer p, Item item) {
        int n = 0;
        for (var stack : p.getInventory().getNonEquipmentItems()) {
            if (stack.is(item)) {
                n += stack.getCount();
            }
        }
        return n + bagCount(p, item);   // the Wish Bag counts too: a gift lands there when the hotbar is full
    }

    private static int bagCount(ServerPlayer p, Item item) {
        try {
            int n = 0;
            for (var s : com.moddingfromamod.bag.WishBagService.contents(p.getServer(), p.getUUID())) {
                if (s.is(item)) {
                    n += s.getCount();
                }
            }
            return n;
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
    }

    private static Entity tagged(GameTestHelper helper, String tag) {
        for (Entity e : helper.getLevel().getAllEntities()) {
            if (e != null && e.getTags().contains(tag)) {
                return e;
            }
        }
        return null;
    }

    // ── a world with a clamp rule: clamped; under the cap and his creatures, unchanged ──────────────────────────────────────

    @GameTest(template = "empty_test", batch = "clamprule", timeoutTicks = T)
    public static void onTheClosedWorldWhatAWishHandsHimIsClampedAndTheRestRunsUnchanged(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer him = MockPlayerNetwork.inLevel(helper, "mfam-clamprule");
        String pet = "gt_clamp_pet_" + helper.getLevel().getGameTime();
        Map<String, ToolResult> out = new LinkedHashMap<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        AtomicReference<Boolean> done = new AtomicReference<>(false);
        AtomicReference<Boolean> done2 = new AtomicReference<>(false);
        java.util.concurrent.atomic.AtomicBoolean startedB = new java.util.concurrent.atomic.AtomicBoolean();
        WorldRulesService.forTest(rules(300));
        RunCommandTool rc = new RunCommandTool();
        asWish(him, "a", () -> {
            for (String c : new String[]{"effect give @s minecraft:speed 30 1", "effect give @s minecraft:levitation 3 2",
                    "effect give @s minecraft:haste infinite", "summon minecraft:pig ~2 ~ ~ {NoAI:1b,Tags:[\"" + pet + "\"]}",
                    "effect give @e[tag=" + pet + ",distance=..10] minecraft:glowing infinite", "give @s minecraft:diamond 640",
                    "xp add @s 1000 levels", "attribute @s minecraft:max_health base set 100",
                    "loot give @s loot minecraft:chests/simple_dungeon",
                    "summon minecraft:item ~ ~ ~ {Item:{id:\"minecraft:diamond\",count:64}}"}) {
                out.put(c, rc.call(cmd(c)));
            }
            for (int i = 0; i < 10; i++) {
                out.put("log" + i, rc.call(cmd("give @s minecraft:oak_log 10")));
            }
            out.put("fast", new ModifyPlayerTool().call(obj("powers", "fast")));
        }, err, done);
        // a second wish: give_items' own clamp, with a fresh ledger
        helper.runAfterDelay(2, () -> {});
        helper.succeedWhen(() -> {
            helper.assertTrue(done.get(), Component.literal("wish a running"));
            if (startedB.compareAndSet(false, true)) {
                asWish(him, "b", () -> {
                    out.put("cobble", new GiveItemsTool().call(obj("item", "minecraft:cobblestone", "count", "256")));
                    for (String k : List.of("apple", "bread", "carrot", "potato", "beef", "stick")) {
                        out.put("kind:" + k, new GiveItemsTool().call(obj("item", "minecraft:" + k, "count", "1")));
                    }
                }, err, done2);
            }
            helper.assertTrue(done2.get(), Component.literal("wish b running"));
            // Every call is done: the rules go NOW, before any assertion can fail and leave them on for the next batch.
            WorldRulesService.forTest(null);
            helper.assertTrue(err.get() == null, Component.literal("a tool threw: " + err.get()));
            // under the cap: unchanged
            MobEffectInstance speed = him.getEffect(MobEffects.SPEED);
            helper.assertTrue(speed != null && !speed.isInfiniteDuration(), Component.literal("no speed: " + out));
            MobEffectInstance haste = him.getEffect(MobEffects.HASTE);
            helper.assertTrue(haste != null && !haste.isInfiniteDuration() && haste.getDuration() <= 300 * 20,
                    Component.literal("`effect … infinite` on him was not clamped: " + haste));
            helper.assertTrue(him.getEffect(MobEffects.LEVITATION) != null,
                    Component.literal("make_item's fly command (levitation 3 s) must run unchanged: " + out));
            Entity p = tagged(helper, pet);
            helper.assertTrue(p instanceof net.minecraft.world.entity.LivingEntity le && le.getEffect(MobEffects.GLOWING) != null
                    && le.getEffect(MobEffects.GLOWING).isInfiniteDuration(), Component.literal("his pet's glow was clamped"));
            helper.assertTrue(count(him, Items.DIAMOND) == 64, Component.literal("`give @s diamond 640` → one stack: got " + count(him, Items.DIAMOND)
                    + " — the tool said: " + out.get("give @s minecraft:diamond 640").text()));
            helper.assertTrue(count(him, Items.OAK_LOG) == 64, Component.literal("ten gives of 10 logs in one wish: got " + count(him, Items.OAK_LOG)));
            helper.assertTrue(him.experienceLevel == 5, Component.literal("`xp add @s 1000 levels` → 5: got " + him.experienceLevel + " — " + out));
            helper.assertTrue(out.get("attribute @s minecraft:max_health base set 100").isError()
                    && out.get("attribute @s minecraft:max_health base set 100").text().contains("modify_player with seconds"),
                    Component.literal("a permanent attribute on him ran"));
            helper.assertTrue(out.get("loot give @s loot minecraft:chests/simple_dungeon").isError(), Component.literal("loot give ran"));
            helper.assertTrue(out.get("summon minecraft:item ~ ~ ~ {Item:{id:\"minecraft:diamond\",count:64}}").isError(),
                    Component.literal("summon item ran"));
            helper.assertTrue(!out.get("fast").isError() && out.get("fast").text().contains("CLAMPED"),
                    Component.literal("modify_player with no seconds: " + out.get("fast").text()));
            helper.assertTrue(PowerTimers.timed(him).contains("fast"), Component.literal("the power was not put on a timer"));
            helper.assertTrue(MockPlayerNetwork.chat(him).stream().anyMatch(l -> l.contains("short boosts")),
                    Component.literal("he was not told: " + MockPlayerNetwork.chat(him)));
            // give_items, wish b
            helper.assertTrue(count(him, Items.COBBLESTONE) == 64, Component.literal("give_items 256 → one stack: got " + count(him, Items.COBBLESTONE)));
            long kinds = List.of("apple", "bread", "carrot", "potato", "beef", "stick").stream()
                    .filter(k -> !out.get("kind:" + k).isError()).count();
            helper.assertTrue(kinds == 2, Component.literal("cobblestone + 2 = 3 kinds in wish b: got " + kinds + " — " + out));
            if (p != null) {
                p.discard();
            }
            WorldRulesService.forTest(null);
            MockPlayerNetwork.remove(him);
        });
    }

    // ── a world with no clamp rule: nothing changes ──────────────────────────────────────────────────────

    @GameTest(template = "empty_test", batch = "clamprule_open", timeoutTicks = T)
    public static void onTheOpenWorldForeverPowersBigGiftsAndBigXpAreUnchanged(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer him = MockPlayerNetwork.inLevel(helper, "mfam-openclamp");
        Map<String, ToolResult> out = new LinkedHashMap<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        AtomicReference<Boolean> done = new AtomicReference<>(false);
        WorldRulesService.forTest(null);
        RunCommandTool rc = new RunCommandTool();
        asWish(him, "w", () -> {
            for (String c : new String[]{"effect give @s minecraft:haste infinite", "give @s minecraft:diamond 640",
                    "xp add @s 1000 levels"}) {
                out.put(c, rc.call(cmd(c)));
            }
            out.put("fast", new ModifyPlayerTool().call(obj("powers", "fast")));
        }, err, done);
        helper.succeedWhen(() -> {
            helper.assertTrue(done.get(), Component.literal("running"));
            helper.assertTrue(err.get() == null, Component.literal("a tool threw: " + err.get()));
            helper.assertTrue(him.getEffect(MobEffects.HASTE) != null && him.getEffect(MobEffects.HASTE).isInfiniteDuration(),
                    Component.literal("the no-rules world's forever effect was clamped"));
            helper.assertTrue(him.getEffect(MobEffects.SPEED) != null && him.getEffect(MobEffects.SPEED).isInfiniteDuration(),
                    Component.literal("the no-rules world's forever power was clamped: " + out.get("fast").text()));
            helper.assertTrue(!PowerTimers.timed(him).contains("fast"), Component.literal("a forever power got a timer"));
            helper.assertTrue(count(him, Items.DIAMOND) == 640, Component.literal("the no-rules world's 640 diamonds: got " + count(him, Items.DIAMOND)));
            helper.assertTrue(him.experienceLevel == 1000, Component.literal("the no-rules world's 1000 levels: got " + him.experienceLevel + " — " + out));
            MockPlayerNetwork.remove(him);
        });
    }

    // ── a clamped power really ends, through a death ──────────────────────────────────────────────────────

    /**
     * With a two-second cap: {@code modify_player giant} with no {@code seconds} makes him giant; he dies and respawns
     * INSIDE the cap and is still giant (vanilla copies attributes), and past the cap the giant is gone from the new
     * body — the power that,, had no clock at all.
     */
    @GameTest(template = "empty_test", batch = "clamprule_death", timeoutTicks = 100_000)
    public static void aClampedPowerIsGoneAfterTheCapEvenThroughDeathAndRespawn(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer him = MockPlayerNetwork.inLevel(helper, "mfam-clampdeath");
        AtomicReference<ToolResult> r = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        AtomicReference<Boolean> done = new AtomicReference<>(false);
        AtomicReference<ServerPlayer> reborn = new AtomicReference<>();
        WorldRulesService.forTest(rules(2));
        asWish(him, "d", () -> r.set(new ModifyPlayerTool().call(obj("powers", "giant"))), err, done);
        long deadline = System.currentTimeMillis() + 60_000;
        helper.succeedWhen(() -> {
            if (System.currentTimeMillis() > deadline) {
                WorldRulesService.forTest(null);
                helper.fail(Component.literal("the giant never ended: " + (reborn.get() == null ? "no respawn"
                        : reborn.get().getAttributeValue(Attributes.SCALE))));
            }
            helper.assertTrue(done.get(), Component.literal("granting"));
            WorldRulesService.forTest(null);      // granted: the timer needs no rules from here
            helper.assertTrue(err.get() == null && !r.get().isError(), Component.literal("modify_player: " + r.get() + err.get()));
            if (reborn.get() == null) {
                helper.assertTrue(him.getAttributeValue(Attributes.SCALE) > 1.0, Component.literal("he was never made giant"));
                him.die(him.damageSources().generic());
                reborn.set(him.getServer().getPlayerList().respawn(him, false, Entity.RemovalReason.KILLED));
                helper.assertTrue(reborn.get() != him, Component.literal("respawn returned the same body"));
                helper.assertTrue(reborn.get().getAttributeValue(Attributes.SCALE) > 1.0,
                        Component.literal("inside the cap he should still be giant after respawning"));
            }
            helper.assertTrue(reborn.get().getAttributeValue(Attributes.SCALE) == 1.0,
                    Component.literal("still giant (waiting out the 2 s cap)"));
            helper.assertTrue(PowerTimers.timed(reborn.get()).isEmpty(), Component.literal("the timer was not cleared"));
            WorldRulesService.forTest(null);
            MockPlayerNetwork.remove(reborn.get());
        });
    }

    // ── a learned button ─────────────────────────────────────────────────────────────────────────────────

    /** A button that gave a forever power and a big gift is clamped when replayed on a world with a clamp rule; on no-rules, it is not. */
    @GameTest(template = "empty_test", batch = "clamprule_replay", timeoutTicks = 200)
    public static void aReplayedButtonIsClampedOnTheClosedWorld(GameTestHelper helper) {
        ServerPlayer closed = MockPlayerNetwork.inLevel(helper, "mfam-clampreplay-c");
        ServerPlayer open = MockPlayerNetwork.inLevel(helper, "mfam-clampreplay-o");
        var server = helper.getLevel().getServer();
        try {
            WorldRulesService.forTest(rules(300));
            var a = WishExecutor.runOne(server, helper.getLevel(), closed.position(), closed, "effect give @s minecraft:haste infinite");
            var b = WishExecutor.runOne(server, helper.getLevel(), closed.position(), closed, "give @s minecraft:diamond 640");
            helper.assertTrue(a.success() && b.success(), Component.literal("the clamped button should still run: " + a.joined() + b.joined()));
            helper.assertTrue(closed.getEffect(MobEffects.HASTE) != null && !closed.getEffect(MobEffects.HASTE).isInfiniteDuration(),
                    Component.literal("a replayed forever effect was not clamped"));
            helper.assertTrue(count(closed, Items.DIAMOND) == 64, Component.literal("a replayed 640 diamonds → one stack: got " + count(closed, Items.DIAMOND)));
            WorldRulesService.forTest(null);
            WishExecutor.runOne(server, helper.getLevel(), open.position(), open, "effect give @s minecraft:haste infinite");
            WishExecutor.runOne(server, helper.getLevel(), open.position(), open, "give @s minecraft:diamond 640");
            helper.assertTrue(open.getEffect(MobEffects.HASTE) != null && open.getEffect(MobEffects.HASTE).isInfiniteDuration(),
                    Component.literal("with no clamp rule the button is unchanged"));
            helper.assertTrue(count(open, Items.DIAMOND) == 640, Component.literal("with no clamp rule, 640: got " + count(open, Items.DIAMOND)));
        } finally {
            WorldRulesService.forTest(null);
            MockPlayerNetwork.remove(closed);
            MockPlayerNetwork.remove(open);
        }
        helper.succeed();
    }

    @SuppressWarnings("unused")
    private static final List<String> UNUSED = new ArrayList<>();
}
