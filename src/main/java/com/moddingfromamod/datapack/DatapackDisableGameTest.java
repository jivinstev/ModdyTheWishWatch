package com.moddingfromamod.datapack;

import net.minecraft.network.chat.Component;
import com.moddingfromamod.ModdingFromAModMod;

import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Two disables in a row must not switch the first pack back on.</b>
 *
 * <p>{@code disableAndReload} used to read {@code repo.getSelectedIds()} and remove one id. That is
 * the same read {@code enableAndReload} carries a scar about twenty lines below it: the applied
 * selection lags the intended one by a reload, so two disables in quick succession both read the
 * SAME set, and because {@code kickReload} keeps only the latest, the second one's set still
 * contained the first pack — which the follow-up then re-applied.
 *
 * <p>{@code kickReload}'s own safety argument names the enable path in as many words: <i>"the
 * LATEST selection is a superset, since enableAndReload re-selects every saved pack, so nothing is
 * lost."</i> A disable produces a SUBSET, so the argument does not carry, and the failure is
 * silent — undo reports success and the wish is still running.
 *
 * <p>Reachable rather than theoretical: {@code WishUndo} loops over every {@code made()} entry, so
 * undoing one wish that authored two datapacks disables both back to back.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class DatapackDisableGameTest {

    @GameTest(template = "empty_test", batch = "datapackDisable")
    public static void aSecondDisableDoesNotResurrectTheFirstPack(GameTestHelper helper) {
        String a = "gt_disable_a_" + helper.getLevel().getGameTime();
        String b = "gt_disable_b_" + helper.getLevel().getGameTime();

        DatapackReloader.rememberDisabled(DatapackReloader.packId(a));
        DatapackReloader.rememberDisabled(DatapackReloader.packId(b));

        var off = DatapackReloader.disabledIds();
        helper.assertTrue(off.contains(DatapackReloader.packId(a)),
                Component.literal("the FIRST pack disabled must still be recorded as off after the second one is — "
                        + "that record is what stops the second disable's rebuilt selection from "
                        + "re-including it, which is how an undone wish went on running"));
        helper.assertTrue(off.contains(DatapackReloader.packId(b)),
                Component.literal("the second pack must be recorded too"));

        // Re-enabling is the ONE thing that legitimately undoes a disable, and it must clear the
        // record — otherwise the next disable rebuilds a set that still excludes this pack and
        // switches it straight back off, which is the same bug pointing the other way.
        DatapackReloader.forgetDisabled(DatapackReloader.packId(a));
        helper.assertTrue(!DatapackReloader.disabledIds().contains(DatapackReloader.packId(a)),
                Component.literal("re-enabling did not clear the disabled record, so the next disable of anything "
                        + "else would switch this one off again"));
        helper.assertTrue(DatapackReloader.disabledIds().contains(DatapackReloader.packId(b)),
                Component.literal("clearing one must not clear the other"));

        DatapackReloader.forgetDisabled(DatapackReloader.packId(b));
        helper.succeed();
    }

    /** A disable must never reach past our own packs into vanilla or mod content. */
    @GameTest(template = "empty_test", batch = "datapackDisable")
    public static void disablingNeverTouchesContentWeDidNotAuthor(GameTestHelper helper) {
        helper.assertTrue(DatapackReloader.isWishPack(DatapackReloader.packId("spikor_fire")),
                Component.literal("our own packs must be recognised as ours"));
        helper.assertTrue(!DatapackReloader.isWishPack("vanilla"),
                Component.literal("vanilla is not ours — #2 is explicit that only wish-authored packs may be "
                        + "considered, and a safeguard that switched off vanilla would break the "
                        + "game rather than the wish"));
        helper.assertTrue(!DatapackReloader.isWishPack("mod:create"),
                Component.literal("a mod's built-in pack is not ours either"));
        helper.succeed();
    }

    /** A minimal valid datapack zip in this build's format, written straight into the world folder. */
    private static void writePack(GameTestHelper helper, String featureId) throws java.io.IOException {
        java.nio.file.Path dir = DatapackReloader.worldDatapackDir(helper.getLevel().getServer());
        java.nio.file.Files.createDirectories(dir);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("pack.mcmeta"));
            zip.write(DatapackDialect.mcmeta("gt 1828").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        java.nio.file.Files.write(dir.resolve(featureId + ".zip"), bytes.toByteArray());
    }

    /**
     * <b>: a disable never switches off a pack the world was MADE with.</b> A SkyBlock world's three
     * packs are world-folder ({@code file/}) packs that no wish made. The first disable of any
     * wish pack rebuilt the selection from the store and kept only non-{@code file/} packs, so all three
     * went off, level.dat saved them as Disabled, and every boot after said they "stay off". Driven
     * through the real {@code disableAndReload} and the real reload: a pack the store does not hold
     * stays on while a sibling is switched off.
     */
    @GameTest(template = "empty_test", batch = "datapackDisableWorldPack", timeoutTicks = 1200)
    public static void aDisableKeepsThePacksTheWorldWasMadeWith(GameTestHelper helper) {
        long t = helper.getLevel().getGameTime();
        String world = "gt_1828_world_" + t;
        String wish = "gt_1828_wish_" + t;
        var server = helper.getLevel().getServer();
        try {
            writePack(helper, world);
            writePack(helper, wish);
        } catch (java.io.IOException e) {
            helper.fail(Component.literal("could not write the test packs: " + e));
            return;
        }
        helper.assertTrue(!DatapackStore.listIds().contains(world),
                Component.literal("the fixture's world pack is in the store, so this is not #1828's case"));
        DatapackReloader.enableAndReload(server, world);
        DatapackReloader.enableAndReload(server, wish);
        java.util.concurrent.atomic.AtomicBoolean disabled = new java.util.concurrent.atomic.AtomicBoolean();
        helper.succeedWhen(() -> {
            if (!disabled.get()) {
                helper.assertTrue(DatapackReloader.isEnabled(server, world) && DatapackReloader.isEnabled(server, wish),
                        Component.literal("the two packs are not both on yet"));
                helper.assertTrue(DatapackReloader.disableAndReload(server, wish),
                        Component.literal("the disable did not run"));
                disabled.set(true);
            }
            helper.assertTrue(!DatapackReloader.isEnabled(server, wish),
                    Component.literal("the pack being disabled is still on (reload not landed yet)"));
            helper.assertTrue(DatapackReloader.isEnabled(server, world),
                    Component.literal("#1828: disabling a wish pack switched off a pack the world was made with"));
            helper.assertTrue(!DatapackReloader.disabledIds().contains(DatapackReloader.packId(world)),
                    Component.literal("#1828: the world's own pack was recorded as switched off"));
            helper.assertTrue(DatapackReloader.worldPacksOff(server.getPackRepository().getAvailableIds(),
                            server.getPackRepository().getSelectedIds(), DatapackReloader.oursIds(server))
                            .stream().noneMatch(id -> id.equals(DatapackReloader.packId(world))),
                    Component.literal("the boot alert would name a world pack that is on"));
            tidy(server, world, wish);
        });
    }

    private static void tidy(net.minecraft.server.MinecraftServer server, String... ids) {
        for (String id : ids) {
            DatapackReloader.forgetDisabled(DatapackReloader.packId(id));
            try {
                java.nio.file.Files.deleteIfExists(DatapackReloader.worldDatapackDir(server).resolve(id + ".zip"));
            } catch (java.io.IOException ignored) {
                // a leftover test zip is harmless: it is not in the store, and nothing selects it
            }
        }
        server.getPackRepository().reload();
    }
}
