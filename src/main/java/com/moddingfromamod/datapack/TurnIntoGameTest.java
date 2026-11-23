package com.moddingfromamod.datapack;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.MakeItemTool;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestCompat;
import com.moddingfromamod.testing.gt.GameTestHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * <b>: turn_into through the REAL make_item, per mode, per kind of FROM and TO.</b>
 *
 * <p>Every item here is made by {@link MakeItemTool#call} exactly as the agent makes one, then used.
 * HELD items are right-clicked by a mock player (the shape's own trigger, so the AIM really walks his
 * line of sight); the other shapes run the item's effect where the shape would — the place a throw
 * lands, the block a swing hit, the player at a machine's handle — because what THIS issue changed is
 * the body each shape gets, and the triggers are each proven elsewhere (the item matrix,
 * {@code ThrowAndUseDoneShapeGameTest}, the real lever in {@code MachineExplainsItselfGameTest}).
 *
 * <p>Its own batches: mock players and every make_item's reload are process-wide, and a batch past
 * ~60 s is a finding (docs/GATE_PERFORMANCE.md), so the cells are split three ways.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class TurnIntoGameTest {

    private static final String HELD = "turn_into_held";
    // A held use runs the wish AS the mock player through WishOnUse, and concurrent mock players in one
    // batch are not told apart there (measured: one test's wand took from ANOTHER test's bag and said
    // "You need Sand" to a player holding sand). So every held test that reads his BAG has a batch of its own.
    private static final String BAG = "turn_into_bag";
    private static final String WORLD = "turn_into_world";

    /** Where he stands, facing south, and the block in front of his eyes he points at. */
    private static final BlockPos FEET = new BlockPos(1, 2, 1);
    private static final BlockPos AIMED = new BlockPos(1, 3, 4);

    // ── making and using ──────────────────────────────────────────────────────────────────────────

    private static JsonObject call(String name, String shape, String effect, String params) {
        JsonObject o = JsonParser.parseString("""
                {"name":"%s","icon":"wand","colour":"purple","shape":"%s","count":1,"effect":"%s"}"""
                .formatted(name, shape, effect)).getAsJsonObject();
        o.add("params", JsonParser.parseString(params));
        return o;
    }

    private static String params(String from, String to, Integer radius) {
        JsonObject p = new JsonObject();
        p.addProperty("FROM", from);
        p.addProperty("TO", to);
        if (radius != null) {
            p.addProperty("RADIUS", String.valueOf(radius));
        }
        return p.toString();
    }

    /** The made feature id, or null having failed the test. Skips (says so) with no compiler. */
    private static String make(GameTestHelper helper, JsonObject call) {
        if (!CbScriptCompiler.available()) {
            System.out.println("TURN_INTO: SKIPPED — no CBScript compiler. This proved NOTHING.");
            helper.succeed();
            return null;
        }
        MakeItemTool tool = new MakeItemTool();
        ToolResult r = tool.call(call);
        if (r.isError()) {
            helper.fail(Component.literal("make_item refused " + call + ": " + r.text()));
            return null;
        }
        return tool.lastMadeId();
    }

    private static ServerPlayer player(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        p.getInventory().clearContent();
        BlockPos feet = helper.absolutePos(FEET);
        p.snapTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.0F, 0.0F);
        heard(p, new StringBuilder());
        return p;
    }

    private static void cmd(GameTestHelper helper, ServerPlayer p, String command) {
        helper.getLevel().getServer().getCommands().performPrefixedCommand(
                p.createCommandSourceStack().withPermission(2).withSuppressedOutput(), command);
    }

    /** Run the item's effect as him, standing at {@code at} — where a throw lands, a swing hits. */
    private static void fire(GameTestHelper helper, ServerPlayer p, String id, BlockPos at) {
        BlockPos a = helper.absolutePos(at);
        var src = p.createCommandSourceStack().withPermission(2).withLevel(helper.getLevel())
                .withPosition(net.minecraft.world.phys.Vec3.atCenterOf(a));
        var fn = helper.getLevel().getServer().getFunctions().get(ResourceLocation.fromNamespaceAndPath(id, "effect"));
        if (fn.isEmpty()) {
            throw GameTestCompat.assertion("no function " + id + ":effect to fire");
        }
        helper.getLevel().getServer().getFunctions().execute(fn.get(), src);
    }

    /** Run a machine's effect as him at the handle: what a pull does, minus the body. */
    private static void pull(GameTestHelper helper, ServerPlayer p, String id) {
        cmd(helper, p, "execute at @s run function " + id + ":effect");
    }

    /** Hand him the made held thing (the real give) and right-click it once, in the air. */
    private static void click(GameTestHelper helper, ServerPlayer p, String id, String name) {
        List<ItemStack> keep = new java.util.ArrayList<>();
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            keep.add(p.getInventory().getItem(i).copy());
        }
        p.getInventory().clearContent();
        cmd(helper, p, "wishdatapack " + id + " give");
        ItemStack wand = ItemStack.EMPTY;
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            if (!s.isEmpty() && s.getHoverName().getString().equals(name)) {
                wand = s.copy();
            }
        }
        if (wand.isEmpty()) {
            throw GameTestCompat.assertion("the real give handed over nothing named " + name);
        }
        p.getInventory().clearContent();
        for (int i = 1; i < keep.size(); i++) {
            p.getInventory().setItem(i, keep.get(i));
        }
        p.setItemInHand(InteractionHand.MAIN_HAND, wand);
        p.gameMode.useItem(p, helper.getLevel(), p.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND);
    }

    private static boolean loaded(GameTestHelper helper, String id) {
        // Not mid-reload: a function can be found while the reload that replaces it is still running.
        return !DatapackReloader.reloadInProgress() && !DatapackReloader.reloadDeferred()
                && helper.getLevel().getServer().getFunctions()
                .get(ResourceLocation.fromNamespaceAndPath(id, "effect")).isPresent();
    }

    private static String heard(ServerPlayer p, StringBuilder into) {
        var channel = p.connection.getConnection().channel();
        if (channel instanceof io.netty.channel.embedded.EmbeddedChannel embedded) {
            Object o;
            while ((o = embedded.readOutbound()) != null) {
                if (o instanceof ClientboundSystemChatPacket chat) {
                    into.append(chat.content().getString()).append('\n');
                }
            }
        }
        return into.toString();
    }

    private static int has(ServerPlayer p, Item item) {
        return p.getInventory().countItem(item);
    }

    private static void give(ServerPlayer p, int slot, Item item, int n) {
        p.getInventory().setItem(slot, new ItemStack(item, n));   // a set slot, never Inventory.add
    }

    private static void is(GameTestHelper helper, BlockPos pos, Block want, String why) {
        helper.assertTrue(helper.getBlockState(pos).is(want),
                Component.literal(why + " — it is " + helper.getBlockState(pos)));
    }

    /** Make, wait for the pack, act, then check — each cell's whole shape. */
    private static void cell(GameTestHelper helper, JsonObject call, java.util.function.BiConsumer<ServerPlayer, String> act,
                             java.util.function.BiConsumer<ServerPlayer, String> check) {
        String id = make(helper, call);
        if (id == null) {
            return;
        }
        ServerPlayer[] p = {null};
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (!loaded(helper, id)) {
                        throw GameTestCompat.assertion("the pack " + id + " has not loaded yet");
                    }
                })
                .thenExecute(() -> {
                    p[0] = player(helper);
                    act.accept(p[0], id);
                })
                .thenExecute(() -> {
                    try {
                        check.accept(p[0], id);
                    } finally {
                        MockPlayerNetwork.remove(p[0]);
                    }
                })
                .thenSucceed();
    }

    private static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * A name no earlier run made: the game time alone repeats across runs of a reused world, and an
     * older pack under the same id answered "loaded" before this one was installed (measured).
     */
    private static String name(GameTestHelper helper, String what) {
        return what + " " + (System.currentTimeMillis() % 1_000_000) + SEQ.incrementAndGet();
    }

    // ── the machine: his inventory ────────────────────────────────────────────────────────────────

    @GameTest(template = "empty_test", batch = BAG, timeoutTicks = 900)
    public static void aMachineTakesOneFromHisBagAndGivesTheItemFormOfGold(GameTestHelper helper) {
        cell(helper, call(name(helper, "Gold Mill"), "machine", "turn_into",
                        params("minecraft:cobblestone", "gold", null)),
                (p, id) -> {
                    give(p, 1, Items.COBBLESTONE, 2);
                    helper.setBlock(FEET.east(), Blocks.COBBLESTONE);
                    pull(helper, p, id);
                },
                (p, id) -> {
                    helper.assertTrue(has(p, Items.GOLD_INGOT) == 1,
                            Component.literal("one pull, one gold INGOT (gold in his hand): " + has(p, Items.GOLD_INGOT)));
                    helper.assertTrue(has(p, Items.COBBLESTONE) == 1, Component.literal("one taken per use, not both"));
                    helper.assertTrue(has(p, Items.GOLD_BLOCK) == 0, Component.literal("never the block form in his bag"));
                    is(helper, FEET.east(), Blocks.COBBLESTONE, "a machine never touches a block");
                });
    }

    @GameTest(template = "empty_test", batch = BAG, timeoutTicks = 900)
    public static void aMachineWithNothingInHisBagSaysInYourInventory(GameTestHelper helper) {
        cell(helper, call(name(helper, "Empty Mill"), "machine", "turn_into",
                        params("minecraft:cobblestone", "minecraft:glass", null)),
                (p, id) -> pull(helper, p, id),
                (p, id) -> {
                    String s = heard(p, new StringBuilder());
                    helper.assertTrue(s.contains("You need") && s.contains("Cobblestone") && s.contains("in your inventory"),
                            Component.literal("an empty pull must say what he needs, IN HIS INVENTORY: " + s.replace('\n', '|')));
                    helper.assertTrue(has(p, Items.GLASS) == 0, Component.literal("and hand over nothing"));
                });
    }

    @GameTest(template = "empty_test", batch = BAG, timeoutTicks = 900)
    public static void severalFromsTakeTheFirstOneHeHas(GameTestHelper helper) {
        cell(helper, call(name(helper, "Two Mill"), "machine", "turn_into",
                        params("minecraft:cobblestone, minecraft:dirt", "minecraft:glass", null)),
                (p, id) -> {
                    give(p, 1, Items.DIRT, 3);
                    pull(helper, p, id);
                },
                (p, id) -> {
                    helper.assertTrue(has(p, Items.GLASS) == 1 && has(p, Items.DIRT) == 2,
                            Component.literal("holding only the SECOND, it takes that: glass " + has(p, Items.GLASS)
                                    + ", dirt " + has(p, Items.DIRT)));
                });
    }

    @GameTest(template = "empty_test", batch = BAG, timeoutTicks = 900)
    public static void aFurnaceMachineSmeltsEachIntoItsOwnAndSaysWhatDoesNot(GameTestHelper helper) {
        cell(helper, call(name(helper, "Pocket Furnace"), "machine", "turn_into",
                        params("[\"minecraft:raw_iron\",\"minecraft:oak_log\",\"minecraft:sand\",\"minecraft:dirt\"]",
                                "furnace", null)),
                (p, id) -> {
                    give(p, 1, Items.RAW_IRON, 1);
                    give(p, 2, Items.OAK_LOG, 1);
                    give(p, 3, Items.SAND, 1);
                    pull(helper, p, id);
                    pull(helper, p, id);
                    pull(helper, p, id);
                    give(p, 4, Items.DIRT, 1);
                    heard(p, new StringBuilder());
                    pull(helper, p, id);
                },
                (p, id) -> {
                    helper.assertTrue(has(p, Items.IRON_INGOT) == 1 && has(p, Items.CHARCOAL) == 1 && has(p, Items.GLASS) == 1,
                            Component.literal("raw iron, a log and sand became an ingot, charcoal and glass: "
                                    + has(p, Items.IRON_INGOT) + "/" + has(p, Items.CHARCOAL) + "/" + has(p, Items.GLASS)));
                    helper.assertTrue(has(p, Items.RAW_IRON) + has(p, Items.OAK_LOG) + has(p, Items.SAND) == 0,
                            Component.literal("each taken once"));
                    String s = heard(p, new StringBuilder());
                    helper.assertTrue(has(p, Items.DIRT) == 1 && s.contains("A furnace makes nothing from"),
                            Component.literal("dirt smelts into nothing, it stays, and the use SAYS so: " + s.replace('\n', '|')));
                });
    }

    @GameTest(template = "empty_test", batch = BAG, timeoutTicks = 300)
    public static void aMachineOfOnlyAnyBlockOrOfAirIsRefusedWithASentence(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            helper.succeed();
            return;
        }
        ToolResult any = new MakeItemTool().call(call(name(helper, "Any Mill"), "machine", "turn_into",
                params("any_block", "minecraft:glass", null)));
        helper.assertTrue(any.isError() && any.text().contains("CARRIES"),
                Component.literal("a machine of any_block takes nothing from his bag: " + any.text()));
        ToolResult air = new MakeItemTool().call(call(name(helper, "Air Mill"), "machine", "turn_into",
                params("minecraft:dirt", "minecraft:air", null)));
        helper.assertTrue(air.isError() && air.text().contains("no form he can carry"),
                Component.literal("a machine that makes air hands over nothing: " + air.text()));
        ToolResult apple = new MakeItemTool().call(call(name(helper, "Fruit Bomb"), "thrown", "turn_into",
                params("minecraft:stone", "minecraft:apple", 1)));
        helper.assertTrue(apple.isError() && apple.text().contains("no block form"),
                Component.literal("a thrown thing whose TO is no block changes nothing: " + apple.text()));
        // routing: "a wand that turns zombies into chickens" reached turn_into 8 in 8. A creature is neither a
        // block nor an item, and the refusal says so in words the agent can act on.
        ToolResult mob = new MakeItemTool().call(call(name(helper, "Flop Wand"), "held", "turn_into",
                params("minecraft:zombie", "minecraft:chicken", 0)));
        helper.assertTrue(mob.isError() && mob.text().contains("CREATURE") && mob.text().contains("author_datapack"),
                Component.literal("a creature is never a FROM: " + mob.text()));
        helper.succeed();
    }

    // ── held: the aim decides ─────────────────────────────────────────────────────────────────────

    @GameTest(template = "empty_test", batch = "turn_into_held_match", timeoutTicks = 900)
    public static void heldAtAMatchingBlockChangesItAndLeavesHisBag(GameTestHelper helper) {
        String n = name(helper, "Midas Wand");
        cell(helper, call(n, "held", "turn_into", params("minecraft:stone", "gold", 0)),
                (p, id) -> {
                    helper.setBlock(AIMED, Blocks.STONE);
                    helper.setBlock(AIMED.east(), Blocks.STONE);
                    give(p, 1, Items.STONE, 2);
                    click(helper, p, id, n);
                },
                (p, id) -> {
                    is(helper, AIMED, Blocks.GOLD_BLOCK, "the stone he pointed at is a gold BLOCK");
                    is(helper, AIMED.east(), Blocks.STONE, "RADIUS 0: only that one");
                    helper.assertTrue(has(p, Items.STONE) == 2 && has(p, Items.GOLD_INGOT) == 0,
                            Component.literal("NEVER both: the stone in his bag is untouched when a block changed"));
                });
    }

    @GameTest(template = "empty_test", batch = "turn_into_held_other", timeoutTicks = 900)
    public static void heldAtAnotherBlockWorksOnHisBag(GameTestHelper helper) {
        String n = name(helper, "Midas Staff");
        cell(helper, call(n, "held", "turn_into", params("minecraft:stone", "gold", 0)),
                (p, id) -> {
                    helper.setBlock(AIMED, Blocks.DIRT);
                    give(p, 1, Items.STONE, 2);
                    click(helper, p, id, n);
                },
                (p, id) -> {
                    is(helper, AIMED, Blocks.DIRT, "a block not in FROM is never changed");
                    helper.assertTrue(has(p, Items.GOLD_INGOT) == 1 && has(p, Items.STONE) == 1,
                            Component.literal("pointed at dirt: one stone in his bag became a gold INGOT"));
                });
    }

    @GameTest(template = "empty_test", batch = "turn_into_held_air", timeoutTicks = 900)
    public static void heldAtTheAirWorksOnHisBag(GameTestHelper helper) {
        String n = name(helper, "Sky Wand");
        cell(helper, call(n, "held", "turn_into", params("minecraft:sand", "minecraft:glass", 0)),
                (p, id) -> {
                    give(p, 1, Items.SAND, 1);
                    click(helper, p, id, n);
                },
                (p, id) -> helper.assertTrue(has(p, Items.GLASS) == 1 && has(p, Items.SAND) == 0,
                        Component.literal("pointed at nothing: his sand became glass — glass " + has(p, Items.GLASS)
                                + ", sand " + has(p, Items.SAND) + ", heard: " + heard(p, new StringBuilder()).replace('\n', '|'))));
    }

    @GameTest(template = "empty_test", batch = HELD, timeoutTicks = 900)
    public static void heldWithAnyBlockNeverTouchesAChestOrBedrock(GameTestHelper helper) {
        String n = name(helper, "Glass Wand");
        cell(helper, call(n, "held", "turn_into", params("any_block", "minecraft:glass", 0)),
                (p, id) -> {
                    helper.setBlock(AIMED, Blocks.CHEST);
                    click(helper, p, id, n);
                    p.snapTo(p.getX() + 2, p.getY(), p.getZ(), 0.0F, 0.0F);
                    helper.setBlock(AIMED.east().east(), Blocks.BEDROCK);
                    click(helper, p, id, n);
                    p.snapTo(p.getX() - 1, p.getY(), p.getZ(), 0.0F, 0.0F);
                    helper.setBlock(AIMED.east(), Blocks.DIRT);
                    click(helper, p, id, n);
                },
                (p, id) -> {
                    is(helper, AIMED, Blocks.CHEST, "any_block never changes a chest");
                    is(helper, AIMED.east().east(), Blocks.BEDROCK, "or bedrock");
                    is(helper, AIMED.east(), Blocks.GLASS, "…while ordinary dirt does change (the control)");
                });
    }

    @GameTest(template = "empty_test", batch = "turn_into_held_itemonly", timeoutTicks = 900)
    public static void heldWithAnItemOnlyToNeverChangesABlock(GameTestHelper helper) {
        String n = name(helper, "Fruit Wand");
        cell(helper, call(n, "held", "turn_into", params("minecraft:stone", "minecraft:apple", 0)),
                (p, id) -> {
                    helper.setBlock(AIMED, Blocks.STONE);
                    give(p, 1, Items.STONE, 1);
                    click(helper, p, id, n);
                },
                (p, id) -> {
                    is(helper, AIMED, Blocks.STONE, "an apple is no block: the aim never changes one");
                    helper.assertTrue(has(p, Items.APPLE) == 1, Component.literal("it is bag-only: stone → apple — heard: "
                            + heard(p, new StringBuilder()).replace('\n', '|') + " stone " + has(p, Items.STONE)));
                });
    }

    /** The island has no block guard; this is the line a held use can reach — six blocks, no further. */
    @GameTest(template = "empty_test", batch = HELD, timeoutTicks = 900)
    public static void aHeldUseNeverChangesABlockPastSixBlocks(GameTestHelper helper) {
        String n = name(helper, "Long Wand");
        cell(helper, call(n, "held", "turn_into", params("minecraft:stone", "minecraft:glass", 0)),
                (p, id) -> {
                    helper.setBlock(new BlockPos(1, 3, 8), Blocks.STONE);   // 7 ahead of his eyes
                    click(helper, p, id, n);
                },
                (p, id) -> is(helper, new BlockPos(1, 3, 8), Blocks.STONE,
                        "a block 7 blocks ahead is out of a held thing's reach"));
    }

    @GameTest(template = "empty_test", batch = HELD, timeoutTicks = 900)
    public static void onUseDoneAimsWithRadiusZeroToo(GameTestHelper helper) {
        // radiusRefusal refused 0 for on_use_done although it walks the same line of sight.
        if (!CbScriptCompiler.available()) {
            helper.succeed();
            return;
        }
        ToolResult r = new MakeItemTool().call(call(name(helper, "Brush"), "on_use_done", "turn_into",
                params("minecraft:dirt", "minecraft:grass_block", 0)));
        helper.assertTrue(!r.isError(), Component.literal("on_use_done aims exactly, so RADIUS 0 is allowed: " + r.text()));
        helper.succeed();
    }

    // ── the world: blocks where it acts ───────────────────────────────────────────────────────────

    @GameTest(template = "empty_test", batch = WORLD, timeoutTicks = 900)
    public static void thrownChangesTheBlocksWhereItLandsAndNeverHisBag(GameTestHelper helper) {
        BlockPos land = new BlockPos(4, 2, 4);
        cell(helper, call(name(helper, "Gold Bomb"), "thrown", "turn_into", params("minecraft:stone", "gold", 1)),
                (p, id) -> {
                    helper.setBlock(land, Blocks.STONE);
                    helper.setBlock(land.east(), Blocks.DIRT);
                    give(p, 1, Items.STONE, 2);
                    fire(helper, p, id, land);
                },
                (p, id) -> {
                    is(helper, land, Blocks.GOLD_BLOCK, "where it landed, stone is a gold block");
                    is(helper, land.east(), Blocks.DIRT, "only FROM changes");
                    helper.assertTrue(has(p, Items.STONE) == 2, Component.literal("a thrown thing never takes from his bag"));
                });
    }

    @GameTest(template = "empty_test", batch = WORLD, timeoutTicks = 900)
    public static void onHitWithATagChangesEveryLogItHits(GameTestHelper helper) {
        BlockPos hit = new BlockPos(4, 2, 4);
        cell(helper, call(name(helper, "Glass Axe"), "on_hit", "turn_into", params("#minecraft:logs", "minecraft:glass", 1)),
                (p, id) -> {
                    helper.setBlock(hit, Blocks.OAK_LOG);
                    helper.setBlock(hit.above(), Blocks.BIRCH_LOG);
                    helper.setBlock(hit.east(), Blocks.OAK_PLANKS);
                    fire(helper, p, id, hit);
                },
                (p, id) -> {
                    is(helper, hit, Blocks.GLASS, "an oak log is in #minecraft:logs");
                    is(helper, hit.above(), Blocks.GLASS, "so is a birch one");
                    is(helper, hit.east(), Blocks.OAK_PLANKS, "planks are not logs");
                });
    }

    @GameTest(template = "empty_test", batch = WORLD, timeoutTicks = 900)
    public static void vanishMakesBlocksDisappear(GameTestHelper helper) {
        BlockPos land = new BlockPos(4, 2, 4);
        cell(helper, call(name(helper, "Vanish Ball"), "thrown", "turn_into", params("minecraft:dirt", "minecraft:air", 1)),
                (p, id) -> {
                    helper.setBlock(land, Blocks.DIRT);
                    helper.setBlock(land.east(), Blocks.STONE);
                    fire(helper, p, id, land);
                },
                (p, id) -> {
                    is(helper, land, Blocks.AIR, "dirt vanished");
                    is(helper, land.east(), Blocks.STONE, "stone did not");
                });
    }

    @GameTest(template = "empty_test", batch = WORLD, timeoutTicks = 900)
    public static void aThrownFurnaceSmeltsBlocksOnceAndLeavesWhatHasNoBlock(GameTestHelper helper) {
        BlockPos land = new BlockPos(4, 2, 4);
        cell(helper, call(name(helper, "Fire Bomb"), "thrown", "turn_into",
                        params("minecraft:sand, minecraft:cobblestone, minecraft:stone, minecraft:oak_log", "furnace", 1)),
                (p, id) -> {
                    helper.setBlock(land, Blocks.SAND);
                    helper.setBlock(land.east(), Blocks.COBBLESTONE);
                    helper.setBlock(land.west(), Blocks.OAK_LOG);
                    helper.setBlock(land.above(), Blocks.STONE);
                    fire(helper, p, id, land);
                },
                (p, id) -> {
                    is(helper, land, Blocks.GLASS, "sand smelts into glass");
                    is(helper, land.east(), Blocks.STONE, "cobblestone into stone — ONCE, not on to smooth stone");
                    is(helper, land.above(), Blocks.SMOOTH_STONE, "stone into smooth stone");
                    is(helper, land.west(), Blocks.OAK_LOG, "a log has no block result: unchanged");
                });
    }

    // ── the old names ─────────────────────────────────────────────────────────────────────────────

    @GameTest(template = "empty_test", batch = WORLD, timeoutTicks = 900)
    public static void anOldConvertCallGroupedStillMakesAWorkingMachine(GameTestHelper helper) {
        cell(helper, call(name(helper, "Old Mill"), "machine", "convert",
                        "{\"convert\":{\"INPUT\":\"minecraft:wheat\",\"OUTPUT\":\"minecraft:bread\"}}"),
                (p, id) -> {
                    give(p, 1, Items.WHEAT, 1);
                    pull(helper, p, id);
                },
                (p, id) -> helper.assertTrue(has(p, Items.BREAD) == 1 && has(p, Items.WHEAT) == 0,
                        Component.literal("convert's INPUT/OUTPUT, grouped under the old name, made a working machine")));
    }

    @GameTest(template = "empty_test", batch = WORLD, timeoutTicks = 900)
    public static void anOldChangeBlockCallFlatStillMakesAWorkingThrow(GameTestHelper helper) {
        BlockPos land = new BlockPos(4, 2, 4);
        cell(helper, call(name(helper, "Old Bomb"), "thrown", "change_block",
                        "{\"FROM\":\"minecraft:dirt\",\"BLOCK\":\"minecraft:glass\",\"RADIUS\":\"1\"}"),
                (p, id) -> {
                    helper.setBlock(land, Blocks.DIRT);
                    fire(helper, p, id, land);
                },
                (p, id) -> is(helper, land, Blocks.GLASS, "change_block's flat BLOCK became turn_into's TO"));
    }

    // ── the furnace table cannot drift from the game ─────────────────────────────────────────────

    @GameTest(template = "empty_test", batch = WORLD, timeoutTicks = 200)
    public static void theSmeltingTableIsWhatTheServersFurnaceMakes(GameTestHelper helper) {
        var level = helper.getLevel();
        var recipes = level.getServer().getRecipeManager();
        TurnInto.Smelting t = TurnInto.Smelting.load();
        StringBuilder bad = new StringBuilder();
        for (Map.Entry<String, String> row : t.items().entrySet()) {
            Item in = BuiltInRegistries.ITEM.getValue(ResourceLocation.parse(row.getKey()));
            SingleRecipeInput input = new SingleRecipeInput(new ItemStack(in));
            var r = recipes.getRecipeFor(RecipeType.SMELTING, input, level);
            String got = r.map(h -> BuiltInRegistries.ITEM.getKey(
                    h.value().assemble(input, level.registryAccess()).getItem()).toString()).orElse("nothing");
            if (!got.equals(row.getValue())) {
                bad.append("\n  ").append(row.getKey()).append(": table ").append(row.getValue()).append(", furnace ").append(got);
            }
        }
        for (Map.Entry<String, String> row : t.blocks().entrySet()) {
            if (!row.getValue().equals(t.items().get(row.getKey()))
                    || BuiltInRegistries.BLOCK.getValue(ResourceLocation.parse(row.getValue())) == Blocks.AIR) {
                bad.append("\n  block ").append(row.getKey()).append(" -> ").append(row.getValue());
            }
        }
        helper.assertTrue(bad.length() == 0, Component.literal("items/smelting.json has drifted from this "
                + "Minecraft's furnace — run tools/gen-smelting-table.py:" + bad));
        helper.assertTrue(t.items().size() > 100, Component.literal("a table that small proves nothing: " + t.items().size()));
        helper.succeed();
    }
}
