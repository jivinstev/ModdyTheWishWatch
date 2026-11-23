package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.util.UUID;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildRegistry;
import com.moddingfromamod.marshal.WishLadder;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * Gate B for the world index — <b>does it tell the truth about the world?</b>
 *
 * <p>The ladder that used to read it was deleted; the index now feeds the FACTS the agent
 * reads (what is in his view, what he talked about last). A wrong distance or a wrong "in view"
 * would make those facts lie, which is worse than saying nothing.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class WishLadderGameTest {

    private static BuildPlan plan(String id) {
        return BuildPlan.fromJson(JsonParser.parseString("""
            {"id":"%s","name":"Ladder test","shapes":[
              {"kind":"box","width":2,"height":2,"depth":2,"block":"minecraft:stone"}]}
            """.formatted(id)).getAsJsonObject());
    }

    /** The registries really do surface as candidates the ladder can use. */
    @GameTest(template = "empty_test")
    public static void aBuildingHeOwnsBecomesACandidate(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        String id = "gt_ladder_castle_" + helper.getLevel().getGameTime();
        BuildRegistry.put(server, plan(id));
        BuildRegistry.rememberAnchor(id, helper.absolutePos(new BlockPos(1, 1, 1)).asLong(),
                helper.getLevel().dimension());

        helper.assertTrue(BuildRegistry.ids().contains(id),
                Component.literal("a building he owns must be visible to the index, or the ladder can never mean it"));
        helper.assertTrue(BuildRegistry.anchorOf(id) != null,
                Component.literal("and it must know WHERE, or every distance rung is guessing"));
        helper.succeed();
    }

    /**
     * <b>The rung that needs no question at all.</b> A wish in flight that has made something marks
     * that thing as in-flight, so "make it bigger" folds into the wish still being granted.
     */
    @GameTest(template = "empty_test")
    public static void whatWeAreAlreadyMakingIsMarkedAsSuch(GameTestHelper helper) {
        UUID me = UUID.nameUUIDFromBytes("gt-ladder-inflight".getBytes());
        UUID wishId = UUID.randomUUID();
        com.moddingfromamod.marshal.Marshal.global().submit(wishId, me, "make me a dragon");
        var run = com.moddingfromamod.marshal.Marshal.global().assign("gt-ladder-w1", wishId);
        run.made(com.moddingfromamod.marshal.MadeKind.CREATURE, "gt_ladder_dragon");

        helper.assertTrue(run.made().size() == 1, Component.literal("the run knows what it made"));
        helper.assertTrue(run.made().get(0).id().equals("gt_ladder_dragon"),
                Component.literal("…and by name, so the index can match it against a candidate"));
        com.moddingfromamod.marshal.Marshal.global().finish(wishId);
        helper.succeed();
    }

    /** Stickiness is a fact about the conversation and must survive between wishes. */
    @GameTest(template = "empty_test")
    public static void whatHeIsTalkingAboutIsRemembered(GameTestHelper helper) {
        UUID me = UUID.nameUUIDFromBytes("gt-ladder-sticky".getBytes());
        WishTopic.forget(me);
        helper.assertTrue(WishTopic.lastOf(me) == null, Component.literal("nothing yet"));
        WishTopic.remember(me, "gt_ladder_dragon");
        helper.assertTrue("gt_ladder_dragon".equals(WishTopic.lastOf(me))
                && WishTopic.ageMs(me) >= 0, Component.literal("what his wish touched is remembered, with its age"));
        WishTopic.forget(me);
        helper.succeed();
    }

    /**
     * A referring wish skips the offline templates — the one routing decision still made locally,
     * because a fixed list of commands cannot know what "it" is. What it MEANS is the agent's.
     */
    @GameTest(template = "empty_test")
    public static void aReferringWishIsTheThingThatTriggersTheLadder(GameTestHelper helper) {
        helper.assertTrue(WishReference.isContextual("make it bigger"),
                Component.literal("'make it bigger' must classify as contextual, or a template answers it"));
        helper.assertTrue(WishReference.isContextual("add a lighthouse on top of it"),
                Component.literal("the real wish from the log that motivated rung 0"));
        helper.assertFalse(WishReference.isContextual("build me a castle"),
                Component.literal("a brand-new wish stays cacheable"));
        helper.succeed();
    }

    /** Every kind the index can produce has a name the child would recognise. */
    @GameTest(template = "empty_test")
    public static void everyKindTheIndexProducesCanBeNarrated(GameTestHelper helper) {
        for (String kind : new String[]{"species", "build", "item", "event"}) {
            var candidate = new WishLadder.Candidate("thing_one", kind, "thing one",
                    java.util.List.of(), 5, false, 0L, true);
            String said = WishLadder.narrate(candidate);
            helper.assertFalse(said.isBlank(), Component.literal(kind + " cannot be named out loud, so a plan "
                    + "about it could not say what it is about"));
            helper.assertTrue(said.contains("thing one"), Component.literal(kind + " narrated as: " + said));
        }
        helper.succeed();
    }
}
