package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.compat.ModelData;
import com.moddingfromamod.datapack.VanillaCarrier;
import com.moddingfromamod.item.IconSprites;
import com.moddingfromamod.pack.NativeItemModels;
import com.moddingfromamod.pack.VanillaItemPack;
import com.moddingfromamod.pack.WishPackWriter;
import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>The native item path, and the shim under it (docs/COMPAT_SHIMS.md).</b>
 *
 * <p>Two halves, and they fail in opposite directions:
 * <ul>
 *   <li><b>the shim</b>: a stack a 1.21.1 jar minted is held by somebody else — a child's save — and
 *   carries only a number. Minecraft's own data fixer upgrades it on load. Nothing in our code calls
 *   anything on that path, so no call graph reaches the {@code range_dispatch} half that draws it;
 *   this test is the only thing that ever drives it, over every number the carrier was ever bound
 *   at;</li>
 *   <li><b>the new path</b>: every way we mint a wished thing names its own picture on 1.21.4, so no
 *   new stack depends on the carrier's table — and on the 1.21.1 compat build, where the component
 *   does not exist, every one still carries the number the old table draws.</li>
 * </ul>
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class NativeItemPathGameTest {

    private static final String BATCH = "nativeitems";

    /** The last data version a 1.21.1 jar wrote ({@code SharedConstants} on 1.21.1). */
    static final int DATA_VERSION_1_21_1 = 3955;

    // ── the pack, as a stock client receives it ──────────────────────────────────────────────

    private static JsonObject read(Path root, String path) throws Exception {
        return JsonParser.parseString(Files.readString(root.resolve(path), StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    /** 1.21.4's RangeSelectItemModel: the largest threshold at or below the value. */
    private static JsonObject rangeDispatch(JsonObject definition, float value) {
        JsonObject model = definition.getAsJsonObject("model");
        JsonObject hit = model.getAsJsonObject("fallback");
        JsonArray entries = model.getAsJsonArray("entries");
        for (int i = 0; i < entries.size(); i++) {
            JsonObject e = entries.get(i).getAsJsonObject();
            if (e.get("threshold").getAsFloat() <= value) {
                hit = e.getAsJsonObject("model");
            }
        }
        return hit;
    }

    /** A carrier stack exactly as a 1.21.1 jar saved it, then loaded by THIS jar's data fixer. */
    private static ItemStack loadedFrom1211(GameTestHelper helper, int customModelData) {
        CompoundTag components = new CompoundTag();
        components.putInt("minecraft:custom_model_data", customModelData);
        CompoundTag dyed = new CompoundTag();
        dyed.putInt("rgb", 0x3366FF);
        components.put("minecraft:dyed_color", dyed);
        CompoundTag saved = new CompoundTag();
        saved.putString("id", "minecraft:" + WishPackWriter.BASE_ITEM);
        saved.putInt("count", 1);
        saved.put("components", components);
        int now = SharedConstants.getCurrentVersion().dataVersion().version();
        Tag upgraded = DataFixers.getDataFixer().update(References.ITEM_STACK,
                new Dynamic<Tag>(NbtOps.INSTANCE, saved), DATA_VERSION_1_21_1, now).getValue();
        return com.moddingfromamod.compat.EntityNbt.parseStack(helper.getLevel().registryAccess(), upgraded).orElse(ItemStack.EMPTY);
    }

    /**
     * <b>Every number a 1.21.1 jar could have minted, loaded by this jar, still draws its artwork.</b>
     *
     * <p>The whole range, not a sample: every integer from 0 to one past the highest binding goes
     * through the real data fixer, and must come out carrying the same number. Every BOUND number
     * must then reach, through the pushed pack's {@code range_dispatch} (the shim — what a 1.21.4
     * client reads for a stack with no {@code item_model}), exactly the model node the native
     * definition for that binding draws, and that node's picture must be the model the binding
     * names — so an old stack and a new one of the same thing look the same. (Until step 3
     * the picture was also checked against the pack's pre-1.21.4 {@code overrides} table, which
     * the pack no longer writes: clients older than 1.21.4 are refused at join.)
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400)
    public static void everyStackA1211JarMintedStillDrawsItsArtwork(GameTestHelper helper) {
        Path root = null;
        try {
                    root = Files.createTempDirectory("native-items");
                    ItemPackService.buildPack(root);
                    JsonObject carrier = read(root, VanillaItemPack.itemDefinitionPath(WishPackWriter.BASE_ITEM));

                    List<NativeItemModels.Binding> bindings = NativeItemModels.all();
                    int last = bindings.get(bindings.size() - 1).customModelData();
                    int bound = 0;
                    List<String> wrong = new ArrayList<>();
                    for (int n = 0; n <= last + 1; n++) {
                        ItemStack s = loadedFrom1211(helper, n);
                        if (s.isEmpty() || !s.is(Items.LEATHER_HORSE_ARMOR)) {
                            wrong.add(n + ": the fixer lost the stack (" + s + ")");
                            continue;
                        }
                        var cmd = s.get(DataComponents.CUSTOM_MODEL_DATA);
                        if (cmd == null || ModelData.id(cmd) != n) {
                            wrong.add(n + ": the fixer turned the number into "
                                    + (cmd == null ? "nothing" : String.valueOf(ModelData.id(cmd))));
                            continue;
                        }
                        if (ModelData.itemModelOf(s) != null) {
                            wrong.add(n + ": an OLD stack gained an item_model it was never given");
                        }
                        String id = NativeItemModels.forNumber(n);
                        if (id == null) {
                            continue;
                        }
                        bound++;
                        String name = id.substring(id.indexOf('/') + 1);
                        JsonObject nativeNode = read(root, NativeItemModels.definitionPath(name))
                                .getAsJsonObject("model");
                        // ONE node serves both tables, so the shim must draw exactly
                        // what the name draws: backing, picture and overlay select alike.
                        JsonObject shim = rangeDispatch(carrier, ModelData.id(cmd));
                        if (!shim.equals(nativeNode)) {
                            wrong.add(n + " (" + name + "): the shim draws " + shim + " but the native path "
                                    + nativeNode);
                        }
                        // A wish icon is a composite — backing glow, twinkles, the PICTURE, the
                        // overlay select — and its picture is the vanilla base where
                        // items/icon-bases.json names one, else the icon's own sprite model.
                        JsonObject picture = nativeNode;
                        if ("minecraft:composite".equals(nativeNode.get("type").getAsString())) {
                            picture = nativeNode.getAsJsonArray("models")
                                    .get(VanillaItemPack.BACKING.size()).getAsJsonObject();
                        }
                        // What the binding NAMES, from the populations themselves rather than from
                        // the pack: an icon is <ns>:item/wish/<name>, an atom <ns>:item/<name>.
                        boolean isIcon = com.moddingfromamod.item.IconSprites.bindings().stream()
                                .anyMatch(sp -> sp.name().equals(name));
                        String own = isIcon ? VanillaItemPack.NAMESPACE + ":item/wish/" + name
                                : VanillaItemPack.extraModelId(name);
                        String vanilla = isIcon ? com.moddingfromamod.pack.IconBases.forIcon(name) : null;
                        String want = vanilla != null ? vanilla : own;
                        if (!picture.get("model").getAsString().equals(want)) {
                            wrong.add(n + " (" + name + "): 1.21.4 draws " + picture.get("model")
                                    + " where " + want + " was expected");
                        }
                    }
                    helper.assertTrue(bound == bindings.size(), Component.literal("drove " + bound + " bound numbers of "
                            + bindings.size() + " — the range walk missed a binding"));
                    helper.assertTrue(wrong.isEmpty(), Component.literal("old stacks that no longer draw what they drew: " + wrong));
                    helper.succeed();
                } catch (Exception e) {
                    helper.fail(Component.literal("could not build the pack to resolve against: " + e));
        } finally {
            deleteQuietly(root);
        }
    }

    /**
     * <b>Every way we mint a wished thing names its own model (1.21.4), or keeps the number the old
     * table draws (the 1.21.1 compat build, which cannot express {@code item_model}).</b>
     *
     * <p>Four mint sites, each taken through its real entry point: a {@code WishItem} stack, Moddy,
     * {@code MakeItemTool}'s give line run as a real {@code /give}, and an agent-style give line
     * written with the number only, taken through the text choke point ({@code CommandDialect}) the
     * way every datapack and replayed command is.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public static void aNewWishedThingNamesItsOwnModel(GameTestHelper helper) {
        int wand = IconSprites.numberFor("wand");
        String wandModel = NativeItemModels.forNumber(wand);
        List<String> wrong = new ArrayList<>();

        check(wrong, "WishItem.doing", com.moddingfromamod.item.WishItem.doing(
                Items.LEATHER_HORSE_ARMOR, wand, "x:y", 0xFFFFFF), wand, wandModel);
        check(wrong, "the watch", com.moddingfromamod.wish.VanillaWatchItem.stack(),
                VanillaCarrier.WATCH_MODEL_DATA, NativeItemModels.forNumber(VanillaCarrier.WATCH_MODEL_DATA));

        com.moddingfromamod.testing.MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper);
        try {
        var server = helper.getLevel().getServer();
        var source = p.createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER).withSuppressedOutput();

        String arg = com.moddingfromamod.agent.tools.MakeItemTool.itemArg(
                new com.moddingfromamod.agent.tools.MakeItemTool.Variant("Native Wand", 0x9966FF),
                wand, "Zap!", null, null,
                com.moddingfromamod.item.WishItem.WISH_FEATURE + ":\"t6d1_native_wand\"");
        server.getCommands().performPrefixedCommand(source, "give @s " + arg + " 1");
        check(wrong, "MakeItemTool's give line", named(p, "Native Wand"), wand, wandModel);

        String agentLine = "give @s minecraft:leather_horse_armor[minecraft:custom_model_data="
                + ModelData.snbt(wand) + ",minecraft:custom_name='\"Agent Wand\"'] 1";
        String corrected = com.moddingfromamod.wish.CommandDialect.correct(agentLine).command();
        server.getCommands().performPrefixedCommand(source, corrected);
        check(wrong, "an agent's give line through CommandDialect (" + corrected + ")",
                named(p, "Agent Wand"), wand, wandModel);
        } finally {
            // A mock left behind becomes "the child" for every later test (MockPlayerNetwork.remove).
            com.moddingfromamod.testing.MockPlayerNetwork.remove(p);
        }

        helper.assertTrue(wrong.isEmpty(), Component.literal((ModelData.NATIVE_ITEM_MODEL
                ? "new wished things still ride the carrier's number table: "
                : "the 1.21.1 compat build lost the number its table draws with: ") + wrong));
        helper.succeed();
    }

    private static void check(List<String> wrong, String site, ItemStack s, int number, String model) {
        if (s.isEmpty()) {
            wrong.add(site + ": produced nothing");
            return;
        }
        var cmd = s.get(DataComponents.CUSTOM_MODEL_DATA);
        if (cmd == null || ModelData.id(cmd) != number) {
            wrong.add(site + ": the number " + number + " is missing — the catalogue keys on it");
        }
        String named = ModelData.itemModelOf(s);
        String want = ModelData.NATIVE_ITEM_MODEL ? model : null;
        if (want == null ? named != null : !want.equals(named)) {
            wrong.add(site + ": item_model is " + named + ", expected " + want);
        }
    }

    private static ItemStack named(ServerPlayer p, String name) {
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            var n = s.get(DataComponents.CUSTOM_NAME);
            if (n != null && name.equals(n.getString())) {
                return s;
            }
        }
        return ItemStack.EMPTY;
    }

    private static void deleteQuietly(Path root) {
        if (root == null) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
        } catch (Exception ignored) {
            // a temp dir; the OS reclaims it
        }
    }
}
