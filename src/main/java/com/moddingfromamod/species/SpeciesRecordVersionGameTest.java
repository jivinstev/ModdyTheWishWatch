package com.moddingfromamod.species;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;

/**
 * <b>A creature record from a newer Moddy is never saved over</b> (docs/STORE_MIGRATIONS.md): the real
 * {@link SpeciesRegistry#load} reads a world whose species store holds a record above {@link Species#RECORD_VERSION};
 * it is read, and a save afterwards leaves the file exactly as it was. A batch of its own: a load re-reads the
 * whole registry. The world's own file is put back when the test ends.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class SpeciesRecordVersionGameTest {

    @GameTest(template = "empty_test", batch = "species_record_version", timeoutTicks = 200)
    public static void aNewerRecordLatchesTheStore(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        Path file = server.getWorldPath(LevelResource.ROOT).resolve("wishes").resolve("species.json");
        String original;
        String written;
        try {
            original = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
            JsonObject root = original == null ? JsonParser.parseString("{\"store\":\"species\",\"v\":1,\"species\":[]}").getAsJsonObject()
                    : JsonParser.parseString(original).getAsJsonObject();
            root.getAsJsonArray("species").add(JsonParser.parseString("{\"v\":" + (Species.RECORD_VERSION + 1)
                    + ",\"id\":\"gt_from_the_future\",\"name\":\"Future\",\"health\":20.0,\"damage\":3.0,\"newField\":true}"));
            written = root.toString();
            Files.createDirectories(file.getParent());
            Files.writeString(file, written, StandardCharsets.UTF_8);
        } catch (Exception e) {
            helper.fail(Component.literal("could not write the store: " + e));
            return;
        }
        MockPlayerNetwork.afterTest(helper, () -> {
            try {
                if (original == null) {
                    Files.deleteIfExists(file);
                } else {
                    Files.writeString(file, original, StandardCharsets.UTF_8);
                }
            } catch (Exception ignored) {
                // the next load says what it finds
            }
            SpeciesRegistry.load(server);
        });
        SpeciesRegistry.load(server);
        helper.assertTrue(SpeciesRegistry.get("gt_from_the_future") != null, Component.literal("the newer record is read"));
        com.moddingfromamod.testing.SpeciesForTest.putSaved(helper, Species.fromJson(JsonParser.parseString(
                "{\"id\":\"gt_saved_now\",\"name\":\"Now\",\"health\":20,\"damage\":3}").getAsJsonObject()));
        String after;
        try {
            after = Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            helper.fail(Component.literal("could not read the store back: " + e));
            return;
        }
        helper.assertTrue(after.equals(written), Component.literal("a save after reading a newer record left the file as it was"));
        helper.succeed();
    }

    private SpeciesRecordVersionGameTest() {
    }
}
