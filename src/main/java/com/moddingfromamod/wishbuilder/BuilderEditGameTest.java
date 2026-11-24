package com.moddingfromamod.wishbuilder;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.McpProtocol;
import com.moddingfromamod.agent.McpSessions;
import com.moddingfromamod.agent.WishAgent;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;

/**
 * step 4: the editor, through the REAL tools. A Builder cat is made, then changed by operators
 * ({@link BuilderMake#modifyRun}): the Builder's own edit mode presses the changes and runs them as an ordinary Builder
 * edit, so the cat's design under the SAME id is bigger and flies, his shelf keeps the new spec, and nothing second
 * is made. A change the Builder's edit cannot make is refused with nothing made.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class BuilderEditGameTest {

    private static volatile McpProtocol protocol;

    private static McpProtocol protocol() {
        if (protocol == null) {
            synchronized (BuilderEditGameTest.class) {
                if (protocol == null) {
                    protocol = new McpProtocol(WishAgent.buildTools(), new McpSessions());
                }
            }
        }
        return protocol;
    }

    private static <T> CompletableFuture<T> onServer(net.minecraft.server.MinecraftServer server,
                                                     java.util.function.Supplier<CompletableFuture<T>> start) {
        CompletableFuture<T> r = new CompletableFuture<>();
        server.execute(() -> start.get().whenComplete((res, err) -> {
            if (err != null) {
                r.completeExceptionally(err);
            } else {
                r.complete(res);
            }
        }));
        return r;
    }

    @GameTest(template = "empty_test", batch = "builderEditInPlace", timeoutTicks = 12000)   // sets the acting child
    public static void operatorsChangeABuilderCatInPlaceThroughTheRealTools(GameTestHelper helper) {
        var child = com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper, "gteditops");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        child.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        com.moddingfromamod.testing.MockPlayerNetwork.actingChildFor(helper, child);
        var who = BuilderMake.Who.of(child);
        var server = helper.getLevel().getServer();
        String owner = child.getUUID().toString();
        long rise = BuilderMake.riseMillis;
        BuilderMake.riseMillis = 0;
        com.moddingfromamod.testing.MockPlayerNetwork.afterTest(helper, () -> BuilderMake.riseMillis = rise);
        BuilderSpec cat = BuilderSpec.read(JsonParser.parseString(
                "{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\",\"size\":2,\"name\":\"Opsy\"}")
                .getAsJsonObject());
        AtomicReference<String> id = new AtomicReference<>();
        AtomicReference<Double> scaleBefore = new AtomicReference<>();
        AtomicReference<String> wrong = new AtomicReference<>();
        AtomicReference<String> refused = new AtomicReference<>();
        CompletableFuture<?> all = onServer(server, () -> BuilderMake.run(server, who, cat, null, protocol(), false, false))
                .thenComposeAsync(made -> {
                    if (made.outcome() != BuilderMake.Outcome.MADE && made.outcome() != BuilderMake.Outcome.PARTIAL) {
                        wrong.set("the cat was not made: " + made.detail());
                        return CompletableFuture.completedFuture(null);
                    }
                    id.set(made.madeId());
                    var sp = com.moddingfromamod.species.SpeciesRegistry.get(made.madeId());
                    scaleBefore.set(sp == null ? -1 : sp.scale());
                    return onServer(server, () -> BuilderMake.modifyRun(server, who, null, protocol(), made.madeId(),
                            List.of(BuilderEdit.Op.step("size", 1), BuilderEdit.Op.add("powers", "power:flies")), false, false))
                            .thenComposeAsync(edit -> {
                                if (edit.outcome() != BuilderMake.Outcome.MADE && edit.outcome() != BuilderMake.Outcome.PARTIAL) {
                                    wrong.set("the change did not stand: " + edit.detail() + " " + edit.calls());
                                } else if (!made.madeId().equals(edit.madeId())) {
                                    wrong.set("the change made '" + edit.madeId() + "', not the same '" + made.madeId() + "'");
                                }
                                // A change the Builder's edit cannot make: refused, nothing made.
                                return onServer(server, () -> BuilderMake.modifyRun(server, who, null, protocol(),
                                        made.madeId(), List.of(BuilderEdit.Op.add("powers", "power:lasers")), false, false))
                                        .thenAcceptAsync(no -> refused.set(no.outcome() + " " + no.detail()));
                            });
                });
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(all.isDone(), Component.literal("still making"));
            helper.assertFalse(all.isCompletedExceptionally(), Component.literal("a make threw"));
            helper.assertTrue(wrong.get() == null, Component.literal(String.valueOf(wrong.get())));
            var sp = com.moddingfromamod.species.SpeciesRegistry.get(id.get());
            helper.assertTrue(sp != null && sp.scale() > scaleBefore.get() + 0.3,
                    Component.literal("the cat under '" + id.get() + "' is scale " + (sp == null ? "none" : sp.scale())
                            + ", was " + scaleBefore.get()));
            helper.assertTrue(sp.powers().stream().anyMatch(p -> p.name().equalsIgnoreCase("flies")),
                    Component.literal("the changed cat does not fly: " + sp.powers()));
            BuilderSpecStore.Made kept = BuilderSpecStore.get(owner, id.get());
            helper.assertTrue(kept != null && kept.read().size() == 3 && kept.read().powers().contains("power:flies"),
                    Component.literal("his shelf does not keep the changed picks: " + (kept == null ? null : kept.spec())));
            helper.assertTrue(refused.get() != null && refused.get().startsWith("REFUSED"),
                    Component.literal("a change the Builder cannot make must be refused, nothing made: " + refused.get()));
        });
    }

    /**
     * step 6 /: a Builder creature made "Follows you" really follows him: the make's own modify_mob sets
     * follow on every copy (here two), through the real tools; "Stays put" sets a stay instead.
     */
    // Its own batch: the acting child is shared, and in the default batch it made every concurrent modify_mob look
    // for mobs round THIS child (12 ModifyMob/MobIdentity failures, full Gate B 2026-11-15).
    // "Taggy the Cat": a name "X the Y", the Apprentice's own kind, reads to modify_mob's scope guard as ONE; the
    // Builder's own follow call is 'group', and was refused for it until the guard learned the Builder's run text.
    @GameTest(template = "empty_test", batch = "builderFollows", timeoutTicks = 12000)
    public static void aFollowsYouCreatureFollowsAndAStaysPutOneStays(GameTestHelper helper) {
        var child = com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper, "gtfollows");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        child.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        com.moddingfromamod.testing.MockPlayerNetwork.actingChildFor(helper, child);
        var who = BuilderMake.Who.of(child);
        var server = helper.getLevel().getServer();
        long rise = BuilderMake.riseMillis;
        BuilderMake.riseMillis = 0;
        com.moddingfromamod.testing.MockPlayerNetwork.afterTest(helper, () -> BuilderMake.riseMillis = rise);
        BuilderSpec pet = BuilderSpec.read(JsonParser.parseString("{\"builder\":1,\"kind\":\"creature\","
                + "\"from\":\"preset:species/cat\",\"temper\":\"pet\",\"count\":2,\"name\":\"Taggy the Cat\"}").getAsJsonObject());
        BuilderSpec stay = BuilderSpec.read(JsonParser.parseString("{\"builder\":1,\"kind\":\"creature\","
                + "\"from\":\"preset:species/cat\",\"temper\":\"stays\",\"name\":\"Homey\"}").getAsJsonObject());
        AtomicReference<String> petId = new AtomicReference<>();
        AtomicReference<String> stayId = new AtomicReference<>();
        AtomicReference<String> wrong = new AtomicReference<>();
        CompletableFuture<?> all = onServer(server, () -> BuilderMake.run(server, who, pet, null, protocol(), false, false))
                .thenComposeAsync(made -> {
                    if (made.outcome() != BuilderMake.Outcome.MADE) {
                        wrong.set("the pet was not made: " + made.outcome() + " " + made.detail() + " " + made.calls());
                    }
                    petId.set(made.madeId());
                    return onServer(server, () -> BuilderMake.run(server, who, stay, null, protocol(), false, false));
                })
                .thenAcceptAsync(made -> {
                    if (made.outcome() != BuilderMake.Outcome.MADE && wrong.get() == null) {
                        wrong.set("the stay-put cat was not made: " + made.outcome() + " " + made.detail() + " " + made.calls());
                    }
                    stayId.set(made.madeId());
                });
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(all.isDone(), Component.literal("still making"));
            helper.assertFalse(all.isCompletedExceptionally(), Component.literal("a make threw"));
            helper.assertTrue(wrong.get() == null, Component.literal(String.valueOf(wrong.get())));
            List<net.minecraft.world.entity.Entity> pets = tagged(helper, petId.get());
            helper.assertTrue(pets.size() == 2, Component.literal("expected his 2 cats, found " + pets.size()));
            for (var e : pets) {
                helper.assertTrue(Boolean.TRUE.equals(com.moddingfromamod.mob.PetBehaviours.of(e).follow()),
                        Component.literal("a 'Follows you' cat does not follow: " + com.moddingfromamod.mob.PetBehaviours.of(e)));
            }
            List<net.minecraft.world.entity.Entity> stays = tagged(helper, stayId.get());
            helper.assertTrue(!stays.isEmpty() && com.moddingfromamod.mob.PetBehaviours.of(stays.get(0)).stay() != null
                            && com.moddingfromamod.mob.PetBehaviours.of(stays.get(0)).stay() >= 0,
                    Component.literal("a 'Stays put' cat has no stay: "
                            + (stays.isEmpty() ? "none found" : com.moddingfromamod.mob.PetBehaviours.of(stays.get(0)))));
        });
    }

    /**
     * step 5, where it RUNS: an idea to change his Builder cat, sent from the wish box exactly as offered
     * ({@code WishCompose.submit}, the box's real Submit), changes that cat in place through the Builder's edit. Sent
     * with other words, the same idea is his own typed wish and changes nothing here.
     */
    @GameTest(template = "empty_test", batch = "builderNextWish", timeoutTicks = 12000)   // sets the acting child
    public static void anIdeaSentAsItIsChangesHisCatInPlace(GameTestHelper helper) {
        var child = com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper, "gtnextwish");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        child.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        com.moddingfromamod.testing.MockPlayerNetwork.actingChildFor(helper, child);
        var who = BuilderMake.Who.of(child);
        var server = helper.getLevel().getServer();
        long rise = BuilderMake.riseMillis;
        boolean card = BuilderMake.takeCard;
        BuilderMake.riseMillis = 0;
        BuilderMake.takeCard = false;
        com.moddingfromamod.testing.MockPlayerNetwork.afterTest(helper, () -> {
            BuilderMake.riseMillis = rise;
            BuilderMake.takeCard = card;
            com.moddingfromamod.wish.NextWish.forget(child.getUUID());
        });
        BuilderSpec cat = BuilderSpec.read(JsonParser.parseString(
                "{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\",\"size\":2,\"name\":\"Ideo\"}")
                .getAsJsonObject());
        AtomicReference<String> id = new AtomicReference<>();
        AtomicReference<Double> scaleBefore = new AtomicReference<>();
        AtomicReference<String> wrong = new AtomicReference<>();
        onServer(server, () -> BuilderMake.run(server, who, cat, null, protocol(), false, false))
                .thenAcceptAsync(made -> {
                    if (made.outcome() != BuilderMake.Outcome.MADE && made.outcome() != BuilderMake.Outcome.PARTIAL) {
                        wrong.set("the cat was not made: " + made.detail());
                        return;
                    }
                    var sp = com.moddingfromamod.species.SpeciesRegistry.get(made.madeId());
                    scaleBefore.set(sp == null ? -1 : sp.scale());
                    server.execute(() -> {
                        var idea = new com.moddingfromamod.wish.NextWish.Idea("Make Ideo bigger",
                                com.moddingfromamod.wish.NextWish.Rung.CHANGE, "", made.madeId(),
                                List.of(BuilderEdit.Op.step("size", 1)));
                        // Edited first: his own words, not ours, so nothing here handles it.
                        com.moddingfromamod.wish.NextWish.offerForTest(child.getUUID(), idea);
                        if (com.moddingfromamod.wish.NextWish.sent(child, "Make Ideo bigger and purple")) {
                            wrong.set("an edited idea was granted as the idea, not as his own wish");
                        }
                        com.moddingfromamod.wish.NextWish.offerForTest(child.getUUID(), idea);
                        com.moddingfromamod.wish.WishCompose.submit(child, "make ideo bigger!");
                        id.set(made.madeId());
                    });
                });
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(wrong.get() == null, Component.literal(String.valueOf(wrong.get())));
            helper.assertTrue(id.get() != null, Component.literal("still making the cat"));
            var sp = com.moddingfromamod.species.SpeciesRegistry.get(id.get());
            helper.assertTrue(sp != null && sp.scale() > scaleBefore.get() + 0.3,
                    Component.literal("the idea sent as it is did not make '" + id.get() + "' bigger: scale "
                            + (sp == null ? "none" : sp.scale()) + ", was " + scaleBefore.get()));
            BuilderSpecStore.Made kept = BuilderSpecStore.get(child.getUUID().toString(), id.get());
            helper.assertTrue(kept != null && kept.read().size() == 3,
                    Component.literal("his shelf does not keep the change: " + (kept == null ? null : kept.spec())));
        });
    }

    /**
     * "Change it in the Builder" reaches every kind's own pages. Through the real tools: a Builder sword's
     * POWER and a Builder treehouse's OUTSIDE are each changed on their own page by the editor
     * ({@link BuilderMake#modifyRun}), in place under the same id, with no second item and the building standing.
     */
    @GameTest(template = "empty_test", batch = "builderEditPages", timeoutTicks = 12000)   // sets the acting child
    public static void aSwordsPowerAndAHousesOutsideChangeOnTheirOwnPages(GameTestHelper helper) {
        var child = com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper, "gteditpages");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        child.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        com.moddingfromamod.testing.MockPlayerNetwork.actingChildFor(helper, child);
        var who = BuilderMake.Who.of(child);
        var server = helper.getLevel().getServer();
        String owner = child.getUUID().toString();
        long rise = BuilderMake.riseMillis;
        BuilderMake.riseMillis = 0;
        com.moddingfromamod.testing.MockPlayerNetwork.afterTest(helper, () -> BuilderMake.riseMillis = rise);
        java.util.Map<String, String> ids = new java.util.concurrent.ConcurrentHashMap<>();
        BuilderMakeGameTest.takeDownWhenDone(helper, () -> ids.containsKey("building") ? List.of(ids.get("building")) : List.of());
        String[][] cases = {
                {"{\"builder\":1,\"kind\":\"item\",\"from\":\"preset:items/lightning_sword_lightning\",\"name\":\"Page Zap\"}",
                        "ITEM_POWER"},
                {"{\"builder\":1,\"kind\":\"building\",\"from\":\"preset:buildings/secret_treehouse\",\"size\":1,\"name\":\"Page Fort\"}",
                        "BUILD_OUTSIDE"}};
        List<String> wrong = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.Map<String, String> asked = new java.util.concurrent.ConcurrentHashMap<>();
        AtomicReference<CompletableFuture<?>> chain = new AtomicReference<>(CompletableFuture.completedFuture(null));
        for (String[] c : cases) {
            BuilderSpec spec = BuilderSpec.read(JsonParser.parseString(c[0]).getAsJsonObject());
            String kind = spec.kind().id();
            chain.set(chain.get()
                    .thenComposeAsync(x -> onServer(server, () -> BuilderMake.run(server, who, spec, null, protocol(), false, false)))
                    .thenComposeAsync(made -> {
                        if (made.outcome() != BuilderMake.Outcome.MADE && made.outcome() != BuilderMake.Outcome.PARTIAL) {
                            wrong.add(kind + ": not made: " + made.detail());
                            return CompletableFuture.completedFuture(null);
                        }
                        ids.put(kind, made.madeId());
                        // Another tile on the page this kind's edit walks to, as a child would press it.
                        BuilderFlow f = BuilderFlow.editing(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED, made.madeId(),
                                BuilderSpecStore.get(owner, made.madeId()).read());
                        for (int g = 0; g < 8 && !f.step().name().equals(c[1]) && f.step() != BuilderFlow.Step.TUNE; g++) {
                            f.press(BuilderFlow.multiPick(f.step()) ? com.moddingfromamod.ui.PickPage.NEXT_ID : f.keepPress());
                        }
                        String other = f.page().tiles().stream().map(com.moddingfromamod.ui.PickPage.Tile::id)
                                .filter(i -> !i.startsWith("@") && !i.equals(f.keepPress()) && !i.endsWith(":" + BuilderBuildings.NOTHING))
                                .filter(i -> f.page().tiles().stream().noneMatch(t -> t.id().equals(i) && t.selected()))
                                .findFirst().orElse(null);
                        if (other == null) {
                            wrong.add(kind + ": its '" + c[1] + "' page offers nothing else (at " + f.step() + ")");
                            return CompletableFuture.completedFuture(null);
                        }
                        asked.put(kind, other);
                        return onServer(server, () -> BuilderMake.modifyRun(server, who, null, protocol(), made.madeId(),
                                List.of(BuilderEdit.Op.set("page", other)), false, false))
                                .thenAcceptAsync(edit -> {
                                    if (edit.outcome() != BuilderMake.Outcome.MADE && edit.outcome() != BuilderMake.Outcome.PARTIAL) {
                                        wrong.add(kind + ": the change to " + other + " did not stand: " + edit.detail() + " " + edit.calls());
                                    } else if (!made.madeId().equals(edit.madeId())) {
                                        wrong.add(kind + ": the change made '" + edit.madeId() + "', not the same '" + made.madeId() + "'");
                                    }
                                });
                    }));
        }
        CompletableFuture<?> all = chain.get();
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(all.isDone(), Component.literal("still making"));
            helper.assertFalse(all.isCompletedExceptionally(), Component.literal("a make threw"));
            helper.assertTrue(wrong.isEmpty(), Component.literal(String.join("\n", wrong)));
            int zaps = com.moddingfromamod.item.WishItemCatalogue.resolve("Page Zap").size();
            helper.assertTrue(zaps == 1, Component.literal(zaps + " items called Page Zap: a change must not make a second"));
            String power = BuilderSpecStore.get(owner, ids.get("item")).read().opt("power");
            helper.assertTrue(asked.get("item").endsWith(":" + power),
                    Component.literal("his shelf keeps power '" + power + "', not the " + asked.get("item") + " he pressed"));
            helper.assertTrue(com.moddingfromamod.builder.BuildRegistry.standing(ids.get("building")),
                    Component.literal("the treehouse is not standing after its change"));
            String outside = BuilderSpecStore.get(owner, ids.get("building")).read().opt("outside");
            helper.assertTrue(outside.contains(Catalogue.bare(asked.get("building"))),
                    Component.literal("his shelf keeps outside '" + outside + "', not the " + asked.get("building") + " he pressed"));
        });
    }

    /**
     * slice 1, through the real tools: a creature that is the MAGICIAN's (here a Builder cat a wish has since changed,
     * an earlier issue's mark, so the Builder no longer re-lowers it) is changed by the Builder's own pages in its Magician mode:
     * bigger and flying. Its design under the SAME id is bigger and flies, its parts are the same parts, and every power
     * it had is still there. His thing page offers "✦ Change it ✪" for it.
     */
    @GameTest(template = "empty_test", batch = "builderMagicianCreature", timeoutTicks = 12000)   // sets the acting child
    public static void aMagicianCreatureIsChangedOnlyWhereTheBuilderCan(GameTestHelper helper) {
        var child = com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper, "gtmagicianedit");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        child.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        com.moddingfromamod.testing.MockPlayerNetwork.actingChildFor(helper, child);
        var who = BuilderMake.Who.of(child);
        var server = helper.getLevel().getServer();
        String owner = child.getUUID().toString();
        long rise = BuilderMake.riseMillis;
        BuilderMake.riseMillis = 0;
        com.moddingfromamod.testing.MockPlayerNetwork.afterTest(helper, () -> BuilderMake.riseMillis = rise);
        BuilderSpec cat = BuilderSpec.read(JsonParser.parseString("{\"builder\":1,\"kind\":\"creature\","
                + "\"from\":\"preset:species/cat\",\"powers\":[\"power:glowing\"],\"name\":\"Wizzy\"}").getAsJsonObject());
        AtomicReference<String> id = new AtomicReference<>();
        AtomicReference<String> wrong = new AtomicReference<>();
        AtomicReference<com.moddingfromamod.species.Species> before = new AtomicReference<>();
        AtomicReference<String> partsBefore = new AtomicReference<>();
        AtomicReference<String> verbs = new AtomicReference<>();
        CompletableFuture<?> all = onServer(server, () -> BuilderMake.run(server, who, cat, null, protocol(), false, false))
                .thenComposeAsync(made -> {
                    if (made.outcome() != BuilderMake.Outcome.MADE && made.outcome() != BuilderMake.Outcome.PARTIAL) {
                        wrong.set("the cat was not made: " + made.detail());
                        return CompletableFuture.completedFuture(null);
                    }
                    id.set(made.madeId());
                    try {
                        BuilderSpecStore.touch(owner, List.of(made.madeId()), "gt-magician-wish");   // a wish changed it
                    } catch (java.io.IOException e) {
                        wrong.set("could not mark it as the Magician's: " + e);
                        return CompletableFuture.completedFuture(null);
                    }
                    before.set(com.moddingfromamod.species.SpeciesRegistry.get(made.madeId()));
                    partsBefore.set(geometryParts(made.madeId()));
                    if (BuilderMenus.magicianCreatureId(child.getUUID(), made.madeId()) == null) {
                        wrong.set("a cat a wish changed is not the Builder's to change as a Magician creature");
                        return CompletableFuture.completedFuture(null);
                    }
                    var cell = new com.moddingfromamod.marshal.WatchPanel.Cell(0, com.moddingfromamod.marshal.WatchPanel.Family.CREATION,
                            com.moddingfromamod.marshal.WatchPanel.Action.CARD, "Wizzy", made.madeId(), "", "", "", List.of());
                    verbs.set(com.moddingfromamod.wish.ThingPage.describe(com.moddingfromamod.wish.ThingPage.verbs(cell, child.getUUID())));
                    BuilderFlow f = BuilderFlow.editingMagician(BuilderAllowlist.REVIEWED, LibraryPresets.SHIPPED,
                            BuilderMenus.magicianThing(made.madeId()));
                    f.press("power:flies");
                    f.press(com.moddingfromamod.ui.PickPage.NEXT_ID);
                    List<BuilderLowering.Call> calls = f.magicianCalls(java.util.Map.of("size", "3"));
                    return onServer(server, () -> BuilderMake.magicianRun(server, who, f.magician(), calls, protocol(), false, false))
                            .thenAcceptAsync(r -> {
                                if (r.outcome() != BuilderMake.Outcome.MADE && r.outcome() != BuilderMake.Outcome.PARTIAL) {
                                    wrong.set("the change did not stand: " + r.detail() + " " + r.calls());
                                }
                            });
                });
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(all.isDone(), Component.literal("still making"));
            helper.assertFalse(all.isCompletedExceptionally(), Component.literal("a make threw"));
            helper.assertTrue(wrong.get() == null, Component.literal(String.valueOf(wrong.get())));
            var sp = com.moddingfromamod.species.SpeciesRegistry.get(id.get());
            helper.assertTrue(sp != null && Math.abs(sp.scale() - before.get().scale() * 1.6) < 1e-6,
                    Component.literal("scale " + (sp == null ? "none" : sp.scale()) + ", was " + before.get().scale()));
            helper.assertTrue(sp.powers().contains(com.moddingfromamod.species.Species.Power.FLIES),
                    Component.literal("it does not fly: " + sp.powers()));
            helper.assertTrue(sp.powers().containsAll(before.get().powers()),
                    Component.literal("a power it had was lost: was " + before.get().powers() + ", now " + sp.powers()));
            helper.assertValueEqual(geometryParts(id.get()), partsBefore.get(), Component.literal("its parts"));
            helper.assertTrue(verbs.get().contains(MagicianCreature.VERB_LABEL),
                    Component.literal("his thing page does not offer the Builder for it: " + verbs.get()));
            BuilderSpecStore.Made kept = BuilderSpecStore.get(owner, id.get());
            helper.assertTrue(kept != null && kept.magicianTouched(), Component.literal("it is still the Magician's, not a Builder make"));
            // Never a creature that is not HIS: one owned by nobody is the library's, everyone's (ForkPolicy).
            for (var other : com.moddingfromamod.species.SpeciesRegistry.all()) {
                if (other.ownerId() == null || !other.ownerId().equals(owner)) {
                    helper.assertTrue(BuilderMenus.magicianCreatureId(child.getUUID(), other.id()) == null,
                            Component.literal("'" + other.id() + "' (owned by " + other.ownerId() + ") is not his to change in place"));
                }
            }
        });
    }

    /** The names of a creature's model parts, in order: what a change that keeps its parts must leave alone. */
    private static String geometryParts(String id) {
        try {
            java.nio.file.Path g = com.moddingfromamod.runtime.ModelRepository.readableGeometryFile(id);
            if (g == null || !java.nio.file.Files.exists(g)) {
                return "(no geometry)";
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"name\"\\s*:\\s*\"([^\"]*)\"")
                    .matcher(java.nio.file.Files.readString(g));
            List<String> names = new java.util.ArrayList<>();
            while (m.find()) {
                names.add(m.group(1));
            }
            return names.toString();
        } catch (java.io.IOException e) {
            return "(unreadable: " + e + ")";
        }
    }

    private static List<net.minecraft.world.entity.Entity> tagged(GameTestHelper helper, String id) {
        List<net.minecraft.world.entity.Entity> out = new java.util.ArrayList<>();
        if (id == null) {
            return out;
        }
        for (var e : helper.getLevel().getAllEntities()) {
            if (e instanceof net.minecraft.world.entity.Mob && e.isAlive() && e.entityTags().contains("wishmob_" + id)) {
                out.add(e);
            }
        }
        return out;
    }
}
