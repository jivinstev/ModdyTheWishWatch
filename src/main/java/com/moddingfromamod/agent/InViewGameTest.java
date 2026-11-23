package com.moddingfromamod.agent;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.WishRun;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.wish.WishAimer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * <b>"In view when he wished" is read when he wishes, and stays that</b> (§3). He faces a wall of andesite; the
 * wish-time lock every wish goes through ({@link WishAimer#lockAtWishTime}, called by {@code WishService.submit} and the
 * Builder) reads it onto the run; then he turns to the sky and empties his bag — and the brief's section and
 * {@code find_content around: true} still name the andesite and the sand he had, because nothing re-reads him live.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class InViewGameTest {

    private InViewGameTest() {
    }

    @GameTest(template = "empty_test", batch = "inView", timeoutTicks = 1000)
    public static void theBriefDescribesTheViewWhenHeWishedNotNow(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(new BlockPos(4, 1, 1));
        List<BlockPos> placed = new ArrayList<>();
        MockPlayerNetwork.afterTest(helper, () -> placed.forEach(b -> level.setBlockAndUpdate(b, Blocks.AIR.defaultBlockState())));
        for (int x = -6; x <= 6; x++) {
            for (int z = -2; z <= 6; z++) {
                for (int y = 1; y <= 6; y++) {
                    level.setBlockAndUpdate(base.offset(x, y, z), Blocks.AIR.defaultBlockState());
                    placed.add(base.offset(x, y, z));
                }
                level.setBlockAndUpdate(base.offset(x, 0, z), Blocks.ANDESITE.defaultBlockState());
                placed.add(base.offset(x, 0, z));
            }
            for (int y = 1; y <= 4; y++) {
                level.setBlockAndUpdate(base.offset(x, y, 4), Blocks.ANDESITE.defaultBlockState());
            }
        }
        ServerPlayer child = MockPlayerNetwork.inLevel(helper, "gtinview");
        child.snapTo(base.getX() + 0.5, base.getY() + 1, base.getZ() + 0.5, 0f, 20f);
        child.getInventory().clearContent();
        child.getInventory().setItem(0, new ItemStack(Items.SAND, 12));

        Marshal marshal = new Marshal(com.moddingfromamod.marshal.Clock.system(), () -> 1);
        WishRun run = marshal.submit(UUID.randomUUID(), child.getUUID(), "turn these blocks into gold");
        WishAimer.lockAtWishTime(child, run, "gtinview");

        // He moves after he wished: looks at the sky, and his bag is emptied
        child.snapTo(base.getX() + 0.5, base.getY() + 1, base.getZ() + 0.5, 90f, -80f);
        child.getInventory().clearContent();

        var seen = run.sawWhenWished();
        helper.assertTrue(seen != null && "minecraft:andesite".equals(seen.crosshair()),
                Component.literal("the wish-time crosshair: " + (seen == null ? "none" : seen.crosshair())));
        String section = Surroundings.inView(seen);
        helper.assertTrue(section.contains("IN VIEW WHEN HE WISHED") && section.contains("at his crosshair: minecraft:andesite")
                        && section.contains("minecraft:sand (Sand) ×12"),
                Component.literal("the section: " + section));
        String around = com.moddingfromamod.content.AroundSearch.answer(seen, "stone",
                com.moddingfromamod.content.ContentIndexes.live(), 40);
        helper.assertTrue(around.contains("minecraft:andesite — Andesite — at his crosshair") && !around.contains("minecraft:stone "),
                Component.literal("find_content around: 'stone' is his andesite, never a random stone: " + around));
        String sand = com.moddingfromamod.content.AroundSearch.answer(seen, "sand",
                com.moddingfromamod.content.ContentIndexes.live(), 40);
        helper.assertTrue(sand.contains("minecraft:sand — Sand — ×12") && !sand.contains("andesite"),
                Component.literal("a query narrows it: " + sand));
        helper.assertTrue(Surroundings.describe(level, child.position(), seen).endsWith(section),
                Component.literal("the brief carries the section"));
        helper.succeed();
    }
}
