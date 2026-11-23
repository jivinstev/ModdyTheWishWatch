package com.moddingfromamod.item;

import net.minecraft.network.chat.Component;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.MakeItemTool;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.wish.BuildGate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>A polished item glints for a moment, then is exactly what it was</b>. Through the real
 * {@code make_item} after the wish's finish, as the agent polishes; and the two ways a glint could
 * stick: a stack put away while it glinted, and one whose design had its glint switched OFF.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class PolishGlintGameTest {

    private static JsonObject wand(String name, String colour, String startFrom) {
        JsonObject o = JsonParser.parseString("""
            {"name":"%s","icon":"wand","colour":"%s","shape":"held","effect":"%s","count":1,
             "params":{}}""".formatted(name, colour, anEffectWithNoParams())).getAsJsonObject();
        if (startFrom != null) {
            o.addProperty("start_from", startFrom);
        }
        return o;
    }

    /** A catalogue effect that needs no parameters, so the call cannot be refused for one. */
    private static String anEffectWithNoParams() {
        return com.moddingfromamod.datapack.ItemCatalogue.load().all().stream()
                .filter(e -> e.params().isEmpty())
                .map(com.moddingfromamod.datapack.ItemCatalogue.Entry::id)
                .findFirst().orElse("");
    }

    private static ItemStack held(ServerPlayer p, String featureId) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            var cd = st.get(DataComponents.CUSTOM_DATA);
            if (cd != null && cd.copyTag().toString().contains(featureId)) {
                return st;
            }
        }
        return ItemStack.EMPTY;
    }

    @GameTest(template = "empty_test", batch = "polishGlint", timeoutTicks = 400)
    public static void aPolishedItemGlintsThenIsWhatItWas(GameTestHelper helper) {
        if (anEffectWithNoParams().isEmpty()) {
            helper.fail(Component.literal("the catalogue has no effect without parameters to build a wand from"));
        }
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        String name = "Glint Wand " + Long.toString(System.nanoTime() % 1_000_000_000L, 36);
        ToolResult made = new MakeItemTool().call(wand(name, "purple", null));
        helper.assertTrue(!made.isError(), Component.literal("could not make the wand: " + made.text()));
        var spec = ItemSpecStore.all().stream().filter(s -> name.equals(s.name())).findFirst().orElse(null);
        helper.assertTrue(spec != null, Component.literal("the wand's design was not stored"));
        p.getServer().getCommands().performPrefixedCommand(
                com.moddingfromamod.compat.EntityCommands.source(p).withPermission(4),
                "wishdatapack " + spec.id() + " give");
        helper.assertTrue(!held(p, spec.id()).isEmpty(), Component.literal("he was never handed the wand"));

        // The wish has ended for him; the agent polishes the wand's colour in place.
        UUID wish = UUID.randomUUID();
        String worker = "gt-polish-glint-" + wish;
        Marshal.global().submit(wish, p.getUUID(), "a wand");
        Marshal.global().assign(worker, wish);
        BuildGate.forWish(wish).markFinishBuildingCalled();
        ToolResult polished;
        AgentContext.bind(worker);
        try {
            polished = new MakeItemTool().call(wand(name, "lime", spec.id()));
        } finally {
            AgentContext.unbind();
        }
        helper.assertTrue(!polished.isError(), Component.literal("the polish was refused: " + polished.text()));
        helper.assertTrue(!polished.text().contains("NOW PUT IT IN HIS HAND"),
                Component.literal("a polish after the finish told the agent to hand him another one"));
        ItemStack st = held(p, spec.id());
        helper.assertTrue(Boolean.TRUE.equals(st.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE))
                        && PolishGlintService.glinting(st),
                Component.literal("the stack he holds was polished and does not glint"));
        long glintedAt = helper.getLevel().getGameTime();
        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getLevel().getGameTime() >= glintedAt + PolishGlint.TICKS,
                    Component.literal("still within the glint's time"));
            ItemStack now = held(p, spec.id());
            helper.assertTrue(!PolishGlintService.glinting(now),
                    Component.literal("the glint's marker is still on the stack after its time"));
            helper.assertTrue(now.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE) == null,
                    Component.literal("the wand glints for ever: " + now.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)));
            Marshal.global().finish(wish);
        });
    }

    @GameTest(template = "empty_test", batch = "polishGlint", timeoutTicks = 200)
    public static void aGlintPutAwayIsPutRightWhenTheChestIsOpened(GameTestHelper helper) {
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        BlockPos at = new BlockPos(1, 1, 1);
        helper.setBlock(at, Blocks.CHEST);
        ChestBlockEntity chest = (ChestBlockEntity) helper.getBlockEntity(at, net.minecraft.world.level.block.entity.BlockEntity.class);
        ItemStack st = new ItemStack(net.minecraft.world.item.Items.STICK);
        st.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, false);   // a design with its glint OFF
        PolishGlintService.apply(st, helper.getLevel().getGameTime() - PolishGlint.TICKS - 1);
        helper.assertTrue(Boolean.TRUE.equals(st.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)),
                Component.literal("apply did not glint the stack"));
        chest.setItem(0, st);

        p.openMenu(chest);   // fires PlayerContainerEvent.Open, where a put-away glint is settled

        ItemStack back = chest.getItem(0);
        helper.assertTrue(!PolishGlintService.glinting(back),
                Component.literal("a glint put away in a chest was still marked when the chest was opened"));
        helper.assertTrue(Boolean.FALSE.equals(back.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)),
                Component.literal("a design with its glint OFF came back " + back.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)));
        p.closeContainer();
        helper.succeed();
    }
}
