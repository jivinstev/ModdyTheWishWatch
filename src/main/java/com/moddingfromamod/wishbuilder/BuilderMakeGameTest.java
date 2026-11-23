package com.moddingfromamod.wishbuilder;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.McpProtocol;
import com.moddingfromamod.agent.McpSessions;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.ToolRegistry;
import com.moddingfromamod.agent.WishAgent;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.wish.WishBook;
import com.moddingfromamod.wish.WishStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;

/**
 * <b>A Builder make runs through the REAL tools</b> (B0a) — a rule tested only where it is
 * defined is not tested where it runs.
 *
 * <ol>
 *   <li>Every argument the lowering writes is one the real tool's schema declares (principle 6: the
 *       Builder's options come from the tools' own schemas).</li>
 *   <li>One make per kind, end to end through {@link BuilderMake#run} and the real
 *       {@code McpProtocol} dispatch: it settles DONE in the wish book and no tool before the photo
 *       refused it.</li>
 * </ol>
 * Its own batch: the makes bind runs in the global Marshal, as a wish does.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class BuilderMakeGameTest {

    private static final String BATCH = "builderMake";

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    private BuilderMakeGameTest() {
    }

    @GameTest(template = "empty_test")
    public static void everyLoweredArgumentIsOneTheRealToolDeclares(GameTestHelper helper) {
        ToolRegistry tools = WishAgent.buildTools();
        List<String> wrong = new ArrayList<>();
        for (String spec : BuilderExamples.ONE_PER_KIND) {
            var calls = BuilderLowering.lower(BuilderSpec.read(JsonParser.parseString(spec).getAsJsonObject()),
                    "gt_schema", LibraryPresets.SHIPPED);
            for (BuilderLowering.Call call : calls) {
                McpTool tool = tools.byName(call.tool());
                if (tool == null) {
                    wrong.add(call.tool() + " is not a tool");
                    continue;
                }
                JsonObject props = tool.inputSchema().getAsJsonObject("properties");
                for (String k : call.args().keySet()) {
                    if (props == null || !props.has(k)) {
                        wrong.add(call.tool() + " has no '" + k + "' (" + spec + ")");
                    }
                }
            }
        }
        helper.assertTrue(wrong.isEmpty(), Component.literal(String.join("\n", wrong)));
        helper.succeed();
    }

    /**
     * Every kind, one after another (one make per child at a time), for a stand-in child standing in
     * the test plot — the tools that place a thing in front of him refuse when nobody is there.
     * Each make must settle DONE with no refusal before the photo; every verdict is logged.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 12000)
    public static void everyKindIsMadeThroughTheRealTools(GameTestHelper helper) {
        var child = com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper, "gtbuilder");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        child.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        child.setYRot(0);     // facing south (+z), looking a little down: the spot a wish locks is in front of him
        child.setXRot(25);
        com.moddingfromamod.testing.MockPlayerNetwork.actingChildFor(helper, child);
        // The real cows the creature make brings go when the test does: left standing, they were within 48
        // blocks of the next batch's child on the same ground, and BuilderFarmAnimalsGameTest counted one as his.
        java.util.Set<java.util.UUID> cowsBefore = new java.util.HashSet<>();
        net.minecraft.world.phys.AABB round = new net.minecraft.world.phys.AABB(at).inflate(128);
        helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.animal.Cow.class, round)
                .forEach(c -> cowsBefore.add(c.getUUID()));
        com.moddingfromamod.testing.MockPlayerNetwork.afterTest(helper, () -> helper.getLevel()
                .getEntitiesOfClass(net.minecraft.world.entity.animal.Cow.class, round, c -> !cowsBefore.contains(c.getUUID()))
                .forEach(net.minecraft.world.entity.Entity::discard));
        var who = BuilderMake.Who.of(child);
        List<String> verdicts = java.util.Collections.synchronizedList(new ArrayList<>());
        List<String> wrong = java.util.Collections.synchronizedList(new ArrayList<>());
        List<String> lookFor = java.util.Collections.synchronizedList(new ArrayList<>());
        // WHAT HE GOT, per make (a Builder lightning sword settled DONE and never reached his hand,
        // and this test, which asked only for DONE, was green). Each is read in the poll, off the world and his bag.
        java.util.Map<String, java.util.function.Supplier<String>> landed = new java.util.concurrent.ConcurrentHashMap<>();
        AtomicReference<CompletableFuture<?>> chain = new AtomicReference<>(CompletableFuture.completedFuture(null));
        var server = helper.getLevel().getServer();
        // D3: the FIRST make keeps the pillar's real 3 s rise (this is the test that owns it); the other
        // fifteen skip it — every one of them asserts the spot was LOCKED, which happens with or without the rise,
        // and sleeping through fifteen rises was ~45 s of this test's 55.
        long rise = BuilderMake.riseMillis;
        com.moddingfromamod.testing.MockPlayerNetwork.afterTest(helper, () -> BuilderMake.riseMillis = rise);
        boolean[] first = {true};
        List<String> buildings = java.util.Collections.synchronizedList(new ArrayList<>());
        takeDownWhenDone(helper, buildings);
        final long localBefore = com.moddingfromamod.marshal.LocalOnly.refusedSoFar();
        for (String json : BuilderExamples.ONE_PER_KIND) {
            BuilderSpec spec = BuilderSpec.read(JsonParser.parseString(json).getAsJsonObject());
            CompletableFuture<?> next = chain.get().thenComposeAsync(x -> {
                CompletableFuture<BuilderMake.Result> r = new CompletableFuture<>();
                if (!first[0]) {
                    BuilderMake.riseMillis = 0;
                }
                first[0] = false;
                server.execute(() -> BuilderMake.run(server, who, spec, null, protocol(), false, false)
                        .whenComplete((res, err) -> {
                            if (err != null) {
                                r.completeExceptionally(err);
                            } else {
                                r.complete(res);
                            }
                        }));
                return r.thenAcceptAsync(res -> {
                    var wish = res.wishId() == null ? null : WishBook.get(server).byId(res.wishId()).orElse(null);
                    String v = spec.kind().id() + " → " + res.outcome() + " " + res.calls() + " " + res.detail()
                            + " book=" + (wish == null ? "none" : wish.status());
                    LOGGER.info("builder make GT: {}", v);
                    verdicts.add(v);
                    if (res.outcome() != BuilderMake.Outcome.MADE && res.outcome() != BuilderMake.Outcome.PARTIAL
                            || wish == null || wish.status() != WishStatus.DONE) {
                        wrong.add(v);
                    }
                    // LOOK at the world, not the verdict — a schemed building stands in its scheme's blocks.
                    // Judged in the poll below: a build is PLACED over ticks after build_structure returns.
                    Schemes.Scheme scheme = Schemes.byId(spec.scheme());
                    if (spec.kind() == BuilderSpec.Kind.BUILDING && scheme != null) {
                        lookFor.add(scheme.mainBlock());
                    }
                    if (spec.kind() == BuilderSpec.Kind.BUILDING && res.madeId() != null) {
                        buildings.add(res.madeId());
                    }
                    if (res.outcome() == BuilderMake.Outcome.MADE || res.outcome() == BuilderMake.Outcome.PARTIAL) {
                        landed.put(spec.kind().id() + " " + json.hashCode(), landedCheck(helper, child, spec, res.madeId()));
                        // LOCKED like a wish, and it went THERE — never wherever a tool defaults to.
                        BlockPos aimed = BuilderMake.aimedAt(res.wishId());
                        if (placed(spec.kind())) {
                            if (aimed == null) {
                                wrong.add(spec.kind().id() + ": no spot was locked for the make (no pillar, no aim)");
                            } else if (aimed.getZ() <= child.getBlockZ()) {
                                wrong.add(spec.kind().id() + ": locked at " + aimed.toShortString() + ", not in front of him at "
                                        + child.blockPosition().toShortString());
                            } else {
                                landed.put(spec.kind().id() + " at " + json.hashCode(), whereCheck(helper, spec, res.madeId(), aimed));
                            }
                        }
                    }
                });
            });
            chain.set(next);
        }
        CompletableFuture<?> all = chain.get();
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            //, LOCAL ONLY: not one model or web call was asked during any make (marshal/LocalOnly refuses and
            // counts each). Before, every creature make asked Muse Ember who the creature was and could start a web
            // researcher on its name. Any refusal is a Builder make reaching for a model, wherever it came from.
            helper.assertTrue(com.moddingfromamod.marshal.LocalOnly.refusedSoFar() == localBefore,
                    Component.literal((com.moddingfromamod.marshal.LocalOnly.refusedSoFar() - localBefore)
                            + " model or web call(s) asked during a Builder make (see the 'local only: refused' lines)"));
            // Every landed check is asked on EVERY poll, before the "all made" gate below: a creature's where-check
            // latches the moment it is seen at its spot (whereCheck), so it must be asked while the rest are still
            // being made, not only at the end. A copy, since a make that finishes adds to the map.
            List<String> missing = new ArrayList<>();
            for (var e : List.copyOf(landed.entrySet())) {
                String why = e.getValue().get();
                if (why != null) {
                    missing.add(e.getKey().split(" ")[0] + ": " + why);
                }
            }
            helper.assertTrue(all.isDone(), Component.literal("made " + verdicts.size() + " of "
                    + BuilderExamples.ONE_PER_KIND.size()));
            helper.assertFalse(all.isCompletedExceptionally(), Component.literal("a make threw: " + verdicts));
            // Evaluated on EVERY poll — a verdict cached on the first poll let a red run read green.
            helper.assertTrue(wrong.isEmpty(), Component.literal(String.join("\n", wrong)));
            helper.assertTrue(missing.isEmpty(), Component.literal("made, but did not land: " + missing));
            for (String block : List.copyOf(lookFor)) {
                int found = countBlocks(helper, child.blockPosition(), block);
                helper.assertTrue(found >= 20, Component.literal("the schemed building has " + found + " " + block
                        + " standing around the child (its build may still be placing)"));
                LOGGER.info("builder make GT: building — {} block(s) of {} stand in the world", found, block);
            }
        });
    }

    /**
     * <b>"Change one thing", through the real tools.</b> A creature, an item and a building are
     * made, then each is EDITED from the spec kept on his shelf — one pick changed — and the world must show
     * the SAME thing changed, never a second one: the creature's design under its own id at the new size, one
     * item of that name in his catalogue, the building still standing at its anchor. And the kept row is the
     * new spec, read back off the shelf file.
     */
    // Its OWN batch: the stand-in child (MockPlayerNetwork.actingChildFor) is one global, and two tests in one
    // batch that each set it restore each other's — the second restored the first's RETIRED child, so every
    // later test that finds "the child's" mobs looked around a player who was gone (measured 2026-11-09:
    // MobIdentityGameTest and ModifyMobReachesTheRigGameTest red only in runs that held both tests).
    @GameTest(template = "empty_test", batch = "builderEdit", timeoutTicks = 12000)
    public static void anEditChangesTheThingItMadeAndMakesNoSecond(GameTestHelper helper) {
        var child = com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper, "gtbuilderedit");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        child.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        com.moddingfromamod.testing.MockPlayerNetwork.actingChildFor(helper, child);
        var who = BuilderMake.Who.of(child);
        var server = helper.getLevel().getServer();
        String owner = child.getUUID().toString();
        // D3: an edit is about the thing changed in place; the pillar's rise is the test above's.
        long rise = BuilderMake.riseMillis;
        BuilderMake.riseMillis = 0;
        com.moddingfromamod.testing.MockPlayerNetwork.afterTest(helper, () -> BuilderMake.riseMillis = rise);
        String[][] cases = {
                {"{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\",\"size\":2,\"name\":\"Edity\"}",
                        "{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\",\"size\":4,\"name\":\"Edity\"}"},
                {"{\"builder\":1,\"kind\":\"item\",\"from\":\"preset:items/lightning_sword_lightning\",\"name\":\"Edit Zap\"}",
                        "{\"builder\":1,\"kind\":\"item\",\"from\":\"preset:items/lightning_sword_lightning\",\"name\":\"Edit Zap\",\"scheme\":\"candy\"}"},
                {"{\"builder\":1,\"kind\":\"building\",\"from\":\"preset:buildings/secret_treehouse\",\"name\":\"Edit Fort\"}",
                        "{\"builder\":1,\"kind\":\"building\",\"from\":\"preset:buildings/secret_treehouse\",\"name\":\"Edit Fort\",\"scheme\":\"candy\"}"}};
        List<String> wrong = java.util.Collections.synchronizedList(new ArrayList<>());
        List<String> verdicts = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.Map<String, Double> scaleBefore = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.Map<String, String> ids = new java.util.concurrent.ConcurrentHashMap<>();
        takeDownWhenDone(helper, () -> ids.containsKey("building") ? List.of(ids.get("building")) : List.of());
        AtomicReference<CompletableFuture<?>> chain = new AtomicReference<>(CompletableFuture.completedFuture(null));
        for (String[] c : cases) {
            BuilderSpec first = BuilderSpec.read(JsonParser.parseString(c[0]).getAsJsonObject());
            BuilderSpec second = BuilderSpec.read(JsonParser.parseString(c[1]).getAsJsonObject());
            String kind = first.kind().id();
            chain.set(chain.get()
                    .thenComposeAsync(x -> once(server, who, first, null))
                    .thenComposeAsync(made -> {
                        verdicts.add(kind + " make → " + made.outcome() + " " + made.calls() + " " + made.detail());
                        if (made.outcome() != BuilderMake.Outcome.MADE && made.outcome() != BuilderMake.Outcome.PARTIAL) {
                            wrong.add(kind + ": the first make did not stand: " + made.detail());
                            return CompletableFuture.completedFuture(null);
                        }
                        ids.put(kind, made.madeId());
                        if (first.kind() == BuilderSpec.Kind.CREATURE) {
                            var sp = com.moddingfromamod.species.SpeciesRegistry.get(made.madeId());
                            scaleBefore.put(kind, sp == null ? -1 : sp.scale());
                        }
                        return once(server, who, second, made.madeId()).thenAcceptAsync(edit -> {
                            verdicts.add(kind + " edit → " + edit.outcome() + " " + edit.calls() + " " + edit.detail());
                            if (edit.outcome() != BuilderMake.Outcome.MADE && edit.outcome() != BuilderMake.Outcome.PARTIAL) {
                                wrong.add(kind + ": the edit did not stand: " + edit.calls() + " " + edit.detail());
                            } else if (!edit.madeId().equals(made.madeId())) {
                                wrong.add(kind + ": the edit made '" + edit.madeId() + "', not the same '" + made.madeId() + "'");
                            }
                        });
                    }));
        }
        CompletableFuture<?> all = chain.get();
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(all.isDone(), Component.literal("still making: " + verdicts));
            helper.assertFalse(all.isCompletedExceptionally(), Component.literal("a make threw: " + verdicts));
            helper.assertTrue(wrong.isEmpty(), Component.literal(String.join("\n", wrong) + "\n" + verdicts));
            String cat = ids.get("creature");
            var sp = com.moddingfromamod.species.SpeciesRegistry.get(cat);
            helper.assertTrue(sp != null && sp.scale() > scaleBefore.get("creature") + 0.3,
                    Component.literal("the cat's design under '" + cat + "' is scale " + (sp == null ? "none" : sp.scale())
                            + ", was " + scaleBefore.get("creature")));
            int zaps = com.moddingfromamod.item.WishItemCatalogue.resolve("Edit Zap").size();
            helper.assertTrue(zaps == 1, Component.literal(zaps + " items called Edit Zap: an edit must not make a second"));
            helper.assertTrue(com.moddingfromamod.builder.BuildRegistry.standing(ids.get("building")),
                    Component.literal("the fort '" + ids.get("building") + "' is not standing after its edit"));
            for (String kind : ids.keySet()) {
                BuilderSpecStore.Made kept = BuilderSpecStore.get(owner, ids.get(kind));
                helper.assertTrue(kept != null, Component.literal("no kept spec for the " + kind));
                BuilderSpec now = kept.read();
                helper.assertTrue(now.size() == 4 || !now.scheme().isEmpty(),
                        Component.literal("the kept " + kind + " is the FIRST spec, not the edit: " + now.toJson()));
            }
            LOGGER.info("builder edit GT: {}", verdicts);
        });
    }

    /**
     * The car is a VEHICLE: made through the real tools as a ride AND as a creature,
     * its species lives the vehicle's life — parked until driven, rideable — the same as the truck. Before, the
     * lowering dropped every body's {@code behaves_like}, so no Builder-made vehicle had that life at all, and the car
     * had none in its data. What the life DOES (stays parked, a flyer comes down, it can be driven) is
     * {@code ArchetypeLifeGameTest.aVehicleStaysParkedAndAFlyingOneComesDown}; this proves a made car gets it.
     */
    @GameTest(template = "empty_test", batch = "builderVehicle", timeoutTicks = 12000)
    public static void aMadeCarIsAVehicleParkedUntilDriven(GameTestHelper helper) {
        var child = com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper, "gtbuildercar");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        child.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        com.moddingfromamod.testing.MockPlayerNetwork.actingChildFor(helper, child);
        var who = BuilderMake.Who.of(child);
        var server = helper.getLevel().getServer();
        List<String> wrong = java.util.Collections.synchronizedList(new ArrayList<>());
        List<String> verdicts = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicReference<CompletableFuture<?>> chain = new AtomicReference<>(CompletableFuture.completedFuture(null));
        for (String[] c : new String[][] {{"ride", "bodies/car"}, {"creature", "bodies/car"}, {"ride", "bodies/truck"}}) {
            BuilderSpec spec = BuilderSpec.read(JsonParser.parseString("{\"builder\":1,\"kind\":\"" + c[0]
                    + "\",\"from\":\"preset:" + c[1] + "\",\"name\":\"Parky " + c[0] + "\"}").getAsJsonObject());
            chain.set(chain.get().thenComposeAsync(x -> once(server, who, spec, null)).thenAcceptAsync(made -> {
                verdicts.add(c[0] + " " + c[1] + " → " + made.outcome() + " " + made.detail());
                if (made.outcome() != BuilderMake.Outcome.MADE && made.outcome() != BuilderMake.Outcome.PARTIAL) {
                    wrong.add(c[0] + " " + c[1] + ": not made: " + made.detail());
                    return;
                }
                var sp = com.moddingfromamod.species.SpeciesRegistry.get(made.madeId());
                if (sp == null || sp.archetype() != com.moddingfromamod.species.Archetype.VEHICLE) {
                    wrong.add(c[0] + " " + c[1] + ": its life is " + (sp == null ? "no species" : sp.archetype()) + ", not a vehicle's");
                } else if (!sp.has(com.moddingfromamod.species.Species.Power.RIDEABLE)) {
                    wrong.add(c[0] + " " + c[1] + ": a vehicle nobody can drive");
                }
            }));
        }
        CompletableFuture<?> all = chain.get();
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(all.isDone(), Component.literal("still making: " + verdicts));
            helper.assertFalse(all.isCompletedExceptionally(), Component.literal("a make threw: " + verdicts));
            helper.assertTrue(wrong.isEmpty(), Component.literal(String.join("\n", wrong) + "\n" + verdicts));
            LOGGER.info("builder car GT: {}", verdicts);
        });
    }

    /** One make (or, with {@code editOf}, an edit) on the server thread, its result on any. */
    /**
     * The Apprentice's free change, through the real tools: an Apprentice make's thing is free to
     * change once; a second Apprentice make moves it to the new thing; the free change is spent by using it; and any
     * other wish ends it.
     */
    @GameTest(template = "empty_test", batch = "builderFreeEdit", timeoutTicks = 12000)
    public static void theLastApprenticeMakeIsFreeToChangeOnceUntilTheNextWish(GameTestHelper helper) {
        var child = com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper, "gtfreeedit");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        child.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        com.moddingfromamod.testing.MockPlayerNetwork.actingChildFor(helper, child);
        var who = BuilderMake.Who.of(child);
        var server = helper.getLevel().getServer();
        UUID me = child.getUUID();
        FreeEdit.forget(me);
        long rise = BuilderMake.riseMillis;
        BuilderMake.riseMillis = 0;
        com.moddingfromamod.testing.MockPlayerNetwork.afterTest(helper, () -> {
            BuilderMake.riseMillis = rise;
            FreeEdit.forget(me);
        });
        BuilderSpec cat = BuilderSpec.read(JsonParser.parseString(
                "{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\",\"size\":2,\"name\":\"Freebie\"}").getAsJsonObject());
        BuilderSpec cat2 = BuilderSpec.read(JsonParser.parseString(
                "{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\",\"size\":2,\"name\":\"Freebie Two\"}").getAsJsonObject());
        BuilderSpec cat2big = BuilderSpec.read(JsonParser.parseString(
                "{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\",\"size\":4,\"name\":\"Freebie Two\"}").getAsJsonObject());
        List<String> wrong = java.util.Collections.synchronizedList(new ArrayList<>());
        List<String> verdicts = java.util.Collections.synchronizedList(new ArrayList<>());
        String[] first = new String[1];
        CompletableFuture<?> all = apprentice(server, who, cat)
                .thenComposeAsync(a -> {
                    verdicts.add("apprentice 1 → " + a.outcome() + " " + a.madeId());
                    first[0] = a.madeId();
                    if (!FreeEdit.isFree(me, a.madeId())) {
                        wrong.add("the Apprentice make's thing is not free to change");
                    }
                    return apprentice(server, who, cat2);
                })
                .thenComposeAsync(b -> {
                    verdicts.add("apprentice 2 → " + b.outcome() + " " + b.madeId());
                    if (FreeEdit.isFree(me, first[0])) {
                        wrong.add("the FIRST make is still free after a second Apprentice make");
                    }
                    if (!FreeEdit.isFree(me, b.madeId())) {
                        wrong.add("the second Apprentice make's thing is not the free one");
                    }
                    return once(server, who, cat2big, b.madeId());
                })
                .thenComposeAsync(e -> {
                    verdicts.add("free change → " + e.outcome() + " " + e.madeId());
                    if (e.outcome() != BuilderMake.Outcome.MADE && e.outcome() != BuilderMake.Outcome.PARTIAL) {
                        wrong.add("the free change did not stand: " + e.detail());
                    }
                    if (FreeEdit.isFree(me, e.madeId())) {
                        wrong.add("the free change is still free after it was used");
                    }
                    return apprentice(server, who, cat);
                })
                .thenComposeAsync(c -> {
                    verdicts.add("apprentice 3 → " + c.outcome() + " " + c.madeId());
                    CompletableFuture<Void> typed = new CompletableFuture<>();
                    server.execute(() -> {
                        // any other wish: the wish book hearing of it ends the free change
                        com.moddingfromamod.wish.WishBook.get(server).add(com.moddingfromamod.wish.Wish.create("a typed wish",
                                com.moddingfromamod.wish.WishSource.TYPED, System.currentTimeMillis(), me, who.name(),
                                who.dimension(), who.x(), who.y(), who.z()));
                        if (FreeEdit.isFree(me, c.madeId())) {
                            wrong.add("another wish did not end the free change");
                        }
                        typed.complete(null);
                    });
                    return typed;
                });
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(all.isDone(), Component.literal("still making: " + verdicts));
            helper.assertFalse(all.isCompletedExceptionally(), Component.literal("a make threw: " + verdicts));
            helper.assertTrue(wrong.isEmpty(), Component.literal(String.join("\n", wrong) + "\n" + verdicts));
            LOGGER.info("builder free edit GT: {}", verdicts);
        });
    }

    /**
     * a Builder make LIVES as its picks say, through the real tools: a ready-made cat is a pack hunter (it comes
     * to him), the same cat with Flies flies (a flitter), a giant one circles (a dragon), and a Builder body with no life
     * of its own (the eagle) gets a flying one. Before the first three lived their carrier's life.
     */
    @GameTest(template = "empty_test", batch = "builderBehaviour", timeoutTicks = 12000)
    public static void aBuilderCreatureLivesTheBehaviourItsPicksChoose(GameTestHelper helper) {
        var child = com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper, "gtbehaviour");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        child.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        com.moddingfromamod.testing.MockPlayerNetwork.actingChildFor(helper, child);
        var who = BuilderMake.Who.of(child);
        var server = helper.getLevel().getServer();
        long rise = BuilderMake.riseMillis;
        BuilderMake.riseMillis = 0;
        com.moddingfromamod.testing.MockPlayerNetwork.afterTest(helper, () -> BuilderMake.riseMillis = rise);
        String[][] cases = {
                {"{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\",\"size\":2,\"name\":\"Behave Cat\"}", "pack_hunter"},
                {"{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\",\"size\":2,\"powers\":[\"power:flies\"],\"name\":\"Behave Flying Cat\"}", "buzzer"},
                {"{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:species/cat\",\"size\":4,\"temper\":\"wild\",\"powers\":[\"power:flies\"],\"name\":\"Behave Giant Cat\"}", "dragon"},
                {"{\"builder\":1,\"kind\":\"creature\",\"from\":\"preset:bodies/eagle\",\"size\":2,\"name\":\"Behave Eagle\"}", "*flying"}};
        List<String> wrong = java.util.Collections.synchronizedList(new ArrayList<>());
        List<String> verdicts = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicReference<CompletableFuture<?>> chain = new AtomicReference<>(CompletableFuture.completedFuture(null));
        for (String[] c : cases) {
            BuilderSpec spec = BuilderSpec.read(JsonParser.parseString(c[0]).getAsJsonObject());
            chain.set(chain.get().thenComposeAsync(x -> once(server, who, spec, null)).thenAcceptAsync(made -> {
                var sp = made.madeId() == null ? null : com.moddingfromamod.species.SpeciesRegistry.get(made.madeId());
                String life = sp == null || sp.archetype() == null ? "(none)" : sp.archetype().key();
                verdicts.add(spec.name() + " -> " + made.outcome() + " " + life);
                boolean ok = c[1].equals("*flying") ? com.moddingfromamod.species.BehaviourChoice.FLYING.contains(life)
                        : c[1].equals(life);
                if (!ok) {
                    wrong.add(spec.name() + ": lives as " + life + ", want " + c[1] + " (" + made.outcome() + " " + made.detail() + ")");
                }
            }));
        }
        // (3): a creature saved BEFORE it had a behaviour gets the one its kept picks choose, at load.
        java.util.Map<String, String> firstMade = new java.util.concurrent.ConcurrentHashMap<>();
        chain.set(chain.get().thenComposeAsync(x -> {
            CompletableFuture<Void> back = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    var made = com.moddingfromamod.species.SpeciesRegistry.all().stream()
                            .filter(sp -> sp.name().equals("Behave Flying Cat")).findFirst().orElse(null);
                    if (made == null) {
                        wrong.add("backfill: the flying cat is not registered");
                    } else {
                        com.moddingfromamod.testing.SpeciesForTest.putSaved(helper, made.withBehaves(null));   // as saved
                        int n = com.moddingfromamod.species.SpeciesRegistry.backfillBehaviour(server, BuilderBehaviourBackfill::choose);
                        var now = com.moddingfromamod.species.SpeciesRegistry.get(made.id());
                        String life = now == null || now.archetype() == null ? "(none)" : now.archetype().key();
                        firstMade.put("backfill", n + " " + life);
                        if (!life.equals("buzzer")) {
                            wrong.add("backfill: the saved flying cat lives as " + life + " after the backfill (" + n + " filled), want buzzer");
                        }
                    }
                } catch (RuntimeException e) {
                    wrong.add("backfill threw: " + e);
                } finally {
                    back.complete(null);
                }
            });
            return back;
        }));
        CompletableFuture<?> all = chain.get();
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            helper.assertTrue(all.isDone(), Component.literal("still making: " + verdicts));
            helper.assertFalse(all.isCompletedExceptionally(), Component.literal("a make threw: " + verdicts));
            helper.assertTrue(wrong.isEmpty(), Component.literal(String.join("\n", wrong) + "\n" + verdicts));
            LOGGER.info("builder behaviour GT: {} {}", verdicts, firstMade);
        });
    }

    private static CompletableFuture<BuilderMake.Result> apprentice(net.minecraft.server.MinecraftServer server,
                                                                    BuilderMake.Who who, BuilderSpec spec) {
        CompletableFuture<BuilderMake.Result> r = new CompletableFuture<>();
        server.execute(() -> BuilderMake.runApprentice(server, who, spec, null, protocol(), false, false)
                .whenComplete((res, err) -> {
                    if (err != null) {
                        r.completeExceptionally(err);
                    } else {
                        r.complete(res);
                    }
                }));
        return r;
    }

    private static CompletableFuture<BuilderMake.Result> once(net.minecraft.server.MinecraftServer server,
                                                              BuilderMake.Who who, BuilderSpec spec, String editOf) {
        CompletableFuture<BuilderMake.Result> r = new CompletableFuture<>();
        server.execute(() -> BuilderMake.run(server, who, spec, null, protocol(), false, false, editOf)
                .whenComplete((res, err) -> {
                    if (err != null) {
                        r.completeExceptionally(err);
                    } else {
                        r.complete(res);
                    }
                }));
        return r;
    }

    /**
     * What this make should have put in the world or in his bag, as a check: null when it is there, else what is
     * missing. Server thread (the poll).
     */
    /** The kinds that stand somewhere in the world, so have a spot to land at. */
    /**
     * <b>Take down every building this test made, when it ends</b> (pass or fail) —.
     *
     * <p>The 1.21.8 runner never clears a finished plot, and lays the next batch's plots beside the last. A
     * building here is not small and does not stay in its plot: the size-4 treehouse is 20,524 blocks, its
     * leaves forty-odd blocks across and high, and a building's spot slides 6–12 blocks out for room. Left
     * standing, it shaded {@code FlierCarrierLightGameTest}'s plot a batch later (the plane's spot read sky 4,
     * the bear's 9, against 15 in open air), and anything later laid under it measures a shadow nobody in that
     * test put there. A queued build is placed first, so nothing goes up after it is cleared.
     */
    public static void takeDownWhenDone(GameTestHelper helper, java.util.function.Supplier<? extends java.util.Collection<String>> ids) {
        net.minecraft.server.level.ServerLevel level = helper.getLevel();
        com.moddingfromamod.testing.MockPlayerNetwork.afterTest(helper, () -> {
            com.moddingfromamod.builder.Builder.drainNow(level);
            for (String id : List.copyOf(ids.get())) {
                var plan = com.moddingfromamod.builder.BuildRegistry.get(id);
                Long anchor = com.moddingfromamod.builder.BuildRegistry.anchorOf(id);
                if (plan == null || anchor == null || !com.moddingfromamod.builder.BuildRegistry.standing(id)) {
                    continue;
                }
                int cleared = com.moddingfromamod.builder.Builder.clearBuild(level, BlockPos.of(anchor), plan);
                com.moddingfromamod.builder.BuildRegistry.markRemoved(level.getServer(), id);
                LOGGER.info("builder make GT: took down '{}' ({} block(s)) so it shades no later plot", id, cleared);
            }
        });
    }

    public static void takeDownWhenDone(GameTestHelper helper, List<String> ids) {
        takeDownWhenDone(helper, () -> ids);
    }

    static boolean placed(BuilderSpec.Kind k) {
        return k == BuilderSpec.Kind.CREATURE || k == BuilderSpec.Kind.RIDE || k == BuilderSpec.Kind.BUILDING
                || k == BuilderSpec.Kind.EVENT;
    }

    /** It stands within a few blocks of the spot the make was locked to. */
    static java.util.function.Supplier<String> whereCheck(GameTestHelper helper, BuilderSpec spec, String madeId, BlockPos aimed) {
        final double near = 8;
        return switch (spec.kind()) {
            case CREATURE, RIDE -> new java.util.function.Supplier<String>() {
                // LATCHED once it is seen at its spot: the question is where it LANDED, and this is polled
                // until every make in the list has finished, so a ride free to roam walked 9 blocks off in the minutes
                // after (measured when an earlier issue's composed machine lengthened the list) and read as made in the wrong place.
                boolean landedThere;

                @Override
                public String get() {
                    if (landedThere) {
                        return null;
                    }
                    String why = where();
                    landedThere = why == null;
                    return why;
                }

                String where() {
                boolean mob = Catalogue.prefix(spec.from()).equals("mob");
                for (var e : helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.Entity.class,
                        new net.minecraft.world.phys.AABB(aimed).inflate(32))) {
                    var sp = com.moddingfromamod.vanilla.DisplayRig.speciesOn(e);
                    boolean it = mob ? e.hasCustomName() && e.getCustomName().getString().equals(spec.name())
                            : sp != null && sp.id().equals(madeId);
                    if (it) {
                        double d = Math.sqrt(e.blockPosition().distSqr(aimed));
                        return d <= near ? null : "stands " + Math.round(d) + " blocks from where it was aimed";
                    }
                }
                return "not found near where it was aimed " + aimed.toShortString();
                }
            };
            case BUILDING -> () -> {
                Long a = com.moddingfromamod.builder.BuildRegistry.anchorOf(madeId);
                if (a == null) {
                    return "has no anchor";
                }
                double d = Math.sqrt(BlockPos.of(a).distSqr(aimed));
                return d <= near ? null : "built " + Math.round(d) + " blocks from where it was aimed";
            };
            case EVENT -> () -> com.moddingfromamod.event.EventDirector.all(helper.getLevel().getServer()).stream()
                    .filter(e -> e.id().equals(madeId) || e.id().startsWith(madeId + "_"))
                    .anyMatch(e -> e.focus() != null && Math.sqrt(e.focus().distSqr(aimed)) <= near) ? null
                    : "not staged near where it was aimed";
            default -> () -> null;
        };
    }

    static java.util.function.Supplier<String> landedCheck(GameTestHelper helper, net.minecraft.server.level.ServerPlayer child,
                                                           BuilderSpec spec, String madeId) {
        var server = helper.getLevel().getServer();
        var calls = BuilderLowering.lower(spec, madeId, LibraryPresets.SHIPPED);
        String itemName = "";
        for (var c : calls) {
            if (c.tool().equals("make_item") && c.args().has("name")) {
                itemName = c.args().get("name").getAsString();
            }
        }
        final String wantItem = itemName;
        return switch (spec.kind()) {
            case ITEM, MACHINE, MAGIC_BLOCK -> () -> {
                var inv = child.getInventory();
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    if (!inv.getItem(i).isEmpty() && inv.getItem(i).getHoverName().getString().contains(wantItem)) {
                        return null;
                    }
                }
                return "no '" + wantItem + "' in his bag";
            };
            case CREATURE, RIDE -> () -> {
                var box = child.getBoundingBox().inflate(64);
                if (Catalogue.prefix(spec.from()).equals("mob")) {
                    String name = spec.name();
                    return helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, box,
                            m -> m.hasCustomName() && m.getCustomName().getString().equals(name)).isEmpty()
                            ? "no mob named " + name + " near him" : null;
                }
                for (var e : helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.Entity.class, box)) {
                    var sp = com.moddingfromamod.vanilla.DisplayRig.speciesOn(e);
                    if (sp != null && sp.id().equals(madeId)) {
                        return null;
                    }
                }
                return "no '" + madeId + "' standing near him";
            };
            case BUILDING -> () -> com.moddingfromamod.builder.BuildRegistry.standing(madeId) ? null
                    : "'" + madeId + "' is not standing";
            case PICTURE -> () -> {
                var pic = com.moddingfromamod.paint.PictureStore.get(madeId);
                if (pic == null) {
                    return "no picture '" + madeId + "'";
                }
                // it hangs at the frame he set, else its drawing's own (a 4x4 castle hangs 4x4).
                String subject = BuilderPictures.composed(spec) ? BuilderPictures.subject(spec.opt("subject")) : "";
                int want = !spec.opt("frame").isEmpty() ? Integer.parseInt(spec.opt("frame"))
                        : subject.isEmpty() ? pic.wide() : BuilderPictures.ownFrame(LibraryPresets.SHIPPED.read(subject));
                return pic.wide() == want && pic.tall() == want ? null
                        : "picture '" + madeId + "' hangs " + pic.wide() + "x" + pic.tall() + ", not " + want + "x" + want;
            };
            // A staged event's id is its name and its run ("chicken_rain_5595ad_1_113").
            case EVENT -> () -> com.moddingfromamod.event.EventDirector.all(server).stream()
                    .anyMatch(e -> e.id().equals(madeId) || e.id().startsWith(madeId + "_")) ? null
                    : "no event '" + madeId + "' was staged";
            case RULE -> () -> com.moddingfromamod.rules.WorldRules.all(server).stream()
                    .anyMatch(r -> r.name().equals(madeId)) ? null : "no rule '" + madeId + "'";
            // the REAL vanilla items, as many as he asked for, in his hotbar or his Wish Bag.
            case SUPPLIES -> () -> {
                BuilderSupplies.Supply s = BuilderSupplies.supply(spec.from());
                int have = BuilderSuppliesGameTest.held(child, s.item());
                return have >= BuilderSupplies.amount(spec) ? null
                        : have + " " + s.item() + " in his hotbar and bag, not " + BuilderSupplies.amount(spec);
            };
        };
    }

    /** Blocks of {@code id} within 48 blocks of {@code at}. On the server thread (the poll), where the world is read. */
    static int countBlocks(GameTestHelper helper, BlockPos at, String id) {
        var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(
                net.minecraft.resources.ResourceLocation.parse(id));
        int c = 0;
        var level = helper.getLevel();
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-48, -8, -48), at.offset(48, 64, 48))) {
            if (level.getBlockState(p).is(block)) {
                c++;
            }
        }
        return c;
    }

    private static volatile McpProtocol protocol;

    private static McpProtocol protocol() {
        if (protocol == null) {
            synchronized (BuilderMakeGameTest.class) {
                if (protocol == null) {
                    protocol = new McpProtocol(WishAgent.buildTools(), new McpSessions());
                }
            }
        }
        return protocol;
    }
}
