package com.moddingfromamod.lite;

import net.minecraft.network.chat.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Gate B for the mint mark — the half that needs a real world on disk.</b>
 *
 * <p>{@link MintMarksTest} has the rules. What only a server can answer is whether the record
 * actually reaches the disk, comes back after a reload, and — the one that matters most — whether a
 * damaged file can hurt anything. A mark that could abort a world load would be strictly worse than
 * no mark, so that is asserted rather than assumed.
 *
 * <p>The store is a static, like every sibling store, so each test restores what it found. Nothing
 * here writes into a plot: {@code <world>/wishes/minted.json} is world-global by design.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class MintStoreGameTest {

    /**
     * <b>Its own batch, and that is isolation rather than tidiness.</b> The store is a static and
     * the file is world-global, so a sibling test running {@code make_item} or {@code sculpt_creature}
     * in the same batch would stamp the very record these tests are measuring — and the failure
     * would land on whichever of the two happened to read second. Batches are strictly sequential
     * ({@code GameTestRunner.runBatch(i)} starts {@code i+1} only once {@code i} has finished), so
     * naming one is real serialisation. Three tests is a cheap thing to serialise.
     */
    private static final String BATCH = "mintmark";

    @GameTest(template = "empty_test", batch = BATCH)
    public static void theMarkSurvivesAReload(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Path file = MintStore.file(server);
        String saved = read(file);
        try {
            MintStore.resetForTest();
            MintStore.stamp(server, "item", true);
            MintStore.stamp(server, "species", false);
            helper.assertTrue(Files.isRegularFile(file), Component.literal("stamping must write " + file));

            // The reload: throw the in-memory record away and read the world back.
            MintStore.resetForTest();
            helper.assertTrue(MintStore.modeOf("item") == MintMarks.Mode.UNKNOWN,
                    Component.literal("the seam must really forget, or this test proves nothing"));
            MintStore.load(server);
            helper.assertTrue(MintStore.modeOf("item") == MintMarks.Mode.VANILLA,
                    Component.literal("item came back as " + MintStore.modeOf("item")));
            helper.assertTrue(MintStore.modeOf("species") == MintMarks.Mode.MOD,
                    Component.literal("species came back as " + MintStore.modeOf("species")));
            helper.assertTrue(MintStore.modeOf("painting") == MintMarks.Mode.UNKNOWN,
                    Component.literal("a class nothing minted must stay UNKNOWN, not inherit a neighbour's answer"));
            helper.succeed();
        } finally {
            restore(file, saved);
            MintStore.resetForTest();
        }
    }

    /**
     * A corrupt mark costs the mark and nothing else — the property every sibling store in this
     * repo holds, and the reason none of them is a generated codec.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public static void aDamagedMarkIsSkippedNeverFatal(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Path file = MintStore.file(server);
        String saved = read(file);
        try {
            for (String rubbish : new String[]{"", "{", "[]", "{\"mintedBy\":3}",
                    "{\"version\":\"tomorrow\",\"mintedBy\":{\"item\":[]}}"}) {
                write(file, rubbish);
                MintStore.resetForTest();
                MintStore.load(server);   // must not throw
                helper.assertTrue(MintStore.modeOf("item") == MintMarks.Mode.UNKNOWN,
                        Component.literal("a world whose mark cannot be read is UNKNOWN, never a guess — "
                                + "got " + MintStore.modeOf("item") + " from <" + rubbish + ">"));
            }

            // …and one good class among the rubbish still comes back.
            write(file, "{\"version\":1,\"mintedBy\":{\"item\":\"VANILLA\",\"species\":9}}");
            MintStore.resetForTest();
            helper.assertTrue(MintStore.load(server) == 1, Component.literal("one readable class of two"));
            helper.assertTrue(MintStore.modeOf("item") == MintMarks.Mode.VANILLA,
                    Component.literal("the readable half must survive its neighbour"));
            helper.succeed();
        } finally {
            restore(file, saved);
            MintStore.resetForTest();
        }
    }

    /** A world with no mark file at all — which is every world that predates this. */
    @GameTest(template = "empty_test", batch = BATCH)
    public static void aWorldWithNoMarkLoadsAndReportsUnknown(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Path file = MintStore.file(server);
        String saved = read(file);
        try {
            try {
                Files.deleteIfExists(file);
            } catch (java.io.IOException e) {
                helper.fail(Component.literal("could not clear the mark: " + e));
            }
            MintStore.resetForTest();
            helper.assertTrue(MintStore.load(server) == 0, Component.literal("nothing to restore"));
            for (String kind : MintMarks.MARKED) {
                helper.assertTrue(MintStore.modeOf(kind) == MintMarks.Mode.UNKNOWN,
                        Component.literal(kind + " should be UNKNOWN on a world that predates the mark"));
            }
            helper.assertTrue(MintStore.current().describe().contains("UNKNOWN"),
                    Component.literal("and it must SAY so — silence is what the mark exists to remove"));
            helper.succeed();
        } finally {
            restore(file, saved);
            MintStore.resetForTest();
        }
    }

    /**
     * <b>The mark is stamped where the mint actually HAPPENS — driven, not asserted about.</b>
     *
     * <p>This repo's own rule, from {@code docs/MACHINE_DEBUG_LOOP.md}: <i>a rule tested only where
     * it is DEFINED is not tested where it RUNS</i>. {@link MintMarks} could be perfect and every
     * stamp call unreachable, and both of the tests above would still be green — they drive the
     * store directly. So this one goes through {@code VanillaWatchItem.give}, a real mint site that
     * needs no agent: hand a child Moddy and the world must know it now holds a carrier.
     */
    @GameTest(template = "empty_test", batch = BATCH)
    public static void handingOutModdyReallyStampsTheWorld(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Path file = MintStore.file(server);
        String saved = read(file);
        try {
            MintStore.resetForTest();
            helper.assertTrue(MintStore.modeOf("watch") == MintMarks.Mode.UNKNOWN,
                    Component.literal("the fixture started already marked, so this would prove nothing"));

            com.moddingfromamod.testing.MockPlayerNetwork.clearStaleMocks(helper);
            net.minecraft.server.level.ServerPlayer p =
                    com.moddingfromamod.testing.MockPlayerNetwork.inLevel(helper);
            com.moddingfromamod.testing.MockPlayerNetwork.withoutChannels(p, () -> {
                p.getInventory().clearContent();
                helper.assertTrue(com.moddingfromamod.wish.VanillaWatchItem.give(p),
                        Component.literal("give() refused on an empty inventory"));
            });

            helper.assertTrue(MintStore.modeOf("watch") == MintMarks.Mode.VANILLA,
                    Component.literal("handing out a carrier Moddy must mark the world — got "
                            + MintStore.modeOf("watch")));
            helper.assertTrue(Files.isRegularFile(file), Component.literal("and it must reach the disk: " + file));
            helper.succeed();
        } finally {
            restore(file, saved);
            MintStore.resetForTest();
        }
    }

    private static String read(Path p) {
        try {
            return Files.isRegularFile(p) ? Files.readString(p, StandardCharsets.UTF_8) : null;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private static void write(Path p, String text) {
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(p, text, StandardCharsets.UTF_8);
        } catch (java.io.IOException ignored) {
            // the assertion below reports it
        }
    }

    private static void restore(Path p, String saved) {
        if (saved == null) {
            try {
                Files.deleteIfExists(p);
            } catch (java.io.IOException ignored) {
                // best effort
            }
        } else {
            write(p, saved);
        }
    }
}
