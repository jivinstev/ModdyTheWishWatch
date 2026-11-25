package com.moddingfromamod.test.contract;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.wish.LearnedTemplates;
import com.moddingfromamod.wish.MakeFactsLookup;
import com.moddingfromamod.wishbuilder.BuilderAgain;
import com.moddingfromamod.wishbuilder.BuilderSpec;
import com.moddingfromamod.wishbuilder.BuilderSpecStore;
import com.moddingfromamod.wishbuilder.MakeFacts;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>A world jar reads a make's facts from the live server</b> (#61, #63): the contract world asks
 * {@link MakeFactsLookup} about a kept Builder make ({@code builder:<id>}), a taught recipe that replays a creature on
 * the shelf, the same creature as a shelf thing, and a recipe whose thing is gone, and decides each by the facts alone.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class ContractWorldFactsGameTest {

    private ContractWorldFactsGameTest() {
    }

    @GameTest(template = "empty_test", batch = "contract_world_facts", timeoutTicks = 200)
    public static void aWorldReadsTheFactsOfEveryWayAMakeComesBack(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, "gtcontract");
        var server = helper.getLevel().getServer();
        String tag = UUID.randomUUID().toString().substring(0, 6);

        // a kept Builder make, as the Builder keeps one: a boss cat
        BuilderSpec boss = new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.CREATURE, "preset:species/cat", "", 2,
                List.of(), "boss", 1, "", "Boss Cat", Map.of());
        String makeId = "gt_contract_" + tag;
        try {
            BuilderSpecStore.put(p.getStringUUID(), new BuilderSpecStore.Made(makeId, makeId, "creature", boss.toJson(),
                    "", System.currentTimeMillis()));
        } catch (java.io.IOException e) {
            helper.fail(Component.literal("could not keep the make: " + e));
            return;
        }
        MakeFacts kept = MakeFactsLookup.forRecipe(server, p.getUUID(), BuilderAgain.recipeId(makeId));
        helper.assertTrue(kept.known() && kept.has(MakeFacts.HOSTILE) && kept.has(MakeFacts.BOSS),
                Component.literal("the kept make's facts: " + kept));
        helper.assertTrue(ContractWorld.verdict(kept) == ContractWorld.Verdict.HIDDEN,
                Component.literal("a hostile make is hidden here: " + ContractWorld.verdict(kept)));
        // another child's id finds nothing of his: cautious, never his facts
        MakeFacts notHis = MakeFactsLookup.forRecipe(server, UUID.randomUUID(), BuilderAgain.recipeId(makeId));
        helper.assertTrue(!notHis.known(), Component.literal("another child's make: " + notHis));

        // a taught recipe that replays a creature on the shelf, and the same creature as a shelf thing
        Species s = Species.fromJson(com.google.gson.JsonParser.parseString("{\"id\":\"gt_contract_dragon_" + tag
                + "\",\"name\":\"Dragon\",\"powers\":[\"friendly\",\"flies\"],\"health\":200,\"damage\":12,\"scale\":2.6}")
                .getAsJsonObject());
        String recipe = "gt_contract_recipe_" + tag;
        String gone = "gt_contract_gone_" + tag;
        List<String> had = LearnedTemplates.savedJsonForTest();
        List<String> with = new ArrayList<>(had);
        with.add("{\"id\":\"" + recipe + "\",\"all\":[\"gtcontract" + tag + "\"],\"ownerId\":\"" + p.getStringUUID()
                + "\",\"commands\":[\"wishmob spawn " + s.id() + " 6\"]}");
        with.add("{\"id\":\"" + gone + "\",\"all\":[\"gtcontractgone" + tag + "\"],\"ownerId\":\"" + p.getStringUUID()
                + "\",\"commands\":[\"wishmob spawn gt_nobody_" + tag + "\"]}");
        LearnedTemplates.restoreForTest(with, server);
        helper.testInfo.addListener(new GameTestListener() {
            @Override public void testStructureLoaded(GameTestInfo info) {
            }

            @Override public void testPassed(GameTestInfo info, GameTestRunner runner) {
                end();
            }

            @Override public void testFailed(GameTestInfo info, GameTestRunner runner) {
                end();
            }

            @Override public void testAddedForRerun(GameTestInfo a, GameTestInfo b, GameTestRunner r) {
                end();
            }

            private void end() {
                LearnedTemplates.restoreForTest(had, server);
                MockPlayerNetwork.remove(p);
            }
        });
        MakeFacts[] read = new MakeFacts[2];
        SpeciesRegistry.withSpeciesForTest(List.of(s), () -> {
            read[0] = MakeFactsLookup.forRecipe(server, p.getUUID(), recipe);
            read[1] = MakeFactsLookup.forThing(server, "creature", s.id());
        });
        MakeFacts replay = read[0];
        MakeFacts thing = read[1];
        helper.assertTrue(replay.known() && (replay.kind().equals("creature") || replay.kind().equals("ride")),
                Component.literal("the replay's facts: " + replay));
        helper.assertTrue(replay.tags().equals(thing.tags())
                        && replay.number(MakeFacts.HEALTH_HALF_HEARTS).equals(thing.number(MakeFacts.HEALTH_HALF_HEARTS)),
                Component.literal("a replay is what it makes: " + replay + " vs " + thing));
        helper.assertTrue(replay.number(MakeFacts.COUNT_MADE).orElse(0) == 6,
                Component.literal("the recipe spawns six: " + replay));
        // the world's OWN rating, from the measured numbers: six big, tough, strong dragons are locked here; one is not
        helper.assertTrue(replay.has(MakeFacts.HARMLESS) && ContractWorld.verdict(replay) == ContractWorld.Verdict.LOCKED,
                Component.literal("six huge friendly dragons are harmless but this world locks them: " + replay
                        + " rated " + ContractWorld.rating(replay)));
        helper.assertTrue(ContractWorld.verdict(thing) == ContractWorld.Verdict.ALLOWED,
                Component.literal("one dragon is allowed here: " + thing + " rated " + ContractWorld.rating(thing)));
        MakeFacts goneFacts = MakeFactsLookup.forRecipe(server, p.getUUID(), gone);
        helper.assertTrue(goneFacts.equals(MakeFacts.cautious("creature")),
                Component.literal("a gone thing is rated cautiously: " + goneFacts));
        helper.assertTrue(ContractWorld.verdict(goneFacts) != ContractWorld.Verdict.ALLOWED,
                Component.literal("a thing that cannot be read is never let through by a limiting world"));
        helper.succeed();
    }
}
