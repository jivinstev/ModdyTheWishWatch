package com.moddingfromamod.agent;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntitySpawnReason;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.tools.LookAroundTool;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildRegistry;
import com.moddingfromamod.mob.EntityHandles;
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
 * Gate B for "tell the agent what's actually around it".
 *
 * <p>Two things were invisible and both produced bad behaviour. The agent could not see nearby
 * mobs at all — its only way to discover them was to make a <b>failing</b> {@code modify_mob} call
 * and read the error — and it could not see their current size, which matters because writes are
 * absolute: "make it bigger" is only answerable if you know how big it is now. Buildings were
 * missing entirely, so "make the castle bigger" arrived with a list containing no castles.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class SurroundingsGameTest {

    private static LivingEntity spawnCow(GameTestHelper helper, double scale) {
        ServerLevel level = helper.getLevel();
        LivingEntity cow = EntityTypes.COW.create(level, EntitySpawnReason.COMMAND);
        BlockPos at = helper.absolutePos(new BlockPos(2, 2, 2));
        cow.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        var inst = cow.getAttribute(Attributes.SCALE);
        if (inst != null) {
            inst.setBaseValue(scale);
        }
        level.addFreshEntity(cow);
        return cow;
    }

    /** A nearby mob is listed, with the handle to target it and its CURRENT size. */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void nearbyMobsAreListedWithTheirHandleAndCurrentSize(GameTestHelper helper) {
        LivingEntity cow = spawnCow(helper, 3.0);
        String handle = EntityHandles.global().remember(cow.getUUID(), "minecraft:cow", "Daisy");

        String report = Surroundings.describe(helper.getLevel(), cow.position());

        helper.assertTrue(report.contains("minecraft:cow"), Component.literal("the mob should be listed: " + report));
        helper.assertTrue(report.contains(handle),
                Component.literal("with the handle that targets it exactly: " + report));
        helper.assertTrue(report.contains("size ×3"),
                Component.literal("and its CURRENT size, so 'bigger' is answerable: " + report));
        helper.assertTrue(report.contains("health"), Component.literal("and its health: " + report));
        helper.succeed();
    }

    /**
     * <b>Every field {@code modify_mob} can WRITE is readable first.</b>
     *
     * <p>It takes absolute numbers, so an unreadable field turns its refinement into a guess — and
     * "make him faster" answered with a guess below the current speed makes the mob <b>slower</b>
     * while the wish said faster. That is not an unanswered wish, it is one answered backwards,
     * and nothing in the world or the log says so.
     *
     * <p>Size and health were reported; speed, damage, invulnerability and the mob's existing
     * effects were not, which is four of the seven writable fields.
     */
    /**
     * An event he has left behind is in the list, with how to put it back (E7): "put
     * everything back" arrived with a sinkhole still open and the agent could not see it.
     */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void anEventLeftBehindIsListedWithHowToPutItBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        String id = "surroundings_crater_" + level.getGameTime();
        com.moddingfromamod.event.EventDirector.start(level, id, "", com.moddingfromamod.event.EventScale.LITTLE,
                com.moddingfromamod.event.EventGuard.Policy.TERRAIN, false, (w, age) -> true);
        String report = Surroundings.describe(level, helper.absoluteVec(new net.minecraft.world.phys.Vec3(2, 2, 2)));
        com.moddingfromamod.event.EventDirector.undo(level.getServer(), id);
        helper.assertTrue(report.contains(id), Component.literal("the event should be listed: " + report));
        helper.assertTrue(report.contains("remove_event"), Component.literal("with how to put it back: " + report));
        helper.succeed();
    }

    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void everyFieldModifyMobCanWriteIsReadableFirst(GameTestHelper helper) {
        LivingEntity cow = spawnCow(helper, 1.0);
        cow.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                net.minecraft.world.effect.MobEffects.SPEED, -1, 2));

        String report = Surroundings.describe(helper.getLevel(), cow.position());

        helper.assertTrue(report.contains("size ×"), Component.literal("size: " + report));
        helper.assertTrue(report.contains("health "), Component.literal("health: " + report));
        helper.assertTrue(report.contains("speed "),
                Component.literal("SPEED — 'make him faster' is a guess without it: " + report));
        helper.assertTrue(report.contains("damage "),
                Component.literal("DAMAGE — 'make him hit harder' is a guess without it: " + report));
        // LEVEL AS A CHILD COUNTS IT. Amplifier 2 is Speed III, and reporting the raw 2 would make
        // every "make it stronger" land one level low.
        helper.assertTrue(report.contains("already has:")
                        && report.contains("minecraft:speed 3"),
                Component.literal("the effects it ALREADY carries, at the level a child counts — this is #191's "
                        + "sentence with a mob as the subject: " + report));
        helper.succeed();
    }

    /**
     * A mob with no attack attribute says so rather than crashing.
     *
     * <p>`getAttributeValue` THROWS on an attribute the entity does not have, and a cow has no
     * ATTACK_DAMAGE — so the obvious version of the line above takes down every `look_around` call
     * made near a farm animal. A dash is also the honest answer: `modify_mob` exposes `damage`, and
     * writing it to this mob would do nothing.
     */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void aMobWithNoAttackAttributeIsReportedNotCrashedOn(GameTestHelper helper) {
        LivingEntity cow = spawnCow(helper, 1.0);
        String report = Surroundings.describe(helper.getLevel(), cow.position());
        helper.assertTrue(report.contains("damage —"),
                Component.literal("a cow has no ATTACK_DAMAGE, so it reads as a dash: " + report));
        helper.succeed();
    }

    /** A mob nobody registered is still listed — the agent shouldn't be blind to it. */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void mobsWithoutAHandleAreStillVisible(GameTestHelper helper) {
        LivingEntity cow = spawnCow(helper, 1.0);
        String report = Surroundings.describe(helper.getLevel(), cow.position());
        helper.assertTrue(report.contains("minecraft:cow"), Component.literal("still listed: " + report));
        helper.assertTrue(report.contains("no handle"),
                Component.literal("and says how to target it instead: " + report));
        helper.succeed();
    }

    /** Saved buildings appear alongside the mobs, with the id needed to change one. */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void savedBuildingsAreListedWithTheIdThatEditsThem(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        BuildRegistry.put(server, BuildPlan.fromJson(JsonParser.parseString("""
            {"id":"sur_castle","name":"Floating Castle","shapes":[
              {"kind":"box","width":8,"height":8,"depth":8,"block":"minecraft:stone_bricks"}]}
            """).getAsJsonObject()));
        BuildRegistry.rememberAnchor("sur_castle", BlockPos.ZERO.asLong());

        String report = Surroundings.describe(helper.getLevel(),
                net.minecraft.world.phys.Vec3.atCenterOf(helper.absolutePos(new BlockPos(1, 2, 1))));

        helper.assertTrue(report.contains("sur_castle"),
                Component.literal("the castle must be referenceable: " + report));
        helper.assertTrue(report.contains("Floating Castle"), Component.literal("by name too: " + report));
        helper.assertTrue(report.contains("build_structure"),
                Component.literal("and say HOW to change it: " + report));
        helper.assertTrue(report.contains("standing at"),
                Component.literal("and where it stands: " + report));
        helper.succeed();
    }

    /**
     * <b>What he is HOLDING, with the numbers inside it.</b>
     *
     * <p>The measured gap. This section did not exist, and on the item-effects ladder a worker
     * that had not made the wand answered <i>"can it set them on fire too"</i> without calling
     * {@code inspect_item} at all — restating every parameter from memory and putting the freeze
     * back to 5 seconds after an earlier wish had set it to 30. Three cells of three, identically,
     * and the child keeps a wand that looks exactly right.
     *
     * <p>The NUMBERS are asserted, not just the id. A list of names would have changed nothing:
     * the agent knew the wand existed and knew what it did. What it could not know was the 30.
     */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void heldItemsAreListedWithTheNumbersInsideThem(GameTestHelper helper) {
        String id = "sur_frazzlepop_" + helper.getLevel().getGameTime();
        com.google.gson.JsonObject params = JsonParser.parseString(
                "{\"freeze\":{\"SECONDS\":\"30\"},\"ignite\":{\"SECONDS\":\"6\"}}")
                .getAsJsonObject();
        com.moddingfromamod.item.ItemSpecStore.put(
                new com.moddingfromamod.item.ItemSpecStore.Spec(id, "Frazzlepop", "wand",
                        "light_blue", "held", 1, java.util.List.of("freeze", "ignite"), params));

        String report = Surroundings.describe(helper.getLevel(),
                net.minecraft.world.phys.Vec3.atCenterOf(helper.absolutePos(new BlockPos(1, 2, 1))));

        helper.assertTrue(report.contains(id), Component.literal("the wand must be referenceable: " + report));
        helper.assertTrue(report.contains("Frazzlepop"), Component.literal("by name too: " + report));
        helper.assertTrue(report.contains("make_item"), Component.literal("and say HOW to change it: " + report));
        // THE NUMBERS. Without these the section is a list of things he owns, which is not the
        // thing that was missing.
        helper.assertTrue(report.contains("freeze SECONDS=30"),
                Component.literal("the CURRENT freeze must be readable, or a stranger can only guess it: " + report));
        helper.assertTrue(report.contains("ignite SECONDS=6"),
                Component.literal("every effect's numbers, not just the first: " + report));
        // …and the instruction that turns knowing into keeping. The buildings section already
        // teaches that the list is not the fix; the sentence under it is.
        helper.assertTrue(report.contains("leave it out"),
                Component.literal("it must say that leaving a parameter out KEEPS it — the agent that broke this "
                        + "sent one again from memory: " + report));
        helper.succeed();
    }

    /**
     * <b>The rules already running, and how to change one without losing the rest.</b>
     *
     * <p>The measured gap, and the second attempt at it. A read-back tool was tried first —
     * {@code list_datapacks <feature_id>} returns the current script — and the agent CALLED it and
     * still dropped a clause: 1982 characters to 1479 losing the night condition. Availability is
     * not preservation. What fixed the same problem for items was putting the facts in the handover
     * unasked with the rule beside them, so this is that.
     *
     * <p>The SIZE is asserted for a specific reason: the failure is a script getting smaller, and a
     * number the agent can compare against what it is about to send is worth more than a warning.
     */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void runningRulesAreListedWithTheirSizeAndHowToChangeOne(GameTestHelper helper) {
        String id = "sur_rule_" + helper.getLevel().getGameTime();
        try {
            java.nio.file.Path dir = com.moddingfromamod.datapack.DatapackStore.featureDir(id);
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.writeString(
                    com.moddingfromamod.datapack.DatapackStore.sourceFor(id),
                    "function rain()\n    /say only at night\nend\n");
            // AND A ZIP. listIds() lists a feature only once it has a compiled zip, which is
            // correct — a feature that never compiled is not a rule running in his world — and it
            // means staging only the source produces a fixture the code under test cannot see.
            // This test failed on exactly that first.
            java.nio.file.Files.write(
                    com.moddingfromamod.datapack.DatapackStore.zipFor(id), new byte[] {0});
        } catch (java.io.IOException e) {
            helper.fail(Component.literal("could not stage a rule: " + e));
            return;
        }

        String report = Surroundings.describe(helper.getLevel(),
                net.minecraft.world.phys.Vec3.atCenterOf(helper.absolutePos(new BlockPos(1, 2, 1))));

        helper.assertTrue(report.contains(id), Component.literal("the rule must be referenceable: " + report));
        helper.assertTrue(report.contains("characters of script"),
                Component.literal("its SIZE must be shown — the failure this exists for is a script that shrank: "
                        + report));
        helper.assertTrue(report.contains("WHOLE program"),
                Component.literal("it must say cbscript is the whole program and not a patch: " + report));
        helper.assertTrue(report.contains("stops happening"),
                Component.literal("…and what the child loses when it is not, or the instruction is just procedure: "
                        + report));
        helper.assertTrue(report.contains("runs alongside"),
                Component.literal("and that a NEW id does not replace the old rule — two of three stranger cells "
                        + "ended with two packs firing and nothing showing it: " + report));
        helper.succeed();
    }

    /** The report is always well-formed, whatever happens to be around. */
    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void anEmptyWorldReadsAsEmptyNotBroken(GameTestHelper helper) {
        String report = Surroundings.describe(helper.getLevel(),
                net.minecraft.world.phys.Vec3.atCenterOf(helper.absolutePos(new BlockPos(1, 40, 1))));
        // Asserting the SHAPE, not emptiness: other tests in this batch save buildings and spawn
        // mobs concurrently, so "the world is empty" is not a claim this test can make.
        helper.assertTrue(report.startsWith("Things you can refer to"), Component.literal("header: " + report));
        helper.assertTrue(report.contains("MOBS"), Component.literal("a mobs section: " + report));
        helper.assertTrue(report.contains("BUILDINGS"), Component.literal("a buildings section: " + report));
        helper.assertTrue(report.contains("RULES"),
                Component.literal("…and a rules section. Without it a worker is told about mobs, buildings and what "
                        + "he is holding, and nothing about the datapacks already running in his "
                        + "world: " + report));
        helper.assertTrue(report.contains("ITEMS"),
                Component.literal("…and an items section. It had none until 2026-08-18, so a worker was told what "
                        + "mobs and buildings were around and nothing about the thing in the "
                        + "child's hand: " + report));
        helper.succeed();
    }

    /** The real tool must carry the report, not just the helper. */
    @GameTest(template = "empty_test", timeoutTicks = 2000)
    public static void lookAroundCarriesTheReport(GameTestHelper helper) {
        spawnCow(helper, 1.0);
        AtomicReference<ToolResult> reply = new AtomicReference<>();
        Thread t = new Thread(() -> reply.set(new LookAroundTool().call(new JsonObject())),
                "surroundings-gametest");
        t.setDaemon(true);
        t.start();

        helper.succeedWhen(() -> {
            helper.assertTrue(reply.get() != null, Component.literal("waiting for look_around"));
            String text = reply.get().text();
            // With no player online look_around reports that; either way it must not error.
            helper.assertFalse(reply.get().isError(), Component.literal("look_around should not error: " + text));
            helper.assertTrue(text.contains("Things you can refer to") || text.contains("Nobody"),
                    Component.literal("look_around must carry the surroundings report: " + text));
        });
    }
}
