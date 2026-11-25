package com.moddingfromamod.species;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonArray;
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
 * <b>#104's stat-line repair, where it runs</b>: the real {@link SpeciesRegistry#load} reading records saved under
 * the old tool wording (no record version). A guard and an owned pet saved with 0 damage come back with their size
 * band's damage; a hit already chosen and a v3 friendly at 0 come back as they were; every record is written back at
 * v3, in the world file and on the owner's shelf (the shelf's merge carries the version), so a second load repairs
 * nothing. The world's own file
 * is put back as it was when the test ends. A batch of its own: a load re-reads the whole registry.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class StatLineRepairGameTest {

    @GameTest(template = "empty_test", batch = "stat_line_repair", timeoutTicks = 200)
    public static void aV1StoreIsRepairedOnceWhenItIsRead(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        Path file = server.getWorldPath(LevelResource.ROOT).resolve("wishes").resolve("species.json");
        String original;
        try {
            original = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (Exception e) {
            helper.fail(Component.literal("could not read the world's species store: " + e));
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
        String owner = "1f2e3d4c-0000-0000-0000-00000000000b";
        try {
            JsonObject root = original == null ? JsonParser.parseString("{\"store\":\"species\",\"v\":1,\"species\":[]}").getAsJsonObject()
                    : JsonParser.parseString(original).getAsJsonObject();
            JsonArray arr = root.getAsJsonArray("species");
            // saved under the old tool wording: no record version (v1)
            arr.add(JsonParser.parseString("{\"id\":\"gt_repair_guard\",\"name\":\"Guard\",\"body\":\"humanoid\","
                    + "\"texture\":\"zombie\",\"tint\":\"#FFFFFF\",\"scale\":1.0,\"health\":30.0,\"speed\":0.25,\"damage\":0.0,"
                    + "\"armor\":0.0,\"knockbackResistance\":0.0,\"powers\":[\"friendly\"],\"base\":\"guardian\"}"));
            arr.add(JsonParser.parseString("{\"id\":\"gt_repair_knight\",\"name\":\"Knight\",\"body\":\"humanoid\","
                    + "\"texture\":\"zombie\",\"tint\":\"#FFFFFF\",\"scale\":1.0,\"health\":40.0,\"speed\":0.25,\"damage\":6.0,"
                    + "\"armor\":0.0,\"knockbackResistance\":0.0,\"powers\":[\"friendly\"],\"base\":\"guardian\"}"));
            // made under #101's wording (v3), friendly at 0: never repaired
            arr.add(JsonParser.parseString("{\"v\":3,\"id\":\"gt_repair_new\",\"name\":\"New\",\"body\":\"humanoid\","
                    + "\"texture\":\"zombie\",\"tint\":\"#FFFFFF\",\"scale\":1.0,\"health\":20.0,\"speed\":0.25,\"damage\":0.0,"
                    + "\"armor\":0.0,\"knockbackResistance\":0.0,\"powers\":[\"friendly\"]}"));
            // an OWNED creature saved under the old wording: it lives on its owner's shelf after the next save
            arr.add(JsonParser.parseString("{\"id\":\"gt_repair_pet~1f2e3d4c\",\"name\":\"Pet\",\"body\":\"humanoid\","
                    + "\"texture\":\"zombie\",\"tint\":\"#FFFFFF\",\"scale\":1.0,\"health\":20.0,\"speed\":0.25,\"damage\":0.0,"
                    + "\"armor\":0.0,\"knockbackResistance\":0.0,\"powers\":[\"friendly\"],\"ownerId\":\"" + owner + "\"}"));
            Files.createDirectories(file.getParent());
            Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            helper.fail(Component.literal("could not write the store: " + e));
            return;
        }
        Path shelf = com.moddingfromamod.runtime.RuntimeConfig.playersDir().resolve(owner);
        MockPlayerNetwork.afterTest(helper, () -> {
            try (var walk = Files.walk(shelf)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            } catch (Exception ignored) {
                // nothing was written there
            }
        });
        SpeciesRegistry.load(server);
        double band = PowerBand.forCreature(Double.NaN, Species.Body.HUMANOID, 1.0).damage();
        Species guard = SpeciesRegistry.get("gt_repair_guard");
        helper.assertTrue(guard != null && guard.damage() == band, Component.literal("a v1 guard at 0 hits for its band's "
                + band + ": " + guard));
        helper.assertTrue(guard.health() == 30 && guard.base() == Species.Base.GUARDIAN && guard.name().equals("Guard"),
                Component.literal("and nothing else about it changed: " + guard));
        helper.assertTrue(SpeciesRegistry.get("gt_repair_knight").damage() == 6, Component.literal("a hit already chosen is kept"));
        helper.assertTrue(SpeciesRegistry.get("gt_repair_new").damage() == 0,
                Component.literal("a v3 friendly at 0 is never repaired: " + SpeciesRegistry.get("gt_repair_new")));
        helper.assertTrue(SpeciesRegistry.get("gt_repair_pet~1f2e3d4c").damage() == band,
                Component.literal("an owned v1 friendly at 0 is repaired: " + SpeciesRegistry.get("gt_repair_pet~1f2e3d4c")));
        // written back at v3: in the world file, and on the owner's shelf through the shelf's merge
        try {
            JsonObject worldGuard = recordIn(file, "gt_repair_guard");
            helper.assertTrue(worldGuard != null && Species.recordVersion(worldGuard) == Species.RECORD_VERSION
                            && worldGuard.get("damage").getAsDouble() == band,
                    Component.literal("the world file holds the repaired guard at v" + Species.RECORD_VERSION + ": " + worldGuard));
            JsonObject shelved = recordIn(shelf.resolve("species.json"), "gt_repair_pet~1f2e3d4c");
            helper.assertTrue(shelved != null && Species.recordVersion(shelved) == Species.RECORD_VERSION
                            && shelved.get("damage").getAsDouble() == band,
                    Component.literal("his shelf holds his repaired pet at v" + Species.RECORD_VERSION + ": " + shelved));
        } catch (Exception e) {
            helper.fail(Component.literal("the stores could not be read back: " + e));
            return;
        }
        // a second load: every record is at v3, nothing more is repaired
        SpeciesRegistry.load(server);
        helper.assertTrue(SpeciesRegistry.get("gt_repair_guard").damage() == band
                        && SpeciesRegistry.get("gt_repair_new").damage() == 0,
                Component.literal("a second load changes nothing"));
        helper.succeed();
    }

    private static JsonObject recordIn(Path store, String id) throws Exception {
        if (!Files.isRegularFile(store)) {
            return null;
        }
        for (var el : JsonParser.parseString(Files.readString(store, StandardCharsets.UTF_8)).getAsJsonObject()
                .getAsJsonArray("species")) {
            if (el.getAsJsonObject().get("id").getAsString().equals(id)) {
                return el.getAsJsonObject();
            }
        }
        return null;
    }

    private StatLineRepairGameTest() {
    }
}
