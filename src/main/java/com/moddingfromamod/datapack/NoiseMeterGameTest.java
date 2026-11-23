package com.moddingfromamod.datapack;

import net.minecraft.network.chat.Component;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.commands.CommandSourceStack;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>A wish datapack that plays a sound on every tick is turned down to one a second</b>,
 * through the REAL playsound and particle commands (the mixins), not the window alone; and one that
 * roars now and then is never touched. Each tick runs the commands as the pack's function would:
 * inside {@link NoiseMeter#enter}, which is what {@code FunctionNoiseMixin} does for a real one.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class NoiseMeterGameTest {

    private static final String DRONE = "gt_noise_drone";
    private static final String ROAR = "gt_noise_roar";

    private static void run(GameTestHelper helper, String pack, String command) {
        CommandSourceStack src = helper.getLevel().getServer().createCommandSourceStack()
                .withSuppressedOutput().withPermission(4);
        NoiseMeter.enter(pack);
        try {
            helper.getLevel().getServer().getCommands().performPrefixedCommand(src, command);
        } finally {
            NoiseMeter.exit();
        }
    }

    @GameTest(template = "empty_test", batch = "noisemeter", timeoutTicks = 900)
    public static void aDroneIsCappedAndARoarIsNot(GameTestHelper helper) {
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        NoiseMeter.forTest(DRONE);
        NoiseMeter.forTest(ROAR);
        int[] ticks = {0};
        helper.onEachTick(() -> {
            int t = ticks[0]++;
            run(helper, DRONE, "playsound minecraft:entity.ravager.roar hostile @a ~ ~ ~ 1 1");
            run(helper, DRONE, "particle minecraft:crit ~ ~ ~ 1 1 1 0.1 60 force");
            run(helper, DRONE, "particle minecraft:flame ~ ~ ~ 1 1 1 0.01 5 force");   // an aura: never counted
            if (t % 100 == 0) {
                run(helper, ROAR, "playsound minecraft:entity.ravager.roar hostile @a ~ ~ ~ 1 1");
            }
        });
        helper.runAfterDelay(700, () -> {
            try {
                helper.assertTrue(NoiseMeter.throttled(DRONE), Component.literal("a sound on every tick was not capped"));
                helper.assertTrue(NoiseMeter.dropped(DRONE) > 300, Component.literal("too little was dropped: "
                        + NoiseMeter.dropped(DRONE)));
                helper.assertTrue(!NoiseMeter.throttled(ROAR), Component.literal("a roar every 5 s was capped"));
                helper.assertTrue(NoiseMeter.dropped(ROAR) == 0, Component.literal("a roar every 5 s lost a roar"));
                helper.succeed();
            } finally {
                NoiseMeter.forgetTest(DRONE);
                NoiseMeter.forgetTest(ROAR);
                p.discard();
            }
        });
    }
}
