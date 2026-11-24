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
 * brings every shipped pack up to date after bumping the compiler. Each recipe is replayed with the library creatures
 * it names registered ({@link #withStarterSpecies}), and a fresh pack must reach as far as the shipped one does once
 * installed in a world that has them ({@link #reachDiff}).
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

    /** What a replay runs while the starter's creatures exist. */
    @FunctionalInterface
    interface Body {
        void run() throws Exception;
    }

    /**
     * The creatures a recipe names: the {@code tag} of each {@code apply_effect} call, which is the species id the
     * ability is for ("the same id you gave sculpt_creature"), in call order, once each.
     */
    static List<String> speciesNamed(StarterRecipe.Recipe recipe) {
        List<String> out = new ArrayList<>();
        for (StarterRecipe.Call c : recipe.calls()) {
            if ("apply_effect".equals(c.tool()) && c.args().has("tag")) {
                String tag = c.args().get("tag").getAsString();
                if (!out.contains(tag)) {
                    out.add(tag);
                }
            }
        }
        return out;
    }

    /**
     * Run {@code body} in a world that has every library creature {@code ids} names, registered the way a player's
     * world gets one ({@link com.moddingfromamod.runtime.WishMobCommands#resolveSpecies}, the first spawn of a
     * library creature), so a replay computes whatever depends on the creature (a step's or a landing's reach
     * counted from its flank, {@code ApplyEffectTool.fillReach}) as it would there. Without it a GameTest world,
     * which has none, made on_land_trex with the marker and its middle-of-the-body reach: a pack the install-time
     * {@link com.moddingfromamod.datapack.ReachFromBodyFix} no longer widens, worse than the one it replaced.
     *
     * <p>Every id is put back as it was afterwards, and the world's species file saved again: the replay's own
     * {@code apply_effect} binds its pack to the creature and saves, so an in-memory undo alone would leave the
     * creature on disk. An id the library does not ship (rainbow_axolotl is a vanilla axolotl) is left alone and
     * said, never guessed.
     */
    static void withStarterSpecies(net.minecraft.server.MinecraftServer server, String id, List<String> ids,
                                   Body body) throws Exception {
        java.util.Map<String, com.moddingfromamod.species.Species> before = new java.util.LinkedHashMap<>();
        for (String s : ids) {
            before.put(s, com.moddingfromamod.species.SpeciesRegistry.get(s));
        }
        try {
            for (String s : ids) {
                if (before.get(s) != null) {
                    continue;
                }
                if (!WishLibrary.keys("species").contains(s)) {
                    System.out.println("STARTER_REGEN: " + id + " names '" + s + "', which the library ships no "
                            + "creature for; replayed without one");
                    continue;
                }
                var sp = com.moddingfromamod.runtime.WishMobCommands.resolveSpecies(server, s);
                if (sp == null) {
                    throw new IllegalStateException("the library ships '" + s + "' but it could not be registered");
                }
                System.out.println("STARTER_REGEN: " + id + " replayed with the library's '" + s + "' (scale "
                        + sp.scale() + ", half width " + com.moddingfromamod.vanilla.CreatureSpawn.halfWidth(s) + ")");
            }
            body.run();
        } finally {
            before.forEach((s, had) -> {
                if (com.moddingfromamod.species.SpeciesRegistry.get(s) != had) {
                    com.moddingfromamod.species.SpeciesRegistry.restoreForTest(server, s, had);
                }
            });
        }
    }

    /** Every function and function tag in a pack, by entry name. */
    private static java.util.Map<String, String> functionTexts(byte[] zip) throws Exception {
        java.util.Map<String, String> out = new java.util.TreeMap<>();
        try (var in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            for (java.util.zip.ZipEntry e; (e = in.getNextEntry()) != null; ) {
                if (!e.isDirectory() && e.getName().endsWith(".mcfunction")) {
                    out.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return out;
    }

    /**
     * Where the fresh pack reaches less or more far than an install makes the shipped pack reach in a world with the
     * same creatures ({@code DatapackDialect.correctFunction}, which runs {@code ReachFromBodyFix} with the live
     * creature widths), one line per function; empty when every step and landing reaches the same. Run inside
     * {@link #withStarterSpecies}, so "the shipped pack in a player's world" is the comparison, never the bare
     * GameTest world's.
     */
    static List<String> reachDiff(byte[] shipped, byte[] fresh) throws Exception {
        java.util.Map<String, String> a = functionTexts(shipped);
        java.util.Map<String, String> b = functionTexts(fresh);
        List<String> out = new ArrayList<>();
        for (var e : a.entrySet()) {
            List<String> want = com.moddingfromamod.datapack.ReachFromBodyFix.reaches(
                    com.moddingfromamod.datapack.DatapackDialect.correctFunction(e.getValue(), new ArrayList<>()));
            List<String> got = com.moddingfromamod.datapack.ReachFromBodyFix.reaches(b.getOrDefault(e.getKey(), ""));
            if (!want.equals(got)) {
                out.add(e.getKey() + ": the shipped pack installs reaching " + want + ", the fresh one reaches " + got);
            }
        }
        return out;
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
                String[] made = new String[1];
                byte[] shipped = bytes("datapacks/" + id + "/" + id + ".zip");
                byte[][] fresh = new byte[1][];
                withStarterSpecies(helper.getLevel().getServer(), id, speciesNamed(recipe), () -> {
                    for (StarterRecipe.Call call : recipe.calls()) {
                        made[0] = replay(id, call, bad);
                        if (made[0] == null) {
                            return;
                        }
                    }
                    if (!id.equals(made[0]) || !Files.isRegularFile(zip)) {
                        bad.add(id + ": made '" + made[0] + "' with no zip at " + zip);
                        made[0] = null;
                        return;
                    }
                    fresh[0] = Files.readAllBytes(zip);
                    // A fresh pack must reach as far as the shipped one does once installed in a world that
                    // has its creature: never narrower because the replay's world lacked it.
                    for (String r : reachDiff(shipped, fresh[0])) {
                        bad.add(id + ": " + r);
                    }
                });
                if (made[0] == null) {
                    continue;
                }
                rebuilt++;
                List<String> diff = StarterRecipe.functionDiff(shipped, fresh[0]);
                System.out.println("STARTER_REGEN: " + id + " rebuilt (" + fresh[0].length + " bytes); functions "
                        + (diff.isEmpty() ? "match the shipped pack" : "differ from the shipped pack: " + diff)
                        + "; reaches " + reachesOf(fresh[0]));
                if (!out.isBlank()) {
                    Path dest = Path.of(out).resolve(id + ".zip");
                    Files.createDirectories(dest.getParent());
                    Files.write(dest, fresh[0]);
                }
            }
        } catch (Exception e) {
            bad.add("threw: " + e);
        }
        System.out.println("STARTER_REGEN: " + rebuilt + " rebuilt, " + skipped + " skipped, " + bad.size() + " failed");
        helper.assertTrue(bad.isEmpty(), Component.literal("starter packs today's tools cannot rebuild: " + bad));
        helper.succeed();
    }

    private static List<String> reachesOf(byte[] zip) throws Exception {
        List<String> out = new ArrayList<>();
        for (String text : functionTexts(zip).values()) {
            out.addAll(com.moddingfromamod.datapack.ReachFromBodyFix.reaches(text));
        }
        return out;
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
                    p.createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER).withSuppressedOutput(),
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
