package com.moddingfromamod.test.contract;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.plugin.Plugins;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.wishbuilder.BuilderAllowlist;
import com.moddingfromamod.wishbuilder.BuilderSpec;
import com.moddingfromamod.wishbuilder.MakeFacts;
import com.moddingfromamod.world.WorldMakes;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;

/**
 * #92: the make facts read the game itself from the moment the server stands, whether or not a world has ever
 * answered. Before, the item reader was installed the first time a world answered, so a sword read before that (by a
 * page walk, a world answering later, a test that read first) carried no attack damage and was remembered so. Run on
 * its own, in a server where no world has ever answered, this failed every time. Test code: never in the jar.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class ItemFactsAtStartGameTest {

    private ItemFactsAtStartGameTest() {
    }

    @GameTest(template = "empty_test", batch = "item_facts_at_start", timeoutTicks = 200)
    public static void aSwordReadBeforeAnyWorldAnswersCarriesItsAttackDamage(GameTestHelper helper) {
        Plugins.shared().hideForTest(WorldMakes.class, true);
        try {
            helper.assertTrue(WorldMakes.get() == WorldMakes.NONE, Component.literal("no world answers here"));
            Map<String, String> o = new LinkedHashMap<>();
            o.put("stock", "sword");
            o.put("shape", "shape:on_hit");
            o.put("effect", "effect:lightning");
            o.put("power", "lightning");
            o.put("strength", "3");
            BuilderSpec sword = new BuilderSpec(BuilderSpec.CURRENT, BuilderSpec.Kind.ITEM, "", "", 2, List.of(), "", 1, "",
                    "", o);
            MakeFacts facts = MakeFacts.of(BuilderAllowlist.REVIEWED.capped(sword));
            helper.assertTrue(facts.number(MakeFacts.ATTACK_DAMAGE_HALF_HEARTS).isPresent(),
                    Component.literal("the game's own attack damage is read before any world answers: " + facts));
        } finally {
            Plugins.shared().hideForTest(WorldMakes.class, false);
        }
        helper.succeed();
    }
}
