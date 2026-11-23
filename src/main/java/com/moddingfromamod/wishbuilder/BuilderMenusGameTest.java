package com.moddingfromamod.wishbuilder;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.ui.MenuKit;
import com.moddingfromamod.ui.PickPage;
import com.moddingfromamod.wish.Wish;
import com.moddingfromamod.wish.WishBook;
import com.moddingfromamod.wish.WishSource;
import com.moddingfromamod.wish.WishStatus;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ClickType;

/**
 * <b>The Builder's real menus, pressed the way a client presses them</b> (B0b).
 *
 * <ol>
 *   <li>Every tune dialog the Builder can open — every kind, a Crafter's and a Magician's — is decoded by
 *       VANILLA's dialog codec into a notice dialog with ≤4 inputs: the codec a real client's packet is
 *       written with.</li>
 *   <li>A child opens the Builder, CLICKS the chest slots (a creature, the cat, a power, Next), and runs
 *       the Make command the dialog would send; a {@code BUILDER} wish settles DONE. The same clicks a
 *       Java client sends and a Bedrock bridge sends for a Bedrock tap.</li>
 * </ol>
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class BuilderMenusGameTest {

    private BuilderMenusGameTest() {
    }

    @GameTest(template = "empty_test")
    public static void vanillaDecodesEveryTuneDialogTheBuilderOpens(GameTestHelper helper) {
        var ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
        List<String> wrong = new ArrayList<>();
        int checked = 0;
        for (BuilderSpec.Kind k : BuilderSpec.Kind.values()) {
            BuilderFlow f = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
            f.press(k.id());
            for (int guard = 0; f.step() != BuilderFlow.Step.TUNE; guard++) {
                if (guard > 20) {
                    // A page whose first tile toggles never leaves itself: name it rather than spin the server.
                    wrong.add(k + ": the walk never reached the dialog (stuck at " + f.step() + ")");
                    break;
                }
                List<PickPage.Tile> tiles = f.page().tiles();
                if (f.step() == BuilderFlow.Step.SNACK_POWER || f.step() == BuilderFlow.Step.BUILD_OUTSIDE) {
                    // a toggle page that needs one pick before Next; what goes outside is one too (several at once).
                    f.press(tiles.stream().anyMatch(PickPage.Tile::selected) ? PickPage.NEXT_ID : tiles.get(0).id());
                    continue;
                }
                f.press(f.step() == BuilderFlow.Step.PARTS || f.step() == BuilderFlow.Step.POWERS || f.step() == BuilderFlow.Step.ABILITIES
                        ? PickPage.NEXT_ID : tiles.get(0).id());
            }
            if (f.step() != BuilderFlow.Step.TUNE) {
                continue;
            }
            try {
                var d = MenuKit.decode(ops, f.tune());
                // A CONFIRMATION (Escape on a notice pressed its one button); this still said
                // "notice", so Gate B was red on main for every kind.
                if (!(d instanceof net.minecraft.server.dialog.ConfirmationDialog n) || n.common().inputs().size() > 4) {
                    wrong.add(k + ": not a confirmation with ≤4 inputs");
                }
                checked++;
            } catch (RuntimeException e) {
                wrong.add(k + ": " + e.getMessage());
            }
        }
        helper.assertTrue(wrong.isEmpty() && checked == BuilderSpec.Kind.values().length, Component.literal(checked + " decoded; " + wrong));
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = "builderMenus", timeoutTicks = 40000)
    public static void aChildClicksThroughTheMenusAndGetsHisCreature(GameTestHelper helper) {
        String before = System.getProperty(BuilderSettings.ENABLED_PROPERTY);
        System.setProperty(BuilderSettings.ENABLED_PROPERTY, "true");
        // D3: the menus make the THING here; the wish card needs a camera a GameTest has not got, and waiting
        // for one was ~85 of this test's 94 s. The pillar's rise is BuilderMakeGameTest's.
        BuilderMake.takeCard = false;
        long rise = BuilderMake.riseMillis;
        BuilderMake.riseMillis = 0;
        MockPlayerNetwork.afterTest(helper, () -> {
            BuilderMake.takeCard = true;
            BuilderMake.riseMillis = rise;
            if (before == null) {
                System.clearProperty(BuilderSettings.ENABLED_PROPERTY);
            } else {
                System.setProperty(BuilderSettings.ENABLED_PROPERTY, before);
            }
        });
        ServerPlayer child = MockPlayerNetwork.inLevel(helper, "gtmenus");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        child.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        MockPlayerNetwork.actingChildFor(helper, child);

        helper.assertTrue(BuilderMenus.open(child), Component.literal("the Builder opens when it is on"));
        click(helper, child, "creature");
        click(helper, child, "species/cat");
        click(helper, child, PickPage.NEXT_ID);   // past the parts
        click(helper, child, "power:glowing");
        click(helper, child, PickPage.NEXT_ID);
        click(helper, child, PickPage.NEXT_ID);   // past the special moves
        BuilderFlow flow = BuilderMenus.flowOf(child);
        helper.assertTrue(flow != null && flow.step() == BuilderFlow.Step.TUNE,
                Component.literal("Next opens the tune dialog: " + (flow == null ? "no flow" : flow.step())));
        // What the dialog's Make sends, as the client runs it: size count scheme (Moddy names it). A trailing word, as
        // a dialog from before names were Moddy's sent, is not read.
        // the newest wish id, not the size: the book keeps 500, so at the cap a new wish leaves the size alone
        var newest0 = WishBook.get(helper.getLevel().getServer()).recent(1);
        Object before2 = newest0.isEmpty() ? null : newest0.get(0).id();
        int ran;
        try {
            ran = helper.getLevel().getServer().getCommands().getDispatcher().execute(
                    "moddy builder make " + flow.token + " 3 1 ocean Glowy", child.createCommandSourceStack());
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            helper.fail(Component.literal("the Make command did not parse: " + e.getMessage()));
            return;
        }
        helper.assertTrue(ran == 1, Component.literal("the Make command ran"));
        helper.assertTrue(BuilderMenus.flowOf(child) == null, Component.literal("a Make ends the flow, so a second press makes nothing"));
        int refused = 0;
        try {
            refused = helper.getLevel().getServer().getCommands().getDispatcher().execute(
                    "moddy builder make " + flow.token + " 3 1 ocean Glowy", child.createCommandSourceStack());
        } catch (Exception e) {
            refused = 0;
        }
        helper.assertTrue(refused == 0, Component.literal("an old dialog's Make is refused"));
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            List<Wish> mine = WishBook.get(helper.getLevel().getServer()).recent(20).stream()
                    .filter(w -> w.playerId() != null && w.playerId().equals(child.getUUID())).toList();
            helper.assertTrue(!mine.isEmpty() && !java.util.Objects.equals(mine.get(mine.size() - 1).id(), before2),
                    Component.literal("a wish was filed for him"));
            Wish w = mine.get(mine.size() - 1);
            helper.assertTrue(w.source() == WishSource.BUILDER, Component.literal("source " + w.source()));
            helper.assertTrue(w.status() == WishStatus.DONE, Component.literal("status " + w.status() + " " + w.result()));
        });
    }

    /** Press the tile (or control) meaning {@code id} on the open pick chest, as a click. */
    private static void click(GameTestHelper helper, ServerPlayer child, String id) {
        if (!(child.containerMenu instanceof MenuKit.PickMenu menu)) {
            helper.fail(Component.literal("no pick chest is open (pressing " + id + "): " + child.containerMenu));
            return;
        }
        Integer slot = null;
        for (Map.Entry<Integer, String> e : menu.page().slots().entrySet()) {
            if (e.getValue().equals(id)) {
                slot = e.getKey();
            }
        }
        if (slot == null) {
            helper.fail(Component.literal("'" + id + "' is not on the page: " + menu.page().slots().values()));
            return;
        }
        menu.clicked(slot, 0, ClickType.PICKUP, child);
    }
}
