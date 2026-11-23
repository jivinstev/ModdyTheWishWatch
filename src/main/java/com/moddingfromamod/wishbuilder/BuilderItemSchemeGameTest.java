package com.moddingfromamod.wishbuilder;

import static com.moddingfromamod.wishbuilder.BuilderAbilityGameTest.carrying;
import static com.moddingfromamod.wishbuilder.BuilderAbilityGameTest.onServer;
import static com.moddingfromamod.wishbuilder.BuilderAbilityGameTest.protocol;
import static com.moddingfromamod.wishbuilder.BuilderAbilityGameTest.take;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * <b>A gold pickaxe made in the Builder is gold in his hand</b> (on a Bedrock phone the item in his
 * hand was not gold). A made-up pickaxe is built on the real pickaxe, which every client draws as itself and which
 * takes no colour, so the Gold scheme was lost at the lowering: the stack he got was the diamond (at the basic tier, the
 * stone) pickaxe, on Java and on Bedrock alike. Made here through the real Builder ({@link BuilderMake}, the real
 * make_item), the stack in his bag must BE the golden one; the controls are the same pickaxe with no scheme and with a
 * scheme that names no material, both still the pickaxe as listed.: every scheme but gold also DRAWS the real
 * item in its colour ({@code item_model wishpack:tinted/<item>} and the scheme's main colour as dyed_color), read here off
 * the stack in his bag; no scheme and gold carry neither.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class BuilderItemSchemeGameTest {

    private BuilderItemSchemeGameTest() {
    }

    /** One make: its spec's scheme and tier, the name it arrives under, and the real item it must be. */
    record Case(String scheme, String tier, String stock, String power, String name, String expect, String model,
                Integer rgb) {
        Case(String scheme, String tier, String stock, String power, String name, String expect) {
            this(scheme, tier, stock, power, name, expect, null, null);
        }

        String spec() {
            return "{\"builder\":1,\"kind\":\"item\",\"from\":\"\",\"name\":\"" + name + "\""
                    + (scheme.isEmpty() ? "" : ",\"scheme\":\"" + scheme + "\"")
                    + ",\"opts\":{\"stock\":\"" + stock + "\",\"power\":\"" + power + "\",\"strength\":\"2\""
                    + (tier.isEmpty() ? "" : ",\"tier\":\"" + tier + "\"") + "}}";
        }
    }

    static final List<Case> CASES = List.of(
            new Case("gold", "", "pickaxe", "haste", "Goldy Pick", "minecraft:golden_pickaxe"),
            new Case("gold", BuilderItemPowers.TIER_BASIC, "pickaxe", "haste", "Basic Goldy Pick", "minecraft:golden_pickaxe"),
            new Case("gold", "", "sword", "lightning", "Goldy Sword", "minecraft:golden_sword"),
            new Case("", "", "pickaxe", "haste", "Plain Pick", "minecraft:diamond_pickaxe"),
            new Case("ocean", "", "pickaxe", "haste", "Ocean Pick", "minecraft:diamond_pickaxe",
                    "wishpack:tinted/diamond_pickaxe", 0x2E7DD1),
            // every scheme but gold is DRAWN on the real item: its tinted model and the scheme's colour
            new Case("candy", BuilderItemPowers.TIER_BASIC, "pickaxe", "haste", "Basic Candy Pick", "minecraft:stone_pickaxe",
                    "wishpack:tinted/stone_pickaxe", 0xFF8FC8),
            new Case("forest", "", "bow", "freeze", "Forest Bow", "minecraft:bow", "wishpack:tinted/bow", 0x3F8A3A),
            new Case("royal", "", "drink", "speed", "Royal Drink", "minecraft:potion", "wishpack:tinted/potion", 0x5B2C9F));

    @GameTest(template = "empty_test", batch = "builderScheme", timeoutTicks = 60000)
    public static void aGoldPickaxeArrivesAsTheGoldenPickaxe(GameTestHelper helper) {
        if (!com.moddingfromamod.datapack.CbScriptCompiler.canBuildReadyMades()) {
            helper.succeed();   // no compiler, no datapack: a power cannot be made here (CLAUDE.md: ply)
            return;
        }
        ServerPlayer p = com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper, "gtscheme");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        com.moddingfromamod.testing.MockPlayerNetwork.actingChildFor(helper, p);
        var server = helper.getLevel().getServer();
        List<String> wrong = java.util.Collections.synchronizedList(new ArrayList<>());
        boolean[] done = {false};
        Thread t = new Thread(() -> {
            long rise = BuilderMake.riseMillis;
            BuilderMake.riseMillis = 0;
            try {
                for (Case c : CASES) {
                    BuilderSpec spec = BuilderSpec.read(JsonParser.parseString(c.spec()).getAsJsonObject());
                    var who = BuilderMake.Who.of(p);
                    BuilderMake.Result made = onServer(server,
                            () -> BuilderMake.run(server, who, spec, null, protocol(), false, false))
                            .get(120, TimeUnit.SECONDS).get(120, TimeUnit.SECONDS);
                    String r;
                    if (made.outcome() != BuilderMake.Outcome.MADE && made.outcome() != BuilderMake.Outcome.PARTIAL) {
                        r = "not made: " + made.outcome() + " " + made.detail();
                    } else {
                        r = onServer(server, () -> {
                            ItemStack s = take(p, c.name());
                            String got = s == null ? null : BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
                            // drawn in the scheme (its tinted model, the colour as dyed_color), or plainly.
                            String model = s == null ? null : com.moddingfromamod.compat.ModelData.itemModelOf(s);
                            var dyed = s == null ? null
                                    : s.get(net.minecraft.core.component.DataComponents.DYED_COLOR);
                            Integer rgb = dyed == null ? null : dyed.rgb() & 0xFFFFFF;
                            p.getInventory().clearContent();
                            if (got == null) {
                                return "never reached his bag (" + carrying(p) + ")";
                            }
                            if (!got.equals(c.expect())) {
                                return "arrived as " + got + ", not " + c.expect();
                            }
                            if (!java.util.Objects.equals(model, c.model()) || !java.util.Objects.equals(rgb, c.rgb())) {
                                return "drawn by item_model " + model + " in " + (rgb == null ? "no colour"
                                        : String.format("#%06X", rgb)) + ", not " + c.model() + " in "
                                        + (c.rgb() == null ? "no colour" : String.format("#%06X", c.rgb()));
                            }
                            return null;
                        }).get(10, TimeUnit.SECONDS);
                    }
                    if (r != null) {
                        wrong.add(c.name() + " [scheme '" + c.scheme() + "', tier '" + c.tier() + "']: " + r);
                    }
                }
            } catch (Throwable e) {
                wrong.add("threw: " + e);
            } finally {
                BuilderMake.riseMillis = rise;
                done[0] = true;
            }
        }, "builder-scheme-gt");
        t.setDaemon(true);
        t.start();
        helper.onEachTick(() -> {
            if (done[0] && !wrong.isEmpty()) {
                throw com.moddingfromamod.testing.gt.GameTestCompat.assertion(
                        "a Builder item did not arrive in its colour's material: " + wrong);
            }
        });
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(done[0], Component.literal("still making"));
            helper.assertTrue(wrong.isEmpty(), Component.literal("wrong: " + wrong));
        });
    }
}
