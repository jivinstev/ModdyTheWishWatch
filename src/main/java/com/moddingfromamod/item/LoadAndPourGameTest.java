package com.moddingfromamod.item;

import com.moddingfromamod.testing.gt.GameTestCompat;
import net.minecraft.network.chat.Component;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.MakeItemTool;
import com.moddingfromamod.datapack.CbScriptCompiler;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>A machine you FILL, then it POURS</b> (the class "machines you fill").
 *
 * <p>The real {@code make_item} builds a {@code load_and_pour} machine, the real give hands it over,
 * and a real player puts it down and works the real handle — so every line of the generated pack
 * runs exactly as a child meets it. One walk through his whole journey, because each step's words
 * are only meaningful against the one before:
 *
 * <ol>
 *   <li>pulled empty, it says what it takes (C18: never silent);</li>
 *   <li>holding two ice, it takes them, shows {@code Ice 2/3} and says what is still missing;</li>
 *   <li>pulled empty again, it says what is missing AND what is already in;</li>
 *   <li>loaded with the rest and an extra, it says it is ready;</li>
 *   <li>a chest machine with no chest refuses by naming the next action, and keeps the load;</li>
 *   <li>with a chest, it pours a named, sized thing INTO the chest — "Medium … with Snowball";</li>
 *   <li>a full chest refuses rather than dropping it;</li>
 *   <li>sneak-pulling prints the how-to; the sign's recipe lines match the spec;</li>
 *   <li>a SECOND machine of the same pack, somewhere else, keeps its own load and its own chest —
 *       the hard-coded-coordinates bug the real tree grinder had, disproved;</li>
 *   <li>the undo ({@code remove_item}) takes BOTH placed machines down and hands nothing back.</li>
 * </ol>
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class LoadAndPourGameTest {

    private static String heard(ServerPlayer p, StringBuilder into) {
        var channel = p.connection.getConnection().channel();
        if (!(channel instanceof io.netty.channel.embedded.EmbeddedChannel embedded)) {
            throw GameTestCompat.assertion("this test cannot see the wire: " + channel);
        }
        Object o;
        while ((o = embedded.readOutbound()) != null) {
            if (o instanceof ClientboundSystemChatPacket chat) {
                into.append(chat.content().getString()).append('\n');
            } else if (o instanceof ClientboundSetActionBarTextPacket bar) {
                into.append(bar.text().getString()).append('\n');
            }
        }
        return into.toString();
    }

    private static BlockPos findLever(GameTestHelper helper, BlockPos around) {
        for (BlockPos q : BlockPos.betweenClosed(around.offset(-2, 0, -2), around.offset(2, 3, 2))) {
            if (helper.getLevel().getBlockState(q).is(Blocks.LEVER)) {
                return q.immutable();
            }
        }
        return null;
    }

    private static void pull(ServerPlayer p, BlockPos lever) {
        p.gameMode.useItemOn(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(lever), Direction.UP, lever, false));
    }

    /** How many of a plain vanilla item he is carrying, over every slot. */
    private static int count(ServerPlayer p, net.minecraft.world.item.Item item) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.is(item)) {
                n += st.getCount();
            }
        }
        return n;
    }

    private static void place(ServerPlayer p, ItemStack machine, BlockPos floor) {
        p.setItemInHand(InteractionHand.MAIN_HAND, machine.copy());
        p.gameMode.useItemOn(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false));
    }

    /** Everything he is carrying, as "slot:count name" — so a failure says WHAT, not just that. */
    private static String carrying(ServerPlayer p) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty()) {
                b.append(i).append(':').append(s.getCount()).append(' ').append(s.getHoverName().getString())
                        .append(" (").append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()))
                        .append(")|");
            }
        }
        return b.length() == 0 ? "nothing" : b.toString();
    }

    /** What a container holds, as "count name" lines. */
    private static String contents(GameTestHelper helper, BlockPos at) {
        if (!(helper.getLevel().getBlockEntity(at) instanceof Container c)) {
            return "(no container at " + at + ")";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty()) {
                b.append(s.getCount()).append(' ').append(s.getHoverName().getString()).append('|');
            }
        }
        return b.toString();
    }

    private static void waitFor(StringBuilder said, ServerPlayer p, String what, String why) {
        String s = heard(p, said);
        if (!s.contains(what)) {
            throw GameTestCompat.assertion(why + " — expected '" + what + "'. Heard: "
                    + s.replace('\n', '|'));
        }
    }

    @GameTest(template = "empty_test", batch = "load_and_pour", timeoutTicks = 1200)
    public static void aMachineYouFillLoadsExplainsAndPoursIntoItsOwnChest(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            System.out.println("LOAD AND POUR: SKIPPED — no CBScript compiler. This proved NOTHING.");
            helper.succeed();
            return;
        }
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.asSurvival(MockPlayerNetwork.inLevel(helper));
        BlockPos floorA = helper.absolutePos(new BlockPos(2, 1, 2));
        BlockPos floorB = helper.absolutePos(new BlockPos(6, 1, 2));
        helper.getLevel().setBlockAndUpdate(floorA, Blocks.STONE.defaultBlockState());
        helper.getLevel().setBlockAndUpdate(floorB, Blocks.STONE.defaultBlockState());
        p.snapTo(floorA.getX() + 0.5, floorA.getY() + 1, floorA.getZ() + 3.5, 180.0F, 0.0F);
        p.setYHeadRot(180.0F);

        String name = "Pour Tester " + helper.getLevel().getGameTime();
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("icon", "orb");
        o.addProperty("colour", "#3BB5E3");
        o.addProperty("shape", "machine");
        o.addProperty("effect", "load_and_pour");
        o.addProperty("count", 2);
        JsonObject params = new JsonObject();
        params.addProperty("INGREDIENTS", "minecraft:ice, minecraft:sugar");
        params.addProperty("EXTRAS", "minecraft:snowball");
        params.addProperty("OUTPUT", "minecraft:mushroom_stew");
        params.addProperty("MAX", 3);
        params.addProperty("WHERE", "chest");
        o.add("params", params);
        MakeItemTool tool = new MakeItemTool();
        ToolResult r = tool.call(o);
        helper.assertTrue(!r.isError(), Component.literal("make_item refused the load_and_pour machine: " + r.text()));

        p.getInventory().clearContent();
        helper.getLevel().getServer().getCommands().performPrefixedCommand(
                p.createCommandSourceStack().withPermission(2).withSuppressedOutput(),
                "wishdatapack " + tool.lastMadeId() + " give");
        ItemStack machine = ItemStack.EMPTY;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty() && s.getHoverName().getString().equals(name)) {
                machine = s.copyWithCount(1);
            }
        }
        helper.assertTrue(!machine.isEmpty(), Component.literal("the real give handed over nothing named " + name));
        // THE STARTER KIT, through the real give: a full load of the main ingredient (MAX 3) and
        // one of everything else, extras included — never a search before the first pour.
        helper.assertTrue(count(p, Items.ICE) == 3 && count(p, Items.SUGAR) == 1
                        && count(p, Items.SNOWBALL) == 1,
                Component.literal("the give did not hand over the kit (3 ice, 1 sugar, 1 snowball): ice="
                        + count(p, Items.ICE) + " sugar=" + count(p, Items.SUGAR)
                        + " snowball=" + count(p, Items.SNOWBALL)));
        p.getInventory().clearContent();
        place(p, machine, floorA);
        final ItemStack oneMachine = machine;

        StringBuilder said = new StringBuilder();
        BlockPos[] lever = new BlockPos[2];
        java.util.concurrent.atomic.AtomicReference<ToolResult> undone = new java.util.concurrent.atomic.AtomicReference<>();
        BlockPos baseA = floorA.above();
        BlockPos chestA = baseA.east();
        BlockPos baseB = floorB.above();
        BlockPos chestB = baseB.east();
        helper.startSequence()
                .thenWaitUntil(() -> {
                    lever[0] = findLever(helper, floorA);
                    if (lever[0] == null) {
                        throw GameTestCompat.assertion("the machine was put down and no handle appeared");
                    }
                })
                .thenExecute(() -> {
                    // THE SIGN SAYS THE RECIPE (lines 2-3 were blank).
                    boolean found = false;
                    for (BlockPos q : BlockPos.betweenClosed(floorA.offset(-2, 0, -2), floorA.offset(2, 4, 2))) {
                        if (helper.getLevel().getBlockEntity(q) instanceof SignBlockEntity sign) {
                            String l2 = sign.getFrontText().getMessage(1, false).getString();
                            String l3 = sign.getFrontText().getMessage(2, false).getString();
                            helper.assertTrue(l2.equals("ice + sugar"), Component.literal("sign line 2 is '" + l2 + "'"));
                            helper.assertTrue(l3.contains("Mushroom Stew"), Component.literal("sign line 3 is '" + l3 + "'"));
                            found = true;
                        }
                    }
                    helper.assertTrue(found, Component.literal("no sign on the machine"));
                    // 1: EMPTY
                    p.getInventory().clearContent();
                    heard(p, new StringBuilder());
                    pull(p, lever[0]);
                })
                .thenWaitUntil(() -> waitFor(said, p, "It takes Ice and Sugar",
                        "pulled empty and it did not say what it takes"))
                .thenExecute(() -> {
                    // 2: TWO ICE
                    said.setLength(0);
                    p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.ICE, 2));
                    pull(p, lever[0]);
                })
                .thenWaitUntil(() -> waitFor(said, p, "Still needs Sugar",
                        "loaded ice and it did not say sugar was still missing"))
                .thenExecute(() -> {
                    String s = said.toString();
                    helper.assertTrue(s.contains("Ice 2/3"), Component.literal("no progress 'Ice 2/3': " + s.replace('\n', '|')));
                    helper.assertTrue(p.getInventory().countItem(Items.ICE) == 0,
                            Component.literal("the ice was not taken from him"));
                    // 3: EMPTY AGAIN: what is missing AND what is in
                    said.setLength(0);
                    pull(p, lever[0]);
                })
                .thenWaitUntil(() -> waitFor(said, p, "Needs Sugar! (has 2 Ice)",
                        "an empty pull on a half-loaded machine must say what is in and what is missing"))
                .thenExecute(() -> {
                    // 4: THE REST, AND AN EXTRA
                    said.setLength(0);
                    p.getInventory().setItem(0, new ItemStack(Items.SUGAR, 1));
                    p.getInventory().setItem(1, new ItemStack(Items.SNOWBALL, 1));
                    pull(p, lever[0]);
                })
                .thenWaitUntil(() -> waitFor(said, p, "Ready!", "fully loaded and it did not say it was ready"))
                .thenExecute(() -> {
                    // 5: NO CHEST: refuse by the next action, keep the load
                    said.setLength(0);
                    pull(p, lever[0]);
                })
                .thenWaitUntil(() -> waitFor(said, p, "Put a chest next to me",
                        "a chest machine with no chest poured anyway, or said nothing"))
                .thenExecute(() -> {
                    helper.assertFalse(p.getInventory().countItem(Items.MUSHROOM_STEW) > 0,
                            Component.literal("a chest machine handed the output to him instead"));
                    // 6: A CHEST
                    helper.getLevel().setBlockAndUpdate(chestA, Blocks.CHEST.defaultBlockState());
                    said.setLength(0);
                    pull(p, lever[0]);
                })
                .thenWaitUntil(() -> {
                    String in = contents(helper, chestA);
                    if (!in.contains("Medium Mushroom Stew with Snowball")) {
                        throw GameTestCompat.assertion("the chest holds '" + in + "', not a Medium "
                                + "Mushroom Stew with Snowball. Heard: " + heard(p, said).replace('\n', '|'));
                    }
                })
                .thenWaitUntil(() -> waitFor(said, p, "in the chest", "poured without saying where it went"))
                .thenExecute(() -> {
                    // 7: A FULL CHEST refuses rather than dropping it
                    if (helper.getLevel().getBlockEntity(chestA) instanceof Container c) {
                        for (int i = 0; i < c.getContainerSize(); i++) {
                            if (c.getItem(i).isEmpty()) {
                                c.setItem(i, new ItemStack(Items.DIRT, 64));
                            }
                        }
                    }
                    p.getInventory().setItem(0, new ItemStack(Items.ICE, 1));
                    p.getInventory().setItem(1, new ItemStack(Items.SUGAR, 1));
                    pull(p, lever[0]);          // loads
                    said.setLength(0);
                    pull(p, lever[0]);          // tries to pour into a full chest
                })
                .thenWaitUntil(() -> waitFor(said, p, "The chest is full", "a full chest was not refused"))
                .thenExecute(() -> {
                    // 8: SNEAK-PULL IS THE HOW-TO
                    said.setLength(0);
                    // A real sneak: the predicate reads the CROUCHING pose, and vanilla only lets a
                    // sneaking player work a block with an empty hand.
                    p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    p.setShiftKeyDown(true);
                    p.setPose(net.minecraft.world.entity.Pose.CROUCHING);
                    pull(p, lever[0]);
                    p.setShiftKeyDown(false);
                    p.setPose(net.minecraft.world.entity.Pose.STANDING);
                })
                .thenWaitUntil(() -> waitFor(said, p, "hold Ice and Sugar and pull the handle",
                        "a sneak-pull printed no how-to"))
                .thenExecute(() -> {
                    // 9: A SECOND MACHINE, ELSEWHERE, keeps its own load and its own chest
                    p.snapTo(floorB.getX() + 0.5, floorB.getY() + 1, floorB.getZ() + 3.5, 180.0F, 0.0F);
                    place(p, oneMachine, floorB);
                    helper.getLevel().setBlockAndUpdate(chestB, Blocks.CHEST.defaultBlockState());
                })
                .thenWaitUntil(() -> {
                    lever[1] = findLever(helper, floorB);
                    if (lever[1] == null) {
                        throw GameTestCompat.assertion("the second machine grew no handle");
                    }
                })
                .thenExecute(() -> {
                    p.getInventory().clearContent();
                    p.getInventory().setItem(0, new ItemStack(Items.ICE, 5));
                    p.getInventory().setItem(1, new ItemStack(Items.SUGAR, 1));
                    pull(p, lever[1]);          // loads 3 ice (its cap) and the sugar
                    pull(p, lever[1]);          // nothing new fits: pours
                })
                .thenWaitUntil(() -> {
                    String in = contents(helper, chestB);
                    if (!in.contains("Big Mushroom Stew")) {
                        throw GameTestCompat.assertion("the second machine's chest holds '" + in + "'");
                    }
                })
                .thenExecute(() -> {
                    helper.assertTrue(p.getInventory().countItem(Items.ICE) == 2,
                            Component.literal("a machine that holds 3 took " + (5 - p.getInventory().countItem(Items.ICE))
                                    + " ice"));
                    helper.assertFalse(contents(helper, chestB).contains("with Snowball"),
                            Component.literal("machine B poured machine A's extra — the two share one load"));
                    // 10: THE UNDO takes both placed machines down and hands nothing back
                    p.getInventory().clearContent();
                    JsonObject rm = new JsonObject();
                    rm.addProperty("id", tool.lastMadeId());
                    // off the server thread, exactly as the agent calls it
                    new Thread(() -> undone.set(new com.moddingfromamod.agent.tools.RemoveItemTool().call(rm)),
                            "load-and-pour-undo").start();
                })
                .thenWaitUntil(() -> {
                    ToolResult gone = undone.get();
                    if (gone == null) {
                        throw GameTestCompat.assertion("remove_item has not answered yet");
                    }
                    helper.assertTrue(!gone.isError() && gone.text().contains("2 placed"),
                            Component.literal("remove_item did not take the placed machines away: " + gone.text()));
                    helper.assertTrue(findLever(helper, floorA) == null && findLever(helper, floorB) == null,
                            Component.literal("a handle is still standing after the undo"));
                    helper.assertTrue(helper.getLevel().getBlockState(baseA).isAir()
                            && helper.getLevel().getBlockState(baseB).isAir(),
                            Component.literal("a machine body is still standing after the undo"));
                })
                .thenExecute(() -> {
                    helper.assertTrue(p.getInventory().isEmpty(),
                            Component.literal("the undo handed a machine back — he is carrying " + carrying(p)));
                    MockPlayerNetwork.remove(p);
                })
                .thenSucceed();
    }

    /**
     * A MADE output, with variants, poured under its sized name. The first test pours a
     * vanilla item; this is the case the real grant produced, replayed call for call from its
     * archive (corpus/wish-runs/2026-09-28-machines-you-fill, run chain5) — and before the fix the
     * pour named the item twice, which does not parse, so the machine poured NOTHING.
     */
    @GameTest(template = "empty_test", batch = "load_and_pour_made", timeoutTicks = 800)
    public static void aMadeDrinkIsPouredUnderItsSizedName(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            System.out.println("LOAD AND POUR (made): SKIPPED — no CBScript compiler. This proved NOTHING.");
            helper.succeed();
            return;
        }
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.asSurvival(MockPlayerNetwork.inLevel(helper));
        BlockPos floor = helper.absolutePos(new BlockPos(2, 1, 2));
        helper.getLevel().setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        p.snapTo(floor.getX() + 0.5, floor.getY() + 1, floor.getZ() + 3.5, 180.0F, 0.0F);
        p.setYHeadRot(180.0F);
        MakeItemTool tool = new MakeItemTool();
        String[] grant = {
            "{\"name\":\"Gate Slushie\",\"icon\":\"cup\",\"colour\":\"#4FC3F7\",\"count\":1,\"shape\":\"plain\",\"from_scratch\":true,"
                    + "\"variants\":[{\"name\":\"Small Slushie\",\"colour\":\"#81D4FA\"},{\"name\":\"Medium Slushie\",\"colour\":\"#29B6F6\"},"
                    + "{\"name\":\"GIANT Slushie\",\"colour\":\"#0277BD\"}]}",
            "{\"start_from\":\"slushie_plain\",\"shape\":\"on_consume\",\"effect\":\"food\",\"params\":{\"BONUS\":\"minecraft:speed\",\"SECONDS\":\"30\"}}",
            "{\"name\":\"Slushie Machine\",\"icon\":\"machine\",\"colour\":\"#4FC3F7\",\"count\":1,\"shape\":\"machine\","
                    + "\"effect\":\"load_and_pour\",\"params\":{\"INGREDIENTS\":\"minecraft:ice\",\"EXTRAS\":\"minecraft:sugar\",\"MAX\":\"3\","
                    + "\"WHERE\":\"hand\",\"OUTPUT\":\"slushie_plain\"},\"from_scratch\":true}"};
        // chain5's grant, with ONE change: the drink is 'Gate Slushie', not 'Slushie'. Since
        // 'slushie_plain' is a LIBRARY starter, and ForkPolicy forks a library design only
        // for a bound child — this test binds none, so start_from on the real name is refused.
        // The pour is named size + base name, so it reads 'Big Gate Slushie with Sugar'. And the world is reused, so
        // follow the id the first call REALLY made rather than the one the grant spelled.
        String drink = null;
        for (String call : grant) {
            if (drink != null) {
                call = call.replace("\"slushie_plain\"", "\"" + drink + "\"");
            }
            ToolResult r = tool.call(com.google.gson.JsonParser.parseString(call).getAsJsonObject());
            helper.assertTrue(!r.isError(), Component.literal("the replayed grant was refused: " + r.text()));
            if (drink == null) {
                drink = tool.lastMadeId();
            }
        }
        final String drinkId = drink;
        p.getInventory().clearContent();
        helper.getLevel().getServer().getCommands().performPrefixedCommand(
                p.createCommandSourceStack().withPermission(2).withSuppressedOutput(),
                "wishdatapack " + tool.lastMadeId() + " give");
        ItemStack machine = ItemStack.EMPTY;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!st.isEmpty() && st.getHoverName().getString().equals("Slushie Machine")) {
                machine = st.copyWithCount(1);
            }
        }
        helper.assertTrue(!machine.isEmpty(), Component.literal("the real give handed over no Slushie Machine"));
        // Keep the KIT, take only the machine: what the give handed over must be enough for the
        // biggest pour by itself (follow-up), so nothing below adds an ingredient.
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            if (p.getInventory().getItem(i).getHoverName().getString().equals("Slushie Machine")) {
                p.getInventory().setItem(i, ItemStack.EMPTY);
            }
        }
        helper.assertTrue(count(p, Items.ICE) == 3 && count(p, Items.SUGAR) == 1,
                Component.literal("the kit is not one Big pour: ice=" + count(p, Items.ICE) + " sugar=" + count(p, Items.SUGAR)));
        place(p, machine, floor);
        BlockPos[] lever = new BlockPos[1];
        helper.startSequence()
                .thenWaitUntil(() -> {
                    lever[0] = findLever(helper, floor);
                    if (lever[0] == null) {
                        throw GameTestCompat.assertion("no handle appeared");
                    }
                })
                .thenExecute(() -> {
                    pull(p, lever[0]);   // loads the kit: 3 ice (Big) and the sugar
                    pull(p, lever[0]);   // nothing new fits: pours
                })
                .thenWaitUntil(() -> {
                    ItemStack got = ItemStack.EMPTY;
                    for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                        ItemStack st = p.getInventory().getItem(i);
                        if (st.getHoverName().getString().contains("Slushie")) {
                            got = st;
                        }
                    }
                    if (got.isEmpty()) {
                        throw GameTestCompat.assertion("no slushie was poured");
                    }
                    helper.assertTrue(got.getHoverName().getString().equals("Big Gate Slushie with Sugar"),
                            Component.literal("poured '" + got.getHoverName().getString() + "'"));
                    var data = got.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
                    helper.assertTrue(data != null && data.copyTag().toString().contains(drinkId),
                            Component.literal("the poured slushie is not the made drink: " + data));
                })
                .thenExecute(() -> {
                    System.out.println("LOAD AND POUR (made): packs + specs for the starter — "
                            + com.moddingfromamod.item.ItemSpecStore.get(drinkId) + " || "
                            + com.moddingfromamod.item.ItemSpecStore.get(tool.lastMadeId()));
                    MockPlayerNetwork.remove(p);
                })
                .thenSucceed();
    }
}
