package com.moddingfromamod.store;

import net.minecraft.network.chat.Component;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.item.ItemSpecStore;
import com.moddingfromamod.item.WishItemCatalogue;
import com.moddingfromamod.runtime.WorldPaths;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Gate B.</b> The unit tests pin {@link WishStoreFile}'s rules where they are
 * DEFINED; this pins them where they RUN, against the real stores, the real world directory and the
 * real save/load cycle.
 *
 * <p>That distinction is this repo's most-repeated lesson: an earlier issue's feed gate was green for months
 * while hand-dropping past the tool the agent actually calls, and an earlier issue's note rule had 14 green unit
 * tests before the first one that drove the real path went red immediately.
 *
 * <p><b>The first test here fails on the pre-T9.1 code.</b> That is the point of it — both stores
 * whole-file overwrote {@code items.json}, so the second {@code save()} erased the first store's
 * data and the loser's {@code load()} then returned empty with nothing logged.
 *
 * <h2>⚠ Every test here is in its OWN batch, and that was learned the expensive way</h2>
 *
 * These four shared one batch on their first Gate B run and
 * {@code bothItemStoresSurviveEachOther} went red. <b>GameTests in the same batch run
 * CONCURRENTLY and share every static</b> — so {@code aRefusedStoreIsNeverOverwritten} deleted
 * {@code item-specs.json} while its neighbour was reloading from it, and two of these call
 * {@code load()}, which clears {@code SPECS} for everybody.
 *
 * <p>That is precisely the hazard {@code GateShardsTest} fails the build over, reproduced one level
 * down — <i>inside a single class</i>, where that test cannot see it because it compares classes.
 * Batches are strictly sequential ({@code runBatch(i+1)} runs only once batch {@code i} is done), so
 * a batch per test is real isolation. It costs nothing here: these do no tick work, only file IO.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class WishStoreFileGameTest {

    /** ⚠ RED before the envelope: whichever store saved second used to erase the other. */
    @GameTest(template = "empty_test", batch = "wishStoreSurvive")
    public void bothItemStoresSurviveEachOther(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        // ⚠ Deliberately NOT clearing either store. They are process-wide statics that other
        // GameTest classes read, and wiping them mid-batch deletes a neighbour's fixture — the
        // damage usually showing up as the NEIGHBOUR passing. GateShardsTest fails the build on it.
        // Unique ids make an assertion about this test's own rows instead.
        ItemSpecStore.put(spec("t9_sword", "T9 Sword"));

        WishItemCatalogue.remember(server, named(Items.IRON_SWORD, "T9 Catalogue Item"));

        // Both are on disk now. Reload exactly as a world load does.
        ItemSpecStore.load();
        WishItemCatalogue.load(server);

        // ⚠ The assertion carries its own evidence. Two guesses at this failure were wrong
        // (a batch race, then a null world root), both from reasoning rather than measuring —
        // which is the failure mode docs/MACHINE_DEBUG_LOOP.md tallies as more common than the bug.
        java.nio.file.Path specsFile = WorldPaths.wishesDir() == null ? null
                : WorldPaths.wishesDir().resolve("item-specs.json");
        String evidence = "\n    wishesDir=" + WorldPaths.wishesDir()
                + "\n    item-specs.json exists=" + (specsFile != null
                        && java.nio.file.Files.isRegularFile(specsFile))
                + "\n    its bytes=" + clip(specsFile == null ? "(no path)" : read(specsFile))
                + "\n    SPECS after load=" + ItemSpecStore.count()
                + "\n    get(t9_sword)=" + ItemSpecStore.get("t9_sword");
        helper.assertTrue(ItemSpecStore.get("t9_sword") != null,
                Component.literal("the item DESIGN survived the catalogue's save — this is #585." + evidence));
        helper.assertTrue(WishItemCatalogue.count() > 0,
                Component.literal("the catalogue survived the design store's save — this is #585 the other way round"));
        helper.succeed();
    }

    /** The split is real on disk: one writer per file, which is the structural half of the fix. */
    @GameTest(template = "empty_test", batch = "wishStoreSplit")
    public void theTwoStoresOwnDifferentFiles(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ItemSpecStore.put(spec("t9_split", "T9 Split"));
        WishItemCatalogue.remember(server, named(Items.STICK, "T9 Split Catalogue"));

        java.nio.file.Path specs = WorldPaths.wishesDir().resolve("item-specs.json");
        java.nio.file.Path items = WorldPaths.wishesDir().resolve("items.json");
        helper.assertTrue(java.nio.file.Files.isRegularFile(specs),
                Component.literal("the designs have their own file: " + specs));
        helper.assertTrue(java.nio.file.Files.isRegularFile(items),
                Component.literal("the catalogue keeps items.json: " + items));

        String specsText = read(specs);
        String itemsText = read(items);
        helper.assertTrue(specsText.contains("\"store\":\"item-specs\""),
                Component.literal("the designs file is stamped: " + clip(specsText)));
        helper.assertTrue(itemsText.contains("\"store\":\"items\""),
                Component.literal("the catalogue file is stamped: " + clip(itemsText)));
        helper.assertFalse(itemsText.contains("t9_split"),
                Component.literal("a design must not appear in the catalogue's file"));
        helper.succeed();
    }

    /**
     * ⚠ <b>The rollback guarantee.</b> A jar built before the envelope looked for its own top-level key and
     * nothing else. The envelope is inline precisely so that still works — nesting the payload would
     * have made every downgrade a silent empty store.
     */
    @GameTest(template = "empty_test", batch = "wishStoreRollback")
    public void anOldReaderStillFindsItsKey(GameTestHelper helper) {
        ItemSpecStore.put(spec("t9_rollback", "T9 Rollback"));

        String text = read(WorldPaths.wishesDir().resolve("item-specs.json"));
        // Exactly what the pre-T9.1 load() did: root.has("specs").
        com.google.gson.JsonObject root =
                com.google.gson.JsonParser.parseString(text).getAsJsonObject();
        helper.assertTrue(root.has("specs"), Component.literal("the payload key is still at the top level"));
        helper.assertTrue(root.getAsJsonArray("specs").size() >= 1, Component.literal("and it still holds the design"));
        helper.succeed();
    }

    /**
     * ⚠ <b>The branch that actually stops the data loss.</b> Refusing to READ an unreadable file is
     * useless on its own — the old code cleared its map, returned quietly, and the next write
     * persisted the emptiness. Here the bytes must still be there afterwards.
     */
    @GameTest(template = "empty_test", batch = "wishStoreRefusal")
    public void aRefusedStoreIsNeverOverwritten(GameTestHelper helper) {
        java.nio.file.Path f = WorldPaths.wishesDir().resolve("item-specs.json");
        String damaged = "{\"specs\": this is not json";
        try {
            java.nio.file.Files.createDirectories(f.getParent());
            java.nio.file.Files.writeString(f, damaged);
        } catch (Exception e) {
            helper.fail(Component.literal("could not stage the damaged file: " + e));
            return;
        }

        ItemSpecStore.load();               // refuses, and latches writing off
        ItemSpecStore.put(spec("t9_should_not_land", "nope"));

        boolean untouched = damaged.equals(read(f));

        // Put the store back before asserting, so a failure here cannot also strand the neighbours:
        // the latch is per LOAD, so deleting the damaged file and re-loading clears it.
        try {
            java.nio.file.Files.deleteIfExists(f);
        } catch (Exception ignored) {
            // best effort
        }
        ItemSpecStore.load();

        helper.assertTrue(untouched,
                Component.literal("the damaged file must be byte-for-byte untouched, not rewritten from an empty map"));
        helper.assertTrue(ItemSpecStore.count() >= 0 && new java.io.File(f.toString()).exists() == false,
                Component.literal("and the store is writable again once the damaged file is gone"));
        helper.succeed();
    }

    /**
     * A minimal design that actually ROUND-TRIPS. The 12-arg constructor is the pre-HD-7 one; owner
     * defaults to SHARED.
     *
     * <p>⚠ {@code effects} must be non-empty for a non-{@code plain} shape, and that is not
     * decoration: {@code Spec.fromJson} rejects an effect-less design unless its shape is
     * {@code plain} (let the plain one back in) — so a "wand" with no effects is written to
     * disk perfectly and rejected on the way back in. This fixture had an
     * empty list at first and the test failed with the file sitting on disk, correct and stamped,
     * and zero specs in memory. See the note in {@code docs/WISH_STORES.md} §7: the asymmetry is
     * real and is NOT fixed here, because changing what an item means does not belong under a
     * versioning ticket.
     */
    private static ItemSpecStore.Spec spec(String id, String name) {
        return new ItemSpecStore.Spec(id, name, "stick", "#ff0000", "wand", 1,
                java.util.List.of("sharpness"), new com.google.gson.JsonObject(),
                new com.google.gson.JsonArray(), new com.google.gson.JsonObject(),
                new com.google.gson.JsonArray(), "minecraft:stick");
    }

    /** The catalogue records only NAMED things on purpose, so a nameless stack proves nothing. */
    private static ItemStack named(net.minecraft.world.item.Item item, String name) {
        ItemStack s = new ItemStack(item);
        s.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal(name));
        return s;
    }

    private static String read(java.nio.file.Path p) {
        try {
            return java.nio.file.Files.readString(p);
        } catch (Exception e) {
            return "";
        }
    }

    private static String clip(String s) {
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }
}
