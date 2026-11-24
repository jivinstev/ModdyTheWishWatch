package com.moddingfromamod.wishbuilder;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.McpProtocol;
import com.moddingfromamod.agent.McpSessions;
import com.moddingfromamod.agent.WishAgent;
import com.moddingfromamod.bag.WishBagService;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;


/**
 * <b>A look made through the real {@code make_item}</b>: "🎨 More looks…" → Hats, crowns &amp; treasure → Crown →
 * Just for fun is a leather helmet he WEARS, drawn in his hand as the crown (the library's number and model), named
 * Crown; a cupcake for fun is a cookie he eats, drawn as the cupcake; a wizard hat with super speed is worn on his head and drawn as the wizard hat in his hand,
 * not the leather piece. Read off the stacks he holds, never a verdict.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class BuilderLooksGameTest {

    private BuilderLooksGameTest() {
    }

    private static volatile McpProtocol protocol;

    private static McpProtocol protocol() {
        if (protocol == null) {
            synchronized (BuilderLooksGameTest.class) {
                if (protocol == null) {
                    protocol = new McpProtocol(WishAgent.buildTools(), new McpSessions());
                }
            }
        }
        return protocol;
    }

    public static BuilderSpec pressed(String look, String power) {
        BuilderFlow f = new BuilderFlow(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED);
        BuilderLooks.Look l = BuilderLooks.look(look);
        for (String id : List.of("item", BuilderLooks.MORE_ID, BuilderLooks.GROUP_PREFIX + l.group(), BuilderLooks.PREFIX + look,
                "itempower:" + power)) {
            BuilderFlow.Outcome o = f.press(id);
            if (!o.say().isEmpty()) {
                throw new IllegalStateException("pressing " + id + " was refused: " + o.say());
            }
        }
        return f.spec(Map.of());
    }

    /** The stack of {@code item} (null: any) in his inventory or Wish Bag whose icon number is {@code icon}, or empty. */
    public static ItemStack held(ServerPlayer p, net.minecraft.world.item.Item item, int icon) {
        List<ItemStack> all = new ArrayList<>();
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            all.add(inv.getItem(i));
        }
        all.addAll(WishBagService.contents(p.level().getServer(), p.getUUID()));
        for (ItemStack s : all) {
            var cmd = s.get(DataComponents.CUSTOM_MODEL_DATA);
            if (!s.isEmpty() && (item == null || s.is(item)) && cmd != null && com.moddingfromamod.compat.ModelData.id(cmd) == icon) {
                return s;
            }
        }
        return ItemStack.EMPTY;
    }

    @GameTest(template = "empty_test", batch = "builderLooks", timeoutTicks = 6000)
    public static void aCrownAndAWizardHatArriveAsHelmetsWearingTheirIcons(GameTestHelper helper) {
        ServerPlayer child = MockPlayerNetwork.inLevel(helper, "gtlooks");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        child.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        MockPlayerNetwork.actingChildFor(helper, child);
        long rise = BuilderMake.riseMillis;
        BuilderMake.riseMillis = 0;
        boolean card = BuilderMake.takeCard;
        BuilderMake.takeCard = false;
        MockPlayerNetwork.afterTest(helper, () -> {
            BuilderMake.riseMillis = rise;
            BuilderMake.takeCard = card;
        });
        MinecraftServer server = helper.getLevel().getServer();
        WishBagService.clearForTest(server, child.getUUID());
        child.getInventory().clearContent();
        var who = BuilderMake.Who.of(child);
        BuilderSpec crown = pressed("crown", BuilderLooks.SHOW);
        BuilderSpec wizard = pressed("wizard_hat", "speed");
        BuilderSpec cupcake = pressed("cupcake", BuilderLooks.SHOW);
        List<String> said = java.util.Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<Void> both = once(server, who, crown).thenComposeAsync(r -> {
            said.add("crown → " + r.outcome() + " " + r.calls() + " " + r.detail());
            return once(server, who, wizard);
        }).thenComposeAsync(r -> {
            said.add("wizard_hat → " + r.outcome() + " " + r.calls() + " " + r.detail());
            return once(server, who, cupcake);
        }).thenAcceptAsync(r -> said.add("cupcake → " + r.outcome() + " " + r.calls() + " " + r.detail()));
        int cupcakeIcon = com.moddingfromamod.datapack.ItemCatalogue.ICONS.get("cupcake");
        int crownIcon = com.moddingfromamod.datapack.ItemCatalogue.ICONS.get("crown");
        int wizardIcon = com.moddingfromamod.datapack.ItemCatalogue.ICONS.get("wizard_hat");
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(both.isDone(), Component.literal("still making: " + said));
            helper.assertFalse(both.isCompletedExceptionally(), Component.literal("a make threw: " + said));
            ItemStack c = held(child, net.minecraft.world.item.Items.LEATHER_HELMET, crownIcon);
            helper.assertTrue(!c.isEmpty(), Component.literal("#2330: a leather helmet drawn as the crown (" + crownIcon + "): " + said
                    + " he holds " + holding(child)));
            var crownModel = c.get(DataComponents.ITEM_MODEL);
            helper.assertTrue(crownModel != null && !crownModel.toString().equals("minecraft:leather_helmet"),
                    Component.literal("drawn as the crown, not the leather helmet: " + crownModel));
            helper.assertTrue(c.get(DataComponents.EQUIPPABLE) != null
                            && c.get(DataComponents.EQUIPPABLE).slot() == net.minecraft.world.entity.EquipmentSlot.HEAD,
                    Component.literal("a crown he WEARS on his head"));
            ItemStack k = held(child, net.minecraft.world.item.Items.COOKIE, cupcakeIcon);
            helper.assertTrue(!k.isEmpty() && k.get(DataComponents.FOOD) != null,
                    Component.literal("#2330: a cookie he EATS, drawn as the cupcake (" + cupcakeIcon + "): " + holding(child)));
            helper.assertTrue(c.getHoverName().getString().contains("Crown"), Component.literal("named " + c.getHoverName().getString()));
            ItemStack w = held(child, null, wizardIcon);
            helper.assertTrue(!w.isEmpty(), Component.literal("a worn item wearing the wizard hat icon (" + wizardIcon + "): " + said));
            var model = w.get(DataComponents.ITEM_MODEL);
            helper.assertTrue(model == null || !model.toString().equals("minecraft:leather_helmet"),
                    Component.literal("drawn as the wizard hat in his hand, not the leather piece: " + model));
            var eq = w.get(DataComponents.EQUIPPABLE);
            helper.assertTrue(eq != null && eq.slot() == net.minecraft.world.entity.EquipmentSlot.HEAD,
                    Component.literal("and still worn on his head: " + eq));
        });
    }

    /**
     * a Cookie Jar and a Fishing Rod look, made through the real {@code make_item}, arrive in his hands. Both were
     * refused ("'Cookie Jar' is a cookie…", the real-item hint) and the tile failed in front of him.
     */
    @GameTest(template = "empty_test", batch = "builderLooks", timeoutTicks = 6000)
    public static void aCookieJarAndAFishingRodLookAreMadeNotRefused(GameTestHelper helper) {
        ServerPlayer child = MockPlayerNetwork.inLevel(helper, "gtlooks2");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        child.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        MockPlayerNetwork.actingChildFor(helper, child);
        long rise = BuilderMake.riseMillis;
        BuilderMake.riseMillis = 0;
        boolean card = BuilderMake.takeCard;
        BuilderMake.takeCard = false;
        MockPlayerNetwork.afterTest(helper, () -> {
            BuilderMake.riseMillis = rise;
            BuilderMake.takeCard = card;
        });
        MinecraftServer server = helper.getLevel().getServer();
        WishBagService.clearForTest(server, child.getUUID());
        child.getInventory().clearContent();
        var who = BuilderMake.Who.of(child);
        BuilderSpec jar = pressed("cookie_jar", BuilderLooks.SHOW);
        BuilderSpec rod = pressed("fishing_rod", BuilderLooks.SHOW);
        List<String> said = java.util.Collections.synchronizedList(new ArrayList<>());
        BuilderMake.Result[] results = new BuilderMake.Result[2];
        CompletableFuture<Void> both = once(server, who, jar).thenComposeAsync(r -> {
            results[0] = r;
            said.add("cookie_jar → " + r.outcome() + " " + r.calls() + " " + r.detail());
            return once(server, who, rod);
        }).thenAcceptAsync(r -> {
            results[1] = r;
            said.add("fishing_rod → " + r.outcome() + " " + r.calls() + " " + r.detail());
        });
        int jarIcon = com.moddingfromamod.datapack.ItemCatalogue.ICONS.get("cookie_jar");
        int rodIcon = com.moddingfromamod.datapack.ItemCatalogue.ICONS.get("fishing_rod");
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(both.isDone(), Component.literal("still making: " + said));
            helper.assertFalse(both.isCompletedExceptionally(), Component.literal("a make threw: " + said));
            for (BuilderMake.Result r : results) {
                helper.assertTrue(r != null && r.outcome() == BuilderMake.Outcome.MADE,
                        Component.literal("#2666: refused, not made: " + said));
            }
            ItemStack j = held(child, null, jarIcon);
            helper.assertTrue(!j.isEmpty() && j.getHoverName().getString().contains("Cookie Jar"),
                    Component.literal("a Cookie Jar drawn as the cookie jar (" + jarIcon + "): " + said + " he holds " + holding(child)));
            ItemStack f = held(child, null, rodIcon);
            helper.assertTrue(!f.isEmpty() && f.getHoverName().getString().contains("Fishing Rod"),
                    Component.literal("a Fishing Rod drawn as the fishing rod (" + rodIcon + "): " + said + " he holds " + holding(child)));
        });
    }

    public static String holding(ServerPlayer p) {
        List<String> out = new ArrayList<>();
        var inv = p.getInventory();
        List<ItemStack> all = new ArrayList<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            all.add(inv.getItem(i));
        }
        all.addAll(WishBagService.contents(p.level().getServer(), p.getUUID()));
        for (ItemStack s : all) {
            if (!s.isEmpty()) {
                out.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()) + " cmd="
                        + s.get(DataComponents.CUSTOM_MODEL_DATA) + " model=" + s.get(DataComponents.ITEM_MODEL)
                        + " '" + s.getHoverName().getString() + "'");
            }
        }
        return out.toString();
    }

    private static CompletableFuture<BuilderMake.Result> once(MinecraftServer server, BuilderMake.Who who, BuilderSpec spec) {
        CompletableFuture<BuilderMake.Result> r = new CompletableFuture<>();
        server.execute(() -> BuilderMake.run(server, who, spec, BuilderAllowlist.REVIEWED, protocol(), false, false)
                .whenComplete((res, err) -> {
                    if (err != null) {
                        r.completeExceptionally(err);
                    } else {
                        r.complete(res);
                    }
                }));
        return r;
    }
}
