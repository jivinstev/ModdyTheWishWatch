package com.moddingfromamod.wishbuilder;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.McpProtocol;
import com.moddingfromamod.agent.McpSessions;
import com.moddingfromamod.agent.WishAgent;
import com.moddingfromamod.bag.WishBagService;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.ui.PickPage;
import com.moddingfromamod.wish.WishBook;
import com.moddingfromamod.wish.WishStatus;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * <b>Supplies land in his bag as real vanilla items</b>, tested where they run: the menus of a world read from
 * its own manifest (a shipped world's), pressed tile by tile, the tune dialog's values, and {@link BuilderMake#run}
 * through the REAL {@code give_items}. Every assertion reads his hotbar, his inventory and his Wish Bag, never a
 * verdict: two water buckets, a stack of torches (a category of one: no second page), one lava bucket (that world allows
 * lava).
 *
 * <p>And a world that turns a category OFF ({@code crafters.builder.supplies.categories}) has no tile for it, refuses a
 * press of it, offers none of it from typed words (the Apprentice, on that world's own tree), and refuses a make of it
 * with nothing handed over; the control, the same world with every category on, offers it.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class BuilderSuppliesGameTest {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    private BuilderSuppliesGameTest() {
    }

    /** How many of {@code itemId} he holds: every inventory slot and his Wish Bag. On the server thread. */
    static int held(ServerPlayer p, String itemId) {
        Item item = BuiltInRegistries.ITEM.getValue(ResourceLocation.parse(itemId));
        int n = 0;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(item)) {
                n += s.getCount();
            }
        }
        for (ItemStack s : WishBagService.contents(p.getServer(), p.getUUID())) {
            if (!s.isEmpty() && s.is(item)) {
                n += s.getCount();
            }
        }
        return n;
    }

    /**
     * A Builder with every kind, temper and supplies category on: the shape of a shipped world's. The public repo has
     * no server manifests, so where the jar carries none this is the Builder the tests stand on.
     */
    static final String PUBLIC_BUILDER = """
            {"crafters": {"builder": {"enabled": true, "ruleScope": "own_island",
              "kinds": ["creature", "ride", "item", "machine", "magic_block", "building", "picture", "event", "supplies"],
              "temperaments": ["friendly", "shy", "wild", "guard", "boss", "pet", "stays"],
              "supplies": {"categories": ["wood", "stone", "farming", "light", "metal", "food", "work"], "most": 64}}}}""";

    /**
     * A shipped world's Builder, read from the manifest the jar ships (so its supplies knob is the one tested), or
     * {@link #PUBLIC_BUILDER} where the jar carries none (the public mod).
     */
    static BuilderAllowlist shippedWorldBuilder() {
        try (InputStream in = BuilderSuppliesGameTest.class.getResourceAsStream(com.moddingfromamod.world.WorldFacts.RESOURCE_DIR + "isle.json")) {
            String json = in == null ? PUBLIC_BUILDER : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return BuilderSettings.parse(json).allow();
        } catch (java.io.IOException | RuntimeException e) {
            throw new IllegalStateException("cannot read the world's manifest", e);
        }
    }

    /** The spec the menus make for these presses and the dialog's values; throws on a press the menus refuse. */
    static BuilderSpec pressed(BuilderAllowlist allow, Map<String, String> values, String... presses) {
        BuilderFlow f = new BuilderFlow(allow, LibraryPresets.SHIPPED);
        for (String id : presses) {
            BuilderFlow.Outcome o = f.press(id);
            if (!o.say().isEmpty()) {
                throw new IllegalStateException("pressing " + id + " was refused: " + o.say());
            }
        }
        if (f.step() != BuilderFlow.Step.TUNE) {
            throw new IllegalStateException("the presses " + List.of(presses) + " stopped at " + f.step());
        }
        return f.spec(values);
    }

    @GameTest(template = "empty_test", batch = "builderSupplies", timeoutTicks = 6000)
    public static void suppliesFromTheBuildersMenusLandInHisBagAsRealItems(GameTestHelper helper) {
        BuilderAllowlist allow = shippedWorldBuilder();
        ServerPlayer child = MockPlayerNetwork.inLevel(helper, "gtsupplies");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        child.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        MockPlayerNetwork.actingChildFor(helper, child);
        long rise = BuilderMake.riseMillis;
        BuilderMake.riseMillis = 0;   // the pillar's rise is BuilderMakeGameTest's
        MockPlayerNetwork.afterTest(helper, () -> BuilderMake.riseMillis = rise);
        MinecraftServer server = helper.getLevel().getServer();
        WishBagService.clearForTest(server, child.getUUID());
        var who = BuilderMake.Who.of(child);

        // Each: the presses, the dialog's values, the item and how many must land.
        record Case(String item, int want, BuilderSpec spec) {
        }
        List<Case> cases = List.of(
                new Case("minecraft:water_bucket", 2, pressed(allow, Map.of("amount", "2"),
                        "supplies", "supplies:work", "supply:water_bucket")),
                // Light is torches alone: the category goes straight to the dialog. A stack is that world's most.
                new Case("minecraft:torch", 64, pressed(allow, Map.of("amount", "64"), "supplies", "supplies:light")),
                new Case("minecraft:lava_bucket", 1, pressed(allow, Map.of(), "supplies", "supplies:work", "supply:lava_bucket")),
                new Case("minecraft:oak_log", 16, pressed(allow, Map.of(), "supplies", "supplies:wood", "supply:oak_log")));
        List<String> wrong = java.util.Collections.synchronizedList(new ArrayList<>());
        List<String> verdicts = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicReference<CompletableFuture<?>> chain = new AtomicReference<>(CompletableFuture.completedFuture(null));
        for (Case c : cases) {
            chain.set(chain.get().thenComposeAsync(x -> once(server, who, c.spec(), allow)).thenAcceptAsync(res -> {
                var wish = res.wishId() == null ? null : WishBook.get(server).byId(res.wishId()).orElse(null);
                verdicts.add(c.item() + " → " + res.outcome() + " " + res.calls() + " " + res.detail());
                if (res.outcome() != BuilderMake.Outcome.MADE || wish == null || wish.status() != WishStatus.DONE
                        || !res.calls().contains("give_items")) {
                    wrong.add(c.item() + ": " + res.outcome() + " " + res.calls() + " " + res.detail()
                            + " book=" + (wish == null ? "none" : wish.status()));
                }
            }));
        }
        CompletableFuture<?> all = chain.get();
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(all.isDone(), Component.literal("still making: " + verdicts));
            helper.assertFalse(all.isCompletedExceptionally(), Component.literal("a make threw: " + verdicts));
            helper.assertTrue(wrong.isEmpty(), Component.literal(String.join("\n", wrong)));
            for (Case c : cases) {
                int have = held(child, c.item());
                helper.assertValueEqual(have, c.want(), Component.literal(c.item() + " in his hotbar, inventory and Wish Bag"));
            }
            LOGGER.info("builder supplies GT: {}", verdicts);
        });
    }

    @GameTest(template = "empty_test", batch = "builderSuppliesOff", timeoutTicks = 4000)
    public static void aCategoryAWorldTurnsOffIsOfferedNowhereAndCannotBeMade(GameTestHelper helper) {
        BuilderAllowlist on = shippedWorldBuilder();
        Set<String> cats = new LinkedHashSet<>(on.supplies().categories());
        cats.remove("farming");
        BuilderAllowlist off = on.withSupplies(new BuilderSupplies.Limits(cats, on.supplies().most()));

        // The menus: no Farming tile, and a press of it is refused.
        BuilderFlow menus = new BuilderFlow(off, LibraryPresets.SHIPPED);
        menus.press("supplies");
        helper.assertTrue(menus.page().tiles().stream().map(PickPage.Tile::id).noneMatch("supplies:farming"::equals),
                Component.literal("the farming tile is on a world that turned farming off"));
        helper.assertFalse(menus.press("supplies:farming").say().isEmpty(), Component.literal("a press of farming was not refused"));
        BuilderFlow control = new BuilderFlow(on, LibraryPresets.SHIPPED);
        control.press("supplies");
        helper.assertTrue(control.page().tiles().stream().map(PickPage.Tile::id).anyMatch("supplies:farming"::equals),
                Component.literal("control: the world as shipped has no farming tile"));

        // The Apprentice, on each world's own tree: typed words reach seeds only where farming is on.
        String words = "seeds for my farm";
        Apprentice offApp = new Apprentice(BuilderTree.of(off, LibraryPresets.SHIPPED), Apprentice.english());
        Apprentice onApp = new Apprentice(BuilderTree.of(on, LibraryPresets.SHIPPED), Apprentice.english());
        helper.assertTrue(offApp.match(words).offers().stream().noneMatch(p -> p.first("from").equals("supply:wheat_seeds")),
                Component.literal("the Apprentice offers seeds on a world with no farming: " + offApp.match(words).offers()));
        helper.assertTrue(onApp.match(words).offers().stream().anyMatch(p -> p.first("from").equals("supply:wheat_seeds")),
                Component.literal("control: the Apprentice offers no seeds in that world: " + onApp.match(words).offers()));

        // A make of it (a spec from elsewhere, an old page) is refused there, and nothing is handed over.
        ServerPlayer child = MockPlayerNetwork.inLevel(helper, "gtsuppliesoff");
        MockPlayerNetwork.actingChildFor(helper, child);
        MinecraftServer server = helper.getLevel().getServer();
        WishBagService.clearForTest(server, child.getUUID());
        BuilderSpec seeds = pressed(on, Map.of("amount", "16"), "supplies", "supplies:farming", "supply:wheat_seeds");
        CompletableFuture<BuilderMake.Result> r = once(server, BuilderMake.Who.of(child), seeds, off);
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(r.isDone(), Component.literal("still making"));
            BuilderMake.Result res = r.join();
            helper.assertTrue(res.outcome() == BuilderMake.Outcome.REFUSED,
                    Component.literal("a farming make on a world with no farming: " + res.outcome() + " " + res.detail()));
            helper.assertValueEqual(held(child, "minecraft:wheat_seeds"), 0, Component.literal("seeds he was handed anyway"));
        });
    }

    private static CompletableFuture<BuilderMake.Result> once(MinecraftServer server, BuilderMake.Who who, BuilderSpec spec,
                                                              BuilderAllowlist allow) {
        CompletableFuture<BuilderMake.Result> r = new CompletableFuture<>();
        server.execute(() -> BuilderMake.run(server, who, spec, allow, protocol(), false, false)
                .whenComplete((res, err) -> {
                    if (err != null) {
                        r.completeExceptionally(err);
                    } else {
                        r.complete(res);
                    }
                }));
        return r;
    }

    private static volatile McpProtocol protocol;

    private static McpProtocol protocol() {
        if (protocol == null) {
            synchronized (BuilderSuppliesGameTest.class) {
                if (protocol == null) {
                    protocol = new McpProtocol(WishAgent.buildTools(), new McpSessions());
                }
            }
        }
        return protocol;
    }
}
