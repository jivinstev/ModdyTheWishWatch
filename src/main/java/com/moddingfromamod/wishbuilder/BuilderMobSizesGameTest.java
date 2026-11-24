package com.moddingfromamod.wishbuilder;

import com.google.gson.JsonElement;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;

/**
 * <b>A real creature at each named size</b>, made where it runs: a shipped world's own menus (a real pig, its tune
 * dialog's Size), lowered by {@link BuilderLowering} and summoned through the REAL command, one per size. Each one
 * measures its own scale ({@code minecraft:scale}) and the size the game gives it at that scale; Normal is the control at 1. Then an
 * installed creature the menus do not list: a giant skeleton at 4.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class BuilderMobSizesGameTest {

    private BuilderMobSizesGameTest() {
    }

    @GameTest(template = "empty_test", batch = "builderMobSizes", timeoutTicks = 400)
    public static void eachNamedSizeIsItsOwnScale(GameTestHelper helper) {
        BuilderAllowlist allow = BuilderSuppliesGameTest.shippedWorldBuilder();
        List<LivingEntity> made = new ArrayList<>();
        for (int i = 0; i < BuilderMobSizes.ALL.size(); i++) {   // a tight grid: every one near enough the test to TICK (the game resizes a hitbox on its own tick)
            made.add(summon(helper, allow, "mob:minecraft:pig", BuilderMobSizes.ALL.get(i),
                    new BlockPos(1 + (i % 3) * 7, 2, 1 + (i / 3) * 7)));
        }
        BuilderMobSizes.Level giant = BuilderMobSizes.of("7");
        LivingEntity skeleton = summon(helper, allow, "mob:minecraft:skeleton", giant, new BlockPos(15, 2, 15));
        // The scale is set at once; the game resizes the hitbox on a later tick (refreshDimensions), so look after a few.
        helper.runAfterDelay(5, () -> check(helper, made, skeleton));
    }

    private static void check(GameTestHelper helper, List<LivingEntity> made, LivingEntity skeleton) {
        try {
            for (int i = 0; i < made.size(); i++) {
                BuilderMobSizes.Level level = BuilderMobSizes.ALL.get(i);
                LivingEntity pig = made.get(i);
                helper.assertTrue(Math.abs(pig.getAttributeValue(Attributes.SCALE) - level.scale()) < 1e-6,
                        Component.literal(level.name() + ": scale " + pig.getAttributeValue(Attributes.SCALE) + ", not " + level.scale()));
                // The size the game gives it at this scale (LivingEntity.getDimensions): the live hitbox only grows where
                // there is room, and the test box has none for a giant.
                double width = pig.getDimensions(pig.getPose()).width();
                helper.assertTrue(Math.abs(width - 0.9 * level.scale()) < 0.05,
                        Component.literal(level.name() + ": a hitbox " + width + " wide, not " + 0.9 * level.scale()));
            }
            helper.assertTrue(Math.abs(skeleton.getAttributeValue(Attributes.SCALE) - 4.0) < 1e-6,
                    Component.literal("a giant skeleton is at scale " + skeleton.getAttributeValue(Attributes.SCALE)));
            helper.succeed();
        } finally {
            made.forEach(LivingEntity::discard);
            skeleton.discard();
        }
    }

    private static LivingEntity summon(GameTestHelper helper, BuilderAllowlist allow, String mob, BuilderMobSizes.Level level,
                                       BlockPos rel) {
        BuilderFlow f = new BuilderFlow(allow, LibraryPresets.SHIPPED);
        f.press("creature");
        helper.assertTrue(f.press(mob).say().isEmpty() && f.step() == BuilderFlow.Step.TUNE,
                Component.literal("the creature page takes no " + mob + " (at " + f.step() + ")"));
        BuilderSpec spec = f.spec(Map.of("count", "1", "mobsize", level.id()));
        helper.assertTrue(allow.problems(spec).isEmpty(), Component.literal("the world refuses " + mob + ": " + allow.problems(spec)));
        BlockPos at = helper.absolutePos(rel);
        MinecraftServer server = helper.getLevel().getServer();
        List<BuilderLowering.Call> calls = BuilderLowering.lower(spec, "x_1", LibraryPresets.SHIPPED);
        helper.assertTrue(calls.stream().anyMatch(c -> c.tool().equals("run_command")),
                Component.literal("a real mob is summoned by run_command, not " + calls));
        for (BuilderLowering.Call call : calls) {
            if (!call.tool().equals("run_command")) {
                continue;   // plan_wish and friends: the summon is the run_command
            }
            for (JsonElement c : call.args().getAsJsonArray("command")) {
                String cmd = c.getAsString().replace("~ ~ ~", at.getX() + " " + at.getY() + " " + at.getZ());
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), cmd);
            }
        }
        List<LivingEntity> here = helper.getLevel().getEntitiesOfClass(LivingEntity.class, new AABB(at).inflate(1),
                e -> e.getType().builtInRegistryHolder().key().identifier().toString().equals(mob.substring(4)));
        helper.assertValueEqual(here.size(), 1, Component.literal(level.name() + " " + mob + "s summoned"));
        return here.get(0);
    }
}
