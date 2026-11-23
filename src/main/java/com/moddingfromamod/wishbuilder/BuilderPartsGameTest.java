package com.moddingfromamod.wishbuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.ModelRepository;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;

/**
 * <b>The parts he adds are ON the creature</b>. All eight kits, three to a creature (the most the
 * Builder lets him add), are made through the real Builder on a ready-made cat, and the creature's saved
 * sculpt must carry each one's {@code kit_} parts — the model the rig is built from, not the spec.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class BuilderPartsGameTest {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    private BuilderPartsGameTest() {
    }

    @GameTest(template = "empty_test", batch = "builderParts", timeoutTicks = 60000)
    public static void everyPartHeAddsIsOnTheCreature(GameTestHelper helper) {
        var p = com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper, "gtparts");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        p.setYRot(0);
        p.setXRot(25);
        com.moddingfromamod.testing.MockPlayerNetwork.actingChildFor(helper, p);
        var server = helper.getLevel().getServer();
        List<List<String>> sets = new ArrayList<>();
        List<String> all = BuilderParts.ALL.stream().map(BuilderParts.Part::id).toList();
        for (String one : all) {
            sets.add(List.of(one));   // one creature per part: every look is seen on its own (F1: 30 parts)
        }
        List<String> wrong = java.util.Collections.synchronizedList(new ArrayList<>());
        boolean[] done = {false};
        Thread t = new Thread(() -> {
            long rise = BuilderMake.riseMillis;
            BuilderMake.riseMillis = 0;
            try {
                for (List<String> set : sets) {
                    StringBuilder opts = new StringBuilder();
                    for (int i = 0; i < set.size(); i++) {
                        opts.append(i == 0 ? "" : ",").append("\"kit").append(i + 1).append("\":\"kit:").append(set.get(i)).append('"');
                    }
                    BuilderSpec spec = BuilderSpec.read(JsonParser.parseString("{\"builder\":1,\"kind\":\"creature\","
                            + "\"from\":\"preset:species/cat\",\"name\":\"Parts\",\"opts\":{" + opts + "}}").getAsJsonObject());
                    BuilderMake.Result made = BuilderAbilityGameTest.onServer(server, () -> BuilderMake.run(server,
                            BuilderMake.Who.of(p), spec, null, BuilderAbilityGameTest.protocol(), false, false))
                            .get(120, TimeUnit.SECONDS).get(120, TimeUnit.SECONDS);
                    if (made.outcome() != BuilderMake.Outcome.MADE) {
                        wrong.add(set + ": not made " + made.outcome() + " " + made.detail());
                        continue;
                    }
                    List<String> names = ModelRepository.sculptedParts(made.madeId()).stream().map(CreatureSculpt.Part::name).toList();
                    for (String kit : set) {
                        String k = BuilderParts.byId(kit).kit();
                        boolean on = names.stream().anyMatch(n -> n.startsWith("kit_" + k) || n.startsWith(k));   // a shell is shell0..
                        LOGGER.info("builder parts GT: {} on {} -> {}", kit, made.madeId(), on ? "OK" : "MISSING");
                        if (!on) {
                            wrong.add(kit + " is not on " + made.madeId() + " (its parts: " + names + ")");
                        }
                    }
                }
            } catch (Throwable e) {
                wrong.add("threw: " + e);
            } finally {
                BuilderMake.riseMillis = rise;
                done[0] = true;
            }
        }, "builder-parts-gt");
        t.setDaemon(true);
        t.start();
        helper.onEachTick(() -> {
            if (done[0] && !wrong.isEmpty()) {
                throw com.moddingfromamod.testing.gt.GameTestCompat.assertion("parts not on the creature: " + wrong);
            }
        });
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(done[0], Component.literal("still making"));
            helper.assertTrue(wrong.isEmpty(), Component.literal(String.join("; ", wrong)));
        });
    }
}
