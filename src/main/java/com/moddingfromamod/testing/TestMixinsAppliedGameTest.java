package com.moddingfromamod.testing;

import java.util.Arrays;

import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.network.chat.Component;

/**
 * the GameTest harness's own mixins (testing/mixin) are a TEST-ONLY config that the dev runs register through
 * FML's {@code --fml.mixinConfig}, never {@code neoforge.mods.toml}. If that argument ever stops reaching the run, the
 * mixins silently stop applying: one test's exception would end the whole run again, and every tick-timed timeout
 * would be seconds. This fails first, by name: both mixins' handlers must be merged into {@code GameTestInfo}.
 */
@GameTestHolder("moddingfromamod")
public class TestMixinsAppliedGameTest {

    @GameTest(template = "empty_test", timeoutTicks = 20)
    public void theGameTestMixinsAreApplied(GameTestHelper helper) {
        String methods = String.join(" ", Arrays.stream(GameTestInfo.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName).toList());
        for (String handler : new String[] {"moddingfromamod$failTheTestNotTheServer", "moddingfromamod$notBeforeItsTime",
                "moddingfromamod$markStart"}) {
            helper.assertTrue(methods.contains(handler), Component.literal("GameTestInfo has no " + handler
                    + ": moddingfromamod.gametest.mixins.json did not apply (build.gradle's --fml.mixinConfig)"));
        }
        helper.succeed();
    }
}
