package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildRegistry;
import com.moddingfromamod.marshal.ArtifactMemory;
import com.moddingfromamod.paint.PaintRecipe;
import com.moddingfromamod.paint.PaintRegistry;
import com.moddingfromamod.paint.PictureStore;
import com.moddingfromamod.runtime.CreatureFiles;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.RuntimeConfig;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.testing.MockPlayerNetwork;

import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>{@code /moddy try <wish id>} — a non-op gets a copy of ANOTHER player's thing by its
 * wish id, as his own wish, and the maker's shelf does not move by a byte</b>.
 *
 * <p>Each test plays one kind the way it really happens: a maker's wish is in the book, its
 * handover names the thing it made (the {@link ArtifactMemoryStore} turn {@code FinishWishTool}
 * writes), and the thing is on the MAKER's shelf. Then a second, non-op child runs the real
 * command through the real dispatcher at permission 0, and the test waits for the thing to arrive
 * beside him, his own wish to be in the book, and the maker's shelf to be byte-identical.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class WishTryGameTest {

    private static final String BATCH = "moddyTry";

    private WishTryGameTest() {
    }

    // ── the four kinds ──────────────────────────────────────────────────────────────────────

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 2400)
    public static void aNonOpTriesAnotherPlayersCreatureByItsId(GameTestHelper helper) {
        Pair p = pair(helper, "cr");
        String id = "gt_try_creature_" + stamp(helper);
        List<CreatureSculpt.Part> parts = com.moddingfromamod.vanilla.RigFitGameTest.whale();
        try {
            CreatureFiles.write(id, parts, com.moddingfromamod.vanilla.RigReloadGameTest.spec(parts));
        } catch (Exception e) {
            helper.fail(Component.literal("could not write the maker's sculpt: " + e));
            return;
        }
        com.moddingfromamod.testing.SpeciesForTest.putSaved(helper, Species.fromJson(JsonParser.parseString(
                "{\"id\":\"" + id + "\",\"modelKey\":\"" + id + "\",\"health\":20,\"speed\":0.0,"
                        + "\"scale\":1.0,\"ownerId\":\"" + p.maker.getStringUUID() + "\"}")
                .getAsJsonObject()));
        Wish made = makersWish(helper, p.maker, "a tiny whale", id, "creature");
        String tag = com.moddingfromamod.entity.WishMobTags.forSpecies(id);
        tryAndExpect(helper, p, made, made.id().toString(), () -> {
            long near = countNear(p.taker, e -> e.getTags().contains(tag));
            helper.assertTrue(near >= 1, Component.literal("no copy of the maker's creature arrived beside the taker"));
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 2400)
    public static void aNonOpTriesAnotherPlayersBuildingByItsShortId(GameTestHelper helper) {
        Pair p = pair(helper, "bu");
        String id = "gt_try_build_" + stamp(helper);
        BuildPlan plan = BuildPlan.fromJson(JsonParser.parseString(
                "{\"id\":\"" + id + "\",\"name\":\"Emerald Hut\",\"shapes\":[{\"kind\":\"box\","
                        + "\"dx\":0,\"dy\":0,\"dz\":0,\"width\":3,\"height\":2,\"depth\":3,"
                        + "\"block\":\"minecraft:emerald_block\"}],\"ownerId\":\""
                        + p.maker.getStringUUID() + "\"}").getAsJsonObject());
        BuildRegistry.put(helper.getLevel().getServer(), plan);
        BlockPos makersSpot = new BlockPos(1, 2, 3);
        BuildRegistry.rememberAnchor(id, makersSpot.asLong(), helper.getLevel().dimension());
        Wish made = makersWish(helper, p.maker, "an emerald hut", id, "build");
        tryAndExpect(helper, p, made, made.shortId(), () -> {
            helper.assertTrue(blocksAhead(p.taker, Blocks.EMERALD_BLOCK) >= 18,
                    Component.literal("the copy of the maker's building did not go up beside the taker"));
            Long spot = BuildRegistry.anchorOf(id);
            helper.assertTrue(spot != null && spot == makersSpot.asLong(),
                    Component.literal("the copy took over the MAKER's remembered spot for his building"));
        });
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 4000)
    public static void aNonOpTriesAnotherPlayersItemByItsId(GameTestHelper helper) {
        Pair p = pair(helper, "it");
        // An item a child made IS its datapack, installed in the world, with a design on his
        // shelf. The pack here is a real one (the lightning sword's), installed the way a world
        // load installs every stored pack.
        String pack = "lightning_sword_lightning";
        var server = helper.getLevel().getServer();
        try {
            com.moddingfromamod.datapack.DatapackReloader.installAndReload(server, pack,
                    com.moddingfromamod.datapack.DatapackStore.zipFor(pack));
        } catch (IOException e) {
            helper.fail(Component.literal("could not install the maker's item pack: " + e));
            return;
        }
        Wish made = makersWish(helper, p.maker, "a lightning sword", pack, "item");
        boolean[] tried = {false};
        RealTimeWait.until(helper, () -> {
            boolean loaded = !com.moddingfromamod.datapack.DatapackReloader.reloadInProgress()
                    && !com.moddingfromamod.datapack.DatapackReloader.reloadDeferred()
                    && server.getFunctions().get(net.minecraft.resources.Identifier
                            .fromNamespaceAndPath(pack, "reset")).isPresent();
            helper.assertTrue(loaded, Component.literal("the item's pack is still loading"));
        }, () -> tryAndExpect(helper, p, made, made.id().toString(), () -> {
            boolean has = false;
            for (var stack : p.taker.getInventory().getNonEquipmentItems()) {
                has |= pack.equals(com.moddingfromamod.item.WishItem.wishFeature(stack));
            }
            helper.assertTrue(has, Component.literal("the taker was not given his own copy of the maker's item"));
        }));
    }

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 2400)
    public static void aNonOpTriesAnotherPlayersPictureByItsId(GameTestHelper helper) {
        Pair p = pair(helper, "pi");
        String id = "gt_try_pic_" + stamp(helper);
        PictureStore.put(new PictureStore.Picture(id, p.maker.getStringUUID(),
                PaintRecipe.parse(JsonParser.parseString("{\"size\":64,\"rects\":["
                        + "{\"x\":0,\"y\":0,\"w\":64,\"h\":64,\"colour\":\"blue\"},"
                        + "{\"x\":16,\"y\":16,\"w\":32,\"h\":32,\"colour\":\"yellow\"}]}")
                        .getAsJsonObject()), 2, 2));
        Wish made = makersWish(helper, p.maker, "a yellow sun on blue", id, "painting");
        String copy = com.moddingfromamod.paint.PictureCommand.copyId(id,
                com.moddingfromamod.marshal.ArtifactOwner.normalise(p.taker.getStringUUID()));
        tryAndExpect(helper, p, made, made.id().toString(), () -> {
            var hung = PaintRegistry.get(copy);
            helper.assertTrue(hung != null && hung.hasVanillaCopy(),
                    Component.literal("no copy of the maker's picture was hung for the taker (" + copy + ")"));
            helper.assertTrue(PaintRegistry.get(id) == null,
                    Component.literal("the copy was recorded under the MAKER's picture id, over his placement"));
        });
    }

    // ── what is not a wish ──────────────────────────────────────────────────────────────────

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 200)
    public static void anUnknownOrMalformedIdSaysSoAndGrantsNothing(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer taker = MockPlayerNetwork.inLevel(helper, "gt_try_nobody");
        try {
            int before = wishesOf(helper, taker).size();
            helper.assertTrue(run(helper, taker, UUID.randomUUID().toString()) == 0,
                    Component.literal("an unknown full id was granted"));
            helper.assertTrue(run(helper, taker, "0badf00d") == 0, Component.literal("an unknown short id was granted"));
            helper.assertTrue(run(helper, taker, "hello") == 0, Component.literal("a word that is no id was granted"));
            helper.assertTrue(WishTry.tryIt(taker, "not-an-id") == WishTry.Result.NOT_AN_ID,
                    Component.literal("a malformed id was not called malformed"));
            helper.assertTrue(WishTry.tryIt(taker, UUID.randomUUID().toString())
                    == WishTry.Result.UNKNOWN, Component.literal("an unknown id was not called unknown"));
            helper.assertTrue(wishesOf(helper, taker).size() == before,
                    Component.literal("an id that names nothing still put a wish in his book"));
            String said = String.join(" | ", MockPlayerNetwork.chat(taker));
            helper.assertTrue(said.contains("isn't a wish id") && said.contains("can't find a wish"),
                    Component.literal("he was not told, in plain words, what was wrong: " + said));
            helper.succeed();
        } finally {
            MockPlayerNetwork.remove(taker);
        }
    }

    // ── the harness ─────────────────────────────────────────────────────────────────────────

    private record Pair(ServerPlayer maker, ServerPlayer taker) {
    }

    private static Pair pair(GameTestHelper helper, String tag) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer maker = MockPlayerNetwork.inLevel(helper, "gt_maker_" + tag);
        ServerPlayer taker = MockPlayerNetwork.inLevel(helper, "gt_taker_" + tag);
        helper.assertTrue(!maker.hasPermissions(2) && !taker.hasPermissions(2),
                Component.literal("the players in this test must NOT be operators — the command is for anyone"));
        return new Pair(maker, taker);
    }

    private static String stamp(GameTestHelper helper) {
        return Long.toString(helper.getLevel().getGameTime(), 36)
                + Integer.toString((int) (Math.random() * 1296), 36);
    }

    /** The maker's own granted wish, and the handover that says what it made. */
    private static Wish makersWish(GameTestHelper helper, ServerPlayer maker, String text,
                                   String artifactId, String kind) {
        var book = WishBook.get(helper.getLevel().getServer());
        Wish w = book.add(Wish.create(text, WishSource.TYPED, System.currentTimeMillis(),
                maker.getUUID(), maker.getGameProfile().name(),
                maker.level().dimension().identifier().toString(),
                maker.getX(), maker.getY(), maker.getZ()));
        book.complete(w.id(), WishStatus.DONE, "made it");
        ArtifactMemoryStore.append(artifactId, kind, new ArtifactMemory.Turn(w.id().toString(),
                System.currentTimeMillis(), "gt", text, "", "made " + artifactId, "", List.of(),
                "", "", ""), text, "", List.of(), List.of());
        return w;
    }

    /**
     * Run {@code /moddy try <idArg>} as the taker through the real dispatcher, then wait until
     * {@code arrived} holds, his own wish is in the book, and the maker's shelf is untouched.
     */
    private static void tryAndExpect(GameTestHelper helper, Pair p, Wish made, String idArg,
                                     Runnable arrived) {
        Map<String, String> shelfBefore = shelf(p.maker);
        int before = wishesOf(helper, p.taker).size();
        int rc = run(helper, p.taker, idArg);
        helper.assertTrue(rc == 1, Component.literal("/moddy try " + idArg + " was not granted: "
                + String.join(" | ", MockPlayerNetwork.chat(p.taker))));
        com.moddingfromamod.test.RealTime.succeedWhen(helper, () -> {
            arrived.run();
            List<Wish> his = wishesOf(helper, p.taker);
            helper.assertTrue(his.size() == before + 1, Component.literal("the copy is not HIS wish in the book"));
            Wish mine = his.get(his.size() - 1);
            helper.assertTrue(mine.text().equals(made.text()) && !mine.id().equals(made.id()),
                    Component.literal("his wish is not a new wish in the maker's words"));
            helper.assertTrue(mine.status() == WishStatus.DONE, Component.literal("his copy has not finished granting"));
            helper.assertTrue(shelf(p.maker).equals(shelfBefore),
                    Component.literal("the MAKER's shelf changed: before " + shelfBefore.keySet()
                            + " after " + shelf(p.maker).keySet()));
            MockPlayerNetwork.remove(p.taker);
            MockPlayerNetwork.remove(p.maker);
        });
    }

    private static int run(GameTestHelper helper, ServerPlayer who, String idArg) {
        var src = who.createCommandSourceStack();   // HIS permission level: 0, not an op's
        return helper.getLevel().getServer().getCommands().getDispatcher().getRoot() == null ? 0
                : runQuietly(helper, src, "moddy try " + idArg);
    }

    private static int runQuietly(GameTestHelper helper,
                                  net.minecraft.commands.CommandSourceStack src, String cmd) {
        try {
            return helper.getLevel().getServer().getCommands().getDispatcher().execute(cmd, src);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            helper.fail(Component.literal("'/" + cmd + "' did not parse for a non-op: " + e.getMessage()));
            return 0;
        }
    }

    private static List<Wish> wishesOf(GameTestHelper helper, ServerPlayer p) {
        return WishBook.get(helper.getLevel().getServer()).recent(500).stream()
                .filter(w -> p.getUUID().equals(w.playerId())).toList();
    }

    /** Every file on this player's shelf, path → size + content hash. */
    private static Map<String, String> shelf(ServerPlayer p) {
        Map<String, String> out = new TreeMap<>();
        Path dir = RuntimeConfig.playersDir().resolve(
                com.moddingfromamod.marshal.ArtifactOwner.normalise(p.getStringUUID()));
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path f : walk.filter(Files::isRegularFile).toList()) {
                byte[] b = Files.readAllBytes(f);
                out.put(dir.relativize(f).toString(), b.length + ":" + java.util.Arrays.hashCode(b));
            }
        } catch (IOException e) {
            out.put("<unreadable>", e.toString());
        }
        return out;
    }

    private static long countNear(ServerPlayer p, java.util.function.Predicate<net.minecraft.world.entity.Entity> test) {
        ServerLevel level = p.level();
        return level.getEntities((net.minecraft.world.entity.Entity) null,
                p.getBoundingBox().inflate(32), test).size();
    }

    /** A building goes up where the wish was AIMED, which can be anywhere out to {@link WishAim#REACH}
     *  in front of him. A ±24 box around the taker passed for weeks and then failed a release (2026-11-09):
     *  the aim hit 25 blocks ahead, so the hut stood one block outside it. Count the strip he faces. */
    private static int blocksAhead(ServerPlayer p, net.minecraft.world.level.block.Block block) {
        ServerLevel level = p.level();
        BlockPos c = p.blockPosition();
        net.minecraft.core.Direction f = p.getDirection();
        net.minecraft.core.Direction side = f.getClockWise();
        int n = 0;
        for (int along = -8; along <= WishAim.REACH + 8; along++) {
            for (int across = -8; across <= 8; across++) {
                for (int up = -8; up <= 16; up++) {
                    BlockPos q = c.relative(f, along).relative(side, across).above(up);
                    if (level.getBlockState(q).is(block)) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /** Wait for a precondition, then do the rest. */
    private static final class RealTimeWait {
        static void until(GameTestHelper helper, Runnable condition, Runnable then) {
            boolean[] done = {false};
            helper.onEachTick(() -> {
                if (done[0]) {
                    return;
                }
                try {
                    condition.run();
                } catch (RuntimeException notYet) {
                    return;
                }
                done[0] = true;
                then.run();
            });
        }
    }
}
