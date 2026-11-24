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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>C18, first proof: a machine pulled empty-handed SAYS what it needs</b>.
 *
 * <p>A wished thing with no GUI that does nothing when used looks exactly like a broken wish, and
 * Moddy's chat line from grant time is long gone by then — so the thing has to carry its own
 * instructions. {@code items/convert.cbs} already refuses out loud ({@code convert_needs}: <i>"You
 * need X to use this!"</i>), and nothing had ever asserted it: {@code MachineClientTest} feeds the
 * input, so it never sees a refusal. This is the template later classes copy (the register's C18
 * cell, {@code docs/WISH_CLASS_CONTRACT.md}).
 *
 * <p>Driven the REAL way end to end: {@code make_item} builds the machine, the real give hands it
 * over, a right-click on the floor puts it down, and the lever is pulled through
 * {@code gameMode.useItemOn} — the path that fires vanilla's {@code default_block_use} trigger,
 * which is the only thing that runs the machine at all. What the child is told is read off the
 * mock's own wire, i.e. the packets a real client would be sent.
 *
 * <p>⚠ It is an A/B, and the B half is not decoration: an empty pull that says "You need" proves
 * nothing if a FULL pull says it too. So the same machine is pulled again holding the input, and
 * must hand over the output without refusing.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class MachineExplainsItselfGameTest {

    private static final String INPUT = "minecraft:cocoa_beans";
    private static final String OUTPUT = "minecraft:mushroom_stew";

    /** Every chat and action-bar line on this player's wire since the last read (reads are destructive). */
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

    @GameTest(template = "empty_test", batch = "explains_itself", timeoutTicks = 600)
    public static void aConvertMachinePulledEmptyHandedSaysWhatItNeeds(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            System.out.println("MACHINE EXPLAINS ITSELF: SKIPPED — no CBScript compiler. This proved NOTHING.");
            helper.succeed();
            return;
        }
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.asSurvival(MockPlayerNetwork.inLevel(helper));
        BlockPos floor = helper.absolutePos(new BlockPos(2, 1, 2));
        helper.getLevel().setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        p.snapTo(floor.getX() + 0.5, floor.getY() + 1, floor.getZ() + 3.5, 180.0F, 0.0F);
        p.setYHeadRot(180.0F);

        String name = "Explain Grinder " + helper.getLevel().getGameTime();
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("icon", "orb");
        o.addProperty("colour", "#8B5A2B");
        o.addProperty("shape", "machine");
        o.addProperty("effect", "convert");   // the OLD name and flat INPUT/OUTPUT, on purpose (alias)
        o.addProperty("count", 1);
        JsonObject params = new JsonObject();
        params.addProperty("INPUT", INPUT);
        params.addProperty("OUTPUT", OUTPUT);
        o.add("params", params);
        MakeItemTool tool = new MakeItemTool();
        ToolResult r = tool.call(o);
        helper.assertTrue(!r.isError(), Component.literal("make_item refused the convert machine: " + r.text()));

        p.getInventory().clearContent();
        helper.getLevel().getServer().getCommands().performPrefixedCommand(
                p.createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER).withSuppressedOutput(),
                "wishdatapack " + tool.lastMadeId() + " give");
        ItemStack machine = ItemStack.EMPTY;
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            if (!s.isEmpty() && s.getHoverName().getString().equals(name)) {
                machine = s.copy();
            }
        }
        helper.assertTrue(!machine.isEmpty(), Component.literal("the real give handed over nothing named " + name));
        p.getInventory().clearContent();
        p.setItemInHand(InteractionHand.MAIN_HAND, machine);
        p.gameMode.useItemOn(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false));

        StringBuilder said = new StringBuilder();
        BlockPos[] lever = new BlockPos[1];
        helper.startSequence()
                // The body is built by the pack's per-tick build_new, so wait for the handle.
                .thenWaitUntil(() -> {
                    lever[0] = findLever(helper, floor);
                    if (lever[0] == null) {
                        throw GameTestCompat.assertion("the machine was put down and no handle appeared");
                    }
                })
                .thenExecute(() -> {
                    // A: EMPTY-HANDED. Nothing to convert, so it must say what it needs.
                    p.getInventory().clearContent();
                    p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    heard(p, new StringBuilder());   // drop the grant-time chatter
                    pull(p, lever[0]);
                })
                .thenWaitUntil(() -> {
                    String s = heard(p, said);
                    if (!s.contains("You need")) {
                        throw GameTestCompat.assertion("pulled empty-handed and the machine said "
                                + "nothing that names what it needs — a silent machine reads as a "
                                + "broken wish. Heard: " + s.replace('\n', '|'));
                    }
                })
                .thenExecute(() -> {
                    String s = said.toString();
                    // turn_into's machine (this call is the OLD convert shape, renamed at the
                    // edge) says WHERE he needs it.
                    helper.assertTrue(s.contains("in your inventory"),
                            Component.literal("the refusal must say he needs it IN HIS INVENTORY: " + s.replace('\n', '|')));
                    helper.assertTrue(s.contains("Cocoa Beans"),
                            Component.literal("the refusal must name the INPUT in words he can act on, not an id: "
                                    + s.replace('\n', '|')));
                    helper.assertFalse(s.contains("minecraft:"),
                            Component.literal("the refusal leaks a registry id to a child: " + s.replace('\n', '|')));
                    helper.assertFalse(p.getInventory().contains(new ItemStack(Items.MUSHROOM_STEW)),
                            Component.literal("an empty pull must not hand over the output"));
                    // B: HOLDING THE INPUT. The same pull must now convert and not refuse.
                    said.setLength(0);
                    p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COCOA_BEANS, 2));
                    pull(p, lever[0]);
                })
                .thenWaitUntil(() -> {
                    if (!p.getInventory().contains(new ItemStack(Items.MUSHROOM_STEW))) {
                        throw GameTestCompat.assertion("pulled holding the input and got no output");
                    }
                })
                .thenExecute(() -> {
                    String s = heard(p, said);
                    helper.assertFalse(s.contains("You need"),
                            Component.literal("a FULL pull also said 'You need' — so the empty-handed refusal above "
                                    + "proved nothing about the empty case: " + s.replace('\n', '|')));
                    MockPlayerNetwork.remove(p);
                })
                .thenSucceed();
    }
}
