package com.moddingfromamod.datapack;

import net.minecraft.network.chat.Component;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.google.gson.JsonParser;
import com.moddingfromamod.library.WishLibrary;
import com.moddingfromamod.testing.GateShards;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.functions.CommandFunction;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Every stored datapack a world is given must PARSE on the build that gives it</b>.
 *
 * <p>The starter library's five datapacks were compiled on 1.21.1. On the 1.21.4 build, two of them
 * lost four functions at pack load ({@code Malformed 'minecraft:custom_model_data' component: 'Not a
 * map: 8'}), the pack still reported enabled, and this gate stayed green, because nothing parsed a
 * stored function on the target it ran on. This parses each one the way the game does
 * ({@link CommandFunction#fromLines} over the server's own dispatcher, the call
 * {@code ServerFunctionLibrary} makes) against the bytes an install really copies
 * ({@link DatapackStore#installBytes}), so it cannot pass over text the world would refuse. No
 * reload, which a GameTest must not do (see {@link DatapackGameTest}'s header).
 */
@GameTestHolder(GateShards.DEFAULT)
public class StarterDatapackDialectGameTest {

    @GameTest(template = "empty_test")
    public static void everyStarterDatapackParsesOnThisBuild(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CommandSourceStack source = server.createCommandSourceStack()
                .withSuppressedOutput().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER);
        List<String> ids = WishLibrary.keys("datapacks");
        helper.assertTrue(!ids.isEmpty(), Component.literal("the starter library carries no datapacks — this test "
                + "would pass over nothing"));
        int functions = 0;
        List<String> refused = new ArrayList<>();
        for (String id : ids) {
            try (ZipInputStream in = new ZipInputStream(
                    new ByteArrayInputStream(DatapackStore.installBytes(id)))) {
                ZipEntry e;
                while ((e = in.getNextEntry()) != null) {
                    String name = e.getName();
                    String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    if (name.equals("pack.mcmeta")) {
                        int format = JsonParser.parseString(text).getAsJsonObject()
                                .getAsJsonObject("pack").get("pack_format").getAsInt();
                        if (format != DatapackDialect.packFormat()) {
                            refused.add(id + ": pack_format " + format + ", this build is "
                                    + DatapackDialect.packFormat());
                        }
                        continue;
                    }
                    if (!name.endsWith(".mcfunction")) {
                        continue;
                    }
                    functions++;
                    Identifier fn = Identifier.fromNamespaceAndPath(id, name
                            .replaceFirst("^data/[^/]+/function/", "").replace(".mcfunction", ""));
                    try {
                        CommandFunction.fromLines(fn, server.getCommands().getDispatcher(), source,
                                text.lines().toList());
                    } catch (RuntimeException parse) {
                        refused.add(fn + ": " + parse.getMessage());
                    }
                }
            } catch (Exception io) {
                refused.add(id + ": could not read the install bytes: " + io);
            }
        }
        helper.assertTrue(functions > 0, Component.literal("no .mcfunction found in " + ids.size() + " datapack(s)"));
        String summary = String.join(" | ", refused);
        helper.assertTrue(refused.isEmpty(), Component.literal(refused.size() + " of " + functions
                + " starter function(s) would be refused: "
                + (summary.length() > 480 ? summary.substring(0, 480) + "…" : summary)));
        helper.succeed();
    }
}
