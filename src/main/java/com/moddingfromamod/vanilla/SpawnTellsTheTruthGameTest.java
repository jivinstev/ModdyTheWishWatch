package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.DefineSpeciesTool;
import com.moddingfromamod.agent.tools.FinishWishTool;
import com.moddingfromamod.entity.WishMobTags;
import com.moddingfromamod.marshal.MadeKind;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.mob.EntityCapture;
import com.moddingfromamod.mob.EntityHandles;
import com.moddingfromamod.runtime.CreatureFiles;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.wish.BuildGate;
import com.moddingfromamod.wish.Wish;
import com.moddingfromamod.wish.WishBook;
import com.moddingfromamod.wish.WishExecutor;
import com.moddingfromamod.wish.WishProps;
import com.moddingfromamod.wish.WishSource;
import com.moddingfromamod.wish.WishStatus;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>A spawn reports what is standing there ().</b>
 *
 * <p>Every spawn site counted {@code addFreshEntity(wishMob)} returning true. Since the rig
 * bridge cancels that join and builds the creature a tick later, so the answer was 0 every time
 * while a creature arrived: production read <i>"✨ Spawned 0× Spikor"</i> for every creature
 * (2026-09-25), the agent spawned him again, and {@code define_creature} never handed out a
 * handle at all. The mirror image,: a wish whose creature genuinely was not there still ended
 * DONE — <i>"I can't see the sea creature you made"</i>.
 *
 * <p>Each test is in its own named batch: they bind a wish run, and {@code AgentContext} falls
 * back to "the single wish in flight", which another test's wish would make ambiguous.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class SpawnTellsTheTruthGameTest {

    // =====================: wishmob spawn =====================

    @GameTest(template = "empty_test", batch = "t6g2_command", timeoutTicks = 400)
    public static void wishmobSpawnCountsTheCreaturesReallyStandingThere(GameTestHelper helper) {
        String id = "gt_t6g2_cmd";
        ServerLevel level = helper.getLevel();
        plantSculpted(helper, id);
        ServerPlayer mock = MockPlayerNetwork.inLevel(helper);
        try {
            // run_command's own diff and prop ledger, around run_command's own executor.
            UUID player = mock.getUUID();
            Wish wish = WishBook.get(level.getServer()).add(Wish.create("give me two " + id,
                    WishSource.TYPED, System.currentTimeMillis(), player, "Steve",
                    "minecraft:overworld", 0, 64, 0));
            Marshal.global().submit(wish.id(), player, wish.text());
            var run = Marshal.global().assign("gt-t6g2-cmd", wish.id());
            var before = EntityCapture.snapshot(level, mock.position());
            var beforeAll = WishProps.snapshot(level, mock.position());

            WishExecutor.Result r = WishExecutor.runOne(level.getServer(), level,
                    mock.position(), mock, "wishmob spawn " + id + " 2");
            String said = String.join(" ", r.output());
            int standing = carriers(level, id).size();

            EntityCapture.rememberNew(level, mock.position(), before);
            WishProps.recordNew(level, mock.position(), beforeAll, run);

            // AND FINISHING THE WISH MUST NOT TAKE THEM APART. run_command records what a command
            // spawned so finish_wish can sweep away the test props the wish did not keep. A rig's
            // displays and proxies are unnamed entities, so once the rig exists at the command
            // (which is this fix) they would read as props and be swept, leaving an invisible
            // carrier. Measured: it is exactly what happened before WishProps skipped them.
            WishProps.sweep(level.getServer(), run);
            for (LivingEntity c : carriers(level, id)) {
                helper.assertTrue(c.isAlive(), Component.literal("finish_wish's prop sweep killed the creature"));
                helper.assertTrue(displaysOf(level, c) > 0, Component.literal("finish_wish's prop sweep took the "
                        + "creature's BODY away — its displays were recorded as test props"));
            }
            helper.assertTrue(r.success(), Component.literal("wishmob spawn failed: " + said));
            helper.assertTrue(standing == 2, Component.literal("#939: the command returned and " + standing
                    + " creature(s) are standing — it should have made two, synchronously, so the "
                    + "count it reports is a count of something. It said: " + said));
            helper.assertTrue(said.contains("Spawned 2×"), Component.literal("#939: two creatures are standing "
                    + "there and the command said: " + said));

            Marshal.global().finish(wish.id());
            helper.succeed();
        } finally {
            tidy(level, id);
            MockPlayerNetwork.remove(mock);
        }
    }

    @GameTest(template = "empty_test", batch = "t6g2_unknown", timeoutTicks = 200)
    public static void aSpawnOfNothingIsAFailureTheAgentCanRead(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer mock = MockPlayerNetwork.inLevel(helper);
        try {
            WishExecutor.Result r = WishExecutor.runOne(level.getServer(), level,
                    mock.position(), mock, "wishmob spawn gt_t6g2_no_such_creature 2");
            helper.assertFalse(r.success(), Component.literal("a spawn of a creature that does not exist must come "
                    + "back as a FAILURE (ok:false), not a number: " + r.output()));
            helper.succeed();
        } finally {
            MockPlayerNetwork.remove(mock);
        }
    }

    // =====================: define_creature =====================

    @GameTest(template = "empty_test", batch = "t6g2_define", timeoutTicks = 400)
    public static void defineCreatureCountsWhatStandsAndHandsOutAWorkingHandle(
            GameTestHelper helper) {
        String id = "gt_t6g2_def";
        ServerLevel level = helper.getLevel();
        ServerPlayer mock = MockPlayerNetwork.inLevel(helper);
        UUID player = mock.getUUID();
        Wish wish = WishBook.get(level.getServer()).add(Wish.create("make me a blob",
                WishSource.TYPED, System.currentTimeMillis(), player, "Steve",
                "minecraft:overworld", 0, 64, 0));
        Marshal.global().submit(wish.id(), player, wish.text());
        Marshal.global().assign("gt-t6g2-def", wish.id());

        AtomicReference<ToolResult> reply = new AtomicReference<>();
        offThread(() -> reply.set(AgentContext.with("gt-t6g2-def", () -> new DefineSpeciesTool()
                .call(args("{\"id\":\"" + id + "\",\"name\":\"Blobby\",\"spawn\":true,"
                        + "\"count\":2}")))));

        helper.succeedWhen(() -> {
            ToolResult r = reply.get();
            helper.assertTrue(r != null, Component.literal("waiting for define_creature"));
            try {
                helper.assertFalse(r.isError(), Component.literal("define_creature failed: " + r.text()));
                helper.assertTrue(r.text().contains("Spawned 2"), Component.literal("#939: define_creature made "
                        + carriers(level, id).size() + " creature(s) and said: " + r.text()));
                String handle = handleIn(r.text());
                helper.assertTrue(handle != null, Component.literal("#939: define_creature handed out no handle "
                        + "(it minted one only when addFreshEntity said yes, which is never): "
                        + r.text()));
                var h = EntityHandles.global().resolve(handle);
                helper.assertTrue(h.isPresent() && EntityCapture.find(level, h.get().id()) != null,
                        Component.literal("the handle " + handle + " does not name a creature standing in the world"));
            } finally {
                Marshal.global().finish(wish.id());
                tidy(level, id);
                MockPlayerNetwork.remove(mock);
            }
        });
    }

    // =====================: nothing standing, cannot end DONE =====================

    @GameTest(template = "empty_test", batch = "t6g2_finish", timeoutTicks = 600)
    public static void aWishWhoseCreatureIsNotStandingThereCannotEndDone(GameTestHelper helper) {
        String id = "gt_t6g2_gone";
        ServerLevel level = helper.getLevel();
        ServerPlayer mock = MockPlayerNetwork.inLevel(helper);
        UUID player = mock.getUUID();
        Wish wish = WishBook.get(level.getServer()).add(Wish.create(
                "Make a GIANT sea creature that LEAPS from the sea!", WishSource.TYPED,
                System.currentTimeMillis(), player, "Steve", "minecraft:overworld", 0, 64, 0));
        Marshal.global().submit(wish.id(), player, wish.text());
        var run = Marshal.global().assign("gt-t6g2-fin", wish.id());

        AtomicReference<String> finished = new AtomicReference<>();
        offThread(() -> AgentContext.with("gt-t6g2-fin", () -> {
            new DefineSpeciesTool().call(args("{\"id\":\"" + id + "\",\"name\":\"Sea Leaper\","
                    + "\"spawn\":true,\"count\":1}"));
            run.made(MadeKind.CREATURE, id);
            // …and then it is not there: never found out why its creature was missing (a
            // position in the sea, a box that did not fit). Remove it, a tick later, so the
            // question finish_wish must ask — is the creature this wish spawned STANDING? — has
            // the answer production's had.
            waitTicks(level, 5);
            onServer(level, () -> tidy(level, id));
            JsonObject done = args("{\"verdict\":\"done\",\"note\":\"Your GIANT sea creature is "
                    + "here — it LEAPS right out of the sea!\"}");
            done.addProperty("id", wish.shortId());
            new FinishWishTool().call(done);
            ToolResult second = new FinishWishTool().call(done);
            finished.set(second == null ? "" : second.text());
            return null;
        }));

        helper.succeedWhen(() -> {
            String said = finished.get();
            helper.assertTrue(said != null, Component.literal("waiting for finish_wish"));
            try {
                var status = WishBook.get(level.getServer()).byId(wish.id())
                        .map(Wish::status).orElse(null);
                helper.assertTrue(status != WishStatus.DONE, Component.literal("#855: the creature this wish spawned "
                        + "is standing nowhere, and the wish still ended DONE — the child is told "
                        + "\"it's here\" about nothing. finish_wish said: " + said));
            } finally {
                Marshal.global().finish(wish.id());
                BuildGate.settle(wish.id());
                MockPlayerNetwork.remove(mock);
            }
        });
    }

    // ===================== helpers =====================

    /** A real species with a real sculpt on disk, so `wishmob spawn` knows the model. */
    private static void plantSculpted(GameTestHelper helper, String id) {
        var parts = SpikeFixture.parts(SpikeFixture.SPIKOR);
        try {
            java.nio.file.Path spec = SpikeFixture.find(
                    SpikeFixture.CORPUS_DIR + "/" + SpikeFixture.SPIKOR + "/sculpt.json");
            helper.assertTrue(spec != null, Component.literal("the corpus sculpt is missing"));
            CreatureFiles.write(id, parts, java.nio.file.Files.readString(spec));
        } catch (Exception e) {
            helper.fail(Component.literal("could not write the sculpt: " + e));
        }
        com.moddingfromamod.testing.SpeciesForTest.put(helper, Species.fromJson(
                JsonParser.parseString("{\"id\":\"" + id + "\",\"name\":\"Truthful\","
                        + "\"modelKey\":\"" + id + "\",\"health\":20,\"speed\":0.0,"
                        + "\"scale\":1.0}").getAsJsonObject()));
    }

    private static List<LivingEntity> carriers(ServerLevel level, String id) {
        List<LivingEntity> out = new ArrayList<>();
        for (Entity e : level.getEntities().getAll()) {
            if (e instanceof LivingEntity l && e.isAlive()
                    && e.entityTags().contains(DisplayRig.RIG_TAG)
                    && e.entityTags().contains(WishMobTags.forSpecies(id))) {
                out.add(l);
            }
        }
        return out;
    }

    private static int displaysOf(ServerLevel level, Entity carrier) {
        String owner = DisplayRig.RIG_OWNER_PREFIX + carrier.getUUID();
        int n = 0;
        for (Entity e : level.getEntities().getAll()) {
            if (e instanceof Display && e.entityTags().contains(owner) && !e.isRemoved()) {
                n++;
            }
        }
        return n;
    }

    private static void tidy(ServerLevel level, String id) {
        for (LivingEntity c : carriers(level, id)) {
            DisplayRig.reap(level, c.getUUID());
            c.discard();
        }
    }

    /** The first {@code @handle} in a tool's reply. */
    private static String handleIn(String text) {
        var m = java.util.regex.Pattern.compile("Handle: (@[a-z0-9_]+)").matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static void waitTicks(ServerLevel level, int ticks) {
        long until = level.getGameTime() + ticks;
        long deadline = System.currentTimeMillis() + 10_000;
        while (level.getGameTime() < until && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void onServer(ServerLevel level, Runnable body) {
        var done = new java.util.concurrent.CompletableFuture<Void>();
        level.getServer().execute(() -> {
            try {
                body.run();
            } finally {
                done.complete(null);
            }
        });
        try {
            done.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonObject args(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static void offThread(Runnable work) {
        Thread t = new Thread(work, "t6g2-gametest");
        t.setDaemon(true);
        t.start();
    }
}
