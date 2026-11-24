package com.moddingfromamod.compat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.event.EventDirector;
import com.moddingfromamod.event.design.EventDesigns;
import com.moddingfromamod.rules.WorldRules;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import net.minecraft.SharedConstants;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.SavedDataStorage;

/**
 * <b>A fresh 26.1.2 world writes the mod's saved data where 26.1 reads it, and reads it back.</b>
 *
 * <p>26.1 keeps a dimension's saved data under {@code dimensions/minecraft/<dimension>/data/} and keys each file by
 * an {@code Identifier}: {@code data/<namespace>/<path>.dat}. {@link SavedDataTypes} turns the 1.21 names
 * ({@code moddingfromamod_rules}) into {@code moddingfromamod:rules}. A world made on 26.1.2 must keep its world rules
 * and its events across a save, so each store is marked dirty, the overworld's storage saved, and each file read
 * back from the path 26.1 uses. (A world UPGRADED from 1.21.x keeps its old files in the old folder, where 26.1 does
 * not look: that is known issue KI-9, upgrade-only; this test is the fresh-world case, which must work.)
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class SavedDataPathGameTest {

    private SavedDataPathGameTest() {
    }

    @GameTest(template = "empty_test", batch = "savedDataPath")
    public static void aFreshWorldKeepsTheModsSavedDataAtThe261Path(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Map<String, SavedData> stores = new LinkedHashMap<>();
        stores.put("rules", WorldRules.store(server));
        stores.put("events", EventDirector.store(server));
        stores.put("event_designs", EventDesigns.store(server));
        stores.values().forEach(SavedData::setDirty);
        SavedDataStorage storage = server.overworld().getDataStorage();
        storage.saveAndJoin();

        Path data = server.getWorldPath(LevelResource.ROOT).resolve("dimensions/minecraft/overworld/data")
                .resolve(ModdingFromAModMod.MODID);
        int version = SharedConstants.getCurrentVersion().dataVersion().version();
        for (String path : stores.keySet()) {
            Path file = data.resolve(path + ".dat");
            helper.assertTrue(Files.isRegularFile(file),
                    Component.literal("the " + path + " store was not written to " + file));
            CompoundTag tag;
            try {
                tag = storage.readTagFromDisk(file, null, version);
            } catch (java.io.IOException e) {
                throw new AssertionError("the " + path + " store at " + file + " does not read back: " + e, e);
            }
            helper.assertTrue(tag.contains("data"),
                    Component.literal("the " + path + " store read back without its data: " + tag));
        }
        helper.assertTrue(SavedDataTypes.identifier(ModdingFromAModMod.MODID + "_build_ground").toString()
                        .equals(ModdingFromAModMod.MODID + ":build_ground"),
                Component.literal("the build-ground journal's file is not moddingfromamod:build_ground"));
        helper.succeed();
    }
}
