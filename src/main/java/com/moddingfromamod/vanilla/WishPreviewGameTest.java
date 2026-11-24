package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.WishRun;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.wish.reveal.WishEarlyReveal;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>A creature designed and not yet spawned is shown as a PREVIEW</b>
 * ({@link WishShimmer#preview}).
 *
 * <p>Driven through {@link WishEarlyReveal#afterTool}, the seam {@code McpProtocol} calls after every
 * tool, never through the helper alone: a rule tested only where it is defined is not tested where it
 * runs. Two endings, because they fail differently:
 * <ul>
 *   <li>the agent spawns the real one (the schema's spawn:false path): the preview must GO, or he
 *       is left with two Spikors, one of them unkillable and frozen;</li>
 *   <li>the agent never does: the preview must become the creature, or he is left with a statue.</li>
 * </ul>
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class WishPreviewGameTest {

    @GameTest(template = "empty_test", batch = "wish_preview_replaced", timeoutTicks = 1200)
    public static void aDesignedCreatureStandsAsAPreviewUntilTheRealOneArrives(GameTestHelper helper) {
        drive(helper, "gt_preview_replaced", true);
    }

    @GameTest(template = "empty_test", batch = "wish_preview_kept", timeoutTicks = 1200)
    public static void aPreviewNobodyReplacedBecomesTheCreatureAtTheEnd(GameTestHelper helper) {
        drive(helper, "gt_preview_kept", false);
    }

    /**
     * <b>An EDIT of a creature he already has</b>: a re-sculpt rebuilds every
     * copy of it on the animator's next tick, after the tool has returned. That must not be taken
     * for a creature designed and not spawned (a frozen preview beside his real one), and the
     * rebuilt copies must keep the birth they had, so the end of the wish never reaps one of his
     * own creatures as the wish's scaffolding.
     */
    @GameTest(template = "empty_test", batch = "wish_preview_edit", timeoutTicks = 1200)
    public static void anEditOfHisCreatureStandsNoPreviewAndKeepsItsBirth(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        String id = SpeciesOnRigGameTest.plant(helper, "gt_preview_edit_" + level.getGameTime(),
                "{\"powers\":[]}");
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer child = MockPlayerNetwork.inLevel(helper);
        // His two, from before the wish.
        var at = child.position();
        for (int i = 0; i < 2; i++) {
            helper.assertTrue(CreatureSpawn.spawn(level, id, at.x + 3 + 3 * i, at.y, at.z + 3, 0f,
                    EntitySpawnReason.COMMAND, false) != null, Component.literal("his creature did not stand"));
        }
        List<Long> births = new ArrayList<>();
        for (Mob m : carriers(level, id)) {
            births.add(WishShimmer.bornTick(m.getUUID()));
        }
        UUID wish = UUID.randomUUID();
        Marshal.global().submit(wish, child.getUUID(), "make my " + id + " bigger");
        WishRun run = Marshal.global().assign("gt-preview-edit-" + level.getGameTime(), wish);
        // As sculpt_creature records it, so the creature is this wish's even with other wishes live.
        run.made(com.moddingfromamod.marshal.MadeKind.CREATURE, id);

        // What sculpt_creature does to a creature that exists: the species changes, and the copies
        // are rebuilt on a LATER tick, by the animator.
        long mark = RigReveal.stoodMark();
        SpeciesOnRigGameTest.plant(helper, id, "{\"powers\":[],\"scale\":1.6}");
        AtomicReference<Throwable> died = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean returned = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean shownAtEnd = new java.util.concurrent.atomic.AtomicBoolean();
        Thread t = new Thread(() -> {
            try {
                JsonObject args = new JsonObject();
                args.addProperty("id", id);
                args.addProperty("spawn", false);
                WishEarlyReveal.afterTool(run, "sculpt_creature", mark, args, true);
            } catch (Throwable e) {
                died.set(e);
            } finally {
                // Shown or not, the wish ends here: a run left live leaks into every later test.
                shownAtEnd.set(WishEarlyReveal.shown(wish));
                server.execute(() -> {
                    WishEarlyReveal.end(server, wish);
                    Marshal.global().finish(wish);
                    returned.set(true);
                });
            }
        }, "gametest-wish-edit");
        t.setDaemon(true);
        t.start();

        helper.succeedWhen(() -> {
            helper.assertTrue(died.get() == null, Component.literal("afterTool threw: " + died.get()));
            helper.assertTrue(returned.get(), Component.literal("waiting for afterTool"));
            helper.assertTrue(previews(level, id).isEmpty(),
                    Component.literal("an edit stood a frozen preview beside his real creature"));
            helper.assertTrue(shownAtEnd.get(), Component.literal("the edit was never shown to him shimmering"));
            List<Mob> now = carriers(level, id);
            helper.assertValueEqual(now.size(), 2, Component.literal("his creatures after the edit"));
            List<Long> kept = new ArrayList<>();
            for (Mob m : now) {
                kept.add(WishShimmer.bornTick(m.getUUID()));
            }
            helper.assertTrue(kept.containsAll(births) && births.containsAll(kept),
                    Component.literal("a rebuilt copy lost its birth " + births + " -> " + kept
                            + ", so the wish could reap it as scaffolding"));
            // His two stand at world spawn, which every later test shares: take them down, or the
            // next test asking "what is nearest him" finds a Pet Dragon (CreatureResolverGameTest).
            for (Mob m : now) {
                DisplayRig.reap(level, m.getUUID());
                m.discard();
            }
            helper.succeed();
        });
    }

    /**
     * <b>The last wish's creature is not this one's</b> (filmed 2026-10-22). The worker's
     * {@code await_wish} call spans the gap between two wishes, so its "what stood during this call"
     * held the previous wish's Spikor, and the next wish opened with "starting to appear!" before it
     * had a plan. Only what stood after the wish was handed to its worker counts.
     */
    @GameTest(template = "empty_test", batch = "wish_preview_await", timeoutTicks = 200)
    public static void whatStoodBeforeTheWishWasHandedOverIsNotItsReveal(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        String id = SpeciesOnRigGameTest.plant(helper, "gt_preview_await_" + level.getGameTime(),
                "{\"powers\":[]}");
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer child = MockPlayerNetwork.inLevel(helper);
        long mark = RigReveal.stoodMark();   // taken as await_wish begins
        var at = child.position();
        helper.assertTrue(CreatureSpawn.spawn(level, id, at.x + 3, at.y, at.z + 3, 0f,
                EntitySpawnReason.COMMAND, false) != null, Component.literal("the last wish's creature did not stand"));
        try {
            Thread.sleep(5);   // the hand-over comes after it, on any clock
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        UUID wish = UUID.randomUUID();
        Marshal.global().submit(wish, child.getUUID(), "make my " + id + " bigger");
        WishRun run = Marshal.global().assign("gt-preview-await-" + level.getGameTime(), wish);
        run.made(com.moddingfromamod.marshal.MadeKind.CREATURE, id);
        // Off the server thread, as McpProtocol calls it.
        java.util.concurrent.atomic.AtomicBoolean returned = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean shown = new java.util.concurrent.atomic.AtomicBoolean();
        Thread t = new Thread(() -> {
            try {
                WishEarlyReveal.afterTool(run, "await_wish", mark, null, true);
            } finally {
                shown.set(WishEarlyReveal.shown(wish));
                level.getServer().execute(() -> {
                    WishEarlyReveal.end(level.getServer(), wish);
                    Marshal.global().finish(wish);
                    returned.set(true);
                });
            }
        }, "gametest-wish-await");
        t.setDaemon(true);
        t.start();
        helper.succeedWhen(() -> {
            helper.assertTrue(returned.get(), Component.literal("waiting for afterTool"));
            helper.assertTrue(!shown.get(),
                    Component.literal("a creature that stood before this wish was handed over was revealed as this wish's"));
        });
    }

    /**
     * <b>A second creature in a wish already shown gets its own line</b>: the castle's
     * seahorses appeared silently after the first reveal, and the line that had been said was "it".
     */
    @GameTest(template = "empty_test", batch = "wish_preview_second", timeoutTicks = 1200)
    public static void aSecondCreatureInOneWishIsNamedAsItAppears(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        long t = level.getGameTime();
        String first = SpeciesOnRigGameTest.plant(helper, "gt_second_a_" + t, "{\"powers\":[],\"name\":\"Castle Crab\"}");
        String second = SpeciesOnRigGameTest.plant(helper, "gt_second_b_" + t, "{\"powers\":[],\"name\":\"Seahorse\"}");
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer child = MockPlayerNetwork.inLevel(helper);
        UUID wish = UUID.randomUUID();
        Marshal.global().submit(wish, child.getUUID(), "a castle crab and seahorses");
        WishRun run = Marshal.global().assign("gt-second-" + t, wish);
        run.made(com.moddingfromamod.marshal.MadeKind.CREATURE, first);
        run.made(com.moddingfromamod.marshal.MadeKind.CREATURE, second);
        var at = child.position();
        AtomicReference<Throwable> died = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        Thread th = new Thread(() -> {
            try {
                for (String sp : List.of(first, second)) {
                    long mark = RigReveal.stoodMark();
                    AtomicReference<Boolean> stood = new AtomicReference<>();
                    server.execute(() -> stood.set(CreatureSpawn.spawn(level, sp, at.x + 3, at.y, at.z + 3, 0f,
                            EntitySpawnReason.COMMAND, false) != null));
                    long until = System.currentTimeMillis() + 5_000;
                    while (stood.get() == null && System.currentTimeMillis() < until) {
                        Thread.sleep(20);
                    }
                    WishEarlyReveal.afterTool(run, "run_command", mark, null, true);
                }
            } catch (Throwable e) {
                died.set(e);
            } finally {
                server.execute(() -> {
                    WishEarlyReveal.end(server, wish);
                    Marshal.global().finish(wish);
                    done.set(true);
                });
            }
        }, "gametest-wish-second");
        th.setDaemon(true);
        th.start();
        helper.succeedWhen(() -> {
            helper.assertTrue(died.get() == null, Component.literal("threw: " + died.get()));
            helper.assertTrue(done.get(), Component.literal("waiting for both creatures"));
            String said = String.join("\n", MockPlayerNetwork.chat(child));
            helper.assertTrue(said.contains("your Castle Crab is starting to appear"),
                    Component.literal("the first creature was not named as it appeared: " + said));
            helper.assertTrue(said.contains("your Seahorse is starting to appear"),
                    Component.literal("the second creature in the wish appeared without a word: " + said));
        });
    }

    private static void drive(GameTestHelper helper, String base, boolean spawnReal) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        String id = SpeciesOnRigGameTest.plant(helper, base + "_" + level.getGameTime(), "{\"powers\":[]}");
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer child = MockPlayerNetwork.inLevel(helper);
        UUID wish = UUID.randomUUID();
        Marshal.global().submit(wish, child.getUUID(), "make me a " + id);
        WishRun run = Marshal.global().assign("gt-preview-" + level.getGameTime(), wish);

        // What McpProtocol does after a sculpt_creature that spawned nothing: off the server thread.
        AtomicReference<Throwable> died = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                JsonObject args = new JsonObject();
                args.addProperty("id", id);
                args.addProperty("spawn", false);
                WishEarlyReveal.afterTool(run, "sculpt_creature",
                        com.moddingfromamod.vanilla.RigReveal.stoodMark(), args, true);
            } catch (Throwable e) {
                died.set(e);
            }
        }, "gametest-wish-preview");
        t.setDaemon(true);
        t.start();

        AtomicInteger stage = new AtomicInteger();
        AtomicReference<UUID> real = new AtomicReference<>();
        helper.succeedWhen(() -> {
            helper.assertTrue(died.get() == null, Component.literal("afterTool threw: " + died.get()));
            switch (stage.get()) {
                case 0 -> {
                    List<Mob> previews = previews(level, id);
                    helper.assertTrue(previews.size() == 1, Component.literal("waiting for exactly one preview of " + id
                            + ", found " + previews.size()));
                    Mob p = previews.get(0);
                    helper.assertTrue(p.isInvulnerable(), Component.literal("a preview can be killed before it works"));
                    helper.assertTrue(p.isNoAi(), Component.literal("a preview acts before it works"));
                    helper.assertTrue(WishShimmer.inProgress(id), Component.literal("the preview is not shimmering"));
                    if (spawnReal) {
                        var at = p.position();
                        var c = CreatureSpawn.spawn(level, id, at.x + 3, at.y, at.z, 0f,
                                EntitySpawnReason.COMMAND, false);
                        helper.assertTrue(c != null, Component.literal("the real one did not stand up"));
                        real.set(c.getUUID());
                    } else {
                        real.set(p.getUUID());
                        WishShimmer.end(server, wish);
                    }
                    stage.set(1);
                    helper.fail(Component.literal("next stage"));
                }
                default -> {
                    if (spawnReal) {
                        helper.assertTrue(previews(level, id).isEmpty(),
                                Component.literal("the preview is still standing beside the real one"));
                    }
                    Entity c = level.getEntity(real.get());
                    helper.assertTrue(c instanceof Mob, Component.literal("the creature is gone"));
                    Mob m = (Mob) c;
                    helper.assertTrue(!m.entityTags().contains(WishShimmer.PREVIEW_TAG) && !m.isNoAi()
                                    && !m.isInvulnerable(),
                            Component.literal("he was left a frozen, unkillable statue rather than his creature"));
                    WishShimmer.end(server, wish);
                    Marshal.global().finish(wish);
                    helper.succeed();
                }
            }
        });
    }

    private static List<Mob> carriers(ServerLevel level, String species) {
        List<Mob> out = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            if (e instanceof Mob m && m.isAlive()) {
                var sp = DisplayRig.speciesOn(m);
                if (sp != null && species.equals(sp.id())) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    private static List<Mob> previews(ServerLevel level, String species) {
        List<Mob> out = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            if (e instanceof Mob m && m.entityTags().contains(WishShimmer.PREVIEW_TAG)) {
                var sp = DisplayRig.speciesOn(m);
                if (sp != null && species.equals(sp.id())) {
                    out.add(m);
                }
            }
        }
        return out;
    }
}
