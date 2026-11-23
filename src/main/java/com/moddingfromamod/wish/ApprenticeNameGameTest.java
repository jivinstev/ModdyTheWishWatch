package com.moddingfromamod.wish;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.apprentice.LayerHost;
import com.moddingfromamod.safety.SafetyGrader;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.ui.MenuKit;
import com.moddingfromamod.wishbuilder.ApprenticeWords;
import com.moddingfromamod.wishbuilder.BuilderMenus;
import com.moddingfromamod.wishbuilder.BuilderSettings;
import com.moddingfromamod.wishbuilder.BuilderSpec;
import com.moddingfromamod.world.WorldCrafters;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.UserWhiteListEntry;

/**
 * <b>The name he gives, through the real wish box</b>: a Crafter's "a castle called sir fluffy"
 * opens the Apprentice's page, is told a Wish Magician can name it, and his make keeps the Builder's own name (nothing a
 * Crafter types is kept); a Magician's makes the castle called Sir Fluffy. A name the safety checks refuse is dropped.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class ApprenticeNameGameTest {

    private ApprenticeNameGameTest() {
    }

    private static void run(ServerPlayer p, String command) {
        p.getServer().getCommands().performPrefixedCommand(p.createCommandSourceStack(), command);
    }

    @GameTest(template = "empty_test", batch = "apprenticeName", timeoutTicks = 400)
    public static void aMagicianNamesHisMakeACrafterIsToldWhoCan(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, "name_kid");
        var list = helper.getLevel().getServer().getPlayerList();
        NextWish.quietForTest(p.getUUID());
        WishService.forget(p);
        CrafterService.forgetForTest(p.getUUID());
        ApprenticeService.forget(p.getUUID());
        SafetyGrader.transportForTest((prompt, text, schema, timeout) -> null);
        LayerHost.useForTest(null, 0);
        try {
            System.setProperty(WorldCrafters.OPEN_PROPERTY, "true");
            System.setProperty(BuilderSettings.ENABLED_PROPERTY, "true");

            // A CRAFTER: told a Wish Magician can, and his make keeps the Builder's name
            helper.assertTrue(CrafterService.isCrafter(p), Component.literal("control: a Crafter first"));
            int mark = MockPlayerNetwork.chat(p).size();
            run(p, "moddy wishbox |a castle called sir fluffy");
            helper.assertTrue(p.containerMenu instanceof MenuKit.PickMenu, Component.literal("the Apprentice's page"));
            var chat = MockPlayerNetwork.chat(p);
            helper.assertTrue(chat.subList(Math.min(mark, chat.size()), chat.size()).stream().anyMatch(l -> l.contains(ApprenticeWords.NAME_MAGICIAN)),
                    Component.literal("told a Wish Magician can name it"));
            AtomicReference<BuilderSpec> made = new AtomicReference<>();
            BuilderMenus.apprenticeMadeForTest(p.getUUID(), made::set);
            ApprenticeService.pressForTest(p, ApprenticeWords.OFFER + 0);
            helper.assertTrue(made.get() != null && !made.get().name().contains("Fluffy"),
                    Component.literal("a Crafter's make keeps the Builder's name: " + (made.get() == null ? "nothing made" : made.get().name())));
            helper.assertTrue(made.get().kind() == BuilderSpec.Kind.BUILDING, Component.literal("and the name's words were not read: " + made.get()));

            // A MAGICIAN on Apprentice magic: the make is called Sir Fluffy
            p.closeContainer();
            list.getWhiteList().add(new UserWhiteListEntry(p.getGameProfile()));
            CrafterService.check(p);
            ApprenticeService.forget(p.getUUID());
            WishService.forget(p);
            run(p, "moddy wishbox magic apprentice |a castle called sir fluffy");
            helper.assertTrue(p.containerMenu instanceof MenuKit.PickMenu, Component.literal("a sure page for the Magician: " + p.containerMenu));
            made.set(null);
            BuilderMenus.apprenticeMadeForTest(p.getUUID(), made::set);
            ApprenticeService.pressForTest(p, ApprenticeWords.OFFER + 0);
            helper.assertTrue(made.get() != null && made.get().name().equals("Sir Fluffy"),
                    Component.literal("the Magician's make is called Sir Fluffy: " + (made.get() == null ? "nothing made" : made.get().name())));
            // A name the checks refuse is dropped: the lexicon's and make_item's own name sweep
            helper.assertTrue(BuilderMenus.nameIsSafe("Sir Fluffy"), Component.literal("a plain name passes"));
            helper.assertFalse(BuilderMenus.nameIsSafe("Hardcore Porn"), Component.literal("a refused term never becomes a name"));
            helper.succeed();
        } finally {
            try {
                ApprenticeService.choose(p, false);
            } catch (RuntimeException ignored) {
                // nothing to put back
            }
            list.getWhiteList().remove(p.getGameProfile());
            System.clearProperty(WorldCrafters.OPEN_PROPERTY);
            System.clearProperty(BuilderSettings.ENABLED_PROPERTY);
            SafetyGrader.transportForTest(null);
        }
    }
}
