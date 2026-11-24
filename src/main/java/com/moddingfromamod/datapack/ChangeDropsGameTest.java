package com.moddingfromamod.datapack;

import net.minecraft.network.chat.Component;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.ChangeDropsTool;

import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>"Zombies drop diamonds" and "trees drop apples", through the real tool, a real death and a
 * real broken block</b> ({@link ChangeDropsTool}).
 *
 * <p>What a unit test cannot prove, and this does:
 * <ul>
 *   <li>the serializer is registered, so NeoForge can decode the JSON at all (an unregistered
 *       type is logged and skipped, and nothing drops);</li>
 *   <li>the pack is laid out where NeoForge looks
 *       ({@code data/neoforge/loot_modifiers/global_loot_modifiers.json});</li>
 *   <li>a mob's queried table id really is {@code minecraft:entities/zombie}, and a block tag
 *       really resolves through {@code minecraft:blocks/oak_leaves};</li>
 *   <li>the tool's "LOADED" is read back from NeoForge, and "removed" really unloads.</li>
 * </ul>
 *
 * <p>ONE BATCH OF ITS OWN, because it RELOADS DATAPACKS, and a reload under another test's feet
 * is the fourth cause of a red run CLAUDE.md names.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class ChangeDropsGameTest {

    private static final String ZOMBIE_RULE = "t6c_test_zombie_diamonds";
    private static final String LEAVES_RULE = "t6c_test_leaves_apples";

    /** change_drops hops to the server thread, so a GameTest must call it from off it. */
    private static void inBackground(AtomicReference<String> out, String... calls) {
        Thread t = new Thread(() -> {
            StringBuilder all = new StringBuilder();
            for (String json : calls) {
                try {
                    ToolResult r = new ChangeDropsTool().call(
                            JsonParser.parseString(json).getAsJsonObject());
                    all.append(r.isError() ? "ERROR " : "").append(r.text()).append('\n');
                } catch (RuntimeException e) {
                    all.append("change_drops threw: ").append(e).append('\n');
                }
            }
            out.set(all.toString());
        }, "change-drops-test");
        t.setDaemon(true);
        t.start();
    }

    private static int count(GameTestHelper helper, BlockPos at, Item item) {
        BlockPos abs = helper.absolutePos(at);
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(abs).inflate(4))
                .stream().filter(e -> e.getItem().is(item)).mapToInt(e -> e.getItem().getCount())
                .sum();
    }

    @GameTest(template = "empty_test", batch = "changedrops", timeoutTicks = 1200)
    public static void aZombieDropsDiamondsAndLeavesDropApples(GameTestHelper helper) {
        AtomicReference<String> installed = new AtomicReference<>();
        AtomicReference<String> removed = new AtomicReference<>();
        AtomicBoolean acted = new AtomicBoolean();
        AtomicBoolean removing = new AtomicBoolean();
        AtomicReference<String> seen = new AtomicReference<>();
        BlockPos mobAt = new BlockPos(1, 2, 1);
        BlockPos leafAt = new BlockPos(3, 2, 3);

        inBackground(installed,
                "{source:'mob', which:'zombie', item:'minecraft:diamond', count:3, name:'"
                        + ZOMBIE_RULE + "'}",
                "{source:'block', which:'#minecraft:leaves', item:'minecraft:apple', count:2, name:'"
                        + LEAVES_RULE + "'}");

        helper.succeedWhen(() -> {
            String in = installed.get();
            helper.assertTrue(in != null, Component.literal("waiting for change_drops to install"));
            helper.assertTrue(!in.contains("ERROR"), Component.literal("change_drops refused or failed: " + in));
            helper.assertTrue(in.split("LOADED").length - 1 == 2,
                    Component.literal("both rules should be read back as LOADED from NeoForge: " + in));

            if (acted.compareAndSet(false, true)) {
                Zombie z = helper.spawn(EntityTypes.ZOMBIE, mobAt);
                z.hurt(z.damageSources().generic(), 1000f);
                helper.setBlock(leafAt, Blocks.OAK_LEAVES);
                helper.getLevel().destroyBlock(helper.absolutePos(leafAt), true);
            }
            int diamonds = count(helper, mobAt, Items.DIAMOND);
            int apples = count(helper, leafAt, Items.APPLE);
            seen.set("diamonds=" + diamonds + " apples=" + apples);
            helper.assertTrue(diamonds >= 3,
                    Component.literal("a zombie with 'zombies drop diamonds' dropped " + diamonds + " diamonds"));
            helper.assertTrue(apples >= 2,
                    Component.literal("an oak leaves block with 'leaves drop apples' (a TAG rule) dropped " + apples));

            // And take them away again: the pack persists in the reused GameTest world, and a
            // later run must not start with zombies that drop diamonds.
            if (removing.compareAndSet(false, true)) {
                inBackground(removed, "{remove:true, name:'" + ZOMBIE_RULE + "'}",
                        "{remove:true, name:'" + LEAVES_RULE + "'}");
            }
            String out = removed.get();
            helper.assertTrue(out != null, Component.literal("waiting for the rules to be removed (" + seen.get() + ")"));
            helper.assertTrue(!out.contains("ERROR") && out.split("Removed").length - 1 == 2,
                    Component.literal("removing both rules should unload them: " + out));
            helper.assertTrue(!ChangeDropsTool.LiveLootModifiers.ids()
                            .contains(ChangeDropsTool.PACK + ":" + ZOMBIE_RULE),
                    Component.literal("the zombie rule is still loaded after it was removed"));
        });
    }

    /** The reflection that reads NeoForge's loaded modifiers must work, or every answer lies. */
    @GameTest(template = "empty_test", batch = "changedrops", timeoutTicks = 40)
    public static void theLoadedModifiersCanBeReadBack(GameTestHelper helper) {
        // The set may be empty (nothing need be loaded), so the proof is that the read does not
        // THROW: an IllegalStateException here means NeoForge moved the manager.
        var ids = ChangeDropsTool.LiveLootModifiers.ids();
        helper.assertTrue(ids != null, Component.literal("no answer at all"));
        helper.succeed();
    }
}
