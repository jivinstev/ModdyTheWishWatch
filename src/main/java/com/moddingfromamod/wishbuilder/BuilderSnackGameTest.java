package com.moddingfromamod.wishbuilder;

import static com.moddingfromamod.wishbuilder.BuilderAbilityGameTest.carrying;
import static com.moddingfromamod.wishbuilder.BuilderAbilityGameTest.clear;
import static com.moddingfromamod.wishbuilder.BuilderAbilityGameTest.findLever;
import static com.moddingfromamod.wishbuilder.BuilderAbilityGameTest.holds;
import static com.moddingfromamod.wishbuilder.BuilderAbilityGameTest.invoke;
import static com.moddingfromamod.wishbuilder.BuilderAbilityGameTest.onServer;
import static com.moddingfromamod.wishbuilder.BuilderAbilityGameTest.place;
import static com.moddingfromamod.wishbuilder.BuilderAbilityGameTest.pull;
import static com.moddingfromamod.wishbuilder.BuilderAbilityGameTest.use;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.ui.MenuKit;
import com.moddingfromamod.ui.PickPage;
import com.moddingfromamod.wish.Wish;
import com.moddingfromamod.wish.WishBook;
import com.moddingfromamod.wish.WishStatus;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * <b>A child composes a snack machine, fills it and eats what it makes</b>. Through the REAL menus — the chest
 * slots clicked as a client clicks them, the tune dialog's Make command — a "Zoom Pizza" that gives super speed AND
 * super jump, three wheat a fill. The make settles DONE through the real tools; then the machine is put down, filled
 * with wheat, pulled until it pours, and the poured snack is EATEN: both powers must be on him.
 *
 * <p>The two-power half is the measurement the issue asked for before promising it ("only if the item engine already
 * stacks effects"): {@code make_item} takes {@code speed+jump} with its parameters grouped by effect, and eating one
 * snack applies both.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class BuilderSnackGameTest {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    private BuilderSnackGameTest() {
    }

    /** The snack's name: Moddy's, worked out below exactly as the Make works it out (the picks and his shelf). */
    static volatile String SNACK = "Zoomy Pizza";
    static volatile String MACHINE = SNACK + " Machine";

    @GameTest(template = "empty_test", batch = "builderSnack", timeoutTicks = 60000)
    public static void aChildComposesASnackMachineFillsItAndEatsWhatItMakes(GameTestHelper helper) {
        if (!com.moddingfromamod.datapack.CbScriptCompiler.canBuildReadyMades()) {
            helper.succeed();   // no compiler, no datapack: load_and_pour cannot exist here (CLAUDE.md: ply)
            return;
        }
        String before = System.getProperty(BuilderSettings.ENABLED_PROPERTY);
        System.setProperty(BuilderSettings.ENABLED_PROPERTY, "true");
        long rise = BuilderMake.riseMillis;
        BuilderMake.riseMillis = 0;   // the pillar's rise is BuilderMakeGameTest's; this test is about the machine
        MockPlayerNetwork.afterTest(helper, () -> {
            BuilderMake.riseMillis = rise;
            if (before == null) {
                System.clearProperty(BuilderSettings.ENABLED_PROPERTY);
            } else {
                System.setProperty(BuilderSettings.ENABLED_PROPERTY, before);
            }
        });
        ServerPlayer child = MockPlayerNetwork.inLevel(helper, "gtsnack");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        child.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        MockPlayerNetwork.actingChildFor(helper, child);
        var server = helper.getLevel().getServer();

        // ---- 1-4: the menus, clicked
        helper.assertTrue(BuilderMenus.open(child), Component.literal("the Builder opens when it is on"));
        click(helper, child, "machine");
        // the machine kind opens on its looks
        click(helper, child, "look:pizza");                        // what it makes
        click(helper, child, "snackpower:speed");                  // what eating it does: two powers
        click(helper, child, "snackpower:jump");
        click(helper, child, com.moddingfromamod.ui.PickPage.NEXT_ID);   // Done: no count fills the page
        click(helper, child, "ingredient:minecraft:wheat");        // what goes in (the pizza's own, first)
        BuilderFlow flow = BuilderMenus.flowOf(child);
        helper.assertTrue(flow != null && flow.step() == BuilderFlow.Step.TUNE,
                Component.literal("the ingredient opens the tune dialog: " + (flow == null ? "no flow" : flow.step())));
        helper.assertTrue(flow.tune().keys().equals(List.of("strength", "fill", "how")),
                Component.literal("the dialog asks strength, how many per fill and how it works: " + flow.tune().keys()));
        SNACK = flow.shelf(BuilderMenus.shelfNames(child.getUUID().toString(), null)).spec(flow.values("2 3 pour")).name();
        MACHINE = BuilderSnacks.machineName(flow.spec(flow.values("2 3 pour")));
        helper.assertTrue(SNACK.endsWith("Pizza"), Component.literal("Moddy names the snack for what it is: " + SNACK));
        int ran;
        try {
            // What the dialog's Make sends: strength 2, three a fill, a fill machine. Moddy names it.
            ran = server.getCommands().getDispatcher().execute(
                    "moddy builder make " + flow.token + " 2 3 pour", child.createCommandSourceStack());
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            helper.fail(Component.literal("the Make command did not parse: " + e.getMessage()));
            return;
        }
        helper.assertTrue(ran == 1, Component.literal("the Make command ran"));

        // ---- then, off the server thread: wait for DONE, put it down, fill it, pull, eat
        String[] verdict = {null};
        boolean[] done = {false};
        Thread t = new Thread(() -> {
            try {
                verdict[0] = runWorld(helper, server, child);
            } catch (Throwable e) {
                verdict[0] = "threw: " + e;
            } finally {
                done[0] = true;
            }
        }, "builder-snack-gt");
        t.setDaemon(true);
        t.start();
        helper.onEachTick(() -> {
            if (done[0] && verdict[0] != null) {
                throw com.moddingfromamod.testing.gt.GameTestCompat.assertion("the composed snack machine: " + verdict[0]);
            }
        });
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(done[0], Component.literal("still running"));
            helper.assertTrue(verdict[0] == null, Component.literal(String.valueOf(verdict[0])));
        });
    }

    /** Null when it all worked; else what did not. */
    static String runWorld(GameTestHelper helper, net.minecraft.server.MinecraftServer server, ServerPlayer child)
            throws Exception {
        long until = System.currentTimeMillis() + 240_000;
        Wish w = null;
        while (System.currentTimeMillis() < until) {
            w = onServer(server, () -> {
                List<Wish> mine = WishBook.get(server).recent(20).stream()
                        .filter(x -> x.playerId() != null && x.playerId().equals(child.getUUID())).toList();
                return mine.isEmpty() ? null : mine.get(mine.size() - 1);
            }).get(10, TimeUnit.SECONDS);
            if (w != null && w.status().isFinished()) {
                break;
            }
            Thread.sleep(250);
        }
        if (w == null || w.status() != WishStatus.DONE) {
            return "the make did not settle DONE: " + (w == null ? "no wish" : w.status() + " " + w.result());
        }
        BlockPos[] lever = {null};
        BlockPos floor = onServer(server, () -> {
            ItemStack machine = takeExact(child, MACHINE);
            if (machine == null) {
                return null;
            }
            BuilderAbilityGameTest.take(child, SNACK);   // the one to taste: put aside, so the one eaten is POURED
            BlockPos f = child.blockPosition().below().relative(Direction.SOUTH, 3);
            clear(helper, f);
            place(child, machine, f);
            return f;
        }).get(30, TimeUnit.SECONDS);
        if (floor == null) {
            return "no '" + MACHINE + "' reached his bag (" + onServer(server, () -> carrying(child)).get(10, TimeUnit.SECONDS) + ")";
        }
        // The machine builds itself a tick later (LoadAndPourGameTest waits for its lever too).
        long leverBy = System.currentTimeMillis() + 10_000;
        while (lever[0] == null && System.currentTimeMillis() < leverBy) {
            lever[0] = onServer(server, () -> findLever(helper, floor.above())).get(10, TimeUnit.SECONDS);
            Thread.sleep(100);
        }
        if (lever[0] == null) {
            return "put down, and it has no lever";
        }
        // Fill: hold three wheat and pull (it takes up to MAX = 3), then pull empty-handed: it pours.
        long pourBy = System.currentTimeMillis() + 15_000;
        boolean poured = false;
        while (!poured && System.currentTimeMillis() < pourBy) {
            poured = onServer(server, () -> {
                child.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WHEAT, 3));
                pull(child, lever[0]);
                child.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                pull(child, lever[0]);
                return holds(child, SNACK);
            }).get(10, TimeUnit.SECONDS);
            Thread.sleep(200);
        }
        if (!poured) {
            return "filled with wheat and pulled, and no " + SNACK + " came (" + onServer(server, () -> carrying(child)).get(10, TimeUnit.SECONDS) + ")";
        }
        // Eat the poured one.
        onServer(server, () -> {
            ItemStack snack = BuilderAbilityGameTest.take(child, SNACK);
            child.removeAllEffects();
            child.getFoodData().setFoodLevel(2);
            child.setItemInHand(InteractionHand.MAIN_HAND, snack);
            use(child);
            invoke(child, "completeUsingItem");
            return null;
        }).get(10, TimeUnit.SECONDS);
        String last = "never checked";
        long eatBy = System.currentTimeMillis() + 8_000;
        while (System.currentTimeMillis() < eatBy) {
            last = onServer(server, () -> {
                boolean speed = child.hasEffect(MobEffects.SPEED);
                boolean jump = child.hasEffect(MobEffects.JUMP_BOOST);
                return speed && jump ? null : "ate the poured " + SNACK + ": speed " + speed + ", jump " + jump;
            }).get(10, TimeUnit.SECONDS);
            if (last == null) {
                break;
            }
            Thread.sleep(100);
        }
        LOGGER.info("builder snack GT: {}", last == null ? "composed, filled, poured, eaten: speed AND jump" : last);
        return last;
    }

    /** The stack in his bag named exactly {@code name}, taken out of it. */
    static ItemStack takeExact(ServerPlayer p, String name) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.getHoverName().getString().strip().equals(name)) {
                inv.setItem(i, ItemStack.EMPTY);
                return s;
            }
        }
        return null;
    }

    /** Press the tile (or control) meaning {@code id} on the open pick chest, as a click. */
    static void click(GameTestHelper helper, ServerPlayer child, String id) {
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
        menu.clicked(slot, 0, ContainerInput.PICKUP, child);
    }
}
