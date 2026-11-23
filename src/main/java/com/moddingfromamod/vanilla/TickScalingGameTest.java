package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.entity.WishMobTags;
import com.moddingfromamod.marshal.ArtifactOwner;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>What the per-tick paths ANSWER, before and after they were made cheap</b> (2026-10-31): the
 * species a carrier is ({@code DisplayRig.speciesOn}, now by index) and whether a live wish is about
 * a creature ({@code RigPowers.stillBeingWished}, now once per tick). Written against the public
 * behaviour only, so the same file passes on the code before the change and after it.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class TickScalingGameTest {

    static Species species(String id) {
        return Species.fromJson(JsonParser.parseString("{\"id\":\"" + id + "\",\"name\":\"" + id
                + "\",\"health\":20,\"speed\":0.0,\"scale\":1.0,\"powers\":[\"FRIENDLY\"]}").getAsJsonObject());
    }

    static Pig carrier(GameTestHelper helper, int x, String... speciesIds) {
        Pig p = helper.spawn(EntityType.PIG, new BlockPos(x, 2, 1));
        p.setNoAi(true);
        p.addTag(DisplayRig.RIG_TAG);
        for (String s : speciesIds) {
            p.addTag(WishMobTags.forSpecies(s));
        }
        return p;
    }

    @GameTest(template = "empty_test", batch = "tickscaling", timeoutTicks = 100)
    public static void speciesOnAnswersAsItAlwaysHas(GameTestHelper helper) {
        String base = "gt_ts_dragon";
        String fork = base + ArtifactOwner.FORK + "00000000-0000-0000-0000-00000000abcd";
        String late = "gt_ts_late";
        List<Pig> made = new ArrayList<>();
        try {
            SpeciesRegistry.withSpeciesForTest(List.of(species(base), species(fork), species("gt_ts_other")), () -> {
                Pig plain = carrier(helper, 1, base);
                Pig forked = carrier(helper, 2, fork, base);       // a fork wears its ancestor's tag too
                Pig unknown = carrier(helper, 3, "gt_ts_nobody");
                Pig notARig = helper.spawn(EntityType.PIG, new BlockPos(4, 2, 1));
                notARig.setNoAi(true);
                notARig.addTag(WishMobTags.forSpecies(base));
                Pig early = carrier(helper, 5, late);
                made.addAll(List.of(plain, forked, unknown, notARig, early));

                helper.assertTrue(base.equals(id(DisplayRig.speciesOn(plain))), Component.literal("plain: " + id(DisplayRig.speciesOn(plain))));
                helper.assertTrue(fork.equals(id(DisplayRig.speciesOn(forked))),
                        Component.literal("a fork carrier is the fork, not its ancestor: " + id(DisplayRig.speciesOn(forked))));
                helper.assertTrue(DisplayRig.speciesOn(unknown) == null, Component.literal("an unknown tag is nobody"));
                helper.assertTrue(DisplayRig.speciesOn(notARig) == null, Component.literal("not a rig carrier is nobody"));
                helper.assertTrue(DisplayRig.speciesOn(early) == null, Component.literal("a species not yet registered is nobody"));
                // a species registered NOW must be found at once — no stale answer from before it
                SpeciesRegistry.withSpeciesForTest(List.of(species(late)), () ->
                        helper.assertTrue(late.equals(id(DisplayRig.speciesOn(early))),
                                Component.literal("a species registered after the first look must be found: "
                                        + id(DisplayRig.speciesOn(early)))));
            });
            // and once it is gone again, nobody
            helper.assertTrue(DisplayRig.speciesOn(made.get(0)) == null, Component.literal("a removed species must be forgotten"));
            helper.succeed();
        } finally {
            made.forEach(net.minecraft.world.entity.Entity::discard);
        }
    }

    @GameTest(template = "empty_test", batch = "tickscaling", timeoutTicks = 100)
    public static void aLiveWishAboutACreatureHoldsItUntilTheWishEnds(GameTestHelper helper) {
        String id = "gt_ts_voiced";
        Species sp = species(id);
        com.moddingfromamod.testing.SpeciesForTest.putSaved(helper, sp);
        Pig c = carrier(helper, 1, id);
        UUID wish = UUID.nameUUIDFromBytes(("tickscaling-" + System.nanoTime()).getBytes());
        Marshal.global().submit(wish, UUID.randomUUID(), "make my voiced thing bigger");
        // One step per tick, from one handler (scheduling from inside a scheduled callback crashes
        // the GameTest runner): each answer is read on a LATER tick than the change it follows.
        int[] step = {0};
        helper.onEachTick(() -> {
            switch (step[0]++) {
                case 1 -> helper.assertTrue(!RigPowers.stillBeingWished(c, sp), Component.literal("no wish is about it yet"));
                case 2 -> Marshal.global().run(wish).about(id);
                case 3 -> helper.assertTrue(RigPowers.stillBeingWished(c, sp), Component.literal("a live wish about it must hold it"));
                case 4 -> Marshal.global().finish(wish);
                case 5 -> {
                    helper.assertTrue(!RigPowers.stillBeingWished(c, sp), Component.literal("a finished wish must let it go"));
                    c.discard();
                    helper.succeed();
                }
                default -> {
                }
            }
        });
    }

    private static String id(Species s) {
        return s == null ? null : s.id();
    }
}
