package com.moddingfromamod.datapack;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.ApplyEffectTool;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>The recolour part really recolours, on every mob it claims.</b>
 *
 * <p>{@link ColourCycle} names a saved field and a list of values per mob, and a wrong field name
 * is the worst kind of wrong: {@code data modify entity @s Colour set value 3} on a sheep is not an
 * error, it is a successful write of a key the sheep never reads, so the ability compiles, loads,
 * verifies and never changes a colour. So each claim is checked on a real mob: write each value,
 * read the mob back, and require the mob to have KEPT it.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class ColourCycleGameTest {

    @GameTest(template = "empty_test", timeoutTicks = 200)
    public static void everyPaletteValueIsOneTheMobKeeps(GameTestHelper helper) {
        List<String> wrong = new ArrayList<>();
        for (String mob : ColourCycle.mobs()) {
            ColourCycle.Palette p = ColourCycle.palette(mob);
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(
                    ResourceLocation.withDefaultNamespace(mob));
            Entity e = helper.spawn(type, new BlockPos(1, 2, 1));
            e.addTag("mfam_colour_probe");
            for (String v : p.values()) {
                helper.getLevel().getServer().getCommands().performPrefixedCommand(
                        helper.getLevel().getServer().createCommandSourceStack().withSuppressedOutput(),
                        "data modify entity " + e.getStringUUID() + " " + p.field()
                                + " set value " + v);
                Tag kept = com.moddingfromamod.compat.EntityNbt.save(e).get(p.field());
                if (kept == null || !kept.toString().equals(v)) {
                    wrong.add(mob + "." + p.field() + "=" + v + " read back " + kept);
                }
            }
            e.discard();
        }
        helper.assertTrue(wrong.isEmpty(), Component.literal("these colours are not ones the mob keeps, so the "
                + "cycle would write them and nothing would change: " + wrong));
        helper.succeed();
    }

    /** The whole path: apply_effect → a compiled, loaded clock → the axolotl actually changes. */
    @GameTest(template = "empty_test", timeoutTicks = 400)
    public static void aRainbowAxolotlChangesColourOnItsOwn(GameTestHelper helper) {
        if (!CbScriptCompiler.available()) {
            System.out.println("COLOUR CYCLE: SKIPPED — no CBScript compiler; proved nothing.");
            helper.succeed();
            return;
        }
        Entity axolotl = helper.spawn(EntityType.AXOLOTL, new BlockPos(1, 2, 1));
        axolotl.addTag("mfam_rainbow_probe");
        int[] start = {variant(axolotl)};

        JsonObject o = new JsonObject();
        o.addProperty("effect", "colour_cycle");
        o.addProperty("tag", "mfam_rainbow_probe");
        JsonObject params = new JsonObject();
        params.addProperty("MOB", "axolotl");
        params.addProperty("EVERY_SECONDS", "0.1");
        o.add("params", params);
        ToolResult r = new ApplyEffectTool().call(o);
        helper.assertTrue(!r.isError(), Component.literal("apply_effect refused colour_cycle: " + r.text()));

        List<Integer> seen = new ArrayList<>();
        helper.onEachTick(() -> {
            int v = variant(axolotl);
            if (seen.isEmpty() || seen.get(seen.size() - 1) != v) {
                seen.add(v);
            }
        });
        helper.runAfterDelay(120, () -> {
            helper.assertTrue(seen.size() >= 3, Component.literal("the axolotl was supposed to step through its "
                    + "colours on its own; it went " + seen + " from " + start[0]));
            helper.succeed();
        });
    }

    private static int variant(Entity e) {
        return com.moddingfromamod.compat.EntityNbt.save(e).getIntOr("Variant", 0);
    }
}
