package com.moddingfromamod.mob;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntitySpawnReason;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.ModifyMobTool;
import com.moddingfromamod.entity.ModEntities;
import com.moddingfromamod.entity.WishMobEntity;
import com.moddingfromamod.testing.RigProbe;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * Gate B for <b>things a seven-year-old would reasonably expect to work</b>.
 *
 * <p>The individual features are tested elsewhere. What this file is for is the <b>seams between
 * them</b> — where a thing made by one path is then changed by another. Those are where the bugs
 * are, because each side was written and tested on its own: a creature summoned by the offline
 * template engine has never met the agent that is now being asked to make it bigger.
 *
 * <p>Every test here is phrased as something he might actually say.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class ChildExpectationsGameTest {

    /**
     * Fire modify_mob off the server thread and poll for the answer.
     *
     * <p><b>Never join() this.</b> A GameTest body runs ON the server thread, and the tool hops
     * back onto that same thread through AgentBridge — so waiting for it here deadlocks, times out
     * after five seconds, and starves every other test in the batch as well. An earlier version of
     * this file did exactly that and took four unrelated tests down with it.
     *
     * @param calls one or more argument objects, run in sequence on the background thread
     */
    private static AtomicReference<ToolResult> modifyAsync(String... calls) {
        AtomicReference<ToolResult> out = new AtomicReference<>();
        Thread t = new Thread(() -> {
            ToolResult last = null;
            for (String json : calls) {
                JsonObject args = JsonParser.parseString(json).getAsJsonObject();
                last = new ModifyMobTool().call(args);
            }
            out.set(last);
        }, "child-expectations");
        t.setDaemon(true);
        t.start();
        return out;
    }

    /**
     * ⚠ <b>The returned entity is NOT what stands in the world.</b>
     *
     * <p>{@code VanillaRigBridge} is total: this mob is cancelled as it joins and a display rig on
     * a vanilla carrier is spawned at the end of the tick. The object handed back was never in the
     * level — and its attributes still answer, so an assertion on it passes whatever happened.
     * Everything below therefore asserts through the HANDLE ({@link RigProbe}), which is also the
     * only thing the agent has.
     */
    /**
     * <b>A named batch, because an edit here must be made by NOBODY in particular.</b>
     *
     * <p>{@code modify_mob} asks {@code AgentContext.currentRun()} whose wish this is, and with no
     * worker bound that falls back to "the single wish in flight" — which, in the shared default
     * batch, was another test's wish. With an asker bound, {@code CreatureFork} forks the creature
     * for that child instead of editing it: measured 2026-09-27 in a tier run, <i>"forked creature
     * 'ce_robot' as 'ce_robot~ec1d1bc9' for the child who asked"</i>, and the design this test reads
     * stayed at 1.0 (2 of 6 branch tier runs; 1 of 470 on main before). Batches run one after
     * another, so a named one has no other test's wish in flight. None of these tests submit one.
     */
    private static final String BATCH = "child_expectations";

    private static WishMobEntity spawnWishMob(GameTestHelper helper, String speciesJson, int x) {
        ServerLevel level = helper.getLevel();
        Species s = Species.fromJson(JsonParser.parseString(speciesJson).getAsJsonObject());
        com.moddingfromamod.testing.SpeciesForTest.putSaved(helper, s);
        WishMobEntity mob = ModEntities.WISH_MOB.get().create(level, EntitySpawnReason.COMMAND);
        BlockPos at = helper.absolutePos(new BlockPos(x, 2, 3));
        mob.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        mob.setSpeciesId(s.id());
        level.addFreshEntity(mob);
        return mob;
    }

    /**
     * <b>An edit that does not start until the creature is actually standing there.</b>
     *
     * <p>{@code VanillaRigBridge} manifests a wished creature on the server's own queue at the END
     * of the tick it joins in, so for one tick the handle names a mob that has been cancelled and a
     * carrier that does not exist yet. Firing {@code modify_mob} inside that window answers
     * <i>"… is gone — it died or unloaded"</i>, which is the truth: there is nothing to edit yet.
     *
     * <p>An agent never meets that window — {@code define_creature} and {@code modify_mob} are
     * separate MCP turns, seconds apart — so closing it in the tool would be adding a wait to
     * production for a race only a test can lose. Waiting here is both cheaper and more honest.
     */
    private static final class Edit {

        private final String handle;
        private final String[] calls;
        private AtomicReference<ToolResult> reply;

        Edit(String handle, String... calls) {
            this.handle = handle;
            this.calls = calls;
        }

        /** @return the agent's answer, or {@code null} while we are still waiting for either */
        ToolResult poll(GameTestHelper helper) {
            if (reply == null) {
                if (RigProbe.bodyFor(helper.getLevel(), handle) == null) {
                    return null;
                }
                reply = modifyAsync(calls);
            }
            return reply.get();
        }
    }

    /** The design this creature is built from — path-independent, and what an edit must move. */
    private static double designScale(String speciesId) {
        Species saved = SpeciesRegistry.get(speciesId);
        return saved == null ? -1 : saved.scale();
    }

    /**
     * <b>"I wished for a robot, now make it bigger."</b>
     *
     * <p>The creature was made by the OFFLINE template engine with no agent involved; the edit
     * comes from the agent. The two have never met, and the only thing joining them is the handle
     * the offline path registered. This is the seam the whole handle mechanism exists to close.
     */
    @GameTest(template = "empty_test", timeoutTicks = 300, batch = BATCH)
    public static void aCreatureMadeOfflineCanBeEditedByTheAgent(GameTestHelper helper) {
        WishMobEntity mob = spawnWishMob(helper,
                "{\"id\":\"ce_robot\",\"name\":\"Robot\",\"scale\":1.0,\"health\":30}", 3);
        // Exactly what WishExecutor does after an offline command creates something.
        String handle = EntityHandles.global().remember(mob.getUUID(),
                "moddingfromamod:wish_mob", "Robot");
        helper.assertTrue(handle.startsWith("@"), Component.literal("the offline path must hand out a handle"));

        var edit = new Edit(handle,
                "{\"scope\":\"one\",\"target\":\"" + handle + "\",\"scale\":4.0}");
        helper.succeedWhen(() -> {
            ToolResult reply = edit.poll(helper);
            helper.assertTrue(reply != null, Component.literal("waiting for the creature, then the agent's edit -- "
                    + RigProbe.describe(helper.getLevel(), handle)));
            helper.assertFalse(reply.isError(),
                    Component.literal("the agent should be able to edit it: " + reply.text()));
            // GROWS, not "grows to exactly what was asked". CreatureGrowth caps one step at
            // 1.6x, because a live "make it bigger" once took a shark from 2.75 to 12 blocks. The
            // child's expectation is that saying it DID something, and that is what is asserted.
            //
            // ⚠ THIS IS THE ASSERTION THAT CAUGHT A LIVE BUG. It used to read
            // the SCALE attribute off `mob`, an object the bridge had already replaced, and the
            // real failure was one step earlier: the handle minted from the wish mob's UUID named
            // a dead entity one tick later, so the agent was answered "it is gone -- it died or
            // unloaded". `EntityHandles.repoint` is the fix; this is what proves it.
            helper.assertTrue(RigProbe.bodyFor(helper.getLevel(), handle) != null,
                    Component.literal("the handle the offline path handed out must still name something standing"));
            helper.assertTrue(designScale("ce_robot") > 1.0,
                    Component.literal("the offline-made creature must grow, its design was "
                            + designScale("ce_robot") + " — modify_mob said: " + reply.text()));
        });
    }

    /** ...and the edit must have reached the SAVED design, so the next robot is big too. */
    @GameTest(template = "empty_test", timeoutTicks = 300, batch = BATCH)
    public static void editingAnOfflineCreatureTeachesTheDesign(GameTestHelper helper) {
        WishMobEntity mob = spawnWishMob(helper,
                "{\"id\":\"ce_teach\",\"name\":\"Bot\",\"scale\":1.0}", 3);
        String handle = EntityHandles.global().remember(mob.getUUID(),
                "moddingfromamod:wish_mob", "Bot");
        var edit = new Edit(handle,
                "{\"scope\":\"one\",\"target\":\"" + handle + "\",\"scale\":5.0}");
        helper.succeedWhen(() -> {
            helper.assertTrue(edit.poll(helper) != null,
                    Component.literal("waiting -- " + RigProbe.describe(helper.getLevel(), handle)));
            Species saved = SpeciesRegistry.get("ce_teach");
            helper.assertTrue(saved != null && saved.scale() > 1.0,
                    Component.literal("the DESIGN must have learned it, was "
                            + (saved == null ? "missing" : saved.scale())));
        });
    }

    /**
     * <b>"Make it bigger... no, BIGGER."</b> A second edit must actually do something. Two edits
     * that both resolve to the same absolute value would leave him saying it twice and seeing
     * nothing happen the second time.
     */
    @GameTest(template = "empty_test", timeoutTicks = 300, batch = BATCH)
    public static void asecondEditGoesFurtherThanTheFirst(GameTestHelper helper) {
        WishMobEntity mob = spawnWishMob(helper, "{\"id\":\"ce_twice\",\"scale\":1.0}", 3);
        String handle = EntityHandles.global().remember(mob.getUUID(),
                "moddingfromamod:wish_mob", "Bot");
        var edit = new Edit(handle,
                "{\"scope\":\"one\",\"target\":\"" + handle + "\",\"scale\":3.0}",
                "{\"scope\":\"one\",\"target\":\"" + handle + "\",\"scale\":6.0}");
        helper.succeedWhen(() -> {
            helper.assertTrue(edit.poll(helper) != null, Component.literal("waiting for both edits -- "
                    + RigProbe.describe(helper.getLevel(), handle)));
            // The point is that the SECOND one moved it further, not that it reached 6.0 --
            // CreatureGrowth caps a single step, so "no, BIGGER" is answered by another step
            // rather than by a leap. One step from 1.0 lands at MAX_STEP; two must land beyond it.
            //
            // Read off the DESIGN rather than off an entity: on the rig path the creature's size
            // lives in the rig's geometry, and the carrier's own `minecraft:scale` is `RigBase`'s
            // fitting factor -- a horse stretched to fit, i.e. a fact about the carrier.
            helper.assertTrue(designScale("ce_twice")
                            > com.moddingfromamod.runtime.CreatureGrowth.MAX_STEP + 0.001,
                    Component.literal("the second edit must stick, was " + designScale("ce_twice")));
            helper.assertTrue(RigProbe.bodyFor(helper.getLevel(), handle) != null,
                    Component.literal("and the handle must survive the rebuild the first edit caused -- a rebuild "
                            + "discards the carrier, so \"no, BIGGER\" would address a dead one"));
        });
    }

    /**
     * <b>"Make that zombie bigger."</b> A vanilla mob has no design of ours to write to, so the
     * change is only ever to that one animal. The agent must be told that, or it will promise the
     * child something that isn't true.
     */
    @GameTest(template = "empty_test", timeoutTicks = 300, batch = BATCH)
    public static void editingAVanillaMobIsHonestAboutNotBeingSaved(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        LivingEntity zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
        BlockPos at = helper.absolutePos(new BlockPos(4, 2, 4));
        zombie.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        level.addFreshEntity(zombie);
        String handle = EntityHandles.global().remember(zombie.getUUID(), "minecraft:zombie", "Zombie");

        var reply = modifyAsync("{\"scope\":\"one\",\"target\":\"" + handle + "\",\"scale\":3.0}");
        helper.succeedWhen(() -> {
            helper.assertTrue(reply.get() != null, Component.literal("waiting"));
            helper.assertFalse(reply.get().isError(), Component.literal("it should still work on that one zombie"));
            helper.assertFalse(reply.get().text().contains("every one of these"),
                    Component.literal("must NOT claim it was saved to a design — there is none: " + reply.get().text()));
            helper.assertTrue(zombie.getAttributeValue(Attributes.SCALE) == 3.0,
                    Component.literal("the zombie itself should still have grown"));
        });
    }

    /** <b>"Make it bigger"</b> with nothing around must be a kind message, never a crash. */
    @GameTest(template = "empty_test", timeoutTicks = 300, batch = BATCH)
    public static void askingToChangeNothingFailsKindly(GameTestHelper helper) {
        var reply = modifyAsync("{\"scope\":\"one\",\"target\":\"@nothing_at_all\",\"scale\":2.0}");
        helper.succeedWhen(() -> {
            helper.assertTrue(reply.get() != null, Component.literal("waiting"));
            helper.assertTrue(reply.get().isError(), Component.literal("an unknown handle should be an error"));
            helper.assertTrue(reply.get().text().toLowerCase().contains("handle"),
                    Component.literal("and should explain what a handle is: " + reply.get().text()));
        });
    }

    /** A handle whose creature has died must say so plainly rather than silently doing nothing. */
    @GameTest(template = "empty_test", timeoutTicks = 300, batch = BATCH)
    public static void ahandleForSomethingDeadSaysSo(GameTestHelper helper) {
        String handle = EntityHandles.global().remember(UUID.randomUUID(),
                "minecraft:zombie", "Ghost Zombie");
        var reply = modifyAsync("{\"scope\":\"one\",\"target\":\"" + handle + "\",\"scale\":2.0}");
        helper.succeedWhen(() -> {
            helper.assertTrue(reply.get() != null, Component.literal("waiting"));
            helper.assertTrue(reply.get().isError(), Component.literal("should be an error"));
            helper.assertTrue(reply.get().text().contains("gone"),
                    Component.literal("and should say it's gone: " + reply.get().text()));
        });
    }

    /** <b>"Make it a MILLION times bigger!"</b> — clamped, not refused, and never a broken world. */
    @GameTest(template = "empty_test", timeoutTicks = 300, batch = BATCH)
    public static void anAbsurdRequestIsClampedNotRefused(GameTestHelper helper) {
        WishMobEntity mob = spawnWishMob(helper, "{\"id\":\"ce_huge\",\"scale\":1.0}", 3);
        String handle = EntityHandles.global().remember(mob.getUUID(),
                "moddingfromamod:wish_mob", "Bot");
        var edit = new Edit(handle,
                "{\"scope\":\"one\",\"target\":\"" + handle + "\",\"scale\":1000000}");
        helper.succeedWhen(() -> {
            ToolResult reply = edit.poll(helper);
            helper.assertTrue(reply != null,
                    Component.literal("waiting -- " + RigProbe.describe(helper.getLevel(), handle)));
            helper.assertFalse(reply.isError(),
                    Component.literal("asking for a million should give the biggest allowed, not an error"));
            // Clamped to what is REASONABLE, which is one growth step rather than the
            // absolute ceiling: a million is not a size, and the honest answer to it is the same
            // as the honest answer to "bigger". Still not an error, which is the actual contract
            // here -- a child asking for a million must not be told no.
            double got = designScale("ce_huge");
            helper.assertTrue(got > 1.0 && got <= MobTweak.MAX_SCALE,
                    Component.literal("should be clamped to something sane, was " + got));
        });
    }

    /**
     * <b>"Make the castle bigger."</b> Buildings must be referenceable too, or the agent has
     * nothing to point at and falls back to guessing about mobs.
     */
    @GameTest(template = "empty_test", timeoutTicks = 200, batch = BATCH)
    public static void buildingsAreReferenceableToo(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        com.moddingfromamod.builder.BuildRegistry.put(server,
                com.moddingfromamod.builder.BuildPlan.fromJson(JsonParser.parseString("""
                    {"id":"ce_castle","name":"Castle","shapes":[
                      {"kind":"box","width":4,"height":4,"depth":4,"block":"minecraft:stone"}]}
                    """).getAsJsonObject()));
        helper.assertTrue(com.moddingfromamod.builder.BuildRegistry.ids().contains("ce_castle"),
                Component.literal("a saved building must be findable by id so the agent can edit it"));
        helper.assertTrue(com.moddingfromamod.builder.BuildRegistry.get("ce_castle") != null,
                Component.literal("and retrievable"));
        helper.succeed();
    }
}
