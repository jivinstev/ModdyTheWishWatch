package com.moddingfromamod.wish;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.config.Magician;
import com.moddingfromamod.config.SettingsOverride;
import com.moddingfromamod.plugin.Plugins;
import com.moddingfromamod.safety.SafetyGrader;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.wishbuilder.BuilderSettings;
import com.moddingfromamod.world.WorldCrafters;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>With Magician mode off, the Apprentice answers a typed wish and never hands it back to itself</b>.
 *
 * <p>Magician off is the public mod's default: every route is the Apprentice's ({@code WishRoute.choose}). A player who
 * is not a Crafter (a Magician by role) used to have his unsure words "sent to the Magician", which is
 * {@code WishService.submit} again, which routed them straight back to the Apprentice: a StackOverflowError that took
 * the server's tick loop down, found by the public repo's first Gate B default tier. Here the overrides are hidden, so
 * the mod runs as the public one does, whatever plug-ins are loaded. Its own batch: hiding the overrides is
 * process-wide.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class MagicianOffApprenticeGameTest {

    private MagicianOffApprenticeGameTest() {
    }

    @GameTest(template = "empty_test", batch = "magician_off_apprentice", timeoutTicks = 200)
    public static void aMagiciansUnsureWordsStayWithTheApprenticeWhenMagicianIsOff(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, "magician_off_kid");
        NextWish.quietForTest(p.getUUID());
        WishService.forget(p);
        CrafterService.forgetForTest(p.getUUID());
        ApprenticeService.forget(p.getUUID());
        SafetyGrader.transportForTest((prompt, text, schema, timeout) -> null);
        com.moddingfromamod.apprentice.LayerHost.useForTest(null, 0);
        String tag = Long.toString(System.nanoTime(), 36);
        Plugins.shared().hideForTest(SettingsOverride.class, true);
        try {
            System.setProperty(WorldCrafters.OPEN_PROPERTY, "false");   // the door shut: he is a Magician by role
            System.setProperty(BuilderSettings.ENABLED_PROPERTY, "true");
            helper.assertFalse(Magician.allowsAi(), Component.literal("control: Magician mode is off"));
            helper.assertFalse(CrafterService.isCrafter(p), Component.literal("control: not a Crafter"));
            helper.assertFalse(ApprenticeService.magicianAnswers(p),
                    Component.literal("with Magician off, nothing sent to the Magician can reach him"));

            // Words no Builder pick is sure of: the ones that used to go "to the Magician" and loop.
            WishService.Outcome out;
            try {
                out = WishService.submit(p, "something really cool and surprising for me " + tag, WishSource.TYPED);
            } catch (StackOverflowError e) {
                helper.fail(Component.literal("the Apprentice handed the words to itself until the stack ran out"));
                return;
            }
            helper.assertValueEqual(out, WishService.Outcome.APPRENTICE,
                    Component.literal("with Magician off the Apprentice answers"));
            helper.assertTrue(MockPlayerNetwork.chat(p).stream().noneMatch(l -> l.contains(
                            com.moddingfromamod.wishbuilder.ApprenticeWords.TO_THE_MAGICIAN)),
                    Component.literal("he is not told his words went to a Magician who is not there: "
                            + MockPlayerNetwork.chat(p)));
            helper.assertTrue(WishBook.get(helper.getLevel().getServer()).recent(40).stream().noneMatch(w -> w.text().contains(tag)),
                    Component.literal("no wish was recorded for an agent"));
        } finally {
            Plugins.shared().hideForTest(SettingsOverride.class, false);
            System.clearProperty(WorldCrafters.OPEN_PROPERTY);
            System.clearProperty(BuilderSettings.ENABLED_PROPERTY);
            SafetyGrader.transportForTest(null);
            p.closeContainer();
            ApprenticeService.forget(p.getUUID());
            CrafterService.forgetForTest(p.getUUID());
            WishService.forget(p);
            MockPlayerNetwork.remove(p);
        }
        helper.succeed();
    }
}
