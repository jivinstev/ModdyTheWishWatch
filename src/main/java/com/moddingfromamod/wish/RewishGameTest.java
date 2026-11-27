package com.moddingfromamod.wish;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.wishbuilder.BuilderMake;
import com.moddingfromamod.wishbuilder.BuilderSettings;
import com.moddingfromamod.wishbuilder.BuilderSpec;
import com.moddingfromamod.wishbuilder.BuilderSpecStore;
import com.moddingfromamod.world.WorldCrafters;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 *, where they RUN: a typed wish, through {@code WishService.submit} exactly as the wish box sends it, read by
 * the local reader against his own things. With {@code wish.rewish} on, "make Sparkle bigger" changes HIS Builder cat in
 * place at once (the Builder's own edit: same id, bigger, his shelf keeps it), and a change the Builder cannot make goes
 * on as typed. With it off, a Wish Crafter whose words change one of his things is told what he can do, never shown a
 * page of NEW makes.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class RewishGameTest {

    @GameTest(template = "empty_test", batch = "rewish", timeoutTicks = 12000)   // sets the acting child
    public static void aTypedReWishChangesHisThingAtOnceAndAnythingElseGoesOn(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, "rewish_kid");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        MockPlayerNetwork.actingChildFor(helper, p);
        var server = helper.getLevel().getServer();
        String owner = p.getUUID().toString();
        long rise = BuilderMake.riseMillis;
        boolean card = BuilderMake.takeCard;
        BuilderMake.riseMillis = 0;
        BuilderMake.takeCard = false;
        String builderWas = System.getProperty(BuilderSettings.ENABLED_PROPERTY);
        System.setProperty(BuilderSettings.ENABLED_PROPERTY, "true");
        CrafterService.forgetForTest(p.getUUID());
        MockPlayerNetwork.afterTest(helper, () -> {
            BuilderMake.riseMillis = rise;
            BuilderMake.takeCard = card;
            Rewish.onForTest(null);
            System.clearProperty(WorldCrafters.OPEN_PROPERTY);
            if (builderWas == null) {
                System.clearProperty(BuilderSettings.ENABLED_PROPERTY);
            } else {
                System.setProperty(BuilderSettings.ENABLED_PROPERTY, builderWas);
            }
            CrafterService.forgetForTest(p.getUUID());
        });
        BuilderSpec cat = BuilderSpec.read(JsonParser.parseString(
                "{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\",\"size\":2,\"name\":\"Sparkle\"}").getAsJsonObject());
        AtomicReference<String> id = new AtomicReference<>();
        AtomicReference<Double> before = new AtomicReference<>();
        AtomicReference<String> wrong = new AtomicReference<>();
        BuilderMake.run(server, BuilderMake.Who.of(p), cat, null, BuilderMake.protocolForTest(), false, false).thenAcceptAsync(made ->
                server.execute(() -> {
                    if (made.outcome() != BuilderMake.Outcome.MADE && made.outcome() != BuilderMake.Outcome.PARTIAL) {
                        wrong.set("the cat was not made: " + made.detail());
                        return;
                    }
                    var sp = com.moddingfromamod.species.SpeciesRegistry.get(made.madeId());
                    before.set(sp == null ? -1 : sp.scale());
                    // With the knob OFF, nothing is read for a Magician: the wish goes on as typed.
                    Rewish.onForTest(false);
                    if (Rewish.tryAnswer(p, "make Sparkle bigger", WishSource.TYPED, WishRoute.Hint.AUTO)) {
                        wrong.set("with wish.rewish off, a Magician's typed wish was answered locally");
                        return;
                    }
                    Rewish.onForTest(true);
                    // Not the Builder's (a hat), a question, a press: never answered here.
                    if (Rewish.tryAnswer(p, "make Sparkle wear a top hat", WishSource.TYPED, WishRoute.Hint.AUTO)
                            || Rewish.tryAnswer(p, "can Sparkle fly?", WishSource.TYPED, WishRoute.Hint.AUTO)
                            || Rewish.tryAnswer(p, "make Sparkle bigger", WishSource.PRESET, WishRoute.Hint.AUTO)
                            || Rewish.tryAnswer(p, "make Sparkle bigger", WishSource.TYPED, WishRoute.Hint.MAGICIAN)) {
                        wrong.set("a wish the reader must leave alone was answered locally");
                        return;
                    }
                    // THE re-wish: typed, through the real submit.
                    WishService.Outcome out = WishService.submit(p, "make Sparkle bigger", WishSource.TYPED);
                    if (out != WishService.Outcome.APPRENTICE) {
                        wrong.set("'make Sparkle bigger' was not answered at once: " + out);
                        return;
                    }
                    id.set(made.madeId());
                }));
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(wrong.get() == null, Component.literal(String.valueOf(wrong.get())));
            helper.assertTrue(id.get() != null, Component.literal("still making the cat"));
            var sp = com.moddingfromamod.species.SpeciesRegistry.get(id.get());
            helper.assertTrue(sp != null && sp.scale() > before.get() + 0.3,
                    Component.literal("'make Sparkle bigger' did not make '" + id.get() + "' bigger: scale "
                            + (sp == null ? "none" : sp.scale()) + ", was " + before.get()));
            BuilderSpecStore.Made kept = BuilderSpecStore.get(owner, id.get());
            helper.assertTrue(kept != null && kept.read().size() == 3,
                    Component.literal("his shelf does not keep the change: " + (kept == null ? null : kept.spec())));
        });
    }

    @GameTest(template = "empty_test", batch = "rewishCrafter", timeoutTicks = 12000)   // sets the acting child
    public static void aCraftersChangeIsToldWhatHeCanDoNeverShownNewMakes(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, "rewish_crafter");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        MockPlayerNetwork.actingChildFor(helper, p);
        var server = helper.getLevel().getServer();
        long rise = BuilderMake.riseMillis;
        boolean card = BuilderMake.takeCard;
        BuilderMake.riseMillis = 0;
        BuilderMake.takeCard = false;
        String builderWas = System.getProperty(BuilderSettings.ENABLED_PROPERTY);
        System.setProperty(BuilderSettings.ENABLED_PROPERTY, "true");
        MockPlayerNetwork.afterTest(helper, () -> {
            BuilderMake.riseMillis = rise;
            BuilderMake.takeCard = card;
            Rewish.onForTest(null);
            System.clearProperty(WorldCrafters.OPEN_PROPERTY);
            if (builderWas == null) {
                System.clearProperty(BuilderSettings.ENABLED_PROPERTY);
            } else {
                System.setProperty(BuilderSettings.ENABLED_PROPERTY, builderWas);
            }
            CrafterService.forgetForTest(p.getUUID());
        });
        BuilderSpec cat = BuilderSpec.read(JsonParser.parseString(
                "{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\",\"name\":\"Tumble\"}").getAsJsonObject());
        AtomicReference<String> wrong = new AtomicReference<>();
        AtomicReference<Boolean> done = new AtomicReference<>(false);
        BuilderMake.run(server, BuilderMake.Who.of(p), cat, null, BuilderMake.protocolForTest(), false, false).thenAcceptAsync(made ->
                server.execute(() -> {
                    System.setProperty(WorldCrafters.OPEN_PROPERTY, "true");
                    CrafterService.forgetForTest(p.getUUID());
                    if (!CrafterService.isCrafter(p)) {
                        wrong.set("control: off the list with the door open, a Crafter");
                        return;
                    }
                    Rewish.onForTest(false);
                    int mark = MockPlayerNetwork.chat(p).size();
                    if (!Rewish.tryAnswer(p, "make Tumble wear a top hat", WishSource.TYPED, WishRoute.Hint.AUTO)) {
                        wrong.set("a Crafter's change of his own cat went on to the new-make page");
                        return;
                    }
                    var said = MockPlayerNetwork.chat(p).subList(mark, MockPlayerNetwork.chat(p).size());
                    if (said.stream().noneMatch(l -> l.contains("The Builder can't change that"))) {
                        wrong.set("he was not told the Builder can't make it: " + said);
                        return;
                    }
                    if (p.containerMenu instanceof com.moddingfromamod.ui.MenuKit.PickMenu) {
                        wrong.set("a page of new makes opened");
                        return;
                    }
                    int mark2 = MockPlayerNetwork.chat(p).size();
                    if (!Rewish.tryAnswer(p, "make Tumble bigger", WishSource.TYPED, WishRoute.Hint.AUTO)
                            || MockPlayerNetwork.chat(p).subList(mark2, MockPlayerNetwork.chat(p).size()).stream()
                            .noneMatch(l -> l.contains("Change it in the Builder"))) {
                        wrong.set("with re-wishes off, a Builder-able change was not pointed at the Builder");
                        return;
                    }
                    done.set(true);
                }));
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(wrong.get() == null, Component.literal(String.valueOf(wrong.get())));
            helper.assertTrue(done.get(), Component.literal("still making the cat"));
        });
    }

    /**
     * A CLOSE answer is OFFERED: "make fluffy bigger" when he has two Fluffys opens a page of
     * both, each saying what it would do, and the Magician's way on; pressing one makes THAT one bigger and leaves the
     * other alone.
     */
    @GameTest(template = "empty_test", batch = "rewishOffer", timeoutTicks = 12000)   // sets the acting child
    public static void aCloseReWishOffersEachThingAndThePressedOneChanges(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, "rewish_offer");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        MockPlayerNetwork.actingChildFor(helper, p);
        var server = helper.getLevel().getServer();
        long rise = BuilderMake.riseMillis;
        boolean card = BuilderMake.takeCard;
        BuilderMake.riseMillis = 0;
        BuilderMake.takeCard = false;
        String builderWas = System.getProperty(BuilderSettings.ENABLED_PROPERTY);
        System.setProperty(BuilderSettings.ENABLED_PROPERTY, "true");
        CrafterService.forgetForTest(p.getUUID());
        MockPlayerNetwork.afterTest(helper, () -> {
            BuilderMake.riseMillis = rise;
            BuilderMake.takeCard = card;
            Rewish.onForTest(null);
            if (builderWas == null) {
                System.clearProperty(BuilderSettings.ENABLED_PROPERTY);
            } else {
                System.setProperty(BuilderSettings.ENABLED_PROPERTY, builderWas);
            }
        });
        AtomicReference<String> blue = new AtomicReference<>();
        AtomicReference<String> red = new AtomicReference<>();
        AtomicReference<Double> blueWas = new AtomicReference<>();
        AtomicReference<Double> redWas = new AtomicReference<>();
        AtomicReference<String> wrong = new AtomicReference<>();
        AtomicReference<Boolean> pressed = new AtomicReference<>(false);
        BuilderSpec b = BuilderSpec.read(JsonParser.parseString(
                "{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\",\"name\":\"Fluffy Blue\"}").getAsJsonObject());
        BuilderSpec r = BuilderSpec.read(JsonParser.parseString(
                "{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\",\"name\":\"Fluffy Red\"}").getAsJsonObject());
        var who = BuilderMake.Who.of(p);
        BuilderMake.run(server, who, b, null, BuilderMake.protocolForTest(), false, false)
                .thenComposeAsync(m1 -> {
                    blue.set(m1.madeId());
                    java.util.concurrent.CompletableFuture<BuilderMake.Result> f = new java.util.concurrent.CompletableFuture<>();
                    server.execute(() -> BuilderMake.run(server, who, r, null, BuilderMake.protocolForTest(), false, false)
                            .whenComplete((m2, e) -> { if (e != null) f.completeExceptionally(e); else f.complete(m2); }));
                    return f;
                })
                .thenAcceptAsync(m2 -> server.execute(() -> {
                    red.set(m2.madeId());
                    blueWas.set(com.moddingfromamod.species.SpeciesRegistry.get(blue.get()).scale());
                    redWas.set(com.moddingfromamod.species.SpeciesRegistry.get(red.get()).scale());
                    Rewish.onForTest(true);
                    if (!Rewish.tryAnswer(p, "make fluffy bigger", WishSource.TYPED, WishRoute.Hint.AUTO)) {
                        wrong.set("'make fluffy bigger' with two Fluffys was not offered");
                        return;
                    }
                    if (!(p.containerMenu instanceof com.moddingfromamod.ui.MenuKit.PickMenu menu)) {
                        wrong.set("no offer page opened: " + p.containerMenu);
                        return;
                    }
                    var slots = menu.page().slots();
                    int slot = slots.entrySet().stream().filter(e -> e.getValue().equals("rewish:" + red.get()))
                            .map(java.util.Map.Entry::getKey).findFirst().orElse(-1);
                    boolean both = slots.containsValue("rewish:" + blue.get()) && slot >= 0;
                    boolean magician = slots.containsValue(Rewish.ELSE_ID);
                    String words = menu.page().tiles().toString();
                    if (!both || !magician || !words.contains("Fluffy Red: bigger")) {
                        wrong.set("the page should offer both Fluffys and the Magician: " + words);
                        return;
                    }
                    menu.clicked(slot, 0, net.minecraft.world.inventory.ClickType.PICKUP, p);
                    pressed.set(true);
                }));
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(wrong.get() == null, Component.literal(String.valueOf(wrong.get())));
            helper.assertTrue(pressed.get(), Component.literal("still making the cats"));
            // The press starts a make of its own, and its run stays WORKING in the process-wide marshal until that
            // make finishes. Passing before then leaked it into the next batch, where AgentContext's single-live-run
            // fallback made this child's run "the wish": modify_mob measured from him, gone, so found no mob at all
            // (ModifyMobGameTest.anUnmatchedTargetExplainsWhatIsNearby, "No mobs within 24 blocks of the child").
            helper.assertTrue(com.moddingfromamod.marshal.Marshal.global().working().stream()
                            .noneMatch(run -> p.getUUID().equals(run.playerId())),
                    Component.literal("still making the pressed Fluffy Red"));
            var rs = com.moddingfromamod.species.SpeciesRegistry.get(red.get());
            var bs = com.moddingfromamod.species.SpeciesRegistry.get(blue.get());
            helper.assertTrue(rs != null && rs.scale() > redWas.get() + 0.3,
                    Component.literal("the pressed Fluffy Red is not bigger: " + (rs == null ? "none" : rs.scale()) + ", was " + redWas.get()));
            helper.assertTrue(bs != null && Math.abs(bs.scale() - blueWas.get()) < 1e-6,
                    Component.literal("Fluffy Blue changed too: " + (bs == null ? "none" : bs.scale()) + ", was " + blueWas.get()));
        });
    }

    /**
     * slice 2 +, through the real typed path, on things that are the MAGICIAN's (Builder makes a wish has
     * since changed, an earlier issue's mark): "make the Mossy Hut icy" re-blocks his building where it stands, "can my Mossy Hut
     * have a pool" lays a pool round it, "take me to my Mossy Hut" takes him there, and "make Goldie gold" recolours his
     * Magician cat — each only what the Builder can do, the rest as the Magician made it.
     */
    @GameTest(template = "empty_test", batch = "rewishMagician", timeoutTicks = 16000)   // sets the acting child
    public static void reWishesChangeMagicianThingsOnlyWhereTheBuilderCan(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, "rewish_magician");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        MockPlayerNetwork.actingChildFor(helper, p);
        var server = helper.getLevel().getServer();
        String owner = p.getUUID().toString();
        long rise = BuilderMake.riseMillis;
        boolean card = BuilderMake.takeCard;
        BuilderMake.riseMillis = 0;
        BuilderMake.takeCard = false;
        String builderWas = System.getProperty(BuilderSettings.ENABLED_PROPERTY);
        System.setProperty(BuilderSettings.ENABLED_PROPERTY, "true");
        CrafterService.forgetForTest(p.getUUID());
        AtomicReference<String> hut = new AtomicReference<>();
        com.moddingfromamod.wishbuilder.BuilderMakeGameTest.takeDownWhenDone(helper,
                () -> hut.get() == null ? java.util.List.of() : java.util.List.of(hut.get()));
        MockPlayerNetwork.afterTest(helper, () -> {
            BuilderMake.riseMillis = rise;
            BuilderMake.takeCard = card;
            Rewish.onForTest(null);
            if (builderWas == null) {
                System.clearProperty(BuilderSettings.ENABLED_PROPERTY);
            } else {
                System.setProperty(BuilderSettings.ENABLED_PROPERTY, builderWas);
            }
        });
        BuilderSpec house = BuilderSpec.read(JsonParser.parseString("{\"builder\":1,\"kind\":\"building\","
                + "\"from\":\"preset:buildings/cozy_house\",\"size\":1,\"name\":\"Mossy Hut\"}").getAsJsonObject());
        BuilderSpec cat = BuilderSpec.read(JsonParser.parseString("{\"builder\":1,\"kind\":\"creature\","
                + "\"from\":\"preset:species/cat\",\"name\":\"Goldie\"}").getAsJsonObject());
        AtomicReference<String> goldie = new AtomicReference<>();
        AtomicReference<String> blocksBefore = new AtomicReference<>();
        AtomicReference<Integer> shapesBefore = new AtomicReference<>();
        AtomicReference<String> partsBefore = new AtomicReference<>();
        AtomicReference<String> wrong = new AtomicReference<>();
        AtomicReference<Boolean> asked = new AtomicReference<>(false);
        AtomicReference<Boolean> wentThere = new AtomicReference<>(false);
        AtomicReference<Boolean> goldieAsked = new AtomicReference<>(false);
        var who = BuilderMake.Who.of(p);
        BuilderMake.run(server, who, house, null, BuilderMake.protocolForTest(), false, false)
                .thenComposeAsync(h -> {
                    hut.set(h.madeId());
                    java.util.concurrent.CompletableFuture<BuilderMake.Result> f = new java.util.concurrent.CompletableFuture<>();
                    server.execute(() -> BuilderMake.run(server, who, cat, null, BuilderMake.protocolForTest(), false, false)
                            .whenComplete((c, e) -> { if (e != null) f.completeExceptionally(e); else f.complete(c); }));
                    return f;
                })
                .thenAcceptAsync(c -> server.execute(() -> {
                    goldie.set(c.madeId());
                    try {
                        BuilderSpecStore.touch(owner, java.util.List.of(hut.get(), goldie.get()), "gt-rewish-magician");
                    } catch (java.io.IOException e) {
                        wrong.set("could not mark them the Magician's: " + e);
                        return;
                    }
                    var plan = com.moddingfromamod.builder.BuildRegistry.get(hut.get());
                    shapesBefore.set(plan.shapes().size());
                    blocksBefore.set(blocks(hut.get()));
                    partsBefore.set(colours(goldie.get()));
                    Rewish.onForTest(true);
                    // Its size is the Magician's: never answered here.
                    if (Rewish.tryAnswer(p, "make the mossy hut bigger", WishSource.TYPED, WishRoute.Hint.AUTO)) {
                        wrong.set("a Magician building's size was changed by the Builder");
                        return;
                    }
                    // Go there first (a rebuild lands where it stands), then the changes.
                    p.teleportTo(p.getX() + 200, p.getY(), p.getZ());
                    if (!Rewish.tryAnswer(p, "take me to my mossy hut", WishSource.TYPED, WishRoute.Hint.AUTO)) {
                        wrong.set("'take me to my mossy hut' was not answered");
                        return;
                    }
                    BlockPos anchor = BlockPos.of(com.moddingfromamod.builder.BuildRegistry.anchorOf(hut.get()));
                    wentThere.set(p.blockPosition().distSqr(anchor) < 40 * 40);
                    if (!Rewish.tryAnswer(p, "make the mossy hut icy", WishSource.TYPED, WishRoute.Hint.AUTO)) {
                        wrong.set("a colour change of his Magician building was not answered at once");
                        return;
                    }
                    asked.set(true);
                }));
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(wrong.get() == null, Component.literal(String.valueOf(wrong.get())));
            helper.assertTrue(asked.get(), Component.literal("still making"));
            helper.assertTrue(wentThere.get(), Component.literal("'take me to my mossy hut' did not take him to it"));
            var plan = com.moddingfromamod.builder.BuildRegistry.get(hut.get());
            String now = blocks(hut.get());
            helper.assertTrue(!now.equals(blocksBefore.get()), Component.literal("the Mossy Hut was not re-blocked: still " + now));
            helper.assertValueEqual(plan.shapes().size(), shapesBefore.get(), Component.literal("its shapes (colours only)"));
            // One change at a time per child: Goldie's is asked once the hut's has finished.
            helper.assertTrue(!com.moddingfromamod.wishbuilder.BuilderMake.making(p.getUUID()),
                    Component.literal("the hut's change is still running"));
            if (!goldieAsked.get()) {
                goldieAsked.set(true);
                if (!Rewish.tryAnswer(p, "make goldie gold", WishSource.TYPED, WishRoute.Hint.AUTO)) {
                    wrong.set("a colour change of his Magician cat was not answered at once");
                }
            }
            helper.assertTrue(!colours(goldie.get()).equals(partsBefore.get()),
                    Component.literal("Goldie's colours did not change: " + colours(goldie.get())));
        });
    }

    private static String blocks(String id) {
        StringBuilder b = new StringBuilder();
        for (var e : com.moddingfromamod.builder.BuildRegistry.get(id).toJson().getAsJsonArray("shapes")) {
            var o = e.getAsJsonObject();
            b.append(o.has("block") ? o.get("block").getAsString() : "").append(';');
        }
        return b.toString();
    }

    private static String colours(String id) {
        StringBuilder b = new StringBuilder();
        for (var part : com.moddingfromamod.runtime.ModelRepository.sculptedParts(id)) {
            b.append(part.name()).append('=').append(Integer.toHexString(part.colour())).append(';');
        }
        return b.toString();
    }
}
