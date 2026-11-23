package com.moddingfromamod.library;

import net.minecraft.network.chat.Component;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.tools.MakeItemTool;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.datapack.CbScriptCompiler;
import com.moddingfromamod.datapack.DatapackStore;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Every starter item is rebuilt from its recipe through today's {@code make_item}</b> (2026-10-10).
 *
 * <p>A starter item is {@code make_item} output frozen into the jar: {@code item.json} is the recipe
 * and {@code datapacks/<id>/<id>.zip} the compiled power, with no source. So a fix to an effect
 * template, the compiler or the rules {@code make_item} enforces never reaches the one row every new
 * child sees. This test is the way back: it hands each recipe to the real tool and proves the tool
 * still accepts it and still compiles a pack. Run with {@code -Ptestprops=regenStarters=<dir>} and
 * it also writes each fresh zip there, which is how the shipped packs are refreshed.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class StarterItemRegenGameTest {

    private static final String[] IDS = {
        "double_jump_boots_jump", "lightning_sword_lightning", "lucky_block_outcome_table",
        "slushie_machine_load_and_pour", "teleport_wand_teleport", "tiny_cookie_grow"};

    /*
     * Not here: slushie_plain. It predates make_item's naming (a "Slushie" with the food effect is
     * slushie_food today), and the slushie machine pours it BY that id, so a rebuild would make a
     * different item the machine never pours. Its food template has not changed since it was made.
     */

    private static JsonObject resource(String path) throws Exception {
        try (InputStream in = StarterItemRegenGameTest.class.getResourceAsStream("/starter-library/" + path)) {
            return in == null ? null
                    : JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    /** The recipe as {@code make_item} takes it: item.json's fields, its one effect, and its icon. */
    static JsonObject callFor(JsonObject item, JsonObject icon) {
        JsonObject call = new JsonObject();
        for (String k : new String[] {"name", "base_item", "shape", "count"}) {
            if (item.has(k) && !item.get(k).isJsonNull()) {
                call.add(k, item.get(k));
            }
        }
        JsonArray effects = item.getAsJsonArray("effects");
        List<String> fx = new ArrayList<>();
        effects.forEach(e -> fx.add(e.getAsString()));
        call.addProperty("effect", String.join("+", fx));
        if (item.has("params")) {
            call.add("params", item.get("params"));
        }
        for (String k : new String[] {"properties", "parts", "variants"}) {
            JsonElement v = item.get(k);
            boolean empty = v == null || v.isJsonNull()
                    || v.isJsonArray() && v.getAsJsonArray().isEmpty()
                    || v.isJsonObject() && v.getAsJsonObject().isEmpty();
            if (!empty) {
                call.add(k, v);
            }
        }
        if (icon != null) {
            call.addProperty("icon", icon.get("sprite").getAsString());
            call.addProperty("colour", icon.get("tint").getAsString());
        }
        call.addProperty("from_scratch", true);
        return call;
    }

    @GameTest(template = "empty_test", batch = "starter_item_regen", timeoutTicks = 1200)
    public static void everyStarterItemIsRebuiltByTodaysMakeItem(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            System.out.println("STARTER_REGEN: SKIPPED — no CBScript compiler. This proved NOTHING.");
            helper.succeed();
            return;
        }
        String out = System.getProperty(ModdingFromAModMod.MODID + ".regenStarters", "");
        List<String> bad = new ArrayList<>();
        try {
            for (String id : IDS) {
                JsonObject item = resource("items/" + id + "/item.json");
                if (item == null) {
                    bad.add(id + ": no item.json");
                    continue;
                }
                // Worn things exist only where this Minecraft can put a wished thing on him (1.21.4),
                // the same rule that leaves the boots off the 1.21.1 starter row (StarterNeeds).
                String shape = item.has("shape") ? item.get("shape").getAsString() : "";
                if (shape.startsWith("worn_") && !com.moddingfromamod.compat.Wearables.SUPPORTED) {
                    System.out.println("STARTER_REGEN: " + id + " skipped — worn, and this build cannot wear");
                    continue;
                }
                MakeItemTool tool = new MakeItemTool();
                ToolResult r = tool.call(callFor(item, resource("items/" + id + "/icon.json")));
                if (r.isError()) {
                    bad.add(id + ": make_item refused it today: " + r.text());
                    continue;
                }
                String made = tool.lastMadeId();
                Path zip = DatapackStore.zipFor(made);
                if (!id.equals(made) || !Files.isRegularFile(zip)) {
                    bad.add(id + ": made '" + made + "' with no zip at " + zip);
                    continue;
                }
                System.out.println("STARTER_REGEN: " + id + " rebuilt (" + Files.size(zip) + " bytes)");
                if (!out.isBlank()) {
                    Path dest = Path.of(out).resolve(id + ".zip");
                    Files.createDirectories(dest.getParent());
                    Files.copy(zip, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (Exception e) {
            bad.add("threw: " + e);
        }
        helper.assertTrue(bad.isEmpty(), Component.literal("starter items today's make_item cannot rebuild: " + bad));
        helper.succeed();
    }

    /**
     * The starter sword is swung: lightning lands on what it HITS (on_hit), not where he points
     * (it was a right-click thing). A zombie with no AI stands in front of a
     * survival mock; he swings once, and a bolt must come down beside it.
     */
    @GameTest(template = "empty_test", batch = "starter_sword_hit", timeoutTicks = 400)
    public static void theStarterSwordStrikesWhatItHits(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            System.out.println("STARTER_SWORD: SKIPPED — no CBScript compiler. This proved NOTHING.");
            helper.succeed();
            return;
        }
        try {
            JsonObject item = resource("items/lightning_sword_lightning/item.json");
            helper.assertTrue("on_hit".equals(item.get("shape").getAsString()),
                    Component.literal("the starter sword is " + item.get("shape") + ", not on_hit"));
            MakeItemTool tool = new MakeItemTool();
            ToolResult r = tool.call(callFor(item, resource("items/lightning_sword_lightning/icon.json")));
            helper.assertFalse(r.isError(), Component.literal("make_item refused the starter sword: " + r.text()));
            com.moddingfromamod.testing.MockPlayerNetwork.clearStaleMocks(helper);
            net.minecraft.server.level.ServerPlayer p = com.moddingfromamod.testing.MockPlayerNetwork.asSurvival(
                    com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper));
            var feet = helper.absolutePos(new net.minecraft.core.BlockPos(1, 2, 1));
            p.snapTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.0F, 0.0F);
            p.getInventory().clearContent();
            helper.getLevel().getServer().getCommands().performPrefixedCommand(
                    p.createCommandSourceStack().withPermission(2).withSuppressedOutput(),
                    "wishdatapack " + tool.lastMadeId() + " give");
            net.minecraft.world.item.ItemStack sword = net.minecraft.world.item.ItemStack.EMPTY;
            for (var s : p.getInventory().getNonEquipmentItems()) {
                if (!s.isEmpty() && s.getHoverName().getString().equals("Lightning Sword")) {
                    sword = s.copy();
                }
            }
            helper.assertFalse(sword.isEmpty(), Component.literal("no Lightning Sword arrived"));
            p.getInventory().clearContent();
            p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, sword);
            var z = helper.spawn(net.minecraft.world.entity.EntityType.ZOMBIE, new net.minecraft.core.BlockPos(1, 2, 3));
            z.setNoAi(true);
            z.snapTo(p.getX(), p.getY(), p.getZ() + 1.5, 180.0F, 0.0F);
            p.attack(z);
            var box = z.getBoundingBox().inflate(3);
            helper.succeedWhen(() -> helper.assertTrue(
                    !helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.LightningBolt.class, box).isEmpty(),
                    Component.literal("he hit the zombie with the starter sword and no lightning came down on it")));
        } catch (Exception e) {
            helper.fail(Component.literal("threw: " + e));
        }
    }
}
