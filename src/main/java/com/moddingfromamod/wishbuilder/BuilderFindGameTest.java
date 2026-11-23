package com.moddingfromamod.wishbuilder;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.ui.MenuKit;
import com.moddingfromamod.ui.PickPage;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ClickType;

/**
 * <b>The Builder's 🔍 Find, through the real menu path</b> (§2): a click on the Find tile opens the box (a dialog
 * vanilla's own codec reads), the box's Submit is the {@code /moddy builder find} command a client runs, its results are
 * a pick chest of installed things (the live game's index: every mod, this one's own refused), and a result clicked is
 * the page's pick. A term the input lexicon refuses is refused in the wish box's words and searches nothing.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class BuilderFindGameTest {

    private BuilderFindGameTest() {
    }

    @GameTest(template = "empty_test", batch = "builderFind", timeoutTicks = 4000)
    public static void aChildFindsAThingByTypingAndTapsIt(GameTestHelper helper) {
        String before = System.getProperty(BuilderSettings.ENABLED_PROPERTY);
        System.setProperty(BuilderSettings.ENABLED_PROPERTY, "true");
        MockPlayerNetwork.afterTest(helper, () -> {
            if (before == null) {
                System.clearProperty(BuilderSettings.ENABLED_PROPERTY);
            } else {
                System.setProperty(BuilderSettings.ENABLED_PROPERTY, before);
            }
        });
        ServerPlayer child = MockPlayerNetwork.inLevel(helper, "gtfind");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        child.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        MockPlayerNetwork.actingChildFor(helper, child);

        helper.assertTrue(BuilderMenus.open(child), Component.literal("the Builder opens when it is on"));
        click(helper, child, "item");
        click(helper, child, BuilderFlow.STOCK_PREFIX + "wand");
        click(helper, child, "itempower:" + BuilderTurnInto.POWER);
        click(helper, child, BuilderFind.TILE_ID);
        BuilderFlow flow = BuilderMenus.flowOf(child);
        helper.assertTrue(flow != null && flow.asking(), Component.literal("the Find tile opens the box"));
        MenuKit.decode(child, flow.findBox());   // vanilla's codec reads it, or this throws

        // The lexicon: a term it refuses searches nothing, and the page comes back
        int refused = run(helper, child, "moddy builder find " + flow.token + " |hardcore porn");
        helper.assertTrue(refused == 0 && flow.found() == null && !flow.asking(),
                Component.literal("a refused term searched: " + flow.found()));
        helper.assertTrue(child.containerMenu instanceof MenuKit.PickMenu,
                Component.literal("the page is back after a refused term"));

        // A misspelt name, through the command the box runs
        click(helper, child, BuilderFind.TILE_ID);
        int ran = run(helper, child, "moddy builder find " + flow.token + " |honny blok");
        helper.assertTrue(ran == 1 && flow.found() != null, Component.literal("the box's Submit searched"));
        List<String> ids = flow.found().stream().map(BuilderFind.Result::id).toList();
        helper.assertTrue(ids.contains("minecraft:honey_block"), Component.literal("honny blok → " + ids));
        helper.assertTrue(ids.stream().noneMatch(BuilderFind::refused), Component.literal("a refused thing offered: " + ids));
        click(helper, child, BuilderFind.RESULT_PREFIX + "minecraft:honey_block");
        helper.assertTrue(flow.step() == BuilderFlow.Step.TURN_FROM && BuilderTurnIntoTestSeam.from(flow).contains("found:minecraft:honey_block"),
                Component.literal("the result is his From pick: " + flow.step()));

        // Refused kinds never appear, even typed by name; this mod's own things neither
        click(helper, child, BuilderFind.TILE_ID);
        for (String typed : List.of("command block", "structure block", "barrier", "debug stick", "moddy watch")) {
            run(helper, child, "moddy builder find " + flow.token + " |" + typed);
            List<String> got = flow.found() == null ? List.of() : flow.found().stream().map(BuilderFind.Result::id).toList();
            helper.assertTrue(got.stream().noneMatch(BuilderFind::refused), Component.literal(typed + " offered " + got));
            click(helper, child, BuilderFind.AGAIN_ID);
        }
        helper.succeed();
    }

    private static int run(GameTestHelper helper, ServerPlayer child, String command) {
        try {
            return helper.getLevel().getServer().getCommands().getDispatcher().execute(command, child.createCommandSourceStack());
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            helper.fail(Component.literal(command + " did not parse: " + e.getMessage()));
            return -1;
        }
    }

    /** Press the tile meaning {@code id} on the open pick chest, as a click. */
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

    /** What the flow's From picks are, read off its page. */
    static final class BuilderTurnIntoTestSeam {
        static List<String> from(BuilderFlow f) {
            return f.turnFromTiles().stream().filter(PickPage.Tile::selected)
                    .map(t -> t.id().substring(BuilderTurnInto.FROM_PREFIX.length())).toList();
        }
    }
}
