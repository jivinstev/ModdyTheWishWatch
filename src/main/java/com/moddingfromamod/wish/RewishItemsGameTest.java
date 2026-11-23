package com.moddingfromamod.wish;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.wishbuilder.BuilderLooks;
import com.moddingfromamod.wishbuilder.BuilderMake;
import com.moddingfromamod.wishbuilder.BuilderSettings;
import com.moddingfromamod.wishbuilder.BuilderSpecStore;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * <b>a typed change of an ITEM's look, answered at once.</b> A Builder crown (a leather helmet he wears, drawn as
 * the crown); then he types "make my crown a wizard hat": the re-wish reader is SURE, the editor walks 🎨 More
 * looks… and its category to the wizard hat, and the change is made by the Builder's own edit. Read off his shelf and
 * the stack he holds, never a verdict.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class RewishItemsGameTest {

    private RewishItemsGameTest() {
    }

    @GameTest(template = "empty_test", batch = "rewishItems", timeoutTicks = 12000)   // sets the acting child
    public static void aTypedChangeOfAnItemsLookIsMadeAtOnce(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, "rewish_items_kid");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        MockPlayerNetwork.actingChildFor(helper, p);
        var server = helper.getLevel().getServer();
        String owner = p.getUUID().toString();
        long rise = BuilderMake.riseMillis;
        boolean card = BuilderMake.takeCard;
        BuilderMake.riseMillis = 0;
        BuilderMake.takeCard = false;
        String builderWas = System.getProperty(BuilderSettings.ENABLED_PROPERTY);
        System.setProperty(BuilderSettings.ENABLED_PROPERTY, "true");
        Rewish.onForTest(true);
        MockPlayerNetwork.afterTest(helper, () -> {
            BuilderMake.riseMillis = rise;
            BuilderMake.takeCard = card;
            Rewish.onForTest(null);
            if (builderWas == null) {
                System.clearProperty(BuilderSettings.ENABLED_PROPERTY);
            } else {
                System.setProperty(BuilderSettings.ENABLED_PROPERTY, builderWas);
            }
        });
        var crown = com.moddingfromamod.wishbuilder.BuilderLooksGameTest.pressed("crown", BuilderLooks.SHOW);
        AtomicReference<String> id = new AtomicReference<>();
        AtomicReference<String> wrong = new AtomicReference<>();
        BuilderMake.run(server, BuilderMake.Who.of(p), crown, null, BuilderMake.protocolForTest(), false, false).thenAcceptAsync(made ->
                server.execute(() -> {
                    if (made.outcome() != BuilderMake.Outcome.MADE && made.outcome() != BuilderMake.Outcome.PARTIAL) {
                        wrong.set("the crown was not made: " + made.detail());
                        return;
                    }
                    WishService.Outcome out = WishService.submit(p, "make my crown a wizard hat", WishSource.TYPED);
                    if (out != WishService.Outcome.APPRENTICE) {
                        wrong.set("'make my crown a wizard hat' was not answered at once: " + out);
                        return;
                    }
                    id.set(made.madeId());
                }));
        int hat = com.moddingfromamod.datapack.ItemCatalogue.ICONS.get("wizard_hat");
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(wrong.get() == null, Component.literal(String.valueOf(wrong.get())));
            helper.assertTrue(id.get() != null, Component.literal("still making the crown"));
            BuilderSpecStore.Made kept = BuilderSpecStore.get(owner, id.get());
            helper.assertTrue(kept != null && "wizard_hat".equals(kept.read().opt(BuilderLooks.SLOT)),
                    Component.literal("his shelf does not keep the new look: " + (kept == null ? null : kept.spec())));
            helper.assertTrue(!com.moddingfromamod.wishbuilder.BuilderLooksGameTest.held(p, null, hat).isEmpty(),
                    Component.literal("he does not hold a wizard hat (" + hat + "): "
                            + com.moddingfromamod.wishbuilder.BuilderLooksGameTest.holding(p)));
        });
    }

    /**
     * a change the editor REFUSES is never announced. A Fire Sword made a candy cane (eaten; food cannot set
     * things on fire): his words go on as typed, and he never heard "Turning your Fire Sword into a Candy Cane".
     */
    @GameTest(template = "empty_test", batch = "rewishItemsRefused", timeoutTicks = 12000)   // sets the acting child
    public static void aChangeTheEditorRefusesIsNeverAnnounced(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, "rewish_refused_kid");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        MockPlayerNetwork.actingChildFor(helper, p);
        var server = helper.getLevel().getServer();
        long rise = BuilderMake.riseMillis;
        boolean card = BuilderMake.takeCard;
        BuilderMake.riseMillis = 0;
        BuilderMake.takeCard = false;
        String builderWas = System.getProperty(BuilderSettings.ENABLED_PROPERTY);
        System.setProperty(BuilderSettings.ENABLED_PROPERTY, "true");
        Rewish.onForTest(true);
        MockPlayerNetwork.afterTest(helper, () -> {
            BuilderMake.riseMillis = rise;
            BuilderMake.takeCard = card;
            Rewish.onForTest(null);
            if (builderWas == null) {
                System.clearProperty(BuilderSettings.ENABLED_PROPERTY);
            } else {
                System.setProperty(BuilderSettings.ENABLED_PROPERTY, builderWas);
            }
        });
        var f = new com.moddingfromamod.wishbuilder.BuilderFlow(com.moddingfromamod.wishbuilder.BuilderAllowlist.REVIEWED,
                com.moddingfromamod.wishbuilder.LibraryPresets.SHIPPED);
        for (String press : java.util.List.of("item", com.moddingfromamod.wishbuilder.BuilderFlow.STOCK_PREFIX + "sword", "itempower:fire")) {
            f.press(press);
        }
        var sword = f.spec(f.values(""));
        AtomicReference<WishService.Outcome> out = new AtomicReference<>();
        AtomicReference<String> wrong = new AtomicReference<>();
        BuilderMake.run(server, BuilderMake.Who.of(p), sword, null, BuilderMake.protocolForTest(), false, false).thenAcceptAsync(made ->
                server.execute(() -> {
                    if (made.outcome() != BuilderMake.Outcome.MADE && made.outcome() != BuilderMake.Outcome.PARTIAL) {
                        wrong.set("the sword was not made: " + made.detail());
                        return;
                    }
                    out.set(WishService.submit(p, "turn my sword into a candy cane", WishSource.TYPED));
                }));
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(wrong.get() == null, Component.literal(String.valueOf(wrong.get())));
            helper.assertTrue(out.get() != null, Component.literal("still making the sword"));
            // his words go on as typed (a new candy cane may be offered by the Apprentice): the change is never announced
            for (String line : MockPlayerNetwork.chat(p)) {
                helper.assertTrue(!line.contains("Turning your"), Component.literal("announced a refused change: " + line));
            }
        });
    }
}
