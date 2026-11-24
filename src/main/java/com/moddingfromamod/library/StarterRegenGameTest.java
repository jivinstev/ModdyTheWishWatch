package com.moddingfromamod.library;

import net.minecraft.network.chat.Component;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.tools.ApplyEffectTool;
import com.moddingfromamod.agent.tools.MakeItemTool;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.datapack.CbScriptCompiler;
import com.moddingfromamod.datapack.DatapackStore;
import com.moddingfromamod.item.ItemSpecStore;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Every starter pack is rebuilt from its recipe through today's tools</b> (2026-10-10; every kind since 2026-11-24).
 *
 * <p>A starter pack is tool output frozen into the jar: {@code datapacks/<id>/<id>.zip} is the compiled power and
 * {@code datapacks/<id>/recipe.json} ({@link StarterRecipe}) the calls that made it. So a fix to an effect template,
 * the compiler or the rules a tool enforces never reaches the one row every new child sees unless something replays
 * those calls. This test is the way back: it hands each recipe's calls to the real tool ({@code apply_effect} for a
 * creature's ability, {@code make_item} for an item), proves the tool still accepts them and still makes the pack the
 * recipe names, and says, per pack, whether the functions it compiled match the shipped ones. Run with
 * {@code -Ptestprops=regenStarters=<dir>} and it also writes each fresh zip there, which is how a version branch
 * brings every shipped pack up to date after bumping the compiler.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class StarterRegenGameTest {

    private static JsonObject resource(String path) throws Exception {
        try (InputStream in = StarterRegenGameTest.class.getResourceAsStream("/starter-library/" + path)) {
            return in == null ? null
                    : JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static byte[] bytes(String path) throws Exception {
        try (InputStream in = StarterRegenGameTest.class.getResourceAsStream("/starter-library/" + path)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    /** Every starter pack id, from the shipped index (a classpath directory cannot be listed inside a jar). */
    static List<String> packIds() throws Exception {
        List<String> ids = new ArrayList<>();
        try (InputStream in = StarterRegenGameTest.class.getResourceAsStream("/" + StarterLibrary.INDEX)) {
            for (String f : StarterLibrary.files(new String(in.readAllBytes(), StandardCharsets.UTF_8))) {
                String[] p = f.split("/");
                if (p.length == 3 && p[0].equals("datapacks") && p[2].equals(p[1] + ".zip")) {
                    ids.add(p[1]);
                }
            }
        }
        return ids;
    }

    /** The recipe as {@code make_item} takes it: item.json's fields, its effects, and its icon. */
    static JsonObject callFor(JsonObject item, JsonObject icon) {
        return StarterRecipe.makeItemCall(item, icon);
    }

    /**
     * Replay one call. A {@code make_item} whose {@code start_from} is the pack's own id rebuilds the starter's design
     * UNDER ITS ID (the slushie machine pours slushie_plain by id, and a fresh make would mint slushie_food): the
     * design is put in this world's item store as a shared one, with the starter layer off so the edit is not a
     * child's fork of a library item, and taken away again after.
     */
    private static String replay(String id, StarterRecipe.Call call, List<String> bad) throws Exception {
        if ("apply_effect".equals(call.tool())) {
            ToolResult r = new ApplyEffectTool().call(call.args().deepCopy());
            if (r.isError()) {
                bad.add(id + ": apply_effect refused it today: " + r.text());
                return null;
            }
            return ApplyEffectTool.featureId(call.args().get("effect").getAsString(),
                    call.args().get("tag").getAsString());
        }
        JsonObject args = call.args().deepCopy();
        String startFrom = args.has("start_from") ? args.get("start_from").getAsString() : "";
        MakeItemTool tool = new MakeItemTool();
        if (!startFrom.equals(id)) {
            ToolResult r = tool.call(args);
            if (r.isError()) {
                bad.add(id + ": make_item refused it today: " + r.text());
                return null;
            }
            return tool.lastMadeId();
        }
        JsonObject item = resource("items/" + id + "/item.json");
        if (item == null) {
            bad.add(id + ": start_from its own id, but there is no items/" + id + "/item.json to start from");
            return null;
        }
        ToolResult[] r = new ToolResult[1];
        try {
            StarterLibrary.withoutStarterForTest(() -> {
                WishLibrary.reload();
                ItemSpecStore.put(ItemSpecStore.Spec.fromJson(item));
                r[0] = tool.call(args);
            });
        } finally {
            WishLibrary.reload();
        }
        if (r[0].isError()) {
            bad.add(id + ": make_item refused the edit of its own design today: " + r[0].text());
            return null;
        }
        return tool.lastMadeId();
    }

    @GameTest(template = "empty_test", batch = "starter_item_regen", timeoutTicks = 1200)
    public static void everyStarterPackIsRebuiltFromItsRecipe(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            System.out.println("STARTER_REGEN: SKIPPED — no CBScript compiler. This proved NOTHING.");
            helper.succeed();
            return;
        }
        String out = System.getProperty(ModdingFromAModMod.MODID + ".regenStarters", "");
        List<String> bad = new ArrayList<>();
        int rebuilt = 0;
        int skipped = 0;
        try {
            for (String id : packIds()) {
                JsonObject raw = resource("datapacks/" + id + "/" + StarterRecipe.FILE);
                if (raw == null) {
                    bad.add(id + ": no " + StarterRecipe.FILE);
                    continue;
                }
                StarterRecipe.Recipe recipe = StarterRecipe.parse(raw.toString());
                // Worn things exist only where this Minecraft can put a wished thing on him (1.21.4),
                // the same rule that leaves the boots off the 1.21.1 starter row (StarterNeeds).
                String shape = recipe.calls().stream().filter(c -> c.args().has("shape"))
                        .map(c -> c.args().get("shape").getAsString()).findFirst().orElse("");
                if (shape.startsWith("worn_") && !com.moddingfromamod.compat.Wearables.SUPPORTED) {
                    System.out.println("STARTER_REGEN: " + id + " skipped — worn, and this build cannot wear");
                    skipped++;
                    continue;
                }
                // The world's own zip, never the library's: zipFor falls back to the shipped pack, and a
                // compile that wrote nothing would then "rebuild" it by copying it.
                Path zip = DatapackStore.ownZipFor(id);
                Files.deleteIfExists(zip);
                String made = null;
                for (StarterRecipe.Call call : recipe.calls()) {
                    made = replay(id, call, bad);
                    if (made == null) {
                        break;
                    }
                }
                if (made == null) {
                    continue;
                }
                if (!id.equals(made) || !Files.isRegularFile(zip)) {
                    bad.add(id + ": made '" + made + "' with no zip at " + zip);
                    continue;
                }
                rebuilt++;
                byte[] fresh = Files.readAllBytes(zip);
                List<String> diff = StarterRecipe.functionDiff(bytes("datapacks/" + id + "/" + id + ".zip"), fresh);
                System.out.println("STARTER_REGEN: " + id + " rebuilt (" + fresh.length + " bytes); functions "
                        + (diff.isEmpty() ? "match the shipped pack" : "differ from the shipped pack: " + diff));
                if (!out.isBlank()) {
                    Path dest = Path.of(out).resolve(id + ".zip");
                    Files.createDirectories(dest.getParent());
                    Files.write(dest, fresh);
                }
            }
        } catch (Exception e) {
            bad.add("threw: " + e);
        }
        System.out.println("STARTER_REGEN: " + rebuilt + " rebuilt, " + skipped + " skipped, " + bad.size() + " failed");
        helper.assertTrue(bad.isEmpty(), Component.literal("starter packs today's tools cannot rebuild: " + bad));
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
