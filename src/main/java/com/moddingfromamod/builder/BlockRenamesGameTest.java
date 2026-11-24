package com.moddingfromamod.builder;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.library.WishLibraryGameTest;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

/**
 * <b>A building saved on 1.21.8 still builds on this Minecraft</b>: a library {@code build.json} with no marker and
 * {@code minecraft:chain} in it, read through the REAL store path ({@code BuildRegistry.get}, the library half), names
 * {@code minecraft:iron_chain}, which this game's block registry has — and the old id is one it does not. The table
 * itself is Gate A ({@code BlockRenamesTest}); this proves the store reads through it.
 *
 * <p>Its own batch: it points the mod at a fixture library, which every library test reads.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class BlockRenamesGameTest {

    static final String WELL = """
            {"id":"gt_rename_well","name":"Old Well","shapes":[
             {"kind":"box","dx":-2,"dy":0,"dz":-2,"width":5,"height":2,"depth":5,"block":"minecraft:cobblestone","hollow":true},
             {"kind":"box","dx":0,"dy":2,"dz":0,"width":1,"height":4,"depth":1,"block":"minecraft:chain","hollow":false,"name":"bucket_rope"},
             {"kind":"box","dx":0,"dy":6,"dz":0,"width":1,"height":1,"depth":1,"block":"minecraft:spruce_planks","hollow":false}]}
            """;

    @GameTest(template = "empty_test", batch = "block_renames", timeoutTicks = 400)
    public static void aLibraryBuildingSavedOn1218ReadsWithIronChain(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        try {
            helper.assertTrue(!BuiltInRegistries.BLOCK.containsKey(ResourceLocation.parse("minecraft:chain")),
                    Component.literal("minecraft:chain is still a block here: the rename row would never be needed"));
            helper.assertTrue(BuiltInRegistries.BLOCK.containsKey(ResourceLocation.parse("minecraft:iron_chain")),
                    Component.literal("minecraft:iron_chain is not a block here"));
            Path dir = WishLibraryGameTest.fixture("renames", null);
            Path well = dir.resolve("buildings").resolve("gt_rename_well");
            Files.createDirectories(well);
            Files.writeString(well.resolve("build.json"), WELL, StandardCharsets.UTF_8);
            WishLibraryGameTest.useLibrary(server, dir);

            BuildPlan plan = BuildRegistry.get("gt_rename_well");
            helper.assertTrue(plan != null, Component.literal("the library building did not read"));
            helper.assertTrue(plan.blockIds().contains("minecraft:iron_chain"),
                    Component.literal("the 1.21.8 rope did not read as iron_chain: " + plan.blockIds()));
            helper.assertTrue(!plan.blockIds().contains("minecraft:chain"),
                    Component.literal("minecraft:chain is still in the plan: " + plan.blockIds()));
            for (String b : plan.blockIds()) {
                String id = b.replaceFirst("[\\[{].*", "");
                helper.assertTrue(BuiltInRegistries.BLOCK.containsKey(ResourceLocation.parse(id)),
                        Component.literal("the plan names a block this game does not have: " + b));
            }
            helper.assertTrue(Files.readString(well.resolve("build.json"), StandardCharsets.UTF_8).equals(WELL),
                    Component.literal("reading the library building rewrote its file"));
            helper.succeed();
        } catch (Exception e) {
            helper.fail(Component.literal("the rename fixture failed: " + e));
        } finally {
            WishLibraryGameTest.restore(server);
        }
    }
}
