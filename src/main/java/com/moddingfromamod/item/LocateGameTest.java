package com.moddingfromamod.item;

import net.minecraft.network.chat.Component;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.MakeItemTool;
import com.moddingfromamod.testing.MockPlayerNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.LodestoneTracker;
import com.moddingfromamod.testing.gt.GameTestHolder;

import java.util.List;
import java.util.Optional;

/**
 * <b>: a finding compass, on the real game.</b> The compass in his hand is pointed at
 * what vanilla's own search found — its {@code lodestone_tracker} names that exact position, not
 * tracked — and he is told how far; a place there is none of is said plainly and the compass is
 * left alone. Red on {@code main}: {@code locate} is not an effect, so make_item refuses it and
 * nothing points anywhere.
 *
 * <p>The GameTest world has the plains and no structures, which is why the found half asks for
 * the plains and the not-found half for a village — both are the real searches.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class LocateGameTest {

    private static ServerPlayer him(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper);
        BlockPos feet = helper.absolutePos(new BlockPos(1, 2, 1));
        p.snapTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.0F, 0.0F);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COMPASS));
        return p;
    }

    @GameTest(template = "empty_test", batch = "locate", timeoutTicks = 200)
    public static void theCompassPointsAtWhatTheSearchFound(GameTestHelper helper) {
        ServerPlayer p = him(helper);
        long t0 = System.nanoTime();
        Optional<BlockPos> at = Locate.point(p, LocateTargets.of("plains"));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        helper.assertTrue(at.isPresent(), Component.literal("no plains found in a plains world"));
        LodestoneTracker lt = p.getMainHandItem().get(DataComponents.LODESTONE_TRACKER);
        helper.assertTrue(lt != null && lt.target().isPresent()
                        && lt.target().get().pos().equals(at.get()) && !lt.tracked(),
                Component.literal("the compass does not point at what was found (" + at.get() + "): " + lt));
        List<String> said = MockPlayerNetwork.chat(p);
        helper.assertTrue(said.stream().anyMatch(s -> s.contains("This way to the plains")),
                Component.literal("he was not told the way: " + said));
        System.out.println("LOCATE: plains found in " + ms + " ms");
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = "locate", timeoutTicks = 200)
    public static void aPlaceThereIsNoneOfIsSaidAndTheCompassIsLeftAlone(GameTestHelper helper) {
        ServerPlayer p = him(helper);
        long t0 = System.nanoTime();
        Optional<BlockPos> at = Locate.point(p, LocateTargets.of("villages"));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        helper.assertTrue(at.isEmpty(), Component.literal("a village was 'found' in a world with no structures: " + at));
        helper.assertTrue(p.getMainHandItem().get(DataComponents.LODESTONE_TRACKER) == null,
                Component.literal("the compass was pointed somewhere anyway"));
        helper.assertTrue(MockPlayerNetwork.chat(p).stream().anyMatch(s -> s.contains("no village")),
                Component.literal("nothing found was not said: " + MockPlayerNetwork.chat(p)));
        System.out.println("LOCATE: village search (none) took " + ms + " ms");
        helper.succeed();
    }

    /** Where it RUNS: make_item takes it on a real compass, and a click's tag is answered. */
    @GameTest(template = "empty_test", batch = "locate", timeoutTicks = 20_000)
    public static void makeItemBuildsAFindingCompass(GameTestHelper helper) {
        JsonObject o = new JsonObject();
        o.addProperty("name", "Plains Finder " + helper.getLevel().getGameTime());
        o.addProperty("icon", "wand");
        o.addProperty("colour", "#C0A040");
        o.addProperty("shape", "held");
        o.addProperty("effect", "locate");
        o.addProperty("base_item", "minecraft:compass");
        o.addProperty("count", 1);
        JsonObject params = new JsonObject();
        params.addProperty("PLACE", "Plains");
        o.add("params", params);
        ToolResult r = new MakeItemTool().call(o);
        helper.assertTrue(!r.isError(), Component.literal("make_item refused a finding compass: " + r.text()));
        JsonObject bad = o.deepCopy();
        bad.getAsJsonObject("params").addProperty("PLACE", "the moon");
        ToolResult no = new MakeItemTool().call(bad);
        helper.assertTrue(no.isError() && no.text().contains("PLACE"), Component.literal("a place not in the list was accepted: " + no.text()));

        ServerPlayer p = him(helper);
        p.addTag(Locate.TAG + "plains");      // what the effect's function does on his click
        // A biome is answered off the server thread and applied on a later tick, so poll (a fresh
        // lambda each time: GameTestInfo drops a Runnable once it has run).
        Runnable[] poll = new Runnable[1];
        poll[0] = () -> {
            if (p.getMainHandItem().get(DataComponents.LODESTONE_TRACKER) == null) {
                helper.runAfterDelay(5, () -> poll[0].run());
                return;
            }
            helper.assertTrue(!p.getTags().contains(Locate.TAG + "plains"), Component.literal("the tag was never taken off"));
            helper.succeed();
        };
        helper.runAfterDelay(1, () -> poll[0].run());
    }
}
